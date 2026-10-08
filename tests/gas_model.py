#!/usr/bin/env python3
"""Independent model for the `--audit` worst-case gas report. Reads the emitted bytecode (HOB) and finds, for every function, the most expensive
EXECUTION by exhaustive search: it follows the control flow like a machine would, counts the iterations of every `for` loop with the literal bounds
in its range type, and at every conditional jump and every unwind-pad/catch staging tries both outcomes. (The compiler's report instead folds
loops arithmetically; the two must agree.) Usage: gas_model.py file.hob -> lines `name value` (value `>=N` when a loop bound is not a literal)."""
import sys, re
from functools import lru_cache

ZERO = set("ALLOC ALLOC_STATIC ARG RETURNS FUNC_START FUNC_END FUNC_DECORATE STRUCT_START STRUCT_END STRUCT_MEMBER STRUCT_PADDING STRUCT_DECORATE ENUM STRING EXTERN GLOBAL CC_START CC_END VARARGS_XMM_COUNT PUSH_LABEL ASM_START ASM_END".split())
TWO = set("DEREF LOOKUP LOOKUP_LHS DOT LEN".split())
TEN = set("THROW EXIT EXIT_THREAD ATOMIC_SWAP ATOMIC_ASSIGN ATOMIC_PUSH STACK_LOCK SLEEP YIELD INVOKE".split())
TWENTY = set("MEMCOPY GT_REGISTER GT_ALIVE_CHECK GT_REF_ID GT_REF_RESOLVE".split())
THIRTY = set("GT_DESTRUCT GT_DESTRUCT_ADDR GT_DESTRUCT_TAIL GT_MOVED".split())
HUNDRED = set("NEW NEW_DYN NEW_UDYN NEW_FROM_STRING NEW_FROM_USTRING RESIZE URESIZE CLONE CLONE_FILL CLONE_DYN GT_INIT".split())


def op_cost(op):
    if op in ZERO: return 0
    if op in TWO: return 2
    if op == "MUL": return 3
    if op in ("DIV", "MOD"): return 20
    if op == "CALL": return 5
    if op in TEN: return 10
    if op in TWENTY: return 20
    if op in THIRTY: return 30
    if op == "EXTERN_CALL": return 50
    if op in HUNDRED: return 100
    return 1


def parse(path):
    fns, cur = {}, None
    for raw in open(path):
        l = raw.strip()
        if not l: continue
        if l.startswith("FUNC_START "):
            cur = {"name": l.split()[1], "lines": [], "labels": {}}
            fns[cur["name"]] = cur
        elif l == "FUNC_END":
            cur = None
        elif cur is not None:
            if l.startswith("@") and l.endswith(":"):
                cur["labels"][l[:-1]] = len(cur["lines"])
            cur["lines"].append(l)
    return fns


def loop_bounds(fn):
    """label -> iteration count (None = not a literal), for every `for` header."""
    out = {}
    L = fn["lines"]
    for lab, i in fn["labels"].items():
        if re.fullmatch(r"@for_\d+", lab):
            n = None
            for j in range(i + 1, min(i + 6, len(L))):
                if L[j].startswith("PUSH $for_range_"):
                    m = re.search(r"range\((-?\d+),(-?\d+)\)$", L[j].split()[-1])
                    if m: n = max(0, int(m.group(2)) - int(m.group(1)))
                    break
            out[lab] = n
    return out


class Budget(Exception):
    pass


LIM = 1 << 63


def tokval(tok, env):
    """Value of a pushed token under the variable values `env` (None = unknown)."""
    if tok.isdigit():
        return int(tok)
    if tok.endswith(".start") or tok.endswith(".end"):
        base, _, fld = tok.rpartition(".")
        v = env.get(base)
        return v[0 if fld == "start" else 1] if isinstance(v, tuple) else None
    return env.get(tok)


def params_of(fn):
    out = []
    for l in fn["lines"]:
        if l.startswith("ARG "):
            p = l.split()
            out.append((p[1], bool(re.fullmatch(r"(imut_|mut_)?range", p[2]))))
        elif l.startswith("ALLOC "):
            break
    return out


def bind(fn, slots):
    """Parameter values of a call: the slot values (in the order they were popped) laid over the callee's parameters (a range takes two slots)."""
    ps = params_of(fn)
    if sum(2 if r else 1 for _, r in ps) != len(slots):
        return ()
    out, k = [], 0
    for name, r in ps:
        if r:
            lo, hi = slots[k], slots[k + 1]
            k += 2
            if isinstance(lo, int) and isinstance(hi, int):
                out.append((name, (lo, hi)))
        else:
            v = slots[k]
            k += 1
            if isinstance(v, int):
                out.append((name, v))
    return tuple(sorted(out))


NARROW = re.compile(r".*[us](8|16|32)$")


def worst(fn, callee, flags, cost, varn=1, args=()):
    """Most expensive execution by exhaustive search. The search also executes the integer assignments it passes (a concrete interpreter over the
    bytecode: literals, variables, ADD SUB MUL INC DEC; everything else is unknown) so that the bound of a `for` whose range is not literal in its type
    is read from the values the path really has, starting from the parameter values `args`. callee(name, slots) -> that callee's figure under those
    argument values; cost(op, pc, env) -> the price of one operation."""
    L, labels = fn["lines"], fn["labels"]
    static = loop_bounds(fn)
    if any(re.fullmatch(r"@loop_\d+", lab) for lab in labels):
        flags.add("loop")
    taken = {t for l in L if l.startswith("ADDR_OF ") for t in l.split()[1:]}
    memo = {}

    def run(pc, counters, env):
        key = (pc, counters, env)
        if key in memo: return memo[key]
        if len(memo) > 300000: raise Budget()
        memo[key] = 0   # cycle guard (only `loop` bodies can cycle; they are excluded from comparison)
        cnt = dict(counters)
        e = dict(env)
        stk, frames, pend, poisoned = [], [], None, False

        def pop():
            return stk.pop() if stk else None

        total = 0
        while True:
            if pc >= len(L): break
            l = L[pc]
            p = l.split()
            op = p[0]
            if l.startswith("@"):
                pc += 1
                continue
            if op == "CALL":
                slots = frames[-1] if frames else []
                total += cost("CALL", pc, e) + callee(p[1], slots)
                pc += 1
                continue
            total += cost(op, pc, e)
            # ---- the interpreter part
            if op == "PUSH":
                stk.append(tokval(p[1], e))
            elif op in ("ADD", "SUB", "MUL"):
                y, x = pop(), pop()
                r = None
                if isinstance(x, int) and isinstance(y, int) and not any(NARROW.match(t) for t in p[1:]):
                    r = x + y if op == "ADD" else x - y if op == "SUB" else x * y
                    r = r if 0 <= r < LIM else None
                stk.append(r)
            elif op in ("INC", "DEC"):
                x = pop()
                r = None
                if isinstance(x, int) and not any(NARROW.match(t) for t in p[1:]):
                    r = x + (1 if op == "INC" else -1)
                    r = r if 0 <= r < LIM else None
                stk.append(r)
            elif op == "ADDR":
                pend, stk, poisoned = p[1], [], False
            elif op == "ASSIGN":
                if pend is not None:
                    v = None
                    if not poisoned:
                        if len(stk) == 2 and all(isinstance(t, int) for t in stk):
                            v = tuple(stk)
                        elif len(stk) == 1 and stk[0] is not None:
                            v = stk[0]
                    if v is None or pend in taken: e.pop(pend, None)
                    else: e[pend] = v
                pend, stk, poisoned = None, [], False
            elif op == "CC_START":
                frames.append([])
            elif op == "CC_END":
                if frames: frames.pop()
            elif op == "POP" and p[1].startswith("ARG"):
                v = pop()
                if frames: frames[-1].append(v)
            elif op == "PUSH_RET":
                stk.append(None)
            elif op in ("CMP", "JMP", "RET", "PUSH_LABEL", "GT_DESTRUCT", "GT_REGISTER", "ALLOC", "ARG", "RETURNS", "FUNC_DECORATE", "CC_START", "CC_END"):
                pass
            else:
                if pend is not None: poisoned = True
                stk.clear()
                stk.append(None)
            if op in ("RET", "THROW", "EXIT", "EXIT_THREAD"):
                break
            if op == "PUSH_LABEL":
                tgt = p[1]
                if tgt in labels and not re.match(r"@(for|loop)", tgt):
                    alt = run(labels[tgt], tuple(sorted(cnt.items())), tuple(sorted(e.items())))
                    rest = run(pc + 1, tuple(sorted(cnt.items())), tuple(sorted(e.items())))
                    total += max(alt, rest)
                    break
            if op == "JMP":
                tgt = p[1]
                cond = pc > 0 and L[pc - 1] == "CMP"
                m = re.fullmatch(r"@for_end_(\d+)", tgt)
                if cond and m and ("@for_%d" % (int(m.group(1)) - 1)) in static:
                    head = "@for_%d" % (int(m.group(1)) - 1)
                    lim = static[head]
                    if lim is None:
                        lk = head + "#"
                        if lk in cnt:
                            lim = cnt[lk]
                        else:
                            rv = None
                            for j in range(labels[head] + 1, min(labels[head] + 6, len(L))):
                                if L[j].startswith("PUSH $for_range_"):
                                    rv = e.get(L[j].split()[1])
                                    break
                            if isinstance(rv, tuple):
                                lim = max(0, rv[1] - rv[0])
                            else:
                                flags.add("var")
                                lim = varn   # lower bound: `varn` iterations
                            cnt[lk] = lim
                    c = cnt.get(head, 0)
                    if c < lim:
                        cnt[head] = c + 1
                        pc += 1
                    else:
                        cnt[head] = 0
                        cnt.pop(head + "#", None)
                        pc = labels[tgt]
                    continue
                if cond:
                    a = run(labels[tgt], tuple(sorted(cnt.items())), tuple(sorted(e.items())))
                    b = run(pc + 1, tuple(sorted(cnt.items())), tuple(sorted(e.items())))
                    total += max(a, b)
                    break
                pc = labels[tgt]
                continue
            pc += 1
        memo[key] = total
        return total

    return run(0, (), tuple(sorted(args)))


ALLOC_OPS = set("NEW NEW_DYN NEW_UDYN NEW_FROM_STRING NEW_FROM_USTRING RESIZE URESIZE CLONE CLONE_DYN".split())


def heap_cost(op, pc=None, env=None):
    return 1 if op in ALLOC_OPS else 0


def gas_cost(op, pc=None, env=None):
    return op_cost(op)


def struct_sizes(path):
    sizes, cur, tot = {}, None, 0
    for raw in open(path):
        l = raw.strip()
        if l.startswith("STRUCT_START "):
            cur, tot = l.split()[1], 0
        elif cur and l.startswith("STRUCT_MEMBER "):
            tot += -(-size_of(l.split()[-1], sizes) // 8) * 8
        elif cur and l.startswith("STRUCT_PADDING "):
            tot += int(l.split()[1])
        elif l == "STRUCT_END" and cur:
            sizes[cur] = max(8, -(-tot // 8) * 8)
            cur = None
    return sizes


def size_of(t, sizes):
    t = re.sub(r"^((mut|imut|indeterminate)_)+", "", t)
    if re.match(r"(owns|ref|raw|auto|static)_", t) or t == "code_addr": return 8
    if t.startswith("range"): return 16
    if t.startswith("dynarray("): return 8
    m = re.fullmatch(r"(.*)\[(\d+)\]", t)
    if m: return int(m.group(2)) * (-(-size_of(m.group(1), sizes) // 8) * 8)
    m = re.fullmatch(r"[us](\d+)", t)
    if m: return max(1, int(m.group(1)) // 8)
    if t in ("bool", "char"): return 1
    if t == "f32": return 4
    if t == "f64": return 8
    if t == "void": return 0
    if t.startswith("atomic_"): return 8
    return sizes.get(t, 8)


def frame_of(fn, sizes):
    total = 16
    for l in fn["lines"]:
        if l.startswith(("ALLOC ", "ARG ", "ALLOC_STATIC ")):
            total += -(-size_of(l.split()[-1], sizes) // 8) * 8
    return total + 64 + 8 * -(-(len(fn["lines"]) + 2) // 24)   # FUNC_START and FUNC_END lines count too


def members_of(path):
    out, cur = {}, None
    for raw in open(path):
        l = raw.strip()
        if l.startswith("STRUCT_START "):
            cur = l.split()[1]; out[cur] = []
        elif cur and l.startswith("STRUCT_MEMBER "):
            out[cur].append(l.split()[-1])
        elif l == "STRUCT_END":
            cur = None
    return out


def strings_of(path):
    out = {}
    for raw in open(path):
        m = re.match(r'STRING (\S+) "(.*)"\s*$', raw.rstrip("\n"))
        if m: out[m.group(1)] = m.group(2)
    return out


def dyn_elem(t):
    i = t.find("dynarray(")
    if i < 0: return None
    j, depth = i + 9, 1
    while depth:
        depth += {"(": 1, ")": -1}.get(t[j], 0); j += 1
    return t[i + 9:j - 1]


def pointee(t):
    t = re.sub(r"^((mut|imut|indeterminate)_)+", "", t)
    m = re.match(r"(owns|ref|raw|auto)_(some_)?(.*)", t)
    return re.sub(r"^((mut|imut|indeterminate)_)+", "", m.group(3)) if m else None


def deep(t, sizes, members, d=0):
    if d > 8 or dyn_elem(t) is not None: return None
    tot = -(-size_of(t, sizes) // 8) * 8
    for m in members.get(t, []):
        if "owns_" in m:
            pt = pointee(m)
            r = None if pt is None or "dynarray" in m else deep(pt, sizes, members, d + 1)
            if r is None: return None
            tot += r
    return tot


def unescape_len(text):
    n, i = 0, 0
    while i < len(text):
        if text[i] == "\\":
            if i + 1 < len(text) and text[i + 1] in "ntr0\\\"'": i += 1
            else: return None
        n += len(text[i].encode()); i += 1
    return n


def bytes_cost_fn(fn, sizes, members, strs, unk):
    """Returns cost(op, pc, env): the bytes an allocation operation requests, the size read from the values the path has when a count is a variable."""
    L = fn["lines"]
    el = lambda t: -(-(size_of(dyn_elem(t), sizes) if dyn_elem(t) else 8) // 8) * 8

    def cost(op, pc, env):
        if op not in ALLOC_OPS:
            return 0
        p = L[pc].split()
        o = p[0]
        tok = lambda j: (L[j].split()[1] if j >= 0 and L[j].startswith("PUSH ") else None)
        val = lambda j: (tokval(tok(j), env) if tok(j) is not None else None)
        if o == "NEW": return -(-size_of(p[1], sizes) // 8) * 8
        if o == "NEW_DYN": return 16 + el(p[1]) * int(p[-1])
        if o == "NEW_UDYN": return max(1, el(p[1]) * int(p[-1]))
        if o in ("NEW_FROM_STRING", "NEW_FROM_USTRING"):
            sid = L[pc - 1].split()[1] if pc > 0 and L[pc - 1].startswith("PUSH string_id") else None
            n = unescape_len(strs[sid]) if sid in strs else None
            if n is None: unk.add(pc); return 16
            return 16 + n if o == "NEW_FROM_STRING" and "unsafe_dynarray" not in p[1] else max(1, n + 1)
        if o == "RESIZE":
            n = val(pc - 2)
            if not isinstance(n, int) or tok(pc - 1) is None: unk.add(pc); return 16
            return 16 + el(p[1]) * n
        if o == "URESIZE":
            n = val(pc - 1)
            if not isinstance(n, int): unk.add(pc); return 1
            return max(1, el(p[1]) * n)
        if o == "CLONE":
            pt = pointee(p[1])
            c = None if pt is None or "dynarray" in p[1] else deep(pt, sizes, members)
            if c is None: unk.add(pc); return 16
            return c
        unk.add(pc)
        return 16

    return cost


def main():
    fns = parse(sys.argv[1])
    sizes = struct_sizes(sys.argv[1])
    members = members_of(sys.argv[1])
    strs = strings_of(sys.argv[1])
    R, active = {}, set()
    ZEROREC = {"gas": 0, "h1": 0, "h2": 0, "b1": 0, "b2": 0, "flags": set(), "unk": False}

    def solve(n, args=()):
        key = (n, args)
        if key in R: return R[key]
        if key in active: return ZEROREC
        active.add(key)
        f = fns[n]
        fl, unk = set(), set()
        bcost = bytes_cost_fn(f, sizes, members, strs, unk)
        children_unk = [False]

        def sub(metric):
            def callee(c, slots):
                if c not in fns: return 0
                base = solve(c, ())
                rec = base
                if (base["flags"] or base["unk"]) and len(R) < 3000:
                    rec = solve(c, bind(fns[c], slots))
                if metric == "gas": fl.update(rec["flags"])
                if metric == "b1" and rec["unk"]: children_unk[0] = True
                return rec[metric]
            return callee
        rec = dict(ZEROREC)
        try:
            rec["gas"] = worst(f, sub("gas"), fl, gas_cost, 1, args)
            rec["h1"] = worst(f, sub("h1"), set(), heap_cost, 1, args)
            rec["h2"] = worst(f, sub("h2"), set(), heap_cost, 2, args)
            rec["b1"] = worst(f, sub("b1"), set(), bcost, 1, args)
            rec["b2"] = worst(f, sub("b2"), set(), bcost, 2, args)
        except Budget:
            fl.add("skip")
            rec.update(gas=0, h1=0, h2=0, b1=0, b2=0)
        rec["flags"] = fl
        rec["unk"] = bool(unk) or children_unk[0]
        R[key] = rec
        active.discard(key)
        return rec

    base = {}
    for n in fns:
        base[n] = solve(n, ())
    gas = {n: base[n]["gas"] for n in fns}
    flags = {n: base[n]["flags"] for n in fns}
    heap1 = {n: base[n]["h1"] for n in fns}
    heap2 = {n: base[n]["h2"] for n in fns}
    by1 = {n: base[n]["b1"] for n in fns}
    by2 = {n: base[n]["b2"] for n in fns}
    unkb = {n: base[n]["unk"] for n in fns}
    # stack depth: longest call chain over the frame estimates
    depth, unb = {}, {}

    def stack(n, path=()):
        if n in depth: return
        f = fns[n]
        best, u = 0, set()
        for l in f["lines"]:
            if l.startswith("CALL "):
                c = l.split()[1]
                if c not in fns: continue
                if c in path or c == n:
                    u.add("rec")
                    continue
                stack(c, path + (n,))
                best = max(best, depth[c])
                if unb[c]: u.add("callee")
            elif l.startswith("INVOKE"):
                u.add("indirect")
        depth[n] = frame_of(f, sizes) + best
        unb[n] = u

    for n in fns:
        stack(n)
    for n in fns:
        cu = heap1[n] != heap2[n] and "loop" not in flags[n]
        bu = (by1[n] != by2[n] or unkb[n]) and "loop" not in flags[n]
        print(n, gas[n], ",".join(sorted(flags[n])) or "-", heap1[n], "U" if cu else "-", by1[n], "U" if bu else "-", depth[n], "U" if unb[n] else "-")


if __name__ == "__main__":
    import threading
    sys.setrecursionlimit(1000000)
    threading.stack_size(1 << 29)
    t = threading.Thread(target=main)
    t.start()
    t.join()

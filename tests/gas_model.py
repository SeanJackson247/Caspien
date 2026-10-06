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
TWENTY = set("MEMCOPY GT_REGISTER GT_ALIVE_CHECK".split())
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


def worst(fn, gas, flags, cost=op_cost, varn=1):
    L, labels = fn["lines"], fn["labels"]
    bounds = loop_bounds(fn)
    for lab, n in bounds.items():
        if n is None:
            flags.add("var")
            bounds[lab] = varn   # lower bound: `varn` iterations
    if any(re.fullmatch(r"@loop_\d+", lab) for lab in labels):
        flags.add("loop")
    exit_of = {int(re.search(r"\d+", lab).group()) + 1: lab for lab in bounds}   # for_end_<n+1> belongs to for_<n>
    memo = {}

    def run(pc, counters):
        key = (pc, counters)
        if key in memo: return memo[key]
        if len(memo) > 200000: raise Budget()
        memo[key] = 0   # cycle guard (only `loop` bodies can cycle; they are excluded from comparison)
        cnt = dict(counters)
        total = 0
        while True:
            if pc >= len(L): break
            l = L[pc]
            op = l.split()[0]
            if l.startswith("@"):
                pc += 1
                continue
            if op == "CALL":
                g = gas.get(l.split()[1])
                total += (cost("CALL", pc) if cost.__code__.co_argcount == 2 else cost("CALL")) + (g or 0)
                pc += 1
                continue
            total += cost(op, pc) if cost.__code__.co_argcount == 2 else cost(op)
            if op in ("RET", "THROW", "EXIT", "EXIT_THREAD"):
                break
            if op == "PUSH_LABEL":
                tgt = l.split()[1]
                if tgt in labels and not re.match(r"@(for|loop)", tgt):
                    alt = run(labels[tgt], tuple(sorted(cnt.items())))
                    rest = run(pc + 1, tuple(sorted(cnt.items())))
                    total += max(alt, rest)
                    break
            if op == "JMP":
                tgt = l.split()[1]
                cond = pc > 0 and L[pc - 1] == "CMP"
                m = re.fullmatch(r"@for_end_(\d+)", tgt)
                if cond and m and ("@for_%d" % (int(m.group(1)) - 1)) in bounds:
                    head = "@for_%d" % (int(m.group(1)) - 1)
                    c = cnt.get(head, 0)
                    if c < bounds[head]:
                        cnt[head] = c + 1
                        pc += 1
                    else:
                        cnt[head] = 0
                        pc = labels[tgt]
                    continue
                if cond:
                    a = run(labels[tgt], tuple(sorted(cnt.items())))
                    b = run(pc + 1, tuple(sorted(cnt.items())))
                    total += max(a, b)
                    break
                pc = labels[tgt]
                continue
            pc += 1
        memo[key] = total
        return total

    return run(0, ())


ALLOC_OPS = set("NEW NEW_DYN NEW_UDYN NEW_FROM_STRING NEW_FROM_USTRING RESIZE URESIZE CLONE CLONE_DYN".split())


def heap_cost(op):
    return 1 if op in ALLOC_OPS else 0


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
    """Returns cost(op) usable by worst(): needs the line, so worst() is given the whole function and a per-index cost map instead."""
    L = fn["lines"]
    out = {}
    for i, l in enumerate(L):
        p = l.split()
        if not p or p[0] not in ALLOC_OPS: continue
        o = p[0]
        el = lambda t: -(-(size_of(dyn_elem(t), sizes) if dyn_elem(t) else 8) // 8) * 8
        lit = lambda j: int(L[j].split()[1]) if j >= 0 and L[j].startswith("PUSH ") and L[j].split()[1].isdigit() else None
        if o == "NEW": c = -(-size_of(p[1], sizes) // 8) * 8
        elif o == "NEW_DYN": c = 16 + el(p[1]) * int(p[-1])
        elif o == "NEW_UDYN": c = max(1, el(p[1]) * int(p[-1]))
        elif o in ("NEW_FROM_STRING", "NEW_FROM_USTRING"):
            sid = L[i - 1].split()[1] if i > 0 and L[i - 1].startswith("PUSH string_id") else None
            n = unescape_len(strs[sid]) if sid in strs else None
            if n is None: unk.add(i); c = 16
            else: c = 16 + n if o == "NEW_FROM_STRING" else max(1, n + 1)
        elif o == "RESIZE":
            n = lit(i - 2)
            if n is None or not L[i - 1].startswith("PUSH "): unk.add(i); c = 16
            else: c = 16 + el(p[1]) * n
        elif o == "URESIZE":
            n = lit(i - 1)
            if n is None: unk.add(i); c = 1
            else: c = max(1, el(p[1]) * n)
        elif o == "CLONE":
            pt = pointee(p[1])
            c = None if pt is None or "dynarray" in p[1] else deep(pt, sizes, members)
            if c is None: unk.add(i); c = 16
        else:
            unk.add(i); c = 16
        out[i] = c
    return out


def main():
    fns = parse(sys.argv[1])
    sizes = struct_sizes(sys.argv[1])
    members = members_of(sys.argv[1])
    strs = strings_of(sys.argv[1])
    gas, flags, heap1, heap2, by1, by2, unkb = {}, {}, {}, {}, {}, {}, {}

    def solve(n, stack=()):
        if n in gas: return
        if n in stack: return
        f = fns[n]
        fl = set()
        ub = False
        for l in f["lines"]:
            if l.startswith("CALL "):
                c = l.split()[1]
                if c in fns:
                    solve(c, stack + (n,))
                    fl |= flags.get(c, set())
                    ub = ub or unkb.get(c, False)
        flags[n] = fl
        unk = set()
        costmap = bytes_cost_fn(f, sizes, members, strs, unk)
        bcost = lambda op, pc: costmap.get(pc, 0) if op in ALLOC_OPS else 0
        try:
            gas[n] = worst(f, gas, fl)
            heap1[n] = worst(f, heap1, set(), heap_cost, 1)
            heap2[n] = worst(f, heap2, set(), heap_cost, 2)
            by1[n] = worst(f, by1, set(), bcost, 1)
            by2[n] = worst(f, by2, set(), bcost, 2)
        except Budget:
            fl.add("skip")
            gas[n] = heap1[n] = heap2[n] = by1[n] = by2[n] = 0
        unkb[n] = ub or bool(unk)

    for n in fns:
        solve(n)
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

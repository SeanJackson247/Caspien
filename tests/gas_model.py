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


def worst(fn, gas, flags):
    L, labels = fn["lines"], fn["labels"]
    bounds = loop_bounds(fn)
    for lab, n in bounds.items():
        if n is None:
            flags.add("var")
            bounds[lab] = 1   # lower bound: one iteration
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
                total += 5 + (g or 0)
                pc += 1
                continue
            total += op_cost(op)
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


def main():
    fns = parse(sys.argv[1])
    gas, flags = {}, {}

    def solve(n, stack=()):
        if n in gas: return
        if n in stack: return
        f = fns[n]
        fl = set()
        for l in f["lines"]:
            if l.startswith("CALL "):
                c = l.split()[1]
                if c in fns:
                    solve(c, stack + (n,))
                    fl |= flags.get(c, set())
        flags[n] = fl
        try:
            gas[n] = worst(f, gas, fl)
        except Budget:
            fl.add("skip")
            gas[n] = 0

    for n in fns:
        solve(n)
    for n in fns:
        print(n, ("" if not flags[n] - {"loop"} and "loop" not in flags[n] else "") + str(gas[n]), ",".join(sorted(flags[n])) or "-")


if __name__ == "__main__":
    import threading
    sys.setrecursionlimit(1000000)
    threading.stack_size(1 << 29)
    t = threading.Thread(target=main)
    t.start()
    t.join()

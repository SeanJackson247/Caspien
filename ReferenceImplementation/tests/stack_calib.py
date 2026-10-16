#!/usr/bin/env python3
"""tests/stack_check.sh helper: the stack-frame estimate of `--audit` (frame_of in gas_model.py) must never be below the REAL frame of the compiled
function: 16 (return address + frame pointer) + the `subq $N, %rsp` of the prologue + 8 per callee-saved register pushed right after it.
Usage: stack_calib.py file.s file.hob -> prints `ok N functions` or FAIL lines (exit 1)."""
import re, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gas_model as M

asm, hob = sys.argv[1], sys.argv[2]
fns = M.parse(hob)
sizes = M.struct_sizes(hob)
lines = open(asm).read().split("\n")
real, i = {}, 0
while i < len(lines):
    m = re.match(r"^(\w+):\s*$", lines[i])
    if m and m.group(1) in fns and m.group(1) not in real:
        name, total = m.group(1), 16
        for l in lines[i + 1:i + 20]:
            mm = re.match(r"\s*subq \$(\d+), %rsp", l)
            if mm:
                total += int(mm.group(1))
            elif re.match(r"\s*pushq %(r1[2-5]|rbx)\b", l):
                total += 8
        real[name] = total
    i += 1
bad = 0
for n, r in real.items():
    e = M.frame_of(fns[n], sizes)
    if e < r:
        print("FAIL: %s: estimate %d < real frame %d" % (n, e, r))
        bad += 1
if bad or not real:
    sys.exit(1)
print("ok %d functions" % len(real))

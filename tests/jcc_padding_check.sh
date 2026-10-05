#!/bin/bash
# (needs Linux, java, gcc, as >= 2.34, objdump, python3) `jcc-padding` (codegen section of toolchain.config): the Compiler adds as -mbranches-within-32B-boundaries.
# tests/narrow_branch_test.caspien (many compares and branches) in a Linux scratch copy, everything on:
#  1. jcc-padding on: output equals tests/narrow_branch_test.py; in the object file NO jmp/jcc crosses or ends on a 32-byte boundary;
#  2. jcc-padding off: same output, but such branches exist (so the check can see them);
#  3. through the stage cache: on -> off -> on gives a different binary for off and the identical binary again for on (the flag is part of stage 5's key);
#  4. `jcc-padding maybe` is a config error.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 tests/narrow_branch_test.py > exp.txt
setcfg() { # jcc value
python3 - "$1" <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
d = dict(B.FULL); d.pop("jcc-padding", None)
B.patch_config("toolchain.config", d)
import re
t = open("toolchain.config").read()
t = re.sub(r"^jcc-padding .*$", "jcc-padding " + sys.argv[1], t, flags=re.M)
open("toolchain.config", "w").write(t)
PY
}
viol() { # object file -> number of jump instructions (jmp, jcc; as does not pad call/ret by default) that cross or end on a 32-byte boundary
objdump -d --no-show-raw-insn "$1" | python3 -c '
import re, sys
rows = []
for l in sys.stdin:
    m = re.match(r"\s*([0-9a-f]+):\s+(\S+)", l)
    if m: rows.append((int(m.group(1), 16), m.group(2)))
bad = 0
for i in range(len(rows) - 1):
    a, mn = rows[i]
    end = rows[i + 1][0]
    if mn.startswith("j") and end > a and (a // 32 != (end - 1) // 32 or end % 32 == 0):
        bad += 1
print(bad)'
}
build() { # label value extra-args
setcfg "$2"
java Compiler -i tests/narrow_branch_test.caspien prog_$1 $3 >compile_$1.log 2>&1 || { echo "FAIL: compile ($1)"; tail -5 compile_$1.log; exit 1; }
./prog_$1 > out_$1.txt
cmp -s out_$1.txt exp.txt || { echo "FAIL: output differs from the model ($1)"; diff out_$1.txt exp.txt | head; exit 1; }
}
build on on --no-cache; von=$(viol .build/out.o)
build off off --no-cache; voff=$(viol .build/out.o)
[ "$von" = 0 ] || { echo "FAIL: jcc-padding on, but $von jumps cross or end on a 32-byte boundary"; exit 1; }
[ "$voff" -ge 5 ] || { echo "FAIL: jcc-padding off has only $voff boundary jumps, the check cannot tell the difference"; exit 1; }
rm -rf .cache
build c1 on ""; build c2 off ""; build c3 on ""
cmp -s prog_c1 prog_c3 || { echo "FAIL: on -> off -> on through the cache gave a different binary"; exit 1; }
cmp -s prog_c1 prog_c2 && { echo "FAIL: the cache returned the same binary for jcc-padding on and off"; exit 1; }
setcfg maybe
if java Compiler -i tests/narrow_branch_test.caspien prog_bad --no-cache >compile_bad.log 2>&1; then echo "FAIL: 'jcc-padding maybe' compiled"; exit 1; fi
grep -q "jcc-padding must be" compile_bad.log || { echo "FAIL: no config error for 'jcc-padding maybe'"; tail -3 compile_bad.log; exit 1; }
echo "PASS jcc_padding_check: output equals the model with the flag on and off; $von branches on a 32-byte boundary with it on, $voff with it off; the cache keeps the two apart; a bad value is a config error"

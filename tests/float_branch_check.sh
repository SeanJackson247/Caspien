#!/bin/bash
# (needs Linux, java, gcc, python3) tests/float_branch_test.caspien with everything on (benchmarks/nbody/bench.py FULL switches), Linux target:
# output equals tests/float_branch_test.py and the assembly has no `seta/setb/...` + `movzbq` + `testq` left behind a ucomis.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 - <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
B.patch_config("toolchain.config", B.FULL)
PY
java Compiler -i tests/float_branch_test.caspien prog --no-cache >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
./prog >out.txt
python3 tests/float_branch_test.py >exp.txt
cmp -s out.txt exp.txt || { echo "FAIL: output differs from the model"; diff out.txt exp.txt; exit 1; }
n=$(grep -A1 -E 'ucomis[sd]' .build/4_codegen.s | grep -cE 'set(e|ne|a|ae|b|be)\b')
[ "$n" = 0 ] || { echo "FAIL: $n setcc left behind a ucomis"; exit 1; }
echo "PASS float_branch_check: output equals the model, no setcc behind a float compare"

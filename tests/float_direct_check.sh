#!/bin/bash
# (needs Linux, java, gcc, python3) tests/float_direct_test.caspien with everything on (benchmarks/nbody/bench.py FULL switches), Linux target:
# output equals tests/float_direct_test.py, and the unrolled loop has far fewer `movaps` (3203 before the change, 1203 after).
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
java Compiler -i tests/float_direct_test.caspien prog --no-cache >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
./prog >out.txt
python3 tests/float_direct_test.py >exp.txt
cmp -s out.txt exp.txt || { echo "FAIL: output differs from the model"; diff out.txt exp.txt; exit 1; }
n=$(grep -c movaps .build/4_codegen.s)
[ "$n" -le 1300 ] || { echo "FAIL: $n movaps left"; exit 1; }
echo "PASS float_direct_check: output equals the model, $n movaps in the program"

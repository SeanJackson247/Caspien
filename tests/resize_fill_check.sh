#!/bin/bash
# (needs Linux, java, gcc, python3) tests/resize_fill_test.caspien with everything on (benchmarks/nbody/bench.py FULL switches), Linux target:
# output equals tests/resize_fill_test.py and the zero fill of a big `resize` is a `rep stosb`.
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
java Compiler -i tests/resize_fill_test.caspien prog --no-cache >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
./prog >out.txt
python3 tests/resize_fill_test.py >exp.txt
cmp -s out.txt exp.txt || { echo "FAIL: output differs from the model"; diff out.txt exp.txt; exit 1; }
grep -q "rep stosb" .build/4_codegen.s || { echo "FAIL: no rep stosb in the assembly"; exit 1; }
echo "PASS resize_fill_check: output equals the model, zero fills use rep stosb"

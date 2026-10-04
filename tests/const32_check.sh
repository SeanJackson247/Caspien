#!/bin/bash
# (needs Linux, java, gcc, python3) tests/const32_test.caspien with everything on (benchmarks/nbody/bench.py FULL switches), Linux target:
# output equals tests/const32_test.py, and the assembly has no `movabs $4294967295` / `movq $<imm in 2^31..2^32>` constant left (movl / self-move).
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
java Compiler -i tests/const32_test.caspien prog --no-cache >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
./prog >out.txt
python3 tests/const32_test.py >exp.txt
cmp -s out.txt exp.txt || { echo "FAIL: output differs from the model"; diff out.txt exp.txt; exit 1; }
n=$(grep -cE 'movq? \$(2147483648|3000000000|4294967295|4294967280),' .build/4_codegen.s)
[ "$n" = 0 ] || { echo "FAIL: $n 64-bit-immediate loads of a 32-bit-range constant left"; grep -nE 'movq? \$(2147483648|3000000000|4294967295|4294967280),' .build/4_codegen.s | head; exit 1; }
echo "PASS const32_check: output equals the model, no 64-bit load of a 32-bit constant left"

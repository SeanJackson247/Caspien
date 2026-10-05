#!/bin/bash
# (needs Linux, java, gcc, python3, valgrind) tests/generic_owning_dyn_test.caspien (a dynarray of a GENERIC struct with an `owns` member: growing
# resize, clone, shrinking resize, drop) in a Linux scratch copy under everything on / shipped / everything off; output must equal
# tests/generic_owning_dyn_test.py, valgrind: no error, no leak (shipped). Regression for the `Slot_u64` element name being split at its underscore.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 tests/generic_owning_dyn_test.py > exp.txt
run() { # label dict
python3 - "$2" <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
if sys.argv[1] != "SHIPPED":
    B.patch_config("toolchain.config", getattr(B, sys.argv[1]))
PY
java Compiler -i tests/generic_owning_dyn_test.caspien prog_$1 --no-cache >compile_$1.log 2>&1 || { echo "FAIL: compile ($1)"; tail -5 compile_$1.log; exit 1; }
./prog_$1 > out_$1.txt
cmp -s out_$1.txt exp.txt || { echo "FAIL: output differs from the model ($1)"; diff out_$1.txt exp.txt; exit 1; }
}
run shipped SHIPPED || exit 1
run on FULL || exit 1
run off OFF || exit 1
valgrind -q --error-exitcode=9 --leak-check=full --errors-for-leak-kinds=definite,indirect ./prog_shipped >/dev/null 2>vg.txt || { echo "FAIL: valgrind"; head -20 vg.txt; exit 1; }
echo "PASS generic_owning_dyn_check: output equals the model in 3 configs, valgrind clean"

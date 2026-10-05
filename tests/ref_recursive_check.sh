#!/bin/bash
# (needs Linux, java, gcc, python3, valgrind) tests/ref_recursive_test.caspien (structs linking to themselves through plain `ref`: list, tree, graph,
# Ping<->Pong, a generic struct, a dangling ref, growing a dynarray whose elements have a NULL `owns` member) in a Linux scratch copy: everything on
# (benchmarks/nbody/bench.py FULL), shipped, and everything off (OFF). Output must equal tests/ref_recursive_test.py each time, and valgrind must report no
# error and no leak on the shipped build. The six tests/ref_recursive_*_error_test.caspien files must still fail to compile (ref some, owns, raw, auto,
# plain, and a cycle through owns).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 tests/ref_recursive_test.py > exp.txt
run() { # label dict
python3 - "$2" <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
if sys.argv[1] != "SHIPPED":
    B.patch_config("toolchain.config", getattr(B, sys.argv[1]))
PY
java Compiler -i tests/ref_recursive_test.caspien prog_$1 --no-cache >compile_$1.log 2>&1 || { echo "FAIL: compile ($1)"; tail -5 compile_$1.log; exit 1; }
./prog_$1 > out_$1.txt
cmp -s out_$1.txt exp.txt || { echo "FAIL: output differs from the model ($1)"; diff out_$1.txt exp.txt; exit 1; }
}
run shipped SHIPPED || exit 1
run on FULL || exit 1
run off OFF || exit 1
valgrind -q --error-exitcode=9 --leak-check=full --errors-for-leak-kinds=definite,indirect ./prog_shipped >/dev/null 2>vg.txt || { echo "FAIL: valgrind"; head -20 vg.txt; exit 1; }
for k in some owns raw auto plain cycle; do
  if java Compiler -i tests/ref_recursive_${k}_error_test.caspien prog_err --no-cache >err_$k.log 2>&1; then echo "FAIL: ref_recursive_${k}_error_test compiled"; exit 1; fi
  grep -q "type error" err_$k.log || { echo "FAIL: ref_recursive_${k}_error_test failed for a reason other than a type error"; tail -3 err_$k.log; exit 1; }
done
echo "PASS ref_recursive_check: output equals the model in 3 configs, valgrind clean (no error, no leak); ref some / owns / raw / auto / plain / owns-cycle self-references are still rejected"

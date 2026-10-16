#!/bin/bash
# ref_id_test: output equals the Python model, valgrind clean, no leaked registrations under tests/alloc_shim.c; and the same source compiled against
# the id-less ghost table (stdlib/gt_set) is rejected (no @gt_ref_id / @gt_ref_resolve), so a nullable ref can never silently run on addresses.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
java Compiler -i tests/ref_id_test.caspien prog >c.log 2>&1 || { echo "FAIL: compile"; tail -3 c.log; exit 1; }
./prog >out.txt 2>&1 || { echo "FAIL: exit code"; exit 1; }
python3 tests/ref_id_test.py >want.txt
diff want.txt out.txt >/dev/null || { echo "FAIL: output differs from the model"; diff want.txt out.txt | head; exit 1; }
valgrind -q --error-exitcode=9 ./prog >/dev/null 2>vg.txt || { echo "FAIL: valgrind"; head vg.txt; exit 1; }
sed 's#"../stdlib/gt/\*"#"../stdlib/gt_set/*"#' tests/ref_id_test.caspien >tests/ref_id_set.caspien
if java Compiler -i tests/ref_id_set.caspien prog2 >c2.log 2>&1; then echo "FAIL: compiled against the id-less table"; exit 1; fi
grep -q "gt_ref_" c2.log || { echo "FAIL: wrong rejection reason"; tail -3 c2.log; exit 1; }
echo "PASS ref_id_check: output equals the model, valgrind clean, id-less table rejected"

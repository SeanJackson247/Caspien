#!/bin/bash
# (needs Linux, java, gcc, python3, valgrind) The exhaustive BigInt test: tests/gen_bigint_test.py full writes about 7000 cases (expected values from Python
# integers) which are compiled in a scratch copy with a Linux target (shipped switches, and everything-off) and must print only PASS lines (36 categories),
# valgrind clean. tests/bigint_test.caspien is the small version of the same cases that the 4-config sweep runs (the aggressive inliner needs far too
# long for the big one).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export CASPIEN_STDLIB="$ROOT/../stdlib"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 tests/gen_bigint_test.py full tests/bigint_full_test.caspien || { echo "FAIL: generator"; exit 1; }
java Compiler -i tests/bigint_full_test.caspien full >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
valgrind -q --leak-check=full --errors-for-leak-kinds=definite --error-exitcode=9 ./full >out.txt 2>vg.txt; rc=$?
bad=0
grep -q FAIL out.txt && { echo "FAIL: $(grep FAIL out.txt | head -3)"; bad=1; }
[ "$(grep -c PASS out.txt)" = "36" ] || { echo "FAIL: expected 36 PASS lines, got $(grep -c PASS out.txt)"; bad=1; }
[ $rc -eq 0 ] || { echo "FAIL: valgrind rc=$rc"; head -15 vg.txt; bad=1; }
[ $bad -eq 0 ] && echo "PASS bigint_check: 36 categories, about 7000 cases equal Python integers, valgrind clean"
exit $bad

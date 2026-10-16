#!/bin/bash
# Runs tests/owns_assign_ptr_test.caspien (needs Linux, java, gcc, valgrind) in a scratch copy of the repo with a Linux target:
# every line must be PASS, and valgrind must report no definitely-lost block and no invalid read/free (the old order freed the
# value before the right side used it).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
java Compiler -i tests/owns_assign_ptr_test.caspien prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
valgrind -q --leak-check=full --errors-for-leak-kinds=definite --error-exitcode=9 ./prog >out.txt 2>vg.txt; rc=$?
grep -q FAIL out.txt && { echo "FAIL: $(grep FAIL out.txt)"; exit 1; }
[ "$(grep -c PASS out.txt)" = 10 ] || { echo "FAIL: expected 10 PASS lines"; cat out.txt; exit 1; }
[ $rc -eq 0 ] || { echo "FAIL: valgrind rc=$rc"; head -20 vg.txt; exit 1; }
echo "PASS owns_assign_ptr_check: 10 checks, valgrind clean"

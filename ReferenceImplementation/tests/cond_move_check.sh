#!/bin/bash
# Runs tests/cond_move_test.caspien (needs Linux, java, gcc, valgrind) in a scratch copy of the repo with a Linux target: every line
# must be PASS (the ghost table's entry count returns to its starting value after each call, whether or not the owns local was
# moved on that path) and valgrind must report no definite leak and no invalid access. Valgrind alone cannot catch the bug this
# guards: the ghost table keeps every registered block reachable, so an unfreed block is "still reachable", never "definitely lost".
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
java Compiler -i tests/cond_move_test.caspien prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
valgrind -q --leak-check=full --errors-for-leak-kinds=definite --error-exitcode=9 ./prog >out.txt 2>vg.txt; rc=$?
grep -q FAIL out.txt && { echo "FAIL: $(grep FAIL out.txt | head -5)"; exit 1; }
[ "$(grep -c PASS out.txt)" = 16 ] || { echo "FAIL: expected 16 PASS lines"; cat out.txt; exit 1; }
[ $rc -eq 0 ] || { echo "FAIL: valgrind rc=$rc"; head -20 vg.txt; exit 1; }
echo "PASS cond_move_check: 16 checks, no leaked ghost-table entry, valgrind clean"

#!/bin/bash
# Runs tests/dyn_growth_test.caspien (needs Linux, java, gcc, valgrind) in a scratch copy with a Linux target: every line must be PASS,
# valgrind must be clean, and the realloc calls counted by tests/alloc_shim.c must stay amortised (the test makes 101000 pushes and 99990
# pops; one realloc per push would be over 100000 calls, doubling needs about 40).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp tests/alloc_shim.c shim.c && gcc -shared -fPIC -o shim.so shim.c -ldl || { echo "FAIL: cannot build shim"; exit 1; }
java Compiler -i tests/dyn_growth_test.caspien prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
LD_PRELOAD=./shim.so ./prog >out.txt 2>err.txt
grep -q FAIL out.txt && { echo "FAIL: $(grep FAIL out.txt)"; exit 1; }
[ "$(grep -c PASS out.txt)" = 12 ] || { echo "FAIL: expected 12 PASS lines"; cat out.txt; exit 1; }
re=$(sed -n 's/.*REALLOCS=\([0-9]*\).*/\1/p' err.txt)
[ -n "$re" ] && [ "$re" -lt 200 ] || { echo "FAIL: $re realloc calls (expected under 200)"; exit 1; }
valgrind -q --leak-check=full --errors-for-leak-kinds=definite --error-exitcode=9 ./prog >out2.txt 2>vg.txt || { echo "FAIL: valgrind"; head -20 vg.txt; exit 1; }
echo "PASS dyn_growth_check: 12 checks, $re reallocs, valgrind clean"

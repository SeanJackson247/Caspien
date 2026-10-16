#!/bin/bash
# Finished `par` threads must be reclaimed (needs Linux, java, gcc, valgrind): runs tests/par_detach_test.caspien under valgrind
# and requires the right results and no lost blocks (an undetached, never-joined thread leaves its thread block behind).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
java Compiler -i tests/par_detach_test.caspien pd_prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
valgrind --leak-check=full -q ./pd_prog >out.txt 2>vg.txt
grep -q "PASS par_detach: 40 results correct" out.txt || { echo "FAIL: output: $(cat out.txt)"; exit 1; }
if grep -q "lost" vg.txt; then echo "FAIL: lost blocks:"; grep "lost" vg.txt | head -5; exit 1; fi
echo "PASS par_detach_check: 40 par threads, valgrind clean"

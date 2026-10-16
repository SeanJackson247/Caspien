#!/bin/bash
# A `par` handle is freed only after its thread ended (needs Linux, java, gcc, valgrind): runs tests/par_lock_test.caspien under valgrind
# and requires the right results, no invalid access (a handle freed under a running thread) and no lost blocks.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
java Compiler -i tests/par_lock_test.caspien pl_prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
valgrind --leak-check=full -q ./pl_prog >out.txt 2>vg.txt
grep -q "PASS results 605" out.txt && grep -q "PASS dropped handles waited 8" out.txt || { echo "FAIL: output: $(cat out.txt)"; exit 1; }
if grep -q "Invalid" vg.txt; then echo "FAIL: invalid access:"; grep -A4 Invalid vg.txt | head -12; exit 1; fi
if grep -q "lost" vg.txt; then echo "FAIL: lost blocks:"; grep "lost" vg.txt | head -5; exit 1; fi
echo "PASS par_lock_check: polled and dropped par handles, valgrind clean"

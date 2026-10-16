#!/bin/bash
# Allocation-failure sweep for growing and cloning dynarrays of owning elements (needs Linux, java, gcc). Compiles
# tests/dyn_owning_oom_test.caspien in a scratch copy of the repo with a Linux target, then runs it with an LD_PRELOAD shim that
# makes the Nth malloc return NULL, for every N after the setup ("built" printed), and then fails the grow realloc inside the owns-pointer clone. Every run must exit 0 and print the normal result or "CAUGHT out of memory", and after a caught failure the
# number of live allocations at exit must equal the clean run's (no leak).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp "$ROOT/tests/alloc_shim.c" shim.c
gcc -shared -fPIC -o shim.so shim.c -ldl || { echo "FAIL: cannot build shim"; exit 1; }
java Compiler -i tests/dyn_owning_oom_test.caspien oom_prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
base=$(LD_PRELOAD=./shim.so ./oom_prog 2>err.txt); N=$(sed -n 's/ALLOCS=\([0-9]*\).*/\1/p' err.txt); L0=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
echo "$base" | grep -q "done 5 2 16" || { echo "FAIL: clean run output: $base"; exit 1; }
bad=0; checked=0
for k in $(seq 1 "$N"); do
  out=$(FAILN=$k LD_PRELOAD=./shim.so ./oom_prog 2>err.txt); rc=$?
  echo "$out" | grep -q "built" || continue          # failure during setup: not what this checks
  checked=$((checked+1))
  live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
  if [ $rc -ne 0 ]; then echo "FAIL k=$k: exit $rc"; bad=1
  elif echo "$out" | grep -q "CAUGHT out of memory"; then
    [ "$live" = "$L0" ] || { echo "FAIL k=$k: caught but live allocations $live (clean run: $L0)"; bad=1; }
  elif ! echo "$out" | grep -q "done 5 2 16"; then echo "FAIL k=$k: unexpected output: $out"; bad=1; fi
done
# (only the grow inside the owns-pointer clone: a failing realloc inside the user's own `resize` leaves its moved-in blocks
# unfreed, a known gap, and 16/64/128 are the ghost table's own growth)
for sz in 32; do
  out=$(FAILREALLOC=$sz LD_PRELOAD=./shim.so ./oom_prog 2>err.txt); rc=$?; live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
  checked=$((checked+1))
  if [ $rc -ne 0 ]; then echo "FAIL realloc $sz: exit $rc"; bad=1
  elif echo "$out" | grep -q "CAUGHT out of memory"; then
    [ "$live" = "$L0" ] || { echo "FAIL realloc $sz: caught but live allocations $live (clean run: $L0)"; bad=1; }
  elif ! echo "$out" | grep -q "done 5 2 16"; then echo "FAIL realloc $sz: unexpected output: $out"; bad=1; fi
done
if [ $bad -eq 0 ]; then echo "PASS dyn_owning_oom_check: $checked failure points checked, none crashed or leaked"; else exit 1; fi

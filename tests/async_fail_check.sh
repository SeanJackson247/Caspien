#!/bin/bash
# `await`/`par` failure handling (needs Linux, java, gcc). Compiles tests/async_fail_test.caspien in a scratch copy with a Linux
# target and runs it (1) plainly, (2) with every malloc failing in turn (tests/alloc_shim.c FAILN=k), (3) with pthread_create failing
# (FAILPTHREAD=1). Every run must exit 0 and print the normal result or "CAUGHT ..."; after a caught failure no allocation may be
# left alive beyond what a run failing at the very first user allocation leaves.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp "$ROOT/tests/alloc_shim.c" shim.c
gcc -shared -fPIC -o shim.so shim.c -ldl -lpthread || { echo "FAIL: cannot build shim"; exit 1; }
java Compiler -i tests/async_fail_test.caspien af_prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
base=$(LD_PRELOAD=./shim.so ./af_prog 2>err.txt); N=$(sed -n 's/ALLOCS=\([0-9]*\).*/\1/p' err.txt)
echo "$base" | grep -q "await=42 par=42" || { echo "FAIL: clean run output: $base"; exit 1; }
FAILN=2 LD_PRELOAD=./shim.so ./af_prog >/dev/null 2>err.txt; Lmin=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
bad=0; checked=0
out=$(FAILPTHREAD=1 LD_PRELOAD=./shim.so ./af_prog 2>err.txt); rc=$?
live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
[ $rc -eq 0 ] && echo "$out" | grep -q "CAUGHT cannot start thread" || { echo "FAIL pthread_create: rc=$rc out=$out"; bad=1; }
[ "$live" = "$Lmin" ] || { echo "FAIL pthread_create: live allocations $live (expected $Lmin)"; bad=1; }
for k in $(seq 2 "$N"); do
  out=$(FAILN=$k LD_PRELOAD=./shim.so ./af_prog 2>err.txt); rc=$?
  live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt); checked=$((checked+1))
  if [ $rc -ne 0 ]; then echo "FAIL k=$k: exit $rc"; bad=1
  elif echo "$out" | grep -q "CAUGHT out of memory"; then
    [ "$live" = "$Lmin" ] || { echo "FAIL k=$k: caught but live allocations $live (expected $Lmin)"; bad=1; }
  elif ! echo "$out" | grep -q "await=42 par=42"; then echo "FAIL k=$k: unexpected output: $out"; bad=1; fi
done
if [ $bad -eq 0 ]; then echo "PASS async_fail_check: pthread_create failure and $checked malloc failure points checked, none crashed or leaked"; else exit 1; fi

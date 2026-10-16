#!/bin/bash
# Allocation-failure sweep for `new` and `dyn([..])` with moved-in owns values (needs Linux, java, gcc). Compiles
# tests/new_oom_test.caspien in a scratch copy of the repo with a Linux target, then runs it with tests/alloc_shim.c making
# the Nth malloc return NULL for every N after the runtime's own start-up. Every run must exit 0 and print either the normal
# result or "CAUGHT out of memory"; after a caught failure the live allocations at exit must equal those of a run that fails at
# the very first user allocation (nothing of the program left behind: values moved into the failed allocation are freed).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp "$ROOT/tests/alloc_shim.c" shim.c
gcc -shared -fPIC -o shim.so shim.c -ldl || { echo "FAIL: cannot build shim"; exit 1; }
java Compiler -i tests/new_oom_test.caspien oom_prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
base=$(LD_PRELOAD=./shim.so ./oom_prog 2>err.txt); N=$(sed -n 's/ALLOCS=\([0-9]*\).*/\1/p' err.txt)
echo "$base" | grep -q "done 5 4 2" || { echo "FAIL: clean run output: $base"; exit 1; }
# the first user allocation is malloc #3 (#1 and #2 are the ghost table and the stdout buffer)
FAILN=3 LD_PRELOAD=./shim.so ./oom_prog >/dev/null 2>err.txt; Lmin=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
bad=0; checked=0
for k in $(seq 3 "$N"); do
  out=$(FAILN=$k LD_PRELOAD=./shim.so ./oom_prog 2>err.txt); rc=$?
  live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt); checked=$((checked+1))
  if [ $rc -ne 0 ]; then echo "FAIL k=$k: exit $rc"; bad=1
  elif echo "$out" | grep -q "CAUGHT out of memory"; then
    [ "$live" = "$Lmin" ] || { echo "FAIL k=$k: caught but live allocations $live (expected $Lmin)"; bad=1; }
  elif ! echo "$out" | grep -q "done 5 4 2"; then echo "FAIL k=$k: unexpected output: $out"; bad=1; fi
done
if [ $bad -eq 0 ]; then echo "PASS new_oom_check: $checked failure points checked, none crashed or leaked"; else exit 1; fi

#!/bin/bash
# Allocation-failure sweep for `clone` (needs Linux, java, gcc). Compiles tests/clone_oom_test.caspien in a scratch copy of the
# repo with a Linux target, then runs it with an LD_PRELOAD shim that makes the Nth malloc return NULL, for every N after the
# program's own setup ("built" printed). Every such run must exit 0 and print either the normal result or "CAUGHT out of memory",
# and after a caught failure the number of live allocations at exit must equal the clean run's (no leak). A second pass fails
# the dynarray-grow realloc inside a deep dynarray clone. Failures of the ghost table's own reallocs and of `new`/`dyn([..])`
# during setup are not part of this check.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp "$ROOT/tests/alloc_shim.c" shim.c
gcc -shared -fPIC -o shim.so shim.c -ldl || { echo "FAIL: cannot build shim"; exit 1; }
java Compiler -i tests/clone_oom_test.caspien oom_prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
base=$(LD_PRELOAD=./shim.so ./oom_prog 2>err.txt); N=$(sed -n 's/ALLOCS=\([0-9]*\).*/\1/p' err.txt); L0=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
echo "$base" | grep -q "cloned 5 4 2" || { echo "FAIL: clean run output: $base"; exit 1; }
bad=0; checked=0
for k in $(seq 1 "$N"); do
  out=$(FAILN=$k LD_PRELOAD=./shim.so ./oom_prog 2>err.txt); rc=$?
  echo "$out" | grep -q "built" || continue          # failure during setup: not what this checks
  checked=$((checked+1))
  live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
  if [ $rc -ne 0 ]; then echo "FAIL k=$k: exit $rc"; bad=1
  elif echo "$out" | grep -q "CAUGHT out of memory"; then
    [ "$live" = "$L0" ] || { echo "FAIL k=$k: caught but live allocations $live (clean run: $L0)"; bad=1; }
  elif ! echo "$out" | grep -q "cloned 5 4 2"; then echo "FAIL k=$k: unexpected output: $out"; bad=1; fi
done
out=$(FAILREALLOC=32 LD_PRELOAD=./shim.so ./oom_prog 2>err.txt); rc=$?; live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
if [ $rc -ne 0 ] || ! echo "$out" | grep -q "CAUGHT out of memory" || [ "$live" != "$L0" ]; then echo "FAIL realloc: rc=$rc live=$live out=$out"; bad=1; fi
if [ $bad -eq 0 ]; then echo "PASS clone_oom_check: $checked failure points checked, none crashed or leaked"; else exit 1; fi

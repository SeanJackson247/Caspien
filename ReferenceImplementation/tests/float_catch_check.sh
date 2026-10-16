#!/bin/bash
# Float variables in xmm registers around a jump straight into a catch (needs Linux, java, gcc). Compiles tests/float_catch_test.caspien
# in a scratch copy with a Linux target, once with the shipped switches and once with register variables, float variables,
# float temporaries and aggressive inlining all on, and requires the two PASS lines and the clean viaNew result (1024). With the
# allocation shim failing each malloc in turn, viaNew must report 1024 or, when the `new` fails, exactly 16 (the value the float variable
# held when the failure jumped into the catch); at least one run must fail the `new`.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp "$ROOT/tests/alloc_shim.c" shim.c
gcc -shared -fPIC -o shim.so shim.c -ldl -lpthread || { echo "FAIL: cannot build shim"; exit 1; }
cp toolchain.config toolchain.shipped
bad=0; caught=0
for mode in shipped allon; do
  cp toolchain.shipped toolchain.config
  if [ $mode = allon ]; then
    sed -i 's/^deferred-operands:.*/deferred-operands: on/; s/^variables-in-registers:.*/variables-in-registers: on/; s/^float-variables-in-registers:.*/float-variables-in-registers: on/; s/^float-temporaries-in-registers:.*/float-temporaries-in-registers: on/; s/^function-inlining:.*/function-inlining: aggressive/' toolchain.config
  fi
  java Compiler -i tests/float_catch_test.caspien fc_$mode >compile_$mode.log 2>&1 || { echo "FAIL $mode: compile"; tail -3 compile_$mode.log; bad=1; continue; }
  out=$(LD_PRELOAD=./shim.so ./fc_$mode 2>err.txt); N=$(sed -n 's/ALLOCS=\([0-9]*\).*/\1/p' err.txt)
  [ "$(echo "$out" | grep -c '^PASS')" = 2 ] && echo "$out" | grep -q "^viaNew=1024.0" || { echo "FAIL $mode: clean run: $out"; bad=1; continue; }
  for k in $(seq 2 "$N"); do
    o=$(FAILN=$k LD_PRELOAD=./shim.so ./fc_$mode 2>/dev/null); rc=$?
    v=$(echo "$o" | sed -n 's/^viaNew=\(.*\)/\1/p')
    if [ $rc -ne 0 ]; then echo "FAIL $mode k=$k: exit $rc"; bad=1
    elif echo "$v" | grep -q "^16.0"; then caught=$((caught+1))
    elif ! echo "$v" | grep -q "^1024.0"; then echo "FAIL $mode k=$k: unexpected output: $o"; bad=1; fi
  done
done
[ $caught -ge 2 ] || { echo "FAIL: no failed 'new' was observed"; bad=1; }
if [ $bad -eq 0 ]; then echo "PASS float_catch_check: shipped and all-on builds, $caught failed allocations seen correctly"; else exit 1; fi

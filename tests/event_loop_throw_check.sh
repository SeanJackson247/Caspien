#!/bin/bash
# An event loop ends when 'main' or the '@tick' function throws: stderr gets "event loop stopped: <message>", exit status 1, stdout shows how
# many ticks ran, and nothing is left registered at exit (live allocations 0 under tests/alloc_shim.c). Needs Linux, java, gcc.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp "$ROOT/tests/alloc_shim.c" shim.c
gcc -shared -fPIC -o shim.so shim.c -ldl || { echo "FAIL: cannot build shim"; exit 1; }
bad=0
for v in tick main; do
  java Compiler -i tests/event_loop_${v}_throw_test.caspien prog_$v >compile.log 2>&1 || { echo "FAIL $v: compile"; tail -5 compile.log; exit 1; }
  addr=$(nm prog_$v | awk '$3=="ghost_table"{print $1}')
  out=$(GT_ADDR=$addr GT_EXE=$W/prog_$v LD_PRELOAD=./shim.so ./prog_$v 2>err.txt); rc=$?
  if [ $v = tick ]; then want=$'tick 1\ntick 2\ntick 3'; msg="event loop stopped: tick gave up"; else want=""; msg="event loop stopped: main gave up"; fi
  [ "$out" = "$want" ] || { echo "FAIL $v: stdout '$out'"; bad=1; }
  [ $rc -eq 1 ] || { echo "FAIL $v: exit status $rc (expected 1)"; bad=1; }
  grep -qF "$msg" err.txt || { echo "FAIL $v: stderr lacks '$msg'"; cat err.txt; bad=1; }
  live=$(sed -n 's/.*GT_LEN=\([0-9]*\).*/\1/p' err.txt)
  [ "$live" = "0" ] || { echo "FAIL $v: blocks still registered at exit: '$live'"; bad=1; }
done
[ $bad -eq 0 ] && echo "PASS event_loop_throw_check: tick and main failures stop the loop with a message and status 1"
exit $bad

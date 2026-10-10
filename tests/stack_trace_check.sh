#!/bin/bash
# Stack traces end to end (Linux, java, gcc): builds tests/stack_trace_test.caspien in a Linux copy of the tree, runs it and compares the
# printed function chains (file:line stripped) with the expected ones; then --trace-depth 2 must cut the chain to the 2 innermost frames.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
bad=0
strip() { sed 's/ (\([a-z_]*\.caspien\):[0-9]*)/ (\1)/'; }
cat > expected_full.txt <<'X'
too big
leaf (stack_trace_test.caspien)
mid (stack_trace_test.caspien)
main (stack_trace_test.caspien)
?
PASS terminator
in caught:
leaf (stack_trace_test.caspien)
caught (stack_trace_test.caspien)
main (stack_trace_test.caspien)
?
PASS copy survives
inner
?
?
X
java Compiler -i tests/stack_trace_test.caspien st --no-cache >log.txt 2>&1 || { echo "FAIL: does not compile"; tail -5 log.txt; exit 1; }
./st | strip > got_full.txt
diff expected_full.txt got_full.txt >/dev/null || { echo "FAIL: full-depth chains differ"; diff expected_full.txt got_full.txt; bad=1; }
java Compiler -i tests/stack_trace_depth_test.caspien st2 --no-cache --trace-depth 2 >log.txt 2>&1 || { echo "FAIL: --trace-depth 2 does not compile"; tail -3 log.txt; exit 1; }
./st2 | strip > got_2.txt
printf 'leaf (stack_trace_depth_test.caspien)\nmid (stack_trace_depth_test.caspien)\n?\n' > expected_2.txt
diff expected_2.txt got_2.txt >/dev/null || { echo "FAIL: depth 2 should keep leaf, mid only"; cat got_2.txt; bad=1; }
# an allocation failure (tests/alloc_shim.c makes the 2nd malloc fail: the Box in make) is caught with a trace starting in `make`
cp "$ROOT/tests/alloc_shim.c" shim.c && gcc -shared -fPIC -o shim.so shim.c -ldl || { echo "FAIL: cannot build shim"; exit 1; }
java Compiler -i tests/stack_trace_oom_test.caspien stoom --no-cache >log.txt 2>&1 || { echo "FAIL: oom test does not compile"; tail -3 log.txt; exit 1; }
[ "$(./stoom)" = "alloc ok" ] || { echo "FAIL: oom test clean run"; bad=1; }
FAILN=2 LD_PRELOAD=./shim.so ./stoom 2>/dev/null | strip > got_oom.txt
printf 'out of memory\nmake (stack_trace_oom_test.caspien)\nmain (stack_trace_oom_test.caspien)\n?\n' > expected_oom.txt
diff expected_oom.txt got_oom.txt >/dev/null || { echo "FAIL: allocation-failure trace differs"; cat got_oom.txt; bad=1; }
# two instantiations of one generic function are told apart; a thread's trace ends at its trampoline
java Compiler -i tests/stack_trace_generic_test.caspien stgen --no-cache >log.txt 2>&1 || { echo "FAIL: generic test does not compile"; tail -3 log.txt; exit 1; }
./stgen | strip > got_gen.txt
printf 'chk_u8 (stack_trace_generic_test.caspien)\nchk_u64 (stack_trace_generic_test.caspien)\n' > expected_gen.txt
diff expected_gen.txt got_gen.txt >/dev/null || { echo "FAIL: generic instantiation names"; cat got_gen.txt; bad=1; }
java Compiler -i tests/stack_trace_par_test.caspien stpar --no-cache >log.txt 2>&1 || { echo "FAIL: par test does not compile"; tail -3 log.txt; exit 1; }
./stpar | strip > got_par.txt
printf 'in thread\nleaf (stack_trace_par_test.caspien)\nwork (stack_trace_par_test.caspien)\n__trampoline_work (stack_trace_par_test.caspien)\n0\n' > expected_par.txt
diff expected_par.txt got_par.txt >/dev/null || { echo "FAIL: thread trace"; cat got_par.txt; bad=1; }
# the same chains with every optimizer switch on (inlining aggressive): the trace slots must survive all passes
python3 - <<'PY'
import sys; sys.path.insert(0, "benchmarks/nbody")
import bench as B
B.patch_config("toolchain.config", B.FULL)
PY
java Compiler -i tests/stack_trace_test.caspien st3 --no-cache >log.txt 2>&1 || { echo "FAIL: all-on does not compile"; tail -3 log.txt; exit 1; }
./st3 | strip > got_3.txt
diff expected_full.txt got_3.txt >/dev/null || { echo "FAIL: chains differ with every optimizer switch on"; diff expected_full.txt got_3.txt; bad=1; }
[ $bad -eq 0 ] && echo "PASS stack_trace_check"
exit $bad

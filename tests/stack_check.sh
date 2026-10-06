#!/bin/bash
# (needs Linux, java, python3) The stack-frame estimate of `--audit` must never be below the real frame the compiler produces: every function of a
# set of programs is compiled to assembly under the shipped (Linux-patched) and the everything-on config and tests/stack_calib.py compares the estimate
# with the prologue (`subq`, callee-saved pushes). Function inlining is off in both: it merges callee frames into the caller, which the estimate (a
# call-graph sum over the source functions) does not model; the report says so. The depth sums come from the same frame figures (compared with tests/gas_model.py in gas_check.sh).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp toolchain.config toolchain.config.shipped
n=0
for cfg in SHIPPED FULL; do
  cp toolchain.config.shipped toolchain.config
  python3 - $cfg <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
if sys.argv[1] != "SHIPPED":
    # everything on except inlining and unrolling: inlining merges the frames of callees into their callers, which the call-graph estimate does not model
    B.patch_config("toolchain.config", dict(B.FULL, **{"function-inlining": "off", "loop-unrolling": "off"}))
PY
  for f in tests/gas_test.caspien tests/heap_gas_test.caspien docs/examples/0[1-8]_*.caspien docs/examples/1[0-5]_*.caspien docs/examples/1[7-9]_*.caspien docs/examples/20_*.caspien tests/perf_codegen_test.caspien; do
    java Compiler -i $f prog --asm --no-cache >log.txt 2>&1 || { echo "FAIL: compile $f ($cfg)"; tail -3 log.txt; exit 1; }
    (cd ASTGenerator && java -cp out caspien.Main -i ../$f ../g.hob >/dev/null 2>&1) || { echo "FAIL: front end $f"; exit 1; }
    out=$(python3 tests/stack_calib.py prog g.hob) || { echo "$f ($cfg): $out"; exit 1; }
    n=$((n+1))
  done
done
echo "PASS stack_check: $n compilations, the frame estimate is never below the real frame"

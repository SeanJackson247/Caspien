#!/bin/bash
# (needs Linux, java, gcc, python3) tests/pure_rt_test.caspien under shipped, everything-on and everything-off configs: output equals tests/pure_rt_test.py;
# plus every tests/pure_*_error_test / non_arg_error_test must be rejected with a '@pure'/'@pure(rt)'/'@non' message (not a crash).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp toolchain.config toolchain.config.shipped
python3 tests/pure_rt_test.py > exp.txt
for cfg in SHIPPED FULL OFF; do
  cp toolchain.config.shipped toolchain.config
  python3 - $cfg <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
if sys.argv[1] != "SHIPPED":
    B.patch_config("toolchain.config", getattr(B, sys.argv[1]))
PY
  java Compiler -i tests/pure_rt_test.caspien prog_$cfg --no-cache >compile_$cfg.log 2>&1 || { echo "FAIL: compile ($cfg)"; tail -5 compile_$cfg.log; exit 1; }
  ./prog_$cfg > out_$cfg.txt
  cmp -s out_$cfg.txt exp.txt || { echo "FAIL: output differs from the model ($cfg)"; diff out_$cfg.txt exp.txt; exit 1; }
done
for f in tests/pure_*_error_test.caspien tests/non_arg_error_test.caspien; do
  out=$(java Compiler -i $f err_prog --hob --no-cache 2>&1)
  echo "$out" | grep -q "Exception in thread" && { echo "FAIL: compiler crashed on $f"; exit 1; }
  echo "$out" | grep -qE "'@pure|@pure\(rt\)|'@non'" || { echo "FAIL: $f was not rejected with a @pure/@non message"; echo "$out" | tail -3; exit 1; }
done
echo "PASS pure_rt_check: output equals the model in 3 configs, every pure/non error fixture rejected with its own message"

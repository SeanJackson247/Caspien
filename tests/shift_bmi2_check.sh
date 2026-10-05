#!/bin/bash
# (needs Linux, java, gcc, python3, a BMI2 CPU) tests/shift_bmi2_test.caspien built with everything on (benchmarks/nbody/bench.py FULL switches, which
# include `bmi2 on`) and again with `bmi2 off`: both outputs equal tests/shift_bmi2_test.py; the first binary has shlx/shrx and no `shl/shr %cl`,
# the second the reverse.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
grep -q bmi2 /proc/cpuinfo || { echo "SKIP shift_bmi2_check: this CPU has no BMI2"; exit 0; }
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 - <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
B.patch_config("toolchain.config", B.FULL)
PY
python3 tests/shift_bmi2_test.py >exp.txt
for m in on off; do
  sed -i "s/^bmi2 .*/bmi2 $m/" toolchain.config
  java Compiler -i tests/shift_bmi2_test.caspien prog_$m --no-cache >compile_$m.log 2>&1 || { echo "FAIL: compile ($m)"; tail -5 compile_$m.log; exit 1; }
  ./prog_$m >out_$m.txt
  cmp -s out_$m.txt exp.txt || { echo "FAIL: output differs from the model (bmi2 $m)"; diff out_$m.txt exp.txt; exit 1; }
done
x=$(objdump -d prog_on | grep -c 'shlx\|shrx'); c=$(objdump -d prog_on | grep -c 'sh[lr] .*%cl')
[ "$x" -gt 0 ] && [ "$c" = 0 ] || { echo "FAIL: bmi2 on has $x shlx/shrx and $c shl/shr %cl"; exit 1; }
x=$(objdump -d prog_off | grep -c 'shlx\|shrx')
[ "$x" = 0 ] || { echo "FAIL: bmi2 off still has $x shlx/shrx"; exit 1; }
echo "PASS shift_bmi2_check: both builds equal the model; bmi2 on uses shlx/shrx only, off none"

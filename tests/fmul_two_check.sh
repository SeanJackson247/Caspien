#!/bin/bash
# (needs Linux, java, gcc, python3) tests/fmul_two_test.caspien under everything on, shipped and everything off: output equals tests/fmul_two_test.py;
# with everything on the generated code has no `mulsd`/`mulss` whose operand is the pooled constant 2.0.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp toolchain.config toolchain.config.shipped
python3 tests/fmul_two_test.py > exp.txt
for cfg in SHIPPED FULL OFF; do
  cp toolchain.config.shipped toolchain.config
  python3 - $cfg <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
if sys.argv[1] != "SHIPPED":
    B.patch_config("toolchain.config", getattr(B, sys.argv[1]))
PY
  java Compiler -i tests/fmul_two_test.caspien prog_$cfg --no-cache >compile_$cfg.log 2>&1 || { echo "FAIL: compile ($cfg)"; tail -5 compile_$cfg.log; exit 1; }
  ./prog_$cfg > out_$cfg.txt
  cmp -s out_$cfg.txt exp.txt || { echo "FAIL: output differs from the model ($cfg)"; diff out_$cfg.txt exp.txt; exit 1; }
  if [ $cfg = FULL ]; then
    # pool labels whose value is 2.0 (f64 0x4000000000000000 = 4611686018427387904, f32 0x40000000 = 1073741824)
    for lab in $(grep -B1 -E "\.(quad 4611686018427387904|long 1073741824)$" .build/4_codegen.s | grep -oE "\.LFC[0-9]+"); do
      if grep -qE "mul(sd|ss) +$lab\(%rip\)" .build/4_codegen.s; then echo "FAIL: multiply by the pooled constant 2.0 ($lab) left"; exit 1; fi
    done
  fi
done
echo "PASS fmul_two_check: output equals the model in 3 configs, no multiply by 2.0 left"

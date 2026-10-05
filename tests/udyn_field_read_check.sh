#!/bin/bash
# (needs Linux, java, gcc, python3) tests/udyn_field_read_test.caspien under everything on, shipped and everything off: output equals tests/udyn_field_read_test.py;
# with everything on the register-form LOB has no `LOOKUP_ARRAY` left (every scalar field read of an element behind a pointer is fused).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp toolchain.config toolchain.config.shipped
python3 tests/udyn_field_read_test.py > exp.txt
for cfg in SHIPPED FULL OFF; do
  cp toolchain.config.shipped toolchain.config
  python3 - $cfg <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
if sys.argv[1] != "SHIPPED":
    B.patch_config("toolchain.config", getattr(B, sys.argv[1]))
PY
  java Compiler -i tests/udyn_field_read_test.caspien prog_$cfg --no-cache >compile_$cfg.log 2>&1 || { echo "FAIL: compile ($cfg)"; tail -5 compile_$cfg.log; exit 1; }
  ./prog_$cfg > out_$cfg.txt
  cmp -s out_$cfg.txt exp.txt || { echo "FAIL: output differs from the model ($cfg)"; diff out_$cfg.txt exp.txt; exit 1; }
  if [ $cfg = FULL ]; then
    n=$(grep -c "^LOOKUP_ARRAY" .build/3_lower_order_generator.lob.txt)
    [ "$n" = 0 ] || { echo "FAIL: $n LOOKUP_ARRAY lines left in the register-form LOB"; exit 1; }
  fi
done
echo "PASS udyn_field_read_check: output equals the model in 3 configs, no whole-element push left"

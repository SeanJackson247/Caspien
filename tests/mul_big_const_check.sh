#!/bin/bash
# (needs Linux, java, gcc, python3) tests/mul_big_const_test.caspien under everything on, shipped and everything off: output equals tests/mul_big_const_test.py;
# with register form on the big constants 2^n+-1 are `shlq` + add/sub.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp toolchain.config toolchain.config.shipped
python3 tests/mul_big_const_test.py > exp.txt
for cfg in SHIPPED FULL OFF; do
  cp toolchain.config.shipped toolchain.config
  python3 - $cfg <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
if sys.argv[1] != "SHIPPED":
    B.patch_config("toolchain.config", getattr(B, sys.argv[1]))
PY
  java Compiler -i tests/mul_big_const_test.caspien prog_$cfg --no-cache >compile_$cfg.log 2>&1 || { echo "FAIL: compile ($cfg)"; tail -5 compile_$cfg.log; exit 1; }
  ./prog_$cfg > out_$cfg.txt
  cmp -s out_$cfg.txt exp.txt || { echo "FAIL: output differs from the model ($cfg)"; diff out_$cfg.txt exp.txt; exit 1; }
  n=$(grep -cE '^    shlq \$(32|40|62|63), ' .build/4_codegen.s || true)
  if [ $cfg != OFF ]; then
    [ "$n" -ge 8 ] || { echo "FAIL: only $n big-constant shifts in $cfg (expected >= 8)"; exit 1; }
  fi
done
echo "PASS mul_big_const_check: output equals the model in 3 configs; big constants are shift+add with register form on"

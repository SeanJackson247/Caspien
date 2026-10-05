#!/bin/bash
# (needs Linux, java, gcc, python3) tests/static_cache_test.caspien under everything on, shipped and everything off: output equals tests/static_cache_test.py;
# with everything on the loops get their statics pre-loaded into xmm0-3 / spare variable registers, with float variables off they do not.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp toolchain.config toolchain.config.shipped
python3 tests/static_cache_test.py > exp.txt
for cfg in SHIPPED FULL OFF; do
  cp toolchain.config.shipped toolchain.config
  python3 - $cfg <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
if sys.argv[1] != "SHIPPED":
    B.patch_config("toolchain.config", getattr(B, sys.argv[1]))
PY
  java Compiler -i tests/static_cache_test.caspien prog_$cfg --no-cache >compile_$cfg.log 2>&1 || { echo "FAIL: compile ($cfg)"; tail -5 compile_$cfg.log; exit 1; }
  ./prog_$cfg > out_$cfg.txt
  cmp -s out_$cfg.txt exp.txt || { echo "FAIL: output differs from the model ($cfg)"; diff out_$cfg.txt exp.txt; exit 1; }
  n=$(grep -cE "mov(sd|ss) (ka|kb|kc|fa|fb|m[0-7])\(%rip\), %xmm([0-3]|1[2-3])$" .build/4_codegen.s)
  if [ $cfg = FULL ]; then
    [ "$n" -ge 12 ] || { echo "FAIL: only $n cache pre-loads in the everything-on build (expected >= 12)"; exit 1; }
  else
    [ "$n" -eq 0 ] || { echo "FAIL: $n cache pre-loads in $cfg (float variables are off there)"; exit 1; }
  fi
done
echo "PASS static_cache_check: output equals the model in 3 configs; the cache pre-loads appear only with float variables on"

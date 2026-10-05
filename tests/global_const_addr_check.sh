#!/bin/bash
# (needs Linux, java, gcc, python3) tests/global_const_addr_test.caspien under everything on, shipped and everything off: output equals tests/global_const_addr_test.py;
# with register form on the constant-index element accesses are `sym+N(%rip)` operands, without it there are none.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp toolchain.config toolchain.config.shipped
python3 tests/global_const_addr_test.py > exp.txt
for cfg in SHIPPED FULL OFF; do
  cp toolchain.config.shipped toolchain.config
  python3 - $cfg <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
if sys.argv[1] != "SHIPPED":
    B.patch_config("toolchain.config", getattr(B, sys.argv[1]))
PY
  java Compiler -i tests/global_const_addr_test.caspien prog_$cfg --no-cache >compile_$cfg.log 2>&1 || { echo "FAIL: compile ($cfg)"; tail -5 compile_$cfg.log; exit 1; }
  ./prog_$cfg > out_$cfg.txt
  cmp -s out_$cfg.txt exp.txt || { echo "FAIL: output differs from the model ($cfg)"; diff out_$cfg.txt exp.txt; exit 1; }
  n=$(grep -cE "(mov[a-z]*|cvt[a-z]*) +[A-Za-z0-9_]+\+[0-9]+\(%rip\)|, [A-Za-z0-9_]+\+[0-9]+\(%rip\)$" .build/4_codegen.s)
  if [ $cfg != OFF ]; then
    [ "$n" -ge 40 ] || { echo "FAIL: only $n sym+N(%rip) operands in $cfg (expected >= 40)"; exit 1; }
  else
    [ "$n" -eq 0 ] || { echo "FAIL: $n sym+N(%rip) operands in $cfg (everything off: no register form)"; exit 1; }
  fi
done
echo "PASS global_const_addr_check: output equals the model in 3 configs; sym+N(%rip) operands only with register form on"

#!/bin/bash
# (needs Linux, java, gcc, valgrind) the benchmark variants that use self-referential `ref` links (graph_ref_*, binarytrees_ref_*, lru_ref_*) and the
# ones that use the ref-based stdlib HashMap (lru_naive_refstd, knucleotide_naive_refstd): each must print exactly what its index-based / open-addressing
# counterpart prints, under everything on (FULL) and the shipped config, at sizes small enough to run fast but large enough to exercise eviction/growth.
# valgrind (shipped): no error and no leak on the ref safe variants and on the refstd lru.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
setcfg() { cp toolchain.config.shipped toolchain.config; if [ "$1" != SHIPPED ]; then python3 - "$1" <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
B.patch_config("toolchain.config", getattr(B, sys.argv[1]))
PY
fi; }
cp toolchain.config toolchain.config.shipped
build() { java Compiler -i benchmarks/$1.caspien prog_$2 --no-cache >log_$2.txt 2>&1 || { echo "FAIL: compile $1 ($2)"; tail -5 log_$2.txt; exit 1; }; }
# variant | reference | env var | size | valgrind size (0 = none)
CASES="graph/caspien/graph_ref_naive graph/caspien/graph_safe GRAPH_N 30000 0
graph/caspien/graph_ref_safe graph/caspien/graph_safe GRAPH_N 30000 3000
graph/caspien/graph_ref_unsafe graph/caspien/graph_safe GRAPH_N 30000 0
binarytrees/caspien/binarytrees_ref_naive binarytrees/caspien/binarytrees_safe BINARYTREES_N 11 0
binarytrees/caspien/binarytrees_ref_safe binarytrees/caspien/binarytrees_safe BINARYTREES_N 11 8
binarytrees/caspien/binarytrees_ref_unsafe binarytrees/caspien/binarytrees_safe BINARYTREES_N 11 0
lru/caspien/lru_ref_naive lru/caspien/lru_safe LRU_N 700000 0
lru/caspien/lru_ref_safe lru/caspien/lru_safe LRU_N 700000 300000
lru/caspien/lru_ref_unsafe lru/caspien/lru_safe LRU_N 700000 0
lru/caspien/lru_naive_refstd lru/caspien/lru_naive LRU_N 700000 300000
knucleotide/caspien/knucleotide_naive_refstd knucleotide/caspien/knucleotide_naive KNUC_N 300000 0"
for cfg in SHIPPED FULL; do
  setcfg $cfg
  echo "$CASES" | while read v r e n vg; do
    build $r ref_$cfg; build $v var_$cfg
    env $e=$n ./prog_ref_$cfg > exp.txt; env $e=$n ./prog_var_$cfg > got.txt
    [ -s exp.txt ] || { echo "FAIL: $r printed nothing"; exit 1; }
    cmp -s exp.txt got.txt || { echo "FAIL: $v differs from $r ($cfg)"; diff exp.txt got.txt | head -5; exit 1; }
    if [ "$cfg" = SHIPPED ] && [ "$vg" != 0 ]; then
      env $e=$vg valgrind -q --error-exitcode=9 --leak-check=full --errors-for-leak-kinds=definite,indirect ./prog_var_$cfg >/dev/null 2>vg.txt || { echo "FAIL: valgrind $v"; head -20 vg.txt; exit 1; }
    fi
  done || exit 1
done
echo "PASS ref_variants_check: 11 ref / ref-in-stdlib benchmark variants print what their counterparts print (shipped + everything on); valgrind clean on 5"

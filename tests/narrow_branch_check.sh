#!/bin/bash
# (needs Linux, java, gcc, python3) tests/narrow_branch_test.caspien in a Linux scratch copy of the repo: everything on (benchmarks/nbody/bench.py FULL), the same with deferred-operands off, and
# everything off (bench.py OFF). Output must equal tests/narrow_branch_test.py each time. With register form on, the low-order bytecode must contain narrow
# `R_BRC <op> 1|2|4` lines (>= 100) and the assembly sized `cmpb/cmpw/cmpl` compares; without register form none.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 tests/narrow_branch_test.py > exp.txt
run() { # label base-dict-name overrides-json
python3 - "$2" "$3" <<'PY'
import sys, json
sys.path.insert(0, "benchmarks/nbody")
import bench as B
B.patch_config("toolchain.config", dict(getattr(B, sys.argv[1]), **json.loads(sys.argv[2])))
PY
java Compiler -i tests/narrow_branch_test.caspien prog_$1 --no-cache >compile_$1.log 2>&1 || { echo "FAIL: compile ($1)"; tail -5 compile_$1.log; exit 1; }
./prog_$1 > out_$1.txt
cmp -s out_$1.txt exp.txt || { echo "FAIL: output differs from the model ($1)"; diff out_$1.txt exp.txt; exit 1; }
b=$(grep -cE '^R_BRC [A-Z_]+ [124] ' .build/3_lower_order_generator.lob.txt || true)
m=$(grep -cE '^    cmp[bwl] ' .build/4_codegen.s || true)
echo "$b $m"
}
read onb onm < <(run on FULL '{}') || { echo "FAIL on"; exit 1; }
read offb offm < <(run off FULL '{"deferred-operands": "off", "variables-in-registers": "off", "float-variables-in-registers": "off", "float-temporaries-in-registers": "off", "hoist-array-bases": "off", "variables-in-alloc-functions": "off", "variables-in-arg-registers": "off", "fuse-length-compare": "off"}') || { echo "FAIL off"; exit 1; }
read nob nom < <(run alloff OFF '{}') || { echo "FAIL alloff"; exit 1; }
[ "$onb" -ge 100 ] && [ "$onm" -ge 100 ] || { echo "FAIL: register form on but only $onb narrow R_BRC lines / $onm sized compares"; exit 1; }
[ "$offb" = 0 ] && [ "$offm" = 0 ] && [ "$nob" = 0 ] && [ "$nom" = 0 ] || { echo "FAIL: narrow R_BRC / sized compare present without register form ($offb/$offm, all off $nob/$nom)"; exit 1; }
echo "PASS narrow_branch_check: output equals the model in 3 configs; $onb narrow R_BRC lines / $onm sized compares with register form on, none without"

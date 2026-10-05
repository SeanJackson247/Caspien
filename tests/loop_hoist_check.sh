#!/bin/bash
# (needs Linux, java, gcc, python3) tests/loop_hoist_test.caspien in a Linux scratch copy of the repo, three ways: everything on with
# hoist-array-bases on (benchmarks/nbody/bench.py FULL), the same with hoist-array-bases off, and with variables-in-registers off (the switch
# is then inert). The output must equal tests/loop_hoist_test.py each time; with the switch on the low-order bytecode must show hoisted
# length loads (`R_LD 8 %t %v`, the pointer lives in a variable register) and with it off none.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 tests/loop_hoist_test.py > exp.txt
run() {  # name, python dict of overrides
python3 - "$2" <<'PY'
import sys, json
sys.path.insert(0, "benchmarks/nbody")
import bench as B
B.patch_config("toolchain.config", dict(B.FULL, **json.loads(sys.argv[1])))
PY
java Compiler -i tests/loop_hoist_test.caspien prog_$1 --no-cache >compile_$1.log 2>&1 || { echo "FAIL: compile ($1)"; tail -5 compile_$1.log; exit 1; }
./prog_$1 > out_$1.txt
cmp -s out_$1.txt exp.txt || { echo "FAIL: output differs from the model ($1)"; diff out_$1.txt exp.txt; exit 1; }
grep -cE '^R_LD 8 %t[0-9] %v[0-9]$' .build/3_lower_order_generator.lob.txt || true
}
on=$(run on '{}') || { echo "$on"; exit 1; }
off=$(run off '{"hoist-array-bases": "off"}') || { echo "$off"; exit 1; }
novar=$(run novar '{"variables-in-registers": "off", "float-variables-in-registers": "off"}') || { echo "$novar"; exit 1; }
[ "$on" -ge 10 ] || { echo "FAIL: only $on hoisted length loads with the switch on"; exit 1; }
[ "$off" = 0 ] || { echo "FAIL: $off hoisted length loads with the switch off"; exit 1; }
[ "$novar" = 0 ] || { echo "FAIL: $novar hoisted length loads without variable registers"; exit 1; }
echo "PASS loop_hoist_check: output equals the model in 3 configs; $on hoisted loads with the switch on, none with it off"

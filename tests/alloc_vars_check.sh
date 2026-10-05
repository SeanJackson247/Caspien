#!/bin/bash
# (needs Linux, java, gcc, python3) tests/alloc_vars_test.caspien in a Linux scratch copy of the repo, three ways: everything on (benchmarks/nbody/bench.py FULL,
# which has variables-in-alloc-functions on), the same with it off, and with variables-in-registers off. Output must equal tests/alloc_vars_test.py each time;
# with the switch on the assembly must contain the save of r12/r13/r14 to frame slots around the scratch-using instructions (>= 10 such stores),
# with it off or without register variables none.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 tests/alloc_vars_test.py > exp.txt
run() {
python3 - "$2" <<'PY'
import sys, json
sys.path.insert(0, "benchmarks/nbody")
import bench as B
B.patch_config("toolchain.config", dict(B.FULL, **json.loads(sys.argv[1])))
PY
java Compiler -i tests/alloc_vars_test.caspien prog_$1 --no-cache >compile_$1.log 2>&1 || { echo "FAIL: compile ($1)"; tail -5 compile_$1.log; exit 1; }
./prog_$1 > out_$1.txt
cmp -s out_$1.txt exp.txt || { echo "FAIL: output differs from the model ($1)"; diff out_$1.txt exp.txt; exit 1; }
grep -cE '^    movq %r1[234], -[0-9]+\(%rbp\)$' .build/4_codegen.s || true
}
on=$(run on '{}') || { echo "$on"; exit 1; }
off=$(run off '{"variables-in-alloc-functions": "off"}') || { echo "$off"; exit 1; }
novar=$(run novar '{"variables-in-registers": "off", "float-variables-in-registers": "off"}') || { echo "$novar"; exit 1; }
# callee-saved bookkeeping saves r12-r14 too (a few per function): the switch must add clearly more of them
[ "$on" -ge $((off + 10)) ] || { echo "FAIL: with the switch on there are only $on register saves against $off with it off"; exit 1; }
echo "PASS alloc_vars_check: output equals the model in 3 configs; $on register-to-frame stores with the switch on, $off with it off"

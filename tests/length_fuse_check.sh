#!/bin/bash
# (needs Linux, java, gcc, python3) tests/length_fuse_test.caspien in a Linux scratch copy of the repo, three ways: everything on (benchmarks/nbody/bench.py FULL,
# which has fuse-length-compare on), the same with it off, and everything off (bench.py OFF). Output must equal tests/length_fuse_test.py each time; with the
# switch on the low-order bytecode must contain R_BRCM compares (>= 10) and the assembly a `cmpq (%reg), %reg` memory compare, with it off none.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 tests/length_fuse_test.py > exp.txt
run() { # label base-dict-name overrides-json
python3 - "$2" "$3" <<'PY'
import sys, json
sys.path.insert(0, "benchmarks/nbody")
import bench as B
B.patch_config("toolchain.config", dict(getattr(B, sys.argv[1]), **json.loads(sys.argv[2])))
PY
java Compiler -i tests/length_fuse_test.caspien prog_$1 --no-cache >compile_$1.log 2>&1 || { echo "FAIL: compile ($1)"; tail -5 compile_$1.log; exit 1; }
./prog_$1 > out_$1.txt
cmp -s out_$1.txt exp.txt || { echo "FAIL: output differs from the model ($1)"; diff out_$1.txt exp.txt; exit 1; }
b=$(grep -c '^R_BRCM ' .build/3_lower_order_generator.lob.txt || true)
m=$(grep -cE '^    cmpq \(%r[a-z0-9]+\), %r[a-z0-9]+$' .build/4_codegen.s || true)
echo "$b $m"
}
read onb onm < <(run on FULL '{}') || { echo "FAIL on"; exit 1; }
read offb offm < <(run off FULL '{"fuse-length-compare": "off"}') || { echo "FAIL off"; exit 1; }
read nob nom < <(run alloff OFF '{}') || { echo "FAIL alloff"; exit 1; }
[ "$onb" -ge 10 ] && [ "$onm" -ge 10 ] || { echo "FAIL: switch on but only $onb R_BRCM lines / $onm memory compares"; exit 1; }
[ "$offb" = 0 ] && [ "$offm" = 0 ] && [ "$nob" = 0 ] && [ "$nom" = 0 ] || { echo "FAIL: R_BRCM / memory compare present with the switch off ($offb/$offm, all off $nob/$nom)"; exit 1; }
echo "PASS length_fuse_check: output equals the model in 3 configs; $onb R_BRCM lines / $onm memory compares with the switch on, none off"

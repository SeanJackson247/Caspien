#!/bin/bash
# (needs Linux, java, gcc, python3) tests/field_disp_test.caspien in a Linux scratch copy of the repo: everything on (benchmarks/nbody/bench.py FULL), shipped
# toolchain.config (Linux target) and everything off (bench.py OFF). Output must equal tests/field_disp_test.py each time. FieldDisplacementPass is always on but
# needs register form: with it the low-order bytecode must contain R_LDD/R_STD/R_LDXD/R_STXD of every width (1, 2, 4, 8; floats 4, 8), with everything off none.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp toolchain.config toolchain.shipped
python3 tests/field_disp_test.py > exp.txt
run() { # label base-dict-name|shipped
if [ "$2" = shipped ]; then cp toolchain.shipped toolchain.config; else
python3 - "$2" <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
B.patch_config("toolchain.config", dict(getattr(B, sys.argv[1])))
PY
fi
java Compiler -i tests/field_disp_test.caspien prog_$1 --no-cache >compile_$1.log 2>&1 || { echo "FAIL: compile ($1)"; tail -5 compile_$1.log; exit 1; }
./prog_$1 > out_$1.txt
cmp -s out_$1.txt exp.txt || { echo "FAIL: output differs from the model ($1)"; diff out_$1.txt exp.txt; exit 1; }
grep -E '^R_(LDD|STD|LDXD|STXD) ' .build/3_lower_order_generator.lob.txt | awk '{print $1 "_" $2}' | sort -u | tr '\n' ' '
echo
}
on=$(run on FULL) || { echo "$on"; exit 1; }
sh=$(run shipped shipped) || { echo "$sh"; exit 1; }
off=$(run alloff OFF) || { echo "$off"; exit 1; }
for k in R_LDD_1 R_LDD_2 R_LDD_4 R_LDD_8 R_STD_1 R_STD_2 R_STD_4 R_STD_8 R_LDXD_4 R_LDXD_8 R_STXD_4 R_STXD_8; do
  case "$on" in *"$k "*) ;; *) echo "FAIL: no $k line with everything on ($on)"; exit 1;; esac
done
[ -z "$(echo $off)" ] || { echo "FAIL: displacement lines with everything off: $off"; exit 1; }
echo "PASS field_disp_check: output equals the model in 3 configs; all 12 displacement forms present with register form on, none with everything off"

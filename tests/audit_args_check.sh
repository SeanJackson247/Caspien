#!/bin/bash
# (needs java, python3, gcc) `--audit` with argument values: a call that passes values the caller knows gets the callee's bound for those values.
# tests/audit_args_test.caspien: every function's figures equal the concrete-interpreter model in tests/gas_model.py (that comparison runs in gas_check.sh too) and
# `main` must be bounded in all four sections; its peak live heap must equal what the real allocator measures (tests/alloc_shim.c). The program runs and prints PASS lines.
# tests/audit_args_unknown_test.caspien: arguments the analysis cannot know (a call result, a variable changed in a loop) must keep `main` finite (not bounded), with the reason.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target windows_gnu/target linux/; s/default: win64/default: sysv_x64/' toolchain.config
mkdir -p output
gcc -shared -fPIC -O1 -o shim.so tests/alloc_shim.c -ldl -lpthread || { echo "FAIL: shim build"; exit 1; }
(cd ASTGenerator && CASPIEN_AUDIT_ALL=1 CASPIEN_AUDIT_FILE=$W/a.txt java -cp out caspien.Main -i ../tests/audit_args_test.caspien $W/a.hob --audit >/dev/null 2>&1) || { echo "FAIL: audit audit_args_test"; exit 1; }
(cd ASTGenerator && CASPIEN_AUDIT_ALL=1 CASPIEN_AUDIT_FILE=$W/u.txt java -cp out caspien.Main -i ../tests/audit_args_unknown_test.caspien $W/u.hob --audit >/dev/null 2>&1) || { echo "FAIL: audit audit_args_unknown_test"; exit 1; }
python3 tests/gas_model.py a.hob > am.txt || { echo "FAIL: model"; exit 1; }
python3 - a.txt am.txt <<'PY' || exit 1
import sys, re
rep = open(sys.argv[1]).read()
model = {l.split()[0]: l.split() for l in open(sys.argv[2])}
mains = [l for l in rep.splitlines() if re.match(r"  main \(entry\)", l)]
if len(mains) != 4: print("FAIL: expected a main line in each of the 4 sections, found", len(mains)); sys.exit(1)
for l in mains:
    if ">=" in l or not re.search(r"  bounded$", l): print("FAIL: main is not bounded:", l.strip()); sys.exit(1)
g = int(re.search(r"main \(entry\)\s+(\d+)  bounded$", mains[0]).group(1))
if g != int(model["main"][1]) or "-" != model["main"][2]: print("FAIL: gas of main: report", g, "model", model["main"][1:3]); sys.exit(1)
for fn in ("sumTo", "sumLocal", "grid", "outer", "tri", "grow", "viaMod"):   # on their own they are unbounded: the figure is a lower bound with the reason
    if not re.search(r"^  %s\s+>= \d+  finite: " % fn, rep, flags=re.M): print("FAIL:", fn, "alone should be reported finite"); sys.exit(1)
print("ok audit_args_test: main bounded, gas", g, "equals the model")
PY
want=$(sed -n '/# peak live heap/,$p' a.txt | sed -n 's/^  main (entry) *peak \([0-9]*\) bytes.*/\1/p')
java -cp . Compiler -i tests/audit_args_test.caspien output/aa > c.log 2>&1 || { echo "FAIL: compile"; tail -3 c.log; exit 1; }
addr=$(nm output/aa | awk '$3=="ghost_table"{print $1}')
GT_ADDR=$addr GT_EXE=$W/output/aa LD_PRELOAD=$W/shim.so ./output/aa > run.out 2> run.err
peak=$(sed -n 's/.*PEAK=\([0-9]*\).*/\1/p' run.err)
grep -c "^PASS" run.out | grep -qx 8 && ! grep -q FAIL run.out || { echo "FAIL: program output"; cat run.out; exit 1; }
[ "$peak" = "$want" ] || { echo "FAIL: peak live heap: allocator measured '$peak', audit says '$want'"; exit 1; }
echo "ok peak live heap $peak bytes == measured"
grep -qE "^  main \(entry\) +>= [0-9]+  finite: .*calls viaResult( \(defined at [^)]*\))? \(finite\)" u.txt || { echo "FAIL: main in audit_args_unknown_test should be finite because of viaResult"; exit 1; }
grep -qE "^  main \(entry\) +>= [0-9]+  finite: .*calls viaLoop( \(defined at [^)]*\))? \(finite\)" u.txt || { echo "FAIL: main should also be finite because of viaLoop"; exit 1; }
grep -qE "^  main \(entry\) +>= [0-9]+  finite: .*calls viaBranch( \(defined at [^)]*\))? \(finite\)" u.txt || { echo "FAIL: main should also be finite because of viaBranch"; exit 1; }
echo "PASS audit_args_check: bounds from call-site values equal the model and the allocator; unknown arguments stay unbounded"

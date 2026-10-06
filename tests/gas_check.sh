#!/bin/bash
# (needs java, python3) `--audit` worst-case gas report: tests/gas_test.caspien and every docs/examples program are audited and the figure of every
# reachable function is compared with tests/gas_model.py (exhaustive path search over the emitted bytecode). Functions that contain a `loop{}`
# (or call one) are only checked for being flagged UNBOUNDED; a function with a non-literal `for` bound must show ">=" and the reason.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W/ASTGenerator" || exit 1
checked=0
for f in ../tests/gas_test.caspien ../docs/examples/*.caspien ../tests/perf_codegen_test.caspien; do
  [ "$(basename $f)" = 09_event_loop.caspien ] && continue
  CASPIEN_AUDIT_FILE=$W/audit.txt java -cp out caspien.Main -i $f $W/g.hob --audit >/dev/null 2>&1 || { echo "FAIL: compile/audit $f"; exit 1; }
  python3 ../tests/gas_model.py $W/g.hob > $W/model.txt
  python3 - $W/audit.txt $W/model.txt $f <<'PY' || exit 1
import sys, re
audit = open(sys.argv[1]).read()
sec = audit.split("# worst-case execution cost", 1)
if len(sec) < 2: print("FAIL: no gas section in", sys.argv[3]); sys.exit(1)
rep = {}
for l in sec[1].splitlines():
    m = re.match(r"  (\S+?)( \(entry\))?\s+(>= )?(\d+)(  UNBOUNDED: (.*))?$", l)
    if m: rep[m.group(1)] = (m.group(3) is not None, int(m.group(4)), m.group(6))
model = {}
for l in open(sys.argv[2]):
    n, v, fl = l.split()
    model[n] = (int(v), set(fl.split(",")) - {"-"})
if not rep: print("FAIL: empty report for", sys.argv[3]); sys.exit(1)
for n, (ub, v, why) in rep.items():
    mv, fl = model[n]
    if "skip" in fl:
        continue
    if "loop" in fl:
        if not ub: print("FAIL: %s in %s contains a loop but is not UNBOUNDED" % (n, sys.argv[3])); sys.exit(1)
        continue
    if ub != ("var" in fl):
        print("FAIL: %s in %s: unbounded=%s, model flags %s" % (n, sys.argv[3], ub, fl)); sys.exit(1)
    if v != mv:
        print("FAIL: %s in %s: report %d, model %d" % (n, sys.argv[3], v, mv)); sys.exit(1)
print("ok %-40s %d functions" % (sys.argv[3].split("/")[-1], len(rep)))
PY
  checked=$((checked+1))
done
grep -q "UNBOUNDED" $W/audit.txt >/dev/null
CASPIEN_AUDIT_FILE=$W/audit.txt java -cp out caspien.Main -i ../tests/gas_test.caspien $W/g.hob --audit >/dev/null 2>&1
for want in "gas_loops  *595$" "gas_branch  *37$" "gas_leaf  *8$" "gas_unbounded  *>= .*UNBOUNDED: .for. bound is not a literal" "gas_forever  *>= .*UNBOUNDED: unbounded .loop."; do
  grep -qE "$want" $W/audit.txt || { echo "FAIL: audit lacks a line matching: $want"; exit 1; }
done
echo "PASS gas_check: $checked programs, every function's worst-case gas equals the path-search model"

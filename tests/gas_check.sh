#!/bin/bash
# (needs java, python3) `--audit` worst-case gas report: tests/gas_test.caspien and every docs/examples program are audited and the figure of every
# reachable function is compared with tests/gas_model.py (exhaustive path search over the emitted bytecode). Functions that contain a `loop{}`
# (or call one) are only checked for being flagged not bounded (stack depth is still compared); a function with a non-literal `for` bound must show ">=" and the reason.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W/ASTGenerator" || exit 1
checked=0
for f in ../tests/gas_test.caspien ../tests/heap_gas_test.caspien ../tests/audit_args_test.caspien ../tests/audit_args_unknown_test.caspien ../docs/examples/*.caspien ../tests/perf_codegen_test.caspien; do
  [ "$(basename $f)" = 09_event_loop.caspien ] && continue
  CASPIEN_AUDIT_ALL=1 CASPIEN_AUDIT_FILE=$W/audit.txt java -cp out caspien.Main -i $f $W/g.hob --audit >/dev/null 2>&1 || { echo "FAIL: compile/audit $f"; exit 1; }
  python3 ../tests/gas_model.py $W/g.hob > $W/model.txt
  python3 - $W/audit.txt $W/model.txt $f <<'PY' || exit 1
import sys, re
audit = open(sys.argv[1]).read()
prog = sys.argv[3].split("/")[-1]
if "# worst-case execution cost" not in audit or "# stack depth" not in audit or "# heap memory" not in audit:
    print("FAIL: a section is missing in the report for", prog); sys.exit(1)
gas_sec = audit.split("# worst-case execution cost", 1)[1].split("# stack depth", 1)[0]
stack_sec = audit.split("# stack depth", 1)[1].split("# heap memory", 1)[0]
heap_sec = audit.split("# heap memory", 1)[1]
def parse(sec, unit):
    rep = {}
    for l in sec.splitlines():
        m = re.match(r"  (\S+?)( \((?:entry|thread entry)\))?\s+(>= )?(\d+)%s(  \(\d+ allocation sites? in its own code\))?  (bounded|finite|unknown|unbounded|can diverge|non-terminating)(: .*)?$" % unit, l)
        if m: rep[m.group(1)] = (m.group(3) is not None, int(m.group(4)))
        if m: assert (m.group(3) is not None) == (m.group(6) != "bounded"), "word and >= disagree: " + l
    return rep
gas, stack = parse(gas_sec, ""), parse(stack_sec, " bytes")
# heap lines: `  name  [>= ]N bytes  ([>= ]K allocation operation(s))  <word>[: reasons]`
heapb, heapc = {}, {}
for l in heap_sec.splitlines():
    m = re.match(r"  (\S+?)( \(entry\))?\s+(>= )?(\d+) bytes  \((>= )?(\d+) allocation operations?\)  (bounded|finite|unknown|unbounded|can diverge|non-terminating)(: .*)?$", l)
    if m:
        heapb[m.group(1)] = (m.group(3) is not None, int(m.group(4)))
        heapc[m.group(1)] = (m.group(5) is not None, int(m.group(6)))
model = {}
for l in open(sys.argv[2]):
    n, g, fl, h, hu, by, bu, sd, su = l.split()
    model[n] = (int(g), set(fl.split(",")) - {"-"}, int(h), hu == "U", int(by), bu == "U", int(sd), su == "U")
if not gas: print("FAIL: empty report for", prog); sys.exit(1)
for n, (ub, v) in gas.items():
    mg, fl, mh, mhu, mb, mbu, ms, msu = model[n]
    if "skip" in fl: continue
    if "loop" in fl:
        if not ub: print("FAIL: %s in %s contains a loop but is reported bounded" % (n, prog)); sys.exit(1)
    else:
        # a bound the report claims must be the model's exact worst case (and the model must find nothing unknown); an UNBOUNDED figure is a lower
        # bound and must not exceed what the model finds
        hub, hv = heapc.get(n, (False, 0))   # heap: absent from the list = exactly 0 and bounded
        bub, bv = heapb.get(n, (False, 0))
        if not ub:
            if "var" in fl: print("FAIL: %s in %s is bounded in the report but the model finds an unknown loop bound" % (n, prog)); sys.exit(1)
            if v != mg: print("FAIL: gas of %s in %s: report %d, model %d" % (n, prog, v, mg)); sys.exit(1)
        elif v > mg: print("FAIL: lower bound of %s in %s is above the model's figure" % (n, prog)); sys.exit(1)
        if not hub:
            if mhu or hv != mh: print("FAIL: allocation count of %s in %s: report %d, model %s%d" % (n, prog, hv, ">=" if mhu else "", mh)); sys.exit(1)
        elif hv > mh: print("FAIL: allocation count lower bound of %s in %s above the model" % (n, prog)); sys.exit(1)
        if not bub:
            if mbu or bv != mb: print("FAIL: heap bytes of %s in %s: report %d, model %s%d" % (n, prog, bv, ">=" if mbu else "", mb)); sys.exit(1)
        elif bv > mb: print("FAIL: heap bytes lower bound of %s in %s above the model" % (n, prog)); sys.exit(1)
    sub, sv = stack[n]
    if sub != msu or sv != ms: print("FAIL: stack of %s in %s: report %s%d, model %s%d" % (n, prog, ">=" if sub else "", sv, ">=" if msu else "", ms)); sys.exit(1)
print("ok %-40s %d functions" % (prog, len(gas)))
PY
  checked=$((checked+1))
done
grep -qE "unbounded|finite|non-terminating" $W/audit.txt >/dev/null
CASPIEN_AUDIT_FILE=$W/audit.txt java -cp out caspien.Main -i ../tests/gas_test.caspien $W/g.hob --audit >/dev/null 2>&1
CASPIEN_AUDIT_ALL=1 CASPIEN_AUDIT_FILE=$W/audit2.txt java -cp out caspien.Main -i ../tests/heap_gas_test.caspien $W/h.hob --audit >/dev/null 2>&1
for want in "heap_nested  *288 bytes  .18 allocation operations" "heap_loop  *80 bytes  .5 alloc" "heap_branch  *32 bytes  .2 alloc" "heap_dyn  *104 bytes  .2 alloc" "heap_one  *16 bytes  .1 allocation operation" "heap_unbounded  *>= 16 bytes  .>= 1 .*(finite|unbounded)" "heap_runtime  *>= 48 bytes  .2 allocation operations.*.resize. count is not a literal"; do
  grep -qE "$want" $W/audit2.txt || { echo "FAIL: heap report lacks a line matching: $want"; exit 1; }
done
grep -qE "heap_quiet" <(sed -n '/# heap memory/,$p' $W/audit2.txt) && { echo "FAIL: heap_quiet (no allocation) is listed in the heap section"; exit 1; }
for want in "gas_loops  *595  bounded$" "gas_branch  *37  bounded$" "gas_leaf  *8  bounded$" "gas_unbounded  *>= .*finite: .for. runs a number of times only known at run time" "gas_forever  *>= [0-9]+  unbounded: .loop. with no static bound"; do
  grep -qE "$want" $W/audit.txt || { echo "FAIL: audit lacks a line matching: $want"; exit 1; }
done
# event loop: the report lists the @with_tick and @tick slices (figures from the model: slice stack = its own estimate + the event loop's frame) and NOT the event loop itself
CASPIEN_AUDIT_ALL=1 CASPIEN_AUDIT_FILE=$W/ev.txt java -cp out caspien.Main -i ../docs/examples/09_event_loop.caspien $W/e.hob --audit >/dev/null 2>&1 || { echo "FAIL: audit 09_event_loop"; exit 1; }
python3 ../tests/gas_model.py $W/e.hob > $W/emodel.txt
python3 - $W/ev.txt $W/emodel.txt <<'PY' || exit 1
import sys, re
rep = open(sys.argv[1]).read()
m = {l.split()[0]: l.split() for l in open(sys.argv[2])}
loopframe = int(m["main"][7]) - max(int(m["__caspien_main"][7]), int(m["tick"][7]))   # main reaches both slices; its own frame = its figure minus the deepest slice
for fn, kind in (("__caspien_main", "@with_tick"), ("tick", "@tick")):
    g, h, hb, st = int(m[fn][1]), int(m[fn][3]), int(m[fn][5]), int(m[fn][7]) + loopframe
    want = r"  %s \(slice: %s\) +gas %d +stack %d bytes +heap %d bytes \(%d allocation operations?\)" % (fn, kind, g, st, hb, h)
    if not re.search(want, rep): print("FAIL: event-loop slice line for", fn, "does not match the model:", want); sys.exit(1)
if re.search(r"^  main\b", rep, flags=re.M): print("FAIL: the event loop (main) is listed"); sys.exit(1)
if re.search(r"(finite|unknown|unbounded|can diverge|non-terminating):", rep.split("# worst-case execution cost", 1)[1]): print("FAIL: an event-loop program shows an unbounded class"); sys.exit(1)
PY
checked=$((checked+1))
# event-loop termination class follows the tick: no @throws -> non-terminating, throws on some paths -> unbounded, on every path -> bounded
for pair in "09_event_loop.caspien:docs/examples:loop: non-terminating" "event_loop_tick_throw_test.caspien:tests:loop: unbounded" "event_loop_tick_always_throw_test.caspien:tests:loop: bounded"; do
  f=${pair%%:*}; rest=${pair#*:}; d=${rest%%:*}; want=${rest#*:}
  CASPIEN_AUDIT_ALL=1 CASPIEN_AUDIT_FILE=$W/cls.txt java -cp out caspien.Main -i ../$d/$f $W/c.hob --audit >/dev/null 2>&1 || { echo "FAIL: audit $f"; exit 1; }
  grep -qF "#     $want (" $W/cls.txt || { echo "FAIL: $f should report '$want'"; exit 1; }
done
checked=$((checked+1))
echo "PASS gas_check: $checked programs, every function's worst-case gas, heap bytes and allocation count and stack estimate equal the path-search model"

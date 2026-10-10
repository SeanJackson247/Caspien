#!/bin/bash
# (needs java, python3) `--audit` terminology: tests/audit_class_test.caspien has one function per case; the expected class of each (first word after the figure) is written here
# from the source, not taken from the report. bounded = exact worst case; finite = always ends, bound not known; unbounded = `loop` that a break/return/throw can leave;
# none = no exit, reached on every path; conditional = only some paths never end; unknown = the audit cannot analyse it (indirect call).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W/ASTGenerator" || exit 1
CASPIEN_AUDIT_ALL=1 CASPIEN_AUDIT_FILE=$W/a.txt java -cp out caspien.Main -i ../tests/audit_class_test.caspien $W/a.hob --audit >/dev/null 2>&1 || { echo "FAIL: audit"; exit 1; }
python3 - $W/a.txt <<'PY' || exit 1
import sys, re
rep = open(sys.argv[1]).read()
gas = rep.split("# worst-case execution cost", 1)[1].split("# stack depth", 1)[0]
want = {"k_bounded": "bounded", "k_finite": "finite", "k_unbounded": "unbounded", "k_unbounded_throw": "unbounded", "k_unbounded_return": "unbounded",
        "k_forever": "none", "k_maybe_forever": "conditional", "k_calls_forever": "none", "k_calls_finite": "finite",
        "k_calls_bounded": "bounded", "k_indirect": "unknown", "k_try": "unbounded", "main": "conditional"}
words = "bounded|finite|unbounded|none|conditional|unknown"
got = {}
for l in gas.splitlines():
    m = re.match(r"  (\S+)( \(entry\))?\s+(>= )?(\d+)  (%s)(: .*)?$" % words, l)
    if m:
        got[m.group(1)] = m.group(5)
        if (m.group(3) is None) != (m.group(5) == "bounded"): print("FAIL: '>=' and the word disagree:", l); sys.exit(1)
for fn, w in want.items():
    if got.get(fn) != w: print("FAIL: %s should be %s, report says %s" % (fn, w, got.get(fn))); sys.exit(1)
if "depends on `n`" not in gas: print("FAIL: the finite `for` does not name what its bound depends on"); sys.exit(1)
if not re.search(r"^# summary: main costs at least \d+ gas \(conditional:", rep, flags=re.M): print("FAIL: summary line"); sys.exit(1)
stack = rep.split("# stack depth", 1)[1].split("# heap memory", 1)[0]
if not re.search(r"^  k_indirect\s+>= \d+ bytes  unknown: ", stack, flags=re.M) or not re.search(r"^  k_forever\s+\d+ bytes  bounded$", stack, flags=re.M):
    print("FAIL: stack classes"); sys.exit(1)
print("ok audit_class_test: %d functions have the expected class" % len(want))
PY
echo "PASS audit_class_check"

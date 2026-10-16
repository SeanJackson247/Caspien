#!/bin/bash
# (needs java, python3) `--viz`: writes an HTML page (canvas, black background) with the entry function and the functions it calls, sized by worst-case stack depth and coloured by termination class (caspien.Palette).
# The depths in the page are compared with tests/gas_model.py (an independent path search), for the two-function program of the spec and for the
# docs examples; the page is checked for the one full-page canvas and the black background; and --viz must build nothing.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target windows_gnu/target linux/; s/default: win64/default: sysv_x64/' toolchain.config
mkdir -p output
cat > hello_viz.caspien <<'SRC'
import "stdlib/libc.caspien"

func foo() void{

}

func main() void{
	foo()
	unsafe extern{
		printf("Hello World!\n")
	}
}
SRC
checked=0
for f in hello_viz.caspien docs/examples/01_basics.caspien docs/examples/10_functions.caspien docs/examples/20_stdlib_tour.caspien docs/examples/03_ownership.caspien; do
  n=$(basename $f .caspien)
  java -cp . Compiler -i $f --viz > $W/log.txt 2>&1 || { echo "FAIL: --viz $f"; cat $W/log.txt; exit 1; }
  [ -f output/$n.html ] || { echo "FAIL: no output/$n.html for $f"; exit 1; }
  [ -e output/$n ] && { echo "FAIL: --viz built an executable for $f"; exit 1; }
  (cd ASTGenerator && java -cp out caspien.Main -i ../$f $W/m.hob >/dev/null 2>&1) || { echo "FAIL: front end $f"; exit 1; }
  python3 tests/gas_model.py $W/m.hob > $W/model.txt
  python3 - output/$n.html $W/model.txt $W/m.hob $n <<'PY' || exit 1
import sys, re, json
page = open(sys.argv[1]).read(); name = sys.argv[4]
if page.count("<canvas") != 1 or "background:#000" not in page: print("FAIL: %s: not one canvas on a black page" % name); sys.exit(1)
if "100vw" not in page or "100vh" not in page: print("FAIL: %s: canvas does not take the whole page" % name); sys.exit(1)
main = json.loads(re.search(r"const MAIN = (\{.*?\});", page).group(1))
callees = json.loads(re.search(r"const CALLEES = (\[.*?\]);", page).group(1))
model = {}
for l in open(sys.argv[2]):
    p = l.split(); model[p[0]] = int(p[7])
# direct callees in the bytecode of the entry function, in order, with a body
hob = open(sys.argv[3]).read().split("\n")
entry = "__caspien_main" if "FUNC_START __caspien_main" in hob else "main"
want, inside = [], False
for l in hob:
    l = l.strip()
    if l == "FUNC_START " + entry: inside = True
    elif l == "FUNC_END" and inside: break
    elif inside and l.startswith("CALL "):
        c = l.split()[1]
        if c in model and c not in want and c != entry: want.append(c)
if main["name"] != entry or main["stack"] != model[entry]: print("FAIL: %s: entry %s" % (name, main)); sys.exit(1)
if [c["name"] for c in callees] != want: print("FAIL: %s: callees %s, expected %s" % (name, [c["name"] for c in callees], want)); sys.exit(1)
for c in callees:
    if c["stack"] != model[c["name"]]: print("FAIL: %s: stack of %s is %d, model %d" % (name, c["name"], c["stack"], model[c["name"]])); sys.exit(1)
print("ok %-28s entry %s (%d bytes), %d callees" % (name, entry, main["stack"], len(callees)))
PY
  checked=$((checked+1))
done
# colours: every node carries its termination class (the word `--audit` prints), the page has a distinct colour and a distinct outline style
# per class, and the fixture with all six classes shows all six
java -cp . Compiler -i tests/doc_comment_test.caspien --viz > $W/log.txt 2>&1 || { echo "FAIL: --viz doc_comment_test"; cat $W/log.txt; exit 1; }
java -cp . Compiler -i tests/doc_comment_test.caspien --audit > $W/audit.txt 2>&1 || { echo "FAIL: --audit doc_comment_test"; exit 1; }
python3 - output/doc_comment_test.html $W/audit.txt <<'PY' || exit 1
import sys, re, json
page = open(sys.argv[1]).read()
nodes = [json.loads(re.search(r"const MAIN = (\{.*?\});", page).group(1))] + json.loads(re.search(r"const CALLEES = (\[.*?\]);", page).group(1))
term = json.loads(re.search(r"const TERM = (\{.*?\});\n", page).group(1))
words = ["bound", "finite", "unbound", "none", "conditional", "indirect"]
if sorted(term) != sorted(words): print("FAIL: palette classes", sorted(term)); sys.exit(1)
if len({t["fill"] for t in term.values()}) != 6 or len({t["ring"] for t in term.values()}) != 6: print("FAIL: colours/outlines are not all distinct"); sys.exit(1)
audit = {}
for l in open(sys.argv[2]):
    m = re.match(r"  (\S+?)(?: \((?:entry|thread entry)\))?\s+(?:>= )?\d+\s+(bound|finite|unbound|none|conditional|indirect)\b", l)
    if m and m.group(1) not in audit: audit[m.group(1)] = m.group(2)
for n in nodes:
    if n["term"] not in term: print("FAIL: %s has class %s" % (n["name"], n["term"])); sys.exit(1)
    if audit.get(n["name"]) != n["term"]: print("FAIL: %s: page says %s, audit says %s" % (n["name"], n["term"], audit.get(n["name"]))); sys.exit(1)
seen = {n["term"] for n in nodes}
if seen != set(words): print("FAIL: the all-classes fixture shows", sorted(seen)); sys.exit(1)
print("ok colours: %d nodes, all six classes, page == audit" % len(nodes))
PY
# the spec's example: main calls foo, nothing else
grep -q '"name":"foo"' output/hello_viz.html || { echo "FAIL: foo is missing from the hello page"; exit 1; }
# a program without main is an error, not a page
printf 'func foo() void{\n}\n' > nomain.caspien
java -cp . Compiler -i nomain.caspien --viz >/dev/null 2>&1 && [ -f output/nomain.html ] && { echo "FAIL: a program without main got a page"; exit 1; }
echo "PASS viz_check: $checked pages, entry and callee stack depths equal the path-search model"

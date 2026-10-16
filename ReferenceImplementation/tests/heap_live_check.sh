#!/bin/bash
# (needs java, python3, gcc) The `--audit` "peak live heap" figure against what the program really does: tests/heap_live_test.caspien has one function per
# allocation shape; for each one a variant program whose main calls only that function is compiled and run under tests/alloc_shim.c, which measures the
# peak of live heap bytes (the ghost table's own blocks and libc's are not counted; a realloc counts as old + new live together). The audit figure of the
# function must equal the measured peak (for these shapes the analysis follows every free).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target windows_gnu/target linux/; s/default: win64/default: sysv_x64/' toolchain.config
mkdir -p output
gcc -shared -fPIC -O1 -o shim.so tests/alloc_shim.c -ldl -lpthread || { echo "FAIL: shim build"; exit 1; }
(cd ASTGenerator && CASPIEN_AUDIT_ALL=1 CASPIEN_AUDIT_FILE=$W/audit.txt java -cp out caspien.Main -i ../tests/heap_live_test.caspien $W/h.hob --audit >/dev/null 2>&1) || { echo "FAIL: audit"; exit 1; }
python3 - audit.txt > audit_live.txt <<'PY'
import sys, re
sec = open(sys.argv[1]).read().split("# peak live heap", 1)[1]
for l in sec.splitlines():
    m = re.match(r"  (\S+)(?: \(entry\))?\s+peak (>= )?(\d+) bytes", l)
    if m: print(m.group(1), "U" if m.group(2) else "-", m.group(3))
PY
[ -s audit_live.txt ] || { echo "FAIL: no peak live section in the audit"; exit 1; }
fail=0; n=0
while read name call mode; do
  src=tests/hl_$name.caspien
  python3 - tests/heap_live_test.caspien "$call" > $src <<'PY'
import sys
t = open(sys.argv[1]).read()
i = t.index("func main() void{")
print(t[:i] + "func main() void{\n\tlet r = mut %s\n\tunsafe extern{ printf(\"%%llu\\n\", r) }\n}" % sys.argv[2])
PY
  java -cp . Compiler -i $src output/hl_$name > compile.log 2>&1 || { echo "FAIL: compile $name"; tail -3 compile.log; fail=1; continue; }
  addr=$(nm output/hl_$name | awk '$3=="ghost_table"{print $1}')
  peak=$(GT_ADDR=$addr GT_EXE=$W/output/hl_$name LD_PRELOAD=$W/shim.so ./output/hl_$name 2>&1 >/dev/null | sed -n 's/.*PEAK=\([0-9]*\).*/\1/p')
  fn=${call%%(*}
  want=$(awk -v f=$fn '$1==f{print $3}' audit_live.txt)
  if [ -z "$peak" ] || [ -z "$want" ]; then echo "FAIL: $name: measured '$peak', audit '$want'"; fail=1; continue; fi
  if [ "$mode" = ">=" ]; then
    if [ "$want" -lt "$peak" ]; then echo "FAIL: $name: the program really peaks at $peak live bytes, the audit says only $want (must be an upper bound)"; fail=1; continue; fi
  elif [ "$peak" != "$want" ]; then echo "FAIL: $name: the program really peaks at $peak live bytes, the audit says $want"; fail=1; continue; fi
  echo "ok $name: peak $peak bytes"
  n=$((n+1))
done <<'LIST'
one sc_one()
loop sc_loop()
three sc_three()
branch sc_branch(5)
callee sc_callee()
move_param sc_move_param()
returned sc_returned()
resize sc_resize()
box sc_box()
cond_move0 sc_cond_move(0)
cond_move5 sc_cond_move(5)
throwing1 sc_throwing(1)
throwing9 sc_throwing(9)
calls_in_loop sc_calls_in_loop()
mem_seq sc_mem_seq()
mem_inline_seq sc_mem_inline_seq()
mem_three_seq sc_mem_three_seq()
mem_param sc_mem_param()
mem_returned_seq sc_mem_returned_seq()
mem_moved_out sc_mem_moved_out() >=
mem_replaced sc_mem_replaced() >=
LIST
# the audit says the loop-of-calls function leaves nothing live and `sc_loop` does not depend on its trip count
grep -q "^sc_loop - 16$" audit_live.txt || { echo "FAIL: sc_loop is not bound at 16"; fail=1; }
[ $fail = 0 ] && echo "PASS heap_live_check: $n scenarios, audit peak live heap == measured peak" || exit 1

#!/bin/bash
# inline-max-multi-callee-lines (Optimizer, FunctionInliningPass): a callee longer than the limit is inlined only when the program calls it from
# exactly one place. tests/inline_multi_callee_test.caspien has `big` (long, 2 call sites), `once` (equally long, 1 site) and `small` (short, 2 sites),
# compiled at `function-inlining: aggressive` in a scratch copy with a Linux target. Output must equal the model (inline_multi_callee_test.py) in every
# case; the optimised HOB must still call `big` when the limit is below its size, never call `once` or `small`, and call nothing when the rule is off (0).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/; s/^function-inlining: .*/function-inlining: aggressive/' toolchain.config
grep -q '^function-inlining: aggressive' toolchain.config || { echo "FAIL: no function-inlining key in toolchain.config"; exit 1; }
python3 tests/inline_multi_callee_test.py > model.txt
fail=0
run() { # label key-line expect_big expect_once expect_small
  sed -i '/^inline-max-multi-callee-lines:/d' toolchain.config
  [ -n "$2" ] && sed -i "s/^function-inlining: aggressive/&\n$2/" toolchain.config
  rm -rf .build output
  timeout 120 java -Xmx1g Compiler -i tests/inline_multi_callee_test.caspien prog --no-cache >compile.log 2>&1 || { echo "FAIL: $1 compile"; tail -5 compile.log; fail=1; return; }
  ./prog > got.txt
  cmp -s got.txt model.txt || { echo "FAIL: $1 output differs from the model"; diff got.txt model.txt; fail=1; return; }
  H=.build/2_optimizer.hob.txt
  b=$(grep -c -E '^[[:space:]]*CALL big[[:space:]]*$' $H || true); o=$(grep -c -E '^[[:space:]]*CALL once[[:space:]]*$' $H || true); s=$(grep -c -E '^[[:space:]]*CALL small[[:space:]]*$' $H || true)
  if [ "$b" = "$3" ] && [ "$o" = "$4" ] && [ "$s" = "$5" ]; then echo "PASS $1 (calls left: big $b, once $o, small $s)"
  else echo "FAIL: $1 calls left big=$b once=$o small=$s, expected $3 $4 $5"; fail=1; fi
}
run "limit 100 (below big/once, above small)" "inline-max-multi-callee-lines: 100" 2 0 0
run "limit 0 (rule off)" "inline-max-multi-callee-lines: 0" 0 0 0
run "default limit (1000, above all three)" "" 0 0 0
[ $fail = 0 ] && echo "PASS inline_multi_callee_check" || exit 1

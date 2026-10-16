#!/bin/bash
# Inliner growth budgets: tests/inline_budget_test.caspien (a 3-way fan-out call chain) compiled at `function-inlining: aggressive` in a
# scratch copy with a Linux target. Unbounded inlining made this program grow 3x per level (OOM on bigger chains); with the per-function
# and program-wide budgets it must compile quickly, print PASS, and keep the optimized HOB under a size bound. Also checks that
# the inline-max-growth key still tightens it (the factor keys have floors of 2000 / 50000 lines, so tiny test programs never reach them).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/; s/^function-inlining: .*/function-inlining: aggressive/' toolchain.config
grep -q '^function-inlining: aggressive' toolchain.config || { echo "FAIL: no function-inlining key in toolchain.config"; exit 1; }
run() { # label maxlines [extra config line]
  [ -n "$3" ] && sed -i "s/^function-inlining: aggressive/&\n$3/" toolchain.config
  rm -rf .build output
  timeout 120 java -Xmx1g Compiler -i tests/inline_budget_test.caspien prog >compile.log 2>&1 || { echo "FAIL: $1 compile"; tail -5 compile.log; exit 1; }
  ./prog | grep -q '^PASS chain sum' || { echo "FAIL: $1 output"; ./prog; exit 1; }
  n=$(wc -l < .build/2_optimizer.hob.txt)
  [ "$n" -le "$2" ] || { echo "FAIL: $1 optimized HOB has $n lines (limit $2)"; exit 1; }
  echo "PASS $1 ($n lines)"
}
run "aggressive default budgets" 20000
run "inline-max-growth 300 per function" 6000 "inline-max-growth: 300"

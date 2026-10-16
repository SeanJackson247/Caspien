#!/bin/bash
# assume_ref_some_test: output equals the Python model, valgrind clean, (the 4-config sweep of run_tests.py covers the other configs); and `assume match Some(r)` on a
# nullable ref resolves the id once (fiveUses has exactly one GT_REF_RESOLVE in its higher-order bytecode, like the real-match twin viaMatch).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 tests/assume_ref_some_test.py >want.txt
for cfg in plain; do
  java Compiler -i tests/assume_ref_some_test.caspien prog_$cfg >c.log 2>&1 || { echo "FAIL: compile ($cfg)"; tail -3 c.log; exit 1; }
  ./prog_$cfg >out.txt 2>&1 || { echo "FAIL: exit code ($cfg)"; exit 1; }
  diff want.txt out.txt >/dev/null || { echo "FAIL: output differs from the model ($cfg)"; diff want.txt out.txt | head; exit 1; }
done
valgrind -q --error-exitcode=9 ./prog_plain >/dev/null 2>vg.txt || { echo "FAIL: valgrind"; head vg.txt; exit 1; }
(cd ASTGenerator && java -cp out caspien.Main -i ../tests/assume_ref_some_test.caspien ../hob.txt >/dev/null 2>&1)
for f in fiveUses viaMatch; do
  n=$(awk "/^FUNC_START $f\$/,/^FUNC_END/" hob.txt | grep -c '^GT_REF_RESOLVE')
  [ "$n" = 1 ] || { echo "FAIL: $f has $n GT_REF_RESOLVE, want 1"; exit 1; }
done
echo "PASS assume_ref_some_check: output equals the model in the linux config, valgrind clean, one resolve per assume"

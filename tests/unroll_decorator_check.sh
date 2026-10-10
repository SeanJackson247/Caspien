#!/bin/bash
# `@unroll`, `@unroll(N)`, `@dont(unroll)` on a `for` (tests/unroll_decorator_test.caspien): results stay right, the optimizer says what it did
# ([note] applied / kept rolled), warns when a request cannot be met ([warning]), honours the decorator even with `loop-unrolling: off`, and the
# messages are replayed from the build cache. Needs Linux, java, gcc.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
fail(){ echo "FAIL: $*"; exit 1; }
T=tests/unroll_decorator_test.caspien
setpreset(){ sed -i "s/^loop-unrolling: .*/loop-unrolling: $1/" toolchain.config; }
run(){ java Compiler -i $T output/ut --cache-report >msg.txt 2>&1 || { cat msg.txt; fail "compile ($1)"; }; ./output/ut >run.txt; }
for preset in off aggressive; do
  setpreset $preset
  run "preset $preset"
  [ "$(grep -c '^PASS' run.txt)" = 9 ] && ! grep -q FAIL run.txt || { cat run.txt; fail "results wrong with preset $preset"; }
  [ "$(grep -c '\[note\].*@unroll: unrolled' msg.txt)" = 8 ] || { cat msg.txt; fail "expected 8 applied notes with preset $preset"; }
  [ "$(grep -c '\[warning\].*@unroll not honoured' msg.txt)" = 1 ] || fail "expected one 'not honoured' warning with preset $preset"
  grep -q 'unroll_decorator_test.caspien:68 - @unroll not honoured: its bounds are not compile-time constants (range 0..mut_n)' msg.txt || fail "warning text/position"
  grep -q 'unroll_decorator_test.caspien:29 - @unroll: unrolled by 3 (10 iterations' msg.txt || fail "@unroll(3) note"
done
# the heuristic would unroll the 4-trip loop; @dont(unroll) keeps it and says so (only with a preset that unrolls it)
grep -q '@dont(unroll): this loop is kept as a loop' msg.txt || fail "no @dont note under the aggressive preset"
setpreset off; run off
grep -q '@dont(unroll)' msg.txt && fail "@dont note should not appear when nothing would have unrolled the loop"
# a second compile is served from the cache and prints the same notes and warnings
java Compiler -i $T output/ut --cache-report >msg2.txt 2>&1 || fail "second compile"
grep -q '\[cache\] s2: hit' msg2.txt || { cat msg2.txt; fail "second compile was not a cache hit"; }
diff <(grep '^\[note\]\|^\[warning\]' msg.txt) <(grep '^\[note\]\|^\[warning\]' msg2.txt) >/dev/null || fail "notes/warnings not replayed from the cache"
# bad factors are compile errors
for bad in '@unroll(1)' '@unroll(65)' '@unroll(2, 3)' '@unroll(abc)'; do
cat >bad.caspien <<EOF
import "stdlib/libc.caspien"
func main() void{
	let s = mut 0
	$bad
	for i in 0..4{
		s = s + i
	}
}
EOF
  java Compiler -i bad.caspien output/bad >bad.txt 2>&1 && fail "$bad should not compile"
done
echo "PASS unroll_decorator_check: results, notes, warning, off-preset, @dont, cache replay, bad factors"

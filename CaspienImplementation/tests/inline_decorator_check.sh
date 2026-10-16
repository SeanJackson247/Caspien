#!/bin/bash
# `@inline` / `@dont(inline)` on a function (tests/inline_decorator_test.caspien): results stay right, `@inline` is honoured even with
# `function-inlining: off` and past a preset's callee-size limit, `@dont(inline)` keeps the call under every preset, the optimizer says what it did,
# and an `@inline` call that cannot be inlined (inside another call's argument list) is a warning. Needs Linux, java, gcc.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
fail(){ echo "FAIL: $*"; exit 1; }
T=tests/inline_decorator_test.caspien
H=output/.build/2_optimizer.hob.txt
calls(){ grep -c "^CALL $1\$" $H; }
for preset in off conservative aggressive; do
  sed -i "s/^function-inlining: .*/function-inlining: $preset/" toolchain.config
  java Compiler -i $T output/it --no-cache >msg.txt 2>&1 || { cat msg.txt; fail "compile ($preset)"; }
  ./output/it >run.txt
  [ "$(grep -c '^PASS' run.txt)" = 6 ] && ! grep -q FAIL run.txt || { cat run.txt; fail "results wrong with preset $preset"; }
  for f in add3 big twice; do [ "$(calls $f)" = 0 ] || fail "$f should be inlined everywhere with preset $preset"; done
  [ "$(calls noinl)" = 1 ] || fail "@dont(inline) noinl should stay a call with preset $preset"
  grep -q '@inline: add3 inlined at 7 call sites' msg.txt || { cat msg.txt; fail "note for add3 ($preset)"; }
  grep -q '@inline: big inlined at 1 call site' msg.txt || fail "note for big ($preset)"
  grep -q '\[warning\].*@inline' msg.txt && fail "unexpected @inline warning ($preset)"
done
sed -i "s/^function-inlining: .*/function-inlining: off/" toolchain.config
java Compiler -i $T output/it --no-cache >/dev/null 2>&1; [ "$(calls plain)" = 1 ] || fail "undecorated plain must stay a call with inlining off"
sed -i "s/^function-inlining: .*/function-inlining: aggressive/" toolchain.config
java Compiler -i $T output/it --no-cache >/dev/null 2>&1; [ "$(calls plain)" = 0 ] || fail "undecorated plain should be inlined by the aggressive preset"
# a call that cannot be inlined: inside another call's argument list
cat >warn.caspien <<EOF
import "stdlib/libc.caspien"
import "stdlib/gt/*"
@inline
func add3(x: mut u64) mut u64{
	return x + 3
}
func show(a: mut u64, b: mut u64) void{
	unsafe extern{
		printf("%llu %llu\n", a, b)
	}
}
func main() void{
	show(1, add3(4))
}
EOF
sed -i "s/^function-inlining: .*/function-inlining: off/" toolchain.config
java Compiler -i warn.caspien output/w --no-cache >msg.txt 2>&1 || { cat msg.txt; fail "warn.caspien compile"; }
grep -q 'warn.caspien:3 - @inline not honoured at 1 call site of add3 (0 inlined)' msg.txt || { cat msg.txt; fail "no warning for an @inline call inside an argument list"; }
./output/w | grep -q '^1 7$' || fail "warn.caspien result"
echo "PASS inline_decorator_check: results, off-preset, past the size limit, @dont, notes, warning"

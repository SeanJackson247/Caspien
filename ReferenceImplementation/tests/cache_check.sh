#!/bin/bash
# Build cache (CompilerCache): hits, misses and what invalidates what. Needs Linux, java, gcc. Runs in a scratch copy of the tree.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
mkdir -p t; fails=0
fail(){ echo "FAIL: $*"; fails=$((fails+1)); }
# build prints "hit"/"miss" per stage as one string such as "s1:hit s2:miss ..."
build(){ java Compiler -i "$1" "$2" --cache-report ${3:-} 2>&1 | sed -n 's/^\[cache\] \(s[0-9]\): \(hit\|miss\)$/\1:\2/p' | tr '\n' ' '; }
expect(){ [ "$1" = "$2" ] || fail "$3: expected '$2' got '$1'"; }
ALLMISS="s1:miss s2:miss s3:miss s4:miss s5:miss "; ALLHIT="s1:hit s2:hit s3:hit s4:hit s5:hit "
printf '@pub\nfunc helper() mut u64{\n\treturn 7\n}\n' > t/imp.caspien
cat > t/main.caspien <<'P'
import "../stdlib/libc.caspien"
import "imp.caspien"

func main() void{
	unsafe extern{
		printf("%llu\n", helper())
	}
}
P
expect "$(build t/main.caspien t/p)" "$ALLMISS" "cold build"; expect "$(./t/p)" 7 "cold result"
expect "$(build t/main.caspien t/p)" "$ALLHIT" "warm build"; expect "$(./t/p)" 7 "warm result (from the cache)"
sed -i 's/return 7/return 9/' t/imp.caspien
expect "$(build t/main.caspien t/p)" "$ALLMISS" "imported file changed"; expect "$(./t/p)" 9 "result after the import changed"
echo "// a comment" >> t/imp.caspien
expect "$(build t/main.caspien t/p)" "s1:miss s2:hit s3:hit s4:hit s5:hit " "comment edit stops at the front end"
cp toolchain.config tc.orig
sed -i 's/^loop-unrolling:.*/loop-unrolling: aggressive/; s/^function-inlining:.*/function-inlining: aggressive/' toolchain.config
r="$(build t/main.caspien t/p)"; case "$r" in "s1:hit s2:miss "*) ;; *) fail "optimiser switch: front end must stay cached, optimiser must rerun: got '$r'";; esac   # later stages may hit: the program has no loop to unroll, so stage 2 output is unchanged
sed -i 's/^deferred-operands:.*/deferred-operands: off/' toolchain.config
r="$(build t/main.caspien t/p)"; case "$r" in "s1:hit s2:hit s3:miss s4:miss s5:miss ") ;; *) fail "register-form switch: got '$r'";; esac
cp tc.orig toolchain.config
expect "$(build t/main.caspien t/p --no-cache)" "" "--no-cache prints nothing"
printf 'func broken( {\n' > t/bad.caspien
java Compiler -i t/bad.caspien t/q >/dev/null 2>&1 && fail "broken program compiled"
java Compiler -i t/bad.caspien t/q >/dev/null 2>&1 && fail "broken program compiled (second time)"
expect "$(build t/bad.caspien t/q 2>/dev/null)" "s1:miss " "a failed build is never cached"
# warnings are replayed on a hit, and --no-warnings still silences them
cp tests/instanceof_warn_test.caspien t/w.caspien; sed -i 's#\.\./stdlib#../stdlib#' t/w.caspien
java Compiler -i t/w.caspien t/w >/dev/null 2>t/w1.log || fail "warning program"
java Compiler -i t/w.caspien t/w >/dev/null 2>t/w2.log || fail "warning program, cached"
[ "$(grep -c 'can never be true' t/w1.log)" = 2 ] && [ "$(grep -c 'can never be true' t/w2.log)" = 2 ] || fail "warnings not replayed on a hit"
java Compiler -i t/w.caspien t/w --no-warnings >/dev/null 2>t/w3.log; [ "$(grep -c 'can never be true' t/w3.log)" = 0 ] || fail "--no-warnings ignored on a hit"
java Compiler --clear-cache >/dev/null 2>&1; [ ! -e .cache ] || fail "--clear-cache left .cache"
if [ "$fails" = 0 ]; then echo "PASS cache_check: hits, misses, invalidation, warnings, failures"; else exit 1; fi

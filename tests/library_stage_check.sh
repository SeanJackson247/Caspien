#!/bin/bash
# All four stages (ASTGenerator `Frontend`, Optimizer, LowerOrderGenerator, Codegen) are libraries the orchestrator calls in its own JVM;
# only the assembler/linker are separate processes. Also checks that each library is still the same program as its stand-alone Main. Needs Linux, java, gcc.
# Runs in a scratch copy of the tree.   tests/library_stage_check.sh
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
unset JAVA_TOOL_OPTIONS
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
mkdir -p t; fails=0
fail(){ echo "FAIL: $*"; fails=$((fails+1)); }
ok(){ echo "PASS $*"; }

# 1. the orchestrator launches no stage as a program
if grep -q '\.Main"' Compiler.java; then fail "Compiler.java still names a stage Main class"; else ok "Compiler.java launches no stage Main"; fi
grep -q 'runLibraryStage(cache, "s2"' Compiler.java && grep -q 'runLibraryStage(cache, "s3"' Compiler.java && grep -q 'runLibraryStage(cache, "s4"' Compiler.java && grep -q 'caspien.Frontend' Compiler.java && ok "all four stages go through library calls" || fail "a stage is not a library call"
grep -q 'optText, optOut.toString(), lobOut' Compiler.java && ok "the Optimizer's text is passed straight to the LowerOrderGenerator" || fail "stage 3 does not take the in-memory Optimizer text"

# 2. a real build: no child JVM at all (the one `java` is the orchestrator itself)
cat > t/p.caspien <<'P'
import "../stdlib/libc.caspien"
func main() void{
	unsafe extern{
		printf("%llu\n", mut 41 + mut 1)
	}
}
P
if command -v strace >/dev/null; then
  strace -f -e trace=execve -o t/ex.log java Compiler -i t/p.caspien t/p --no-cache >t/build.log 2>&1
  n=$(grep -c 'execve("[^"]*/java"' t/ex.log)
  [ "$n" = 1 ] && ok "no stage runs as a separate JVM" || fail "java started $n times"
else
  java Compiler -i t/p.caspien t/p --no-cache >t/build.log 2>&1; echo "SKIP process count (no strace)"
fi
[ "$(./t/p)" = "42" ] && ok "the program built through the libraries runs" || fail "built program prints '$(./t/p)'"

# 3. each library equals its stand-alone program on the same input and config
B=t/.build
java Compiler -i t/p.caspien t/p2 --no-cache >/dev/null 2>&1
java Compiler -i t/p.caspien t/h --hob --no-cache >/dev/null 2>&1
cp t/.build/1_*.txt t/hob_lib.txt 2>/dev/null
( cd Optimizer && java -cp out caspien.optimizer.Main -i "$W/t/h" "$W/t/opt_standalone.txt" >/dev/null 2>&1 ; rm -f output.txt )
cmp -s t/opt_standalone.txt t/.build/2_optimizer.hob.txt && ok "Optimizer library == stand-alone Main" || fail "Optimizer library output differs from its Main"
( cd LowerOrderGenerator && java -cp out caspien.lowerorder.Main -i "$W/t/.build/2_optimizer.hob.txt" "$W/t/lob_standalone.txt" >/dev/null 2>&1 ; rm -f output.txt )
cmp -s t/lob_standalone.txt t/.build/3_lower_order_generator.lob.txt && ok "LowerOrderGenerator library == stand-alone Main" || fail "LowerOrderGenerator library output differs from its Main"

( cd ASTGenerator && java -cp out caspien.Main -i "$W/t/p.caspien" "$W/t/hob_standalone.txt" >/dev/null 2>&1 ; rm -f output.txt debug.txt )
cmp -s t/hob_standalone.txt t/.build/1_ast_generator.hob.txt && ok "ASTGenerator library == stand-alone Main" || fail "ASTGenerator library output differs from its Main"
( cd Codegen && java -cp out caspien.codegen.Main -i "$W/t/.build/3_lower_order_generator.lob.txt" "$W/t/asm_standalone.s" >/dev/null 2>&1 ; rm -f output.txt )
cmp -s t/asm_standalone.s t/.build/4_codegen.s && ok "Codegen library == stand-alone Main" || fail "Codegen library output differs from its Main"

# 4. notes from a library stage reach the user, on a miss and replayed from the cache
cat > t/n.caspien <<'P'
import "../stdlib/libc.caspien"
func f() mut u64{
	let s = mut 0
	@unroll
	for i in 0..8{
		s = s + i * i
	}
	return s
}
func main() void{
	unsafe extern{
		printf("%llu\n", f())
	}
}
P
java Compiler -i t/n.caspien t/n --no-cache 2>&1 | grep -q '\[note\].*@unroll' && ok "[note] from the Optimizer library is shown" || fail "no [note] from the Optimizer library"
java Compiler -i t/n.caspien t/n >/dev/null 2>&1; java Compiler -i t/n.caspien t/n 2>&1 | grep -q '\[note\].*@unroll' && ok "[note] is replayed from the cache" || fail "[note] not replayed on a cache hit"

# 5. a crash inside a library stage is reported like a crashed process (stack trace, nonzero exit)
sed -i 's/^constant-folding: .*/constant-folding: maybe/' toolchain.config
java Compiler -i t/p.caspien t/bad --no-cache >t/bad.log 2>&1; rc=$?
if [ $rc -ne 0 ] && grep -q 'failed' t/bad.log; then ok "a failing stage fails the build (exit $rc)"; else fail "bad config did not fail the build (rc=$rc)"; fi
[ $fails -eq 0 ] && echo "ALL LIBRARY STAGE CHECKS PASSED" || { echo "$fails check(s) failed"; exit 1; }

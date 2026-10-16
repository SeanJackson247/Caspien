#!/bin/bash
# `--fix unsafe-split`: big `unsafe unaudited{}` blocks are rewritten into minimal blocks with exactly the tags each statement needs. The rewritten
# program compiles (so the tags are exact), prints what it printed before, a second run changes nothing, a rewrite that cannot be verified is left
# as written while the others go through, and `unsafe-split,justify` does not scaffold a block twice.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache --exclude=benchmarks -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
fail() { echo "FAIL: $1"; exit 1; }
build() { java Compiler -i "$1" "$2" >b.log 2>&1 || { tail -3 b.log; fail "$1 does not compile"; }; }
A=tests/unsafe_split_test.caspien
B=tests/unsafe_split_fallback_test.caspien
cp $A orig_a.caspien; cp $B orig_b.caspien
build $A pa; ./pa > out_a.txt
build $B pb; ./pb > out_b.txt
[ "$(wc -l < out_a.txt)" -ge 3 ] || fail "baseline output"

# dry run changes nothing and names the plan
sum0=$(md5sum $A | cut -d' ' -f1)
java Compiler -i $A --fix unsafe-split > dry.log 2>&1 || { tail -3 dry.log; fail "dry run failed"; }
[ "$sum0" = "$(md5sum $A | cut -d' ' -f1)" ] || fail "dry run modified the file"
grep -q "unsafe block" dry.log && grep -q "unsafe extern (2 statements)" dry.log || fail "dry run plan"

# write: verified, no unaudited left, program compiles (exact tags) and behaves the same
java Compiler -i $A --fix unsafe-split --write > w.log 2>&1 || { tail -3 w.log; fail "write failed"; }
grep -q "verified: the program compiles to identical bytecode" w.log || fail "no verification line"
grep -q "^[[:space:]]*unsafe unaudited" $A && fail "unaudited left in $A"
grep -q "^[[:space:]]*unsafe udyn{" $A || fail "expected an unsafe udyn block"
grep -q "justify: the whole demo is deliberately unsafe" $A || fail "existing justification lost"
n_unsafe=$(grep -c "^[[:space:]]*unsafe " $A); n_todo=$(grep -c "justify: TODO" $A)
[ "$n_todo" -eq $((n_unsafe - 1)) ] || fail "scaffolds: $n_todo TODO for $n_unsafe blocks (one keeps its text)"
build $A pa2; ./pa2 > out_a2.txt
cmp -s out_a.txt out_a2.txt || fail "output changed after the rewrite"

# a second run changes nothing
sum1=$(md5sum $A | cut -d' ' -f1)
java Compiler -i $A --fix unsafe-split --write > w2.log 2>&1 || { tail -3 w2.log; fail "second write failed"; }
grep -q "rewritten into minimal blocks" w2.log && fail "second run still rewrites"
[ "$sum1" = "$(md5sum $A | cut -d' ' -f1)" ] || fail "second run modified the file"

# an unverifiable rewrite is left alone, the other goes through
java Compiler -i $B --fix unsafe-split --write > f.log 2>&1 || { tail -3 f.log; fail "fallback run failed"; }
grep -q "1 rewritten and verified, 1 left as written" f.log || { cat f.log | grep "^#"; fail "fallback summary"; }
[ "$(grep -c "^[[:space:]]*unsafe unaudited{" $B)" -eq 1 ] || fail "the unverifiable block must stay as written"
build $B pb2; ./pb2 > out_b2.txt
cmp -s out_b.txt out_b2.txt || fail "fallback output changed"

# unsafe-split,justify together: no block gets two scaffolds
cp orig_a.caspien $A
java Compiler -i $A --fix unsafe-split,justify --write > j.log 2>&1 || { tail -3 j.log; fail "combined write failed"; }
n_unsafe=$(grep -c "^[[:space:]]*unsafe " $A); n_just=$(grep -c "justify:" $A | head -1)
[ "$n_just" -eq "$n_unsafe" ] || fail "combined: $n_just justify comments for $n_unsafe blocks"
build $A pa3; ./pa3 | cmp -s - out_a.txt || fail "combined: output changed"
echo "PASS unsafe_split_check: split verified, exact tags, idempotent, per-block fallback, combined with justify"

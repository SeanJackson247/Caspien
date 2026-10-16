#!/bin/bash
# Doc comments (`/*! key: text */`, `//! key: text`): the labelled test program compiles and runs; every wrong label, misplaced comment, bad key or
# bad value is a compile error with the expected message; --audit prints the justify text and counts unjustified blocks.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache --exclude=benchmarks -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
fail() { echo "FAIL: $1"; exit 1; }
T=tests/doc_comment_test.caspien
java Compiler -i $T prog >c.log 2>&1 || { tail -3 c.log; fail "labelled program does not compile"; }
[ "$(./prog)" = "PASS doc_comment_test" ] || fail "labelled program output"
# expect <name> <sed script> <message fragment>: the mutated test program must fail with the fragment
n=0
expect() {
    n=$((n+1))
    sed "$2" $T >tests/mut_doc.caspien
    if java Compiler -i tests/mut_doc.caspien /dev/null --hob >m.log 2>&1; then fail "$1: compiled"; fi
    grep -qF -- "$3" m.log || { tail -4 m.log; fail "$1: expected message containing: $3"; }
}
L() { grep -n "$1" $T | head -1 | cut -d: -f1; }
fl=$(L 'termination: finite \*/'); expect "function label too strong" "${fl}s/finite/bound/" "function \`k_finite\` is labelled \`bound\` but its termination class is \`finite\`"
expect "function label too weak" "$(L 'termination: bound \*/')s/bound/finite/" "is labelled \`finite\` but its termination class is \`bound\`"
fl=$(L 'termination: none \*/'); expect "none labelled unbound" "${fl}s/none/unbound/" "is labelled \`unbound\` but its termination class is \`none\`"
expect "conditional labelled none" "$(L 'termination: conditional \*/')s/conditional/none/" "labelled \`none\` but its termination class is \`conditional\`"
kb=$(grep -n 'func k_bounded' $T | head -1 | cut -d: -f1); expect "for labelled finite" "$((kb+2))s/bound/finite/" "\`for\` in \`k_bounded\` is labelled \`finite\` but its termination class is \`bound\`"
expect "indirect labelled bound" "$(L 'termination: indirect')s/indirect/bound/" "labelled \`bound\` but its termination class is \`indirect\`"
expect "unknown key" "$(L '^[[:space:]]*/\*! justify:')s/justify:/proof:/" "unknown doc comment key 'proof'"
expect "no colon" "$(L '^[[:space:]]*/\*! justify:')s#/\*!.*\*/#/*! just so */#" "doc comment must read"
expect "empty text" "$(L '^[[:space:]]*/\*! justify:')s#justify:.*\*/#justify: */#" "has no text"
expect "bad class" "$(L 'termination: indirect')s/indirect/maybe/" "termination class 'maybe' is not one of"
expect "old class name" "$(L 'termination: none \*/')s/none/non-terminating/" "termination class 'non-terminating' is not one of"
expect "duplicate" "$(L 'termination: indirect')a /*! termination: indirect */" "appears twice before the same"
# placement
ll=$(L '^[[:space:]]*/\*! termination: bound \*/$'); ml=$(grep -n 'match @lock box' $T | head -1 | cut -d: -f1)
expect "match @lock labelled finite" "$((ml-1))s/bound/finite/" "is labelled \`finite\` but its termination class is \`bound\`"
P() { printf 'import "stdlib/print.caspien"\nimport "stdlib/gt/*"\n%b\n' "$1" >pl.caspien; if java Compiler -i pl.caspien /dev/null --hob >m.log 2>&1; then fail "placement '$2': compiled"; fi; grep -qF -- "$3" m.log || { tail -3 m.log; fail "placement '$2': expected $3"; }; n=$((n+1)); }
P 'func main() void{\n\t/*! justify: no reason */\n\tlet a = mut 1\n\tprintln(a)\n}' "justify before let" "'justify:' doc comment is not expected here"
P '/*! justify: not a block */\nfunc main() void{\n}' "justify before func" "'justify:' doc comment is not expected here"
P 'func main() void{\n\tlet a = mut 1\n\t/*! termination: bound */\n\tif a == 1{ println(a) }\n}' "termination before if" "'termination:' doc comment is not expected here"
P 'func main() void{\n\tlet n = mut 0\n\t/*! termination: unbound */\n\tunsafe loop{\n\t\tloop{ n += 1\n\t\t\tif n > 2{ break } }\n\t}\n}' "termination before unsafe loop" "(found \`unsafe\`"
P 'func main() void{\n\tlet a = mut 1\n\t/*! termination: bound */\n\tmatch a{ default:{ println(a) } }\n}' "termination before a plain match" "'termination:' doc comment is not expected here"
P 'func main() void{\n\t//! termination: bound\n}' "termination before a brace" "is not expected here"
P 'func main() void{\n}\n//! justify: trailing' "comment at the end" "nothing follows it"
P 'func main() void{\n\tprintln(1) //! justify: same line\n}' "trailing comment on a statement" "is not expected here"
# accepted: no label at all, a decorator between the label and its func, a plain comment between, //! on a func
printf 'import "stdlib/print.caspien"\nimport "stdlib/gt/*"\n//! termination: bound\n@inline\nfunc one() mut u64{ return 1 }\n\n/*! termination: bound */\n// an ordinary comment in between\nfunc two() mut u64{ return 2 }\nfunc main() void{\n\tprintln(one() + two())\n}\n' >ok.caspien
java Compiler -i ok.caspien okprog >m.log 2>&1 || { tail -3 m.log; fail "decorator/plain comment between label and func"; }
[ "$(./okprog)" = "3" ] || fail "ok program output"
# --audit: justify text and the counts
java Compiler -i $T --audit --audit-no-stdlib >a.txt 2>&1
grep -qF "justify: a bare loop has no bound; this test exists to be audited" a.txt || fail "audit does not print the justify text"
grep -qE "^# justified blocks: 4 of 7 \(3 without a justify comment, 0 with \`justify: TODO\`\)" a.txt || { grep "justified blocks" a.txt; fail "audit justified count"; }
grep -qE "justify: \(none\)" a.txt || fail "audit does not mark a block with no justify"
# --fix: dry run writes nothing; --write inserts labels and TODO justifications, verifies identical bytecode, is idempotent, keeps existing text
cp tests/audit_class_test.caspien tests/fixcopy.caspien
cp tests/fixcopy.caspien before.txt
java Compiler -i tests/fixcopy.caspien --fix termination,justify >f.txt 2>&1 || { tail -3 f.txt; fail "--fix dry run failed"; }
cmp -s tests/fixcopy.caspien before.txt || fail "dry run changed the file"
grep -q "(dry run" f.txt || fail "dry run header"
java Compiler -i tests/fixcopy.caspien --fix termination,justify --write >f.txt 2>&1 || { tail -3 f.txt; fail "--fix --write failed"; }
grep -q "# verified: the program compiles to identical bytecode" f.txt || { tail -3 f.txt; fail "no verification line"; }
[ "$(grep -c 'termination:' tests/fixcopy.caspien)" -ge 20 ] || fail "labels were not inserted"
grep -q "/\*! termination: conditional \*/" tests/fixcopy.caspien || fail "conditional label missing"
java Compiler -i tests/fixcopy.caspien prog4 >c.log 2>&1 || { tail -3 c.log; fail "labelled copy does not compile"; }
[ "$(./prog4)" = "PASS audit_class_test" ] || fail "labelled copy output"
java Compiler -i tests/fixcopy.caspien --fix termination,justify --write >f.txt 2>&1
grep -q "^# 0 comment lines in 0 files" f.txt || fail "second --fix run is not a no-op"
java Compiler -i tests/fixcopy.caspien --audit --audit-no-stdlib >a2.txt 2>&1
grep -qE "^# justified blocks: 0 of 7 \(0 without a justify comment, 7 with" a2.txt || { grep "justified blocks" a2.txt; fail "TODO justifications not counted"; }
# a human-written justification is never replaced
sed -i '0,/justify: TODO/s//justify: kept as written/' tests/fixcopy.caspien
java Compiler -i tests/fixcopy.caspien --fix justify --write >f.txt 2>&1
grep -q "kept as written" tests/fixcopy.caspien || fail "--fix replaced an existing justification"
# a program whose output depends on line numbers cannot be proven unchanged: the edit is undone
printf 'import "stdlib/print.caspien"\nimport "stdlib/gt/*"\nfunc main() void{\n\tprintln(__LINE)\n}\n' >tests/linedep.caspien
cp tests/linedep.caspien before2.txt
java Compiler -i tests/linedep.caspien --fix termination --write >f.txt 2>&1 && fail "line-dependent program was accepted"
cmp -s tests/linedep.caspien before2.txt || fail "file not restored after a failed verification"
echo "PASS doc_comment_check: $n error cases, labelled program runs, audit shows justify text and counts, --fix verified/idempotent/restoring"

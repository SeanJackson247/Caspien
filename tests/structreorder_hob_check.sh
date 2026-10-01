#!/bin/bash
# Bytecode-level check for the Optimizer's StructMemberReorderingPass (`struct-member-reordering: on`). Hand-made HOB files go through the prebuilt Optimizer.
# Each case prints the declaration of struct S (members only) and the body of main, compared with the hand-written expectation.
#   tests/structreorder_hob_check.sh        (needs Optimizer/out, i.e. the shipped prebuilt classes)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); cd "$W" || exit 1
export JAVA_TOOL_OPTIONS=
bad=0
# S = { ___type, a:u8, b:u64, c:u8 } as the front end declares it (padding baked in); 32 bytes. Reordered: ___type, b, a, c, padding 6 (24 bytes).
S='STRUCT_START S\nSTRUCT_MEMBER ___type imut_u64\nSTRUCT_MEMBER a mut_u8\nSTRUCT_PADDING 7\nSTRUCT_MEMBER b mut_u64\nSTRUCT_MEMBER c mut_u8\nSTRUCT_PADDING 7\nSTRUCT_END\n'
TRL='ENUM Class S 1..1\nENUM ClassID S 1\n'
# hob <case name> <extra declarations> <main body>
hob(){ printf "$S$2"; printf 'FUNC_START main\nRETURNS imut_void\n'; printf "$3"; printf 'RET imut_void\nFUNC_END\n'; printf "$TRL"; }
run(){ printf 'struct-member-reordering: on\n' > compiler.config
  java -cp "$ROOT/Optimizer/out" caspien.optimizer.Main -i "$1.hob" "$1.out" >/dev/null 2>&1; rc=$?
  [ $rc -eq 0 ] && { sed -n '/^STRUCT_START S$/,/^STRUCT_END/p;/^FUNC_START main/,/^FUNC_END/p' "$1.out" | grep -v '^FUNC_\|^RETURNS\|^RET \|^STRUCT_START\|^STRUCT_END' | tr '\n' ';' | sed 's/;$//'; }; }
check(){ got=$(run "$1"); if [ "$got" = "$2" ]; then echo "PASS $1"; else echo "FAIL $1: got [$got]"; echo "          want [$2]"; bad=1; fi; }
SAME_DECL='STRUCT_MEMBER ___type imut_u64;STRUCT_MEMBER a mut_u8;STRUCT_PADDING 7;STRUCT_MEMBER b mut_u64;STRUCT_MEMBER c mut_u8;STRUCT_PADDING 7'
NEW_DECL='STRUCT_MEMBER ___type imut_u64;STRUCT_MEMBER b mut_u64;STRUCT_MEMBER a mut_u8;STRUCT_MEMBER c mut_u8;STRUCT_PADDING 6'
CONS_OLD='ADDR s mut_S\nPUSH 1 imut_u64\nPUSH 10 indeterminate_u8\nSTACK_LOCK 7\nPUSH 20 indeterminate_u64\nPUSH 30 indeterminate_u8\nSTACK_LOCK 7\nASSIGN mut_S mut_S mut_S\n'
CONS_NEW='ADDR s mut_S;PUSH 1 imut_u64;PUSH 20 indeterminate_u64;PUSH 10 indeterminate_u8;PUSH 30 indeterminate_u8;STACK_LOCK 6;ASSIGN mut_S mut_S mut_S'
# r1 a stack literal: declaration and the construction are reordered together, trailing padding included
hob r1 '' "ALLOC s mut_S\n$CONS_OLD" > r1.hob
check r1 "$NEW_DECL;ALLOC s mut_S;$CONS_NEW"
# r2 a computed member value (a binary operation) moves as one unit
hob r2 '' 'ALLOC s mut_S\nADDR s mut_S\nPUSH 1 imut_u64\nPUSH 10 indeterminate_u8\nSTACK_LOCK 7\nPUSH 2 indeterminate_u64\nPUSH 3 indeterminate_u64\nADD indeterminate_u64 indeterminate_u64 indeterminate_u64\nPUSH 30 indeterminate_u8\nSTACK_LOCK 7\nASSIGN mut_S mut_S mut_S\n' > r2.hob
check r2 "$NEW_DECL;ALLOC s mut_S;ADDR s mut_S;PUSH 1 imut_u64;PUSH 2 indeterminate_u64;PUSH 3 indeterminate_u64;ADD indeterminate_u64 indeterminate_u64 indeterminate_u64;PUSH 10 indeterminate_u8;PUSH 30 indeterminate_u8;STACK_LOCK 6;ASSIGN mut_S mut_S mut_S"
# r3 a heap `new`
hob r3 '' 'PUSH 1 imut_u64\nPUSH 10 indeterminate_u8\nSTACK_LOCK 7\nPUSH 20 indeterminate_u64\nPUSH 30 indeterminate_u8\nSTACK_LOCK 7\nNEW indeterminate_S\n' > r3.hob
check r3 "$NEW_DECL;PUSH 1 imut_u64;PUSH 20 indeterminate_u64;PUSH 10 indeterminate_u8;PUSH 30 indeterminate_u8;STACK_LOCK 6;NEW indeterminate_S"
# b1 a `raw` pointer to S anywhere: punning / memcopy / C interop see the layout, so the struct is left alone
hob b1 '' "ALLOC s mut_S\nALLOC p raw_mut_S\n$CONS_OLD" > b1.hob
check b1 "$SAME_DECL;ALLOC s mut_S;ALLOC p raw_mut_S;$(printf "$CONS_OLD" | tr '\n' ';' | sed 's/;$//')"
# b2 a construction the pass cannot parse with certainty (an unknown line between the members) blocks the struct
hob b2 '' 'ALLOC s mut_S\nADDR s mut_S\nPUSH 1 imut_u64\nPUSH 10 indeterminate_u8\nSTACK_LOCK 7\nMYSTERY\nPUSH 20 indeterminate_u64\nPUSH 30 indeterminate_u8\nSTACK_LOCK 7\nASSIGN mut_S mut_S mut_S\n' > b2.hob
check b2 "$SAME_DECL;ALLOC s mut_S;ADDR s mut_S;PUSH 1 imut_u64;PUSH 10 indeterminate_u8;STACK_LOCK 7;MYSTERY;PUSH 20 indeterminate_u64;PUSH 30 indeterminate_u8;STACK_LOCK 7;ASSIGN mut_S mut_S mut_S"
# b3 STRUCT_PIN (a front-end-folded `let static n = sizeof(S)`): left alone, and the pin line is always removed
hob b3 'STRUCT_PIN S\n' "ALLOC s mut_S\n$CONS_OLD" > b3.hob
check b3 "$SAME_DECL;ALLOC s mut_S;$(printf "$CONS_OLD" | tr '\n' ';' | sed 's/;$//')"
# b4 a struct that is extended (Class range covers a child) is left alone
printf "$S" > b4.hob; printf 'STRUCT_START K\nSTRUCT_MEMBER ___type imut_u64\nSTRUCT_MEMBER a mut_u8\nSTRUCT_PADDING 7\nSTRUCT_MEMBER b mut_u64\nSTRUCT_MEMBER c mut_u8\nSTRUCT_PADDING 7\nSTRUCT_MEMBER e mut_u8\nSTRUCT_PADDING 7\nSTRUCT_END\nFUNC_START main\nRETURNS imut_void\nRET imut_void\nFUNC_END\nENUM Class S 1..2 K 2..2\nENUM ClassID S 1 K 2\n' >> b4.hob
check b4 "$SAME_DECL"
# b5 a struct with an array member is left alone
printf 'STRUCT_START S\nSTRUCT_MEMBER ___type imut_u64\nSTRUCT_MEMBER a mut_u8\nSTRUCT_PADDING 7\nSTRUCT_MEMBER b mut_u64[2]\nSTRUCT_MEMBER c mut_u8\nSTRUCT_PADDING 7\nSTRUCT_END\nFUNC_START main\nRETURNS imut_void\nRET imut_void\nFUNC_END\nENUM Class S 1..1\nENUM ClassID S 1\n' > b5.hob
check b5 "STRUCT_MEMBER ___type imut_u64;STRUCT_MEMBER a mut_u8;STRUCT_PADDING 7;STRUCT_MEMBER b mut_u64[2];STRUCT_MEMBER c mut_u8;STRUCT_PADDING 7"
# b6 with the switch off the program is untouched (and a STRUCT_PIN line is still removed)
hob b6 'STRUCT_PIN S\n' "ALLOC s mut_S\n$CONS_OLD" > b6.hob
printf 'struct-member-reordering: off\n' > compiler.config
java -cp "$ROOT/Optimizer/out" caspien.optimizer.Main -i b6.hob b6.out >/dev/null 2>&1
if grep -q STRUCT_PIN b6.out || ! grep -q 'STRUCT_MEMBER a mut_u8' b6.out; then echo "FAIL b6 (off must keep layout and strip the pin)"; bad=1; else echo "PASS b6"; fi
[ -n "$KEEP" ] && echo "kept $W" || { cd /; rm -rf "$W"; }; exit $bad

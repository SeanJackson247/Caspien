#!/bin/bash
# Bytecode-level check for the Optimizer's SizeofResolutionPass (always on): a front-end `SIZEOF TypeName <type>` becomes `PUSH n <type>`, with n read from
# the STRUCT declarations (members + STRUCT_PADDING), and constant folding (when on) then folds the PUSH. Hand-made HOB files go through the prebuilt Optimizer.
#   tests/sizeof_hob_check.sh        (needs Optimizer/out, i.e. the shipped prebuilt classes)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); cd "$W" || exit 1
export JAVA_TOOL_OPTIONS=
bad=0
STRUCTS='STRUCT_START S\nSTRUCT_MEMBER ___type imut_u64\nSTRUCT_MEMBER a mut_u8\nSTRUCT_PADDING 7\nSTRUCT_END\nSTRUCT_START T\nSTRUCT_MEMBER ___type imut_u64\nSTRUCT_MEMBER s mut_S\nSTRUCT_MEMBER q mut_S[3]\nSTRUCT_MEMBER p raw_mut_S\nSTRUCT_MEMBER b mut_u16\nSTRUCT_PADDING 6\nSTRUCT_END\nSTRUCT_START Box_u64\nSTRUCT_MEMBER ___type imut_u64\nSTRUCT_MEMBER v mut_u64\nSTRUCT_MEMBER w mut_u8\nSTRUCT_PADDING 7\nSTRUCT_END\n'
# body <lines>: a main holding the given body lines
hob(){ printf "$STRUCTS"; printf 'FUNC_START main\nRETURNS imut_void\n'; printf "$2"; printf 'RET imut_void\nFUNC_END\n'; }
run(){ printf 'constant-folding: %s\n' "${3:-off}" > compiler.config
  java -cp "$ROOT/Optimizer/out" caspien.optimizer.Main -i "$1.hob" "$1.out" >/dev/null 2>&1; rc=$?
  [ $rc -eq 0 ] && sed -n '/^FUNC_START main/,/^FUNC_END/p' "$1.out" | grep -v '^FUNC_\|^RETURNS\|^RET ' | tr '\n' ';' | sed 's/;$//'; }
check(){ got=$(run "$1" "$2" "$3"); want=$2; if [ "$got" = "$want" ]; then echo "PASS $1"; else echo "FAIL $1: got [$got]"; echo "          want [$want]"; bad=1; fi; }
# s1 a struct with padding: 8 + 1 + 7; s2 nested struct, array of struct, pointer, padding: 8+16+48+8+2+6; s3 a generic instantiation by its mangled name;
# s4 the type operand after the name is carried over unchanged
hob s1 'SIZEOF S imut_u64\nSIZEOF T imut_u64\nSIZEOF Box_u64 mut_u64\n' > s1.hob
check s1 "PUSH 16 imut_u64;PUSH 88 imut_u64;PUSH 24 mut_u64"
# s5 with constant folding on, 3 * sizeof(S) folds to one PUSH (resolution runs before folding in the same outer loop)
hob s5 'PUSH 3 imut_u64\nSIZEOF S imut_u64\nMUL imut_u64 imut_u64 imut_u64\n' > s5.hob
check s5 "PUSH 48 imut_u64" on
# s6 the same with folding off: the PUSH is there but nothing folds it
cp s5.hob s6.hob
check s6 "PUSH 3 imut_u64;PUSH 16 imut_u64;MUL imut_u64 imut_u64 imut_u64" off
# s7 a pointer-scale line (`raw S` step) carries more operands after the type: they survive
hob s7 'SIZEOF S imut_u64 extra\n' > s7.hob
check s7 "PUSH 16 imut_u64 extra"
# s8 a SIZEOF naming a struct with no declaration is an internal error, not a silent 8
hob s8 'SIZEOF Nope imut_u64\n' > s8.hob
got=$(run s8 x); if [ -z "$got" ]; then echo "PASS s8 (unknown struct rejected)"; else echo "FAIL s8: got [$got]"; bad=1; fi
# s9 no SIZEOF: the program is returned untouched
hob s9 'PUSH 7 imut_u64\n' > s9.hob
check s9 "PUSH 7 imut_u64"
cd /; rm -rf "$W"; exit $bad

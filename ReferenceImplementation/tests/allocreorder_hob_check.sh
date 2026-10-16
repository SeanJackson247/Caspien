#!/bin/bash
# Bytecode-level check for the Optimizer's VariableAllocationReorderingPass (`variable-allocation-reordering: on`): the order of each function's
# hoisted ALLOC run after the pass. It writes small hand-made HOB files to a temp dir, runs the prebuilt Optimizer on each with only that switch set
# and compares the ALLOC names, function by function, with what the pass is specified to produce. Run from anywhere:
#   tests/allocreorder_hob_check.sh        (needs Optimizer/out, i.e. the shipped prebuilt classes)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); cd "$W" || exit 1
export JAVA_TOOL_OPTIONS=
bad=0
# fn <name> <alloc list "name:type ..."> [extra body lines]: a function whose ALLOC run is the given one; every variable is mentioned once by a PUSH
fn(){ printf 'FUNC_START %s\nRETURNS imut_void\n' "$1"; for a in $2; do printf 'ALLOC %s %s\n' "${a%%:*}" "${a#*:}"; done
  for a in $2; do case "${a%%:*}" in gt_*) ;; *) printf 'PUSH %s %s\nPOP ARG0 %s\n' "${a%%:*}" "${a#*:}" "${a#*:}";; esac; done
  printf "$3"; printf 'RET imut_void\nFUNC_END\n'; }
run(){ # <case> <switch value> ; reads <case>.hob
  printf 'variable-allocation-reordering: %s\n' "$2" > compiler.config
  java -cp "$ROOT/Optimizer/out" caspien.optimizer.Main -i "$1.hob" "$1.out" >/dev/null 2>&1
  awk '/^FUNC_START/{f=$2} /^ALLOC /{a[f]=a[f] " " $2} END{for(k in a) print k ":" a[k]}' "$1.out" | sort | tr '\n' ';' | sed 's/;$//'; }
check(){ got=$(run "$1" "${3:-on}"); if [ "$got" = "$2" ]; then echo "PASS $1"; else echo "FAIL $1: got [$got]"; echo "          want [$2]"; bad=1; fi; }
{ fn main 'a:mut_u8 b:mut_u64 c:mut_u8 d:mut_u64'; } > r1.hob
check r1 "main: b d a c"
{ fn main 'gt_routine_address:code_addr gt_error_message:static_imut_string a:mut_u8 b:mut_u64 c:mut_u16 d:mut_u32'; } > r2.hob
check r2 "main: gt_routine_address gt_error_message b d c a"
# x, y, z all 8 bytes: z is used inside a loop (weight 8), x and y once each: z first, then x, y in their original order
{ printf 'FUNC_START main\nRETURNS imut_void\nALLOC x mut_u64\nALLOC y mut_u64\nALLOC z mut_u64\nPUSH x mut_u64\nPUSH y mut_u64\n@L:\nPUSH z mut_u64\nJMP @L\nRET imut_void\nFUNC_END\n'; } > r3.hob
check r3 "main: z x y"
# a struct is as aligned as its widest member (the classId alone makes it 8)
{ printf 'STRUCT_START S\nSTRUCT_MEMBER ___type imut_u64\nSTRUCT_MEMBER a mut_u8\nSTRUCT_PADDING 7\nSTRUCT_END\nSTRUCT_START T\nSTRUCT_MEMBER a mut_u8\nSTRUCT_MEMBER b mut_u8\nSTRUCT_END\n'; fn main 'i:mut_u8 t:mut_T q:mut_u32 s:mut_S'; } > r4.hob
check r4 "main: s q i t"
{ printf 'FUNC_START main\nRETURNS imut_void\nALLOC a mut_u8\nALLOC b mut_u64\nASM_START\nASM_END\nRET imut_void\nFUNC_END\n'; } > r5.hob
check r5 "main: a b"
{ fn main 'a:mut_u8 b:mut_u64 c:mut_u8 d:mut_u64'; } > r6.hob
check r6 "main: a b c d" off
# only the leading contiguous ALLOC run is reordered: the ALLOC after a body line keeps its place
{ printf 'FUNC_START main\nRETURNS imut_void\nALLOC a mut_u8\nALLOC b mut_u64\nPUSH a mut_u8\nALLOC c mut_u64\nALLOC d mut_u8\nRET imut_void\nFUNC_END\n'; } > r7.hob
check r7 "main: b a c d"
# an unknown type (an enum) is 8-aligned, like the lowering treats it; pointers 8, arrays their element's, a range 8
{ fn main 'a:mut_u8 e:mut_Color p:raw_mut_u8 w:mut_u16[3] v:mut_u32[3] r:imut_range(0,mut_n)'; } > r8.hob
check r8 "main: e p r v w a"
# gt_error_message anywhere but right behind gt_routine_address: the function is left alone
{ fn main 'gt_routine_address:code_addr a:mut_u8 gt_error_message:static_imut_string b:mut_u64'; } > r9.hob
check r9 "main: gt_routine_address a gt_error_message b"
# a single slot / already-sorted runs / parameters: nothing to do
{ fn main 'a:mut_u64'; fn f 'b:mut_u64 c:mut_u8'; printf 'FUNC_START g\nRETURNS imut_void\nARG p mut_u8\nALLOC x mut_u8\nALLOC y mut_u64\nRET imut_void\nFUNC_END\n'; } > r10.hob
check r10 "f: b c;g: y x;main: a"
cd /; rm -rf "$W"; exit $bad

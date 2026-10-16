#!/bin/bash
# Bytecode-level check for the Optimizer's UnusedDeclarationRemovalPass (`unused-declaration-removal: on`), for the cases a Caspien program cannot easily
# express: a program without `main`, inline assembly, the externs/globals the backend uses by fixed name without any reference in the bytecode, and
# same-named function-local statics. It writes small hand-made HOB files to a temp dir, runs the prebuilt Optimizer on each with only that switch on
# and compares the surviving declarations with what the pass is specified to keep. Run from anywhere:
#   tests/unusedecl_hob_check.sh        (needs Optimizer/out, i.e. the shipped prebuilt classes)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); cd "$W" || exit 1
export JAVA_TOOL_OPTIONS=
printf 'unused-declaration-removal: on\n' > compiler.config
decls(){ printf 'EXTERN foo_unused mut_u64 mut_u64\nEXTERN foo_used mut_u64 mut_u64\nEXTERN malloc raw_mut_u8 mut_u64\nEXTERN strlen mut_u64 static_imut_string\nEXTERN sched_yield mut_u64\n'
  printf 'STRING string_id1 "unused"\nSTRING string_id2 "used"\n'
  printf 'GLOBAL ghost_table mut_u64 0\nGLOBAL g_unused mut_u64 1\nGLOBAL g_used mut_Pt\nGLOBAL g_used.a mut_u64 2\nGLOBAL g_used.b mut_u64 3\nGLOBAL g_unused2 mut_Pt\nGLOBAL g_unused2.a mut_u64 4\n'; }
mainf(){ printf "FUNC_START main\nRETURNS imut_void\n${1}RET imut_void\nFUNC_END\n"; }
USES='PUSH string_id2 static_imut_string\nEXTERN_CALL foo_used 1\nPUSH g_used.a mut_u64\n'
{ decls; mainf "$USES"; } > u1.hob                                              # unused ones go; used ones, the fixed-name externs and ghost_table stay
{ decls; printf 'FUNC_START lib\nRETURNS imut_void\nRET imut_void\nFUNC_END\n'; } > u2.hob   # no main (a library): nothing is removed
{ decls; printf 'FUNC_START asmf\nRETURNS imut_void\nASM_START\nASM_END\nRET imut_void\nFUNC_END\n'; mainf "$USES"; } > u3.hob   # inline assembly anywhere: nothing is removed
{ printf 'FUNC_START f1\nRETURNS imut_void\nALLOC_STATIC st mut_u64 0\nRET imut_void\nFUNC_END\nFUNC_START f2\nRETURNS imut_void\nALLOC_STATIC st mut_u64 0\nRET imut_void\nFUNC_END\n'
  mainf 'ALLOC_STATIC solo mut_u64 0\n'; } > u4.hob                                # a name declared in two functions is left alone; a single unused static goes
bad=0
check(){ java -cp "$ROOT/Optimizer/out" caspien.optimizer.Main -i "$1.hob" "$1.out" >/dev/null 2>&1
  got=$(grep -E '^(EXTERN|STRING|GLOBAL|ALLOC_STATIC) ' "$1.out" | awk '{print $1":"$2}' | tr '\n' ' ' | sed 's/ $//')
  if [ "$got" = "$2" ]; then echo "PASS $1"; else echo "FAIL $1: got [$got]"; echo "          want [$2]"; bad=1; fi; }
check u1 "EXTERN:foo_used EXTERN:malloc EXTERN:strlen EXTERN:sched_yield STRING:string_id2 GLOBAL:ghost_table GLOBAL:g_used GLOBAL:g_used.a GLOBAL:g_used.b"
ALL="EXTERN:foo_unused EXTERN:foo_used EXTERN:malloc EXTERN:strlen EXTERN:sched_yield STRING:string_id1 STRING:string_id2 GLOBAL:ghost_table GLOBAL:g_unused GLOBAL:g_used GLOBAL:g_used.a GLOBAL:g_used.b GLOBAL:g_unused2 GLOBAL:g_unused2.a"
check u2 "$ALL"
check u3 "$ALL"
check u4 "ALLOC_STATIC:st ALLOC_STATIC:st"
cd /; rm -rf "$W"; exit $bad

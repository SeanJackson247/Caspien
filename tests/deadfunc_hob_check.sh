#!/bin/bash
# Bytecode-level check for the Optimizer's DeadFunctionRemovalPass (`dead-function-removal: on`), for the cases the language cannot easily express
# (recursion cycles need the '@recursive'/range-parameter machinery): it writes small hand-made HOB files to a temp dir, runs the prebuilt Optimizer on
# each with only that switch on, and compares the surviving function names with what the pass is specified to keep. Run from anywhere:
#   tests/deadfunc_hob_check.sh        (needs Optimizer/out, i.e. the shipped prebuilt classes)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); cd "$W" || exit 1
export JAVA_TOOL_OPTIONS=
printf 'dead-function-removal: on\n' > compiler.config
fn(){ printf 'FUNC_START %s\nRETURNS mut_u64\nARG x mut_u64\nPUSH x mut_u64\nRET mut_u64\nFUNC_END\n' "$1"; }
cyc(){ printf 'FUNC_START %s\nRETURNS mut_u64\nARG x mut_u64\nPUSH x mut_u64\nCC_START sysv_x64\n%s %s\nCC_END sysv_x64\nPUSH_RET mut_u64\nRET mut_u64\nFUNC_END\n' "$1" "$2" "$3"; }
base(){ cyc ping CALL pong; cyc pong CALL ping; cyc selfy RECURSIVE_CALL selfy; fn pointed; fn usedbyptr; }
mainf(){ printf "FUNC_START main\nRETURNS imut_void\n${1}RET imut_void\nFUNC_END\n"; }
{ base; mainf 'PUSH pointed static_imut_func(mut_u64)mut_u64\n'; } > c1.hob                       # cycles and the self-recursive function go; the pointer target stays
{ base; mainf 'PUSH pointed static_imut_func(mut_u64)mut_u64\nINVOKE 0\n'; } > c2.hob             # an INVOKE anywhere: nothing is removed
base > c3.hob                                                                                       # no main (a library): nothing is removed
{ base; printf 'FUNC_START gt_init\nFUNC_DECORATE @gt_init\nRETURNS imut_void\nRET imut_void\nFUNC_END\nFUNC_START decorated\nFUNC_DECORATE @sleep\nRETURNS imut_void\nRET imut_void\nFUNC_END\nFUNC_START recdec\nFUNC_DECORATE @recursive\nRETURNS imut_void\nRET imut_void\nFUNC_END\n'; mainf ''; } > c4.hob   # hook and @sleep are roots; @recursive is not
bad=0
check(){ java -cp "$ROOT/Optimizer/out" caspien.optimizer.Main -i "$1.hob" "$1.out" >/dev/null 2>&1
  got=$(grep '^FUNC_START' "$1.out" | awk '{print $2}' | tr '\n' ' ' | sed 's/ $//')
  if [ "$got" = "$2" ]; then echo "PASS $1: $got"; else echo "FAIL $1: got [$got] want [$2]"; bad=1; fi; }
check c1 "pointed main"
check c2 "ping pong selfy pointed usedbyptr main"
check c3 "ping pong selfy pointed usedbyptr"
check c4 "gt_init decorated main"
cd /; rm -rf "$W"; exit $bad

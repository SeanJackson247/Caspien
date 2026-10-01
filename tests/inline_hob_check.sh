#!/bin/bash
# Bytecode-level check for the Optimizer's FunctionInliningPass (`function-inlining: off|conservative|balanced|aggressive`), for the cases the
# language cannot easily express (a call through a pointer, an extern, hand-made decorators and unwinding, call cycles, size / depth / growth limits).
# It writes small hand-made HOB files to a temp dir, runs the prebuilt Optimizer on each with only the inlining switch set, and counts the
# `CALL name` lines that remain. Run from anywhere:
#   tests/inline_hob_check.sh        (needs Optimizer/out, i.e. the shipped prebuilt classes)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); cd "$W" || exit 1
export JAVA_TOOL_OPTIONS=

# one function: name, decorator line ('' for none), body lines (printf format), return type
fn(){ printf 'FUNC_START %s\n' "$1"; [ -n "$2" ] && printf 'FUNC_DECORATE %s\n' "$2"; printf 'RETURNS mut_u64\nARG x mut_u64\n%b' "$3"; printf 'RET mut_u64\nFUNC_END\n'; }
INC='PUSH x mut_u64\nPUSH 1 indeterminate_u64\nADD mut_u64 indeterminate_u64 mut_u64\n'
call(){ printf 'CC_START sysv_x64\nPUSH 1 indeterminate_u64\nPOP ARG0 mut_u64\nCALL %s\nCC_END sysv_x64\n' "$1"; }
# main calling each of the named functions once as `let r = f(1)`
mainf(){ printf 'FUNC_START main\nRETURNS imut_void\n'; k=0; for f in "$@"; do printf 'ALLOC r%s mut_u64\n' "$k"; k=$((k+1)); done
  k=0; for f in "$@"; do printf 'ADDR r%s mut_u64\n' "$k"; call "$f"; printf 'PUSH_RET mut_u64\nASSIGN mut_u64 mut_u64 mut_u64\n'; k=$((k+1)); done
  printf 'RET imut_void\nFUNC_END\n'; }
bad=0
# run <case> <config lines with | for newline> <function name to look for> <expected remaining CALLs>
check(){ printf '%s\n' "$2" | tr '|' '\n' > compiler.config
  java -cp "$ROOT/Optimizer/out" caspien.optimizer.Main -i "$1.hob" "$1.out" >/dev/null 2>&1
  got=$(grep -c "^CALL $3\$" "$1.out")
  if [ "$got" = "$4" ]; then echo "PASS $1 [$2]: CALL $3 x$got"; else echo "FAIL $1 [$2]: CALL $3 x$got, wanted $4"; bad=1; fi; }
AGG='function-inlining: aggressive'

# plain callee: inlined; the same program with the pass off, and one with an @pub/@pure/@recursive callee
{ fn inc '' "$INC"; mainf inc; } > a1.hob;                                            check a1 "$AGG" inc 0
check a1 'function-inlining: off' inc 1
{ fn incp '@pub' "$INC"; mainf incp; } > a2.hob;                                      check a2 "$AGG" incp 0
{ printf 'FUNC_START incq\nFUNC_DECORATE @pure\nFUNC_DECORATE @recursive\nRETURNS mut_u64\nARG x mut_u64\n'; printf "$INC"; printf 'RET mut_u64\nFUNC_END\n'; mainf incq; } > a3.hob; check a3 "$AGG" incq 0
# decorators that must never be inlined
{ fn asy '@async' "$INC"; mainf asy; } > d1.hob;                                      check d1 "$AGG" asy 1
{ fn thr '@throws' "$INC"; mainf thr; } > d2.hob;                                     check d2 "$AGG" thr 0    # @throws is fine: it is the unwinding that matters
{ fn slp '@sleep' "$INC"; mainf slp; } > d3.hob;                                      check d3 "$AGG" slp 1
{ fn lck '@lock' "$INC"; mainf lck; } > d4.hob;                                     check d4 "$AGG" lck 0    # @lock is a compile-time proof contract only: the lock code sits at the caller
{ fn gti '@gt_init' "$INC"; mainf gti; } > d5.hob;                                   check d5 "$AGG" gti 1
# bodies that must never be inlined
{ fn thw '' "${INC}THROW string_id1\n"; mainf thw; } > b1.hob;                        check b1 "$AGG" thw 1    # unwinds, call site has no staged label
{ fn pls '' "PUSH_LABEL @gt_callsite__1 code_addr\n$INC"; mainf pls; } > b2.hob;      check b2 "$AGG" pls 1
{ fn ext '' "CC_START sysv_x64\nEXIT\n${INC}"; mainf ext; } > b3.hob;                 check b3 "$AGG" ext 1
{ fn asm '' "ASM_START\nASM_END\n$INC"; mainf asm; } > b4.hob;                        check b4 "$AGG" asm 1
{ fn sta '' "ALLOC_STATIC g mut_u64\n$INC"; mainf sta; } > b5.hob;                    check b5 "$AGG" sta 1
{ printf 'FUNC_START byv\nRETURNS mut_u64\nARG p mut_Pair\nPUSH 1 indeterminate_u64\nRET mut_u64\nFUNC_END\n'; mainf byv; } > b6.hob; check b6 "$AGG" byv 1
{ fn lbl '' "JMP @outside_9\n$INC"; mainf lbl; } > b7.hob;                            check b7 "$AGG" lbl 1
# an extern call inside the callee is fine (it is just a line of the body); a call through a pointer stays an INVOKE
{ fn xc '' "CC_START sysv_x64\nPUSH 1 indeterminate_u64\nPOP ARG0 mut_u64\nEXTERN_CALL puts 1\nCC_END sysv_x64\n$INC"; mainf xc; } > e1.hob; check e1 "$AGG" xc 0
{ fn inc '' "$INC"; printf 'FUNC_START viaptr\nRETURNS imut_void\nPUSH inc static_imut_func(mut_u64)mut_u64\nINVOKE 1\nRET imut_void\nFUNC_END\n'; mainf inc; } > e2.hob; check e2 "$AGG" inc 0
grep -q '^INVOKE' e2.out && echo "PASS e2: INVOKE kept" || { echo "FAIL e2: INVOKE lost"; bad=1; }
# throw-program scaffolding: every function carries ALLOC gt_routine_address / gt_error_message. A callee whose body never mentions them is
# inlined and the slots are NOT copied into the caller; a body that uses either one (staging, message write) stays a call
SC='ALLOC gt_routine_address code_addr\nALLOC gt_error_message static_imut_string\n'
{ fn sca '' "${SC}${INC}"; mainf sca; } > s1.hob;                                     check s1 "$AGG" sca 0
[ "$(grep -c '^ALLOC gt_routine_address' s1.out)" = 1 ] && echo "PASS s1: slots not copied into main" || { echo "FAIL s1: scaffolding slot copied"; bad=1; }
{ fn scb '' "${SC}ADDR gt_routine_address code_addr\nPUSH 1 indeterminate_u64\nASSIGN code_addr code_addr code_addr\n${INC}"; mainf scb; } > s2.hob; check s2 "$AGG" scb 1
{ fn scc '' "${SC}ADDR gt_error_message static_imut_string\nPUSH 1 indeterminate_u64\nASSIGN static_imut_string static_imut_string static_imut_string\n${INC}"; mainf scc; } > s3.hob; check s3 "$AGG" scc 1
{ fn scd '' "${SC}${INC}"; mainf scd; } > s4.hob;                                     check s4 'function-inlining: off' scd 1
# same slots, but the caller has them too: now inlined (the inlined lines write/read the caller's own slots)
mainsc(){ # <stage label or ''> <extra main alloc lines> names...
  st="$1"; xa="$2"; shift 2
  printf 'FUNC_START main\nRETURNS imut_void\n'; printf '%b' "$SC"; printf '%b' "$xa"; k=0; for f in "$@"; do printf 'ALLOC r%s mut_u64\n' "$k"; k=$((k+1)); done
  k=0; for f in "$@"; do printf 'ADDR r%s mut_u64\n' "$k"; [ -n "$st" ] && printf 'ADDR gt_routine_address code_addr\nPUSH_LABEL %s code_addr\nASSIGN code_addr code_addr code_addr\n' "$st"
    call "$f"; printf 'PUSH_RET mut_u64\nASSIGN mut_u64 mut_u64 mut_u64\n'; k=$((k+1)); done
  printf 'RET imut_void\n'
  [ -n "$st" ] && [ "${st#@gt_callsite__}" != "$st" ] && printf 'JMP @endpads_1\n%s:\nGT_UNWIND MSG\n@endpads_1:\n' "$st"
  printf 'FUNC_END\n'; }
{ fn scb '' "${SC}ADDR gt_routine_address code_addr\nPUSH 1 indeterminate_u64\nASSIGN code_addr code_addr code_addr\n${INC}"; mainsc '' '' scb; } > s5.hob; check s5 "$AGG" scb 0
{ fn scc '' "${SC}ADDR gt_error_message static_imut_string\nPUSH 1 indeterminate_u64\nASSIGN static_imut_string static_imut_string static_imut_string\n${INC}"; mainsc '' '' scc; } > s6.hob; check s6 "$AGG" scc 0
# unwinding callees (steps 2 and 3): a throw site (message write + GT_UNWIND MSG) and a landing pad
THROWBODY='PUSH x mut_u64\nPUSH 0 indeterminate_u64\nEQ mut_u64 indeterminate_u64 indeterminate_bool\nCMP\nJMP @after_1\nTHROW string_id1\nADDR gt_error_message static_imut_string\nPUSH string_id1 static_some_imut_string\nASSIGN static_imut_string static_some_imut_string static_imut_string\nGT_UNWIND MSG\nJMP @after_1\n@after_1:\nPUSH x mut_u64\n'
{ fn tw '@throws' "${SC}${THROWBODY}"; mainsc @gt_callsite__main_1 '' tw; } > t1.hob;   check t1 "$AGG" tw 0
mainpart(){ sed -n '/^FUNC_START main/,$p' "$1"; }
[ "$(mainpart t1.out | grep -c '^GT_UNWIND')" = 1 ] && mainpart t1.out | grep -q '^JMP @gt_callsite__main_1$' && echo "PASS t1: throw became a jump to the caller's landing pad (caller's own GT_UNWIND kept)" || { echo "FAIL t1: throw not turned into a jump to the staged label"; bad=1; }
mainpart t1.out | grep -q '^THROW' && { echo "FAIL t1: THROW marker copied"; bad=1; } || echo "PASS t1: THROW marker dropped"
{ fn tw '@throws' "${SC}${THROWBODY}"; mainsc '@catch_9' '' tw; printf 'X\n' >/dev/null; } > t2.hob
sed -i 's/^RET imut_void$/RET imut_void\nJMP @end_c_1\n@catch_9:\nRET imut_void\n@end_c_1:/' t2.hob; check t2 "$AGG" tw 0
mainpart t2.out | grep -q '^JMP @catch_9$' && echo "PASS t2: throw became a jump to the caller's catch" || { echo "FAIL t2: no jump to the catch"; bad=1; }
{ fn tw '@throws' "${SC}${THROWBODY}"; mainsc '@catch_9' 'ALLOC fl mut_f32\n' tw; } > t3.hob
sed -i 's/^RET imut_void$/RET imut_void\nJMP @end_c_1\n@catch_9:\nRET imut_void\n@end_c_1:/' t3.hob; check t3 "$AGG" tw 1   # caller has a float variable: left alone at a catch
{ fn tw '@throws' "${SC}${THROWBODY}"; mainf tw; } > t4.hob;                          check t4 "$AGG" tw 1    # no staged label at the site
{ fn tw '@throws' "${SC}${THROWBODY}"; mainsc @gt_callsite__main_1 '' tw; } > t5.hob;  check t5 'function-inlining: off' tw 1
# a callee that leaves a word behind (an ignored call result) is not run inline with operands beneath the site
LEAK='CC_START sysv_x64\nPUSH 1 indeterminate_u64\nPOP ARG0 mut_u64\nEXTERN_CALL puts 1\nCC_END sysv_x64\nPUSH_RET mut_u64\n'
mainbeneath(){ printf 'FUNC_START main\nRETURNS imut_void\nALLOC r0 mut_u64\nALLOC r1 mut_u64\nADDR r0 mut_u64\nPUSH r0 mut_u64\n'; call "$1"; printf 'PUSH_RET mut_u64\nADD mut_u64 mut_u64 mut_u64\nASSIGN mut_u64 mut_u64 mut_u64\nRET imut_void\nFUNC_END\n'; }
{ fn lk '' "${LEAK}${INC}"; mainbeneath lk; } > h1.hob;                               check h1 "$AGG" lk 1    # operands beneath: stays a call
{ fn lk '' "${LEAK}${INC}"; mainf lk; } > h2.hob;                                    check h2 "$AGG" lk 0    # nothing else beneath but the target address: inlined
{ fn nl '' "${INC}"; mainbeneath nl; } > h3.hob;                                     check h3 "$AGG" nl 0    # balanced callee with operands beneath: inlined
# cycles and self recursion
{ printf 'FUNC_START ping\nRETURNS mut_u64\nARG x mut_u64\n'; call pong; printf 'PUSH_RET mut_u64\nRET mut_u64\nFUNC_END\n'
  printf 'FUNC_START pong\nRETURNS mut_u64\nARG x mut_u64\n'; call ping; printf 'PUSH_RET mut_u64\nRET mut_u64\nFUNC_END\n'; mainf ping; } > r1.hob; check r1 "$AGG" ping 2
{ printf 'FUNC_START selfy\nRETURNS mut_u64\nARG x mut_u64\n'; call selfy | sed 's/CALL selfy/RECURSIVE_CALL selfy/'; printf 'PUSH_RET mut_u64\nRET mut_u64\nFUNC_END\n'; mainf selfy; } > r2.hob
check r2 "$AGG" selfy 1
# limits: callee lines, growth, depth (a chain c3 -> c2 -> c1 -> c0 called from main)
chain(){ fn c0 '' "$INC"; for i in 1 2 3; do p=$((i-1)); printf 'FUNC_START c%s\nRETURNS mut_u64\nARG x mut_u64\n' $i; call c$p; printf 'PUSH_RET mut_u64\nRET mut_u64\nFUNC_END\n'; done; mainf c3; }
chain > l1.hob
check l1 "$AGG" c3 0
check l1 "$AGG" c0 0
check l1 'function-inlining: aggressive|inline-max-depth: 1' c3 0
check l1 'function-inlining: aggressive|inline-max-depth: 1' c2 1
STEP='ADDR x mut_u64\nPUSH x mut_u64\nPUSH 1 indeterminate_u64\nADD mut_u64 indeterminate_u64 mut_u64\nASSIGN mut_u64 mut_u64 mut_u64\n'   # x = x + 1: leaves the stack as it found it
{ fn big '' "$STEP$STEP$STEP$STEP$STEP$STEP$STEP$STEP$STEP$STEP$STEP$STEP$STEP$STEP$INC"; mainf big; } > l2.hob
check l2 'function-inlining: conservative' big 1
check l2 'function-inlining: aggressive' big 0
check l2 'function-inlining: conservative|inline-max-callee-lines: 100' big 0
{ fn inc '' "$INC"; mainf inc inc inc inc inc inc inc inc inc; } > l3.hob
check l3 'function-inlining: aggressive' inc 0
check l3 'function-inlining: aggressive|inline-max-growth: 30' inc 6
# @inline is an accepted decorator: it must not stop a callee from being inlined
{ fn inl '@inline' "$INC"; mainf inl; } > a4.hob;                                     check a4 "$AGG" inl 0
# a call inside another call's argument list: inlined when no argument register is loaded yet, NOT when an earlier argument already is
# (inlined code has no register protection: the backend's block copies, shifts and divides use argument registers as scratch)
# (the outer call is an extern, like printf: an outer call to an inlinable function would itself disappear and leave nothing loaded)
nest(){ fn inc '' "$INC"
  printf 'FUNC_START main\nRETURNS imut_void\nALLOC r mut_u64\nADDR r mut_u64\nCC_START sysv_x64\n'
  if [ "$1" = first ]; then call inc; printf 'PUSH_RET mut_u64\nPOP ARG0 mut_u64\nPUSH 2 indeterminate_u64\nPOP ARG1 mut_u64\n'
  else printf 'PUSH 2 indeterminate_u64\nPOP ARG0 mut_u64\n'; call inc; printf 'PUSH_RET mut_u64\nPOP ARG1 mut_u64\n'; fi
  printf 'EXTERN_CALL printf 2\nCC_END sysv_x64\nPUSH_RET mut_u64\nASSIGN mut_u64 mut_u64 mut_u64\nRET imut_void\nFUNC_END\n'; }
nest first > n1.hob;  check n1 "$AGG" inc 0
nest second > n2.hob; check n2 "$AGG" inc 1
# a call that MOVES an owns variable in: the null-out of the variable (`ADDR o / PUSH null / ASSIGN`) follows the POP inside the bracket.
# Inlined, and the null-out is kept; anything else after the last POP still stops the inlining
NUL='ADDR r0 mut_u64\nPUSH null mut_u64\nASSIGN mut_u64 mut_u64 mut_u64\n'
mainmove(){ # <callee> <lines after the last POP> <lines between the two POPs (2-argument callee only)>
  printf 'FUNC_START main\nRETURNS imut_void\nALLOC r0 mut_u64\nALLOC r1 mut_u64\nADDR r1 mut_u64\nCC_START sysv_x64\nPUSH r0 mut_u64\nPOP ARG0 mut_u64\n'
  printf '%b' "$3"; [ -n "$3" ] && printf 'PUSH 7 indeterminate_u64\nPOP ARG1 mut_u64\n'
  printf '%b' "$2"; printf 'CALL %s\nCC_END sysv_x64\nPUSH_RET mut_u64\nASSIGN mut_u64 mut_u64 mut_u64\nRET imut_void\nFUNC_END\n' "$1"; }
{ fn inc '' "$INC"; mainmove inc "$NUL" ''; } > o1.hob;                               check o1 "$AGG" inc 0
[ "$(mainpart o1.out | grep -c '^PUSH null')" = 1 ] && echo "PASS o1: null-out kept" || { echo "FAIL o1: null-out lost"; bad=1; }
{ fn inc '' "$INC"; mainmove inc "${NUL}${NUL}" ''; } > o2.hob;                       check o2 "$AGG" inc 0    # one triple per moved argument
{ fn inc '' "$INC"; mainmove inc 'PUSH 1 indeterminate_u64\n' ''; } > o3.hob;         check o3 "$AGG" inc 1    # something else after the POP: left alone
{ fn inc '' "$INC"; mainmove inc 'ADDR r0 mut_u64\nPUSH 1 mut_u64\nASSIGN mut_u64 mut_u64 mut_u64\n' ''; } > o4.hob; check o4 "$AGG" inc 1  # a store that is not a null-out
{ printf 'FUNC_START two\nRETURNS mut_u64\nARG x mut_u64\nARG y mut_u64\nPUSH x mut_u64\nPUSH y mut_u64\nADD mut_u64 mut_u64 mut_u64\nRET mut_u64\nFUNC_END\n'; mainmove two '' "$NUL"; } > o5.hob; check o5 "$AGG" two 0   # null-out between two arguments
cd /; rm -rf "$W"; exit $bad

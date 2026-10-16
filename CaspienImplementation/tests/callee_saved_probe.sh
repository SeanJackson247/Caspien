#!/bin/bash
# Usage: tests/callee_saved_probe.sh   (Linux, run from the project root; needs gcc)
# Compiles tests/callee_saved_test.caspien with a scratch copy set to `target linux`, then links its assembly against a small asm
# driver that puts sentinels in rbx, r12-r15, calls probeThrow(0), probeThrow(5) and probePlain(9) through the C ABI, and checks that
# every sentinel survived. Also runs tests/check_callee_saved.py over the generated assembly.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; export JAVA_TOOL_OPTIONS=
T=$(mktemp -d); cp -r "$ROOT"/ASTGenerator "$ROOT"/Optimizer "$ROOT"/LowerOrderGenerator "$ROOT"/Codegen "$ROOT"/stdlib "$ROOT"/tests "$ROOT"/Compiler*.class "$ROOT"/Compiler.java "$T"/
sed -e 's/^target windows_gnu/target linux/' -e 's/^    default: win64/    default: sysv_x64/' -e '/^variables-in-registers:/d' -e 's/^CallingConventions:/variables-in-registers: on\nCallingConventions:/' "$ROOT/toolchain.config" > "$T/toolchain.config"
cd "$T"; java Compiler -i tests/callee_saved_test.caspien output/cs >/dev/null 2>&1
echo "expected program output: $(./output/cs)"
python3 tests/check_callee_saved.py output/.build/4_codegen.s
sed 's/^main:/caspien_main:/; s/\.globl main/.globl caspien_main/' output/.build/4_codegen.s > cs.s
cat > probe.S <<'ASM'
    .text
    .globl probe
probe:                          # unsigned long probe(void *fn, unsigned long arg); sets probe_mask bit i if register i changed
    pushq %rbp
    pushq %rbx
    pushq %r12
    pushq %r13
    pushq %r14
    pushq %r15
    subq $8, %rsp
    movq %rdi, %rax
    movq %rsi, %rdi
    movabsq $0x1111111111111111, %rbx
    movabsq $0x2222222222222222, %r12
    movabsq $0x3333333333333333, %r13
    movabsq $0x4444444444444444, %r14
    movabsq $0x5555555555555555, %r15
    call *%rax
    xorl %r9d, %r9d
    movabsq $0x1111111111111111, %r10
    cmpq %r10, %rbx
    je 1f
    orl $1, %r9d
1:  movabsq $0x2222222222222222, %r10
    cmpq %r10, %r12
    je 2f
    orl $2, %r9d
2:  movabsq $0x3333333333333333, %r10
    cmpq %r10, %r13
    je 3f
    orl $4, %r9d
3:  movabsq $0x4444444444444444, %r10
    cmpq %r10, %r14
    je 4f
    orl $8, %r9d
4:  movabsq $0x5555555555555555, %r10
    cmpq %r10, %r15
    je 5f
    orl $16, %r9d
5:  movq %r9, probe_mask(%rip)
    addq $8, %rsp
    popq %r15
    popq %r14
    popq %r13
    popq %r12
    popq %rbx
    popq %rbp
    ret
    .bss
    .globl probe_mask
probe_mask: .quad 0
    .section .note.GNU-stack,"",@progbits
ASM
cat > drv.c <<'C'
#include <stdio.h>
extern unsigned long probe(void *, unsigned long); extern unsigned long probe_mask;
extern unsigned long probeThrow(unsigned long), probePlain(unsigned long), probeLoop(unsigned long), probeLoopThrow(unsigned long);
int main(void){ int bad = 0; unsigned long r;
  r = probe((void*)probeThrow, 0); printf("probeThrow(0) = %lu (want 1000) clobbered-mask=%lu\n", r, probe_mask); bad |= probe_mask || r != 1000;
  r = probe((void*)probeThrow, 5); printf("probeThrow(5) = %lu (want 17)   clobbered-mask=%lu\n", r, probe_mask); bad |= probe_mask || r != 17;
  r = probe((void*)probePlain, 9); printf("probePlain(9) = %lu (want 56)   clobbered-mask=%lu\n", r, probe_mask); bad |= probe_mask || r != 56;
  r = probe((void*)probeLoop, 10); printf("probeLoop(10) = %lu (want 90)    clobbered-mask=%lu\n", r, probe_mask); bad |= probe_mask || r != 90;
  r = probe((void*)probeLoopThrow, 3); printf("probeLoopThrow(3) = %lu (want 6) clobbered-mask=%lu\n", r, probe_mask); bad |= probe_mask || r != 6;
  r = probe((void*)probeLoopThrow, 6); printf("probeLoopThrow(6) = %lu (want 500) clobbered-mask=%lu\n", r, probe_mask); bad |= probe_mask || r != 500;
  puts(bad ? "FAIL" : "PASS callee-saved registers preserved"); return bad; }
C
gcc -o drv drv.c probe.S cs.s -lm -pthread 2>&1 | grep -v "NOTE:\|deprecated" || true
./drv; rc=$?; rm -rf "$T"; exit $rc

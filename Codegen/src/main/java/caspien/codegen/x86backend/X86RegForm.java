package caspien.codegen.x86backend;

import caspien.bytecode.BytecodeStatement;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import caspien.bytecode.Text;

/** Last stack-form mnemonic groups, allocation/resize helpers, and the first register-form (R_*) helpers (see X86Generator for how the parts fit together). */
final class X86RegForm {
    private final X86Backend hub;
    private final X86Core core;

    X86RegForm(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    boolean emitCases6(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        if (mnemonic.equals("GT_INIT")) {
            hub.calls.emitAlignedCallByName("gt_init");
            return true;
        } else if (mnemonic.equals("GT_REGISTER")) {
            // See README.md, "GT_INIT and the ghost-table opcodes".
            hub.emit.popReg("rax");
            hub.emit.pushReg("rax"); // the surviving copy -- left alone, underneath the call
            hub.emit.pushReg("rax"); // the throwaway copy -- this one becomes the argument
            hub.emit.popReg("rax");
            hub.emit.raw(("    movq %rax, %" + hub.calls.argReg(0)));
            hub.calls.emitAlignedCallByName("gt_register");
            String regOk = newInternalLabel("gtreg_ok");
            hub.emit.raw("    testb %al, %al");
            hub.emit.raw("    jnz " + regOk);
            hub.emit.popReg("rax"); // the surviving copy
            hub.emit.raw(("    movq %rax, %" + hub.calls.argReg(0)));
            hub.calls.emitAlignedCallByName("free");
            hub.emit.raw("    xorq %rax, %rax");
            hub.emit.pushReg("rax");
            hub.emit.raw(regOk + ":");
            return true;
        } else if (mnemonic.equals("GT_ALIVE_CHECK")) {
            hub.emit.popReg("rax");
            List<String> loaded = gtSaveLoadedArgRegs(); // the pointer being checked
            hub.emit.raw(("    movq %rax, %" + hub.calls.argReg(0)));
            hub.calls.emitAlignedCallByName("gt_alive_check");
            gtRestoreLoadedArgRegs(loaded);
            hub.emit.pushReg("rax"); // its bool result
            return true;
        } else if (mnemonic.equals("GT_REF_ID")) {
            hub.emit.popReg("rax");
            List<String> loaded = gtSaveLoadedArgRegs(); // an address (ref some / owns) being stored into a nullable ref
            hub.emit.raw(("    movq %rax, %" + hub.calls.argReg(0)));
            hub.calls.emitAlignedCallByName("gt_ref_id");
            gtRestoreLoadedArgRegs(loaded);
            hub.emit.pushReg("rax"); // its 64-bit id (0 for null / dead)
            return true;
        } else if (mnemonic.equals("GT_REF_RESOLVE")) {
            hub.emit.popReg("rax");
            List<String> loaded = gtSaveLoadedArgRegs(); // an id
            hub.emit.raw(("    movq %rax, %" + hub.calls.argReg(0)));
            hub.calls.emitAlignedCallByName("gt_ref_resolve");
            gtRestoreLoadedArgRegs(loaded);
            hub.emit.pushReg("rax"); // the object's address, null when it is no longer registered
            return true;
        } else if (mnemonic.equals("GT_DESTRUCT")) {
            String operand = line.get(1).text;
            if (operand.startsWith("$")) {
                hub.emit.loadSizedFromFrame("rax", Long.parseLong(operand.substring(1)), 8);
                hub.emit.raw(("    movq %rax, %" + hub.calls.argReg(0)));
                hub.calls.emitAlignedCallByName("gt_destruct");
            } else {
                hub.emit.comment("TODO(codegen): 'GT_DESTRUCT " + operand + "' (non-stack-offset operand) not yet implemented");
            }
            return true;
        } else if (mnemonic.equals("GT_DESTRUCT_ADDR")) {
            hub.emit.popReg("rax"); // the computed address itself
            hub.emit.loadSizedFromAddr("rax", "rax", 8); // dereference: the owns pointer's own value
            hub.emit.raw(("    movq %rax, %" + hub.calls.argReg(0)));
            hub.calls.emitAlignedCallByName("gt_destruct");
            return true;
        } else if (mnemonic.equals("YIELD")) {
            hub.calls.emitAlignedCallByName("sched_yield");
            return true;
        } else if (mnemonic.equals("SLEEP")) {
            String realFuncName = line.get(1).text;
            hub.emit.popReg("rax"); // the pushed duration argument
            hub.emit.raw(("    movq %rax, %" + hub.calls.argReg(0)));
            hub.calls.emitAlignedCallByName(realFuncName);
            hub.emit.pushReg("rax"); // the real C "sleep"'s own u64 result (remaining, unslept seconds)
            return true;
        } else if (mnemonic.equals("EXIT")) {
            hub.emit.raw(("    movq $1, %" + hub.calls.argReg(0)));
            hub.calls.emitAlignedCallByName("exit");
            return true;
        } else if (mnemonic.equals("EXIT_THREAD")) {
            hub.emit.raw(("    movq $0, %" + hub.calls.argReg(0)));
            hub.calls.emitAlignedCallByName("pthread_exit");
            return true;
        } else if (mnemonic.equals("TRACE_CAPTURE")) {
            int depth = Integer.parseInt(line.get(1).text);
            hub.emit.raw("    pushq %rax");
            hub.emit.raw("    pushq %rcx");
            hub.emit.raw("    pushq %rdx");
            hub.emit.raw("    pushq %rsi");
            hub.emit.raw("    pushq %r11");
            hub.emit.raw("    movq %rbp, %rcx");
            hub.emit.raw("    xorl %edx, %edx");
            hub.emit.raw("1:");
            hub.emit.raw("    incq %rdx");
            hub.emit.raw("    movq -24(%rcx), %rax");
            hub.emit.raw("    testb $1, %al");
            hub.emit.raw("    jnz 2f");
            hub.emit.raw("    movq (%rcx), %rcx");
            hub.emit.raw("    jmp 1b");
            hub.emit.raw("2:");
            hub.emit.raw("    movq -32(%rcx), %rsi");
            hub.emit.raw("    movq $" + depth + ", %rax");
            hub.emit.raw("    cmpq %rax, %rdx");
            hub.emit.raw("    cmova %rax, %rdx");
            hub.emit.raw("    movq %rbp, %rcx");
            hub.emit.raw("    xorl %eax, %eax");
            hub.emit.raw("3:");
            hub.emit.raw("    cmpq %rdx, %rax");
            hub.emit.raw("    jae 4f");
            hub.emit.raw("    movq -24(%rcx), %r11");
            hub.emit.raw("    shrq $1, %r11");
            hub.emit.raw("    movq %r11, (%rsi,%rax,8)");
            hub.emit.raw("    incq %rax");
            hub.emit.raw("    movq (%rcx), %rcx");
            hub.emit.raw("    jmp 3b");
            hub.emit.raw("4:");
            hub.emit.raw("    movq $0, (%rsi,%rdx,8)");
            hub.emit.raw("    movq %rsi, -32(%rbp)");   // this frame's trace pointer = the root's array (a catch in this same frame reads it)
            hub.emit.raw("    popq %r11");
            hub.emit.raw("    popq %rsi");
            hub.emit.raw("    popq %rdx");
            hub.emit.raw("    popq %rcx");
            hub.emit.raw("    popq %rax");
            return true;
        } else if (mnemonic.equals("GT_UNWIND")) {
            // See README.md, "GT_UNWIND".
            hub.emit.raw(X86Core.CSR_MARK);
            boolean carryMessage = line.size() > 1 && "MSG".equals(line.get(1).text);
            // "GT_UNWIND MSG TRACE" (stack traces on): the trace pointer slot (rbp-32) travels up the same way; rcx is free here
            // (a variable in rcx lives only in a call-free region, and an unwind always follows a call).
            boolean carryTrace = carryMessage && line.size() > 2 && "TRACE".equals(line.get(2).text);
            if (carryMessage) {
                hub.emit.movMemToReg("rax", -16);
            }
            if (carryTrace) {
                hub.emit.movMemToReg("rcx", -32);
            }
            hub.emit.raw("    movq %rbp, %rsp");

            hub.emit.popReg("rbp");
            hub.emit.raw("    addq $8, %rsp");

            if (carryMessage) {
                hub.emit.movRegToMem("rax", -16); // the caller's gt_error_message slot
            }
            if (carryTrace) {
                hub.emit.movRegToMem("rcx", -32); // the caller's gt_trace_ptr slot
            }
            hub.emit.movMemToReg("rax", -8); // the caller's own gt_routine_address slot
            hub.emit.raw("    jmp *%rax");

            return true;
        } else if (mnemonic.equals("THROW")) {
            // See README.md, "THROW".
            return true;
        } else if (mnemonic.equals("RET")) {
            long size = Long.parseLong(line.get(1).text);
            if (size > 0) {
                hub.emit.popReg("rax");
            } else {
                hub.emit.movImmToReg("rax", 0);
            }
            hub.gen.emitFunctionEpilogue();
            return true;
        } else if (mnemonic.equals("RET_FLOAT")) {
            hub.emit.popReg("rax");
            hub.calls.movRegToXmm("rax", "xmm0");
            hub.gen.emitFunctionEpilogue();
            return true;
        } else {
            return false;
        }
    }

    boolean emitCases7(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        if (mnemonic.equals("VARARGS_XMM_COUNT")) {
            core.pendingVarargsXmmCount = Integer.parseInt(line.get(1).text);
            return true;
        } else if (mnemonic.equals("CALL")) {
            String name = line.get(1).text;
            if ((name.startsWith("__drop_") || name.startsWith("__clone_")) && core.callBufferStack.isEmpty()) {
                emitDropGlueCall(name);
                return true;
            }
            hub.calls.resolveStackArgBytesAndCall(false, name);
            return true;
        } else if (mnemonic.equals("INVOKE")) {
            hub.emit.popReg("r10");
            hub.calls.resolveStackArgBytesAndCall(true, "r10");
            return true;
        } else if (mnemonic.equals("POP")) {
            String dest = line.get(1).text;
            int size = (int) Long.parseLong(line.get(2).text);
            if (dest.startsWith("ARG")) {
                int idx = Integer.parseInt(dest.substring(3));
                hub.emit.popReg(hub.calls.argReg(idx));
                hub.emit.noteArgRegLoaded("i" + idx);
            } else if (dest.startsWith("FARG")) {
                int idx = Integer.parseInt(dest.substring(4));
                hub.emit.popReg("rax");
                hub.calls.movRegToXmm("rax", hub.calls.argFloatReg(idx));
                hub.emit.noteArgRegLoaded("f" + idx);
            } else if (dest.startsWith("$")) {
                long addr = Long.parseLong(dest.substring(1));
                if (size <= 8) {
                    hub.emit.popReg("rax");
                    hub.emit.storeSizedToFrame("rax", addr, size);
                } else {
                    long words = (size + 7) / 8;
                    for (long i = 0; i < words; i++) {
                        hub.emit.popReg("rax");
                        hub.emit.storeSizedToFrame("rax", addr + size - 8 - i * 8, 8);
                    }
                }
            } else {
                hub.emit.comment("TODO(codegen): 'POP " + dest + " " + size + "' not yet implemented");
            }
            return true;
        } else if (mnemonic.equals("PUSH_LABEL")) {
            String name = line.get(1).text;
            hub.emit.leaGlobalToReg("rax", hub.gen.mangleLabel(name));
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("PUSH_RET_INT")) {
            hub.emit.pushReg("rax"); // the call result already sitting in rax
            return true;
        } else if (mnemonic.equals("PUSH_RET_FLOAT")) {
            if (core.floatResultInR10) {
                // xmm0 was already restored for the enclosing call's
                // argument 0 (see "CC_END"); the real result is in r10.
                hub.emit.raw("    movq %r10, %rax");
                core.floatResultInR10 = false;
            } else {
                hub.calls.movXmmToReg("xmm0", "rax");
            }
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("ASM_START")) {
            int end = core.currentLineIndex + 1;
            while (end < core.allLines.size()) {
                BytecodeStatement candidate = core.allLines.get(end);
                if (candidate.size() == 1 && candidate.get(0).text.equals("ASM_END")) {
                    break;
                }
                end++;
            }
            for (int i = core.currentLineIndex + 1; i < end; i++) {
                hub.emit.raw(String.join(" ", X86Core.textsOf(core.allLines.get(i))));
            }
            core.asmSkipUntilIndex = end; // skips the raw lines and ASM_END itself
            return true;
        } else if (mnemonic.equals("ASM_END")) {
            // Reached only for a malformed/unpaired block (no matching
            // ASM_START skipped it first) -- nothing to emit either way.
            return true;
        } else if (mnemonic.equals("FLOAT_CHECK")) {
            String[] categories = Text.split(line.get(1).text, '|', 0);
            boolean f64Check = line.size() > 2 && line.get(2).text.equals("8");
            hub.emit.popReg("rax");
            if (f64Check) {
                // double: strip the sign bit (bit 63) by shifting it out, compare against the +-Inf exponent pattern 0x7ff0000000000000
                hub.emit.raw("    btrq $63, %rax");
                hub.emit.movImmToReg("rdx", 0x7ff0000000000000L);
                hub.emit.raw("    cmpq %rdx, %rax");
            } else {
            hub.emit.raw("    andl $2147483647, %eax"); // strip the sign bit -> |bits|
            hub.emit.raw("    cmpl $2139095040, %eax"); // vs. the shared +-Inf/NaN exponent pattern
            }
            boolean firstCategory = true;
            for (String category : categories) {
                String setcc;
                if (category.equals("finite")) {
                    setcc = "setb";
                } else if (category.equals("infinite")) {
                    setcc = "sete";
                } else {
                    setcc = "seta"; // "nan"
                }
                String reg;
                if (firstCategory) {
                    reg = "rax";
                } else {
                    reg = "rdx";
                }
                hub.emit.raw("    " + setcc + " %" + hub.emit.sizedReg(reg, 1));
                hub.emit.raw("    movzbq %" + hub.emit.sizedReg(reg, 1) + ", %" + reg);

                if (!firstCategory) {
                    hub.emit.raw("    orq %rdx, %rax");
                }
                firstCategory = false;
            }
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("CC_START")) {
            // See README.md, "Calls, arguments, and call brackets".
            List<String> saved = new ArrayList<>();
            if (!core.loadedArgRegsStack.isEmpty()) {
                saved.addAll(core.loadedArgRegsStack.peek());
            }
            for (String r : saved) {
                if (r.charAt(0) == 'i') {
                    hub.emit.pushReg(hub.calls.argReg(Integer.parseInt(r.substring(1))));
                } else {
                    hub.calls.movXmmToReg(hub.calls.argFloatReg(Integer.parseInt(r.substring(1))), "r11");
                    hub.emit.pushReg("r11");
                }
            }
            core.savedOuterArgRegsStack.push(saved);
            core.loadedArgRegsStack.push(new ArrayList<>());
            core.callBufferStack.push(new StringBuilder());
            core.argTrackDeltaStack.push(0L);
            return true;
        } else if (mnemonic.equals("CC_END")) {
            StringBuilder finished = core.callBufferStack.pop();
            core.argTrackDeltaStack.pop();
            core.loadedArgRegsStack.pop();
            hub.emit.currentSink().append(finished);
            core.strideKeySink = null;
            List<String> saved = core.savedOuterArgRegsStack.pop();
            if (saved.contains("f0")) {
                hub.calls.movXmmToReg("xmm0", "r10");
                core.floatResultInR10 = true;
            }
            for (int k = saved.size() - 1; k >= 0; k--) {
                String r = saved.get(k);
                if (r.charAt(0) == 'i') {
                    hub.emit.popReg(hub.calls.argReg(Integer.parseInt(r.substring(1))));
                } else {
                    hub.emit.popReg("r11");
                    hub.calls.movRegToXmm("r11", hub.calls.argFloatReg(Integer.parseInt(r.substring(1))));
                }
            }
            return true;
        } else if (mnemonic.equals("EXPORT")) {
            // EXPORT's own target function already gets a real
            // .globl/PUBLIC from its own FUNC_START.
            return true;
        } else if (mnemonic.equals("GLOBAL") || mnemonic.equals("ALLOC_STATIC") || mnemonic.equals("STRING") || mnemonic.equals("EXTERN")) {
            return true;
        } else {
            return false;
        }
    }

    // A ghost-table call in the middle of an argument list must keep the argument registers already loaded.
    private List<String> gtSaveLoadedArgRegs() {
        List<String> saved = new ArrayList<>();
        if (!core.loadedArgRegsStack.isEmpty()) {
            saved.addAll(core.loadedArgRegsStack.peek());
        }
        for (String r : saved) {
            if (r.charAt(0) == 'i') {
                hub.emit.pushReg(hub.calls.argReg(Integer.parseInt(r.substring(1))));
            } else {
                hub.calls.movXmmToReg(hub.calls.argFloatReg(Integer.parseInt(r.substring(1))), "r11");
                hub.emit.pushReg("r11");
            }
        }
        return saved;
    }

    private void gtRestoreLoadedArgRegs(List<String> saved) {
        for (int k = saved.size() - 1; k >= 0; k--) {
            String r = saved.get(k);
            if (r.charAt(0) == 'i') {
                hub.emit.popReg(hub.calls.argReg(Integer.parseInt(r.substring(1))));
            } else {
                hub.emit.popReg("r11");
                hub.calls.movRegToXmm("r11", hub.calls.argFloatReg(Integer.parseInt(r.substring(1))));
            }
        }
    }

    void storeSizedToAddr_withOffsetImm(String baseReg64, long offset, long value, int size) {
        hub.emit.raw("    movq $" + value + ", " + offset + "(%" + baseReg64 + ")");
        
    }

    void storeSizedToAddr_reg(String baseReg64, long offset, String srcReg64, int size) {
        hub.emit.raw("    movq %" + srcReg64 + ", " + offset + "(%" + baseReg64 + ")");
        
    }

    // See README.md, "Drop-glue and clone-glue calls".
    void emitDropGlueCall(String name) {
        // `__clone_value_T(dst, src)` takes two words (dst pushed first, src on top): same dance with both words kept.
        boolean twoWords = name.startsWith("__clone_value_");
        hub.emit.popReg("rax");
        if (twoWords) {
            hub.emit.popReg("rcx");
        }
        hub.emit.raw("    movq %rsp, %rdx");
        hub.emit.raw("    andq $-16, %rsp");
        hub.emit.raw("    subq $8, %rsp");
        hub.emit.raw("    movq %rdx, (%rsp)");
        
        if (twoWords) {
            hub.emit.pushReg("rcx");
        }
        hub.emit.pushReg("rax");
        hub.calls.emitCallByName(name);
        int argBytes;
        if (twoWords) {
            argBytes = 16;
        } else {
            argBytes = 8;
        }
        hub.emit.raw("    addq $" + argBytes + ", %rsp");
        hub.emit.raw("    movq (%rsp), %rsp");
        
    }

    /** Push `reg`, pass it as the first argument to the ghost table function `fn`, restore `reg`. */
    void emitGtCall(String fn, String reg) {
        hub.emit.pushReg(reg);
        hub.emit.raw(("    movq %" + reg + ", %" + hub.calls.argReg(0)));
        hub.calls.emitAlignedCallByName(fn);
        hub.emit.popReg(reg);
    }

    void emitResizeGtUpdate() {
        core.resizeExtraStack = 0;
        hub.emit.popReg("rbx");
        String skip = newInternalLabel("resize_gt_skip");
        hub.emit.raw("    testq %r15, %r15");
        hub.emit.raw("    jz " + skip);
        hub.emit.raw("    cmpq %rbx, %r15");
        hub.emit.raw("    je " + skip);
        emitGtCall("gt_moved", "rbx");
        emitGtCall("gt_register", "r15");
        hub.emit.raw(skip + ":");
    }

    void emitResizeWideFillCopy(int elemSize, int words) {
        for (int k = 0; k < words; k++) {
            long srcOff = (long) (words - 1 - k) * 8 + core.resizeExtraStack;
            int n = Math.min(8, elemSize - 8 * k);
            hub.emit.raw("    movq " + srcOff + "(%rsp), %rdx");
            
            long dst = 8L * k;
            for (int b = 0; b < n;) {
                int chunk;
                if (n - b >= 8) {
                    chunk = 8;
                } else if (n - b >= 4) {
                    chunk = 4;
                } else if (n - b >= 2) {
                    chunk = 2;
                } else {
                    chunk = 1;
                }
                hub.emit.raw("    mov" + hub.emit.movSuffix(chunk) + " %" + hub.emit.sizedReg("rdx", chunk) + ", " + (dst + b) + "(%rax)");
                
                if (b + chunk < n) {
                    hub.emit.raw(("    shrq $" + (chunk * 8) + ", %rdx"));
                }
                b += chunk;
            }
        }
    }

    void emitMallocCall(long size) {
        hub.emit.raw("    movq $" + size + ", %" + hub.calls.argReg(0));
        
        hub.calls.emitAlignedCallByName("malloc");
    }

    String newInternalLabel(String prefix) {
        return "L_cg_" + prefix + "_" + (core.internalLabelCounter++);
    }

    String rfReg(String tok) {
        if (tok.equals("%s")) {
            return X86Core.RF_SCRATCH; // the spare scratch register, usable as an operand only inside the backend
        }
        if (tok.startsWith("%v")) {
            int vi = Integer.parseInt(tok.substring(2));
            if (vi < 3) {
                core.rfVarMask |= 1 << vi;
                core.rfVarUsed = true; // %v3..%v5 are r8/r9/r10: caller-saved, no save/restore, no clash with the r13/r14 scratch uses
            }
            return X86Core.RF_VAR_REGS[vi];
        }
        return X86Core.RF_TEMP_REGS[Integer.parseInt(tok.substring(2))];
    }

    String rfRegText(String reg) {
        return ("%" + reg);
    }

    String rfMemText(long off) {
        return (off + "(%rbp)");
    }

    /** Source-operand text for a two-operand instruction; a 64-bit immediate that will not fit imm32 goes through the scratch register. */
    String rfSrc(String tok) {
        if (X86Core.rfIsTemp(tok)) {
            return rfRegText(rfReg(tok));
        }
        if (X86Core.rfIsSlot(tok)) {
            return rfMemText(X86Core.rfSlot(tok));
        }
        long v = X86Core.rfImm(tok);
        if (!X86Core.rfFitsImm32(v)) {
            hub.emit.movImmToReg(X86Core.RF_SCRATCH, v);
            return rfRegText(X86Core.RF_SCRATCH);
        }
        return ("$" + v);
    }

    /** "op src, dst" in the active syntax (AT&T gets the q suffix and reversed operand order). */
    void rfOp2(String op, String dstText, String srcText) {
        hub.emit.raw(("    " + op + "q " + srcText + ", " + dstText));
    }

    void rfRegToReg(String dst, String src) {
        if (!dst.equals(src)) {
            hub.emit.raw(("    movq %" + src + ", %" + dst));
        }
    }

    void rfMov(String dst, String src) {
        if (X86Core.rfIsTemp(dst)) {
            String d = rfReg(dst);
            if (X86Core.rfIsImm(src)) {
                hub.emit.movImmToReg(d, X86Core.rfImm(src));
            } else if (X86Core.rfIsSlot(src)) {
                hub.emit.movMemToReg(d, X86Core.rfSlot(src));
            } else {
                rfRegToReg(d, rfReg(src));
            }
            return;
        }
        long off = X86Core.rfSlot(dst);
        if (X86Core.rfIsTemp(src)) {
            hub.emit.movRegToMem(rfReg(src), off);
        } else if (X86Core.rfIsImm(src)) {
            long v = X86Core.rfImm(src);
            if (X86Core.rfFitsImm32(v)) {
                hub.emit.raw(("    movq $" + v + ", " + off + "(%rbp)"));
            } else {
                hub.emit.movImmToReg(X86Core.RF_SCRATCH, v);
                hub.emit.movRegToMem(X86Core.RF_SCRATCH, off);
            }
        } else {
            hub.emit.movMemToReg(X86Core.RF_SCRATCH, X86Core.rfSlot(src));
            hub.emit.movRegToMem(X86Core.RF_SCRATCH, off);
        }
    }

    void rfRotate(String op, int size, String dstTok, String aTok, String bTok) {
        String d = rfReg(dstTok);
        boolean aIsD = X86Core.rfIsTemp(aTok) && rfReg(aTok).equals(d);
        String mn;
        if (op.equals("ROTL")) {
            mn = "rol";
        } else {
            mn = "ror";
        }
        if (X86Core.rfIsImm(bTok)) {
            long c = X86Core.rfExtImm(X86Core.rfImm(bTok), size, false) & (size * 8L - 1);
            if (!aIsD) {
                rfMov(dstTok, aTok);
            }
            if (c != 0) {
                if (size < 4) {
                    hub.emit.zeroExtendReg(d, size);
                }
                hub.emit.raw("    " + mn + hub.emit.movSuffix(size) + " $" + c + ", %" + hub.emit.sizedReg(d, size));
            }
            hub.emit.zeroExtendReg(d, size);
            return;
        }
        hub.emit.pushReg("rcx");
        if (X86Core.rfIsTemp(bTok)) {
            rfRegToReg("rcx", rfReg(bTok));
        } else {
            hub.emit.movMemToReg("rcx", X86Core.rfSlot(bTok));
        }
        if (!aIsD) {
            rfMov(dstTok, aTok);
        }
        hub.emit.zeroExtendReg(d, size);
        hub.emit.raw("    " + mn + hub.emit.movSuffix(size) + " %cl, %" + hub.emit.sizedReg(d, size));
        hub.emit.popReg("rcx");
    }

    void rfShift(String op, int size, String dstTok, String aTok, String bTok) {
        String d = rfReg(dstTok);
        boolean aIsD = X86Core.rfIsTemp(aTok) && rfReg(aTok).equals(d);
        if (X86Core.rfIsImm(bTok)) {
            long c = X86Core.rfExtImm(X86Core.rfImm(bTok), size, false);
            boolean wide = Long.compareUnsigned(c, 63) > 0;
            if (!aIsD) {
                rfMov(dstTok, aTok);
            }
            if (wide && !op.equals("SAR")) {
                // shifted out entirely
                hub.emit.raw(("    xorl %" + hub.emit.sizedReg(d, 4) + ", %" + hub.emit.sizedReg(d, 4)));
                return;
            }
            if (wide) {
                c = 63;
            }
            if (op.equals("SHR")) {
                hub.emit.zeroExtendReg(d, size);
            } else if (op.equals("SAR")) {
                hub.emit.signExtendReg(d, size);
            }
            if (c != 0) {
                String sop;
                if (op.equals("SHL")) {
                    sop = "shl";
                } else if (op.equals("SHR")) {
                    sop = "shr";
                } else {
                    sop = "sar";
                }
                hub.emit.raw(("    " + sop + "q $" + c + ", %" + d));
            }
            if (!op.equals("SHR")) {
                hub.emit.zeroExtendReg(d, size);
            }
            return;
        }
        if (core.bmi2 && !op.equals("SAR")) {
            // BMI2: the count goes into the scratch register (b may sit in d's own register, which the move of `a` below overwrites); no %rcx traffic.
            // rfMov into a temp never touches the scratch register, so the count survives it.
            if (X86Core.rfIsTemp(bTok)) {
                rfRegToReg(X86Core.RF_SCRATCH, rfReg(bTok));
            } else {
                hub.emit.movMemToReg(X86Core.RF_SCRATCH, X86Core.rfSlot(bTok));
            }
            hub.emit.zeroExtendReg(X86Core.RF_SCRATCH, size);
            if (!aIsD) {
                rfMov(dstTok, aTok);
            }
            hub.emit.emitShiftBmi2(op, size, d, X86Core.RF_SCRATCH);
            return;
        }
        // variable count: it goes into %rcx first (b may sit in d's own register, which the move of `a` below overwrites)
        hub.emit.pushReg("rcx");
        if (X86Core.rfIsTemp(bTok)) {
            rfRegToReg("rcx", rfReg(bTok));
        } else {
            hub.emit.movMemToReg("rcx", X86Core.rfSlot(bTok));
        }
        hub.emit.zeroExtendReg("rcx", size);
        if (!aIsD) {
            rfMov(dstTok, aTok);
        }
        hub.emit.emitShiftCore(op, size, d);
        hub.emit.popReg("rcx");
    }

    void rfDivConst(boolean mod, int size, String dstTok, String aTok, long k) {
        String d = rfReg(dstTok);
        long[] ms0 = X86Core.divMagicSimple(k);
        if (ms0 != null && !mod) {
            // a plain quotient needs no copy of the dividend: it goes straight into rdx (shifted by the pre-shift), before rax is parked in d
            if (X86Core.rfIsTemp(aTok)) {
                rfRegToReg("rdx", rfReg(aTok));
            } else if (X86Core.rfIsImm(aTok)) {
                hub.emit.movImmToReg("rdx", X86Core.rfExtImm(X86Core.rfImm(aTok), size, false));
            } else {
                hub.emit.movMemToReg("rdx", X86Core.rfSlot(aTok));
            }
            if (size < 8 && !X86Core.rfIsImm(aTok)) {
                hub.emit.zeroExtendReg("rdx", size);   // a narrow dividend: only its low `size` bytes count (the register may hold more)
            }
            if (ms0[0] > 0) {
                hub.emit.raw("    shrq $" + ms0[0] + ", %rdx");
            }
            boolean inRax0 = d.equals("rax");
            if (!inRax0) {
                hub.emit.raw("    xchgq %rax, %" + d);
            }
            hub.emit.movImmToReg("rax", ms0[1]);
            hub.emit.raw("    mulq %rdx");
            if (ms0[2] > 0) {
                hub.emit.raw("    shrq $" + ms0[2] + ", %rdx");
            }
            hub.emit.raw("    movq %rdx, %rax");
            if (!inRax0) {
                hub.emit.raw("    xchgq %rax, %" + d);
            }
            return;
        }
        if (X86Core.rfIsTemp(aTok)) {
            rfRegToReg(X86Core.RF_SCRATCH, rfReg(aTok));
        } else if (X86Core.rfIsImm(aTok)) {
            hub.emit.movImmToReg(X86Core.RF_SCRATCH, X86Core.rfExtImm(X86Core.rfImm(aTok), size, false));
        } else {
            hub.emit.movMemToReg(X86Core.RF_SCRATCH, X86Core.rfSlot(aTok));
        }
        if (size < 8 && !X86Core.rfIsImm(aTok)) {
            hub.emit.zeroExtendReg(X86Core.RF_SCRATCH, size);
        }
        boolean inRax = d.equals("rax");
        long[] ms = X86Core.divMagicSimple(k);
        if (ms != null) {
            // q = mulhi(a >> pre, magic) >> post, no fix-up: the dividend a is kept in r15, the shifted copy goes to rdx (mulq may read the register it overwrites)
            int pre = (int) ms[0];
            int post = (int) ms[2];
            if (!inRax) {
                hub.emit.raw("    xchgq %rax, %" + d);
            }
            String src = X86Core.RF_SCRATCH;
            if (pre > 0) {
                hub.emit.raw("    movq %" + X86Core.RF_SCRATCH + ", %rdx");
                hub.emit.raw("    shrq $" + pre + ", %rdx");
                src = "rdx";
            }
            hub.emit.movImmToReg("rax", ms[1]);
            hub.emit.raw("    mulq %" + src);
            if (post > 0) {
                hub.emit.raw("    shrq $" + post + ", %rdx");
            }
            if (mod) {
                if (X86Core.rfFitsImm32(k)) {
                    hub.emit.raw("    imulq $" + k + ", %rdx, %rax");
                } else {
                    hub.emit.movImmToReg("rax", k);
                    hub.emit.raw("    imulq %rdx, %rax");
                }
                hub.emit.raw("    subq %rax, %" + X86Core.RF_SCRATCH);
                hub.emit.raw("    movq %" + X86Core.RF_SCRATCH + ", %rax");
            } else {
                hub.emit.raw("    movq %rdx, %rax");
            }
            if (!inRax) {
                hub.emit.raw("    xchgq %rax, %" + d);
            }
            return;
        }
        int l = 64 - Long.numberOfLeadingZeros(k - 1);
        BigInteger two64 = BigInteger.ONE.shiftLeft(64);
        BigInteger bk = new BigInteger(Long.toUnsignedString(k));
        BigInteger m = BigInteger.ONE.shiftLeft(l).subtract(bk).shiftLeft(64).divide(bk).add(BigInteger.ONE);
        long magic = m.mod(two64).longValue();
        if (!inRax) {
            hub.emit.raw("    xchgq %rax, %" + d);
        }
        hub.emit.movImmToReg("rax", magic);
        hub.emit.raw("    mulq %" + X86Core.RF_SCRATCH);
        hub.emit.raw("    movq %" + X86Core.RF_SCRATCH + ", %rax");
        hub.emit.raw("    subq %rdx, %rax");
        hub.emit.raw("    shrq $1, %rax");
        hub.emit.raw("    addq %rdx, %rax");
        if (l > 1) {
            hub.emit.raw("    shrq $" + (l - 1) + ", %rax");
        }
        if (mod) {
            hub.emit.movImmToReg("rdx", k);
            hub.emit.raw("    imulq %rdx, %rax");
            hub.emit.raw("    movq %" + X86Core.RF_SCRATCH + ", %rdx");
            hub.emit.raw("    subq %rax, %rdx");
            if (inRax) {
                hub.emit.raw("    movq %rdx, %rax");
            } else {
                hub.emit.raw("    movq %" + d + ", %rax");
                hub.emit.raw("    movq %rdx, %" + d);
            }
        } else if (!inRax) {
            hub.emit.raw("    xchgq %rax, %" + d);
        }
    }
}

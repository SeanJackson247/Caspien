package caspien.codegen.x86backend;

import caspien.bytecode.BytecodeStatement;

/** Argument registers, aligned calls with stack-passed arguments, and the mnemonic dispatcher (emitLine, first mnemonic group) (see X86Generator for how the parts fit together). */
final class X86Calls {
    private final X86Backend hub;
    private final X86Core core;

    X86Calls(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    long memcopyConstantSize() {
        int values = 0;
        for (int back = 1; back <= 4; back++) {
            int idx = core.currentLineIndex - back;
            BytecodeStatement l;
            if (core.allLines == null || idx < 0) {
                l = null;
            } else {
                l = core.allLines.get(idx);
            }
            if (l == null || l.isEmpty()) {
                return -1;
            }
            String m = l.get(0).text;
            if (m.equals("ADDR_OF")) {
                continue;
            }
            if (!m.equals("PUSH") || l.size() < 3) {
                return -1;
            }
            String operand = l.get(2).text;
            if (operand.matches("\\d+") && l.get(1).text.equals("8")) {
                if (values != 1) {
                    return -1;
                }
                long v = Long.parseLong(operand);
                if (v >= 1 && v <= 64) {
                    return v;
                }
                return -1;
            }
            values++;
            if (values > 1) {
                return -1;
            }
        }
        return -1;
    }

    // ---- Float (SSE) helpers (README.md, "X86Calls.java") ----

    void movRegToXmm(String gpr64, String xmmReg) {
        hub.emit.raw(("    movq %" + gpr64 + ", %" + xmmReg));
    }

    void movXmmToReg(String xmmReg, String gpr64) {
        hub.emit.raw(("    movq %" + xmmReg + ", %" + gpr64));
    }

    /** "ss" (single-precision, 4-byte) vs "sd" (double-precision, 8-byte) SSE instruction suffix for a given byte size. `f32` is the only floating-point type this language has at all (checked directly, not assumed -- see FLOAT_CHECK's own doc comment below), so every real call site passes 4 here; the 8-byte ("sd") branch is kept only so this doesn't silently mishandle a size this pass has never actually seen, not because anything in the language can produce one today. */
    String sseSuffix(int size) {
        if (size <= 4) {
            return "ss";
        }
        return "sd";
    }

    /** reads `size` bytes at (%addrReg64) and pushes them: a single sized load+push for size<=8, or a whole-word-at-a-time block push (lowest source offset first, highest last/top) for a larger aggregate -- the read-side counterpart of DOT/LOOKUP_ARRAY/LOOKUP_DYN's own "_LHS" (address-only) variants, generalized to structs/array elements bigger than one register. `addrReg64` is clobbered. */
    void pushSizedOrBlockFromAddr(String addrReg64, int size) {
        if (size <= 8) {
            hub.emit.loadSizedFromAddr("rax", addrReg64, size);
            hub.emit.pushReg("rax");
            return;
        }
        long words = (size + 7) / 8;
        for (long i = 0; i < words; i++) {
            hub.emit.loadSizedFromAddr("r11", addrReg64, 8);
            if (i < words - 1) {
                hub.emit.raw(("    addq $8, %" + addrReg64));
            }
            hub.emit.pushReg("r11");
        }
    }

    String argReg(int index) {
        // register choice is an ABI question, not a syntax one -- WINDOWS_GNU_X64 uses these same win64 registers, just named/referenced in AT&T syntax
        String[] table;
        if (hub.emit.isWinAbi()) {
            table = X86Core.WIN_ARG_REGS;
        } else {
            table = X86Core.LINUX_ARG_REGS;
        }
        if (index < table.length) {
            return table[index];
        }
        return "rax";
    }

    /** The float-bank counterpart of `argReg` -- `ARGn`/`FARGn`'s own real per-bank index already accounts for the two conventions' different counting rules (see `AddressLoweringPass.resolveArgWordSlots`/`CompilerConfig.CallingConvention.sharedArgumentPosition` on the sibling lowering project), so this is a plain, dumb table lookup, exactly like `argReg` -- no convention-mode awareness needed here at all. */
    String argFloatReg(int index) {
        String[] table;
        if (hub.emit.isWinAbi()) {
            table = X86Core.WIN_ARG_REGS_FLOAT;
        } else {
            table = X86Core.LINUX_ARG_REGS_FLOAT;
        }
        if (index < table.length) {
            return table[index];
        }
        return "xmm0"; // beyond the register-passed args -- same documented stack-argument gap argReg's own fallback has.
    }

    // See README.md, "Calls and stack alignment" and "Stack-passed arguments".
    int alignPadFor(long stackArgBytes) {
        if (stackArgBytes % 16 == 0) {
            return 16;
        }
        return 8;
    }

    void emitStackArgRestore(long stackArgBytes) {
        hub.emit.raw(("    movq " + stackArgBytes + "(%rsp), %rsp"));
    }

    String buildStackArgReservation(long stackArgBytes) {
        int pad = alignPadFor(stackArgBytes);
        return "    movq %rsp, %rax\n"
                + "    andq $-16, %rsp\n"
                + "    subq $" + pad + ", %rsp\n"
                + "    movq %rax, (%rsp)\n";
    }

    void reverseStackArgWords(long stackArgBytes) {
        long words = stackArgBytes / 8;
        for (long i = 0; i < words / 2; i++) {
            long lo = i * 8;
            long hi = (words - 1 - i) * 8;
            hub.emit.raw("    movq " + lo + "(%rsp), %rax");
            hub.emit.raw("    movq " + hi + "(%rsp), %r11");
            hub.emit.raw("    movq %r11, " + lo + "(%rsp)");
            hub.emit.raw("    movq %rax, " + hi + "(%rsp)");
            
        }
    }

    void resolveStackArgBytesAndCall(boolean indirect, String target) {
        if (core.argTrackDeltaStack.isEmpty() || core.callBufferStack.isEmpty()) {
            emitAlignedCall(indirect, target);
            return;
        }
        long stackArgBytes = core.argTrackDeltaStack.peek();
        if (stackArgBytes > 0) {
            core.callBufferStack.peek().insert(0, buildStackArgReservation(stackArgBytes));
            boolean winAbi = hub.emit.isWinAbi();
            core.argTrackSuspendDepth++;
            try {
                reverseStackArgWords(stackArgBytes);
                if (winAbi) {
                    hub.emit.raw("    subq $32, %rsp");
                } else {
                    hub.emit.raw("    movl $" + hub.emit.consumeVarargsXmmCount() + ", %eax");
                }
                emitCallTarget(indirect, target);
                if (winAbi) {
                    hub.emit.raw("    addq $32, %rsp");
                }
            } finally {
                core.argTrackSuspendDepth--;
            }
            emitStackArgRestore(stackArgBytes);
        } else {
            emitAlignedCall(indirect, target);
        }
    }

    /** Direct call by name, or indirect through register `target`. */
    private void emitCallTarget(boolean indirect, String target) {
        if (indirect) {
            emitCallIndirect(target);
        } else {
            emitCallByName(target);
        }
    }

    void emitAlignedCallByName(String name) {
        emitAlignedCall(false, name);
    }

    void emitAlignedCall(boolean indirect, String target) {
        core.argTrackSuspendDepth++;
        try {
            boolean winAbi = hub.emit.isWinAbi(); // shadow space vs SysV varargs %al -- an ABI question, independent of syntax
            hub.emit.raw("    movq %rsp, %rax");
            hub.emit.raw("    andq $-16, %rsp");
            hub.emit.raw("    subq $16, %rsp"); // reserved slot for the pre-align rsp -- keeps 16-alignment (subtracting a multiple of 16)
            hub.emit.raw("    movq %rax, 8(%rsp)");
            if (winAbi) {
                hub.emit.raw("    subq $32, %rsp"); // win64 shadow space (WINDOWS_GNU_X64, AT&T syntax)
            } else {
                hub.emit.raw("    movl $" + hub.emit.consumeVarargsXmmCount() + ", %eax");
            }
            emitCallTarget(indirect, target);
            if (winAbi) {
                hub.emit.raw("    addq $32, %rsp"); // undo shadow space -- rsp is back at the reserved slot
            }
            hub.emit.raw("    movq 8(%rsp), %rsp"); // load the pre-align rsp straight into rsp -- also discards the reserved slot
            
        } finally {
            core.argTrackSuspendDepth--;
        }
    }

    void emitCallByName(String name) {
        hub.gen.xvSpillAll();
        hub.emit.raw(("    call " + name));
        hub.gen.xvReloadAll();
    }

    void emitCallIndirect(String reg64) {
        hub.gen.xvSpillAll();
        hub.emit.raw(("    call *%" + reg64));
        hub.gen.xvReloadAll();
    }

    void emitLine(BytecodeStatement line) {
        if (!line.isEmpty() && core.currentLineIndex > core.asmSkipUntilIndex && line.size() >= 1 && !(line.size() == 1 && line.get(0).text.endsWith(":"))
                && X86Core.RF_R13_R14_USERS.contains(line.get(0).text) && !line.get(0).text.equals("ASM_START") && core.csrFuncStart >= 0) {
            hub.emit.raw(X86Core.V1314_SAVE);
            emitLineInner(line);
            hub.emit.raw(X86Core.V1314_REST);
            return;
        }
        emitLineInner(line);
    }

    void emitLineInner(BytecodeStatement line) {
        if (line.isEmpty()) {
            return;
        }
        if (core.currentLineIndex <= core.asmSkipUntilIndex) {
            // Already copied verbatim by the "ASM_START" case below.
            return;
        }
        String first = line.get(0).text;

        if (first.endsWith(":") && line.size() == 1) {
            hub.emit.raw(hub.gen.mangleLabel(first.substring(0, first.length() - 1)) + ":");
            if (first.contains("catch_") && core.csrFuncStart >= 0) {
                hub.emit.raw(X86Core.RSP_MARK); // a catch entry: the unwind left rsp wherever the callee's frame ended (finishCalleeSaved fixes it)
            }
            return;
        }

        boolean wasCmp = core.lastWasCmp;
        core.lastWasCmp = false;

        long pendingBlockSize = core.lastValueBlockSize;
        core.lastValueBlockSize = 0;
        if (first.equals("PUSH") && line.size() > 1 && line.get(1).text.matches("\\d+")) {
            if (pendingBlockSize != 0 && core.pushedWordWithTag) {
                pendingBlockSize = 0;
            }
            core.pushedWordWithTag = pendingBlockSize != 0 && line.get(1).text.equals("8");
        } else {
            core.pushedWordWithTag = false;
        }

        String mnemonic = first;
        if (mnemonic.equals("ASM_START")) {
            core.rfClobberSeen = true; // inline assembly may use anything; the other scratch users are bracketed by V1314 marks
        }
        
        if (emitCases1(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.casesA.emitCases2(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.casesA.emitCases3(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.casesB.emitCases4(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.casesB.emitCases5(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.regForm.emitCases6(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.regForm.emitCases7(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        hub.emit.comment("TODO(codegen): '" + String.join(" ", X86Core.textsOf(line)) + "' not yet implemented");
                return;
    }

    boolean emitCases1(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        if (mnemonic.equals("FUNC_START")) {
            String name = line.get(1).text;
            core.currentFuncName = name;
            core.rfVarUsed = false;
            core.rfVarMask = 0;
            core.rfClobberSeen = false;
            core.xvHome.clear();
            core.xvWidth.clear();
            hub.emit.raw("");
            hub.emit.raw(".globl " + name);
            hub.emit.raw(name + ":");
            hub.emit.pushReg("rbp");
            hub.gen.movRegToRegRbpFromRsp();
            core.csrFuncStart = core.out.length();
            core.csrAllocSum = 0;
            core.csrAllocPos = -1;
            core.csrAllocLine = null;
            return true;
        } else if (mnemonic.equals("FUNC_END")) {
            // See README.md, "emitCases1 mnemonics" (FUNC_END).
            hub.emit.movImmToReg("rax", 0);
            hub.gen.emitFunctionEpilogue();
            if (core.rfVarUsed && core.rfClobberSeen) {
                throw new RuntimeException("codegen internal error: '" + core.currentFuncName
                        + "' keeps variables in r13/r14 but also has an instruction that uses them as scratch");
            }
            hub.gen.finishCalleeSaved();
            return true;
        } else if (mnemonic.equals("ALLOC")) {
            long size = Long.parseLong(line.get(1).text);
            long aligned = (size + 15) & ~15L;
            core.csrAllocSum += aligned;
            if (core.csrAllocPos < 0 && core.callBufferStack.isEmpty() && core.csrFuncStart >= 0) {
                core.csrAllocPos = core.out.length();
                core.csrAllocBytes = aligned;
                core.csrAllocLine = ("    subq $" + aligned + ", %rsp\n");
            }
            hub.emit.raw("    subq $" + aligned + ", %rsp");
            return true;
        } else if (mnemonic.equals("ADDR")) {
            // "ADDR size $offset" -> push that address.
            // "ADDR size globalName" -> push that global's address.
            String operand = line.get(2).text;
            if (operand.startsWith("$")) {
                hub.emit.leaMemToReg("rax", Long.parseLong(operand.substring(1)));
            } else {
                hub.emit.leaGlobalToReg("rax", operand);
            }
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("PUSH")) {
            if (line.size() < 3) {
                hub.emit.comment("TODO(codegen): PUSH (2-token form) '" + String.join(" ", X86Core.textsOf(line)) + "' not yet implemented");
                return true;
            }
            int size = (int) Long.parseLong(line.get(1).text);
            String operand = line.get(2).text;
            if (operand.startsWith("$") || operand.startsWith("%x")) {
                boolean fromXvar = operand.startsWith("%x"); // a float variable promoted to an xmm register (RegVarPromotionPass)
                long offset;
                if (fromXvar) {
                    offset = 0;
                } else {
                    offset = Long.parseLong(operand.substring(1));
                }
                if (size <= 8) {
                    if (fromXvar) {
                        // movd/movq: the variable's bits, zero-extended -- what the frame load gave
                        int xmmWidth;
                        if (size == 8) {
                            xmmWidth = 8;
                        } else {
                            xmmWidth = 4;
                        }
                        hub.gen.rfXmmToGpr(hub.gen.xvReg(operand), "rax", xmmWidth);
                    } else {
                        hub.emit.loadSizedFromFrame("rax", offset, size);
                    }
                    hub.emit.storeConstructionOrPlainPush("rax", size);
                    if (pendingBlockSize == 0 && size < 8) {
                        // Negative size = small fixed array by value; see README.md, "emitCases1 mnemonics".
                        core.lastValueBlockSize = -size;
                    } else {
                        core.lastValueBlockSize = pendingBlockSize;
                    }
                } else {
                    // Multi-word value push; see README.md, "emitCases1 mnemonics".
                    hub.emit.pushBlockFromFrameConstructionAware(offset, size);
                    core.lastValueBlockSize = size;
                }
                return true;
            } else if (operand.equals("null")) {
                hub.emit.movImmToReg("rax", 0);
            } else if (X86Core.isInteger(operand)) {
                long intBits;
                if (operand.startsWith("-")) {
                    intBits = Long.parseLong(operand);
                } else {
                    intBits = Long.parseUnsignedLong(operand);
                }
                hub.emit.movImmToReg("rax", intBits);
            } else if (X86Core.isFloatLiteral(operand)) {
                long bits;
                if (size <= 4) {
                    bits = Integer.toUnsignedLong(Float.floatToRawIntBits(Float.parseFloat(operand)));
                } else {
                    bits = Double.doubleToRawLongBits(Double.parseDouble(operand));
                }
                hub.emit.movImmToReg("rax", bits);
            } else if (operand.matches("\\d+\\.(start|end)")) {
                hub.emit.comment("TODO(codegen): PUSH of incomplete-range pseudo-member '" + operand + "' not yet implemented -- pushing 0 as a placeholder");
                hub.emit.movImmToReg("rax", 0);
            } else if (operand.startsWith("ARG")) {
                int idx = Integer.parseInt(operand.substring(3));
                hub.emit.raw(("    movq %" + argReg(idx) + ", %rax"));
            } else if (operand.startsWith("FARG")) {
                int idx = Integer.parseInt(operand.substring(4));
                movXmmToReg(argFloatReg(idx), "rax");
            } else if (core.stringLiterals.containsKey(operand)) {
                hub.emit.leaGlobalToReg("rax", operand);
            } else if (core.globalsSizeInit.containsKey(operand) || core.globalAliasOffset.containsKey(operand)) {
                if (hub.emit.lineAtOffsetIsAtomicSwap(2)) {
                    hub.emit.leaGlobalToReg("rax", operand);
                } else if (size > 8) {
                    hub.emit.leaGlobalToReg("rax", operand);
                    pushSizedOrBlockFromAddr("rax", size);
                    core.lastValueBlockSize = size;
                    return true;
                } else {
                    hub.emit.leaGlobalToReg("rax", operand);
                    hub.emit.loadSizedFromAddr("rax", "rax", size);
                    if (pendingBlockSize == 0 && size < 8) {
                        core.lastValueBlockSize = -size;
                        hub.emit.storeConstructionOrPlainPush("rax", size);
                        return true;
                    }
                }
            } else if (core.declaredFunctionNames.contains(operand)) {
                hub.emit.leaGlobalToReg("rax", operand);
            } else if (X86Core.isCharLiteral(operand)) {
                hub.emit.movImmToReg("rax", X86Core.decodeCharLiteral(line.get(2)));
            } else {
                hub.emit.comment("TODO(codegen): PUSH of '" + operand + "' (not a declared global) not yet implemented -- pushing 0 as a placeholder");
                hub.emit.movImmToReg("rax", 0);
            }
            core.lastValueBlockSize = pendingBlockSize;
            hub.emit.storeConstructionOrPlainPush("rax", size);
            return true;
        } else if (mnemonic.equals("ASSIGN") || mnemonic.equals("ATOMIC_ASSIGN")) {
            int size = (int) Long.parseLong(line.get(1).text);
            if (core.constructionScopedAssign.contains(core.currentLineIndex)) {
                // See README.md, "emitCases1 mnemonics" (ASSIGN).
                hub.emit.assignConstructionBlock(size);
            } else if (line.size() > 4) {
                hub.emit.emitAssignRepack(size, line);
            } else if (size <= 8) {
                hub.emit.popReg("rax"); // value
                hub.emit.popReg("rbx"); // address
                hub.emit.storeSizedToAddr("rax", "rbx", size);
            } else {
                hub.emit.assignBlock(size);
            }
            return true;
        } else if (mnemonic.equals("R_MOV")) {
            // Register-form (see the LowerOrderGenerator's RegisterFormPass): "R_MOV 8 dst src".
            hub.regForm.rfMov(line.get(2).text, line.get(3).text);
            return true;
        } else if (mnemonic.equals("R_BIN")) {
            // "R_BIN OP size %tD a b" -- %tD = a OP b.
            hub.gen.rfBin(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
            return true;
        } else if (mnemonic.equals("R_DIVC")) {
            // "R_DIVC DIV|MOD 8 %tD a #k" -- unsigned division / remainder by a constant, by multiplying with the reciprocal.
            hub.regForm.rfDivConst(line.get(1).text.equals("MOD"), (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, X86Core.rfImm(line.get(5).text));
            return true;
        } else if (mnemonic.equals("R_UN")) {
            // "R_UN OP size %tD a" -- %tD = OP a.
            hub.gen.rfUn(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text);
            return true;
        } else if (mnemonic.equals("R_BRF")) {
            // "R_BRF %tN @label" -- jump if the temp is zero (replaces CMP + JMP).
            String reg = hub.regForm.rfReg(line.get(1).text);
            hub.emit.raw(("    testq %" + reg + ", %" + reg));
            hub.emit.raw("    je " + hub.gen.mangleLabel(line.get(2).text));
            return true;
        } else if (mnemonic.equals("R_BRC")) {
            // "R_BRC OP 8 a b @label" -- compare a with b and jump to the label when NOT (a OP b) (the fused form of
            // R_BIN cmp + R_BRF, made by the LowerOrderGenerator's BranchFusionPass; the compare result never exists).
            if (line.get(2).text.startsWith("F")) { // "R_BRC OP F4|F8 a b @label": float compare (ucomiss/ucomisd) + jump
                hub.gen.rfFloat(true, line.get(1).text, Integer.parseInt(line.get(2).text.substring(1)), null, line.get(3).text, line.get(4).text,
                        hub.gen.mangleLabel(line.get(5).text));
                return true;
            }
            int brcSize = (int) Long.parseLong(line.get(2).text);
            if (brcSize < 8) {
                hub.gen.rfBrcNarrow(line.get(1).text, brcSize, line.get(3).text, line.get(4).text, hub.gen.mangleLabel(line.get(5).text));
                return true;
            }
            hub.gen.rfBrc(line.get(1).text, line.get(3).text, line.get(4).text, hub.gen.mangleLabel(line.get(5).text));
            return true;
        } else if (mnemonic.equals("R_CMOV")) {
            hub.gen.rfCmov(line);
            return true;
        } else {
            return false;
        }
    }
}

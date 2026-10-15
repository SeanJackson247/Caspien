package caspien.codegen.x86backend;

import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.codegen.CodegenException;

/** Mnemonic groups 2 and 3 of the stack-form instruction set (one switch per group, false = not mine) (see X86Generator for how the parts fit together). */
final class X86CasesA {
    private final X86Backend hub;
    private final X86Core core;

    X86CasesA(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    boolean emitCases2(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        if (mnemonic.equals("R_BRCM")) {
            // "R_BRCM OP 8 a %base @label" -- R_BRC whose second operand is the 8-byte word AT the address in %base (made by the
            // LowerOrderGenerator's LengthCompareFusionPass from `R_LD 8 %t %base ; R_BRC OP 8 a %t @label`).
            hub.gen.rfBrcMem(line.get(1).text, line.get(3).text, line.get(4).text, hub.gen.mangleLabel(line.get(5).text));
            return true;
        } else if (mnemonic.equals("R_PUSH")) {
            // "R_PUSH 8 src" -- materialise an operand (%tN, $off or #imm) on the real stack. Goes through
            // pushReg so the stack-delta tracker sees it; memory/immediate sources use the spare scratch.
            String src = line.get(2).text;
            if (X86Core.rfIsTemp(src)) {
                hub.emit.pushReg(hub.regForm.rfReg(src));
            } else {
                hub.regForm.rfMov("%s", src);
                hub.emit.pushReg(X86Core.RF_SCRATCH);
            }
            return true;
        } else if (mnemonic.equals("R_PUSHA")) {
            // "R_PUSHA 8 $off" / "R_PUSHA 8 &sym" -- push the address of a frame slot / a global.
            hub.gen.rfAddrToReg(X86Core.RF_SCRATCH, line.get(2).text);
            hub.emit.pushReg(X86Core.RF_SCRATCH);
            return true;
        } else if (mnemonic.equals("R_SETV")) {
            // "R_SETV n %vK src" -- n bytes of src (#imm, %tN or %vN) into a promoted variable, zero-extended to 64 bits.
            int n = (int) Long.parseLong(line.get(1).text);
            String vr = hub.regForm.rfReg(line.get(2).text);
            String src = line.get(3).text;
            if (X86Core.rfIsImm(src)) {
                hub.emit.movImmToReg(vr, X86Core.rfExtImm(X86Core.rfImm(src), n, false));
            } else if (n == 4 || n == 2 || n == 1) {
                // one instruction: movl / movzwl / movzbl from the source's sub-register straight into the 32-bit destination
                // (writing a 32-bit register clears bits 32..63), instead of a 64-bit copy followed by an in-place zero-extension
                String sr = hub.regForm.rfReg(src);
                if (n == 4) {
                    hub.emit.raw("    movl %" + hub.emit.sizedReg(sr, 4) + ", %" + hub.emit.sizedReg(vr, 4));
                } else {
                    hub.emit.raw("    movz" + hub.emit.movSuffix(n) + "l %" + hub.emit.sizedReg(sr, n) + ", %" + hub.emit.sizedReg(vr, 4));
                }
            } else {
                hub.regForm.rfRegToReg(vr, hub.regForm.rfReg(src));
            }
            return true;
        } else if (mnemonic.equals("R_LD")) {
            // "R_LD n %tD addr" -- %tD = n bytes at addr (a frame slot, a global or the pointer in a temp), zero-extended.
            hub.gen.rfLoad((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text);
            return true;
        } else if (mnemonic.equals("R_ST")) {
            // "R_ST n addr src" -- n bytes at addr = src (#imm or %tN).
            hub.gen.rfStore((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text);
            return true;
        } else if (mnemonic.equals("R_LDI")) {
            // "R_LDI n %tD &sym %vK scale" -- %tD = n bytes at sym + vK*scale (an R_LEA folded into the load that used it).
            hub.gen.rfLoadIndexed((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text, line.get(4).text, Long.parseLong(line.get(5).text),
                    optLong(line, 6));
            return true;
        } else if (mnemonic.equals("R_STI")) {
            // "R_STI n &sym %vK scale src" -- n bytes at sym + vK*scale = src (#imm or %tN).
            hub.gen.rfStoreIndexed((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text), line.get(5).text,
                    optLong(line, 6));
            return true;
        } else if (mnemonic.equals("R_LDXI")) {
            // "R_LDXI n %xK &sym %vK scale" -- variable K = the float (n = 4 or 8 bytes) at sym + vK*scale (an R_LEA folded into R_LDX).
            hub.gen.rfLoadXIndexed(Integer.parseInt(line.get(1).text), line.get(2).text, line.get(3).text, line.get(4).text, Long.parseLong(line.get(5).text),
                    optLong(line, 6));
            return true;
        } else if (mnemonic.equals("R_STXI")) {
            // "R_STXI n &sym %vK scale %xK" -- the float in variable K stored at sym + vK*scale (an R_LEA folded into R_STX).
            hub.gen.rfStoreXIndexed(Integer.parseInt(line.get(1).text), line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text), line.get(5).text,
                    optLong(line, 6));
            return true;
        } else if (mnemonic.equals("R_LDD")) {
            // "R_LDD n %tD %base disp" -- %tD = n bytes at base + disp, zero-extended (an `R_LEA %t base #k 1` folded into the load: a field read).
            hub.gen.rfLoadDisp((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text));
            return true;
        } else if (mnemonic.equals("R_STD")) {
            // "R_STD n %base disp src" -- n bytes at base + disp = src (#imm or %tN): a field write.
            hub.gen.rfStoreDisp((int) Long.parseLong(line.get(1).text), line.get(2).text, Long.parseLong(line.get(3).text), line.get(4).text);
            return true;
        } else if (mnemonic.equals("R_LDXD")) {
            // "R_LDXD n %xK %base disp" -- variable K = the float (n = 4 or 8 bytes) at base + disp.
            hub.gen.rfLoadXDisp(hub.gen.xvReg(line.get(2).text), line.get(3).text, Long.parseLong(line.get(4).text), Integer.parseInt(line.get(1).text));
            return true;
        } else if (mnemonic.equals("R_STXD")) {
            // "R_STXD n %base disp %xK" -- the float in variable K stored at base + disp.
            hub.gen.rfStoreXDisp(line.get(2).text, Long.parseLong(line.get(3).text), hub.gen.xvReg(line.get(4).text), Integer.parseInt(line.get(1).text));
            return true;
        } else if (mnemonic.equals("R_LEA")) {
            // "R_LEA %tD base idx scale [disp]" -- %tD = base + idx*scale + disp.
            hub.gen.rfLea(line.get(1).text, line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text),
                    optLong(line, 5));
            return true;
        } else if (mnemonic.equals("R_FBIN") || mnemonic.equals("R_FCMP")) {
            // "R_FBIN OP size %tD a b" / "R_FCMP OP size %tD a b" -- scalar float arithmetic / compare on raw bit patterns.
            hub.gen.rfFloat(mnemonic.equals("R_FCMP"), line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
            return true;
        } else if (mnemonic.equals("R_XVAR")) {
            int xk = Integer.parseInt(line.get(1).text.substring(2));
            core.xvHome.put(xk, X86Core.rfSlot(line.get(2).text));
            int xw;
            if (line.size() > 3) {
                xw = Integer.parseInt(line.get(3).text);
            } else {
                xw = 4;
            }
            core.xvWidth.put(xk, xw);
            return true;
        } else if (mnemonic.equals("R_XMOV")) {
            // "R_XMOV %xK|%yK %xJ|%yJ" -- register copy of a float value (FloatTempPass).
            hub.gen.rfMovaps(hub.gen.xvReg(line.get(1).text), hub.gen.xvReg(line.get(2).text));
            return true;
        } else if (mnemonic.equals("R_XSPILL")) {
            // write every float variable to its home slot: emitted before a direct jump into a catch label (no call spills them)
            hub.gen.xvSpillAll();
            return true;
        } else if (mnemonic.equals("R_XRELOAD")) {
            // reload every float variable from its home slot: emitted where control can arrive from an unwind (catch entry),
            // after which the register-held copies are gone on ABIs whose xmm registers do not survive a call.
            hub.gen.xvReloadAll();
            return true;
        } else if (mnemonic.equals("R_XTOG")) {
            // "R_XTOG n %tD %xK" -- %tD = the n*8 float bits of variable K, zero-extended.
            hub.gen.rfXmmToGpr(hub.gen.xvReg(line.get(3).text), hub.regForm.rfReg(line.get(2).text), Integer.parseInt(line.get(1).text));
            return true;
        } else if (mnemonic.equals("R_GTOX")) {
            // "R_GTOX n %xK src" -- variable K = the float bits in src (%tN or #imm).
            int gn = Integer.parseInt(line.get(1).text);
            String src = line.get(3).text;
            if (X86Core.rfIsImm(src)) {
                hub.gen.rfLoadXmm(src, hub.gen.xvReg(line.get(2).text), gn);

            } else {
                hub.gen.rfGprToXmm(hub.gen.xvReg(line.get(2).text), hub.regForm.rfReg(src), gn);
            }
            return true;
        } else if (mnemonic.equals("R_LDX")) {
            // "R_LDX n %xK addr" -- variable K = the float at addr (a frame slot, a global or the pointer in a temp).
            hub.gen.rfLoadX(hub.gen.xvReg(line.get(2).text), line.get(3).text, Integer.parseInt(line.get(1).text));
            return true;
        } else if (mnemonic.equals("R_STX")) {
            // "R_STX n addr %xK" -- the float in variable K stored at addr.
            hub.gen.rfStoreX(line.get(2).text, hub.gen.xvReg(line.get(3).text), Integer.parseInt(line.get(1).text));
            return true;
        } else if (mnemonic.equals("R_FBINX")) {
            // "R_FBINX OP 4 %xK a b" -- variable K = a OP b, computed in place when it can be.
            hub.gen.rfFloatX(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
            return true;
        } else if (mnemonic.equals("R_FSQRT")) {
            // "R_FSQRT n DST SRC" -- DST = sqrt(SRC), both xmm-register operands (FloatIntrinsicPass).
            int fn = (int) Long.parseLong(line.get(1).text);
            String xd = hub.gen.xvReg(line.get(2).text);
            String xs = hub.gen.xvReg(line.get(3).text);
            // sqrtsd/sqrtss keep the upper bits of DST, so they depend on DST's old value; zeroing DST first (when it is not SRC) breaks that chain.
            if (!xd.equals(xs)) hub.emit.raw("    xorps %" + xd + ", %" + xd);
            String sqrtSfx;
            if (fn == 4) {
                sqrtSfx = "ss";
            } else {
                sqrtSfx = "sd";
            }
            hub.emit.raw("    sqrt" + sqrtSfx + " %" + xs + ", %" + xd);
            return true;
        } else if (mnemonic.equals("R_POPV")) {
            // "R_POPV n %vK" -- integer variable K = the n-byte word on top of the real stack (what ASSIGN n n n stored through its address).
            hub.emit.popReg("rax");
            hub.emit.zeroExtendReg("rax", Integer.parseInt(line.get(1).text));
            hub.regForm.rfRegToReg(hub.regForm.rfReg(line.get(2).text), "rax");
            return true;
        } else if (mnemonic.equals("R_GETRET")) {
            // "R_GETRET n %vK" -- integer variable K = the n-byte integer a just-finished call returned (what PUSH_RET_INT + ASSIGN did).
            hub.emit.zeroExtendReg("rax", Integer.parseInt(line.get(1).text));
            hub.regForm.rfRegToReg(hub.regForm.rfReg(line.get(2).text), "rax");
            return true;
        } else if (mnemonic.equals("R_POPX")) {
            // "R_POPX n %xK" -- variable K = the float word on top of the real stack (what ASSIGN n n n stored through its address).
            hub.emit.popReg("rax");
            hub.gen.rfGprToXmm(hub.gen.xvReg(line.get(2).text), "rax", Integer.parseInt(line.get(1).text));
            return true;
        } else if (mnemonic.equals("R_GETRETF")) {
            // "R_GETRETF 4 %xK" -- variable K = the float a just-finished call returned (what PUSH_RET_FLOAT + ASSIGN did).
            String xk = hub.gen.xvReg(line.get(2).text);
            if (core.floatResultInR10) {
                hub.gen.rfGprToXmm(xk, "r10", 8);
                core.floatResultInR10 = false;
            } else {
                hub.emit.raw(("    movaps %xmm0, %" + xk));
            }
            return true;
        } else if (mnemonic.equals("R_RMW")) {
            // "R_RMW OP 8 $x [b]" -- one memory-operand read-modify-write on an 8-byte frame slot.
            String rmwB;
            if (line.size() > 4) {
                rmwB = line.get(4).text;
            } else {
                rmwB = null;
            }
            hub.gen.rfRmw(line.get(1).text, line.get(3).text, rmwB);
            return true;
        } else if (mnemonic.equals("R_ARG")) {
            // "R_ARG n size src" -- integer argument register n = src (what "PUSH src / POP ARGn" did).
            String reg = hub.gen.rfArgReg(Integer.parseInt(line.get(1).text));
            int asize = (int) Long.parseLong(line.get(2).text);
            String src = line.get(3).text;
            if (X86Core.rfIsXvar(src)) {
                int xmmBytes;
                if (asize == 8) {
                    xmmBytes = 8;
                } else {
                    xmmBytes = 4;
                }
                hub.gen.rfXmmToGpr(hub.gen.xvReg(src), reg, xmmBytes);
            } else if (X86Core.rfIsTemp(src)) {
                hub.regForm.rfRegToReg(reg, hub.regForm.rfReg(src));
            } else if (X86Core.rfIsImm(src)) {
                hub.emit.movImmToReg(reg, X86Core.rfImm(src));
            } else {
                hub.emit.loadSizedFromFrame(reg, X86Core.rfSlot(src), asize);
            }
            hub.emit.noteArgRegLoaded("i" + Integer.parseInt(line.get(1).text));
            return true;
        } else if (mnemonic.equals("R_ARGA")) {
            // "R_ARGA n addr" -- integer argument register n = an address ($off or &sym).
            String reg = hub.gen.rfArgReg(Integer.parseInt(line.get(1).text));
            hub.gen.rfAddrToReg(reg, line.get(2).text);
            hub.emit.noteArgRegLoaded("i" + Integer.parseInt(line.get(1).text));
            return true;
        } else if (mnemonic.equals("R_FARG")) {
            // "R_FARG n size src" -- float argument register n = the bit pattern src.
            int fidx = Integer.parseInt(line.get(1).text);
            String xr = hub.calls.argFloatReg(fidx);
            int fsize = (int) Long.parseLong(line.get(2).text);
            String src = line.get(3).text;
            if (X86Core.rfIsXvar(src)) {
                hub.emit.raw(("    movaps %" + hub.gen.xvReg(src) + ", %" + xr));
            } else if (X86Core.rfIsTemp(src)) {
                hub.calls.movRegToXmm(hub.regForm.rfReg(src), xr);
            } else {
                if (X86Core.rfIsImm(src)) {
                    hub.emit.movImmToReg(X86Core.RF_SCRATCH, X86Core.rfImm(src));
                } else {
                    hub.emit.loadSizedFromFrame(X86Core.RF_SCRATCH, X86Core.rfSlot(src), fsize);
                }
                hub.calls.movRegToXmm(X86Core.RF_SCRATCH, xr);
            }
            hub.emit.noteArgRegLoaded("f" + fidx);
            return true;
        } else if (mnemonic.equals("R_RET") || mnemonic.equals("R_RETF")) {
            // "R_RET src" / "R_RETF src" -- the return value into rax / xmm0, then the function epilogue.
            String src = line.get(1).text;
            if (mnemonic.equals("R_RET")) {
                if (X86Core.rfIsTemp(src)) {
                    hub.regForm.rfRegToReg("rax", hub.regForm.rfReg(src));
                } else if (X86Core.rfIsImm(src)) {
                    hub.emit.movImmToReg("rax", X86Core.rfImm(src));
                } else {
                    hub.emit.movMemToReg("rax", X86Core.rfSlot(src));
                }
            } else if (X86Core.rfIsXvar(src)) {
                hub.emit.raw(("    movaps %" + hub.gen.xvReg(src) + ", %xmm0"));
            } else if (X86Core.rfIsTemp(src)) {
                hub.calls.movRegToXmm(hub.regForm.rfReg(src), "xmm0");
            } else {
                if (X86Core.rfIsImm(src)) {
                    hub.emit.movImmToReg(X86Core.RF_SCRATCH, X86Core.rfImm(src));
                } else {
                    hub.emit.movMemToReg(X86Core.RF_SCRATCH, X86Core.rfSlot(src));
                }
                hub.calls.movRegToXmm(X86Core.RF_SCRATCH, "xmm0");
            }
            hub.gen.emitFunctionEpilogue();
            return true;
        } else if (mnemonic.equals("ADD_INT") || mnemonic.equals("SUB_INT") || mnemonic.equals("MUL_INT")) {
            hub.emit.popReg("rbx");
            hub.emit.popReg("rax");
            String op;
            if (mnemonic.equals("ADD_INT")) {
                op = "add";
            } else if (mnemonic.equals("SUB_INT")) {
                op = "sub";
            } else {
                op = "imul";
            }
            hub.emit.raw("    " + op + "q %rbx, %rax");

            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("SDIV_INT") || mnemonic.equals("SMOD_INT")) {
            // Signed division/modulo (s8/s16/s32/s64 operands): sign-extend both operands from
            // their declared width, then cqto/idiv (remainder takes the dividend's sign, as in C).
            int size = (int) Long.parseLong(line.get(1).text);
            hub.emit.popReg("rbx");
            hub.emit.popReg("rax");
            hub.emit.signExtendReg("rax", size);
            hub.emit.signExtendReg("rbx", size);
            // MIN / -1 overflows and traps (SIGFPE) in idiv: a divisor of -1 is handled
            // without idiv (x / -1 = -x wrapping, x % -1 = 0).
            boolean isDiv = mnemonic.equals("SDIV_INT");
            String notMinusOne = hub.regForm.newInternalLabel("sdiv_normal");
            String doneLabel = hub.regForm.newInternalLabel("sdiv_done");
            hub.emit.raw("    cmpq $-1, %rbx");
            hub.emit.raw("    jne " + notMinusOne);
            if (isDiv) {
                hub.emit.raw("    negq %rax");
            } else {
                hub.emit.raw("    xorq %rax, %rax");
            }
            hub.emit.raw("    jmp " + doneLabel);
            hub.emit.raw(notMinusOne + ":");
            hub.emit.raw("    cqto");
            hub.emit.raw("    idivq %rbx");
            if (!isDiv) {
                hub.emit.raw("    movq %rdx, %rax");
            }
            hub.emit.raw(doneLabel + ":");

            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("SLT_INT") || mnemonic.equals("SLT_EQ_INT") || mnemonic.equals("SGT_EQ_INT") || mnemonic.equals("SGT_INT")) {
            // Signed comparisons: sign-extend both operands from their declared width first.
            int size = (int) Long.parseLong(line.get(1).text);
            hub.emit.popReg("rbx");
            hub.emit.popReg("rax");
            hub.emit.signExtendReg("rax", size);
            hub.emit.signExtendReg("rbx", size);
            String scc;
            if (mnemonic.equals("SLT_INT")) {
                scc = "setl";
            } else if (mnemonic.equals("SLT_EQ_INT")) {
                scc = "setle";
            } else if (mnemonic.equals("SGT_EQ_INT")) {
                scc = "setge";
            } else {
                scc = "setg";
            }
            hub.emit.raw("    cmpq %rbx, %rax");
            hub.emit.raw("    " + scc + " %al");
            hub.emit.raw("    movzbq %al, %rax");

            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("DIV_INT") || mnemonic.equals("MOD_INT")) {
            // Unsigned only (known gap); see README.md, "Integer division and comparisons".
            int dsize = optSize(line, 1, 8);
            hub.emit.popReg("rbx");
            hub.emit.popReg("rax");
            hub.emit.zeroExtendReg("rax", dsize);
            hub.emit.zeroExtendReg("rbx", dsize);
            if (dsize <= 4) {
                // both operands were zero-extended from at most 32 bits: the 32-bit divide gives the same quotient and remainder,
                // several times faster than divq on most x86 cores (a divl clears the upper halves of rax and rdx itself)
                hub.emit.raw("    xorl %edx, %edx");
                hub.emit.raw("    divl %ebx");
            } else {
                hub.emit.raw("    xorq %rdx, %rdx");
                hub.emit.raw("    divq %rbx");
            }

            String divResult;
            if (mnemonic.equals("DIV_INT")) {
                divResult = "rax";
            } else {
                divResult = "rdx";
            }
            hub.emit.pushReg(divResult);
            return true;
        } else if (mnemonic.equals("SHL") || mnemonic.equals("SHR") || mnemonic.equals("SAR")) {
            int ssize = optSize(line, 1, 8);
            hub.emit.popReg("rbx"); // shift count
            hub.emit.popReg("rax"); // value
            if (core.bmi2 && !mnemonic.equals("SAR")) {
                hub.emit.zeroExtendReg("rbx", ssize);
                hub.emit.emitShiftBmi2(mnemonic, ssize, "rax", "rbx");
                hub.emit.pushReg("rax");
                return true;
            }
            // %rcx is the shift-count register but also an argument register: save it around the shift (see LOOKUP_ARRAY's small-array path).
            hub.emit.pushReg("rcx");
            hub.emit.raw("    movq %rbx, %rcx");
            hub.emit.zeroExtendReg("rcx", ssize);
            hub.emit.emitShiftCore(mnemonic, ssize, "rax");
            hub.emit.popReg("rcx");
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("ROTL") || mnemonic.equals("ROTR")) {
            int rsize = optSize(line, 1, 8);
            hub.emit.popReg("rbx"); // count
            hub.emit.popReg("rax"); // value
            hub.emit.zeroExtendReg("rax", rsize);
            hub.emit.pushReg("rcx");
            hub.emit.raw("    movq %rbx, %rcx");
            String rotOp;
            if (mnemonic.equals("ROTL")) {
                rotOp = "rol";
            } else {
                rotOp = "ror";
            }
            hub.emit.raw("    " + rotOp + hub.emit.movSuffix(rsize) + " %cl, %" + hub.emit.sizedReg("rax", rsize));
            hub.emit.popReg("rcx");
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("BITS_OR") || mnemonic.equals("BITS_AND") || mnemonic.equals("BITS_XOR")) {
            // "BITS_xxx size": pop two, and/or/xor, push. Size 8 is also what StrengthReductionPass makes of x % 2^n. A narrow
            // result is re-truncated, so the stack word keeps holding the zero-extended value (like the shifts).
            int bsize = optSize(line, 1, 8);
            hub.emit.popReg("rbx");
            hub.emit.popReg("rax");
            String bop;
            if (mnemonic.equals("BITS_OR")) {
                bop = "or";
            } else if (mnemonic.equals("BITS_AND")) {
                bop = "and";
            } else {
                bop = "xor";
            }
            hub.emit.raw(("    " + bop + "q %rbx, %rax"));
            hub.emit.zeroExtendReg("rax", bsize);
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("BITS_NOT")) {
            // "BITS_NOT size": the bitwise complement, truncated to the operand width (u8: ~0 = 255).
            int nsize = optSize(line, 1, 8);
            hub.emit.popReg("rax");
            hub.emit.raw("    notq %rax");
            hub.emit.zeroExtendReg("rax", nsize);
            hub.emit.pushReg("rax");
            return true;
        } else {
            return false;
        }
    }

    boolean emitCases3(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        if (mnemonic.equals("EQ_INT") || mnemonic.equals("NEQ_INT") || mnemonic.equals("LT_INT") || mnemonic.equals("LT_EQ_INT") || mnemonic.equals("GT_EQ_INT") || mnemonic.equals("GT_INT")) {
            int csize = optSize(line, 1, 8);
            hub.emit.popReg("rbx");
            hub.emit.popReg("rax");
            hub.emit.zeroExtendReg("rax", csize);
            hub.emit.zeroExtendReg("rbx", csize);
            String setcc;
            if (mnemonic.equals("EQ_INT")) {
                setcc = "sete";
            } else if (mnemonic.equals("NEQ_INT")) {
                setcc = "setne";
            } else if (mnemonic.equals("LT_INT")) {
                setcc = "setb";
            } else if (mnemonic.equals("LT_EQ_INT")) {
                setcc = "setbe";
            } else if (mnemonic.equals("GT_EQ_INT")) {
                setcc = "setae";
            } else {
                setcc = "seta"; // GT_INT (unsigned; see DIV_INT's own note on signedness)
            }
            hub.emit.raw("    cmpq %rbx, %rax");
            hub.emit.raw("    " + setcc + " %al");
            hub.emit.raw("    movzbq %al, %rax");

            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("ADD_FLOAT") || mnemonic.equals("SUB_FLOAT") || mnemonic.equals("MUL_FLOAT") || mnemonic.equals("DIV_FLOAT")) {
            int size = (int) Long.parseLong(line.get(1).text);
            String sfx = hub.calls.sseSuffix(size);
            hub.emit.popReg("rbx"); // right operand bits
            hub.emit.popReg("rax"); // left operand bits
            hub.calls.movRegToXmm("rax", hub.gen.rfXmmA());
            hub.calls.movRegToXmm("rbx", hub.gen.rfXmmB());
            String op;
            if (mnemonic.equals("ADD_FLOAT")) {
                op = "add";
            } else if (mnemonic.equals("SUB_FLOAT")) {
                op = "sub";
            } else if (mnemonic.equals("MUL_FLOAT")) {
                op = "mul";
            } else {
                op = "div";
            }
            hub.emit.raw(("    " + op + sfx + " %" + hub.gen.rfXmmB() + ", %" + hub.gen.rfXmmA()));
            hub.calls.movXmmToReg(hub.gen.rfXmmA(), "rax");
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("EQ_FLOAT") || mnemonic.equals("NEQ_FLOAT") || mnemonic.equals("LT_FLOAT") || mnemonic.equals("LT_EQ_FLOAT") || mnemonic.equals("GT_EQ_FLOAT") || mnemonic.equals("GT_FLOAT")) {
            int size = (int) Long.parseLong(line.get(1).text);
            String cmp;
            if (size <= 4) {
                cmp = "ucomiss";
            } else {
                cmp = "ucomisd";
            }
            hub.emit.popReg("rbx");
            hub.emit.popReg("rax");
            hub.calls.movRegToXmm("rax", hub.gen.rfXmmA());
            hub.calls.movRegToXmm("rbx", hub.gen.rfXmmB());
            hub.emit.raw(("    " + cmp + " %" + hub.gen.rfXmmB() + ", %" + hub.gen.rfXmmA()));
            String setcc;
            if (mnemonic.equals("EQ_FLOAT")) {
                setcc = "sete";
            } else if (mnemonic.equals("NEQ_FLOAT")) {
                setcc = "setne";
            } else if (mnemonic.equals("LT_FLOAT")) {
                setcc = "setb";
            } else if (mnemonic.equals("LT_EQ_FLOAT")) {
                setcc = "setbe";
            } else if (mnemonic.equals("GT_EQ_FLOAT")) {
                setcc = "setae";
            } else {
                setcc = "seta"; // GT_FLOAT
            }
            hub.emit.raw("    " + setcc + " %al");
            hub.emit.raw("    movzbq %al, %rax");

            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("AND") || mnemonic.equals("OR")) {
            hub.emit.popReg("rbx");
            hub.emit.popReg("rax");
            String logOp;
            if (mnemonic.equals("AND")) {
                logOp = "and";
            } else {
                logOp = "or";
            }
            hub.emit.raw(("    " + logOp + "b %bl, %al"));
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("NOT")) {
            hub.emit.popReg("rax");
            hub.emit.raw("    xorb $1, %al");
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("NEG")) {
            hub.emit.popReg("rax");
            hub.emit.raw("    negq %rax");
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("NEG_FLOAT")) {
            // Float negation flips the IEEE-754 sign bit; an integer `neg`
            // would two's-complement the bit pattern (wrong value).
            int size = (int) Long.parseLong(line.get(1).text);
            hub.emit.popReg("rax");
            if (size <= 4) {
                hub.emit.raw("    xorl $0x80000000, %eax");
            } else {
                hub.emit.movImmToReg("rbx", Long.MIN_VALUE);
                hub.emit.raw("    xorq %rbx, %rax");
            }
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("INC_INT") || mnemonic.equals("DEC_INT")) {
            hub.emit.popReg("rax");
            String incOp;
            if (mnemonic.equals("INC_INT")) {
                incOp = "inc";
            } else {
                incOp = "dec";
            }
            hub.emit.raw(("    " + incOp + "q %rax"));
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("INC_FLOAT") || mnemonic.equals("DEC_FLOAT")) {
            int size = (int) Long.parseLong(line.get(1).text);
            long oneBits;
            if (size <= 4) {
                oneBits = Integer.toUnsignedLong(Float.floatToRawIntBits(1.0f));
            } else {
                oneBits = Double.doubleToRawLongBits(1.0);
            }
            hub.emit.popReg("rax");
            hub.calls.movRegToXmm("rax", hub.gen.rfXmmA());
            hub.emit.movImmToReg("rbx", oneBits);
            hub.calls.movRegToXmm("rbx", hub.gen.rfXmmB());
            String sfx = hub.calls.sseSuffix(size);
            String op;
            if (mnemonic.equals("INC_FLOAT")) {
                op = "add";
            } else {
                op = "sub";
            }
            hub.emit.raw(("    " + op + sfx + " %" + hub.gen.rfXmmB() + ", %" + hub.gen.rfXmmA()));
            hub.calls.movXmmToReg(hub.gen.rfXmmA(), "rax");
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("CMP")) {
            hub.emit.popReg("rax");
            hub.emit.raw("    testq %rax, %rax");
            core.lastWasCmp = true;
            return true;
        } else if (mnemonic.equals("JMP")) {
            String label = hub.gen.mangleLabel(line.get(1).text);
            if (wasCmp) {
                hub.emit.raw(("    je " + label));
            } else {
                hub.emit.raw(("    jmp " + label));
            }
            return true;
        } else if (mnemonic.equals("SEXT")) {
            int srcSize = (int) Long.parseLong(line.get(1).text);
            hub.emit.popReg("rax");
            if (srcSize == 4) {
                hub.emit.raw("    movslq %eax, %rax");
            } else {
                hub.emit.raw("    movs" + hub.emit.movSuffix(srcSize) + "q %" + hub.emit.sizedReg("rax", srcSize) + ", %rax");
            }
            if (line.size() > 2) {
                // Narrow results are kept zero-extended in the stack word: drop the sign bits above dstSize.
                int sextDst = (int) Long.parseLong(line.get(2).text);
                if (sextDst < 8) {
                    if (sextDst == 4) {
                        hub.emit.raw("    movl %eax, %eax");
                    } else {
                        hub.emit.raw("    movz" + hub.emit.movSuffix(sextDst) + "q %" + hub.emit.sizedReg("rax", sextDst) + ", %rax");
                    }
                }
            }
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("TRUNC")) {
            int dstSize = (int) Long.parseLong(line.get(2).text);
            hub.emit.popReg("rax");
            if (dstSize < 8) {
                if (dstSize == 4) {
                    hub.emit.raw("    movl %eax, %eax");
                } else {
                    hub.emit.raw("    movz" + hub.emit.movSuffix(dstSize) + "q %" + hub.emit.sizedReg("rax", dstSize) + ", %rax");
                }
            }
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("ZEXT")) {
            int srcSize = (int) Long.parseLong(line.get(1).text);
            hub.emit.popReg("rax");
            if (srcSize == 4) {
                hub.emit.raw("    movl %eax, %eax"); // writing the 32-bit half auto-zeroes the upper 32 bits
            } else if (srcSize < 8) {
                hub.emit.raw("    movz" + hub.emit.movSuffix(srcSize) + "q %" + hub.emit.sizedReg("rax", srcSize) + ", %rax");
            }
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("DUP_TOP")) {
            hub.emit.popReg("rax");
            hub.emit.pushReg("rax");
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("FCONV")) {
            // "FCONV srcSize dstSize" -- float conversion: 4 -> 8 widens (cvtss2sd, exact), 8 -> 4 narrows (cvtsd2ss, rounds to
            // nearest); equal sizes leave the value alone. The value travels as its bit pattern in one stack word.
            int fromSize = (int) Long.parseLong(line.get(1).text);
            int toSize = (int) Long.parseLong(line.get(2).text);
            if (fromSize == toSize) {
                return true;
            }
            hub.emit.popReg("rax");
            hub.calls.movRegToXmm("rax", hub.gen.rfXmmA());
            String cv;
            if (fromSize == 4) {
                cv = "cvtss2sd";
            } else {
                cv = "cvtsd2ss";
            }
            hub.emit.raw(("    " + cv + " %" + hub.gen.rfXmmA() + ", %" + hub.gen.rfXmmA()));
            if (toSize == 4) {
                hub.gen.rfXmmToGpr(hub.gen.rfXmmA(), "rax", 4); // movd: zero-extends the 32 result bits
            } else {
                hub.calls.movXmmToReg(hub.gen.rfXmmA(), "rax");
            }
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("PROMOTE_F32_TO_F64")) {
            // See README.md, "Float (SSE) cases" (scratch is xmm8, never xmm0).
            hub.emit.popReg("rax");
            hub.calls.movRegToXmm("rax", hub.gen.rfXmmA());
            hub.emit.raw(("    cvtss2sd %" + hub.gen.rfXmmA() + ", %" + hub.gen.rfXmmA()));
            hub.calls.movXmmToReg(hub.gen.rfXmmA(), "rax");
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("PUSH_FIELDNAME")) {
            long offset = Long.parseLong(line.get(1).text);
            hub.emit.movImmToReg("rax", offset);
            hub.emit.pushReg("rax");
            core.lastValueBlockSize = pendingBlockSize;
            return true;
        } else if (mnemonic.equals("DOT_LHS")) {
            int size = (int) Long.parseLong(line.get(1).text);
            hub.emit.popReg("rbx"); // field offset (from PUSH_FIELDNAME)
            hub.emit.popReg("rax"); // base address
            hub.emit.raw("    addq %rbx, %rax");
            hub.emit.pushReg("rax"); // the computed field address itself
            return true;
        } else {
            return false;
        }
    }

    /** Operand idx as a long when the line has it, else 0. */
    private static long optLong(BytecodeStatement line, int idx) {
        if (line.size() > idx) {
            return Long.parseLong(line.get(idx).text);
        }
        return 0L;
    }

    /** Operand idx as an int size when the line has it, else dflt. */
    private static int optSize(BytecodeStatement line, int idx, int dflt) {
        if (line.size() > idx) {
            return (int) Long.parseLong(line.get(idx).text);
        }
        return dflt;
    }
}

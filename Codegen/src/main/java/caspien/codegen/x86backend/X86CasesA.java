package caspien.codegen.x86backend;

import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.codegen.CodegenException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mnemonic groups 2 and 3 of the stack-form instruction set (one switch per group, false = not mine) (see X86Generator for how the parts fit together). */
final class X86CasesA {
    private final X86Backend hub;
    private final X86Core core;

    X86CasesA(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    boolean emitCases2(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        switch (mnemonic) {
case "R_BRCM": {
                // "R_BRCM OP 8 a %base @label" -- R_BRC whose second operand is the 8-byte word AT the address in %base (made by the
                // LowerOrderGenerator's LengthCompareFusionPass from `R_LD 8 %t %base ; R_BRC OP 8 a %t @label`).
                hub.gen.rfBrcMem(line.get(1).text, line.get(3).text, line.get(4).text, hub.gen.mangleLabel(line.get(5).text));
                return true;
            }
            
case "R_PUSH": {
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
            }
            
case "R_PUSHA": {
                // "R_PUSHA 8 $off" / "R_PUSHA 8 &sym" -- push the address of a frame slot / a global.
                hub.gen.rfAddrToReg(X86Core.RF_SCRATCH, line.get(2).text);
                hub.emit.pushReg(X86Core.RF_SCRATCH);
                return true;
            }
            
case "R_SETV": {
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
            }
            
case "R_LD": {
                // "R_LD n %tD addr" -- %tD = n bytes at addr (a frame slot, a global or the pointer in a temp), zero-extended.
                hub.gen.rfLoad((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text);
                return true;
            }
            
case "R_ST": {
                // "R_ST n addr src" -- n bytes at addr = src (#imm or %tN).
                hub.gen.rfStore((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text);
                return true;
            }
            
case "R_LDI": {
                // "R_LDI n %tD &sym %vK scale" -- %tD = n bytes at sym + vK*scale (an R_LEA folded into the load that used it).
                hub.gen.rfLoadIndexed((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text, line.get(4).text, Long.parseLong(line.get(5).text),
                        line.size() > 6 ? Long.parseLong(line.get(6).text) : 0L);
                return true;
            }
            
case "R_STI": {
                // "R_STI n &sym %vK scale src" -- n bytes at sym + vK*scale = src (#imm or %tN).
                hub.gen.rfStoreIndexed((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text), line.get(5).text,
                        line.size() > 6 ? Long.parseLong(line.get(6).text) : 0L);
                return true;
            }
            
case "R_LDXI": {
                // "R_LDXI n %xK &sym %vK scale" -- variable K = the float (n = 4 or 8 bytes) at sym + vK*scale (an R_LEA folded into R_LDX).
                hub.gen.rfLoadXIndexed(Integer.parseInt(line.get(1).text), line.get(2).text, line.get(3).text, line.get(4).text, Long.parseLong(line.get(5).text),
                        line.size() > 6 ? Long.parseLong(line.get(6).text) : 0L);
                return true;
            }
            
case "R_STXI": {
                // "R_STXI n &sym %vK scale %xK" -- the float in variable K stored at sym + vK*scale (an R_LEA folded into R_STX).
                hub.gen.rfStoreXIndexed(Integer.parseInt(line.get(1).text), line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text), line.get(5).text,
                        line.size() > 6 ? Long.parseLong(line.get(6).text) : 0L);
                return true;
            }
            
case "R_LDD": {
                // "R_LDD n %tD %base disp" -- %tD = n bytes at base + disp, zero-extended (an `R_LEA %t base #k 1` folded into the load: a field read).
                hub.gen.rfLoadDisp((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text));
                return true;
            }
            
case "R_STD": {
                // "R_STD n %base disp src" -- n bytes at base + disp = src (#imm or %tN): a field write.
                hub.gen.rfStoreDisp((int) Long.parseLong(line.get(1).text), line.get(2).text, Long.parseLong(line.get(3).text), line.get(4).text);
                return true;
            }
            
case "R_LDXD": {
                // "R_LDXD n %xK %base disp" -- variable K = the float (n = 4 or 8 bytes) at base + disp.
                hub.gen.rfLoadXDisp(hub.gen.xvReg(line.get(2).text), line.get(3).text, Long.parseLong(line.get(4).text), Integer.parseInt(line.get(1).text));
                return true;
            }
            
case "R_STXD": {
                // "R_STXD n %base disp %xK" -- the float in variable K stored at base + disp.
                hub.gen.rfStoreXDisp(line.get(2).text, Long.parseLong(line.get(3).text), hub.gen.xvReg(line.get(4).text), Integer.parseInt(line.get(1).text));
                return true;
            }
            
case "R_LEA": {
                // "R_LEA %tD base idx scale [disp]" -- %tD = base + idx*scale + disp.
                hub.gen.rfLea(line.get(1).text, line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text),
                        line.size() > 5 ? Long.parseLong(line.get(5).text) : 0L);
                return true;
            }
            
case "R_FBIN":
            case "R_FCMP": {
                // "R_FBIN OP size %tD a b" / "R_FCMP OP size %tD a b" -- scalar float arithmetic / compare on raw bit patterns.
                hub.gen.rfFloat(mnemonic.equals("R_FCMP"), line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
                return true;
            }
            
case "R_XVAR": {
                // "R_XVAR %xK $off [n]" -- declaration only (no code): float variable K lives in an xmm register; $off is its
                // home frame slot, used to spill/reload around calls where the ABI does not preserve xmm registers; n is the
                // variable's width (4 = f32, the default; 8 = f64).
                int xk = Integer.parseInt(line.get(1).text.substring(2));
                core.xvHome.put(xk, X86Core.rfSlot(line.get(2).text));
                core.xvWidth.put(xk, line.size() > 3 ? Integer.parseInt(line.get(3).text) : 4);
                return true;
            }
            
case "R_XMOV": {
                // "R_XMOV %xK|%yK %xJ|%yJ" -- register copy of a float value (FloatTempPass).
                hub.gen.rfMovaps(hub.gen.xvReg(line.get(1).text), hub.gen.xvReg(line.get(2).text));
                return true;
            }
            
case "R_XSPILL": {
                // write every float variable to its home slot: emitted before a direct jump into a catch label (no call spills them)
                hub.gen.xvSpillAll();
                return true;
            }
            
case "R_XRELOAD": {
                // reload every float variable from its home slot: emitted where control can arrive from an unwind (catch entry),
                // after which the register-held copies are gone on ABIs whose xmm registers do not survive a call.
                hub.gen.xvReloadAll();
                return true;
            }
            
case "R_XTOG": {
                // "R_XTOG n %tD %xK" -- %tD = the n*8 float bits of variable K, zero-extended.
                hub.gen.rfXmmToGpr(hub.gen.xvReg(line.get(3).text), hub.regForm.rfReg(line.get(2).text), Integer.parseInt(line.get(1).text));
                return true;
            }
            
case "R_GTOX": {
                // "R_GTOX n %xK src" -- variable K = the float bits in src (%tN or #imm).
                int gn = Integer.parseInt(line.get(1).text);
                String src = line.get(3).text;
                if (X86Core.rfIsImm(src)) {
                    hub.gen.rfLoadXmm(src, hub.gen.xvReg(line.get(2).text), gn);
                    
                } else {
                    hub.gen.rfGprToXmm(hub.gen.xvReg(line.get(2).text), hub.regForm.rfReg(src), gn);
                }
                return true;
            }
            
case "R_LDX": {
                // "R_LDX n %xK addr" -- variable K = the float at addr (a frame slot, a global or the pointer in a temp).
                hub.gen.rfLoadX(hub.gen.xvReg(line.get(2).text), line.get(3).text, Integer.parseInt(line.get(1).text));
                return true;
            }
            
case "R_STX": {
                // "R_STX n addr %xK" -- the float in variable K stored at addr.
                hub.gen.rfStoreX(line.get(2).text, hub.gen.xvReg(line.get(3).text), Integer.parseInt(line.get(1).text));
                return true;
            }
            
case "R_FBINX": {
                // "R_FBINX OP 4 %xK a b" -- variable K = a OP b, computed in place when it can be.
                hub.gen.rfFloatX(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
                return true;
            }
            
case "R_FSQRT": {
                // "R_FSQRT n DST SRC" -- DST = sqrt(SRC), both xmm-register operands (FloatIntrinsicPass).
                int fn = (int) Long.parseLong(line.get(1).text);
                String xd = hub.gen.xvReg(line.get(2).text);
                String xs = hub.gen.xvReg(line.get(3).text);
                // sqrtsd/sqrtss keep the upper bits of DST, so they depend on DST's old value; zeroing DST first (when it is not SRC) breaks that chain.
                if (!xd.equals(xs)) hub.emit.raw("    xorps %" + xd + ", %" + xd);
                hub.emit.raw("    sqrt" + (fn == 4 ? "ss" : "sd") + " %" + xs + ", %" + xd);
                return true;
            }
            
case "R_POPV": {
                // "R_POPV n %vK" -- integer variable K = the n-byte word on top of the real stack (what ASSIGN n n n stored through its address).
                hub.emit.popReg("rax");
                hub.emit.zeroExtendReg("rax", Integer.parseInt(line.get(1).text));
                hub.regForm.rfRegToReg(hub.regForm.rfReg(line.get(2).text), "rax");
                return true;
            }
            
case "R_GETRET": {
                // "R_GETRET n %vK" -- integer variable K = the n-byte integer a just-finished call returned (what PUSH_RET_INT + ASSIGN did).
                hub.emit.zeroExtendReg("rax", Integer.parseInt(line.get(1).text));
                hub.regForm.rfRegToReg(hub.regForm.rfReg(line.get(2).text), "rax");
                return true;
            }
            
case "R_POPX": {
                // "R_POPX n %xK" -- variable K = the float word on top of the real stack (what ASSIGN n n n stored through its address).
                hub.emit.popReg("rax");
                hub.gen.rfGprToXmm(hub.gen.xvReg(line.get(2).text), "rax", Integer.parseInt(line.get(1).text));
                return true;
            }
            
case "R_GETRETF": {
                // "R_GETRETF 4 %xK" -- variable K = the float a just-finished call returned (what PUSH_RET_FLOAT + ASSIGN did).
                String xk = hub.gen.xvReg(line.get(2).text);
                if (core.floatResultInR10) {
                    hub.gen.rfGprToXmm(xk, "r10", 8);
                    core.floatResultInR10 = false;
                } else {
                    hub.emit.raw(("    movaps %xmm0, %" + xk));
                }
                return true;
            }
            
case "R_RMW": {
                // "R_RMW OP 8 $x [b]" -- one memory-operand read-modify-write on an 8-byte frame slot.
                hub.gen.rfRmw(line.get(1).text, line.get(3).text, line.size() > 4 ? line.get(4).text : null);
                return true;
            }
            
case "R_ARG": {
                // "R_ARG n size src" -- integer argument register n = src (what "PUSH src / POP ARGn" did).
                String reg = hub.gen.rfArgReg(Integer.parseInt(line.get(1).text));
                int asize = (int) Long.parseLong(line.get(2).text);
                String src = line.get(3).text;
                if (X86Core.rfIsXvar(src)) {
                    hub.gen.rfXmmToGpr(hub.gen.xvReg(src), reg, asize == 8 ? 8 : 4);
                } else if (X86Core.rfIsTemp(src)) {
                    hub.regForm.rfRegToReg(reg, hub.regForm.rfReg(src));
                } else if (X86Core.rfIsImm(src)) {
                    hub.emit.movImmToReg(reg, X86Core.rfImm(src));
                } else {
                    hub.emit.loadSizedFromFrame(reg, X86Core.rfSlot(src), asize);
                }
                hub.emit.noteArgRegLoaded("i" + Integer.parseInt(line.get(1).text));
                return true;
            }
            
case "R_ARGA": {
                // "R_ARGA n addr" -- integer argument register n = an address ($off or &sym).
                String reg = hub.gen.rfArgReg(Integer.parseInt(line.get(1).text));
                hub.gen.rfAddrToReg(reg, line.get(2).text);
                hub.emit.noteArgRegLoaded("i" + Integer.parseInt(line.get(1).text));
                return true;
            }
            
case "R_FARG": {
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
            }
            
case "R_RET":
            case "R_RETF": {
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
            }
            
case "ADD_INT":
            case "SUB_INT":
            case "MUL_INT": {
                hub.emit.popReg("rbx");
                hub.emit.popReg("rax");
                String op = mnemonic.equals("ADD_INT") ? "add" : mnemonic.equals("SUB_INT") ? "sub" : "imul";
                hub.emit.raw("    " + op + "q %rbx, %rax");
                
                hub.emit.pushReg("rax");
                return true;
            }
            
case "SDIV_INT":
            case "SMOD_INT": {
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
            }
            
case "SLT_INT":
            case "SLT_EQ_INT":
            case "SGT_EQ_INT":
            case "SGT_INT": {
                // Signed comparisons: sign-extend both operands from their declared width first.
                int size = (int) Long.parseLong(line.get(1).text);
                hub.emit.popReg("rbx");
                hub.emit.popReg("rax");
                hub.emit.signExtendReg("rax", size);
                hub.emit.signExtendReg("rbx", size);
                String scc = mnemonic.equals("SLT_INT") ? "setl"
                        : mnemonic.equals("SLT_EQ_INT") ? "setle"
                        : mnemonic.equals("SGT_EQ_INT") ? "setge"
                        : "setg";
                hub.emit.raw("    cmpq %rbx, %rax");
                hub.emit.raw("    " + scc + " %al");
                hub.emit.raw("    movzbq %al, %rax");
                
                hub.emit.pushReg("rax");
                return true;
            }
            
case "DIV_INT":
            case "MOD_INT": {
                // Unsigned division -- this bytecode carries only a byte
                // size per operator, never a signedness flag, and this
                // language's own numeric literals default to unsigned
                // ("indeterminate_u64") throughout -- so, like LT_INT/
                // LT_EQ_INT/GT_EQ_INT below, this pass treats every
                // int comparison/division as unsigned. A real signed
                // s8/s16/s32/s64 division would need idiv/cqto instead;
                // documented as a known gap rather than silently wrong
                // for the (much more common, in this language) unsigned
                // case.
                int dsize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
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
                
                hub.emit.pushReg(mnemonic.equals("DIV_INT") ? "rax" : "rdx");
                return true;
            }
            
case "SHL":
            case "SHR":
            case "SAR": {
                // Shifts have ONE well-defined meaning at every width, on both targets and in both forms (the register-form
                // twin is rfShift): the count is read as an UNSIGNED number of the operand's own width (a negative signed
                // count is therefore huge); a count >= the operand width gives 0 for SHL and SHR and the sign fill (0 or
                // -1) for SAR -- not x86's "count modulo 64". SAR = arithmetic shift right for signed operands (the value is
                // sign-extended from its width first); SHR zero-extends first. A narrow result is re-truncated, so the stack
                // word keeps holding the zero-extended value.
                int ssize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
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
            }
            
case "ROTL":
            case "ROTR": {
                // bits_rotl / bits_rotr: rotate the low `size` bytes, count modulo the width (what rol/ror do: the hardware masks the count to 5 or 6
                // bits, and a 1/2-byte rotate by (count mod 32) is the same as by (count mod width)). The value is zero-extended first so the
                // untouched upper bits of a 1/2-byte sub-register stay zero.
                int rsize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                hub.emit.popReg("rbx"); // count
                hub.emit.popReg("rax"); // value
                hub.emit.zeroExtendReg("rax", rsize);
                hub.emit.pushReg("rcx");
                hub.emit.raw("    movq %rbx, %rcx");
                hub.emit.raw("    " + (mnemonic.equals("ROTL") ? "rol" : "ror") + hub.emit.movSuffix(rsize) + " %cl, %" + hub.emit.sizedReg("rax", rsize));
                hub.emit.popReg("rcx");
                hub.emit.pushReg("rax");
                return true;
            }
            
case "BITS_OR":
            case "BITS_AND":
            case "BITS_XOR": {
                // "BITS_xxx size": pop two, and/or/xor, push. Size 8 is also what StrengthReductionPass makes of x % 2^n. A narrow
                // result is re-truncated, so the stack word keeps holding the zero-extended value (like the shifts).
                int bsize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                hub.emit.popReg("rbx");
                hub.emit.popReg("rax");
                String bop = mnemonic.equals("BITS_OR") ? "or" : mnemonic.equals("BITS_AND") ? "and" : "xor";
                hub.emit.raw(("    " + bop + "q %rbx, %rax"));
                hub.emit.zeroExtendReg("rax", bsize);
                hub.emit.pushReg("rax");
                return true;
            }
            
case "BITS_NOT": {
                // "BITS_NOT size": the bitwise complement, truncated to the operand width (u8: ~0 = 255).
                int nsize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                hub.emit.popReg("rax");
                hub.emit.raw("    notq %rax");
                hub.emit.zeroExtendReg("rax", nsize);
                hub.emit.pushReg("rax");
                return true;
            }
            
            default:
                return false;
        }
    }

    boolean emitCases3(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        switch (mnemonic) {
case "EQ_INT":
            case "NEQ_INT":
            case "LT_INT":
            case "LT_EQ_INT":
            case "GT_EQ_INT":
            case "GT_INT": {
                // "GT_INT" found genuinely missing here while building
                // float support -- the sibling `caspien-lowerordergenerator`
                // project's own AddressLoweringPass rewrites all six
                // comparison operators (GT/LT/GT_EQ/LT_EQ/EQ/NEQ) through
                // one identical rule (see its own doc comment on that
                // rewrite), so every one of them was always just as
                // reachable as GT_EQ_INT, right below it, which *was*
                // handled -- this one case was simply never added.
                // Unrelated to anything float-specific; fixed alongside it
                // rather than left now that it's been noticed, same
                // unsigned-only scope as its five siblings (see DIV_INT's
                // own note).
                int csize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                hub.emit.popReg("rbx");
                hub.emit.popReg("rax");
                hub.emit.zeroExtendReg("rax", csize);
                hub.emit.zeroExtendReg("rbx", csize);
                String setcc = mnemonic.equals("EQ_INT") ? "sete"
                        : mnemonic.equals("NEQ_INT") ? "setne"
                        : mnemonic.equals("LT_INT") ? "setb"
                        : mnemonic.equals("LT_EQ_INT") ? "setbe"
                        : mnemonic.equals("GT_EQ_INT") ? "setae"
                        : "seta"; // GT_INT (unsigned; see DIV_INT's own note on signedness)
                hub.emit.raw("    cmpq %rbx, %rax");
                hub.emit.raw("    " + setcc + " %al");
                hub.emit.raw("    movzbq %al, %rax");
                
                hub.emit.pushReg("rax");
                return true;
            }
            
case "ADD_FLOAT":
            case "SUB_FLOAT":
            case "MUL_FLOAT":
            case "DIV_FLOAT": {
                int size = (int) Long.parseLong(line.get(1).text);
                String sfx = hub.calls.sseSuffix(size);
                hub.emit.popReg("rbx"); // right operand bits
                hub.emit.popReg("rax"); // left operand bits
                hub.calls.movRegToXmm("rax", hub.gen.rfXmmA());
                hub.calls.movRegToXmm("rbx", hub.gen.rfXmmB());
                String op = mnemonic.equals("ADD_FLOAT") ? "add"
                        : mnemonic.equals("SUB_FLOAT") ? "sub"
                        : mnemonic.equals("MUL_FLOAT") ? "mul"
                        : "div";
                // AT&T "op src, dest" computes dest = dest OP src; xmm0
                // holds the left operand (pushed first, popped last,
                // exactly the ADD_INT/SUB_INT/MUL_INT convention just
                // above), xmm1 the right, so "opss %xmm1, %xmm0" gives
                // left-OP-right, in the correct order for the
                // non-commutative SUB_FLOAT/DIV_FLOAT cases.
                hub.emit.raw(("    " + op + sfx + " %" + hub.gen.rfXmmB() + ", %" + hub.gen.rfXmmA()));
                hub.calls.movXmmToReg(hub.gen.rfXmmA(), "rax");
                hub.emit.pushReg("rax");
                return true;
            }
            
case "EQ_FLOAT":
            case "NEQ_FLOAT":
            case "LT_FLOAT":
            case "LT_EQ_FLOAT":
            case "GT_EQ_FLOAT":
            case "GT_FLOAT": {
                // Same unordered-compare caveat DIV_INT/MOD_INT's own note
                // flags for unsigned-only integer division: `ucomiss` sets
                // CF=ZF=PF=1 whenever either operand is NaN ("unordered"),
                // so a NaN compared with anything reads as both "less than"
                // and "equal to" it here, rather than IEEE-754's own rule
                // that every ordered comparison against NaN is false. This
                // mirrors the file's existing precedent for a scoped,
                // documented gap rather than a silent one: correct for
                // every ordinary (non-NaN) float comparison, not yet
                // NaN-aware.
                int size = (int) Long.parseLong(line.get(1).text);
                String cmp = size <= 4 ? "ucomiss" : "ucomisd";
                hub.emit.popReg("rbx");
                hub.emit.popReg("rax");
                hub.calls.movRegToXmm("rax", hub.gen.rfXmmA());
                hub.calls.movRegToXmm("rbx", hub.gen.rfXmmB());
                hub.emit.raw(("    " + cmp + " %" + hub.gen.rfXmmB() + ", %" + hub.gen.rfXmmA()));
                String setcc = mnemonic.equals("EQ_FLOAT") ? "sete"
                        : mnemonic.equals("NEQ_FLOAT") ? "setne"
                        : mnemonic.equals("LT_FLOAT") ? "setb"
                        : mnemonic.equals("LT_EQ_FLOAT") ? "setbe"
                        : mnemonic.equals("GT_EQ_FLOAT") ? "setae"
                        : "seta"; // GT_FLOAT
                hub.emit.raw("    " + setcc + " %al");
                hub.emit.raw("    movzbq %al, %rax");
                
                hub.emit.pushReg("rax");
                return true;
            }
            
case "AND":
            case "OR": {
                hub.emit.popReg("rbx");
                hub.emit.popReg("rax");
                hub.emit.raw(("    " + (mnemonic.equals("AND") ? "and" : "or") + "b %bl, %al"));
                hub.emit.pushReg("rax");
                return true;
            }
            
case "NOT": {
                hub.emit.popReg("rax");
                hub.emit.raw("    xorb $1, %al");
                hub.emit.pushReg("rax");
                return true;
            }
            
case "NEG": {
                hub.emit.popReg("rax");
                hub.emit.raw("    negq %rax");
                hub.emit.pushReg("rax");
                return true;
            }
            
case "NEG_FLOAT": {
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
            }
            
case "INC_INT":
            case "DEC_INT": {
                hub.emit.popReg("rax");
                hub.emit.raw(("    " + (mnemonic.equals("INC_INT") ? "inc" : "dec") + "q %rax"));
                hub.emit.pushReg("rax");
                return true;
            }
            
case "INC_FLOAT":
            case "DEC_FLOAT": {
                // No SSE "increment by one" instruction exists (unlike
                // `inc`/`dec` on a GPR) -- 1.0's own IEEE-754 bit pattern
                // is loaded as a plain integer immediate (exactly how a
                // float literal already reaches a register in PUSH's own
                // isFloatLiteral branch above) and added/subtracted as a
                // real SSE operand instead.
                int size = (int) Long.parseLong(line.get(1).text);
                long oneBits = size <= 4
                        ? Integer.toUnsignedLong(Float.floatToRawIntBits(1.0f))
                        : Double.doubleToRawLongBits(1.0);
                hub.emit.popReg("rax");
                hub.calls.movRegToXmm("rax", hub.gen.rfXmmA());
                hub.emit.movImmToReg("rbx", oneBits);
                hub.calls.movRegToXmm("rbx", hub.gen.rfXmmB());
                String sfx = hub.calls.sseSuffix(size);
                String op = mnemonic.equals("INC_FLOAT") ? "add" : "sub";
                hub.emit.raw(("    " + op + sfx + " %" + hub.gen.rfXmmB() + ", %" + hub.gen.rfXmmA()));
                hub.calls.movXmmToReg(hub.gen.rfXmmA(), "rax");
                hub.emit.pushReg("rax");
                return true;
            }
            
case "CMP": {
                // Pops the boolean condition and sets the real flags
                // from it -- "if true, fall through; if false, jump
                // away" (emitForLoop's own doc comment, confirmed
                // directly), and CMP is always immediately followed by
                // exactly one JMP (verified against every real low-order
                // output this pass was checked against) -- so the
                // *next* JMP becomes a conditional "jump if false"
                // rather than an unconditional jump; see the JMP case.
                hub.emit.popReg("rax");
                hub.emit.raw("    testq %rax, %rax");
                core.lastWasCmp = true;
                return true;
            }
            
case "JMP": {
                String label = hub.gen.mangleLabel(line.get(1).text);
                if (wasCmp) {
                    hub.emit.raw(("    je " + label));
                } else {
                    hub.emit.raw(("    jmp " + label));
                }
                return true;
            }
            
case "SEXT": {
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
            }
            
case "TRUNC": {
                // "TRUNC srcSize destSize" -- integer narrowing (only ever emitted where a
                // `match x fits T` proof shows the value is representable in T). Keeps the low
                // destSize bytes and zero-fills the rest of the stack word.
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
            }
            
case "ZEXT": {
                int srcSize = (int) Long.parseLong(line.get(1).text);
                hub.emit.popReg("rax");
                if (srcSize == 4) {
                    hub.emit.raw("    movl %eax, %eax"); // writing the 32-bit half auto-zeroes the upper 32 bits
                } else if (srcSize < 8) {
                    hub.emit.raw("    movz" + hub.emit.movSuffix(srcSize) + "q %" + hub.emit.sizedReg("rax", srcSize) + ", %rax");
                }
                hub.emit.pushReg("rax");
                return true;
            }
            
case "DUP_TOP": {
                // Duplicates the top 8-byte stack word in place (pop,
                // then push it back twice) -- used only by the front
                // end's own win64-varargs-float-duplication rule (see
                // `BytecodeEmitter.emitArgTransferTail`'s own doc
                // comment): "for variadic functions, floating-point
                // arguments must be passed in both the XMM register and
                // the corresponding general-purpose register" is a
                // win64-only ABI requirement with no SysV equivalent --
                // the one already-promoted 8-byte double bit pattern
                // needs to reach both a "POP FARGn" and a "POP ARGn"
                // line, and this is what makes a second, independent
                // copy of it available to consume. A generic "duplicate
                // the top of stack" primitive, not itself float-specific
                // -- nothing about this mnemonic cares what the 8 bytes
                // mean.
                hub.emit.popReg("rax");
                hub.emit.pushReg("rax");
                hub.emit.pushReg("rax");
                return true;
            }
            
case "FCONV": {
                // "FCONV srcSize dstSize" -- float conversion: 4 -> 8 widens (cvtss2sd, exact), 8 -> 4 narrows (cvtsd2ss, rounds to
                // nearest); equal sizes leave the value alone. The value travels as its bit pattern in one stack word.
                int fromSize = (int) Long.parseLong(line.get(1).text);
                int toSize = (int) Long.parseLong(line.get(2).text);
                if (fromSize == toSize) {
                    return true;
                }
                hub.emit.popReg("rax");
                hub.calls.movRegToXmm("rax", hub.gen.rfXmmA());
                String cv = fromSize == 4 ? "cvtss2sd" : "cvtsd2ss";
                hub.emit.raw(("    " + cv + " %" + hub.gen.rfXmmA() + ", %" + hub.gen.rfXmmA()));
                if (toSize == 4) {
                    hub.gen.rfXmmToGpr(hub.gen.rfXmmA(), "rax", 4); // movd: zero-extends the 32 result bits
                } else {
                    hub.calls.movXmmToReg(hub.gen.rfXmmA(), "rax");
                }
                hub.emit.pushReg("rax");
                return true;
            }
            
case "PROMOTE_F32_TO_F64": {
                // C's own "default argument promotion" rule: a `float`
                // (this language's `f32`) argument reaching the `...`
                // portion of a variadic call (printf's own "%f" and
                // friends) is promoted to `double` *before* the call,
                // unconditionally -- never left as a raw 4-byte value,
                // regardless of what the callee's own varargs-reading
                // code assumes. This is a real, separate ABI step from
                // ordinary argument marshalling: the fixed, non-varargs
                // prefix of a variadic function's own declared
                // parameters (e.g. printf's own format-string argument)
                // is NOT promoted -- only the true varargs are -- so this
                // mnemonic is only ever emitted by the front end
                // (`emitArgWord`'s own promote flag) for an argument
                // index at or past the extern's own declared fixed
                // parameter count, on a genuinely `f32`-typed argument.
                // No `f64`/`double` type exists anywhere in this
                // language (confirmed directly, see `FLOAT_CHECK`'s own
                // doc comment above) -- this mnemonic's whole job is
                // purely a *value*-level ABI conversion, in place, on
                // the stack; it introduces no new type anywhere in the
                // rest of this bytecode format.
                //
                // Every value on this backend's own stack already
                // travels as an opaque 8-byte word (see the "Float
                // (SSE) helpers" doc comment above) -- a pushed `f32`
                // sits in the low 4 bytes of that word, upper 4 bytes
                // zero (confirmed directly against real generated
                // assembly: `movq %xmm0, %rax` zero-extends because the
                // source xmm register's own upper 32 bits are zero for
                // a real `f32` value). So: pop that word, reinterpret
                // its low 32 bits as a real `float` in a scratch xmm
                // register (`movq`, the same opaque-bit-move
                // `movRegToXmm` already uses elsewhere -- exact here
                // since the upper garbage bits are already known zero),
                // widen with a genuine `cvtss2sd` (the one real
                // floating-point conversion this mnemonic exists for),
                // move the resulting 8-byte `double` bit pattern back to
                // a GPR, and push it -- now a full, ABI-correct 8-byte
                // double occupying the same one stack word a raw `f32`
                // word already occupied, so nothing downstream
                // (register-vs-stack argument counting, `POP FARGn`, an
                // overflowing stack-passed word) needs to change at
                // all: this still consumes exactly one float-bank
                // argument slot, exactly as an unpromoted `f32` word
                // already did.
                //
                // **Deliberately uses `xmm8`, never `xmm0`, as scratch --
                // a real, confirmed register-clobber bug otherwise, the
                // identical class of hazard already documented for
                // `LOOKUP_ARRAY`'s own scratch-register choice** (this
                // project's own CLAUDE.md, "a second, real bug found
                // ... this time a register-clobber"): a multi-float
                // variadic call (e.g. `printf("%f %f", a, b)`) already
                // has `a`'s own promoted value sitting live in `xmm0`
                // (its real "POP FARG0" destination) by the time `b`'s
                // own "PUSH b / PROMOTE_F32_TO_F64 / POP FARG1" sequence
                // runs -- using `xmm0` as this mnemonic's own scratch
                // register for `b` would silently overwrite `a`'s
                // already-transferred value before the call ever runs
                // (confirmed directly: printed both floats as `b`'s own
                // value before this fix). `xmm8` is never a real SysV or
                // win64 argument register in either convention (both cap
                // out at `xmm7`/`xmm3` respectively), so it can never
                // collide with a live, not-yet-consumed `FARGn`
                // transfer, no matter how many float arguments a call
                // has or how many of them have already landed in their
                // own real argument register by the time this mnemonic
                // runs for a later one.
                hub.emit.popReg("rax");
                hub.calls.movRegToXmm("rax", hub.gen.rfXmmA());
                hub.emit.raw(("    cvtss2sd %" + hub.gen.rfXmmA() + ", %" + hub.gen.rfXmmA()));
                hub.calls.movXmmToReg(hub.gen.rfXmmA(), "rax");
                hub.emit.pushReg("rax");
                return true;
            }
            
case "PUSH_FIELDNAME": {
                // Already address-lowered to "PUSH_FIELDNAME offset
                // size" -- pushes the (compile-time-constant) field
                // offset as an immediate, for the DOT/DOT_LHS that
                // always immediately follows to add to the base address
                // already on the stack.
                long offset = Long.parseLong(line.get(1).text);
                hub.emit.movImmToReg("rax", offset);
                hub.emit.pushReg("rax");
                // Preserve a pending value-block across this (see PUSH's
                // own note above) -- e.g. a DOT chained right after a
                // LOOKUP_ARRAY read needs to know the element value
                // LOOKUP_ARRAY just left on the stack is still there.
                core.lastValueBlockSize = pendingBlockSize;
                return true;
            }
            
case "DOT_LHS": {
                int size = (int) Long.parseLong(line.get(1).text);
                hub.emit.popReg("rbx"); // field offset (from PUSH_FIELDNAME)
                hub.emit.popReg("rax"); // base address
                hub.emit.raw("    addq %rbx, %rax");
                hub.emit.pushReg("rax"); // the computed field address itself
                return true;
            }
            
            default:
                return false;
        }
    }
}

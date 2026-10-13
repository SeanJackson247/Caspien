package caspien.codegen.x86backend;

import caspien.codegen.CodegenTarget;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.codegen.CodegenException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one and only backend so far: x86-64, either target. This is a
 * *substantial* second pass, not a finished code generator -- "begin
 * building the fourth program... this will generate assembly
 * instructions from the lower order bytecode," confirmed directly, plus
 * a later, explicit "fix that [PUSH] bug and then work thru the
 * remaining 70+ [mnemonics]," confirmed directly. It now correctly
 * translates the great majority of the real low-order instruction set
 * (see the mnemonic list in `emitLine`'s own switch); anything still
 * genuinely unresolved is emitted as a visible `; TODO(codegen): <line>
 * not yet implemented` comment rather than silently dropped or guessed
 * at -- see this project's own CLAUDE.md, "Known gaps," for the
 * current, honest list of what's still missing and *why* (a few of
 * these turned out to need either a real runtime-protocol decision that
 * doesn't exist anywhere yet, such as GT_UNWIND/async, or a fix one
 * layer up in caspien-lowerordergenerator itself, such as WITHIN's
 * incomplete-range case and INSTANCEOF's class tag -- both still carry
 * unresolved symbolic operands even at "low order," not something
 * codegen alone can safely translate).
 *
 * Translation strategy: this bytecode is a stack machine (PUSH/POP/
 * ADDR all push or consume one 8-byte cell), and the host machine has
 * a real stack of its own -- so, for this first version, every
 * bytecode-level push/pop is translated 1:1 to a real `push`/`pop` of
 * the target's own hardware stack. This is deliberately not an
 * optimizing translation (a real backend would keep short-lived values
 * in registers instead) -- it is a straightforward, easy-to-verify
 * mapping, chosen so this first version can be checked for
 * correctness by actually assembling and running its output, not just
 * read for plausibility.
 *
 * Memory layout decisions this pass had to make on its own (nothing
 * else in the pipeline reads a dynarray's or a ghost-registered heap
 * pointer's actual bytes except the mnemonics this backend itself
 * implements, confirmed by grepping every sibling project's own source
 * for any DOT/PUSH_FIELDNAME-style structural access to one -- there is
 * none, so this backend is free to choose its own internal
 * representation as long as it is self-consistent):
 * - A dynarray value is a pointer to a malloc'd block laid out as
 *   [8-byte len][8-byte capacity][elements...]. `len`/`capacity` are
 *   always kept equal (no growth slack) for this first version's own
 *   simplicity -- see LEN/RESIZE/URESIZE/LOOKUP_DYN(_LHS).
 * - Every other heap value (NEW/CLONE) is a bare malloc'd block of
 *   exactly the given size, no header at all.
 *
 * Two concrete targets share this one class, distinguished by
 * `CodegenTarget` (set from `codegen.config`); both emit GNU
 * assembler (GAS) AT&T syntax:
 * - LINUX_X64: SysV calling convention (rdi/rsi/rdx/rcx/r8/r9).
 * - WINDOWS_GNU_X64: win64 calling convention (rcx/rdx/r8/r9 + 32-byte
 *   shadow space), assembled and linked with mingw-w64 gcc.
 * Both are run in this project's test sandbox (Windows under Wine). There
 * is no MASM/Intel-syntax target: it was removed on purpose.
 *
 * Source layout (composition, not inheritance; Java has no partial classes and one file would be 7000+ lines):
 * X86Backend is the public hub that builds the shared state X86Core (fields, constants, static helpers) and the
 * parts: X86Emit (emission helpers, construction runs, sized moves, block copies), X86Calls (argument registers,
 * aligned calls, `emitLine`/`emitLineInner`, the first mnemonic group), X86CasesA and X86CasesB (more mnemonic groups,
 * `emitCasesN`, each a `switch` that returns false for a mnemonic it does not own), X86RegForm (register-form `R_*`
 * helpers) and X86Generator (the rest of the register form, callee-saved handling, `generate`). A part reaches the
 * state as `core.x` and another part as `hub.<part>.x`. No file is over 1500 lines.
 */
final class X86Generator {
    private final X86Backend hub;
    private final X86Core core;

    X86Generator(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    public String generate(List<BytecodeStatement> lines) {
        core.out.setLength(0);
        core.globalsSizeInit.clear();
        core.globalsHasInit.clear();
        core.stringLiterals.clear();
        core.floatPool.clear();
        core.globalAliasOffset.clear();
        core.aliasInits.clear();
        core.globalAliasParent.clear();
        hub.emit.collectDeclarations(lines);
        hub.emit.emitHeader();
        hub.emit.emitDataSections();
        hub.emit.raw(".text");
        core.allLines = lines;
        hub.emit.precomputeConstructionOffsets();
        core.callBufferStack.clear();
        core.argTrackDeltaStack.clear();
        core.argTrackSuspendDepth = 0;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            core.currentLineIndex = lineIndex;
            BytecodeStatement line = lines.get(lineIndex);
            hub.calls.emitLine(line);
        }
        emitFloatPool();
        hub.emit.emitFooter();
        return core.out.toString();
    }

    void rfBin(String op, int size, String dstTok, String aTok, String bTok) {
        if (op.equals("SHL") || op.equals("SHR") || op.equals("SAR")) {
            hub.regForm.rfShift(op, size, dstTok, aTok, bTok);
            return;
        }
        if (op.equals("ROTL") || op.equals("ROTR")) {
            hub.regForm.rfRotate(op, size, dstTok, aTok, bTok);
            return;
        }
        String d = hub.regForm.rfReg(dstTok);
        boolean commutative = op.equals("ADD") || op.equals("MUL") || op.equals("AND") || op.equals("OR")
                || op.equals("BAND") || op.equals("BOR") || op.equals("BXOR") || op.equals("EQ") || op.equals("NEQ");
        if ((op.equals("ADD") || op.equals("MUL") || op.equals("BAND") || op.equals("BOR") || op.equals("BXOR")) && X86Core.rfIsImm(aTok) && !X86Core.rfIsImm(bTok)) {
            // a constant on the left of a commutative operation: swap, so the constant forms (shift / lea / add imm / sized imm) apply
            String sw = aTok;
            aTok = bTok;
            bTok = sw;
        }
        if (op.equals("MUL") && X86Core.rfIsImm(bTok) && !X86Core.rfIsImm(aTok) && rfMulByBigConst(d, aTok, X86Core.rfImm(bTok))) {
            return;
        }
        if (op.equals("MUL") && size == 8 && X86Core.rfIsImm(bTok) && !X86Core.rfIsImm(aTok) && X86Core.rfFitsImm32(X86Core.rfImm(bTok)) && !X86Core.mulByConstShape(X86Core.rfImm(bTok))
                && !(X86Core.rfIsTemp(aTok) && hub.regForm.rfReg(aTok).equals(d)) && !(X86Core.rfIsTemp(bTok) && hub.regForm.rfReg(bTok).equals(d))) {
            // d = a * imm32 in one instruction (imul r64, r/m64, imm32) instead of `mov a, d; imul imm, d`
            hub.emit.raw("    imulq $" + X86Core.rfImm(bTok) + ", " + (X86Core.rfIsTemp(aTok) ? hub.regForm.rfRegText(hub.regForm.rfReg(aTok)) : hub.regForm.rfMemText(X86Core.rfSlot(aTok))) + ", " + hub.regForm.rfRegText(d));
            return;
        }
        String bText = null;
        if (X86Core.rfIsTemp(bTok) && hub.regForm.rfReg(bTok).equals(d) && !(X86Core.rfIsTemp(aTok) && hub.regForm.rfReg(aTok).equals(d))) {
            // The destination register is b's own register: a plain "d = a; d op= b" would overwrite b first.
            if (commutative) {
                String t = aTok;
                aTok = bTok;
                bTok = t;
            } else {
                hub.regForm.rfRegToReg(X86Core.RF_SCRATCH, d);
                bText = hub.regForm.rfRegText(X86Core.RF_SCRATCH);
            }
        }
        boolean narrowCmp = size < 8 && !(op.equals("ADD") || op.equals("SUB") || op.equals("MUL") || op.equals("AND") || op.equals("OR")
                || op.equals("BAND") || op.equals("BOR") || op.equals("BXOR"));
        boolean signedCmp = narrowCmp && op.charAt(0) == 'S';
        if (narrowCmp) {
            // A narrow compare looks at the low `size` bytes only: constants are cut down at compile time,
            // registers extended (the stack form's zeroExtendReg/signExtendReg on both operands).
            if (X86Core.rfIsImm(aTok)) {
                aTok = "#" + X86Core.rfExtImm(X86Core.rfImm(aTok), size, signedCmp);
            }
            if (X86Core.rfIsImm(bTok)) {
                bTok = "#" + X86Core.rfExtImm(X86Core.rfImm(bTok), size, signedCmp);
            }
        }
        if (bText == null && op.equals("BAND") && size == 8 && X86Core.rfIsImm(bTok) && X86Core.rfIsTemp(aTok) && !hub.regForm.rfReg(aTok).equals(d)
                && (X86Core.rfImm(bTok) == 0xFFFFFFFFL || X86Core.rfImm(bTok) == 0xFFFFL || X86Core.rfImm(bTok) == 0xFFL)) {
            // x & 0xFF / 0xFFFF / 0xFFFFFFFF into another register: one zero-extending move instead of a copy and a mask
            long mk = X86Core.rfImm(bTok);
            if (mk == 0xFFFFFFFFL) {
                hub.emit.raw("    movl %" + hub.emit.sizedReg(hub.regForm.rfReg(aTok), 4) + ", %" + hub.emit.sizedReg(d, 4));
            } else {
                hub.emit.raw("    movz" + (mk == 0xFFL ? "b" : "w") + "l %" + hub.emit.sizedReg(hub.regForm.rfReg(aTok), mk == 0xFFL ? 1 : 2) + ", %" + hub.emit.sizedReg(d, 4));
            }
            return;
        }
        if (!(X86Core.rfIsTemp(aTok) && hub.regForm.rfReg(aTok).equals(d))) {
            hub.regForm.rfMov(dstTok, aTok);
        }
        if (bText == null && op.equals("BAND") && size == 8 && X86Core.rfIsImm(bTok) && X86Core.rfImm(bTok) == 0xFFFFFFFFL) {
            // x & 0xFFFFFFFF: a 32-bit self-move zero-extends (no 10-byte constant through the scratch register)
            hub.emit.raw("    movl %" + hub.emit.sizedReg(d, 4) + ", %" + hub.emit.sizedReg(d, 4));
            return;
        }
        if (bText == null) {
            bText = hub.regForm.rfSrc(bTok);
        }
        if (narrowCmp) {
            rfExtend(d, size, signedCmp);
            if (bText.equals(hub.regForm.rfRegText(X86Core.RF_SCRATCH))) {
                rfExtend(X86Core.RF_SCRATCH, size, signedCmp);
            } else if (X86Core.rfIsTemp(bTok) && !hub.regForm.rfReg(bTok).equals(d)) {
                rfExtend(hub.regForm.rfReg(bTok), size, signedCmp);
            }
        }
        String dText = hub.regForm.rfRegText(d);
        switch (op) {
            case "ADD":
                hub.regForm.rfOp2("add", dText, bText);
                return;
            case "SUB":
                hub.regForm.rfOp2("sub", dText, bText);
                return;
            case "MUL":
                if (bText.startsWith("$") && rfMulByConst(d, Long.parseLong(bText.substring(1)))) {
                    return;
                }
                hub.regForm.rfOp2("imul", dText, bText);
                return;
            case "BAND":
            case "BOR":
            case "BXOR":
                // bitwise and / or / xor (BAND is also x % 2^n); the mask is a small immediate or a register. A narrow result is
                // cut back to the operand width, so the temp holds the zero-extended value (the entry is marked zx by the pass).
                hub.regForm.rfOp2(op.equals("BAND") ? "and" : op.equals("BOR") ? "or" : "xor", dText, bText);
                hub.emit.zeroExtendReg(d, size);
                return;
            case "AND":
            case "OR": {
                String o = op.equals("AND") ? "and" : "or";
                if (size == 1) {
                    // Booleans: both operands are register-held 0/1 results; byte-wide, like the stack form.
                    String bReg = X86Core.rfIsTemp(bTok) ? hub.regForm.rfReg(bTok) : d;
                    hub.emit.raw("    " + o + "b %" + hub.emit.sizedReg(bReg, 1) + ", %" + hub.emit.sizedReg(d, 1));
                    
                } else {
                    hub.regForm.rfOp2(o, dText, bText);
                }
                return;
            }
            default:
                break;
        }
        String setcc;
        switch (op) {
            case "EQ": setcc = "sete"; break;
            case "NEQ": setcc = "setne"; break;
            case "LT": setcc = "setb"; break;
            case "LT_EQ": setcc = "setbe"; break;
            case "GT": setcc = "seta"; break;
            case "GT_EQ": setcc = "setae"; break;
            case "SLT": setcc = "setl"; break;
            case "SLT_EQ": setcc = "setle"; break;
            case "SGT": setcc = "setg"; break;
            case "SGT_EQ": setcc = "setge"; break;
            default:
                throw new IllegalStateException("unknown R_BIN operator '" + op + "'");
        }
        hub.regForm.rfOp2("cmp", dText, bText);
        String d8 = hub.emit.sizedReg(d, 1);
        hub.emit.raw("    " + setcc + " %" + d8);
        hub.emit.raw("    movzbq %" + d8 + ", %" + d);
        
    }

    /**
     * `d = a * k` for k = 2^n + 1 or 2^n - 1 above 2^31 (too big for an imm32 imul: it would take a 10-byte `movabs` plus a 3-cycle `imul`), as
     * `mov a, d; shl n, d; add|sub a, d` (a is read again, so a == d goes through the scratch register). The low 64 bits equal the imul's.
     * Returns false (nothing emitted) for any other k.
     */
    boolean rfMulByBigConst(String d, String aTok, long k) {
        if (k <= (1L << 31)) {
            return false;
        }
        boolean plus = Long.bitCount(k - 1) == 1;
        if (!plus && Long.bitCount(k + 1) != 1) {
            return false;
        }
        int sh = Long.numberOfTrailingZeros(plus ? k - 1 : k + 1);
        String mn = plus ? "addq" : "subq";
        if (X86Core.rfIsTemp(aTok) && hub.regForm.rfReg(aTok).equals(d)) {
            hub.emit.raw("    movq %" + d + ", %" + X86Core.RF_SCRATCH);
            hub.emit.raw("    shlq $" + sh + ", %" + d);
            hub.emit.raw("    " + mn + " %" + X86Core.RF_SCRATCH + ", %" + d);
        } else if (X86Core.rfIsTemp(aTok)) {
            hub.regForm.rfRegToReg(d, hub.regForm.rfReg(aTok));
            hub.emit.raw("    shlq $" + sh + ", %" + d);
            hub.emit.raw("    " + mn + " %" + hub.regForm.rfReg(aTok) + ", %" + d);
        } else {
            hub.emit.movMemToReg(d, X86Core.rfSlot(aTok));
            hub.emit.raw("    shlq $" + sh + ", %" + d);
            hub.emit.raw("    " + mn + " " + hub.regForm.rfMemText(X86Core.rfSlot(aTok)) + ", %" + d);
        }
        return true;
    }

    /**
     * `d *= k` for a constant k as shifts and adds instead of an `imul` (3 cycles of latency; the shifts are 1 + 1): a power of two is one
     * `shl`, 3, 5 and 9 are one `lea`, 2^n + 1 and 2^n - 1 are `mov scratch; shl; add|sub scratch` (so `x * 31` is `(x << 5) - x`). The low 64 bits
     * are the same as the imul's, which is all a register holds (narrow products are never masked there anyway). Returns false for any other k.
     */
    boolean rfMulByConst(String d, long k) {
        if (k < 2 || k > (1L << 31)) {
            return false;
        }
        String r = "%" + d;
        if (Long.bitCount(k) == 1) {
            hub.emit.raw("    shlq $" + Long.numberOfTrailingZeros(k) + ", " + r);
            return true;
        }
        if (k == 3 || k == 5 || k == 9) {
            hub.emit.raw("    leaq (" + r + "," + r + "," + (k - 1) + "), " + r);
            return true;
        }
        boolean plus = Long.bitCount(k - 1) == 1;
        if (!plus && Long.bitCount(k + 1) != 1) {
            return false;
        }
        int sh = Long.numberOfTrailingZeros(plus ? k - 1 : k + 1);
        hub.emit.raw("    movq " + r + ", %" + X86Core.RF_SCRATCH);
        hub.emit.raw("    shlq $" + sh + ", " + r);
        hub.emit.raw("    " + (plus ? "addq" : "subq") + " %" + X86Core.RF_SCRATCH + ", " + r);
        return true;
    }

    /**
     * R_BRCM: jump to `label` when NOT (a op [base]), where [base] is the 8-byte word at the address held in the register `base`
     * (the safe dynarray length word: `cmpq (%base), %a`). `a` is a %t/%v register or a frame slot.
     */
    void rfBrcMem(String op, String aTok, String baseTok, String label) {
        String cc = X86Core.rfFalseJump(op);
        String aText;
        if (X86Core.rfIsSlot(aTok)) {
            hub.regForm.rfMov("%s", aTok); // cmp takes one memory operand: a frame-slot index goes through the scratch register
            aText = hub.regForm.rfRegText(X86Core.RF_SCRATCH);
        } else {
            aText = hub.regForm.rfRegText(hub.regForm.rfReg(aTok));
        }
        String bText = "(" + hub.regForm.rfRegText(hub.regForm.rfReg(baseTok)) + ")";
        hub.regForm.rfOp2("cmp", aText, bText);
        hub.emit.raw("    " + cc + " " + label);
    }

    /**
     * R_BRC of a 1, 2 or 4 byte compare: one sized cmp (cmpb/cmpw/cmpl) of the low bytes of two registers or a register and an immediate,
     * then the jump taken when the comparison is false. Unsigned operators use the unsigned conditions and S* the signed ones, which on a
     * sized cmp look at exactly the low `size` bytes (what the unfused R_BIN did by extending both operands first). Frame-slot operands are
     * never fused (BranchFusionPass).
     */
    String rfBrcNarrow(String op, int size, String aTok, String bTok, String label) {
        String cc = X86Core.rfFalseJump(op);
        if (X86Core.rfIsImm(aTok)) {
            String t = aTok;
            aTok = bTok;
            bTok = t;
            cc = X86Core.rfMirrorJump(cc);
        }
        if (!X86Core.rfIsTemp(aTok) || !(X86Core.rfIsTemp(bTok) || X86Core.rfIsImm(bTok))) {
            throw new IllegalStateException("narrow R_BRC needs register or immediate operands: " + aTok + " " + bTok);
        }
        String suffix = size == 1 ? "b" : size == 2 ? "w" : "l";
        String aText = "%" + hub.emit.sizedReg(hub.regForm.rfReg(aTok), size);
        String bText = X86Core.rfIsImm(bTok) ? "$" + X86Core.rfTrunc(X86Core.rfImm(bTok), size) : "%" + hub.emit.sizedReg(hub.regForm.rfReg(bTok), size);
        hub.emit.raw("    cmp" + suffix + " " + bText + ", " + aText);
        if (label != null) {
            hub.emit.raw("    " + cc + " " + label);
        }
        return cc;
    }

    String rfBrc(String op, String aTok, String bTok, String label) {
        String cc = X86Core.rfFalseJump(op); // the jump taken when the comparison is false
        if (X86Core.rfIsImm(aTok)) {
            // "cmp imm, x" does not exist: swap the operands and mirror the condition.
            String t = aTok;
            aTok = bTok;
            bTok = t;
            cc = X86Core.rfMirrorJump(cc);
        }
        String aText;
        if (X86Core.rfIsTemp(aTok)) {
            aText = hub.regForm.rfRegText(hub.regForm.rfReg(aTok));
        } else if (X86Core.rfIsSlot(aTok) && !X86Core.rfIsSlot(bTok)) {
            aText = hub.regForm.rfMemText(X86Core.rfSlot(aTok));
        } else {
            // both operands are slots (cmp takes at most one memory operand): a goes through the scratch register
            hub.regForm.rfMov("%s", aTok);
            aText = hub.regForm.rfRegText(X86Core.RF_SCRATCH);
        }
        String bText = hub.regForm.rfSrc(bTok); // a huge immediate goes through the scratch register (a is then never in it: a slot stays in memory)
        hub.regForm.rfOp2("cmp", aText, bText);
        if (label != null) {
            hub.emit.raw("    " + cc + " " + label);
        }
        return cc;
    }

    /** `mov imm, reg` that leaves the flags alone (never the xor idiom) */
    void rfMovImmKeepFlags(String reg, long v) {
        if (v >= 0 && v <= 0xFFFFFFFFL) {
            hub.emit.raw("    movl $" + v + ", %" + hub.emit.sizedReg(reg, 4));
        } else if (X86Core.rfFitsImm32(v)) {
            hub.emit.raw("    movq $" + v + ", %" + reg);
        } else {
            hub.emit.raw("    movabsq $" + v + ", %" + reg);
        }
    }

    /**
     * "R_CMOV OP n a b %vK X [Y]" -- the select made by the LowerOrderGenerator's ConditionalMovePass: %vK = (a OP b) ? X : Y (Y absent = %vK keeps its
     * value). X and Y are a register or an immediate (already cut to the variable's width). One compare, then `mov Y` (flags untouched) and `cmovCC X`.
     */
    void rfCmov(BytecodeStatement line) {
        String op = line.get(1).text;
        int size = (int) Long.parseLong(line.get(2).text);
        String a = line.get(3).text, b = line.get(4).text, v = line.get(5).text, x = line.get(6).text;
        String y = line.size() > 7 ? line.get(7).text : null;
        String dst = hub.regForm.rfReg(v);
        String cc = size < 8 ? rfBrcNarrow(op, size, a, b, null) : rfBrc(op, a, b, null);
        if (y != null && !y.equals(v)) {
            if (X86Core.rfIsImm(y)) {
                rfMovImmKeepFlags(dst, X86Core.rfImm(y));
            } else {
                hub.regForm.rfRegToReg(dst, hub.regForm.rfReg(y));
            }
        }
        String xr;
        if (X86Core.rfIsImm(x)) {
            rfMovImmKeepFlags(X86Core.RF_SCRATCH, X86Core.rfImm(x));
            xr = X86Core.RF_SCRATCH;
        } else {
            xr = hub.regForm.rfReg(x);
        }
        hub.emit.raw("    cmov" + X86Core.rfTrueSuffix(cc) + "q %" + xr + ", %" + dst);
    }

    void rfExtend(String reg, int size, boolean signed) {
        if (signed) {
            hub.emit.signExtendReg(reg, size);
        } else {
            hub.emit.zeroExtendReg(reg, size);
        }
    }

    void rfUn(String op, int size, String dstTok, String aTok) {
        String d = hub.regForm.rfReg(dstTok);
        if (!(X86Core.rfIsTemp(aTok) && hub.regForm.rfReg(aTok).equals(d))) {
            hub.regForm.rfMov(dstTok, aTok);
        }
        switch (op) {
            case "INC":
                hub.emit.raw(("    incq %" + d));
                return;
            case "DEC":
                hub.emit.raw(("    decq %" + d));
                return;
            case "NEG":
                hub.emit.raw(("    negq %" + d));
                return;
            case "NOT":
                hub.emit.raw(("    xorb $1, %" + hub.emit.sizedReg(d, 1)));
                return;
            case "BNOT":
                // bitwise complement cut back to the operand width (u8: ~0 = 255)
                hub.emit.raw(("    notq %" + d));
                hub.emit.zeroExtendReg(d, size);
                return;
            default:
                throw new IllegalStateException("unknown R_UN operator '" + op + "'");
        }
    }

    /** address of a frame slot ("$off") or a global ("&sym") into reg. */
    void rfAddrToReg(String reg, String tok) {
        if (X86Core.rfIsGlobal(tok)) {
            hub.emit.leaGlobalToReg(reg, tok.substring(1));
        } else {
            hub.emit.leaMemToReg(reg, X86Core.rfSlot(tok));
        }
    }

    /** An integer argument register; an index beyond the register-passed ones would fall back to rax (a temp), which the pass never produces. */
    String rfArgReg(int idx) {
        String reg = hub.calls.argReg(idx);
        if (reg.equals("rax")) {
            throw new CodegenException("codegen", "<register-form>", 0, "R_ARG index " + idx + " is beyond the register-passed arguments");
        }
        return reg;
    }

    void rfLoad(int n, String dstTok, String srcTok) {
        String d = hub.regForm.rfReg(dstTok);
        if (X86Core.rfIsSlot(srcTok)) {
            hub.emit.loadSizedFromFrame(d, X86Core.rfSlot(srcTok), n);
        } else if (X86Core.rfIsGlobal(srcTok)) {
            // one instruction, `mov sym+off(%rip), %d`, not `leaq sym(%rip), %d; mov (%d), %d`
            core.rfMemOverride = hub.emit.globalRipOperand(srcTok.substring(1));
            try {
                hub.emit.loadSizedFromAddr(d, d, n);
            } finally {
                core.rfMemOverride = null;
            }
        } else {
            hub.emit.loadSizedFromAddr(d, hub.regForm.rfReg(srcTok), n);
        }
    }

    /** "(disp)(%base)" memory text for a register base plus a constant displacement */
    String rfDispMem(String baseTok, long disp) {
        return (disp != 0 ? String.valueOf(disp) : "") + "(%" + hub.regForm.rfReg(baseTok) + ")";
    }

    /** %tD = n bytes (1, 2, 4 or 8) at base + disp, zero-extended */
    void rfLoadDisp(int n, String dstTok, String baseTok, long disp) {
        String d = hub.regForm.rfReg(dstTok);
        String prev = core.rfMemOverride;
        core.rfMemOverride = rfDispMem(baseTok, disp);
        try {
            hub.emit.loadSizedFromAddr(d, d, n);
        } finally {
            core.rfMemOverride = prev;
        }
    }

    /** n bytes (1, 2, 4 or 8) at base + disp = src (#imm or a register) */
    void rfStoreDisp(int n, String baseTok, long disp, String srcTok) {
        String mem = rfDispMem(baseTok, disp);
        if (X86Core.rfIsImm(srcTok)) {
            hub.emit.raw("    mov" + hub.emit.movSuffix(n) + " $" + X86Core.rfTrunc(X86Core.rfImm(srcTok), n) + ", " + mem);
            return;
        }
        hub.emit.raw("    mov" + hub.emit.movSuffix(n) + " %" + hub.emit.sizedReg(hub.regForm.rfReg(srcTok), n) + ", " + mem);
    }

    void rfLoadXDisp(String xmm, String baseTok, long disp, int n) {
        hub.emit.raw("    " + (n == 8 ? "movsd" : "movss") + " " + rfDispMem(baseTok, disp) + ", %" + xmm);
    }

    void rfStoreXDisp(String baseTok, long disp, String xmm, int n) {
        hub.emit.raw("    " + (n == 8 ? "movsd" : "movss") + " %" + xmm + ", " + rfDispMem(baseTok, disp));
    }

    void rfStore(int n, String addrTok, String srcTok) {
        String areg = null; // register holding the address; null = a frame slot
        String ripMem = null; // a global stored with one instruction, `mov %r, sym+off(%rip)`
        if (X86Core.rfIsGlobal(addrTok) && !X86Core.isOddSize(n)) {
            ripMem = hub.emit.globalRipOperand(addrTok.substring(1));
        } else if (X86Core.rfIsGlobal(addrTok)) {
            hub.emit.leaGlobalToReg(X86Core.RF_SCRATCH, addrTok.substring(1));
            areg = X86Core.RF_SCRATCH;
        } else if (!X86Core.rfIsSlot(addrTok)) {
            areg = hub.regForm.rfReg(addrTok);
        }
        if (X86Core.rfIsImm(srcTok)) {
            long v = X86Core.rfTrunc(X86Core.rfImm(srcTok), n);
            String mem;
            mem = ripMem != null ? ripMem : areg == null ? (X86Core.rfSlot(addrTok) + "(%rbp)") : ("(%" + areg + ")");
            hub.emit.raw("    mov" + hub.emit.movSuffix(n) + " $" + v + ", " + mem);
            
            return;
        }
        String reg = hub.regForm.rfReg(srcTok);
        if (ripMem != null) {
            hub.emit.raw("    mov" + hub.emit.movSuffix(n) + " %" + hub.emit.sizedReg(reg, n) + ", " + ripMem);
        } else if (areg == null) {
            hub.emit.storeSizedToFrame(reg, X86Core.rfSlot(addrTok), n);
        } else {
            hub.emit.storeSizedToAddr(reg, areg, n);
        }
    }

    String rfMemOperand(String baseReg) {
        if (core.rfMemOverride != null) {
            return core.rfMemOverride;
        }
        if (core.rfIdxReg == null) {
            return ("(%" + baseReg + ")");
        }
        return (core.rfIdxDisp != 0 ? String.valueOf(core.rfIdxDisp) : "") + "(%" + baseReg + ",%" + core.rfIdxReg + "," + core.rfIdxScale + ")";
    }

    /**
     * Base of an indexed access (R_LDI/R_STI/R_LDXI/R_STXI): a global is loaded into `scratch`, a frame slot is addressed
     * off rbp with a displacement, a temp or variable register is used as it is. Sets rfIdxDisp; the caller resets it.
     */
    String rfIndexedBase(String baseTok, String scratch) {
        core.rfIdxDisp = 0;
        if (X86Core.rfIsGlobal(baseTok)) {
            hub.emit.leaGlobalToReg(scratch, baseTok.substring(1));
            return scratch;
        }
        if (X86Core.rfIsSlot(baseTok)) {
            core.rfIdxDisp = X86Core.rfSlot(baseTok);
            return "rbp";
        }
        if (X86Core.rfIsTemp(baseTok)) {
            return hub.regForm.rfReg(baseTok);
        }
        throw new IllegalStateException("malformed indexed base " + baseTok);
    }

    /**
     * Index register + scale of an indexed access. A hardware scale (1/2/4/8) is used as it is; any other stride (a struct element: 24, 56 ...)
     * is multiplied into the scratch register first (`imulq $scale, %idx, %r15`, the variable is not touched) and the operand becomes
     * `disp(%base,%r15,1)`. The base of such an access must not be a global (a global base address already needs the scratch register).
     */
    void rfSetIndex(String baseTok, String idxTok, long scale) {
        if (X86Core.rfHwScale(scale)) {
            core.rfIdxReg = hub.regForm.rfReg(idxTok);
            core.rfIdxScale = scale;
            return;
        }
        if (X86Core.rfIsGlobal(baseTok) || scale <= 0 || !X86Core.rfFitsImm32(scale)) {
            throw new IllegalStateException("malformed indexed access (stride " + scale + ", base " + baseTok + ")");
        }
        String ir = hub.regForm.rfReg(idxTok);
        if (core.strideKeySink == null || core.strideKeySink != hub.emit.currentSink() || core.strideKeyScale != scale || !ir.equals(core.strideKeyIdx)) {
            hub.emit.raw("    imulq $" + scale + ", %" + ir + ", %" + X86Core.RF_SCRATCH);
            core.strideKeySink = hub.emit.currentSink();
            core.strideKeyIdx = ir;
            core.strideKeyScale = scale;
        } else {
            core.strideCseHits++;
        }
        core.rfIdxReg = X86Core.RF_SCRATCH;
        core.rfIdxScale = 1;
    }

    void rfLoadIndexed(int n, String dstTok, String baseTok, String idxTok, long scale, long disp) {
        if (!(n == 1 || n == 2 || n == 4 || n == 8) || !X86Core.rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_LDI");
        }
        String d = hub.regForm.rfReg(dstTok);
        String b = rfIndexedBase(baseTok, d);
        core.rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            hub.emit.loadSizedFromAddr(d, b, n);
        } finally {
            core.rfIdxReg = null;
            core.rfIdxDisp = 0;
        }
    }

    void rfStoreIndexed(int n, String baseTok, String idxTok, long scale, String srcTok, long disp) {
        if (!(n == 1 || n == 2 || n == 4 || n == 8) || !X86Core.rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_STI");
        }
        String b = rfIndexedBase(baseTok, X86Core.RF_SCRATCH);
        core.rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            if (X86Core.rfIsImm(srcTok)) {
                long v = X86Core.rfTrunc(X86Core.rfImm(srcTok), n);
                hub.emit.raw("    mov" + hub.emit.movSuffix(n) + " $" + v + ", " + rfMemOperand(b));
                
            } else {
                hub.emit.storeSizedToAddr(hub.regForm.rfReg(srcTok), b, n);
            }
        } finally {
            core.rfIdxReg = null;
            core.rfIdxDisp = 0;
        }
    }

    /** xmm register = the float at global + index*scale (R_LDXI): the base address goes through the scratch register. */
    void rfLoadXIndexed(int n, String xTok, String baseTok, String idxTok, long scale, long disp) {
        if (!(n == 4 || n == 8) || (X86Core.rfHwScale(scale) && scale != n) || !X86Core.rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_LDXI");
        }
        String b = rfIndexedBase(baseTok, X86Core.RF_SCRATCH);
        core.rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            String mem = (rfMemOperand(b));
            String mn = n == 8 ? "movsd" : "movss";
            String xmm = xvReg(xTok);
            hub.emit.raw(("    " + mn + " " + mem + ", %" + xmm));
        } finally {
            core.rfIdxReg = null;
            core.rfIdxDisp = 0;
        }
    }

    /** the float in an xmm register stored at base + index*scale (R_STXI). */
    void rfStoreXIndexed(int n, String baseTok, String idxTok, long scale, String xTok, long disp) {
        if (!(n == 4 || n == 8) || (X86Core.rfHwScale(scale) && scale != n) || !X86Core.rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_STXI");
        }
        String b = rfIndexedBase(baseTok, X86Core.RF_SCRATCH);
        core.rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            String mem = (rfMemOperand(b));
            String mn = n == 8 ? "movsd" : "movss";
            String xmm = xvReg(xTok);
            hub.emit.raw(("    " + mn + " %" + xmm + ", " + mem));
        } finally {
            core.rfIdxReg = null;
            core.rfIdxDisp = 0;
        }
    }

    void rfAddImm(String reg, long v) {
        if (v == 0) {
            return;
        }
        if (X86Core.rfFitsImm32(v)) {
            hub.emit.raw(("    addq $" + v + ", %" + reg));
        } else {
            hub.emit.movImmToReg(X86Core.RF_SCRATCH, v);
            hub.emit.raw(("    addq %" + X86Core.RF_SCRATCH + ", %" + reg));
        }
    }

    void rfLea(String dstTok, String baseTok, String idxTok, long scale, long extra) {
        String d = hub.regForm.rfReg(dstTok);
        boolean baseIsT = X86Core.rfIsTemp(baseTok);
        String bReg = baseIsT ? hub.regForm.rfReg(baseTok) : null;
        if (X86Core.rfIsImm(idxTok)) {
            long disp = X86Core.rfImm(idxTok) * scale + extra;
            if (X86Core.rfIsSlot(baseTok)) {
                long total = X86Core.rfSlot(baseTok) + disp;
                if (X86Core.rfFitsImm32(total)) {
                    hub.emit.leaMemToReg(d, total);
                } else {
                    hub.emit.leaMemToReg(d, X86Core.rfSlot(baseTok));
                    rfAddImm(d, disp);
                }
            } else if (X86Core.rfIsGlobal(baseTok)) {
                hub.emit.leaGlobalToReg(d, baseTok.substring(1));
                rfAddImm(d, disp);
            } else {
                hub.regForm.rfRegToReg(d, bReg);
                rfAddImm(d, disp);
            }
            return;
        }
        String x;
        boolean prescaled = false;
        if (X86Core.rfIsTemp(idxTok)) {
            x = hub.regForm.rfReg(idxTok);
            if (!(scale == 1 || scale == 2 || scale == 4 || scale == 8) && X86Core.rfFitsImm32(scale)) {
                // scale * idx in one 3-operand imul into the scratch register (no copy first, never in place on a variable)
                hub.emit.raw(("    imulq $" + scale + ", %" + x + ", %" + X86Core.RF_SCRATCH));
                x = X86Core.RF_SCRATCH;
                prescaled = true;
            } else if (X86Core.rfIsVar(idxTok) && !(scale == 1 || scale == 2 || scale == 4 || scale == 8)) {
                hub.regForm.rfRegToReg(X86Core.RF_SCRATCH, x); // the multiply below works in place; never on a variable
                x = X86Core.RF_SCRATCH;
            }
            if (x.equals(d) && !(baseIsT && bReg.equals(d))) {
                hub.regForm.rfRegToReg(X86Core.RF_SCRATCH, x); // the index lives in the destination register: keep it before the base overwrites it
                x = X86Core.RF_SCRATCH;
            }
        } else {
            hub.emit.movMemToReg(X86Core.RF_SCRATCH, X86Core.rfSlot(idxTok));
            x = X86Core.RF_SCRATCH;
        }
        if (X86Core.rfIsSlot(baseTok)) {
            hub.emit.leaMemToReg(d, X86Core.rfSlot(baseTok));
        } else if (X86Core.rfIsGlobal(baseTok)) {
            hub.emit.leaGlobalToReg(d, baseTok.substring(1));
        } else {
            hub.regForm.rfRegToReg(d, bReg);
        }
        boolean fitsDisp = X86Core.rfFitsImm32(extra);
        if (scale == 1 || scale == 2 || scale == 4 || scale == 8) {
            if (extra != 0 && fitsDisp) {
                hub.emit.raw(("    leaq " + extra + "(%" + d + ",%" + x + "," + scale + "), %" + d));
            } else {
                hub.emit.raw(("    leaq (%" + d + ",%" + x + "," + scale + "), %" + d));
                if (extra != 0) {
                    rfAddImm(d, extra);
                }
            }
        } else {
            if (!prescaled) {
                hub.emit.raw(("    imulq $" + scale + ", %" + x + ", %" + x));
            }
            hub.emit.raw(("    addq %" + x + ", %" + d));
            if (extra != 0) {
                rfAddImm(d, extra);
            }
        }
    }

    /** Scratch xmm registers for register-form float ops: never an argument register (SysV passes floats in xmm0-7; win64 in xmm0-3) and never a win64 non-volatile one. */
    String rfXmmA() {
        return hub.emit.isWinAbi() ? "xmm4" : "xmm14";
    }

    String rfXmmB() {
        return hub.emit.isWinAbi() ? "xmm5" : "xmm15";
    }

    void rfGprToXmm(String xmm, String gpr64, int n) {
        if (n == 4) {
            hub.emit.raw(("    movd %" + hub.emit.sizedReg(gpr64, 4) + ", %" + xmm));
        } else {
            hub.emit.raw(("    movq %" + gpr64 + ", %" + xmm));
        }
    }

    void rfXmmToGpr(String xmm, String gpr64, int n) {
        if (n == 4) {
            hub.emit.raw(("    movd %" + xmm + ", %" + hub.emit.sizedReg(gpr64, 4)));
        } else {
            hub.emit.raw(("    movq %" + xmm + ", %" + gpr64));
        }
    }

    /** a float operand (a temp, an immediate bit pattern or a frame slot of the op's own width) into an xmm register. */
    void rfLoadXmm(String tok, String xmm, int n) {
        if (X86Core.rfIsXvar(tok)) {
            rfMovaps(xmm, xvReg(tok));
        } else if (X86Core.rfIsTemp(tok)) {
            rfGprToXmm(xmm, hub.regForm.rfReg(tok), n);
        } else if (X86Core.rfIsImm(tok)) {
            long v = n == 4 ? (X86Core.rfImm(tok) & 0xFFFFFFFFL) : X86Core.rfImm(tok);
            if (v == 0) {
                hub.emit.raw("    xorps %" + xmm + ", %" + xmm);
            } else {
                hub.emit.raw("    mov" + (n == 4 ? "ss" : "sd") + " " + floatPoolMem(v, n) + ", %" + xmm);
            }
            
        } else {
            long off = X86Core.rfSlot(tok);
            String sfx = n == 4 ? "ss" : "sd";
            hub.emit.raw(("    mov" + sfx + " " + off + "(%rbp), %" + xmm));
        }
    }

    String yPhys(int k) {
        return "xmm" + ((hub.emit.isWinAbi() ? 12 : 4) + k);
    }

    String xvPhys(int k) {
        return "xmm" + ((hub.emit.isWinAbi() ? 6 : 8) + k);
    }

    String xvReg(String tok) {
        int k = Integer.parseInt(tok.substring(2));
        if (tok.startsWith("%z")) { // StaticFloatCachePass: xmm0-3, only inside call-free loops
            if (k < 0 || k >= 4) {
                throw new IllegalStateException("float cache register " + tok + " out of range");
            }
            return "xmm" + k;
        }
        if (tok.startsWith("%y")) {
            if (k < 0 || k >= X86Core.YT_COUNT) {
                throw new IllegalStateException("float temporary register " + tok + " out of range");
            }
            return yPhys(k);
        }
        if (k < 0 || k >= X86Core.XV_COUNT) {
            throw new IllegalStateException("float variable register " + tok + " out of range");
        }
        return xvPhys(k);
    }

    void xvSpillAll() {
        if (hub.emit.isWinAbi() || core.xvHome.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<Integer, Long> e : core.xvHome.entrySet()) {
            hub.emit.raw("    mov" + (core.xvWidth.getOrDefault(e.getKey(), 4) == 8 ? "sd" : "ss") + " %" + xvPhys(e.getKey()) + ", " + e.getValue() + "(%rbp)");
        }
    }

    void xvReloadAll() {
        if (hub.emit.isWinAbi() || core.xvHome.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<Integer, Long> e : core.xvHome.entrySet()) {
            hub.emit.raw("    mov" + (core.xvWidth.getOrDefault(e.getKey(), 4) == 8 ? "sd" : "ss") + " " + e.getValue() + "(%rbp), %" + xvPhys(e.getKey()));
        }
    }

    /** The pool label for a float constant of width n (4 or 8 bytes), creating the entry on first use. */
    String floatPoolLabel(long bits, int n) {
        long b = n == 4 ? (bits & 0xFFFFFFFFL) : bits;
        String key = n + ":" + b;
        String label = core.floatPool.get(key);
        if (label == null) {
            label = ".LFC" + core.floatPool.size();
            core.floatPool.put(key, label);
        }
        return label;
    }

    /** The memory operand text of a pooled float constant. */
    String floatPoolMem(long bits, int n) {
        return floatPoolLabel(bits, n) + "(%rip)";
    }

    /** Emits the pool after the last function: read-only, each entry aligned to its own size. */
    void emitFloatPool() {
        if (core.floatPool.isEmpty()) {
            return;
        }
        hub.emit.raw(core.target == CodegenTarget.WINDOWS_GNU_X64 ? ".section .rdata,\"dr\"" : ".section .rodata");
        for (Map.Entry<String, String> e : core.floatPool.entrySet()) {
            int colon = e.getKey().indexOf(':');
            int n = Integer.parseInt(e.getKey().substring(0, colon));
            long bits = Long.parseLong(e.getKey().substring(colon + 1));
            hub.emit.raw(n == 4 ? "    .p2align 2" : "    .p2align 3");
            hub.emit.raw(e.getValue() + ":");
            hub.emit.raw(n == 4 ? ("    .long " + bits) : ("    .quad " + bits));
        }
    }

    String xText(String xmm) {
        return ("%" + xmm);
    }

    void rfMovaps(String dst, String src) {
        if (!dst.equals(src)) {
            hub.emit.raw(("    movaps %" + src + ", %" + dst));
        }
    }

    /** memory operand text for a float at addr (a frame slot, a global or the pointer in a temp), n = 4 or 8 bytes wide. */
    String rfFloatMem(String addrTok, int n) {
        String ptr = n == 8 ? "qword ptr " : "dword ptr ";
        if (X86Core.rfIsSlot(addrTok)) {
            return (X86Core.rfSlot(addrTok) + "(%rbp)");
        } else if (X86Core.rfIsGlobal(addrTok)) {
            return hub.emit.globalRipOperand(addrTok.substring(1));
        }
        return ("(%" + hub.regForm.rfReg(addrTok) + ")");
    }

    /** xmm register = the float (n = 4: f32, 8: f64) at addr. */
    void rfLoadX(String xmm, String addrTok, int n) {
        String mem = rfFloatMem(addrTok, n);
        String mn = n == 8 ? "movsd" : "movss";
        hub.emit.raw(("    " + mn + " " + mem + ", %" + xmm));
    }

    /** the float in an xmm register stored at addr. */
    void rfStoreX(String addrTok, String xmm, int n) {
        String mem = rfFloatMem(addrTok, n);
        String mn = n == 8 ? "movsd" : "movss";
        hub.emit.raw(("    " + mn + " %" + xmm + ", " + mem));
    }

    /** "R_FBINX OP n %xK a b": variable K = a OP b (n is always 4). */
    /** For `a * b` where exactly one operand is the immediate 2.0 (f64 or f32 bits per n): the other operand token, else null. */
    String rfTimesTwoOperand(int n, String aTok, String bTok) {
        long two = n == 8 ? 0x4000000000000000L : 0x40000000L;
        if (X86Core.rfIsImm(aTok) && (n == 8 ? X86Core.rfImm(aTok) : (X86Core.rfImm(aTok) & 0xFFFFFFFFL)) == two && !X86Core.rfIsImm(bTok)) return bTok;
        if (X86Core.rfIsImm(bTok) && (n == 8 ? X86Core.rfImm(bTok) : (X86Core.rfImm(bTok) & 0xFFFFFFFFL)) == two && !X86Core.rfIsImm(aTok)) return aTok;
        return null;
    }

    void rfFloatX(String op, int n, String dstTok, String aTok, String bTok) {
        String xd = xvReg(dstTok);
        String mn;
        switch (op) {
            case "ADD": mn = "add"; break;
            case "SUB": mn = "sub"; break;
            case "MUL": mn = "mul"; break;
            case "DIV": mn = "div"; break;
            default: throw new IllegalStateException("unknown R_FBINX operator '" + op + "'");
        }
        String sfx = n == 4 ? "ss" : "sd";
        if (op.equals("MUL")) {
            // x * 2.0 is exactly x + x in IEEE-754 (zero sign, infinities, NaN included) and an add is shorter than a multiply on the usual critical chain
            String other = rfTimesTwoOperand(n, aTok, bTok);
            if (other != null) {
                if (X86Core.rfIsXvar(other)) {
                    rfMovaps(xd, xvReg(other));
                } else {
                    rfLoadXmm(other, xd, n);
                }
                hub.emit.raw(("    add" + sfx + " %" + xd + ", %" + xd));
                return;
            }
        }
        if (core.avx) {
            // VEX three-operand form: xd = a op b with no copy of a into the destination first (b may be the destination's own register)
            String ra;
            if (X86Core.rfIsXvar(aTok)) {
                ra = xvReg(aTok);
            } else {
                ra = (X86Core.rfIsXvar(bTok) && xvReg(bTok).equals(xd)) ? rfXmmA() : xd;
                rfLoadXmm(aTok, ra, n);
            }
            String vb;
            if (X86Core.rfIsXvar(bTok)) {
                vb = xText(xvReg(bTok));
            } else if (X86Core.rfIsSlot(bTok)) {
                vb = (X86Core.rfSlot(bTok) + "(%rbp)");
            } else if (X86Core.rfIsImm(bTok)) {
                vb = floatPoolMem(X86Core.rfImm(bTok), n);
            } else {
                rfLoadXmm(bTok, rfXmmB(), n);
                vb = xText(rfXmmB());
            }
            hub.emit.raw(("    v" + mn + sfx + " " + vb + ", %" + ra + ", %" + xd));
            return;
        }
        boolean commutative = op.equals("ADD") || op.equals("MUL");
        boolean bIsDst = X86Core.rfIsXvar(bTok) && xvReg(bTok).equals(xd);
        boolean aIsDst = X86Core.rfIsXvar(aTok) && xvReg(aTok).equals(xd);
        if (bIsDst && !aIsDst && commutative) {
            String t = aTok;
            aTok = bTok;
            bTok = t;
            aIsDst = true;
            bIsDst = false;
        }
        String work = bIsDst ? rfXmmA() : xd; // b lives in the destination register: compute elsewhere, then copy
        String bText;
        if (X86Core.rfIsXvar(bTok)) {
            bText = xText(xvReg(bTok));
        } else if (X86Core.rfIsSlot(bTok)) {
            long off = X86Core.rfSlot(bTok);
            bText = (off + "(%rbp)");
        } else if (X86Core.rfIsImm(bTok)) {
            bText = floatPoolMem(X86Core.rfImm(bTok), n);
        } else {
            rfLoadXmm(bTok, rfXmmB(), n);
            bText = xText(rfXmmB());
        }
        if (X86Core.rfIsXvar(aTok)) {
            rfMovaps(work, xvReg(aTok));
        } else {
            rfLoadXmm(aTok, work, n);
        }
        hub.emit.raw(("    " + mn + sfx + " " + bText + ", %" + work));
        rfMovaps(xd, work);
    }

    void rfFloat(boolean compare, String op, int n, String dstTok, String aTok, String bTok) {
        rfFloat(compare, op, n, dstTok, aTok, bTok, null);
    }

    /** branchLabel != null (compare only, dstTok null): jump to it when NOT (a op b) instead of producing the 0/1 value. */
    void rfFloat(boolean compare, String op, int n, String dstTok, String aTok, String bTok, String branchLabel) {
        String d = dstTok == null ? null : hub.regForm.rfReg(dstTok);
        String xa = rfXmmA();
        if (!compare && op.equals("MUL")) {
            String other = rfTimesTwoOperand(n, aTok, bTok);
            if (other != null) {
                rfLoadXmm(other, xa, n);
                hub.emit.raw("    add" + (n == 4 ? "ss" : "sd") + " %" + xa + ", %" + xa);
                rfXmmToGpr(xa, d, n);
                return;
            }
        }
        String xb = rfXmmB();
        String sfx = n == 4 ? "ss" : "sd";
        boolean aDirect = compare && X86Core.rfIsXvar(aTok); // a compare never modifies a: read the variable's register in place
        if (!aDirect) {
            rfLoadXmm(aTok, xa, n);
        } else {
            xa = xvReg(aTok);
        }
        String bText;
        if (X86Core.rfIsXvar(bTok)) {
            bText = xText(xvReg(bTok));
        } else if (X86Core.rfIsSlot(bTok)) {
            long off = X86Core.rfSlot(bTok);
            bText = (off + "(%rbp)");
        } else if (X86Core.rfIsImm(bTok)) {
            bText = floatPoolMem(X86Core.rfImm(bTok), n);
        } else {
            rfLoadXmm(bTok, xb, n);
            bText = ("%" + xb);
        }
        String aText = ("%" + xa);
        if (!compare) {
            String mn;
            switch (op) {
                case "ADD": mn = "add"; break;
                case "SUB": mn = "sub"; break;
                case "MUL": mn = "mul"; break;
                case "DIV": mn = "div"; break;
                default: throw new IllegalStateException("unknown R_FBIN operator '" + op + "'");
            }
            hub.emit.raw(("    " + mn + sfx + " " + bText + ", " + aText));
            rfXmmToGpr(xa, d, n);
            return;
        }
        hub.emit.raw(("    ucomi" + sfx + " " + bText + ", " + aText));
        if (branchLabel != null) {
            String jcc; // the jump taken when the comparison is false (same flags as the setcc below)
            switch (op) {
                case "EQ": jcc = "jne"; break;
                case "NEQ": jcc = "je"; break;
                case "LT": jcc = "jae"; break;
                case "LT_EQ": jcc = "ja"; break;
                case "GT_EQ": jcc = "jb"; break;
                case "GT": jcc = "jbe"; break;
                default: throw new IllegalStateException("unknown R_BRC float operator '" + op + "'");
            }
            hub.emit.raw("    " + jcc + " " + branchLabel);
            return;
        }
        String setcc;
        switch (op) {
            case "EQ": setcc = "sete"; break;
            case "NEQ": setcc = "setne"; break;
            case "LT": setcc = "setb"; break;
            case "LT_EQ": setcc = "setbe"; break;
            case "GT_EQ": setcc = "setae"; break;
            case "GT": setcc = "seta"; break;
            default: throw new IllegalStateException("unknown R_FCMP operator '" + op + "'");
        }
        rfSetcc(setcc, d);
    }

    void rfSetcc(String setcc, String d) {
        String d8 = hub.emit.sizedReg(d, 1);
        hub.emit.raw("    " + setcc + " %" + d8);
        hub.emit.raw("    movzbq %" + d8 + ", %" + d);
        
    }

    void rfRmw(String op, String slotTok, String srcTok) {
        if (X86Core.rfIsVar(slotTok)) {
            String vr = hub.regForm.rfReg(slotTok);
            switch (op) {
                case "INC":
                    hub.emit.raw(("    incq %" + vr));
                    return;
                case "DEC":
                    hub.emit.raw(("    decq %" + vr));
                    return;
                case "ADD":
                case "SUB": {
                    String mn = op.equals("ADD") ? "add" : "sub";
                    if (X86Core.rfIsSlot(srcTok)) {
                        hub.emit.movMemToReg(X86Core.RF_SCRATCH, X86Core.rfSlot(srcTok));
                        hub.regForm.rfOp2(mn, hub.regForm.rfRegText(vr), hub.regForm.rfRegText(X86Core.RF_SCRATCH));
                    } else {
                        hub.regForm.rfOp2(mn, hub.regForm.rfRegText(vr), hub.regForm.rfSrc(srcTok));
                    }
                    return;
                }
                default:
                    throw new IllegalStateException("unknown R_RMW operator '" + op + "'");
            }
        }
        String mem = hub.regForm.rfMemText(X86Core.rfSlot(slotTok));
        switch (op) {
            case "INC":
                hub.emit.raw(("    incq " + mem));
                return;
            case "DEC":
                hub.emit.raw(("    decq " + mem));
                return;
            case "ADD":
            case "SUB": {
                String mn = op.equals("ADD") ? "add" : "sub";
                if (X86Core.rfIsSlot(srcTok)) {
                    hub.emit.movMemToReg(X86Core.RF_SCRATCH, X86Core.rfSlot(srcTok));
                    hub.regForm.rfOp2(mn, mem, hub.regForm.rfRegText(X86Core.RF_SCRATCH));
                } else {
                    hub.regForm.rfOp2(mn, mem, hub.regForm.rfSrc(srcTok));
                }
                return;
            }
            default:
                throw new IllegalStateException("unknown R_RMW operator '" + op + "'");
        }
    }

    String mangleLabel(String text) {
        return text.replace("@", "L_");
    }

    void movRegToRegRbpFromRsp() {
        hub.emit.raw("    movq %rsp, %rbp");
        
    }

    /**
     * Expands every V1314 mark pair: a variable register is saved and restored around an instruction only when the instruction's own code
     * mentions that register (most scratch users touch none of them); pairs that need nothing disappear.
     */
    int varMarksNeeded(int start) {
        String[] regs = {"r13", "r14", "r12"};
        String sm = X86Core.V1314_SAVE + "\n";
        String rm = X86Core.V1314_REST + "\n";
        int mask = 0;
        int from = start;
        int i;
        while ((i = core.out.indexOf(sm, from)) >= 0) {
            int segStart = i + sm.length();
            int j = core.out.indexOf(rm, segStart);
            if (j < 0) {
                break;
            }
            String seg = core.out.substring(segStart, j);
            for (int vi = 0; vi < 3; vi++) {
                if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + regs[vi] + "[dwb]?(?![A-Za-z0-9_])").matcher(seg).find()) {
                    mask |= 1 << vi;
                }
            }
            from = j + rm.length();
        }
        return mask;
    }

    void expandVarMarks(int start) {
        String[] regs = {"r13", "r14", "r12"};
        String sm = X86Core.V1314_SAVE + "\n";
        String rm = X86Core.V1314_REST + "\n";
        int from = start;
        int i;
        while ((i = core.out.indexOf(sm, from)) >= 0) {
            int segStart = i + sm.length();
            int j = core.out.indexOf(rm, segStart);
            if (j < 0) {
                throw new RuntimeException("codegen internal error: unbalanced register-save marks in '" + core.currentFuncName + "'");
            }
            String seg = core.out.substring(segStart, j);
            StringBuilder sv = new StringBuilder();
            StringBuilder rs = new StringBuilder();
            for (int vi = 0; vi < 3; vi++) {
                if (core.vSlots[vi] != null && java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + regs[vi] + "[dwb]?(?![A-Za-z0-9_])").matcher(seg).find()) {
                    sv.append("    movq %").append(regs[vi]).append(", ").append(core.vSlots[vi]).append("\n");
                    rs.append("    movq ").append(core.vSlots[vi]).append(", %").append(regs[vi]).append("\n");
                }
            }
            core.out.replace(j, j + rm.length(), rs.toString());
            core.out.replace(i, i + sm.length(), sv.toString());
            from = i + sv.length();
        }
    }

    void finishCalleeSaved() {
        if (core.csrFuncStart < 0) {
            return;
        }
        long frameBytes = core.csrAllocSum;
        String body = core.out.substring(core.csrFuncStart);
        java.util.List<String[]> regs = new java.util.ArrayList<>(); // {name, kind gpr|xmm}
        java.util.regex.Pattern nb = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rbx|ebx|bx|bl|bh)(?![A-Za-z0-9_])");
        if (nb.matcher(body).find()) regs.add(new String[] {"rbx", "gpr"});
        for (int r = 12; r <= 15; r++) {
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])r" + r + "[dwb]?(?![A-Za-z0-9_])").matcher(body).find()) {
                regs.add(new String[] {"r" + r, "gpr"});
            }
        }
        if (hub.emit.isWinAbi()) {
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rdi|edi|di|dil)(?![A-Za-z0-9_])").matcher(body).find()) regs.add(new String[] {"rdi", "gpr"});
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rsi|esi|si|sil)(?![A-Za-z0-9_])").matcher(body).find()) regs.add(new String[] {"rsi", "gpr"});
            for (int x = 6; x <= 15; x++) {
                if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])xmm" + x + "(?![0-9A-Za-z_])").matcher(body).find()) {
                    regs.add(new String[] {"xmm" + x, "xmm"});
                }
            }
        }
        String restoreText = "";
        java.util.Arrays.fill(core.vSlots, null);
        if (!regs.isEmpty()) {
            if (core.csrAllocPos < 0) {
                // no locals at all (no ALLOC line): make the frame ourselves, right after the prologue
                core.csrAllocPos = core.csrFuncStart;
                core.csrAllocBytes = 0;
                core.csrAllocLine = "";
            }
            String beforeAlloc = core.out.substring(core.csrFuncStart, core.csrAllocPos);
            for (String[] r : regs) {
                java.util.regex.Pattern any = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + (r[1].equals("xmm") ? r[0] : (r[0].equals("rbx") ? "(?:rbx|ebx|bx|bl|bh)" : r[0].equals("rdi") ? "(?:rdi|edi|di|dil)" : r[0].equals("rsi") ? "(?:rsi|esi|si|sil)" : r[0] + "[dwb]?")) + "(?![0-9A-Za-z_])");
                if (any.matcher(beforeAlloc.replace(X86Core.CSR_MARK, "")).find()) {
                    throw new RuntimeException("codegen internal error: function '" + core.currentFuncName + "' uses " + r[0] + " before its frame is allocated");
                }
            }
            long cur = core.csrAllocBytes;
            StringBuilder saves = new StringBuilder();
            StringBuilder restores = new StringBuilder();
            for (String[] r : regs) {
                boolean x = r[1].equals("xmm");
                cur += x ? 16 : 8;
                String st, ld;
                String mem = "-" + cur + "(%rbp)";
                st = "    " + (x ? "movups" : "movq") + " %" + r[0] + ", " + mem;
                ld = "    " + (x ? "movups" : "movq") + " " + mem + ", %" + r[0];
                
                saves.append(st).append('\n');
                restores.append(ld).append('\n');
            }
            // r13/r14/r12 variables are saved to frame slots around the scratch users (see V1314_SAVE)
            java.util.Arrays.fill(core.vSlots, null);
            int needMask = varMarksNeeded(core.csrFuncStart);
            for (int vi = 0; vi < 3; vi++) {
                if ((core.rfVarMask & needMask & (1 << vi)) != 0) {
                    cur += 8;
                    core.vSlots[vi] = "-" + cur + "(%rbp)";
                }
            }
            long total = (cur + 15) & ~15L;
            frameBytes = core.csrAllocSum - core.csrAllocBytes + total;
            String newSub = ("    subq $" + total + ", %rsp\n");
            if (!core.out.substring(core.csrAllocPos, core.csrAllocPos + core.csrAllocLine.length()).equals(core.csrAllocLine)) {
                throw new RuntimeException("codegen internal error: ALLOC line moved in '" + core.currentFuncName + "'");
            }
            core.out.replace(core.csrAllocPos, core.csrAllocPos + core.csrAllocLine.length(), newSub + saves);
            restoreText = restores.toString();
        }
        expandVarMarks(core.csrFuncStart);
        int from = core.csrFuncStart;
        String marked = X86Core.CSR_MARK + "\n";
        int i;
        while ((i = core.out.indexOf(marked, from)) >= 0) {
            core.out.replace(i, i + marked.length(), restoreText);
            from = i + restoreText.length();
        }
        String rspMarked = X86Core.RSP_MARK + "\n";
        String rspFix = frameBytes == 0 ? ("    movq %rbp, %rsp\n")
                : (("    leaq -" + frameBytes + "(%rbp), %rsp\n"));
        from = core.csrFuncStart;
        while ((i = core.out.indexOf(rspMarked, from)) >= 0) {
            core.out.replace(i, i + rspMarked.length(), rspFix);
            from = i + rspFix.length();
        }
        core.csrFuncStart = -1;
    }

    void emitFunctionEpilogue() {
        hub.emit.raw(X86Core.CSR_MARK);
        hub.emit.raw("    movq %rbp, %rsp");
        
        hub.emit.popReg("rbp");
        hub.emit.raw("    ret");
    }
}

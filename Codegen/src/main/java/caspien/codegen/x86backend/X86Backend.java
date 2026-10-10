package caspien.codegen.x86backend;

import caspien.codegen.BytecodeToken;
import caspien.codegen.CodegenConfig;
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
 * `CodegenConfig.Target` (set from `codegen.config`); both emit GNU
 * assembler (GAS) AT&T syntax:
 * - LINUX_X64: SysV calling convention (rdi/rsi/rdx/rcx/r8/r9).
 * - WINDOWS_GNU_X64: win64 calling convention (rcx/rdx/r8/r9 + 32-byte
 *   shadow space), assembled and linked with mingw-w64 gcc.
 * Both are run in this project's test sandbox (Windows under Wine). There
 * is no MASM/Intel-syntax target: it was removed on purpose.
 *
 * Source layout: this class is the last of a chain of abstract classes in this package, because Java has no
 * partial classes and one file would be 7000+ lines: X86Core (fields, constants, static helpers) <- X86Emit
 * (emission helpers, construction runs, sized moves, block copies) <- X86Calls (argument registers, aligned
 * calls, `emitLine`/`emitLineInner`, the first mnemonic group) <- X86CasesA, X86CasesB (more mnemonic groups,
 * `emitCasesN`, each a `switch` that returns false for a mnemonic it does not own) <- X86RegForm (register-form
 * `R_*` helpers) <- X86Backend (the rest of the register form, callee-saved handling, `generate`). A part calls a
 * method of a later part through an abstract declaration at the top of its class. No file is over 1500 lines.
 */
public class X86Backend extends X86RegForm {
    public X86Backend(CodegenConfig.Target target) {
        this(target, false);
    }

    public X86Backend(CodegenConfig.Target target, boolean bmi2) {
        super(target, bmi2);
    }

    public String generate(List<List<BytecodeToken>> lines) {
        out.setLength(0);
        globalsSizeInit.clear();
        globalsHasInit.clear();
        stringLiterals.clear();
        floatPool.clear();
        globalAliasOffset.clear();
        aliasInits.clear();
        globalAliasParent.clear();
        collectDeclarations(lines);
        emitHeader();
        emitDataSections();
        raw(".text");
        allLines = lines;
        precomputeConstructionOffsets();
        callBufferStack.clear();
        argTrackDeltaStack.clear();
        argTrackSuspendDepth = 0;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            currentLineIndex = lineIndex;
            List<BytecodeToken> line = lines.get(lineIndex);
            emitLine(line);
        }
        emitFloatPool();
        emitFooter();
        return out.toString();
    }

    void rfBin(String op, int size, String dstTok, String aTok, String bTok) {
        if (op.equals("SHL") || op.equals("SHR") || op.equals("SAR")) {
            rfShift(op, size, dstTok, aTok, bTok);
            return;
        }
        if (op.equals("ROTL") || op.equals("ROTR")) {
            rfRotate(op, size, dstTok, aTok, bTok);
            return;
        }
        String d = rfReg(dstTok);
        boolean commutative = op.equals("ADD") || op.equals("MUL") || op.equals("AND") || op.equals("OR")
                || op.equals("BAND") || op.equals("BOR") || op.equals("BXOR") || op.equals("EQ") || op.equals("NEQ");
        if ((op.equals("ADD") || op.equals("MUL") || op.equals("BAND") || op.equals("BOR") || op.equals("BXOR")) && rfIsImm(aTok) && !rfIsImm(bTok)) {
            // a constant on the left of a commutative operation: swap, so the constant forms (shift / lea / add imm / sized imm) apply
            String sw = aTok;
            aTok = bTok;
            bTok = sw;
        }
        if (op.equals("MUL") && rfIsImm(bTok) && !rfIsImm(aTok) && rfMulByBigConst(d, aTok, rfImm(bTok))) {
            return;
        }
        if (op.equals("MUL") && size == 8 && rfIsImm(bTok) && !rfIsImm(aTok) && rfFitsImm32(rfImm(bTok)) && !mulByConstShape(rfImm(bTok))
                && !(rfIsTemp(aTok) && rfReg(aTok).equals(d)) && !(rfIsTemp(bTok) && rfReg(bTok).equals(d))) {
            // d = a * imm32 in one instruction (imul r64, r/m64, imm32) instead of `mov a, d; imul imm, d`
            raw("    imulq $" + rfImm(bTok) + ", " + (rfIsTemp(aTok) ? rfRegText(rfReg(aTok)) : rfMemText(rfSlot(aTok))) + ", " + rfRegText(d));
            return;
        }
        String bText = null;
        if (rfIsTemp(bTok) && rfReg(bTok).equals(d) && !(rfIsTemp(aTok) && rfReg(aTok).equals(d))) {
            // The destination register is b's own register: a plain "d = a; d op= b" would overwrite b first.
            if (commutative) {
                String t = aTok;
                aTok = bTok;
                bTok = t;
            } else {
                rfRegToReg(RF_SCRATCH, d);
                bText = rfRegText(RF_SCRATCH);
            }
        }
        boolean narrowCmp = size < 8 && !(op.equals("ADD") || op.equals("SUB") || op.equals("MUL") || op.equals("AND") || op.equals("OR")
                || op.equals("BAND") || op.equals("BOR") || op.equals("BXOR"));
        boolean signedCmp = narrowCmp && op.charAt(0) == 'S';
        if (narrowCmp) {
            // A narrow compare looks at the low `size` bytes only: constants are cut down at compile time,
            // registers extended (the stack form's zeroExtendReg/signExtendReg on both operands).
            if (rfIsImm(aTok)) {
                aTok = "#" + rfExtImm(rfImm(aTok), size, signedCmp);
            }
            if (rfIsImm(bTok)) {
                bTok = "#" + rfExtImm(rfImm(bTok), size, signedCmp);
            }
        }
        if (bText == null && op.equals("BAND") && size == 8 && rfIsImm(bTok) && rfIsTemp(aTok) && !rfReg(aTok).equals(d)
                && (rfImm(bTok) == 0xFFFFFFFFL || rfImm(bTok) == 0xFFFFL || rfImm(bTok) == 0xFFL)) {
            // x & 0xFF / 0xFFFF / 0xFFFFFFFF into another register: one zero-extending move instead of a copy and a mask
            long mk = rfImm(bTok);
            if (mk == 0xFFFFFFFFL) {
                raw("    movl %" + sizedReg(rfReg(aTok), 4) + ", %" + sizedReg(d, 4));
            } else {
                raw("    movz" + (mk == 0xFFL ? "b" : "w") + "l %" + sizedReg(rfReg(aTok), mk == 0xFFL ? 1 : 2) + ", %" + sizedReg(d, 4));
            }
            return;
        }
        if (!(rfIsTemp(aTok) && rfReg(aTok).equals(d))) {
            rfMov(dstTok, aTok);
        }
        if (bText == null && op.equals("BAND") && size == 8 && rfIsImm(bTok) && rfImm(bTok) == 0xFFFFFFFFL) {
            // x & 0xFFFFFFFF: a 32-bit self-move zero-extends (no 10-byte constant through the scratch register)
            raw("    movl %" + sizedReg(d, 4) + ", %" + sizedReg(d, 4));
            return;
        }
        if (bText == null) {
            bText = rfSrc(bTok);
        }
        if (narrowCmp) {
            rfExtend(d, size, signedCmp);
            if (bText.equals(rfRegText(RF_SCRATCH))) {
                rfExtend(RF_SCRATCH, size, signedCmp);
            } else if (rfIsTemp(bTok) && !rfReg(bTok).equals(d)) {
                rfExtend(rfReg(bTok), size, signedCmp);
            }
        }
        String dText = rfRegText(d);
        switch (op) {
            case "ADD":
                rfOp2("add", dText, bText);
                return;
            case "SUB":
                rfOp2("sub", dText, bText);
                return;
            case "MUL":
                if (bText.startsWith("$") && rfMulByConst(d, Long.parseLong(bText.substring(1)))) {
                    return;
                }
                rfOp2("imul", dText, bText);
                return;
            case "BAND":
            case "BOR":
            case "BXOR":
                // bitwise and / or / xor (BAND is also x % 2^n); the mask is a small immediate or a register. A narrow result is
                // cut back to the operand width, so the temp holds the zero-extended value (the entry is marked zx by the pass).
                rfOp2(op.equals("BAND") ? "and" : op.equals("BOR") ? "or" : "xor", dText, bText);
                zeroExtendReg(d, size);
                return;
            case "AND":
            case "OR": {
                String o = op.equals("AND") ? "and" : "or";
                if (size == 1) {
                    // Booleans: both operands are register-held 0/1 results; byte-wide, like the stack form.
                    String bReg = rfIsTemp(bTok) ? rfReg(bTok) : d;
                    raw("    " + o + "b %" + sizedReg(bReg, 1) + ", %" + sizedReg(d, 1));
                    
                } else {
                    rfOp2(o, dText, bText);
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
        rfOp2("cmp", dText, bText);
        String d8 = sizedReg(d, 1);
        raw("    " + setcc + " %" + d8);
        raw("    movzbq %" + d8 + ", %" + d);
        
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
        if (rfIsTemp(aTok) && rfReg(aTok).equals(d)) {
            raw("    movq %" + d + ", %" + RF_SCRATCH);
            raw("    shlq $" + sh + ", %" + d);
            raw("    " + mn + " %" + RF_SCRATCH + ", %" + d);
        } else if (rfIsTemp(aTok)) {
            rfRegToReg(d, rfReg(aTok));
            raw("    shlq $" + sh + ", %" + d);
            raw("    " + mn + " %" + rfReg(aTok) + ", %" + d);
        } else {
            movMemToReg(d, rfSlot(aTok));
            raw("    shlq $" + sh + ", %" + d);
            raw("    " + mn + " " + rfMemText(rfSlot(aTok)) + ", %" + d);
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
            raw("    shlq $" + Long.numberOfTrailingZeros(k) + ", " + r);
            return true;
        }
        if (k == 3 || k == 5 || k == 9) {
            raw("    leaq (" + r + "," + r + "," + (k - 1) + "), " + r);
            return true;
        }
        boolean plus = Long.bitCount(k - 1) == 1;
        if (!plus && Long.bitCount(k + 1) != 1) {
            return false;
        }
        int sh = Long.numberOfTrailingZeros(plus ? k - 1 : k + 1);
        raw("    movq " + r + ", %" + RF_SCRATCH);
        raw("    shlq $" + sh + ", " + r);
        raw("    " + (plus ? "addq" : "subq") + " %" + RF_SCRATCH + ", " + r);
        return true;
    }

    /**
     * R_BRCM: jump to `label` when NOT (a op [base]), where [base] is the 8-byte word at the address held in the register `base`
     * (the safe dynarray length word: `cmpq (%base), %a`). `a` is a %t/%v register or a frame slot.
     */
    void rfBrcMem(String op, String aTok, String baseTok, String label) {
        String cc = rfFalseJump(op);
        String aText;
        if (rfIsSlot(aTok)) {
            rfMov("%s", aTok); // cmp takes one memory operand: a frame-slot index goes through the scratch register
            aText = rfRegText(RF_SCRATCH);
        } else {
            aText = rfRegText(rfReg(aTok));
        }
        String bText = "(" + rfRegText(rfReg(baseTok)) + ")";
        rfOp2("cmp", aText, bText);
        raw("    " + cc + " " + label);
    }

    /**
     * R_BRC of a 1, 2 or 4 byte compare: one sized cmp (cmpb/cmpw/cmpl) of the low bytes of two registers or a register and an immediate,
     * then the jump taken when the comparison is false. Unsigned operators use the unsigned conditions and S* the signed ones, which on a
     * sized cmp look at exactly the low `size` bytes (what the unfused R_BIN did by extending both operands first). Frame-slot operands are
     * never fused (BranchFusionPass).
     */
    String rfBrcNarrow(String op, int size, String aTok, String bTok, String label) {
        String cc = rfFalseJump(op);
        if (rfIsImm(aTok)) {
            String t = aTok;
            aTok = bTok;
            bTok = t;
            cc = rfMirrorJump(cc);
        }
        if (!rfIsTemp(aTok) || !(rfIsTemp(bTok) || rfIsImm(bTok))) {
            throw new IllegalStateException("narrow R_BRC needs register or immediate operands: " + aTok + " " + bTok);
        }
        String suffix = size == 1 ? "b" : size == 2 ? "w" : "l";
        String aText = "%" + sizedReg(rfReg(aTok), size);
        String bText = rfIsImm(bTok) ? "$" + rfTrunc(rfImm(bTok), size) : "%" + sizedReg(rfReg(bTok), size);
        raw("    cmp" + suffix + " " + bText + ", " + aText);
        if (label != null) {
            raw("    " + cc + " " + label);
        }
        return cc;
    }

    String rfBrc(String op, String aTok, String bTok, String label) {
        String cc = rfFalseJump(op); // the jump taken when the comparison is false
        if (rfIsImm(aTok)) {
            // "cmp imm, x" does not exist: swap the operands and mirror the condition.
            String t = aTok;
            aTok = bTok;
            bTok = t;
            cc = rfMirrorJump(cc);
        }
        String aText;
        if (rfIsTemp(aTok)) {
            aText = rfRegText(rfReg(aTok));
        } else if (rfIsSlot(aTok) && !rfIsSlot(bTok)) {
            aText = rfMemText(rfSlot(aTok));
        } else {
            // both operands are slots (cmp takes at most one memory operand): a goes through the scratch register
            rfMov("%s", aTok);
            aText = rfRegText(RF_SCRATCH);
        }
        String bText = rfSrc(bTok); // a huge immediate goes through the scratch register (a is then never in it: a slot stays in memory)
        rfOp2("cmp", aText, bText);
        if (label != null) {
            raw("    " + cc + " " + label);
        }
        return cc;
    }

    /** `mov imm, reg` that leaves the flags alone (never the xor idiom) */
    void rfMovImmKeepFlags(String reg, long v) {
        if (v >= 0 && v <= 0xFFFFFFFFL) {
            raw("    movl $" + v + ", %" + sizedReg(reg, 4));
        } else if (rfFitsImm32(v)) {
            raw("    movq $" + v + ", %" + reg);
        } else {
            raw("    movabsq $" + v + ", %" + reg);
        }
    }

    /**
     * "R_CMOV OP n a b %vK X [Y]" -- the select made by the LowerOrderGenerator's ConditionalMovePass: %vK = (a OP b) ? X : Y (Y absent = %vK keeps its
     * value). X and Y are a register or an immediate (already cut to the variable's width). One compare, then `mov Y` (flags untouched) and `cmovCC X`.
     */
    void rfCmov(List<BytecodeToken> line) {
        String op = line.get(1).text;
        int size = (int) Long.parseLong(line.get(2).text);
        String a = line.get(3).text, b = line.get(4).text, v = line.get(5).text, x = line.get(6).text;
        String y = line.size() > 7 ? line.get(7).text : null;
        String dst = rfReg(v);
        String cc = size < 8 ? rfBrcNarrow(op, size, a, b, null) : rfBrc(op, a, b, null);
        if (y != null && !y.equals(v)) {
            if (rfIsImm(y)) {
                rfMovImmKeepFlags(dst, rfImm(y));
            } else {
                rfRegToReg(dst, rfReg(y));
            }
        }
        String xr;
        if (rfIsImm(x)) {
            rfMovImmKeepFlags(RF_SCRATCH, rfImm(x));
            xr = RF_SCRATCH;
        } else {
            xr = rfReg(x);
        }
        raw("    cmov" + rfTrueSuffix(cc) + "q %" + xr + ", %" + dst);
    }

    void rfExtend(String reg, int size, boolean signed) {
        if (signed) {
            signExtendReg(reg, size);
        } else {
            zeroExtendReg(reg, size);
        }
    }

    void rfUn(String op, int size, String dstTok, String aTok) {
        String d = rfReg(dstTok);
        if (!(rfIsTemp(aTok) && rfReg(aTok).equals(d))) {
            rfMov(dstTok, aTok);
        }
        switch (op) {
            case "INC":
                raw(("    incq %" + d));
                return;
            case "DEC":
                raw(("    decq %" + d));
                return;
            case "NEG":
                raw(("    negq %" + d));
                return;
            case "NOT":
                raw(("    xorb $1, %" + sizedReg(d, 1)));
                return;
            case "BNOT":
                // bitwise complement cut back to the operand width (u8: ~0 = 255)
                raw(("    notq %" + d));
                zeroExtendReg(d, size);
                return;
            default:
                throw new IllegalStateException("unknown R_UN operator '" + op + "'");
        }
    }

    /** address of a frame slot ("$off") or a global ("&sym") into reg. */
    void rfAddrToReg(String reg, String tok) {
        if (rfIsGlobal(tok)) {
            leaGlobalToReg(reg, tok.substring(1));
        } else {
            leaMemToReg(reg, rfSlot(tok));
        }
    }

    /** An integer argument register; an index beyond the register-passed ones would fall back to rax (a temp), which the pass never produces. */
    String rfArgReg(int idx) {
        String reg = argReg(idx);
        if (reg.equals("rax")) {
            throw new CodegenException("codegen", "<register-form>", 0, "R_ARG index " + idx + " is beyond the register-passed arguments");
        }
        return reg;
    }

    void rfLoad(int n, String dstTok, String srcTok) {
        String d = rfReg(dstTok);
        if (rfIsSlot(srcTok)) {
            loadSizedFromFrame(d, rfSlot(srcTok), n);
        } else if (rfIsGlobal(srcTok)) {
            // one instruction, `mov sym+off(%rip), %d`, not `leaq sym(%rip), %d; mov (%d), %d`
            rfMemOverride = globalRipOperand(srcTok.substring(1));
            try {
                loadSizedFromAddr(d, d, n);
            } finally {
                rfMemOverride = null;
            }
        } else {
            loadSizedFromAddr(d, rfReg(srcTok), n);
        }
    }

    /** "(disp)(%base)" memory text for a register base plus a constant displacement */
    String rfDispMem(String baseTok, long disp) {
        return (disp != 0 ? String.valueOf(disp) : "") + "(%" + rfReg(baseTok) + ")";
    }

    /** %tD = n bytes (1, 2, 4 or 8) at base + disp, zero-extended */
    void rfLoadDisp(int n, String dstTok, String baseTok, long disp) {
        String d = rfReg(dstTok);
        String prev = rfMemOverride;
        rfMemOverride = rfDispMem(baseTok, disp);
        try {
            loadSizedFromAddr(d, d, n);
        } finally {
            rfMemOverride = prev;
        }
    }

    /** n bytes (1, 2, 4 or 8) at base + disp = src (#imm or a register) */
    void rfStoreDisp(int n, String baseTok, long disp, String srcTok) {
        String mem = rfDispMem(baseTok, disp);
        if (rfIsImm(srcTok)) {
            raw("    mov" + movSuffix(n) + " $" + rfTrunc(rfImm(srcTok), n) + ", " + mem);
            return;
        }
        raw("    mov" + movSuffix(n) + " %" + sizedReg(rfReg(srcTok), n) + ", " + mem);
    }

    void rfLoadXDisp(String xmm, String baseTok, long disp, int n) {
        raw("    " + (n == 8 ? "movsd" : "movss") + " " + rfDispMem(baseTok, disp) + ", %" + xmm);
    }

    void rfStoreXDisp(String baseTok, long disp, String xmm, int n) {
        raw("    " + (n == 8 ? "movsd" : "movss") + " %" + xmm + ", " + rfDispMem(baseTok, disp));
    }

    void rfStore(int n, String addrTok, String srcTok) {
        String areg = null; // register holding the address; null = a frame slot
        String ripMem = null; // a global stored with one instruction, `mov %r, sym+off(%rip)`
        if (rfIsGlobal(addrTok) && !isOddSize(n)) {
            ripMem = globalRipOperand(addrTok.substring(1));
        } else if (rfIsGlobal(addrTok)) {
            leaGlobalToReg(RF_SCRATCH, addrTok.substring(1));
            areg = RF_SCRATCH;
        } else if (!rfIsSlot(addrTok)) {
            areg = rfReg(addrTok);
        }
        if (rfIsImm(srcTok)) {
            long v = rfTrunc(rfImm(srcTok), n);
            String mem;
            mem = ripMem != null ? ripMem : areg == null ? (rfSlot(addrTok) + "(%rbp)") : ("(%" + areg + ")");
            raw("    mov" + movSuffix(n) + " $" + v + ", " + mem);
            
            return;
        }
        String reg = rfReg(srcTok);
        if (ripMem != null) {
            raw("    mov" + movSuffix(n) + " %" + sizedReg(reg, n) + ", " + ripMem);
        } else if (areg == null) {
            storeSizedToFrame(reg, rfSlot(addrTok), n);
        } else {
            storeSizedToAddr(reg, areg, n);
        }
    }

    String rfMemOperand(String baseReg) {
        if (rfMemOverride != null) {
            return rfMemOverride;
        }
        if (rfIdxReg == null) {
            return ("(%" + baseReg + ")");
        }
        return (rfIdxDisp != 0 ? String.valueOf(rfIdxDisp) : "") + "(%" + baseReg + ",%" + rfIdxReg + "," + rfIdxScale + ")";
    }

    /**
     * Base of an indexed access (R_LDI/R_STI/R_LDXI/R_STXI): a global is loaded into `scratch`, a frame slot is addressed
     * off rbp with a displacement, a temp or variable register is used as it is. Sets rfIdxDisp; the caller resets it.
     */
    String rfIndexedBase(String baseTok, String scratch) {
        rfIdxDisp = 0;
        if (rfIsGlobal(baseTok)) {
            leaGlobalToReg(scratch, baseTok.substring(1));
            return scratch;
        }
        if (rfIsSlot(baseTok)) {
            rfIdxDisp = rfSlot(baseTok);
            return "rbp";
        }
        if (rfIsTemp(baseTok)) {
            return rfReg(baseTok);
        }
        throw new IllegalStateException("malformed indexed base " + baseTok);
    }

    /**
     * Index register + scale of an indexed access. A hardware scale (1/2/4/8) is used as it is; any other stride (a struct element: 24, 56 ...)
     * is multiplied into the scratch register first (`imulq $scale, %idx, %r15`, the variable is not touched) and the operand becomes
     * `disp(%base,%r15,1)`. The base of such an access must not be a global (a global base address already needs the scratch register).
     */
    void rfSetIndex(String baseTok, String idxTok, long scale) {
        if (rfHwScale(scale)) {
            rfIdxReg = rfReg(idxTok);
            rfIdxScale = scale;
            return;
        }
        if (rfIsGlobal(baseTok) || scale <= 0 || !rfFitsImm32(scale)) {
            throw new IllegalStateException("malformed indexed access (stride " + scale + ", base " + baseTok + ")");
        }
        String ir = rfReg(idxTok);
        if (strideKeySink == null || strideKeySink != currentSink() || strideKeyScale != scale || !ir.equals(strideKeyIdx)) {
            raw("    imulq $" + scale + ", %" + ir + ", %" + RF_SCRATCH);
            strideKeySink = currentSink();
            strideKeyIdx = ir;
            strideKeyScale = scale;
        } else {
            strideCseHits++;
        }
        rfIdxReg = RF_SCRATCH;
        rfIdxScale = 1;
    }

    void rfLoadIndexed(int n, String dstTok, String baseTok, String idxTok, long scale, long disp) {
        if (!(n == 1 || n == 2 || n == 4 || n == 8) || !rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_LDI");
        }
        String d = rfReg(dstTok);
        String b = rfIndexedBase(baseTok, d);
        rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            loadSizedFromAddr(d, b, n);
        } finally {
            rfIdxReg = null;
            rfIdxDisp = 0;
        }
    }

    void rfStoreIndexed(int n, String baseTok, String idxTok, long scale, String srcTok, long disp) {
        if (!(n == 1 || n == 2 || n == 4 || n == 8) || !rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_STI");
        }
        String b = rfIndexedBase(baseTok, RF_SCRATCH);
        rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            if (rfIsImm(srcTok)) {
                long v = rfTrunc(rfImm(srcTok), n);
                raw("    mov" + movSuffix(n) + " $" + v + ", " + rfMemOperand(b));
                
            } else {
                storeSizedToAddr(rfReg(srcTok), b, n);
            }
        } finally {
            rfIdxReg = null;
            rfIdxDisp = 0;
        }
    }

    /** xmm register = the float at global + index*scale (R_LDXI): the base address goes through the scratch register. */
    void rfLoadXIndexed(int n, String xTok, String baseTok, String idxTok, long scale, long disp) {
        if (!(n == 4 || n == 8) || (rfHwScale(scale) && scale != n) || !rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_LDXI");
        }
        String b = rfIndexedBase(baseTok, RF_SCRATCH);
        rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            String mem = (rfMemOperand(b));
            String mn = n == 8 ? "movsd" : "movss";
            String xmm = xvReg(xTok);
            raw(("    " + mn + " " + mem + ", %" + xmm));
        } finally {
            rfIdxReg = null;
            rfIdxDisp = 0;
        }
    }

    /** the float in an xmm register stored at base + index*scale (R_STXI). */
    void rfStoreXIndexed(int n, String baseTok, String idxTok, long scale, String xTok, long disp) {
        if (!(n == 4 || n == 8) || (rfHwScale(scale) && scale != n) || !rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_STXI");
        }
        String b = rfIndexedBase(baseTok, RF_SCRATCH);
        rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            String mem = (rfMemOperand(b));
            String mn = n == 8 ? "movsd" : "movss";
            String xmm = xvReg(xTok);
            raw(("    " + mn + " %" + xmm + ", " + mem));
        } finally {
            rfIdxReg = null;
            rfIdxDisp = 0;
        }
    }

    void rfAddImm(String reg, long v) {
        if (v == 0) {
            return;
        }
        if (rfFitsImm32(v)) {
            raw(("    addq $" + v + ", %" + reg));
        } else {
            movImmToReg(RF_SCRATCH, v);
            raw(("    addq %" + RF_SCRATCH + ", %" + reg));
        }
    }

    void rfLea(String dstTok, String baseTok, String idxTok, long scale, long extra) {
        String d = rfReg(dstTok);
        boolean baseIsT = rfIsTemp(baseTok);
        String bReg = baseIsT ? rfReg(baseTok) : null;
        if (rfIsImm(idxTok)) {
            long disp = rfImm(idxTok) * scale + extra;
            if (rfIsSlot(baseTok)) {
                long total = rfSlot(baseTok) + disp;
                if (rfFitsImm32(total)) {
                    leaMemToReg(d, total);
                } else {
                    leaMemToReg(d, rfSlot(baseTok));
                    rfAddImm(d, disp);
                }
            } else if (rfIsGlobal(baseTok)) {
                leaGlobalToReg(d, baseTok.substring(1));
                rfAddImm(d, disp);
            } else {
                rfRegToReg(d, bReg);
                rfAddImm(d, disp);
            }
            return;
        }
        String x;
        boolean prescaled = false;
        if (rfIsTemp(idxTok)) {
            x = rfReg(idxTok);
            if (!(scale == 1 || scale == 2 || scale == 4 || scale == 8) && rfFitsImm32(scale)) {
                // scale * idx in one 3-operand imul into the scratch register (no copy first, never in place on a variable)
                raw(("    imulq $" + scale + ", %" + x + ", %" + RF_SCRATCH));
                x = RF_SCRATCH;
                prescaled = true;
            } else if (rfIsVar(idxTok) && !(scale == 1 || scale == 2 || scale == 4 || scale == 8)) {
                rfRegToReg(RF_SCRATCH, x); // the multiply below works in place; never on a variable
                x = RF_SCRATCH;
            }
            if (x.equals(d) && !(baseIsT && bReg.equals(d))) {
                rfRegToReg(RF_SCRATCH, x); // the index lives in the destination register: keep it before the base overwrites it
                x = RF_SCRATCH;
            }
        } else {
            movMemToReg(RF_SCRATCH, rfSlot(idxTok));
            x = RF_SCRATCH;
        }
        if (rfIsSlot(baseTok)) {
            leaMemToReg(d, rfSlot(baseTok));
        } else if (rfIsGlobal(baseTok)) {
            leaGlobalToReg(d, baseTok.substring(1));
        } else {
            rfRegToReg(d, bReg);
        }
        boolean fitsDisp = rfFitsImm32(extra);
        if (scale == 1 || scale == 2 || scale == 4 || scale == 8) {
            if (extra != 0 && fitsDisp) {
                raw(("    leaq " + extra + "(%" + d + ",%" + x + "," + scale + "), %" + d));
            } else {
                raw(("    leaq (%" + d + ",%" + x + "," + scale + "), %" + d));
                if (extra != 0) {
                    rfAddImm(d, extra);
                }
            }
        } else {
            if (!prescaled) {
                raw(("    imulq $" + scale + ", %" + x + ", %" + x));
            }
            raw(("    addq %" + x + ", %" + d));
            if (extra != 0) {
                rfAddImm(d, extra);
            }
        }
    }

    /** Scratch xmm registers for register-form float ops: never an argument register (SysV passes floats in xmm0-7; win64 in xmm0-3) and never a win64 non-volatile one. */
    String rfXmmA() {
        return isWinAbi() ? "xmm4" : "xmm14";
    }

    String rfXmmB() {
        return isWinAbi() ? "xmm5" : "xmm15";
    }

    void rfGprToXmm(String xmm, String gpr64, int n) {
        if (n == 4) {
            raw(("    movd %" + sizedReg(gpr64, 4) + ", %" + xmm));
        } else {
            raw(("    movq %" + gpr64 + ", %" + xmm));
        }
    }

    void rfXmmToGpr(String xmm, String gpr64, int n) {
        if (n == 4) {
            raw(("    movd %" + xmm + ", %" + sizedReg(gpr64, 4)));
        } else {
            raw(("    movq %" + xmm + ", %" + gpr64));
        }
    }

    /** a float operand (a temp, an immediate bit pattern or a frame slot of the op's own width) into an xmm register. */
    void rfLoadXmm(String tok, String xmm, int n) {
        if (rfIsXvar(tok)) {
            rfMovaps(xmm, xvReg(tok));
        } else if (rfIsTemp(tok)) {
            rfGprToXmm(xmm, rfReg(tok), n);
        } else if (rfIsImm(tok)) {
            long v = n == 4 ? (rfImm(tok) & 0xFFFFFFFFL) : rfImm(tok);
            if (v == 0) {
                raw("    xorps %" + xmm + ", %" + xmm);
            } else {
                raw("    mov" + (n == 4 ? "ss" : "sd") + " " + floatPoolMem(v, n) + ", %" + xmm);
            }
            
        } else {
            long off = rfSlot(tok);
            String sfx = n == 4 ? "ss" : "sd";
            raw(("    mov" + sfx + " " + off + "(%rbp), %" + xmm));
        }
    }

    String yPhys(int k) {
        return "xmm" + ((isWinAbi() ? 12 : 4) + k);
    }

    String xvPhys(int k) {
        return "xmm" + ((isWinAbi() ? 6 : 8) + k);
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
            if (k < 0 || k >= YT_COUNT) {
                throw new IllegalStateException("float temporary register " + tok + " out of range");
            }
            return yPhys(k);
        }
        if (k < 0 || k >= XV_COUNT) {
            throw new IllegalStateException("float variable register " + tok + " out of range");
        }
        return xvPhys(k);
    }

    void xvSpillAll() {
        if (isWinAbi() || xvHome.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<Integer, Long> e : xvHome.entrySet()) {
            raw("    mov" + (xvWidth.getOrDefault(e.getKey(), 4) == 8 ? "sd" : "ss") + " %" + xvPhys(e.getKey()) + ", " + e.getValue() + "(%rbp)");
        }
    }

    void xvReloadAll() {
        if (isWinAbi() || xvHome.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<Integer, Long> e : xvHome.entrySet()) {
            raw("    mov" + (xvWidth.getOrDefault(e.getKey(), 4) == 8 ? "sd" : "ss") + " " + e.getValue() + "(%rbp), %" + xvPhys(e.getKey()));
        }
    }

    /** The pool label for a float constant of width n (4 or 8 bytes), creating the entry on first use. */
    String floatPoolLabel(long bits, int n) {
        long b = n == 4 ? (bits & 0xFFFFFFFFL) : bits;
        String key = n + ":" + b;
        String label = floatPool.get(key);
        if (label == null) {
            label = ".LFC" + floatPool.size();
            floatPool.put(key, label);
        }
        return label;
    }

    /** The memory operand text of a pooled float constant. */
    String floatPoolMem(long bits, int n) {
        return floatPoolLabel(bits, n) + "(%rip)";
    }

    /** Emits the pool after the last function: read-only, each entry aligned to its own size. */
    void emitFloatPool() {
        if (floatPool.isEmpty()) {
            return;
        }
        raw(target == CodegenConfig.Target.WINDOWS_GNU_X64 ? ".section .rdata,\"dr\"" : ".section .rodata");
        for (Map.Entry<String, String> e : floatPool.entrySet()) {
            int colon = e.getKey().indexOf(':');
            int n = Integer.parseInt(e.getKey().substring(0, colon));
            long bits = Long.parseLong(e.getKey().substring(colon + 1));
            raw(n == 4 ? "    .p2align 2" : "    .p2align 3");
            raw(e.getValue() + ":");
            raw(n == 4 ? ("    .long " + bits) : ("    .quad " + bits));
        }
    }

    String xText(String xmm) {
        return ("%" + xmm);
    }

    void rfMovaps(String dst, String src) {
        if (!dst.equals(src)) {
            raw(("    movaps %" + src + ", %" + dst));
        }
    }

    /** memory operand text for a float at addr (a frame slot, a global or the pointer in a temp), n = 4 or 8 bytes wide. */
    String rfFloatMem(String addrTok, int n) {
        String ptr = n == 8 ? "qword ptr " : "dword ptr ";
        if (rfIsSlot(addrTok)) {
            return (rfSlot(addrTok) + "(%rbp)");
        } else if (rfIsGlobal(addrTok)) {
            return globalRipOperand(addrTok.substring(1));
        }
        return ("(%" + rfReg(addrTok) + ")");
    }

    /** xmm register = the float (n = 4: f32, 8: f64) at addr. */
    void rfLoadX(String xmm, String addrTok, int n) {
        String mem = rfFloatMem(addrTok, n);
        String mn = n == 8 ? "movsd" : "movss";
        raw(("    " + mn + " " + mem + ", %" + xmm));
    }

    /** the float in an xmm register stored at addr. */
    void rfStoreX(String addrTok, String xmm, int n) {
        String mem = rfFloatMem(addrTok, n);
        String mn = n == 8 ? "movsd" : "movss";
        raw(("    " + mn + " %" + xmm + ", " + mem));
    }

    /** "R_FBINX OP n %xK a b": variable K = a OP b (n is always 4). */
    /** For `a * b` where exactly one operand is the immediate 2.0 (f64 or f32 bits per n): the other operand token, else null. */
    String rfTimesTwoOperand(int n, String aTok, String bTok) {
        long two = n == 8 ? 0x4000000000000000L : 0x40000000L;
        if (rfIsImm(aTok) && (n == 8 ? rfImm(aTok) : (rfImm(aTok) & 0xFFFFFFFFL)) == two && !rfIsImm(bTok)) return bTok;
        if (rfIsImm(bTok) && (n == 8 ? rfImm(bTok) : (rfImm(bTok) & 0xFFFFFFFFL)) == two && !rfIsImm(aTok)) return aTok;
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
                if (rfIsXvar(other)) {
                    rfMovaps(xd, xvReg(other));
                } else {
                    rfLoadXmm(other, xd, n);
                }
                raw(("    add" + sfx + " %" + xd + ", %" + xd));
                return;
            }
        }
        if (avx) {
            // VEX three-operand form: xd = a op b with no copy of a into the destination first (b may be the destination's own register)
            String ra;
            if (rfIsXvar(aTok)) {
                ra = xvReg(aTok);
            } else {
                ra = (rfIsXvar(bTok) && xvReg(bTok).equals(xd)) ? rfXmmA() : xd;
                rfLoadXmm(aTok, ra, n);
            }
            String vb;
            if (rfIsXvar(bTok)) {
                vb = xText(xvReg(bTok));
            } else if (rfIsSlot(bTok)) {
                vb = (rfSlot(bTok) + "(%rbp)");
            } else if (rfIsImm(bTok)) {
                vb = floatPoolMem(rfImm(bTok), n);
            } else {
                rfLoadXmm(bTok, rfXmmB(), n);
                vb = xText(rfXmmB());
            }
            raw(("    v" + mn + sfx + " " + vb + ", %" + ra + ", %" + xd));
            return;
        }
        boolean commutative = op.equals("ADD") || op.equals("MUL");
        boolean bIsDst = rfIsXvar(bTok) && xvReg(bTok).equals(xd);
        boolean aIsDst = rfIsXvar(aTok) && xvReg(aTok).equals(xd);
        if (bIsDst && !aIsDst && commutative) {
            String t = aTok;
            aTok = bTok;
            bTok = t;
            aIsDst = true;
            bIsDst = false;
        }
        String work = bIsDst ? rfXmmA() : xd; // b lives in the destination register: compute elsewhere, then copy
        String bText;
        if (rfIsXvar(bTok)) {
            bText = xText(xvReg(bTok));
        } else if (rfIsSlot(bTok)) {
            long off = rfSlot(bTok);
            bText = (off + "(%rbp)");
        } else if (rfIsImm(bTok)) {
            bText = floatPoolMem(rfImm(bTok), n);
        } else {
            rfLoadXmm(bTok, rfXmmB(), n);
            bText = xText(rfXmmB());
        }
        if (rfIsXvar(aTok)) {
            rfMovaps(work, xvReg(aTok));
        } else {
            rfLoadXmm(aTok, work, n);
        }
        raw(("    " + mn + sfx + " " + bText + ", %" + work));
        rfMovaps(xd, work);
    }

    void rfFloat(boolean compare, String op, int n, String dstTok, String aTok, String bTok) {
        rfFloat(compare, op, n, dstTok, aTok, bTok, null);
    }

    /** branchLabel != null (compare only, dstTok null): jump to it when NOT (a op b) instead of producing the 0/1 value. */
    void rfFloat(boolean compare, String op, int n, String dstTok, String aTok, String bTok, String branchLabel) {
        String d = dstTok == null ? null : rfReg(dstTok);
        String xa = rfXmmA();
        if (!compare && op.equals("MUL")) {
            String other = rfTimesTwoOperand(n, aTok, bTok);
            if (other != null) {
                rfLoadXmm(other, xa, n);
                raw("    add" + (n == 4 ? "ss" : "sd") + " %" + xa + ", %" + xa);
                rfXmmToGpr(xa, d, n);
                return;
            }
        }
        String xb = rfXmmB();
        String sfx = n == 4 ? "ss" : "sd";
        boolean aDirect = compare && rfIsXvar(aTok); // a compare never modifies a: read the variable's register in place
        if (!aDirect) {
            rfLoadXmm(aTok, xa, n);
        } else {
            xa = xvReg(aTok);
        }
        String bText;
        if (rfIsXvar(bTok)) {
            bText = xText(xvReg(bTok));
        } else if (rfIsSlot(bTok)) {
            long off = rfSlot(bTok);
            bText = (off + "(%rbp)");
        } else if (rfIsImm(bTok)) {
            bText = floatPoolMem(rfImm(bTok), n);
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
            raw(("    " + mn + sfx + " " + bText + ", " + aText));
            rfXmmToGpr(xa, d, n);
            return;
        }
        raw(("    ucomi" + sfx + " " + bText + ", " + aText));
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
            raw("    " + jcc + " " + branchLabel);
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
        String d8 = sizedReg(d, 1);
        raw("    " + setcc + " %" + d8);
        raw("    movzbq %" + d8 + ", %" + d);
        
    }

    void rfRmw(String op, String slotTok, String srcTok) {
        if (rfIsVar(slotTok)) {
            String vr = rfReg(slotTok);
            switch (op) {
                case "INC":
                    raw(("    incq %" + vr));
                    return;
                case "DEC":
                    raw(("    decq %" + vr));
                    return;
                case "ADD":
                case "SUB": {
                    String mn = op.equals("ADD") ? "add" : "sub";
                    if (rfIsSlot(srcTok)) {
                        movMemToReg(RF_SCRATCH, rfSlot(srcTok));
                        rfOp2(mn, rfRegText(vr), rfRegText(RF_SCRATCH));
                    } else {
                        rfOp2(mn, rfRegText(vr), rfSrc(srcTok));
                    }
                    return;
                }
                default:
                    throw new IllegalStateException("unknown R_RMW operator '" + op + "'");
            }
        }
        String mem = rfMemText(rfSlot(slotTok));
        switch (op) {
            case "INC":
                raw(("    incq " + mem));
                return;
            case "DEC":
                raw(("    decq " + mem));
                return;
            case "ADD":
            case "SUB": {
                String mn = op.equals("ADD") ? "add" : "sub";
                if (rfIsSlot(srcTok)) {
                    movMemToReg(RF_SCRATCH, rfSlot(srcTok));
                    rfOp2(mn, mem, rfRegText(RF_SCRATCH));
                } else {
                    rfOp2(mn, mem, rfSrc(srcTok));
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
        raw("    movq %rsp, %rbp");
        
    }

    /**
     * Expands every V1314 mark pair: a variable register is saved and restored around an instruction only when the instruction's own code
     * mentions that register (most scratch users touch none of them); pairs that need nothing disappear.
     */
    int varMarksNeeded(int start) {
        String[] regs = {"r13", "r14", "r12"};
        String sm = V1314_SAVE + "\n";
        String rm = V1314_REST + "\n";
        int mask = 0;
        int from = start;
        int i;
        while ((i = out.indexOf(sm, from)) >= 0) {
            int segStart = i + sm.length();
            int j = out.indexOf(rm, segStart);
            if (j < 0) {
                break;
            }
            String seg = out.substring(segStart, j);
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
        String sm = V1314_SAVE + "\n";
        String rm = V1314_REST + "\n";
        int from = start;
        int i;
        while ((i = out.indexOf(sm, from)) >= 0) {
            int segStart = i + sm.length();
            int j = out.indexOf(rm, segStart);
            if (j < 0) {
                throw new RuntimeException("codegen internal error: unbalanced register-save marks in '" + currentFuncName + "'");
            }
            String seg = out.substring(segStart, j);
            StringBuilder sv = new StringBuilder();
            StringBuilder rs = new StringBuilder();
            for (int vi = 0; vi < 3; vi++) {
                if (vSlots[vi] != null && java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + regs[vi] + "[dwb]?(?![A-Za-z0-9_])").matcher(seg).find()) {
                    sv.append("    movq %").append(regs[vi]).append(", ").append(vSlots[vi]).append("\n");
                    rs.append("    movq ").append(vSlots[vi]).append(", %").append(regs[vi]).append("\n");
                }
            }
            out.replace(j, j + rm.length(), rs.toString());
            out.replace(i, i + sm.length(), sv.toString());
            from = i + sv.length();
        }
    }

    void finishCalleeSaved() {
        if (csrFuncStart < 0) {
            return;
        }
        long frameBytes = csrAllocSum;
        String body = out.substring(csrFuncStart);
        java.util.List<String[]> regs = new java.util.ArrayList<>(); // {name, kind gpr|xmm}
        java.util.regex.Pattern nb = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rbx|ebx|bx|bl|bh)(?![A-Za-z0-9_])");
        if (nb.matcher(body).find()) regs.add(new String[] {"rbx", "gpr"});
        for (int r = 12; r <= 15; r++) {
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])r" + r + "[dwb]?(?![A-Za-z0-9_])").matcher(body).find()) {
                regs.add(new String[] {"r" + r, "gpr"});
            }
        }
        if (isWinAbi()) {
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rdi|edi|di|dil)(?![A-Za-z0-9_])").matcher(body).find()) regs.add(new String[] {"rdi", "gpr"});
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rsi|esi|si|sil)(?![A-Za-z0-9_])").matcher(body).find()) regs.add(new String[] {"rsi", "gpr"});
            for (int x = 6; x <= 15; x++) {
                if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])xmm" + x + "(?![0-9A-Za-z_])").matcher(body).find()) {
                    regs.add(new String[] {"xmm" + x, "xmm"});
                }
            }
        }
        String restoreText = "";
        java.util.Arrays.fill(vSlots, null);
        if (!regs.isEmpty()) {
            if (csrAllocPos < 0) {
                // no locals at all (no ALLOC line): make the frame ourselves, right after the prologue
                csrAllocPos = csrFuncStart;
                csrAllocBytes = 0;
                csrAllocLine = "";
            }
            String beforeAlloc = out.substring(csrFuncStart, csrAllocPos);
            for (String[] r : regs) {
                java.util.regex.Pattern any = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + (r[1].equals("xmm") ? r[0] : (r[0].equals("rbx") ? "(?:rbx|ebx|bx|bl|bh)" : r[0].equals("rdi") ? "(?:rdi|edi|di|dil)" : r[0].equals("rsi") ? "(?:rsi|esi|si|sil)" : r[0] + "[dwb]?")) + "(?![0-9A-Za-z_])");
                if (any.matcher(beforeAlloc.replace(CSR_MARK, "")).find()) {
                    throw new RuntimeException("codegen internal error: function '" + currentFuncName + "' uses " + r[0] + " before its frame is allocated");
                }
            }
            long cur = csrAllocBytes;
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
            java.util.Arrays.fill(vSlots, null);
            int needMask = varMarksNeeded(csrFuncStart);
            for (int vi = 0; vi < 3; vi++) {
                if ((rfVarMask & needMask & (1 << vi)) != 0) {
                    cur += 8;
                    vSlots[vi] = "-" + cur + "(%rbp)";
                }
            }
            long total = (cur + 15) & ~15L;
            frameBytes = csrAllocSum - csrAllocBytes + total;
            String newSub = ("    subq $" + total + ", %rsp\n");
            if (!out.substring(csrAllocPos, csrAllocPos + csrAllocLine.length()).equals(csrAllocLine)) {
                throw new RuntimeException("codegen internal error: ALLOC line moved in '" + currentFuncName + "'");
            }
            out.replace(csrAllocPos, csrAllocPos + csrAllocLine.length(), newSub + saves);
            restoreText = restores.toString();
        }
        expandVarMarks(csrFuncStart);
        int from = csrFuncStart;
        String marked = CSR_MARK + "\n";
        int i;
        while ((i = out.indexOf(marked, from)) >= 0) {
            out.replace(i, i + marked.length(), restoreText);
            from = i + restoreText.length();
        }
        String rspMarked = RSP_MARK + "\n";
        String rspFix = frameBytes == 0 ? ("    movq %rbp, %rsp\n")
                : (("    leaq -" + frameBytes + "(%rbp), %rsp\n"));
        from = csrFuncStart;
        while ((i = out.indexOf(rspMarked, from)) >= 0) {
            out.replace(i, i + rspMarked.length(), rspFix);
            from = i + rspFix.length();
        }
        csrFuncStart = -1;
    }

    void emitFunctionEpilogue() {
        raw(CSR_MARK);
        raw("    movq %rbp, %rsp");
        
        popReg("rbp");
        raw("    ret");
    }
}

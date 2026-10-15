package caspien.codegen.codegenCaspien.x86backend;

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
        return false;
    }

    boolean emitCases7(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        return false;
    }

    void storeSizedToAddr_withOffsetImm(String baseReg64, long offset, long value, int size) {
    }

    void storeSizedToAddr_reg(String baseReg64, long offset, String srcReg64, int size) {
    }

    // See README.md, "Drop-glue and clone-glue calls".
    void emitDropGlueCall(String name) {
    }

    /** Push `reg`, pass it as the first argument to the ghost table function `fn`, restore `reg`. */
    void emitGtCall(String fn, String reg) {
    }

    void emitResizeGtUpdate() {
    }

    void emitResizeWideFillCopy(int elemSize, int words) {
    }

    void emitMallocCall(long size) {
    }

    String newInternalLabel(String prefix) {
        return null;
    }

    String rfReg(String tok) {
        return null;
    }

    String rfRegText(String reg) {
        return null;
    }

    String rfMemText(long off) {
        return null;
    }

    /** Source-operand text for a two-operand instruction; a 64-bit immediate that will not fit imm32 goes through the scratch register. */
    String rfSrc(String tok) {
        return null;
    }

    /** "op src, dst" in the active syntax (AT&T gets the q suffix and reversed operand order). */
    void rfOp2(String op, String dstText, String srcText) {
    }

    void rfRegToReg(String dst, String src) {
    }

    void rfMov(String dst, String src) {
    }

    void rfRotate(String op, int size, String dstTok, String aTok, String bTok) {
    }

    void rfShift(String op, int size, String dstTok, String aTok, String bTok) {
    }

    void rfDivConst(boolean mod, int size, String dstTok, String aTok, long k) {
    }
}

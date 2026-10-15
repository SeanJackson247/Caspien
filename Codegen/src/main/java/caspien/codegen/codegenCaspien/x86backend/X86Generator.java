package caspien.codegen.codegenCaspien.x86backend;

import caspien.codegen.codegenCaspien.CodegenTarget;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.Text;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The x86-64 backend's code generator (register form, callee-saved handling, `generate`); overview in README.md. */
final class X86Generator {
    private final X86Backend hub;
    private final X86Core core;

    X86Generator(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    public String generate(List<BytecodeStatement> lines) {
        return null;
    }

    void rfBin(String op, int size, String dstTok, String aTok, String bTok) {
    }

    boolean rfMulByBigConst(String d, String aTok, long k) {
        return false;
    }

    boolean rfMulByConst(String d, long k) {
        return false;
    }

    void rfBrcMem(String op, String aTok, String baseTok, String label) {
    }

    String rfBrcNarrow(String op, int size, String aTok, String bTok, String label) {
        return null;
    }

    String rfBrc(String op, String aTok, String bTok, String label) {
        return null;
    }

    /** `mov imm, reg` that leaves the flags alone (never the xor idiom) */
    void rfMovImmKeepFlags(String reg, long v) {
    }

    void rfCmov(BytecodeStatement line) {
    }

    void rfExtend(String reg, int size, boolean signed) {
    }

    void rfUn(String op, int size, String dstTok, String aTok) {
    }

    /** address of a frame slot ("$off") or a global ("&sym") into reg. */
    void rfAddrToReg(String reg, String tok) {
    }

    /** An integer argument register; an index beyond the register-passed ones would fall back to rax (a temp), which the pass never produces. */
    String rfArgReg(int idx) {
        return null;
    }

    void rfLoad(int n, String dstTok, String srcTok) {
    }

    /** "(disp)(%base)" memory text for a register base plus a constant displacement */
    String rfDispMem(String baseTok, long disp) {
        return null;
    }

    /** %tD = n bytes (1, 2, 4 or 8) at base + disp, zero-extended */
    void rfLoadDisp(int n, String dstTok, String baseTok, long disp) {
    }

    /** n bytes (1, 2, 4 or 8) at base + disp = src (#imm or a register) */
    void rfStoreDisp(int n, String baseTok, long disp, String srcTok) {
    }

    void rfLoadXDisp(String xmm, String baseTok, long disp, int n) {
    }

    void rfStoreXDisp(String baseTok, long disp, String xmm, int n) {
    }

    void rfStore(int n, String addrTok, String srcTok) {
    }

    String rfMemOperand(String baseReg) {
        return null;
    }

    String rfIndexedBase(String baseTok, String scratch) {
        return null;
    }

    void rfSetIndex(String baseTok, String idxTok, long scale) {
    }

    void rfLoadIndexed(int n, String dstTok, String baseTok, String idxTok, long scale, long disp) {
    }

    void rfStoreIndexed(int n, String baseTok, String idxTok, long scale, String srcTok, long disp) {
    }

    /** xmm register = the float at global + index*scale (R_LDXI): the base address goes through the scratch register. */
    void rfLoadXIndexed(int n, String xTok, String baseTok, String idxTok, long scale, long disp) {
    }

    /** the float in an xmm register stored at base + index*scale (R_STXI). */
    void rfStoreXIndexed(int n, String baseTok, String idxTok, long scale, String xTok, long disp) {
    }

    void rfAddImm(String reg, long v) {
    }

    void rfLea(String dstTok, String baseTok, String idxTok, long scale, long extra) {
    }

    /** Scratch xmm registers for register-form float ops: never an argument register (SysV passes floats in xmm0-7; win64 in xmm0-3) and never a win64 non-volatile one. */
    String rfXmmA() {
        return null;
    }

    String rfXmmB() {
        return null;
    }

    void rfGprToXmm(String xmm, String gpr64, int n) {
    }

    void rfXmmToGpr(String xmm, String gpr64, int n) {
    }

    /** a float operand (a temp, an immediate bit pattern or a frame slot of the op's own width) into an xmm register. */
    void rfLoadXmm(String tok, String xmm, int n) {
    }

    String yPhys(int k) {
        return null;
    }

    String xvPhys(int k) {
        return null;
    }

    String xvReg(String tok) {
        return null;
    }

    void xvSpillAll() {
    }

    void xvReloadAll() {
    }

    /** The pool label for a float constant of width n (4 or 8 bytes), creating the entry on first use. */
    String floatPoolLabel(long bits, int n) {
        return null;
    }

    /** The memory operand text of a pooled float constant. */
    String floatPoolMem(long bits, int n) {
        return null;
    }

    /** Emits the pool after the last function: read-only, each entry aligned to its own size. */
    void emitFloatPool() {
    }

    String xText(String xmm) {
        return null;
    }

    void rfMovaps(String dst, String src) {
    }

    /** memory operand text for a float at addr (a frame slot, a global or the pointer in a temp), n = 4 or 8 bytes wide. */
    String rfFloatMem(String addrTok, int n) {
        return null;
    }

    /** xmm register = the float (n = 4: f32, 8: f64) at addr. */
    void rfLoadX(String xmm, String addrTok, int n) {
    }

    /** the float in an xmm register stored at addr. */
    void rfStoreX(String addrTok, String xmm, int n) {
    }

    /** "R_FBINX OP n %xK a b": variable K = a OP b (n is always 4). */
    /** For `a * b` where exactly one operand is the immediate 2.0 (f64 or f32 bits per n): the other operand token, else null. */
    String rfTimesTwoOperand(int n, String aTok, String bTok) {
        return null;
    }

    void rfFloatX(String op, int n, String dstTok, String aTok, String bTok) {
    }

    void rfFloat(boolean compare, String op, int n, String dstTok, String aTok, String bTok) {
    }

    /** branchLabel != null (compare only, dstTok null): jump to it when NOT (a op b) instead of producing the 0/1 value. */
    void rfFloat(boolean compare, String op, int n, String dstTok, String aTok, String bTok, String branchLabel) {
    }

    void rfSetcc(String setcc, String d) {
    }

    void rfRmw(String op, String slotTok, String srcTok) {
    }

    /** "ss" for a 4-byte float, else "sd" */
    private static String fsfx(int n) {
        return null;
    }

    /** "sd" for width 8, else "ss" */
    private static String widthSfx(int w) {
        return null;
    }

    /** "movsd" for 8 bytes, else "movss" */
    private static String fmov(int n) {
        return null;
    }

    /** v cut to 32 bits when n == 4 */
    private static long maskIf4(long v, int n) {
        return 0;
    }

    /** v as is when n == 8, else cut to 32 bits */
    private static long maskUnless8(long v, int n) {
        return 0;
    }

    String mangleLabel(String text) {
        return null;
    }

    void movRegToRegRbpFromRsp() {
    }

    int varMarksNeeded(int start) {
        return 0;
    }

    void expandVarMarks(int start) {
    }

    void finishCalleeSaved() {
    }

    void emitFunctionEpilogue() {
    }
}

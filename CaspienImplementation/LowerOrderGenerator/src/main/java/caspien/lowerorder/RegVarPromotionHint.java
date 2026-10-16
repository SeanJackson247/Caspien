package caspien.lowerorder;

final class RegVarPromotionHint {
    long off;
    int size;
    long weight;
    boolean banned;
    int reg = -1;
    /** the hint carried the float marker */
    boolean isFloat;
    /** promoted to an xmm register (float hint and the float switch is on) rather than r13/r14 */
    boolean xmm;
    /** may live in a caller-saved register (r8/r9/r10): never live across or at a line that could clobber them */
    boolean volOk;
    /** may live in rsi/rdi: volOk, and never mentioned inside a call's argument bracket (CC_START .. CC_END), where those registers are being filled */
    boolean argSafe;
    /** may live in rdx: never live at or across a constant divide (R_DIVC clobbers rdx) */
    boolean rdxOk;
    /** may live in rcx: never live at or across a variable-count shift (the shift saves and uses rcx as the count) */
    boolean rcxOk;
}

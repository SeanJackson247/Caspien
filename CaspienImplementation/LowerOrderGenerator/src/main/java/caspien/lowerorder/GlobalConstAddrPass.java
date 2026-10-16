package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;


/**
 * A global array element at a constant index is one RIP-relative instruction. Runs after {@link IndexedAccessPass} (always on;
 * no register-form lines, nothing to match):
 * <pre>
 *   R_LEA %t0 &amp;W #9 8 ; R_LD 8 %v6 %t0      -&gt;   R_LD 8 %v6 &amp;W+72
 *   R_LEA %t0 &amp;W #16 8 ; R_ST 8 %t0 %t1     -&gt;   R_ST 8 &amp;W+128 %t1
 *   R_LEA %t0 &amp;f #3 8 ; R_LDX 8 %y0 %t0     -&gt;   R_LDX 8 %y0 &amp;f+24      (and R_STX)
 * </pre>
 * The address token {@code &sym+N} is a global plus a constant byte offset; the backend writes it as {@code sym+N(%rip)}.
 * Only widths 1, 2, 4, 8 (the backend's odd widths go through an address register), the lea temp must be dead after the access and
 * not read by it other than as the address, and every line between lea and access must be a known register-form line that does
 * not mention the temp (same rules as {@link FieldDisplacementPass}).
 */
public class GlobalConstAddrPass {

    private static final int MAX_GAP = 40;

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private static String t(BytecodeStatement l, int i) { return null; }

    /** `R_LEA %tX &sym #k scale [extra]` -> "&sym+N" (N = k*scale + extra >= 0), else null */
    private static String symbolWithOffset(BytecodeStatement l) { return null; }

    private static boolean okWidth(String s) { return false; }

    private static BytecodeStatement fuse(BytecodeStatement l, String x, String sym) { return null; }
}

package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;


/**
 * Field access through a pointer as one instruction (always on; runs last, after {@link LengthCompareFusionPass}, on the final register-form
 * text; only the backend reads the result). A struct field read or written through a pointer in a register used to cost an address
 * instruction and the access:
 *
 * <pre>
 *   R_LEA %t0 %t0 #8 1 ; R_LD 8 %t0 %t0         ->  R_LDD 8 %t0 %t0 8          (movq 8(%rax), %rax, was addq $8, %rax; movq (%rax), %rax)
 *   R_LEA %t1 %v0 #16 1 ; R_ST 8 %t1 %t2         ->  R_STD 8 %v0 16 %t2
 *   R_LEA %t0 %t0 #8 1 ; R_LDX 8 %x0 %t0         ->  R_LDXD 8 %x0 %t0 8
 *   R_LEA %t0 %t0 #8 1 ; R_STX 8 %t0 %x0         ->  R_STXD 8 %t0 8 %x0
 * </pre>
 *
 * The lea has a register base and an immediate index (displacement = index * scale [+ the lea's own 6th token]). The lines between the lea
 * and its consumer must be known register-form mnemonics that neither mention the lea's temp nor write the base register (the base is read
 * later than before), and the lea's temp must be dead after the consumer (as in {@link IndexedAccessPass}). Access widths 1, 2, 4 and 8 only
 * (floats 4 and 8), displacements within 32 bits.
 */
public class FieldDisplacementPass {

    private static final int MAX_GAP = 40;

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    /** the byte displacement of `R_LEA %tX %base #k scale [disp]` (register base, immediate index), or Long.MIN_VALUE when the line has another shape */
    private static long displacement(BytecodeStatement l) { return 0L; }

    private static boolean okWidth(String s) { return false; }

    /** the fused line for consumer l of the address in x, or null */
    private static BytecodeStatement fuse(BytecodeStatement l, String x, String base, long disp) { return null; }

    private static BytecodeStatement line(BytecodeToken h, String... parts) { return null; }

    private static boolean isXmm(String tok) { return false; }

    private static String t(BytecodeStatement l, int i) { return null; }
}

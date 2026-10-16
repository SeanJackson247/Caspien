package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;


/**
 * Constant-bounds `match i in arr` / `match i into arr` check (always on, runs on the STACK-form text right after
 * StrengthReductionPass and before RegisterFormPass). MembershipLoweringPass builds the per-iteration bounds test of a
 * range with literal bounds by materialising the range on the stack and copying it into temps:
 *
 *   PUSH 8 $i ; PUSH 8 K0 ; PUSH 8 K1 ; POP $r 16 ; POP $l 8 ;
 *   PUSH 8 $l ; PUSH 8 $r ; GT_EQ_INT 8 ; PUSH 8 $l ; PUSH 8 $r+8 ; LT_INT 8 ; AND 1 ; CMP ; JMP @L
 *
 * ($r holds K0, $r+8 holds K1; $l holds a copy of $i). That is five stack operations and two frame stores per check, and
 * the `PUSH 8 $i` keeps the index out of a variable register. With the three temp slots mentioned nowhere else in the
 * function the whole thing is the same as
 *
 *   PUSH 8 $i ; PUSH 8 K0 ; GT_EQ_INT 8 ; PUSH 8 $i ; PUSH 8 K1 ; LT_INT 8 ; AND 1 ; CMP ; JMP @L
 *
 * and, when K0 is 0 (an unsigned index is always >= 0), just
 *
 *   PUSH 8 $i ; PUSH 8 K1 ; LT_INT 8 ; CMP ; JMP @L
 *
 * RegisterFormPass then fuses what is left. Only this exact 14-line shape is rewritten, only with decimal literal bounds in
 * 0..2^31-1, only for the unsigned compares (GT_EQ_INT / LT_INT), and only when no other line of the same function mentions
 * $l, $r or $r+8 (so the dropped stores cannot be observed).
 */
public class RangeCheckFusionPass {

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private static BytecodeProgram match(BytecodeProgram ls, int i, int fs, int fe) { return null; }

    private static BytecodeStatement copy(BytecodeStatement l, String unused) { return null; }

    private static boolean isMn(BytecodeStatement l, String m) { return false; }
    private static String t(BytecodeStatement l, int i) { return null; }
    private static boolean isPush8(BytecodeStatement l) { return false; }
    private static boolean isOp(BytecodeStatement l, String m, String sz) { return false; }
    private static boolean isSlot(String s) { return false; }

    private static Long off(String s) { return null; }

    /** `PUSH 8 <decimal literal in 0..2^31-1>` -> the literal, else null. */
    private static Long lit(BytecodeStatement l) { return null; }
}

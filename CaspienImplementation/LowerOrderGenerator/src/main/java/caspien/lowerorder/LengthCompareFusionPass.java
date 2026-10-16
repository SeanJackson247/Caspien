package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;

import java.util.HashMap;
import java.util.Map;

/**
 * Folds the length load of a safe dynarray bounds check into the compare ({@code length-compare-fusion: on}; runs last, after
 * {@link IndexedAccessPass}, on the final register-form text). A safe access {@code match i in a { ... a[i] ... }} loads the length word
 * at the start of the array block and compares the index with it:
 *
 * <pre>
 *   R_MOV 8 %t0 $S ; R_LD 8 %t0 %t0 ; R_BRC LT 8 idx %t0 @out ; R_MOV 8 %t0 $S ; R_LDI 8 %t0 %t0 idx 8 16
 *   R_LD 8 %t0 %v0 ; R_BRC LT 8 idx %t0 @out                              (base hoisted into %v0 by LoopHoistPass)
 * </pre>
 *
 * becomes
 *
 * <pre>
 *   R_MOV 8 %t0 $S ; R_BRCM LT 8 idx %t0 @out ; R_LDI 8 %t0 %t0 idx 8 16       (cmpq (%rax), %r9: no length register, no base reload)
 *   R_BRCM LT 8 idx %v0 @out
 * </pre>
 *
 * {@code R_BRCM op 8 a %base @L} is {@code R_BRC} whose second operand is the 8-byte word at the address in {@code %base}; `a` is a register or
 * (first form only) a frame slot, which the backend loads into its scratch register first (with a slot index the second form would gain nothing). The first form
 * keeps the BASE in %t0 (it used to hold the length), so an identical {@code R_MOV 8 %t0 $S} right after the branch is dropped; when it is
 * not there the temp must be dead on the fall-through path. On the jump-out path the temp must be dead too (the first mention after the
 * label is a pure redefinition). The length register disappears in the second form, so the temp must be dead on both paths there as well.
 * Measured on lru_safe: 10% faster (the hand-patched assembly), same output.
 */
public class LengthCompareFusionPass {

    private final boolean enabled;

    public LengthCompareFusionPass(boolean enabled) { }

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    /** `R_BRC op 8 X %tA @L` with a register X different from %tA: the length (%tA) is the second compared operand */
    private static boolean isLengthBranch(BytecodeStatement b, String tA, boolean slotIndexOk) { return false; }

    private static BytecodeStatement fused(BytecodeStatement b, String base) { return null; }

    private static boolean sameLine(BytecodeStatement x, BytecodeStatement y) { return false; }

    /** the temp is dead from line `from` on (never read before a pure redefinition, a label or a jump) */
    private static boolean deadAfter(BytecodeProgram cur, boolean[] gone, int from, String x) { return false; }

    /** the temp is dead where the branch jumps to (its label must be defined in this function's text; unknown label: not dead) */
    private static boolean deadAtTarget(BytecodeProgram cur, boolean[] gone, Map<String, Integer> labelAt, BytecodeStatement b, String x) { return false; }

    private static String t(BytecodeStatement l, int i) { return null; }
}

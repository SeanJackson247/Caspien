package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;

import java.util.Set;

/**
 * Compare-and-branch fusion on the final register-form text (always on; a pure code-quality rewrite). A comparison whose
 * 0/1 result is only used to decide a jump no longer materialises that value (setcc / movzbq / test / je); it compares and
 * jumps:
 *
 *   R_BIN C 8 %tD a b ; R_BRF %tD @L                                   ->  R_BRC C 8 a b @L
 *   R_BIN C1 8 %tX a b ; R_BIN C2 8 %tY c d ; R_BIN AND 1 %tR %tX %tY ; R_BRF %tR @L
 *                                                                       ->  R_BRC C1 8 a b @L ; R_BRC C2 8 c d @L
 *
 * A float compare feeding a jump fuses the same way (`R_FCMP C n %tD a b ; R_BRF %tD @L` -> `R_BRC C Fn a b @L`, no setcc/movzbq/test).
 * "R_BRC C size a b @L" jumps to L when NOT (a C b). The second shape is the test of a `for` loop over a range
 * (start <= i and i < end); the two compares are pure reads, so evaluating the second only when the first held changes nothing.
 * 8-byte compares, and 1/2/4-byte compares whose operands are registers or immediates (the backend emits a sized `cmpb/cmpw/cmpl`, which looks at the low bytes only,
 * exactly what the unfused narrow compare did after extending its operands; a frame-slot operand stays unfused), and only when the compare result temps are
 * dead afterwards, which the register-form model guarantees (a temp is never live across a jump or label). Runs after
 * RegVarPromotionPass and FloatTempPass so neither needs to know the new line.
 */
public class BranchFusionPass {

    private static final Set<String> CMP = Set.of("EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ", "SLT", "SLT_EQ", "SGT", "SGT_EQ");

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private static String t(BytecodeStatement l, int i) { return null; }

    private static boolean isCmp(BytecodeStatement l) { return false; }

    private static boolean isFcmp(BytecodeStatement l) { return false; }

    private static boolean isAnd(BytecodeStatement l) { return false; }

    private static boolean isBrf(BytecodeStatement l) { return false; }

    private static boolean notBothImm(BytecodeStatement l) { return false; }

    private static boolean mentions(BytecodeStatement l, String tok) { return false; }

    private static BytecodeStatement brc(BytecodeStatement cmp, BytecodeStatement brf) { return null; }
}

package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;

import java.util.HashMap;
import java.util.Map;
import caspien.bytecode.Text;

/**
 * Conditional moves (`conditional-move: on`). A select between two register-or-constant values of a promoted variable is a compare and a branch
 * around a move; this pass turns the two shapes
 *
 *   one arm:   R_BRC C n a b @E ; R_MOV 8 %vK X | R_SETV m %vK #c ; @E:
 *   two arms:  R_BRC C n a b @L ; R_MOV 8 %vK X ; JMP @E ; @L: ; R_MOV 8 %vK Y ; @E:      (the labels are used only by these jumps)
 *
 * into one {@code R_CMOV C n a b %vK X [Y]}: %vK = (a C b) ? X : Y (one arm: %vK keeps its value when the test fails). The backend emits
 * `cmp`, `mov Y` (which leaves the flags alone) and `cmovCC X`: no jump, no mispredict. X and Y are a register or an immediate (a SETV
 * constant is cut to its width here); a source that reads memory is never speculated. Two arms are only merged when X is not %vK itself.
 */
public class ConditionalMovePass {

    private final boolean enabled;

    public ConditionalMovePass(boolean enabled) { }

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    /** {variable, source} of `R_MOV 8 %vK src` (register or immediate) or `R_SETV m %vK #c` (the constant cut to m bytes, zero-extended), else null */
    private static String[] source(BytecodeStatement l) { return null; }

    private static boolean isNumImm(String s) { return false; }

    private static BytecodeStatement cmov(BytecodeStatement br, String v, String x, String y) { return null; }

    private static boolean isLabel(BytecodeStatement l, String name) { return false; }

    private static boolean isJmp(BytecodeStatement l) { return false; }

    private static String t(BytecodeStatement l, int i) { return null; }
}

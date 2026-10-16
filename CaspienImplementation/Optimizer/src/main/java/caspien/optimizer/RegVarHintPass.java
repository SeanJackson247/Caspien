package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.bytecode.BytecodeTokenKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import caspien.bytecode.Text;

/**
 * Marks local variables that are worth keeping in a register, as a HINT for the lower stages:
 *
 *     REGVAR name weight [f]
 *
 * (the optional trailing "f" says the variable is a float, so a lowering that has a separate float register file can use it)
 *
 * placed right after the function's leading ALLOC run. The ALLOC stays (a variable that gets no register simply stays a
 * stack slot, and a lower stage that ignores the hint produces exactly the code it did before). The hint names NO register:
 * which registers exist, which are free and how many is the lowering's business.
 *
 * A local is eligible when
 *   - it is declared by a plain "ALLOC name type" with a plain scalar type (mut/imut u8..u64, s8..s64, f32, bool, char;
 *     no storage keyword, no atomic, no struct/array/range), every declaration of the name in the function having the same
 *     type (the `i` of several `for` loops is one slot), and no '$' hidden name;
 *   - its name appears ONLY as the operand of ALLOC, PUSH and ADDR lines (any other mention, e.g. GT_DESTRUCT, disqualifies);
 *   - no PUSH/ADDR of it is directly followed by ADDR_OF (that is raw/auto/ref taking its address).
 * The weight is the number of PUSH/ADDR mentions, each counted 8^depth where depth is the number of loops (a backward JMP to an
 * already-seen label) containing it, capped at depth 4. Only variables with a weight of at least 8 (used inside a loop) are
 * hinted. A function that already carries REGVAR lines is left alone, so the pass is idempotent inside the fixed-point loop.
 * This pass proves nothing about the lowered code; the LowerOrderGenerator re-checks every mention before it promotes anything.
 */
public class RegVarHintPass {

    private static final long MIN_WEIGHT = 8;

    public PassResult run(BytecodeProgram lines) { return null; }

    private static boolean isMnemonic(BytecodeStatement line, String m) { return false; }

    private static String mnemonic(BytecodeStatement line) { return null; }

    /** Returns the function's lines with REGVAR lines inserted, or null when nothing changes. */
    private BytecodeProgram hintFunction(BytecodeProgram fn) { return null; }
}

package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Variable shifting: a local scalar that is assigned constants more than once, and is otherwise a variable elision candidate (every
 * assignment is "ADDR x T / PUSH literal / ASSIGN", never address-taken, no other kind of mention), is split at each reassignment:
 * a new variable of the same type is allocated (its ALLOC is hoisted next to x's), the reassignment now writes to it, and every
 * reference after that point is renamed to it. Each resulting variable is assigned once, so VariableElisionPass can then take them.
 *
 *     let x = 1; use(x); x = 2; use(x)      ->     x = 1; use(x); x__s1 = 2; use(x__s1)
 *
 * Only straight-line splits are made. A reassignment is split only if it is reached by every path to the references after it (no
 * forward jump from before it lands between it and a later reference) and is not inside a loop (no backward jump after it lands
 * before it); a reassignment inside an 'if' branch or a loop body is left alone, and so is x's first assignment. A function with a
 * catch block (its body is emitted at the top of the function, out of textual order) or inline assembly is skipped.
 *
 * Off unless "variable-shifting: on" is set in compiler.config (see VariableConfig).
 */
public class VariableShiftingPass {

    private final boolean enabled;

    public VariableShiftingPass() { }

    public VariableShiftingPass(boolean enabled) { }

    public PassResult run(BytecodeProgram lines) { return null; }

    /** A reassignment at line a can start a new version only if it is reached on every path to the later mentions and is not in a loop. */
    private static boolean canSplit(int a, TreeMap<Integer, Boolean> mentions, List<int[]> jumps) { return false; }

    private static boolean hasCatchLabel(BytecodeProgram L, int s, int e) { return false; }
}

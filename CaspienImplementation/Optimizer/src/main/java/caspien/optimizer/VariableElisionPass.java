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
import caspien.bytecode.Text;

/**
 * Variable elision (constant propagation for a variable that is assigned exactly once): finds
 *
 *     ADDR x T
 *     PUSH <literal> T2
 *     ASSIGN ...
 *
 * where x is a local scalar that is never assigned again and never has its address taken, removes the assignment (and x's ALLOC),
 * and replaces every read "PUSH x T" with "PUSH <literal> T". With ConstantFoldingPass running in the same inner loop, the
 * substituted literals then fold in turn.
 *
 * Safe only if every read is reached through the assignment, so a read must sit after the assignment in the text and no forward jump
 * from before the assignment may land between it and the read (an assignment inside an 'if' branch does not license reads after the
 * branch). A variable with any other kind of mention (compound assignment, ++, address taken, struct field, parameter, ...) is left
 * alone. Only integers (u/s 8..64), f32/f64 and bool; a negative literal in a narrow type is not propagated. A function containing
 * inline assembly is skipped.
 *
 * Off unless "variable-elision: on" is set in compiler.config (see VariableConfig).
 */
public class VariableElisionPass {

    private final boolean enabled;

    public VariableElisionPass() { }

    public VariableElisionPass(boolean enabled) { }

    public PassResult run(BytecodeProgram lines) { return null; }

    /**
     * A hidden for-range's type text spells a variable bound by name, e.g. {@code imut_range(0,imut_n)}. Once n is elided the name is
     * dangling, and LoopUnrollingPass (which reads literal trip counts only from this text) could not use the known bound. Rewrites
     * such bounds to the literal: {@code imut_range(0,4)}. Every token spelling the type is rewritten the same way, so they stay equal.
     */
    private static BytecodeStatement rewriteRangeTypes(BytecodeStatement row, Map<String, String> lits) { return null; }

    private static String boundText(String part, Map<String, String> lits) { return null; }
}

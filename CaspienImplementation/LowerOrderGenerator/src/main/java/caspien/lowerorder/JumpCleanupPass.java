package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;

import java.util.Map;

/**
 * Jump cleanup on the final register-form text (always on; a pure code-quality rewrite, runs after BranchFusionPass).
 * `if c { break }` and friends leave chains such as
 *
 *   R_BRC EQ 8 a b @L1 ; JMP @Lend ; JMP @L1 ; @L1:
 *
 * which assemble to `jne L1; jmp Lend; jmp L1; L1:`. Three local rewrites, repeated until nothing changes:
 *   1. a JMP or R_BRC directly after an unconditional JMP is unreachable (nothing can fall into it; a label would stop the
 *      scan) and is dropped;
 *   2. a JMP whose target label is the next thing in the text (only labels in between) is dropped;
 *   3. `R_BRC C s a b @L1 ; JMP @L2 ; @L1:` becomes `R_BRC C' s a b @L2 ; @L1:` with C' the opposite condition
 *      ("R_BRC C" jumps when NOT (a C b), so jumping to L2 when (a C b) holds is the jump of the opposite condition).
 * A `JMP` directly after a bare `CMP` (stack form) is the conditional jump and is never treated as unconditional.
 * Labels are never removed, so every other reference stays valid. Only JMP / R_BRC lines are ever deleted.
 */
public class JumpCleanupPass {

    private static final Map<String, String> OPPOSITE = Map.ofEntries(
            Map.entry("EQ", "NEQ"), Map.entry("NEQ", "EQ"),
            Map.entry("LT", "GT_EQ"), Map.entry("GT_EQ", "LT"),
            Map.entry("LT_EQ", "GT"), Map.entry("GT", "LT_EQ"),
            Map.entry("SLT", "SGT_EQ"), Map.entry("SGT_EQ", "SLT"),
            Map.entry("SLT_EQ", "SGT"), Map.entry("SGT", "SLT_EQ"));

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private BytecodeProgram step(BytecodeProgram in) { return null; }

    /** In stack form a `JMP` right after a bare `CMP` is the conditional jump (jump when the compared value is false), not a plain one. */
    private static boolean followsCmp(BytecodeProgram list, int idx) { return false; }

    private static boolean nextLabelsContain(BytecodeProgram in, int from, String label) { return false; }

    private static boolean isLabel(BytecodeStatement l) { return false; }

    private static boolean isLabelDef(BytecodeStatement l, String label) { return false; }

    private static boolean isJmp(BytecodeStatement l) { return false; }

    private static boolean isBrc(BytecodeStatement l) { return false; }

    private static String t(BytecodeStatement l, int i) { return null; }
}

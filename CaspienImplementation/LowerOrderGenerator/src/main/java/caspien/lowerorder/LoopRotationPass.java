package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;

import java.util.HashMap;
import java.util.Map;

/**
 * Loop rotation (`loop-rotation: on`), the last register-form pass. A `for` (or `loop` that starts with an exit test) is emitted as
 *
 *   @X:  [pure header lines] ; R_BRC C s a b @E          (leave when NOT (a C b))
 *        body
 *        JMP @X
 *   @E:
 *
 * so every iteration executes a conditional jump AND an unconditional jump. The rotated form copies the header to the bottom:
 *
 *   @X:  [header] ; R_BRC C s a b @E                      (entry test, runs once)
 *   @Xr:
 *        body
 *        [header] ; R_BRC C' s a b @Xr                    (C' = the opposite condition: continue while (a C b))
 *   @E:
 *
 * one conditional jump per iteration. The original header stays where it is (it still serves the entry and any other jump to @X), so
 * the transformation is exact: the copy executes the same pure lines in the same register state as the next iteration's header would.
 * Applied only when
 *   - the back edge `JMP @X` is directly followed by @E (the target of the header's exit branch), so the fall-through of the copy lands
 *     where the original jump-to-exit landed;
 *   - the header is at most three lines that only define temporaries (`R_MOV`/`R_LD`/`R_LDI`/`R_LDD` into %t, simple `R_BIN` into %t)
 *     followed by an `R_BRC` or `R_BRCM` whose condition has an opposite; no label, call, store or other side effect in between.
 */
public class LoopRotationPass {

    private static final Map<String, String> OPPOSITE = Map.ofEntries(
            Map.entry("EQ", "NEQ"), Map.entry("NEQ", "EQ"),
            Map.entry("LT", "GT_EQ"), Map.entry("GT_EQ", "LT"),
            Map.entry("LT_EQ", "GT"), Map.entry("GT", "LT_EQ"),
            Map.entry("SLT", "SGT_EQ"), Map.entry("SGT_EQ", "SLT"),
            Map.entry("SLT_EQ", "SGT"), Map.entry("SGT", "SLT_EQ"));
    private static final java.util.Set<String> SIMPLE_BIN = java.util.Set.of("ADD", "SUB", "MUL", "AND", "OR", "BAND", "BOR", "BXOR", "SHL", "SHR", "SAR");
    private static final int MAX_HEADER_PURE_LINES = 3;

    private final boolean enabled;

    public LoopRotationPass(boolean enabled) { }

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private static boolean usedBy(Map<Integer, BytecodeProgram> replaceAt, String label) { return false; }

    /** True when the lines starting at `from` are labels only (up to the first non-label) and one of them is `label:`. */
    private static boolean labelRunAfterContains(BytecodeProgram in, int from, String label) { return false; }

    private static boolean isPure(BytecodeStatement l) { return false; }

    private static boolean isTemp(String s) { return false; }

    private static boolean isBranch(BytecodeStatement l) { return false; }

    private static boolean isLabel(BytecodeStatement l) { return false; }

    private static boolean isJmp(BytecodeStatement l) { return false; }

    private static String t(BytecodeStatement l, int i) { return null; }
}

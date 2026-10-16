package caspien.lowerorder;
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
 * 16-byte range values built or copied word by word instead of through the stack (runs on the STACK-form text, after
 * RangeEndHintPass and before RegisterFormPass; only when "deferred-operands" is on).
 *
 * <p>A `range` is two 8-byte words (start at the lower address). The lowering of `@recursive` functions, `for` loops and
 * ordinary range code builds and copies them with 16-byte stack operations that RegisterFormPass cannot fuse:
 *
 * <pre>
 *   ADDR 16 $X ; PUSH 8 a ; PUSH 8 b ; ASSIGN 16 16 16      (construction)
 *   ADDR 16 $X ; PUSH 16 $Y ; ASSIGN 16 16 16                (copy)
 * </pre>
 *
 * Each becomes two independent 8-byte assignments, which the register-form pass turns into plain moves:
 *
 * <pre>
 *   ADDR 8 $X ; PUSH 8 a ; ASSIGN 8 8 8 ; ADDR 8 $X+8 ; PUSH 8 b ; ASSIGN 8 8 8
 * </pre>
 *
 * Conditions (anything else is left byte for byte): `a` and `b` are `$slot`, an integer literal or `ARGn`; a slot operand
 * is read AFTER the first word of the destination was written (b) so its 8 bytes must not overlap $X..$X+16; a copy needs
 * $Y == $X (left alone, it is a no-op) or $Y..$Y+16 disjoint from $X..$X+16. The bytes written are exactly the same.
 */
public class RangeWordSplitPass {

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private final Set<Long> splitSlots = new HashSet<>();

    private BytecodeProgram split(BytecodeProgram lines, Set<Long> slots) { return null; }

    /** REGHINT lines for the two words of every split range whose every mention is a plain 8-byte one (the promotion re-checks all of it). */
    private BytecodeProgram hint(BytecodeProgram fn) { return null; }

    /** weights {start word, end word}, or null when some mention is not a plain 8-byte access or a wide access overlaps. */
    private static long[] usable(BytecodeProgram fn, long x, int[] depth) { return null; }

    private static long wt(int depth) { return 0L; }

    private static Long addr16(BytecodeStatement l) { return null; }

    private static Long push16(BytecodeStatement l) { return null; }

    private static boolean isAssign16(BytecodeStatement l) { return false; }

    /** PUSH 8 of an integer literal, ARGn, or a slot; a slot read after the destination's first word was written must not overlap it. */
    private static boolean operand8(BytecodeStatement l, long x, boolean afterFirstWrite) { return false; }

    private static String t(BytecodeStatement l, int i) { return null; }

    private static Long num(String s) { return null; }

    private static Long slotOff(String s) { return null; }

    private static BytecodeStatement mk(BytecodeToken ref, String... texts) { return null; }
}

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

/**
 * Loop-invariant base-pointer copies ("hoisting the array base") -- runs after {@link RegisterFormPass} and before
 * {@link RegVarPromotionPass}, only when compiler.config says "hoist-array-bases: on" (needs deferred-operands and
 * variables-in-registers, otherwise a copy has nowhere to live).
 *
 * <p>In safe code every access of a dynarray local reloads the pointer from its frame slot twice (once for the length check,
 * once for the element address): {@code R_MOV 8 %t0 $S ; R_LD 8 %t0 %t0 ; R_BRC ... ; R_MOV 8 %t0 $S ; R_LEA %t0 %t0 idx 1 16}.
 * Per loop, when the slot {@code $S} is never written inside the loop, this pass makes a hidden copy slot {@code $X} just before
 * the loop header ({@code R_MOV 8 $X $S}), points every read of {@code $S} inside the loop at {@code $X}, and gives {@code $X}
 * a REGHINT. {@link RegVarPromotionPass} then decides, with its usual rules (weights, liveness sharing, volatile registers
 * only across call-free ranges), whether the copy gets a register; if it gets none the copy stays in memory and the loop
 * reads {@code $X} instead of {@code $S}, which costs the same as before plus one move per loop entry.
 *
 * <p>A slot is hoisted out of a loop only when ALL of these hold:
 * <ul>
 *   <li>the loop is a natural loop with a single entry: its header label is never jumped to from outside the loop, and no
 *       label inside it is referenced from outside;</li>
 *   <li>every mention of {@code $S} inside the loop is a plain read {@code R_MOV 8 %t $S} (any write, {@code ADDR}, {@code
 *       GT_DESTRUCT}, {@code RESIZE} operand, ... inside the loop keeps the slot as it is);</li>
 *   <li>the address of {@code $S} is never exposed anywhere in the function ({@code ADDR_OF}, {@code R_ARGA}), so nothing else
 *       (a callee, a pointer) can change it while the loop runs;</li>
 *   <li>at least {@link #MIN_READS} of the reads are the start of a dynarray/pointer access ({@code R_MOV %t $S} directly
 *       followed by {@code R_LD 8 %t %t} (length) or {@code R_LEA %t %t ..} (element address)).</li>
 * </ul>
 * Loops are processed outermost first, so a slot that is invariant in an outer loop is copied once for all inner loops.
 * The copy slots are appended below the function's frame ({@code ALLOC} grows by 8 bytes per slot, rounded to 16).
 * Functions with inline assembly are left alone.
 */
public class LoopHoistPass {

    /** the fewest pointer-access starts in one loop for a copy to be worth a register */
    static final int MIN_READS = 2;

    private static final int MAX_LINES = 20000;

    private final boolean enabled;

    /** per function (by name): the frame size before the copy slots were added and the copy slot -> original slot map */
    private final Map<String, Object[]> hoisted = new HashMap<>();

    public LoopHoistPass(boolean enabled) { }

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private static String t(BytecodeStatement l, int i) { return null; }

    private static boolean isLabelDef(BytecodeStatement l) { return false; }

    /** returns the new function text, or null when nothing was hoisted */
    private BytecodeProgram hoistFunction(BytecodeProgram fn) { return null; }

    /**
     * How many bytes the mention of slot operand k of this line may store to (0 = the line only reads it). Known shapes give
     * their width; any other shape is unknown and counts as "everything from the slot upward" (a variable occupies the bytes
     * above its offset, so an unknown access at T can reach any slot at or above T).
     */
    private static long writeWidth(String m, BytecodeStatement l, int k) { return 0L; }

    private static final long UNKNOWN = 1L << 40;

    private static long widthOf(String s) { return 0L; }

    /** may any recorded store touch the 8 bytes at slot offset s? */
    private static boolean overlaps(List<long[]> writes, long s) { return false; }

    /** an ADDR_OF/R_ARGA at or below the slot (within 4 KiB) may be a pointer to an object containing it */
    private static boolean isExposed(List<Long> exposedOffsets, long s) { return false; }

    /**
     * Runs after {@link RegVarPromotionPass}. A copy slot that got no register is undone (reads point at the original slot again, the
     * copy line goes, the frame returns to its old size): a copy that stays in memory only adds a move per loop entry and spreads
     * the frame over more cache lines. A copy that did get a register no longer needs frame space, so the frame is shrunk back in
     * every case. Then, for the copies that live in registers, a copy into a temp that the next line immediately overwrites is
     * dropped,
     * <pre>
     *   R_MOV 8 %tA %vK ; R_LD n %tA %tA              ->  R_LD n %tA %vK
     *   R_MOV 8 %tA %vK ; R_LEA %tA %tA idx sc [disp] ->  R_LEA %tA %vK idx sc [disp]     (idx is not %tA)
     * </pre>
     * (the temp is redefined by the second line, so nothing else can have read the copy).
     */
    @SuppressWarnings("unchecked")
    public BytecodeProgram finish(BytecodeProgram lines) { return null; }

    private static BytecodeProgram finishFunction(BytecodeProgram fn, long origAlloc, Map<String, String> copyOf) { return null; }

    private static boolean isSlot(String s) { return false; }

    /** the read at i is directly followed by "R_LD 8 %t %t" (length) or "R_LEA %t %t ..." (element address) on the same temp */
    private static boolean startsAccess(BytecodeProgram cur, int i, int bound) { return false; }

    /** true when no jump from outside [h,b] lands on a label inside it (the header itself is checked by the caller) */
    private static boolean singleEntry(BytecodeProgram fn, LoopHoistLoop lp, Map<String, Integer> labelDef) { return false; }

    private static BytecodeStatement mk(BytecodeToken ref, String... texts) { return null; }

    private static int compareLoops(LoopHoistLoop x, LoopHoistLoop y) { return 0; }

    /** Stable insertion sort of the list by compareLoops. */
    private static void sortLoops(List<LoopHoistLoop> list) { }
}

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
 * The hidden range of a `for` loop, split so its END can live in a variable register (runs on the STACK-form text, after
 * RangeCheckFusionPass and before RegisterFormPass; only when both "deferred-operands" and "variables-in-registers" are on).
 *
 * <p>A `for i in a..b` loop builds its range as one 16-byte value in a hidden frame slot and tests `i < end` on every iteration:
 *
 * <pre>
 *   ADDR 16 $r ; &lt;a&gt; ; &lt;b&gt; ; ASSIGN 16 16 16          ($r = start, $r+8 = end: the first pushed word is the lower one)
 *   ...  PUSH 8 $i ; PUSH 8 $r+8 ; LT_INT 8 ; CMP ; JMP @for_end
 * </pre>
 *
 * Nothing names `$r+8` as a variable, so the register promotion never saw it and every iteration read the end from memory.
 * This pass rewrites the construction into two 8-byte stores (the memory layout is unchanged, so every other reader still
 * sees the same bytes):
 *
 * <pre>
 *   ADDR 8 $r ; &lt;a&gt; ; ASSIGN 8 8 8 ; ADDR 8 $r+8 ; &lt;b&gt; ; ASSIGN 8 8 8
 * </pre>
 *
 * and adds a "REGHINT $r+8 8 weight" line (right after the function's ALLOC, where the Optimizer's hints are), so
 * RegVarPromotionPass treats the end like any other hot scalar. The promotion's own all-or-nothing rule applies: if the
 * slot stays in memory the program is the same as before, only with two 8-byte stores instead of one 16-byte one.
 *
 * <p>Conditions (anything else is left alone): the construction is exactly ADDR 16 $r, two complete 8-byte value
 * expressions built only from `PUSH 8 x` and size-8 `ADD_INT`/`SUB_INT`/`MUL_INT`/`SHR`/`SHL`/`SAR`/`BITS_AND`/`BITS_OR`/`BITS_XOR` (so their stack effect is
 * known and neither can read the half-written range), then ASSIGN 16 16 16; every OTHER mention of `$r` in the function is
 * `PUSH 8 $r` or another construction of this same shape; every other mention of `$r+8` is `PUSH 8 $r+8`; no token names an
 * offset strictly between $r and $r+16 except those two. The weight of the hint is the sum over the mentions of `$r+8` of
 * 8^min(loopDepth, 4), a backward jump to an earlier label marking a loop (the same rule as the Optimizer's hints); a weight
 * below 8 gets no hint.
 */
public class RangeEndHintPass {

    private static final Set<String> BIN = new HashSet<>(java.util.Arrays.asList("ADD_INT", "SUB_INT", "MUL_INT", "SHR", "SHL", "SAR", "BITS_AND", "BITS_OR", "BITS_XOR"));

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private BytecodeProgram function(BytecodeProgram fn) { return null; }

    /**
     * -1 when the slot cannot be treated this way, else the weight of its end slot. Every other mention of $ro must be PUSH 8 $ro,
     * every mention of $ro+8 PUSH 8 $ro+8 (or the construction itself), and no token may name an offset strictly inside the 16 bytes.
     */
    private static long usable(BytecodeProgram fn, long ro, List<RangeEndHintSite> sites, int[] depth) { return 0L; }

    private static long w(int depth) { return 0L; }

    /** ADDR 16 $r ; expression ; expression ; ASSIGN 16 16 16 starting at i, or null */
    private static RangeEndHintSite match(BytecodeProgram fn, int i) { return null; }

    private static boolean isMn(BytecodeStatement l, String m) { return false; }

    private static String t(BytecodeStatement l, int i) { return null; }

    private static boolean isPush8(BytecodeStatement l) { return false; }

    private static Long num(String s) { return null; }

    private static Long slotOff(String s) { return null; }

    private static BytecodeStatement mk(BytecodeToken ref, String... texts) { return null; }
}

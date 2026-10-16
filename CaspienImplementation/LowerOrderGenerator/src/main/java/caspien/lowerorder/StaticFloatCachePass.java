package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.bytecode.BytecodeTokenKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only float statics in spare xmm registers for the length of a call-free loop. Runs after {@link FloatIntrinsicPass}
 * (so a libm sqrt is already one instruction and no longer a call), only with "float-variables-in-registers: on".
 *
 * <p>In a loop that reads a `let static` float several times and never writes it, every read is a load
 * ({@code R_LDX n %yK &g}). This pass loads the static once just before the loop header ({@code R_LDX n %zK &g}) and turns the
 * reads inside the loop into register copies ({@code R_XMOV %yK %zK}). {@code %z0..%z3} are xmm0-3 (argument registers: free in
 * a loop without calls); {@code %x} registers above the highest one the function uses join the pool.
 *
 * <p>A loop qualifies only when ALL of these hold:
 * <ul>
 *   <li>every line in it is one of a small whitelist of register-form ops (no call, no spill/reload, no stack-form op that
 *       might use xmm0/xmm1 as its own scratch);</li>
 *   <li>the header label is jumped to only from inside the loop and no label inside is jumped to from outside;</li>
 *   <li>the static is "plain" program-wide: its address appears only as the address operand of R_LDX/R_STX/R_LD/R_ST (never
 *       as a pointer, an array base or an argument, so nothing can reach it any other way) and is not written in the loop;</li>
 *   <li>the program has no inline assembly and no thread start ({@code par_call}/{@code await_call}/{@code pthread}), so
 *       another thread cannot change the static under the loop;</li>
 *   <li>the static is read at least twice in the loop.</li>
 * </ul>
 * Loops are taken outermost first; registers an enclosing loop uses are not reused inside it.
 */
public class StaticFloatCachePass {

    private static final Set<String> OPS = new HashSet<>(Arrays.asList(
            "R_MOV", "R_LD", "R_LDI", "R_LDD", "R_ST", "R_STI", "R_STD", "R_LEA", "R_BIN", "R_BRC", "R_BRCM", "R_FBIN", "R_FBINX",
            "R_FCMP", "R_BRF", "R_LDX", "R_STX", "R_FSQRT", "R_XMOV", "R_XTOG", "R_GTOX", "R_RMW", "JMP"));
    private static final Set<String> ADDR_OPS = new HashSet<>(Arrays.asList("R_LDX", "R_STX", "R_LD", "R_ST"));
    private static final int Z_COUNT = 4;
    private static final int X_COUNT = 6;
    private static final int MAX_LINES = 20000;

    private final boolean enabled;

    public StaticFloatCachePass(boolean enabled) { }

    private static String t(BytecodeStatement l, int i) { return null; }

    private static boolean isLabelDef(BytecodeStatement l) { return false; }

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private BytecodeProgram cacheFunction(BytecodeProgram fn, Set<String> escaped) { return null; }

    /** whitelisted ops only, single entry: no jump to the header from outside, none to an inner label from outside */
    private boolean loopOk(BytecodeProgram cur, StaticFloatCacheLoop lp, Map<String, Integer> labelDef, int n) { return false; }

    private static int compareLoops(StaticFloatCacheLoop a, StaticFloatCacheLoop b) { return 0; }

    /** Stable insertion sort of the list by compareLoops. */
    private static void sortLoops(List<StaticFloatCacheLoop> list) { }

    private static int compareByReads(String a, String b, Map<String, Integer> reads) { return 0; }

    /** Stable insertion sort of the list by compareByReads. */
    private static void sortByReads(List<String> list, Map<String, Integer> reads) { }
}

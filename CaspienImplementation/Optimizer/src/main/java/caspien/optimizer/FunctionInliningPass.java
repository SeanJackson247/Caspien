package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.bytecode.BytecodeTokenKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import caspien.bytecode.Text;

/**
 * Function inlining: a direct call "CC_START / args / CALL f / CC_END [PUSH_RET T]" is replaced by the body of f.
 *
 * How a call site is rewritten
 *   - Every argument (the lines before its "POP ARGn T") is assigned to a fresh local that stands for the matching parameter:
 *     "ADDR p__iK T / <argument lines> / ASSIGN T T T". Arguments are evaluated left to right, exactly as before.
 *   - The callee's own locals and labels are renamed per inlined copy (x -> x__iK, @lbl_12 -> @lbl_<fresh>; the numeric-suffix
 *     shape is kept so loop unrolling still recognises a copied `for` loop), and all their ALLOC lines move to the top of the caller.
 *   - A callee that is one straight-line body ending in a single `RET` just leaves its value on the stack (the PUSH_RET of the call
 *     site is dropped). Otherwise each `RET` stores into a result local (found by walking the return expression back with the
 *     operand-stack effect of each line) and jumps to the end of the copy, where the result is pushed. A void `RET` is a jump. A call
 *     whose result is discarded drops the (pure) return expression instead.
 *
 * Throw / catch programs: every function carries `gt_routine_address` (and `gt_error_message`) slots and stages a per-call-site label
 * into its own slot before each call it makes. The two slots are dead scaffolding when the body never mentions them, and are never
 * copied (the caller's own slots stand in: the inlined code writes and reads the caller's, which is exactly what the callee's
 * frame slots would have been copied into on unwind). A callee that stages its own calls, throws (THROW is a no-op marker and is
 * dropped) or unwinds is inlined too: each `GT_UNWIND [MSG]` becomes a `JMP` to the label the CALLER staged for this call (its
 * landing pad or `@catch_N`), the message having been written into the caller's slot already. The callee's own landing pads and
 * catch blocks are copied with renamed labels. The staging triple of the call site is dropped (nothing reads it any more).
 *
 * Never inlined (the callee): a function with any decorator other than @pub / @pure / @recursive / @inline / @throws (so never
 * @async, the ghost-table hooks, par/await/sleep glue), `main`, anything on a call cycle, a function using EXIT / inline assembly /
 * a function-local static, a callee that unwinds at a call site with no staged label (old-form pads in non-throw programs), an
 * a function with a by-value struct, array or dynarray parameter, one that reaches outside itself with a label, and one whose local names collide with tokens the renamer cannot tell apart.
 * A bare `range` parameter is supported: the call site passes it as two words (start, end), which are assigned to the copy's range
 * variable like a `lo..hi` literal; variable names inside range type text (`imut_range(mut_lo,mut_hi)`) are renamed along with the
 * variables. A parameter that the callee declares again as a local of the same type (the loop-converted recursive form) is one variable.
 * A call through a function pointer (INVOKE) is not a `CALL` and an extern is not a function with a body: neither is ever touched.
 * A recursive function has already been turned into a loop by the front end, so it is inlined like any other function.
 *
 * Where a call sits matters: with anything else on the operand stack beneath it (an operand of an enclosing expression) only a
 * straight-line callee of plain scalar operations is inlined; `let x = f(..)` (one address beneath) with a branching callee uses the
 * result local and pushes the address after the copy.
 *
 * Configuration: see InlineConfig (presets off|conservative|balanced|aggressive with size / depth / growth limits).
 */
public class FunctionInliningPass {

    private final InlineConfig cfg;
    private int roundsDone = 0;
    private final Map<String, Long> grown = new HashMap<>();
    private int fresh = -1;
    /** Original size per function and for the program (first round), and lines added so far: the relative budgets. */
    private final Map<String, Long> origSize = new HashMap<>();
    private long origProgram = -1;
    private long totalAdded = 0;
    /** Number of call sites inlined over the whole run (for diagnostics). */
    public int inlinedSites = 0;

    private final Messages messages;

    public FunctionInliningPass() { }

    public FunctionInliningPass(InlineConfig cfg, Messages messages) { }

    // ------------------------------------------------------------------------------------------------------------------
    // small helpers

    private static String mn(BytecodeStatement l) { return null; }

    private static String tok(BytecodeStatement l, int i) { return null; }

    private static boolean is(BytecodeStatement l, int size, String first) { return false; }

    private static boolean isLabelDef(BytecodeStatement l) { return false; }

    private static BytecodeToken retext(BytecodeToken t, String text) { return null; }

    private static BytecodeStatement mk(BytecodeToken ref, String... parts) { return null; }

    private static boolean allDigits(String s) { return false; }

    private static final Set<String> OK_DECORATORS = new HashSet<>(Arrays.asList("@pub", "@pure", "@pure(rt)", "@non(deterministic)", "@recursive", "@inline", "@throws", "@lock"));
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList("EXIT", "EXIT_THREAD",
            "ASM_START", "ASM_END", "RECURSIVE_CALL", "ALLOC_STATIC", "FUNC_START", "FUNC_END", "STRUCT_START", "GLOBAL", "REGVAR", "STRING",
            "EXTERN", "ENUM", "STRUCT_MEMBER", "STRUCT_END", "STRUCT_DECORATE", "STRUCT_PADDING"));
    private static final Set<String> BINARY = new HashSet<>(Arrays.asList("ADD", "SUB", "MUL", "DIV", "MOD", "AND", "OR", "EQ", "NEQ", "LT",
            "GT", "LT_EQ", "GT_EQ", "SHL", "SHR", "ROTL", "ROTR", "BITS_OR", "BITS_AND", "BITS_XOR", "IN", "WITHIN", "LOOKUP", "LOOKUP_LHS", "DOT", "DOT_LHS"));
    private static final Set<String> UNARY = new HashSet<>(Arrays.asList("NEG", "NOT", "BITS_NOT", "SEXT", "ZEXT", "TRUNC", "FCONV", "DEREF", "ADDR_OF",
            "NEG_FLOAT", "LEN", "PROMOTE_F32_TO_F64"));
    private static final Set<String> PUSH1 = new HashSet<>(Arrays.asList("PUSH", "ADDR", "PUSH_FIELDNAME", "ATOMIC_PUSH"));
    /** What a plain scalar body may contain when other operands are live beneath the call. */
    private static final Set<String> SIMPLE = new HashSet<>(Arrays.asList("ALLOC", "ADDR", "PUSH", "ASSIGN", "ADD", "SUB", "MUL", "DIV", "MOD",
            "AND", "OR", "EQ", "NEQ", "LT", "GT", "LT_EQ", "GT_EQ", "NEG", "NOT", "SEXT", "ZEXT", "TRUNC", "FCONV", "SHL", "SHR", "ROTL", "ROTR", "BITS_OR", "BITS_AND", "BITS_XOR", "BITS_NOT",
            "RET", "INC", "DEC"));
    private static final Set<String> BOUNDARY = new HashSet<>(Arrays.asList("ASSIGN", "ATOMIC_ASSIGN", "JMP", "CMP", "ALLOC", "ARG", "RETURNS",
            "FUNC_DECORATE", "FUNC_START", "CC_END", "CC_START", "GT_DESTRUCT", "RET", "POP"));
    private static final Set<String> SCALARS = new HashSet<>(Arrays.asList("u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "f32", "f64",
            "bool", "char"));

    /** A parameter type the inliner can copy by a plain scalar ASSIGN: a scalar, or any pointer. */
    static boolean scalarType(String t) { return false; }

    /** A bare range parameter (`imut_range` / `mut_range`, no bounds in the type): passed as two argument words, start then end. */
    static boolean rangeParam(String t) { return false; }

    private static int wordsOf(String paramType) { return 0; }

    private static final String[] MUT_PREFIXES = {"mut_", "imut_", "indeterminate_"};

    /**
     * A range type's text embeds the names of the variables that bound it (`imut_range(mut_lo,mut_hi)`); when the variables are
     * renamed for an inlined copy the names inside the text must follow, or elision/unrolling would read stale names.
     */
    static String renameRangeText(String x, Map<String, String> local) { return null; }

    // ------------------------------------------------------------------------------------------------------------------
    // callee analysis

    private static final Set<String> NAME_POS = new HashSet<>(Arrays.asList("PUSH", "ADDR", "GT_DESTRUCT", "ATOMIC_PUSH"));

    /**
     * Simulates the operand-stack depth over the body in text order. True when it is 0 at every RET (after the returned value is
     * popped), every statement boundary the walk sees and the end, and every mnemonic has a known effect. A call whose result is ignored
     * leaves its PUSH_RET word on the stack: harmless in a frame of its own (RET resets rsp), but once the body runs inline in the middle of
     * an expression that word sits between the pending operands and the code that consumes them.
     */
    private static boolean stackBalanced(BytecodeProgram code) { return false; }

    private static boolean pureLine(BytecodeStatement l) { return false; }

    /** Walks back from line `tail` over an expression that leaves one value; returns its first line, or -1 if the shape is not understood. */
    private static int walkBack(BytecodeProgram code, int tail) { return 0; }

    private FunctionInliningCallee analyse(BytecodeProgram L, int s, int e, Set<String> cyclic) { return null; }

    // ------------------------------------------------------------------------------------------------------------------
    // the pass

    public PassResult run(BytecodeProgram lines) { return null; }

    private static String unq(String t) { return null; }

    private final Map<String, Integer> forcedSites = new LinkedHashMap<>();
    private final Map<String, String> forcedPos = new HashMap<>();
    private final Map<String, String> forcedWhy = new HashMap<>();

    /**
     * Run once after the optimizer has settled: what @inline did. "[note] file:line - @inline: f inlined at K call sites"; a call to an
     * @inline function that is still a call is a "[warning] ... @inline not honoured at N call sites: <reason>".
     */
    public void reportForced(BytecodeProgram L) { }

    /** Rounds of inlining available to @inline functions (a chain of marked functions needs one round per level). */
    private static final int FORCED_ROUNDS = 8;

    private static boolean anyInlineDecorator(BytecodeProgram lines) { return false; }

    /** One round: every call site whose callee (as it stands now) is eligible is inlined. Returns null if nothing changed. */
    private BytecodeProgram round(BytecodeProgram L) { return null; }

    /** Index of the CC_END matching the CC_START at k, or -1. */
    private static int matchEnd(BytecodeProgram L, int k, int to) { return 0; }

    /** What is on the operand stack beneath a call site whose CC_START would be appended to `out`: 0 = nothing, 1 = one address, 2 = other. */
    private int classify(BytecodeProgram out, int cut, boolean baseClean) { return 0; }

    /** True if a "POP ARGn" / "POP FARGn" of the bracket being built (depth 0 of `out`) has already put a value in an argument register. */
    private static boolean argRegisterLoaded(BytecodeProgram out) { return false; }

    /**
     * regsLoaded: this region sits inside the argument list of an enclosing call that has already loaded an argument register. Inlined code
     * runs with no register protection (a real nested call saves and restores the loaded argument registers around itself; the backend's own
     * block copies, shifts and divides use rdi, rsi, rdx, rcx as scratch), so a call site in that position is never inlined.
     */
    private BytecodeProgram expandRange(BytecodeProgram L, int from, int to, FunctionInliningCtx cx, boolean baseClean, boolean regsLoaded) { return null; }

    /** True if `l` is empty or made only of null-out triples "ADDR name T / PUSH null T / ASSIGN T T T" (a moved owns variable being cleared). */
    private static boolean moveNullOuts(BytecodeProgram l) { return false; }

    /** The label staged for this call site by "ADDR gt_routine_address / PUSH_LABEL X / ASSIGN" (a landing pad or a catch), or null. */
    private String stagedLabel(BytecodeProgram out) { return null; }

    private static boolean boundary(BytecodeStatement l) { return false; }

    private boolean tryInline(FunctionInliningCallee c, BytecodeStatement ccStart, BytecodeProgram args, BytecodeStatement pushRet,
            BytecodeProgram out, int cut, int cls, FunctionInliningCtx cx) { return false; }

    private String renameVar(String name, int inst) { return null; }

    private String renameLabel(String lb, int inst) { return null; }

    private static BytecodeStatement rename(BytecodeStatement l, Map<String, String> local, Map<String, String> lab) { return null; }
}

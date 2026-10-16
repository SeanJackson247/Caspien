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
 * Register-variable promotion -- runs after {@link RegisterFormPass}, on its output, and only when compiler.config says
 * "variables-in-registers: on" (which needs "deferred-operands: on").
 *
 * <p>Input hints: "REGHINT offset size weight", one per scalar local that the Optimizer's RegVarHintPass marked as hot
 * ("REGVAR name weight", turned into a frame offset and width by AddressLoweringPass). The weight is the number of
 * source mentions, each counted 8^loopDepth times. The hint says nothing about WHICH register: this pass chooses.
 *
 * <p>What it does, per function: it picks the (at most {@link #VAR_COUNT}) hinted slots with the highest weight that are
 * <b>safe to promote</b> and renames every operand naming that frame slot ("$off") to a variable register "%vN"
 * (r13, r14 in the backend). A slot is safe only if EVERY mention of it in the function is an operand of a register-form
 * instruction that this pass knows how to rewrite, at exactly the slot's width. Any other mention -- a plain PUSH/ADDR/
 * ASSIGN the register-form pass could not fuse, an R_PUSHA or R_LEA base (the slot's address), a size mismatch --
 * leaves that slot in memory. Because the decision is made on the FINAL text there is nothing to roll back: the rename
 * is a pure change of where the value lives, line for line. (The Optimizer already excluded every variable whose
 * address is taken.)
 *
 * <p>The whole function is skipped when it contains an instruction whose backend code uses r13/r14 as its own scratch
 * ({@link #R13_R14_USERS}), or inline assembly.
 *
 * <p>Rewrites (the variable register always holds the value zero-extended to 64 bits, like a temp):
 * <pre>
 *   R_LD n %tD $x        -> R_MOV 8 %tD %vK
 *   R_ST n $x src        -> R_MOV 8 %vK src            (n == 8)
 *                        -> R_SETV n %vK src           (n &lt; 8: truncate, zero-extend)
 *   any other operand    -> the same line with $x replaced by %vK
 * </pre>
 * The backend maps %vN to a callee-saved register; every function that touches one already saves/restores it
 * (X86Backend.finishCalleeSaved), so a value held across a call, a throw or a thread start is safe.
 *
 * <p>Always strips REGHINT lines (and any stray REGVAR), so Codegen never sees them and, with the switch off, the output
 * is byte-identical to a build without this feature.
 *
 * <p><b>Float variables</b> ("float-variables-in-registers: on"): a hint carrying the trailing "f" marker (an f32 local) is
 * kept in an xmm register "%xK" (at most {@link #XVAR_COUNT} per function, chosen by weight) instead of competing for r13/r14.
 * The same all-or-nothing rule applies (every mention of the slot must be renameable; see {@link #mentionOkX}). Rewrites:
 * <pre>
 *   R_LD n %tD $x          -> R_XTOG n %tD %xK            (movd / movq; n = 4 or 8)
 *   R_ST n $x src          -> R_GTOX n %xK src
 *   PUSH n $x              -> PUSH n %xK                  (the backend pushes the zero-extended bits, as before)
 *   R_FBIN/R_FCMP/R_ARG/R_FARG/R_RETF ... $x ...  -> the same line with %xK (the backend reads the register directly)
 *   ADDR 4 $x / CC_START .. CC_END / PUSH_RET_FLOAT 4 / ASSIGN 4 4 4  ->  R_GETRETF 4 %xK after the call   (x = f(...))
 *   R_LD n %t addr ; R_GTOX n %xK %t          -> R_LDX n %xK addr         (temp dead afterwards)
 *   R_FBIN OP n %t a b ; R_GTOX n %xK %t      -> R_FBINX OP n %xK a b     (temp dead afterwards)
 *   R_XTOG n %t %xK ; R_ST n addr %t          -> R_STX n addr %xK
 * </pre>
 * Each function gets "R_XVAR %xK $home" declarations (its frame slot, for the ABIs whose xmm registers do not survive a call:
 * the backend spills/reloads around every call) and an "R_XRELOAD" right after every "@catch_" label (control arrives there
 * from an unwind). Functions with inline assembly are never touched; a function that only uses r13/r14 as backend scratch
 * (NEW, CLONE, ...) can still have float variables, since no backend sequence uses the variable xmm registers.
 */
public class RegVarPromotionPass {

    /** how many variable registers exist (%v0, %v1 -> r13, r14); a function that never needs a fourth temporary also gets %v2 -> r12 */
    public static final int VAR_COUNT = 2;

    /** the most variable registers any function can get (%v2 shares r12 with the fourth temporary %t3) */
    public static final int VAR_COUNT_MAX = 3;

    /** how many float variable registers exist (%x0..%x5 -> xmm8-13 on SysV, xmm6-11 on win64) */
    public static final int XVAR_COUNT = 6;

    /**
     * Variable registers 3..5 are r8, r9 and r10: caller-saved (r8/r9 are argument registers, r10 the INVOKE target and float-result
     * stash), so a variable may sit there only if it is never live at or across a line that could clobber them. Only the lines in
     * {@link #VOL_SAFE} are known not to (checked against the backend: none of them touches r8, r9 or r10). Used only when more
     * variables want registers than the callee-saved ones provide.
     */
    public static final int VOL_FIRST = 3;
    public static final int VOL_COUNT = 7;
    /** how many of the VOL_COUNT registers are the always-available r8-r10 (the first three); the rest (%v6, %v7 = rsi, rdi, SysV argument registers 1 and 0; %v8, %v9 = rdx, rcx, SysV argument registers 2 and 3, SysV only) need the argRegs switch */
    static final int VOL_BASE = 3;

    static final Set<String> VOL_SAFE = new HashSet<>(Arrays.asList(
            "R_MOV", "R_LD", "R_ST", "R_BIN", "R_UN", "R_DIVC", "R_POPV", "R_GETRET", "R_BRC", "R_BRF", "R_LEA", "R_RMW", "R_SETV", "R_LDX", "R_STX", "R_FBIN",
            "R_FBINX", "R_FCMP", "R_XTOG", "R_GTOX", "R_XMOV", "R_PUSH", "R_PUSHA", "R_XVAR", "R_RET", "R_RETF", "JMP", "CMP",
            "ALLOC", "FUNC_START", "FUNC_END"));

    /** Mnemonics whose backend code uses r13/r14 internally (CLONE, DOT of wide fields, LOOKUP_ARRAY, NEW*, RESIZE*) or that run arbitrary code. */
    static final Set<String> R13_R14_USERS = new HashSet<>(Arrays.asList(
            "NEW", "NEW_DYN", "NEW_UDYN", "NEW_FROM_STRING", "NEW_FROM_USTRING", "CLONE", "CLONE_DYN", "RESIZE", "URESIZE",
            "DOT", "LOOKUP_ARRAY", "ASM_START", "ASM_END"));

    private final boolean enabled;
    private final boolean floatEnabled;
    /** `variables-in-arg-registers: on`: rsi and rdi may also hold variables (call-free ranges that never touch an argument bracket) */
    private final boolean argRegs;
    /** functions with scratch-using instructions (NEW, RESIZE, DOT, ...) may keep variables in r12-r14: the backend saves them around those lines */
    private final boolean allocFunctions;

    public RegVarPromotionPass(boolean enabled) { }

    public RegVarPromotionPass(boolean enabled, boolean floatEnabled) { }

    public RegVarPromotionPass(boolean enabled, boolean floatEnabled, boolean allocFunctions) { }

    public RegVarPromotionPass(boolean enabled, boolean floatEnabled, boolean allocFunctions, boolean argRegs) { }

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    /** true when the function text mentions the fourth temporary (%t3 = r12) anywhere */
    private static boolean usesTemp3(BytecodeProgram fn) { return false; }

    private static boolean isHint(BytecodeStatement line) { return false; }

    private void processFunction(BytecodeProgram fn, BytecodeProgram out) { }

    /** True for a jump or branch line whose target is a `@catch_` label (a direct jump, not an unwind through a call). */
    private static boolean jumpsToCatchLabel(BytecodeStatement l) { return false; }

    /** Mnemonics that may transfer control into this function's landing pads / catch labels (the callee unwinds into them). */
    private static final Set<String> MAY_UNWIND = new HashSet<>(Arrays.asList("CALL", "INVOKE", "EXTERN_CALL", "SLEEP"));

    /** Mnemonics after which control does not continue to the next line. */
    private static final Set<String> NO_FALLTHROUGH = new HashSet<>(Arrays.asList(
            "RET", "RET_FLOAT", "R_RET", "R_RETF", "GT_UNWIND", "EXIT", "EXIT_THREAD", "FUNC_END"));

    private static boolean isLabelDefLine(BytecodeStatement l) { return false; }

    /**
     * Register sharing. Two candidate variables may live in the same register only if no program point has both live, and neither is
     * written while the other is still live (a write to a variable that is never read still overwrites the register). Liveness is the
     * usual backward dataflow over the lines of the function (fallthrough, JMP, R_BRC/R_BRF, stack-form CMP+JMP, and an edge from every
     * call to every label whose address the function stages for unwinding). A line counts as a WRITE only when it is a plain store of
     * the whole slot (R_ST, R_MOV with the slot as destination, the assignment that ends a float "ADDR x ... ASSIGN" pair); every other
     * mention is a read, which can only make a range longer, never shorter. Candidates are coloured heaviest first; one that finds no
     * register free of its neighbours stays in memory. Floats of different width never share a register (the backend spills a register
     * with one width). Sets {@code Hint.reg} (-1 = not promoted).
     */
    private void shareRegisters(BytecodeProgram fn, Map<Long, RegVarPromotionHint> hints, List<int[]> pairs,
                                List<RegVarPromotionHint> intCands, List<RegVarPromotionHint> xCands, int intLimit) { }

    /** Integer colouring: callee-saved registers 0..intLimit-1, and for variables that may (volOk) also r8/r9/r10 as 3..5. */
    private static void colourInt(List<RegVarPromotionHint> all, List<RegVarPromotionHint> cands, java.util.BitSet[] adj, Map<Long, Integer> idOf, int intLimit,
                                  boolean[] volRegOk) { }

    private static void colour(List<RegVarPromotionHint> all, List<RegVarPromotionHint> cands, java.util.BitSet[] adj, Map<Long, Integer> idOf, int regCount, boolean xmm) { }

    private static long pairSlotOf(BytecodeProgram fn, int addrIdx, Map<Long, RegVarPromotionHint> hints) { return 0L; }

    private static BytecodeStatement mk(BytecodeToken ref, String... texts) { return null; }

    /**
     * Peephole over the rewritten function: a value that is computed into a temp and immediately moved into a float variable
     * is computed straight into the variable's register instead (the temp must be dead afterwards, see {@link #tempDeadAfter}).
     */
    private static BytecodeProgram fuse(BytecodeProgram res) { return null; }

    /** stack-form mnemonics that may sit between "ADDR 4 $x" and its "ASSIGN 4 4 4" without touching the pushed address */
    private static final java.util.Set<String> VALUE_OPS = new HashSet<>(Arrays.asList(
            "PUSH", "PUSH_RET_FLOAT", "PUSH_RET_INT", "PUSH_LABEL", "ADD_FLOAT", "SUB_FLOAT", "MUL_FLOAT", "DIV_FLOAT",
            "ADD_INT", "SUB_INT", "MUL_INT", "CC_START", "CC_END", "CALL", "VARARGS_XMM_COUNT", "PROMOTE_F32_TO_F64", "FCONV", "NEG_FLOAT",
            // pure stack operators of integer value computations (each pops only its own operands, pushes one result)
            "DIV_INT", "MOD_INT", "SDIV_INT", "SMOD_INT", "SHL", "SHR", "SAR", "ROTL", "ROTR", "BITS_AND", "BITS_OR", "BITS_XOR", "BITS_NOT", "NEG", "NOT",
            "INC_INT", "DEC_INT", "EQ_INT", "NEQ_INT", "LT_INT", "LT_EQ_INT", "GT_INT", "GT_EQ_INT", "SLT_INT", "SLT_EQ_INT", "SGT_INT",
            "SGT_EQ_INT", "AND", "OR", "ZEXT", "TRUNC", "DEREF", "LEN", "LOOKUP_DYN", "LOOKUP_ARRAY", "DOT", "PUSH_FIELDNAME", "DOT_LHS",
            "LOOKUP_DYN_LHS", "LOOKUP_ARRAY_LHS"));

    private static final java.util.Set<String> BLOCK_END = new HashSet<>(Arrays.asList(
            "JMP", "R_BRF", "R_RET", "R_RETF", "RET", "RET_FLOAT", "GT_UNWIND", "THROW", "CC_START", "CC_END", "CALL", "INVOKE", "FUNC_END"));

    /**
     * True when temp t is not read again after position from: scan forward; a read means "live", a write first means "dead", and the
     * end of the straight-line run (a label, a jump, a call bracket, a return) means "dead" too -- the register-form pass flushes
     * its operand model at all of those, so no temp is ever live across them.
     */
    private static boolean tempDeadAfter(BytecodeProgram res, int from, String t) { return false; }

    /** operand position of the destination temp of a register-form instruction, or -1 */
    private static int dstIndex(String m) { return 0; }

    private static RegVarPromotionHint slotHint(Map<Long, RegVarPromotionHint> hints, String tok) { return null; }

    private static int intOf(String s) { return 0; }

    /** May operand k of this line name the hinted slot h (and be renamed to its register)? */
    private static boolean mentionOk(String m, BytecodeStatement l, int k, RegVarPromotionHint h) { return false; }

    /** May operand k of this line name the float-variable slot h (and be renamed to its xmm register)? */
    private static boolean mentionOkX(String m, BytecodeStatement l, int k, RegVarPromotionHint h) { return false; }

    private static BytecodeStatement rewrite(BytecodeStatement l, Map<Long, RegVarPromotionHint> chosenInt, Map<Long, RegVarPromotionHint> chosenX) { return null; }

    private static int compareByWeight(RegVarPromotionHint a, RegVarPromotionHint b) { return 0; }

    /** Stable insertion sort of the list by compareByWeight. */
    private static void sortByWeight(List<RegVarPromotionHint> list) { }
}

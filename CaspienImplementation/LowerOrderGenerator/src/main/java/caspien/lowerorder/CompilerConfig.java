package caspien.lowerorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and parses this project's own copy of "compiler.config" -- a
 * hand-duplicated copy of the sibling `caspien-compiler` project's own
 * class of the identical name and identical parser, confirmed directly
 * this is the right way to keep the two projects working off the same
 * calling-convention numbers without a cross-project import: "they are
 * all to work off the same config file... it can just be duplicated
 * for now it should be a small file, easy to keep in sync." This
 * project still has zero imports of `caspien.*` (the compiler's own
 * package) -- see this project's CLAUDE.md, "What this project is" --
 * so the two `CompilerConfig` classes are two independent, hand-kept-
 * in-sync copies of the same tiny parser, not a shared dependency.
 *
 * `AddressLoweringPass` is this project's only consumer: it needs a
 * function's own resolved calling convention's `argumentRegisters`
 * count (to decide whether a given `PUSH ARGn` word is register- or
 * stack-transferred) and `shadowStack` size (to compute a stack-
 * transferred word's own real, positive base-pointer-relative offset)
 * -- see that pass's own "Address lowering" section in CLAUDE.md.
 *
 * Expected shape (identical to the compiler's own copy -- see that
 * project's `CompilerConfig.java` for the full grammar notes; not
 * repeated here to avoid the two doc comments drifting out of sync
 * with each other independently of the two parsers themselves):
 *
 *     CallingConventions:
 *         win64:
 *             cleanup: caller
 *             argument-registers: ["RCX", "RDX", "R8", "R9"]
 *             argument-registers-float: ["XMM0", "XMM1", "XMM2", "XMM3"]
 *             return-register: "RAX"
 *             return-register-float: "XMM0"
 *             alignment: 16
 *             shadow-stack: 32
 *         sysv_x64:
 *             ...
 *         default: win64
 *
 * A fixed path ("compiler.config", resolved relative to the current
 * working directory), hard-required: missing entirely is a fatal
 * error, never a silently-applied built-in fallback -- the identical
 * "hard-required, no fallback" contract the compiler side's own copy
 * already follows. `#` starts a whole-line comment (only at the start
 * of a line, after its own leading indentation). Blank lines are
 * ignored anywhere. No regex anywhere, matching this project's own
 * "no regex, ever" rule (the compiler side's own copy already follows
 * the identical rule, for the identical reason).
 */
public class CompilerConfig {

    public final Map<String, CompilerConfigCallingConvention> callingConventions = new LinkedHashMap<>();
    public String defaultConvention;
    /** Optional top-level 'deferred-operands: on|off' (default off): enables the LowerOrderGenerator's RegisterFormPass. */
    public boolean deferredOperands = false;
    /** Optional top-level \'variables-in-registers: on|off\' (default off; needs deferred-operands: on): promote REGVAR-hinted scalar locals to registers. */
    public boolean variablesInRegisters = false;
    /** Optional top-level 'float-variables-in-registers: on|off' (default off; needs variables-in-registers: on): keep hot f32 locals in xmm registers instead of memory. */
    public boolean floatVariablesInRegisters = false;
    /** Optional top-level 'float-temporaries-in-registers: on|off' (default off; needs deferred-operands: on): f32 expression temporaries stay in xmm registers instead of round-tripping through general registers. */
    public boolean floatTemporariesInRegisters = false;
    /** Optional top-level 'hoist-array-bases: on|off' (default off; needs variables-in-registers: on): LoopHoistPass copies loop-invariant array base pointers into register candidates. */
    public boolean hoistArrayBases = false;
    /** Optional top-level 'variables-in-alloc-functions: on|off' (default off; needs variables-in-registers: on): functions that contain NEW/RESIZE/CLONE/DOT/LOOKUP_ARRAY may keep variables in r12-r14 (the backend saves them around those instructions). */
    public boolean variablesInAllocFunctions = false;
    /** Optional top-level 'fuse-length-compare: on|off' (default off; needs deferred-operands: on): LengthCompareFusionPass folds the safe dynarray length load into the bounds compare (R_BRCM). */
    public boolean fuseLengthCompare = false;
    /** Optional top-level 'variables-in-arg-registers: on|off' (default off; needs variables-in-registers: on): rsi and rdi may hold variables in call-free live ranges (RegVarPromotionPass %v6, %v7). */
    public boolean variablesInArgRegisters = false;
    /** Optional top-level 'conditional-move: on|off' (default off): ConditionalMovePass turns a compare-and-branch around a move of a register variable (one arm or two) into a cmov. Needs deferred-operands. */
    public boolean conditionalMove = false;
    /** Optional top-level 'copy-forward: on|off' (default off): CopyForwardPass replaces the reads of a temporary that only holds a copy of a promoted variable by the variable itself and deletes the copy. */
    public boolean copyForward = false;
    /** Optional top-level 'loop-rotation: on|off' (default off): LoopRotationPass copies the exit test of a for/loop to the bottom of the loop (one conditional jump per iteration instead of a conditional plus an unconditional one). */
    public boolean loopRotation = false;

    public CompilerConfigCallingConvention getDefault() { return null; }

    /** @param content the text of `compiler.config`; @param path its name, for error messages */
    public static CompilerConfig parse(String content, String path) { return null; }

    // ---- hand-written scanning helpers -- no regex anywhere, matching this project's own rule ----

    private static List<String> splitLines(String content) { return null; }

    private static String stripCrAndComment(String line) { return null; }

    private static int leadingWhitespace(String s) { return 0; }

    private static boolean startsWithLiteral(String s, String prefix) { return false; }

    private static int parseIntField(String text, String path, int lineNo, String field) { return 0; }

    private static boolean parseBoolField(String text, String path, int lineNo, String field) { return false; }

    private static List<String> parseStringList(String text, String path, int lineNo) { return null; }

    private static String unquoteOrBare(String text) { return null; }

    private static RuntimeException configErr(String path, int lineNo, String message) { return null; }
}

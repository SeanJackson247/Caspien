package caspien.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and parses compiler.config -- a small, deliberately narrow,
 * hand-written parser for exactly this project's one fixed shape.
 * Never general YAML, confirmed directly: "these config files will
 * never be open ended in a way that would require that sort of
 * thing" -- matching this project's own "no regex, anywhere, ever"
 * and "no third-party dependencies" conventions, every character is
 * scanned by hand (no String.split/replaceAll/matches, all of which
 * are regex-backed even for a literal-looking pattern).
 *
 * Expected shape (indentation is whatever the file itself uses --
 * this parser measures it dynamically from the first line at each
 * level, rather than assuming a fixed width):
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
 * working directory -- see Main), hard-required: missing entirely is
 * a fatal error, confirmed directly, never a silently-applied
 * built-in fallback. `#` starts a whole-line comment (only at the
 * start of a line, after its own leading indentation -- this format
 * never needs a value containing '#', so no quote-awareness is
 * needed for it). Blank lines are ignored anywhere.
 */
public class CompilerConfig {



    public final Map<String, CallingConvention> callingConventions = new LinkedHashMap<>();
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

    public CallingConvention getDefault() { return null; }

    /** Parses the text of `compiler.config` (required: calling conventions); `path` names it in error messages. Reads no file. */
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

    private static CompilerException configErr(String path, int lineNo, String message) { return null; }
}

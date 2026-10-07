package caspien.lowerorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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

    /** One named entry under "CallingConventions:" -- every field mirrors the config file's own key names. `argumentRegisters.size()`/`argumentRegistersFloat.size()`, `shadowStack`, and `sharedArgumentPosition` are read by `AddressLoweringPass`'s own per-function parameter-word resolution; the rest are parsed and validated (so a malformed config still fails loudly) but not otherwise consumed here. */
    public static class CallingConvention {
        public String name;
        public String cleanup;
        public List<String> argumentRegisters = new ArrayList<>();
        public List<String> argumentRegistersFloat = new ArrayList<>();
        public String returnRegister;
        public String returnRegisterFloat;
        public int alignment;
        public int shadowStack;
        /** True only for a convention where an integer argument and a float argument advance one shared running position counter (win64's own documented behavior) rather than two independent per-bank counters (SysV, arm64's AAPCS64) -- see the compiler-side `CompilerConfig`'s own copy of this same field for the full reasoning (a real, fixed ABI fact, not derived from the two register lists' lengths). Defaults false when the config omits this key. */
        public boolean sharedArgumentPosition;
    }

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

    public CallingConvention getDefault() {
        return callingConventions.get(defaultConvention);
    }

    public static CompilerConfig load(String path) {
        String content;
        try {
            content = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("required config file '" + path + "' could not be read: " + e.getMessage(), e);
        }
        return parse(content, path);
    }

    private static CompilerConfig parse(String content, String path) {
        List<String> lines = splitLines(content);
        CompilerConfig config = new CompilerConfig();

        int i = 0;
        int lineNo = 0;

        boolean foundHeader = false;
        while (i < lines.size()) {
            lineNo++;
            String raw = stripCrAndComment(lines.get(i));
            i++;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (leadingWhitespace(raw) != 0) {
                throw configErr(path, lineNo, "unexpected indentation before 'CallingConventions:'");
            }
            if (startsWithLiteral(trimmed, "loop-unrolling:") || startsWithLiteral(trimmed, "loop-unroll-") || startsWithLiteral(trimmed, "function-inlining:") || startsWithLiteral(trimmed, "inline-max-")
                    || startsWithLiteral(trimmed, "constant-folding:") || startsWithLiteral(trimmed, "variable-elision:")
                    || startsWithLiteral(trimmed, "variable-shifting:") || startsWithLiteral(trimmed, "struct-unpacking:") || startsWithLiteral(trimmed, "dead-control-flow-removal:") || startsWithLiteral(trimmed, "dead-function-removal:") || startsWithLiteral(trimmed, "unused-declaration-removal:") || startsWithLiteral(trimmed, "variable-allocation-reordering:") || startsWithLiteral(trimmed, "struct-member-reordering:")) {
                // Loop-unrolling and constant-folding settings belong to the Optimizer, which validates them; every stage that reads
                // compiler.config just has to accept them.
                continue;
            }
            if (startsWithLiteral(trimmed, "deferred-operands:")) {
                // Optional switch for the LowerOrderGenerator's RegisterFormPass; parsed here so every stage
                // that reads compiler.config accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("deferred-operands:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'deferred-operands' must be 'on' or 'off', found '" + v + "'");
                }
                config.deferredOperands = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "variables-in-alloc-functions:")) {
                // Optional switch: RegVarPromotionPass may use r12-r14 in functions with scratch-using instructions; parsed here so every stage accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("variables-in-alloc-functions:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'variables-in-alloc-functions' must be 'on' or 'off', found '" + v + "'");
                }
                config.variablesInAllocFunctions = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "variables-in-arg-registers:")) {
                // Optional switch: RegVarPromotionPass (LowerOrderGenerator); parsed here so every stage that reads compiler.config accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("variables-in-arg-registers:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'variables-in-arg-registers' must be 'on' or 'off', found '" + v + "'");
                }
                config.variablesInArgRegisters = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "loop-rotation:")) {
                String v = unquoteOrBare(trimmed.substring("loop-rotation:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'loop-rotation' must be 'on' or 'off', found '" + v + "'");
                }
                config.loopRotation = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "copy-forward:")) {
                String v = unquoteOrBare(trimmed.substring("copy-forward:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'copy-forward' must be 'on' or 'off', found '" + v + "'");
                }
                config.copyForward = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "conditional-move:")) {
                String v = unquoteOrBare(trimmed.substring("conditional-move:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'conditional-move' must be 'on' or 'off', found '" + v + "'");
                }
                config.conditionalMove = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "fuse-length-compare:")) {
                // Optional switch: LengthCompareFusionPass (LowerOrderGenerator); parsed here so every stage that reads compiler.config accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("fuse-length-compare:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'fuse-length-compare' must be 'on' or 'off', found '" + v + "'");
                }
                config.fuseLengthCompare = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "hoist-array-bases:")) {
                // Optional switch: LoopHoistPass (LowerOrderGenerator); parsed here so every stage that reads compiler.config accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("hoist-array-bases:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'hoist-array-bases' must be 'on' or 'off', found '" + v + "'");
                }
                config.hoistArrayBases = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "float-temporaries-in-registers:")) {
                // Optional switch: FloatTempPass keeps f32 expression temporaries in xmm registers; parsed here so every stage
                // that reads compiler.config accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("float-temporaries-in-registers:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'float-temporaries-in-registers' must be 'on' or 'off', found '" + v + "'");
                }
                config.floatTemporariesInRegisters = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "float-variables-in-registers:")) {
                // Optional switch: hot f32 locals (REGVAR hints with the float marker) live in xmm registers; parsed here so every stage
                // that reads compiler.config accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("float-variables-in-registers:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'float-variables-in-registers' must be 'on' or 'off', found '" + v + "'");
                }
                config.floatVariablesInRegisters = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "variables-in-registers:")) {
                // Optional switch: RegisterFormPass keeps hot scalar locals (REGVAR hints) in registers; parsed here so every stage
                // that reads compiler.config accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("variables-in-registers:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'variables-in-registers' must be 'on' or 'off', found '" + v + "'");
                }
                config.variablesInRegisters = v.equals("on");
                continue;
            }
            if (!trimmed.equals("CallingConventions:")) {
                throw configErr(path, lineNo, "expected top-level key 'CallingConventions:', found '" + trimmed + "'");
            }
            foundHeader = true;
            break;
        }
        if (!foundHeader) {
            throw configErr(path, lineNo, "missing top-level 'CallingConventions:' key");
        }

        int conventionIndent = -1;
        int fieldIndent = -1;
        CallingConvention current = null;

        while (i < lines.size()) {
            lineNo++;
            String raw = stripCrAndComment(lines.get(i));
            i++;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int indent = leadingWhitespace(raw);

            if (indent == 0) {
                throw configErr(path, lineNo,
                        "unexpected top-level content '" + trimmed + "' after 'CallingConventions:'");
            }

            if (conventionIndent == -1) {
                conventionIndent = indent;
            }

            if (indent == conventionIndent) {
                current = null;
                if (startsWithLiteral(trimmed, "default:")) {
                    String value = trimmed.substring("default:".length()).trim();
                    config.defaultConvention = unquoteOrBare(value);
                    continue;
                }
                if (!trimmed.endsWith(":")) {
                    throw configErr(path, lineNo,
                            "expected a calling-convention name ('name:') or 'default: name', found '"
                                    + trimmed + "'");
                }
                String name = trimmed.substring(0, trimmed.length() - 1).trim();
                if (config.callingConventions.containsKey(name)) {
                    throw configErr(path, lineNo, "calling convention '" + name + "' is already declared");
                }
                current = new CallingConvention();
                current.name = name;
                config.callingConventions.put(name, current);
                continue;
            }

            if (fieldIndent == -1) {
                fieldIndent = indent;
            }
            if (indent != fieldIndent) {
                throw configErr(path, lineNo, "inconsistent indentation");
            }
            if (current == null) {
                throw configErr(path, lineNo, "a field must be indented under a calling-convention name");
            }

            int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0) {
                throw configErr(path, lineNo, "expected 'field: value', found '" + trimmed + "'");
            }
            String key = trimmed.substring(0, colonIdx).trim();
            String valueText = trimmed.substring(colonIdx + 1).trim();
            switch (key) {
                case "cleanup":
                    current.cleanup = unquoteOrBare(valueText);
                    break;
                case "argument-registers":
                    current.argumentRegisters = parseStringList(valueText, path, lineNo);
                    break;
                case "argument-registers-float":
                    current.argumentRegistersFloat = parseStringList(valueText, path, lineNo);
                    break;
                case "return-register":
                    current.returnRegister = unquoteOrBare(valueText);
                    break;
                case "return-register-float":
                    current.returnRegisterFloat = unquoteOrBare(valueText);
                    break;
                case "alignment":
                    current.alignment = parseIntField(valueText, path, lineNo, "alignment");
                    break;
                case "shadow-stack":
                    current.shadowStack = parseIntField(valueText, path, lineNo, "shadow-stack");
                    break;
                case "shared-argument-position":
                    current.sharedArgumentPosition = parseBoolField(valueText, path, lineNo, "shared-argument-position");
                    break;
                default:
                    throw configErr(path, lineNo, "unknown calling-convention field '" + key + "'");
            }
        }

        if (config.callingConventions.isEmpty()) {
            throw configErr(path, lineNo, "'CallingConventions:' declares no calling conventions");
        }
        if (config.defaultConvention == null) {
            throw configErr(path, lineNo, "missing 'default: <conventionName>'");
        }
        if (!config.callingConventions.containsKey(config.defaultConvention)) {
            throw configErr(path, lineNo,
                    "'default: " + config.defaultConvention + "' does not name a declared calling convention");
        }
        for (CallingConvention cc : config.callingConventions.values()) {
            if (cc.argumentRegisters.isEmpty()) {
                throw configErr(path, lineNo,
                        "calling convention '" + cc.name + "' has no 'argument-registers'");
            }
        }
        return config;
    }

    // ---- hand-written scanning helpers -- no regex anywhere, matching this project's own rule ----

    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines.add(content.substring(start, i));
                start = i + 1;
            }
        }
        lines.add(content.substring(start));
        return lines;
    }

    private static String stripCrAndComment(String line) {
        String noCr = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        int i = 0;
        while (i < noCr.length() && (noCr.charAt(i) == ' ' || noCr.charAt(i) == '\t')) {
            i++;
        }
        if (i < noCr.length() && noCr.charAt(i) == '#') {
            return noCr.substring(0, i);
        }
        return noCr;
    }

    private static int leadingWhitespace(String s) {
        int n = 0;
        while (n < s.length() && (s.charAt(n) == ' ' || s.charAt(n) == '\t')) {
            n++;
        }
        return n;
    }

    private static boolean startsWithLiteral(String s, String prefix) {
        return s.length() >= prefix.length() && s.substring(0, prefix.length()).equals(prefix);
    }

    private static int parseIntField(String text, String path, int lineNo, String field) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw configErr(path, lineNo, "'" + field + "' expects an integer, got '" + text + "'");
        }
    }

    private static boolean parseBoolField(String text, String path, int lineNo, String field) {
        String trimmed = text.trim();
        if (trimmed.equals("true")) {
            return true;
        }
        if (trimmed.equals("false")) {
            return false;
        }
        throw configErr(path, lineNo, "'" + field + "' expects 'true' or 'false', got '" + trimmed + "'");
    }

    private static List<String> parseStringList(String text, String path, int lineNo) {
        text = text.trim();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            throw configErr(path, lineNo, "expected a '[...]' list, got '" + text + "'");
        }
        String inner = text.substring(1, text.length() - 1).trim();
        List<String> result = new ArrayList<>();
        if (inner.isEmpty()) {
            return result;
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            if (inner.charAt(i) == ',') {
                parts.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(inner.substring(start));
        for (String part : parts) {
            result.add(unquoteOrBare(part.trim()));
        }
        return result;
    }

    private static String unquoteOrBare(String text) {
        text = text.trim();
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static RuntimeException configErr(String path, int lineNo, String message) {
        return new RuntimeException("config error in '" + path + "' at line " + lineNo + ": " + message);
    }
}

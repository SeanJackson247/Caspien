package caspien;

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

    /** One named entry under "CallingConventions:" -- every field mirrors the config file's own key names, in the same order they're documented there. `cleanup`/`alignment`/the return-register fields are parsed and validated but not yet consulted by bytecode emission (same "abstract stack-machine IR, real register assignment is the not-yet-built assembly stage's job" reasoning the rest of this compiler's bytecode format already follows); `argumentRegisters.size()`/`argumentRegistersFloat.size()` (this convention's own per-bank "n") and `sharedArgumentPosition` are read directly by `BytecodeEmitter.emitCallSequence` to decide, per call argument, which bank it draws from and whether it's still register-transferred -- see `sharedArgumentPosition`'s own doc comment for why that split exists at all. */
    public static class CallingConvention {
        public String name;
        public String cleanup;
        public List<String> argumentRegisters = new ArrayList<>();
        public List<String> argumentRegistersFloat = new ArrayList<>();
        public String returnRegister;
        public String returnRegisterFloat;
        public int alignment;
        public int shadowStack;
        /** True only for a convention where an integer argument and a float argument advance one shared running position counter (win64's own documented behavior: "f(int, float, int)" passes its 2nd argument in XMM1, not XMM0, because it's the 2nd argument overall, not the 1st float one) rather than two genuinely independent per-bank counters (SysV, arm64's AAPCS64 -- a float argument never "uses up" an integer register slot there, or vice versa). A real, fixed ABI fact about the convention, confirmed directly against win64's own calling convention rather than inferred from the two register lists' lengths (which happen to both be 4 for win64, but that coincidence isn't what makes it shared -- arm64's own lists are both 8, equally coincidentally, and arm64 is independently-counted). Defaults false (independent counters) when the config omits this key entirely, matching every convention but win64. */
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

    public CallingConvention getDefault() {
        return callingConventions.get(defaultConvention);
    }

    public static CompilerConfig load(String path) {
        String content;
        try {
            content = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CompilerException("config", path, 0,
                    "required config file '" + path + "' could not be read: " + e.getMessage());
        }
        return parse(content, path);
    }

    private static CompilerConfig parse(String content, String path) {
        List<String> lines = splitLines(content);
        CompilerConfig config = new CompilerConfig();

        int i = 0;
        int lineNo = 0;

        // Find the top-level "CallingConventions:" header -- the only
        // top-level key this format has today.
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

    private static CompilerException configErr(String path, int lineNo, String message) {
        return new CompilerException("config", path, lineNo, message);
    }
}

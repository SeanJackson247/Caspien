package caspien;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stack-trace settings of one compiler run (design: stack_trace.md at the repository root).
 *
 * <p>{@code depth} is the number of frames a captured trace keeps ({@code --trace-depth N} on the orchestrator, written to {@code trace.config}
 * as {@code trace-depth: N}, default 16). {@code usesTrace} is the whole-program gate: true exactly when the program, including everything it
 * imports, mentions {@code stack_trace} or {@code funcname} as a name. When it is false the generated code must be byte-identical to a compiler
 * without the feature (test {@code tests/trace_off_check.sh}).
 */
public final class TraceConfig {
    public static final int DEFAULT_DEPTH = 16;
    public static final int MAX_DEPTH = 1024;

    /** Frames kept by a captured trace. */
    public static int depth = DEFAULT_DEPTH;
    /** Set by {@link #scan}: the program mentions {@code stack_trace} or {@code funcname}. */
    public static boolean usesTrace = false;

    private TraceConfig() { }

    public static void load(String file) throws IOException {
        depth = DEFAULT_DEPTH;
        usesTrace = false;
        Path path = Paths.get(file);
        if (!Files.exists(path)) {
            return;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.startsWith("trace-depth:")) {
                String v = t.substring("trace-depth:".length()).trim();
                try {
                    int n = Integer.parseInt(v);
                    if (n < 1 || n > MAX_DEPTH) {
                        throw new NumberFormatException();
                    }
                    depth = n;
                } catch (NumberFormatException e) {
                    throw new IOException("trace.config: trace-depth must be a whole number from 1 to " + MAX_DEPTH + ", got '" + v + "'");
                }
            }
        }
    }

    /** True when {@code name} is one of the two names whose use turns tracing on. */
    static boolean isTraceName(String name) {
        return "stack_trace".equals(name) || "funcname".equals(name);
    }

    /** Looks through the whole token tree (string and character literals excluded) and sets {@link #usesTrace}. */
    public static void scan(List<Token> roots) {
        usesTrace = false;
        Map<Token, Boolean> seen = new IdentityHashMap<>();
        for (Token t : roots) {
            if (walk(t, seen)) {
                usesTrace = true;
                return;
            }
        }
    }

    private static boolean walk(Token t, Map<Token, Boolean> seen) {
        if (t == null || seen.put(t, Boolean.TRUE) != null) {
            return false;
        }
        if (t.type != TokenType.STRING && t.type != TokenType.CHAR && isTraceName(t.text)) {
            return true;
        }
        for (Token c : t.childs) {
            if (walk(c, seen)) {
                return true;
            }
        }
        for (Token c : t.sub) {
            if (walk(c, seen)) {
                return true;
            }
        }
        return walk(t.left, seen) || walk(t.right, seen);
    }
}

package caspien.config;

import caspien.token.Token;
import caspien.token.TokenType;

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

    /** Reads the text of `trace.config` (null = absent: defaults). */
    public static void parse(String text) throws IOException { }

    /** True when {@code name} is one of the two names whose use turns tracing on. */
    static boolean isTraceName(String name) { return false; }

    /** Looks through the whole token tree (string and character literals excluded) and sets {@link #usesTrace}. */
    public static void scan(List<Token> roots) { }

    /** True when a trace name occurs in the tree under t; an explicit stack (order does not matter for a yes/no answer). */
    private static boolean walk(Token start, Map<Token, Boolean> seen) { return false; }
}

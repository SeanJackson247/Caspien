package caspien.optimizer;

import java.util.List;

/**
 * Function-inlining settings, read from the top-level keys of "compiler.config" (the same file, with the same mirrored copy, the other stages
 * read). No other key of that file is looked at, and the file is optional here: a missing file or a missing 'function-inlining' key means
 * inlining is off.
 *
 *   function-inlining: off | conservative | balanced | aggressive      (default off)
 *   inline-max-callee-lines: N     never inline a callee whose body is longer than N bytecode lines
 *   inline-max-depth: N            rounds of inlining: 1 inlines the calls written in each function, 2 also the calls that came in with
 *                                  the first round's bodies, and so on
 *   inline-max-growth: N           at most N added bytecode lines per function, over the whole run
 *   inline-max-growth-factor: N    also at most N times the function's original size (but never below 2000 lines); 0 = no such limit
 *   inline-max-total-factor: N     at most N times the original program size added over all functions (but never below 50000 lines); 0 = none
 *   inline-max-multi-callee-lines: N  a callee longer than N lines is inlined only when the whole program calls it from exactly one
 *                                  place (so inlining adds no code); with several call sites it stays a call (default 1000, 0 = no such
 *                                  rule). Measured: inlining a ~1100-line hash function into a large `main` at three sites made
 *                                  the merkle benchmark 34% slower (register pressure, code size).
 *                                  When a limit is reached the call is simply left as a call (never an error).
 *
 * The preset supplies all three numbers; any of the three keys after it overrides just that number. The keys do nothing while the preset is
 * off. A malformed value stops the compile (never silently ignored).
 */
public final class InlineConfig {

    public boolean enabled = false;
    public String preset = "off";
    public int maxCalleeLines = 0;
    public int maxDepth = 0;
    public long maxGrowth = 0;
    /** Per-caller cap as a multiple of the caller's original size (0 = none); never below GROWTH_FLOOR lines. */
    public long growthFactor = 0;
    /** Program-wide cap on added lines as a multiple of the original program size (0 = none); never below TOTAL_FLOOR lines. */
    public long totalFactor = 0;
    /** A callee longer than this is inlined only if it has a single call site in the program (0 = no such rule). */
    public int maxMultiCalleeLines = 0;
    public static final int DEFAULT_MULTI_CALLEE_LINES = 1000;
    public static final long GROWTH_FLOOR = 2000, TOTAL_FLOOR = 50_000;

    public static InlineConfig disabled() { return null; }

    /** Loads "compiler.config" from the working directory if it exists; otherwise inlining is off. */

    public static InlineConfig parse(List<String> lines, String path) { return null; }

    private void set(int maxCalleeLines, int maxDepth, long maxGrowth) { }

    private static long number(String path, int lineIdx, String key, String val) { return 0L; }

    private static RuntimeException bad(String path, int lineIdx, String msg) { return null; }

    @Override
    public String toString() { return null; }
}

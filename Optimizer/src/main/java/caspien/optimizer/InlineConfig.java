package caspien.optimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    public static final long GROWTH_FLOOR = 2000, TOTAL_FLOOR = 50_000;

    public static InlineConfig disabled() {
        return new InlineConfig();
    }

    /** Loads "compiler.config" from the working directory if it exists; otherwise inlining is off. */
    public static InlineConfig loadFromWorkingDirectory() {
        Path p = Paths.get("compiler.config");
        if (!Files.exists(p)) {
            return disabled();
        }
        try {
            return parse(Files.readAllLines(p, StandardCharsets.UTF_8), p.toString());
        } catch (IOException e) {
            throw new RuntimeException("could not read '" + p + "': " + e.getMessage(), e);
        }
    }

    public static InlineConfig parse(List<String> lines, String path) {
        String preset = "off";
        Integer calleeLines = null, depth = null;
        Long growth = null, gfactor = null, tfactor = null;
        for (int n = 0; n < lines.size(); n++) {
            String raw = lines.get(n);
            int hash = raw.indexOf('#');
            if (hash >= 0) {
                raw = raw.substring(0, hash);
            }
            if (raw.isEmpty() || Character.isWhitespace(raw.charAt(0))) {
                continue; // top-level keys only (the calling-convention block is indented)
            }
            String t = raw.trim();
            int colon = t.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = t.substring(0, colon).trim();
            String val = t.substring(colon + 1).trim();
            if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                val = val.substring(1, val.length() - 1);
            }
            switch (key) {
                case "function-inlining":
                    if (!(val.equals("off") || val.equals("conservative") || val.equals("balanced") || val.equals("aggressive"))) {
                        throw bad(path, n, "'function-inlining' must be off, conservative, balanced or aggressive, found '" + val + "'");
                    }
                    preset = val;
                    break;
                case "inline-max-callee-lines":
                    calleeLines = (int) Math.min(number(path, n, key, val), Integer.MAX_VALUE);
                    break;
                case "inline-max-depth":
                    depth = (int) Math.min(number(path, n, key, val), Integer.MAX_VALUE);
                    break;
                case "inline-max-growth":
                    growth = number(path, n, key, val);
                    break;
                case "inline-max-growth-factor":
                    gfactor = number(path, n, key, val);
                    break;
                case "inline-max-total-factor":
                    tfactor = number(path, n, key, val);
                    break;
                default:
                    break;
            }
        }
        InlineConfig c = new InlineConfig();
        c.preset = preset;
        switch (preset) {
            case "conservative":
                c.set(12, 1, 200);
                break;
            case "balanced":
                c.set(40, 3, 2000);
                break;
            case "aggressive":
                c.set(Integer.MAX_VALUE, 32, 100_000_000L);
                c.growthFactor = 30;
                c.totalFactor = 10;
                break;
            default:
                return c; // off: the numbers are ignored
        }
        c.enabled = true;
        if (calleeLines != null) c.maxCalleeLines = calleeLines;
        if (depth != null) c.maxDepth = depth;
        if (growth != null) c.maxGrowth = growth;
        if (gfactor != null) c.growthFactor = gfactor;
        if (tfactor != null) c.totalFactor = tfactor;
        return c;
    }

    private void set(int maxCalleeLines, int maxDepth, long maxGrowth) {
        this.maxCalleeLines = maxCalleeLines;
        this.maxDepth = maxDepth;
        this.maxGrowth = maxGrowth;
    }

    private static long number(String path, int lineIdx, String key, String val) {
        if (val.isEmpty() || val.length() > 12) {
            throw bad(path, lineIdx, "'" + key + "' needs a whole number, found '" + val + "'");
        }
        long v = 0;
        for (int i = 0; i < val.length(); i++) {
            char ch = val.charAt(i);
            if (ch < '0' || ch > '9') {
                throw bad(path, lineIdx, "'" + key + "' needs a whole number (0 or more), found '" + val + "'");
            }
            v = v * 10 + (ch - '0');
        }
        return v;
    }

    private static RuntimeException bad(String path, int lineIdx, String msg) {
        return new RuntimeException(path + ":" + (lineIdx + 1) + ": " + msg);
    }

    @Override
    public String toString() {
        return enabled ? "function-inlining " + preset + " (callee<=" + maxCalleeLines + " lines, depth " + maxDepth + ", growth<="
                + maxGrowth + ", x" + growthFactor + " per function, x" + totalFactor + " per program)" : "function-inlining off";
    }
}

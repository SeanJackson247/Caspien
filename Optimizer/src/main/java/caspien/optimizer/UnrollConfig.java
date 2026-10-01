package caspien.optimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Loop-unrolling settings, read from the top-level keys of "compiler.config" (the same file, with the same
 * mirrored copy, the other stages read). No other key of that file is looked at, and the file is optional
 * here: a missing file, or a missing 'loop-unrolling' key, means unrolling is off (this stage never needed a
 * config before).
 *
 *   loop-unrolling: off | conservative | balanced | aggressive      (default off)
 *   loop-unroll-factor: N                 partial unroll: N copies of the body per pass (0 or 1 = never partial)
 *   loop-unroll-full-max-trips: N         fully unroll a loop of at most N known iterations (0 = never full)
 *   loop-unroll-max-body-lines: N         never copy a loop body longer than N bytecode lines
 *   loop-unroll-max-growth: N             at most N added bytecode lines per function, over the whole run
 *
 * The preset supplies all four numbers; any of the four keys after it overrides just that number. The four
 * keys do nothing while the preset is off. A malformed value stops the compile (never silently ignored).
 */
public final class UnrollConfig {

    public boolean enabled = false;
    public String preset = "off";
    public int factor = 0;
    public int fullMaxTrips = 0;
    public int maxBodyLines = 0;
    public long maxGrowth = 0;

    public static UnrollConfig disabled() {
        return new UnrollConfig();
    }

    /** Loads "compiler.config" from the working directory if it exists; otherwise unrolling is off. */
    public static UnrollConfig loadFromWorkingDirectory() {
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

    public static UnrollConfig parse(List<String> lines, String path) {
        String preset = "off";
        Integer factor = null, fullTrips = null, bodyLines = null;
        Long growth = null;
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
                case "loop-unrolling":
                    if (!(val.equals("off") || val.equals("conservative") || val.equals("balanced") || val.equals("aggressive"))) {
                        throw bad(path, n, "'loop-unrolling' must be off, conservative, balanced or aggressive, found '" + val + "'");
                    }
                    preset = val;
                    break;
                case "loop-unroll-factor":
                    factor = (int) number(path, n, key, val);
                    break;
                case "loop-unroll-full-max-trips":
                    fullTrips = (int) number(path, n, key, val);
                    break;
                case "loop-unroll-max-body-lines":
                    bodyLines = (int) number(path, n, key, val);
                    break;
                case "loop-unroll-max-growth":
                    growth = number(path, n, key, val);
                    break;
                default:
                    break;
            }
        }
        UnrollConfig c = new UnrollConfig();
        c.preset = preset;
        switch (preset) {
            case "conservative":
                c.set(2, 4, 40, 400);
                break;
            case "balanced":
                c.set(4, 16, 100, 2000);
                break;
            case "aggressive":
                c.set(8, 4096, 2000, 200000);
                break;
            default:
                return c; // off: the four numbers are ignored
        }
        c.enabled = true;
        if (factor != null) c.factor = factor;
        if (fullTrips != null) c.fullMaxTrips = fullTrips;
        if (bodyLines != null) c.maxBodyLines = bodyLines;
        if (growth != null) c.maxGrowth = growth;
        return c;
    }

    private void set(int factor, int fullMaxTrips, int maxBodyLines, long maxGrowth) {
        this.factor = factor;
        this.fullMaxTrips = fullMaxTrips;
        this.maxBodyLines = maxBodyLines;
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
        return enabled ? "loop-unrolling " + preset + " (factor " + factor + ", full<=" + fullMaxTrips + " trips, body<="
                + maxBodyLines + " lines, growth<=" + maxGrowth + ")" : "loop-unrolling off";
    }
}

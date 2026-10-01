package caspien.optimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Constant-folding switch, read from the top-level key of "compiler.config" (the same file the other stages read):
 *
 *   constant-folding: on | off        (default off; a missing file or key means off)
 *
 * A malformed value stops the compile (never silently ignored).
 */
public final class FoldConfig {

    public final boolean enabled;

    public FoldConfig(boolean enabled) {
        this.enabled = enabled;
    }

    public static FoldConfig disabled() {
        return new FoldConfig(false);
    }

    public static FoldConfig loadFromWorkingDirectory() {
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

    public static FoldConfig parse(List<String> lines, String path) {
        boolean on = false;
        for (int n = 0; n < lines.size(); n++) {
            String raw = lines.get(n);
            int hash = raw.indexOf('#');
            if (hash >= 0) {
                raw = raw.substring(0, hash);
            }
            if (raw.isEmpty() || Character.isWhitespace(raw.charAt(0))) {
                continue; // top-level keys only
            }
            String t = raw.trim();
            int colon = t.indexOf(':');
            if (colon <= 0 || !t.substring(0, colon).trim().equals("constant-folding")) {
                continue;
            }
            String val = t.substring(colon + 1).trim();
            if (val.equals("on")) {
                on = true;
            } else if (val.equals("off")) {
                on = false;
            } else {
                throw new RuntimeException(path + ":" + (n + 1) + ": 'constant-folding' must be on or off, found '" + val + "'");
            }
        }
        return new FoldConfig(on);
    }

    @Override
    public String toString() {
        return enabled ? "constant-folding on" : "constant-folding off";
    }
}

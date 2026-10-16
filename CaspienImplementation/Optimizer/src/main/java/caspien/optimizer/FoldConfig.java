package caspien.optimizer;

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

    public FoldConfig(boolean enabled) { }

    public static FoldConfig disabled() { return null; }


    public static FoldConfig parse(List<String> lines, String path) { return null; }

    @Override
    public String toString() { return null; }
}

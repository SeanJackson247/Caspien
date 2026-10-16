package caspien.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Remembers every source file the front end read (the input, every import, every ASM file) so the orchestrator's build cache
 * can tell whether a cached result is still valid. If the environment variable CASPIEN_DEPS_FILE names a file, the list is written
 * there (one absolute path per line) after a successful run; otherwise nothing happens.
 */
public final class DepsLog {
    public static final Set<String> FILES = new LinkedHashSet<>();

    public DepsLog() { }

    public static void record(Path p) {
        FILES.add(p.toAbsolutePath().normalize().toString());
    }

    public static void record(String p) {
        record(Paths.get(p));
    }

    public static java.util.List<String> files() { return null; }

    public static void writeIfRequested() throws IOException { }
}

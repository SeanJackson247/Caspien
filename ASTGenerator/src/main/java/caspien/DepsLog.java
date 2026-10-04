package caspien;

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
final class DepsLog {
    private static final Set<String> FILES = new LinkedHashSet<>();

    private DepsLog() {
    }

    static void record(Path p) {
        FILES.add(p.toAbsolutePath().normalize().toString());
    }

    static void record(String p) {
        record(Paths.get(p));
    }

    static void writeIfRequested() throws IOException {
        String target = System.getenv("CASPIEN_DEPS_FILE");
        if (target == null || target.isEmpty()) {
            return;
        }
        Files.write(Paths.get(target), String.join("\n", FILES).getBytes(StandardCharsets.UTF_8));
    }
}

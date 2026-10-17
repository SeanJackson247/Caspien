package caspien.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The project folder (the implementation's own root) and the one stdlib folder shared by every implementation (`import "stdlib/..."` resolves
 * against it): both set once per compile by the caller, never taken from the working directory.
 */
public final class ProjectRoot {
    private static Path root = Paths.get("..").toAbsolutePath().normalize();
    private static Path stdlib = defaultStdlib(root);

    private ProjectRoot() {
    }

    /** The stdlib folder next to the implementation folder: `<root>/../stdlib`. */
    public static Path defaultStdlib(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize().resolve("..").resolve("stdlib").normalize();
    }

    public static void set(Path projectRoot, Path stdlibFolder) {
        root = projectRoot.toAbsolutePath().normalize();
        stdlib = stdlibFolder == null ? defaultStdlib(root) : stdlibFolder.toAbsolutePath().normalize();
    }

    public static Path root() {
        return root;
    }

    public static Path stdlib() {
        return stdlib;
    }

    /** A source file as shown in reports: `stdlib/<file>` for the shared stdlib, relative to the project root inside it, else absolute. */
    public static String shown(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (abs.startsWith(stdlib)) {
            return "stdlib/" + stdlib.relativize(abs);
        }
        return abs.startsWith(root) ? root.relativize(abs).toString() : abs.toString();
    }
}

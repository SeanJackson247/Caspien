package caspien;

import java.nio.file.Path;
import java.nio.file.Paths;

/** The project folder (the one holding `stdlib/`): set once per compile by the caller, never taken from the working directory. */
public final class ProjectRoot {
    private static Path root = Paths.get("..").toAbsolutePath().normalize();

    private ProjectRoot() {
    }

    public static void set(Path projectRoot) {
        root = projectRoot.toAbsolutePath().normalize();
    }

    public static Path root() {
        return root;
    }

    public static Path stdlib() {
        return root.resolve("stdlib");
    }
}

package caspien.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/** The project folder (the one holding `stdlib/`): set once per compile by the caller, never taken from the working directory. */
public final class ProjectRoot {
    private static Path root = Paths.get("..").toAbsolutePath().normalize();

    private ProjectRoot() { }

    public static void set(Path projectRoot) { }

    public static Path root() { return null; }

    public static Path stdlib() { return null; }
}

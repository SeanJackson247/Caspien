package caspien.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import caspien.bytecode.Text;

/**
 * File-system policy chosen by whoever compiles, read from the optional fs.config (written by the Compiler from toolchain.config's
 * ===fs.config=== section). Keys:
 *   fs-roots: /var/app/data:rw, /tmp/scratch:r        -- directories a program may open by path, with their access
 *   fs-deny-externs: open, openat, creat, ...         -- extern names (or @link_name targets) forbidden outside stdlib/
 * No fs.config (or no key) means no restriction for that key. Paths use '/' (also on Windows). The run-time roots text embedded in
 * a program through the identifier __FS_ROOTS is "path?rw|path?r"; "?" and "|" cannot appear in a root.
 */
public final class FsPolicy {


    /** The policy of this compiler run (set by Main before lexing). */
    public static FsPolicy current = new FsPolicy();

    /** The codegen target ("linux", "windows_gnu"), read from platform.config; used to pick platform-specific stdlib files ("{target}" in an import path). */
    public static String platform = "linux";

    /** Reads the text of `platform.config` (null = absent: the default platform). */
    public static void parsePlatform(String text) { }

    public final List<FsRoot> roots = new ArrayList<>();
    public final Set<String> denyExterns = new LinkedHashSet<>();
    /** True when fs-roots is present: then literal roots are checked and the run-time table is the list above. */
    public boolean rootsEnforced;
    public final List<String> report = new ArrayList<>();

    /** Reads the text of `fs.config` (null = absent: no restriction); `file` names it in error messages. */
    public static FsPolicy parse(String text, String file) { return null; }

    /** The run-time table text: "path?rw|path?r". */
    public String runtimeRootsText() { return null; }

    /** Null when `path` (a literal) may be opened with the wanted access; otherwise the reason it may not. */
    public String checkLiteralRoot(String path, boolean wantRw) { return null; }

    /** Whether an extern declared in `declFile` (a path) under these names is forbidden. The stdlib's own externs are exempt. */
    public boolean denies(String name, String linkName) { return false; }
}

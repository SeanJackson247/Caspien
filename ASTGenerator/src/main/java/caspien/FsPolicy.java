package caspien;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * File-system policy chosen by whoever compiles, read from the optional fs.config (written by the Compiler from toolchain.config's
 * ===fs.config=== section). Keys:
 *   fs-roots: /var/app/data:rw, /tmp/scratch:r        -- directories a program may open by path, with their access
 *   fs-deny-externs: open, openat, creat, ...         -- extern names (or @link_name targets) forbidden outside stdlib/
 * No fs.config (or no key) means no restriction for that key. Paths use '/' (also on Windows). The run-time roots text embedded in
 * a program through the identifier __FS_ROOTS is "path?rw|path?r"; "?" and "|" cannot appear in a root.
 */
public final class FsPolicy {
    public static final class Root {
        public final String path;
        public final boolean rw;
        Root(String path, boolean rw) { this.path = path; this.rw = rw; }
    }

    /** The policy of this compiler run (set by Main before lexing). */
    public static FsPolicy current = new FsPolicy();

    /** The codegen target ("linux", "windows_gnu"), read from platform.config; used to pick platform-specific stdlib files ("{target}" in an import path). */
    public static String platform = "linux";

    public static void loadPlatform(String file) throws IOException {
        Path path = Paths.get(file);
        if (!Files.exists(path)) {
            return;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.startsWith("target:")) {
                platform = t.substring("target:".length()).trim();
            }
        }
    }

    public final List<Root> roots = new ArrayList<>();
    public final Set<String> denyExterns = new LinkedHashSet<>();
    /** True when fs-roots is present: then literal roots are checked and the run-time table is the list above. */
    public boolean rootsEnforced;
    public final List<String> report = new ArrayList<>();

    public static FsPolicy load(String file) throws IOException {
        FsPolicy p = new FsPolicy();
        Path path = Paths.get(file);
        if (!Files.exists(path)) {
            return p;
        }
        int lineNo = 0;
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new CompilerException("config", file, lineNo, "expected 'key: value'");
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (key.equals("fs-roots")) {
                p.rootsEnforced = true;
                for (String item : value.split(",")) {
                    String it = item.trim();
                    if (it.isEmpty()) continue;
                    int c = it.lastIndexOf(':');
                    if (c < 0) {
                        throw new CompilerException("config", file, lineNo, "fs-roots entry '" + it + "' needs ':r' or ':rw'");
                    }
                    String dir = it.substring(0, c).trim();
                    String mode = it.substring(c + 1).trim();
                    if (!mode.equals("r") && !mode.equals("rw")) {
                        throw new CompilerException("config", file, lineNo, "fs-roots access must be 'r' or 'rw', got '" + mode + "'");
                    }
                    while (dir.length() > 1 && dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
                    if (dir.isEmpty() || dir.indexOf('?') >= 0 || dir.indexOf('|') >= 0 || dir.contains("/..") || dir.indexOf('\\') >= 0) {
                        throw new CompilerException("config", file, lineNo, "fs-roots path '" + dir + "' is not allowed (use '/' separators, no '?', '|' or '..')");
                    }
                    p.roots.add(new Root(dir, mode.equals("rw")));
                }
            } else if (key.equals("fs-deny-externs")) {
                for (String item : value.split(",")) {
                    String it = item.trim();
                    if (!it.isEmpty()) p.denyExterns.add(it);
                }
            } else {
                throw new CompilerException("config", file, lineNo, "unknown fs.config key '" + key + "'");
            }
        }
        return p;
    }

    /** The run-time table text: "path?rw|path?r". */
    public String runtimeRootsText() {
        if (!rootsEnforced) {
            return "*"; // no fs-roots key: every path is accepted
        }
        StringBuilder sb = new StringBuilder();
        for (Root r : roots) {
            if (sb.length() > 0) sb.append('|');
            sb.append(r.path).append('?').append(r.rw ? "rw" : "r");
        }
        return sb.toString();
    }

    /** Null when `path` (a literal) may be opened with the wanted access; otherwise the reason it may not. */
    public String checkLiteralRoot(String path, boolean wantRw) {
        if (!rootsEnforced) {
            return null;
        }
        if (path.contains("/..") || path.contains("\\")) {
            return "'" + path + "' contains '..' or a backslash";
        }
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        for (Root r : roots) {
            boolean inside = p.equals(r.path) || p.startsWith(r.path.equals("/") ? "/" : r.path + "/");
            if (inside) {
                if (wantRw && !r.rw) {
                    return "root '" + r.path + "' is read-only (fs-roots)";
                }
                return null;
            }
        }
        return "'" + path + "' is not inside any fs-roots entry";
    }

    /** Whether an extern declared in `declFile` (a path) under these names is forbidden. The stdlib's own externs are exempt. */
    public boolean denies(String name, String linkName) {
        return denyExterns.contains(name) || denyExterns.contains(linkName);
    }
}

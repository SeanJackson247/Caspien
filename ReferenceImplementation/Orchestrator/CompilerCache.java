import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Content-addressed build cache for the Compiler orchestrator: every stage's result is stored under a key that is the SHA-256 of
 * everything that stage's output can depend on, so a stage runs only when one of those things changed (a Merkle chain: a stage's key
 * contains the hash of the previous stage's output, so an edit that leaves a stage's output unchanged -- a comment, a whitespace change --
 * stops invalidating at that stage).
 *
 *   stage 1 (ASTGenerator)  : the ASTGenerator class files, its config view, the input path, plus -- checked on lookup, not part of the key --
 *                             the hash of every source file the front end read (the input, every import, every ASM file; it reports them
 *                             through CASPIEN_DEPS_FILE)
 *   stage 2 (Optimizer)     : Optimizer classes, its config view, hash of stage 1's output
 *   stage 3 (LowerOrderGen) : LowerOrderGenerator classes, its config view, hash of stage 2's output
 *   stage 4 (Codegen)       : Codegen classes, codegen.config, hash of stage 3's output
 *   stage 5 (as + gcc)      : codegen target, versions of the assembler/linker, hash of stage 4's output
 * Every key also contains the Java runtime version. A "config view" is compiler.config without comments and without the keys the stage
 * never reads (see OPT_KEYS and REG_KEYS); an unknown key stays in every view, so a new key can only cost a cache miss, never a stale hit.
 * The cache is advisory: any I/O problem is treated as a miss, and only successful stages are stored. Warnings are stored with the entry
 * and replayed on a hit; progress ("[info]") lines are not.
 */
final class CompilerCache {
    /** compiler.config keys read only by the Optimizer. */
    static final String[] OPT_KEYS = {"loop-unrolling:", "loop-unroll-", "function-inlining:", "inline-max-", "constant-folding:", "variable-elision:",
            "variable-shifting:", "struct-unpacking:", "dead-control-flow-removal:", "dead-function-removal:", "unused-declaration-removal:",
            "variable-allocation-reordering:", "struct-member-reordering:"};
    /** compiler.config keys read only by the LowerOrderGenerator (the front end parses them but never uses them; the Optimizer does not read them). */
    static final String[] REG_KEYS = {"deferred-operands:", "variables-in-registers:", "float-variables-in-registers:", "float-temporaries-in-registers:", "hoist-array-bases:", "variables-in-alloc-functions:", "fuse-length-compare:", "variables-in-arg-registers:", "loop-rotation:", "copy-forward:", "conditional-move:"};



    private final Path root;
    private final boolean enabled;
    private final boolean report;
    private final Map<String, String> memo = new HashMap<>();

    CompilerCache(Path root, boolean enabled, boolean report) {
        this.root = root;
        this.enabled = enabled;
        this.report = report;
    }

    boolean enabled() {
        return enabled;
    }

    void note(String stage, boolean hit) {
        if (report && enabled) {
            System.err.println("[cache] " + stage + ": " + (hit ? "hit" : "miss"));
        }
    }

    static void clear(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> all = walkAll(root);
        java.util.Collections.sort(all);
        for (int i = all.size() - 1; i >= 0; i--) {
            Files.delete(all.get(i));
        }
    }

    /** Every path under root, root included, without following symlinks (unordered; callers sort). Iterative. */
    static List<Path> walkAll(Path root) throws IOException {
        List<Path> all = new ArrayList<>();
        java.util.ArrayDeque<Path> todo = new java.util.ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            Path cur = todo.pop();
            all.add(cur);
            if (Files.isDirectory(cur, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(cur)) {
                    for (Path child : ds) {
                        todo.push(child);
                    }
                }
            }
        }
        return all;
    }

    // ---- hashing ----------------------------------------------------------

    static String sha(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha(String s) {
        return sha(s.getBytes(StandardCharsets.UTF_8));
    }

    static String fileHash(Path p) throws IOException {
        return sha(Files.readAllBytes(p));
    }

    /** Hash of a recorded dependency: a file's bytes, or for a folder (a wildcard import) the sorted list of its .caspien file names. */
    static String depHash(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            List<String> names = new ArrayList<>();
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path x : ds) {
                    String n = x.getFileName().toString();
                    if (Files.isRegularFile(x) && n.endsWith(".caspien")) {
                        names.add(n);
                    }
                }
            }
            java.util.Collections.sort(names);
            return sha("DIR\n" + String.join("\n", names));
        }
        return fileHash(p);
    }

    /** True when a recorded dependency still exists (file or folder). */
    static boolean depExists(Path p) {
        return Files.isRegularFile(p) || Files.isDirectory(p);
    }

    /** Hash of every class file under componentDir/out (names and contents, sorted). */
    String classesHash(Path componentDir) throws IOException {
        String k = componentDir.toString();
        String m = memo.get(k);
        if (m != null) {
            return m;
        }
        List<Path> files = new ArrayList<>();
        Path out = componentDir.resolve("out");
        if (Files.isDirectory(out)) {
            for (Path p : walkAll(out)) {
                if (p.toString().endsWith(".class")) {
                    files.add(p);
                }
            }
            java.util.Collections.sort(files);
        }
        StringBuilder sb = new StringBuilder(System.getProperty("java.version")).append('|').append(System.getProperty("java.vendor")).append('\n');
        for (Path f : files) {
            sb.append(out.relativize(f)).append(' ').append(fileHash(f)).append('\n');
        }
        String h = sha(sb.toString());
        memo.put(k, h);
        return h;
    }

    /** compiler.config with comments, blank lines and carriage returns removed, and without the top-level keys starting with any prefix in dropPrefixes. */
    static String configView(String text, String[]... dropPrefixes) {
        StringBuilder sb = new StringBuilder();
        outer:
        for (String raw : CompilerText.split(text, '\n', -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                for (String[] group : dropPrefixes) {
                    for (String pre : group) {
                        if (t.startsWith(pre)) {
                            continue outer;
                        }
                    }
                }
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ---- entries ----------------------------------------------------------

    private Path entryDir(String stage, String key) {
        return root.resolve(stage).resolve(key);
    }

    /**
     * Copies the cached output of (stage, key) to outFile and fills log; false = miss. With checkDeps the recorded source-file hashes must all
     * still match (stage 1).
     */
    boolean restore(String stage, String key, Path outFile, boolean checkDeps, CompilerStageLog log) {
        if (!enabled || key == null) {
            return false;
        }
        try {
            Path d = entryDir(stage, key);
            Path data = d.resolve("out");
            if (!Files.isRegularFile(data) || !Files.isRegularFile(d.resolve("meta"))) {
                return false;
            }
            if (checkDeps) {
                Path deps = d.resolve("deps");
                if (!Files.isRegularFile(deps)) {
                    return false;
                }
                for (String line : Files.readAllLines(deps, StandardCharsets.UTF_8)) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) {
                        return false;
                    }
                    Path f = Path.of(line.substring(tab + 1));
                    if (!depExists(f) || !depHash(f).equals(line.substring(0, tab))) {
                        return false;
                    }
                }
            }
            for (String line : Files.readAllLines(d.resolve("meta"), StandardCharsets.UTF_8)) {
                if (line.startsWith("W\t")) {
                    log.warnings.add(line.substring(2));
                } else if (line.startsWith("N\t")) {
                    log.notes.add(line.substring(2));
                }
            }
            Files.copy(data, outFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Stores a successful stage's output; `deps` (stage 1) is the list of source files the front end read, or null. Failures are ignored. */
    void store(String stage, String key, Path outFile, CompilerStageLog log, List<String> deps) {
        if (!enabled || key == null) {
            return;
        }
        try {
            Path parent = root.resolve(stage);
            Files.createDirectories(parent);
            Path tmp = Files.createTempDirectory(parent, "tmp-");
            Files.copy(outFile, tmp.resolve("out"), StandardCopyOption.COPY_ATTRIBUTES);
            StringBuilder meta = new StringBuilder();
            for (String w : log.warnings) {
                meta.append("W\t").append(w).append('\n');
            }
            for (String n : log.notes) {
                meta.append("N\t").append(n).append('\n');
            }
            Files.writeString(tmp.resolve("meta"), meta.toString(), StandardCharsets.UTF_8);
            if (deps != null) {
                StringBuilder lines = new StringBuilder();
                for (String f : deps) {
                    if (!f.isEmpty()) {
                        lines.append(depHash(Path.of(f))).append('\t').append(f).append('\n');
                    }
                }
                Files.writeString(tmp.resolve("deps"), lines.toString(), StandardCharsets.UTF_8);
            }
            Path target = entryDir(stage, key);
            if (Files.exists(target)) {
                clear(target);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {   // lost a race with another compile storing the same key: theirs is as good as ours
                clear(tmp);
            }
        } catch (IOException | RuntimeException e) {
            // advisory cache: never fail the build over it
        }
    }
}

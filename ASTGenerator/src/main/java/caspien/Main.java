package caspien;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Stand-alone front end: `java caspien.Main -i main.caspien out.txt [--fs-report] [--audit] [--viz]`, run from the ASTGenerator folder.
 * Reads compiler.config (required) and fs.config / platform.config / trace.config (optional) from the working directory, calls
 * {@link Frontend#compile}, and prints / writes the results. The environment variables CASPIEN_AUDIT_FILE, CASPIEN_VIZ_FILE and
 * CASPIEN_FIX_* name where reports go and pass the --fix options (the orchestrator calls the library directly and does not use them).
 */
public class Main {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IOException ioe) {
            System.err.println("[io error] " + ioe.getMessage());
            System.exit(1);
        }
    }

    private static String optionalFile(String name) throws IOException {
        Path p = Paths.get(name);
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
    }

    private static void put(Map<String, String> options, String key, String value) {
        if (value != null && !value.isEmpty()) {
            options.put(key, value);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length < 3 || !args[0].equals("-i")) {
            System.err.println("Usage: compiler -i <input.caspien> <output>");
            System.exit(1);
            return;
        }
        String inputPath = args[1];
        String outputPath = args[2];

        Map<String, String> configs = new HashMap<>();
        String compilerConfig = optionalFile("compiler.config");
        if (compilerConfig == null) {
            System.err.println("[config error] compiler.config:0 - required config file 'compiler.config' could not be read");
            System.exit(1);
            return;
        }
        configs.put("compiler.config", compilerConfig);
        for (String name : new String[]{"fs.config", "platform.config", "trace.config"}) {
            String text = optionalFile(name);
            if (text != null) {
                configs.put(name, text);
            }
        }

        Map<String, String> options = new HashMap<>();
        options.put("root", Paths.get("..").toAbsolutePath().normalize().toString());
        options.put("debug", "1");
        boolean audit = false;
        boolean viz = false;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--fs-report" -> options.put("fs-report", "1");
                case "--audit" -> {
                    audit = true;
                    options.put("audit", "1");
                }
                case "--viz" -> {
                    viz = true;
                    options.put("viz", "1");
                }
                default -> { }
            }
        }
        put(options, "audit-no-stdlib", "1".equals(System.getenv("CASPIEN_AUDIT_NOSTDLIB")) ? "1" : null);
        put(options, "fix-mode", System.getenv("CASPIEN_FIX_MODE"));
        put(options, "fix-write", "1".equals(System.getenv("CASPIEN_FIX_WRITE")) ? "1" : null);
        put(options, "fix-stdlib", "1".equals(System.getenv("CASPIEN_FIX_STDLIB")) ? "1" : null);
        put(options, "fix-backup", System.getenv("CASPIEN_FIX_BACKUP"));
        put(options, "fix-only", System.getenv("CASPIEN_FIX_ONLY"));
        String verifyFile = System.getenv("CASPIEN_FIX_VERIFY");
        if (verifyFile != null && !verifyFile.isEmpty()) {
            options.put("fix-verify", Files.readString(Paths.get(verifyFile), StandardCharsets.UTF_8));
        }

        FrontendResult result = Frontend.compile(Paths.get(inputPath).toAbsolutePath().normalize().toString(), configs, options);
        for (String line : result.messages) {
            System.err.println(line);
        }
        if (result.failed) {
            System.exit(1);
            return;
        }
        String bytecode = result.hob;
        Map<String, String> reports = result.reports;

        if (audit) {
            toFileOrStderr(System.getenv("CASPIEN_AUDIT_FILE"), reports.get("audit"));
        }
        if (reports.containsKey("fixHash")) {
            String hashFile = System.getenv("CASPIEN_FIX_HASH");
            if (hashFile != null && !hashFile.isEmpty()) {
                writeFile(hashFile, reports.get("fixHash"));
            }
            String blocksFile = System.getenv("CASPIEN_FIX_BLOCKS");
            if (blocksFile != null && !blocksFile.isEmpty()) {
                writeFile(blocksFile, reports.get("fixBlocks"));
            }
            toFileOrStderr(System.getenv("CASPIEN_FIX_FILE"), reports.get("fix"));
        }
        if (viz) {
            String target = System.getenv("CASPIEN_VIZ_FILE");
            writeFile(target == null || target.isEmpty() ? "viz.html" : target, reports.get("viz"));
        }
        System.out.print(bytecode);

        Path deps = System.getenv("CASPIEN_DEPS_FILE") == null || System.getenv("CASPIEN_DEPS_FILE").isEmpty()
                ? null : Paths.get(System.getenv("CASPIEN_DEPS_FILE"));
        if (deps != null) {
            writeFile(deps.toString(), String.join("\n", result.deps));
        }
        writeFile(outputPath, bytecode);
        writeFile("output.txt", bytecode);
        writeFile("debug.txt", reports.get("debug"));

        System.err.println("\n[info] wrote bytecode to " + outputPath + " and output.txt ("
                + bytecode.lines().count() + " instructions/lines); debug tree written to debug.txt");
    }

    private static void toFileOrStderr(String target, String text) throws IOException {
        if (target != null && !target.isEmpty()) {
            writeFile(target, text);
        } else {
            System.err.print(text);
        }
    }

    private static void writeFile(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }
}

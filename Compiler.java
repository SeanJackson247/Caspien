import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Compiler -- orchestrator for the Caspien toolchain.
 *
 * This is not itself a compiler stage. It presumes the four real
 * pipeline components already exist on disk, each in its own folder
 * next to this file (relative addressing, resolved against the
 * directory this is run from):
 *
 *   ASTGenerator/          .caspien source -> "higher order" bytecode
 *   Optimizer/             bytecode -> bytecode (shallow passes)
 *   LowerOrderGenerator/   bytecode -> "low order" bytecode
 *   Codegen/               low-order bytecode -> x86-64 assembly
 *
 * Each is a separately prebuilt Java library (its own "out/" directory
 * of .class files). This program's own job is: read ONE config file
 * (toolchain.config, next to this file), split it into the config text
 * each component expects, and call the four components in sequence as
 * libraries: this JVM loads their classes from their own out/ folders
 * (a private class loader per call) and calls Frontend.compile /
 * Optimizer.optimize / LowerOrder.generate / Codegen.generate with text
 * and config text, so each stage's output string goes straight into the
 * next. Their stand-alone Main programs still exist and call the same code.
 * Then, unless
 * told to stop early, assemble and link the final assembly into a real
 * binary using whatever toolchain the "target" in Codegen's config
 * calls for.
 *
 * Usage:
 *   java Compiler -i <input.caspien> <output> [flags]
 *
 * Flags:
 *   --no-warnings   suppress warnings (compiler diagnostics and
 *                   codegen's own "not yet implemented" notices) --
 *                   they simply aren't printed; errors are never
 *                   suppressed
 *   --hob           stop after ASTGenerator + Optimizer; <output>
 *                   receives the "higher order" bytecode text
 *   --lob           stop after LowerOrderGenerator; <output> receives
 *                   the "low order" bytecode text
 *   --asm           stop after Codegen; <output> receives the
 *                   generated assembly (.s) text
 *   (no stop flag)  run the full pipeline, then assemble and link;
 *                   <output> receives the real binary
 *
 * At most one of --hob/--lob/--asm may be given. Intermediate files
 * for any stage that ISN'T the requested stopping point are written
 * into a ".build" folder created next to <output>, not deleted
 * afterward -- they're real artifacts, not scratch, and worth being
 * able to inspect.
 *
 * A test .caspien file that imports stdlib paths (e.g.
 * "stdlib/libc.caspien") needs a real "stdlib" folder reachable by
 * that same relative path from wherever the file itself lives --
 * ASTGenerator's own import resolution works that way, independent of
 * this orchestrator or any component's working directory. A "stdlib"
 * folder is provided at this project's own root for exactly this
 * reason: keep test .caspien files there too, next to this file, for
 * now (matches "relativistic addressing" -- everything here assumes it
 * is being run from the directory it lives in).
 */
public class Compiler {

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (CompilerUsageError ue) {
            System.err.println(ue.getMessage());
            System.err.println();
            System.err.println(USAGE);
            code = 2;
        } catch (IOException ioe) {
            System.err.println("[error] " + ioe.getMessage());
            code = 1;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            System.err.println("[error] interrupted");
            code = 1;
        }
        System.exit(code);
    }

    private static final String USAGE =
            "Usage: java Compiler -i <input.caspien> <output> [--no-warnings] [--fs-report] [--asm | --lob | --hob] [--trace-depth N] [--no-cache] [--cache-report] [--clear-cache]\n       java Compiler -i <input.caspien> --audit [--audit-no-stdlib]\n       java Compiler -i <input.caspien> --viz [out.html]\n       java Compiler -i <input.caspien> --fix termination|justify|unsafe-split [--write] [--fix-stdlib]";



    // ---- CLI ----------------------------------------------------------

    private static int run(String[] args) throws IOException, InterruptedException {
        String inputArg = null;
        String outputArg = null;
        boolean noWarnings = false;
        boolean fsReport = false;
        boolean stopAsm = false, stopLob = false, stopHob = false;
        boolean noCache = false, cacheReport = false, clearCache = false;
        boolean audit = false, auditNoStdlib = false, viz = false;
        String fixModes = null;
        boolean fixWrite = false, fixStdlib = false;
        int traceDepth = 16;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-i":
                    if (i + 1 >= args.length) {
                        throw new CompilerUsageError("-i requires a path");
                    }
                    inputArg = args[++i];
                    break;
                case "--no-warnings":
                    noWarnings = true;
                    break;
                case "--fs-report":
                    fsReport = true;
                    break;
                case "--asm":
                    stopAsm = true;
                    break;
                case "--lob":
                    stopLob = true;
                    break;
                case "--hob":
                    stopHob = true;
                    break;
                case "--audit":
                    audit = true;
                    break;
                case "--viz":
                    viz = true;
                    break;
                case "--fix":
                    if (i + 1 >= args.length) {
                        throw new CompilerUsageError("--fix requires a mode: termination, justify, unsafe-split (comma separated)");
                    }
                    fixModes = args[++i];
                    break;
                case "--write":
                    fixWrite = true;
                    break;
                case "--fix-stdlib":
                    fixStdlib = true;
                    break;
                case "--audit-no-stdlib":
                    audit = true;
                    auditNoStdlib = true;
                    break;
                case "--trace-depth":
                    if (i + 1 >= args.length) {
                        throw new CompilerUsageError("--trace-depth requires a number");
                    }
                    try {
                        traceDepth = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        throw new CompilerUsageError("--trace-depth requires a whole number, got: " + args[i]);
                    }
                    if (traceDepth < 1 || traceDepth > 1024) {
                        throw new CompilerUsageError("--trace-depth must be from 1 to 1024, got: " + traceDepth);
                    }
                    break;
                case "--no-cache":
                    noCache = true;
                    break;
                case "--cache-report":
                    cacheReport = true;
                    break;
                case "--clear-cache":
                    clearCache = true;
                    break;
                default:
                    if (a.startsWith("-")) {
                        throw new CompilerUsageError("unknown flag: " + a);
                    } else if (outputArg == null) {
                        outputArg = a;
                    } else {
                        throw new CompilerUsageError("unexpected extra argument: " + a);
                    }
            }
        }

        Path cacheRoot = System.getenv("CASPIEN_CACHE") != null && !System.getenv("CASPIEN_CACHE").isEmpty()
                ? Paths.get(System.getenv("CASPIEN_CACHE")).toAbsolutePath() : Paths.get("").toAbsolutePath().resolve(".cache");
        if (clearCache) {
            CompilerCache.clear(cacheRoot);
            System.err.println("[info] cleared the build cache " + cacheRoot);
            if (inputArg == null && outputArg == null) {
                return 0;
            }
        }
        if (inputArg == null) {
            throw new CompilerUsageError("missing required -i <input.caspien>");
        }
        Path vizHtml = null;
        if (viz) {
            // --viz builds nothing, like --audit: the optional output path is the HTML page (default output/<input name>.html)
            String base = CompilerText.stripSuffixAtEnd(Path.of(inputArg).getFileName().toString(), ".caspien");
            vizHtml = Path.of(outputArg != null ? outputArg : "output/" + base + ".html");
            outputArg = "output/viz";   // the front end's intermediate file
        }
        if (outputArg == null && fixModes != null) {
            outputArg = "output/fix";     // --fix builds nothing; the front end's intermediate file goes here
        }
        if (outputArg == null && audit) {
            outputArg = "output/audit";   // an audit builds nothing; the front end's intermediate file goes here
        }
        if (outputArg == null) {
            throw new CompilerUsageError("missing required <output> path");
        }
        int stopCount = (stopAsm ? 1 : 0) + (stopLob ? 1 : 0) + (stopHob ? 1 : 0);
        if (stopCount > 1) {
            throw new CompilerUsageError("only one of --asm, --lob, --hob may be given");
        }

        Path input = Paths.get(inputArg).toAbsolutePath().normalize();
        Path output = Paths.get(outputArg).toAbsolutePath().normalize();
        if (!Files.exists(input)) {
            throw new CompilerUsageError("input file does not exist: " + input);
        }

        Path root = Paths.get("").toAbsolutePath(); // this orchestrator's own directory -- everything below is relative to it
        Path astGenDir = root.resolve("ASTGenerator");
        Path optimizerDir = root.resolve("Optimizer");
        Path lowerOrderDir = root.resolve("LowerOrderGenerator");
        Path codegenDir = root.resolve("Codegen");
        for (Path p : new Path[]{astGenDir, optimizerDir, lowerOrderDir, codegenDir}) {
            if (!Files.isDirectory(p)) {
                throw new IOException("expected component folder not found: " + p
                        + " -- this orchestrator must be run from the directory it lives in, "
                        + "with ASTGenerator/, Optimizer/, LowerOrderGenerator/, and Codegen/ alongside it");
            }
        }

        Files.createDirectories(output.getParent() != null ? output.getParent() : root);

        splitToolchainConfig(root, astGenDir, optimizerDir, lowerOrderDir, codegenDir);
        // the front end needs the target for the platform-specific stdlib imports ("{target}" in an import path, e.g. fs_{target}.caspien)
        Files.writeString(astGenDir.resolve("platform.config"), "target: " + readCodegenTarget(codegenDir) + "\n", StandardCharsets.UTF_8);
        // stack traces: how many frames a captured trace keeps (see stack_trace.md); only matters to a program that uses the feature
        Files.writeString(astGenDir.resolve("trace.config"), "trace-depth: " + traceDepth + "\n", StandardCharsets.UTF_8);

        Path buildDir = null; // created lazily, only if an intermediate file is actually needed

        CompilerDiagnostics diag = new CompilerDiagnostics(noWarnings);
        CompilerCache cache = new CompilerCache(cacheRoot, !noCache, cacheReport);

        // Each stage's cache key is the hash of everything its output depends on (see CompilerCache); an unchanged stage is not run again.
        String javaId = System.getProperty("java.version");
        String compilerCfg = Files.readString(astGenDir.resolve("compiler.config"), StandardCharsets.UTF_8);
        String codegenCfg = CompilerCache.configView(Files.readString(codegenDir.resolve("codegen.config"), StandardCharsets.UTF_8));
        Path fsCfgPath = astGenDir.resolve("fs.config");
        String fsCfg = Files.exists(fsCfgPath) ? CompilerCache.configView(Files.readString(fsCfgPath, StandardCharsets.UTF_8)) : "";
        String target = readCodegenTarget(codegenDir);
        boolean jccPadding = readCodegenFlag(codegenDir, "jcc-padding") && assemblerSupportsJccPadding(target);

        // ---- Stage 1: ASTGenerator (.caspien -> higher-order bytecode), run in this JVM as a library ----
        Path hobOut = stopHob ? output : (buildDir = ensureBuildDir(buildDir, output)).resolve("1_ast_generator.hob.txt");
        String key1 = fsReport ? null : CompilerCache.sha("S1\n" + cache.classesHash(astGenDir) + "\n"
                + CompilerCache.configView(compilerCfg, CompilerCache.OPT_KEYS, CompilerCache.REG_KEYS) + "\n--fs--\n" + fsCfg + "\n--target--\n" + target + "\n--trace-depth--\n" + traceDepth
                + "\n--input--\n" + input);
        Map<String, String> feConfigs = new java.util.HashMap<>();
        feConfigs.put("compiler.config", compilerCfg);
        if (Files.exists(fsCfgPath)) {
            feConfigs.put("fs.config", Files.readString(fsCfgPath, StandardCharsets.UTF_8));
        }
        feConfigs.put("platform.config", "target: " + target + "\n");
        feConfigs.put("trace.config", "trace-depth: " + traceDepth + "\n");
        Map<String, String> feOptions = new java.util.HashMap<>();
        feOptions.put("root", root.toString());
        if (viz) {
            // --viz builds nothing, like --audit: the page goes to vizHtml
            feOptions.put("viz", "1");
            CompilerOut o = runFrontend(astGenDir, input, feConfigs, feOptions, diag, null);
            if (!diag.hasFatalError()) {
                if (vizHtml.getParent() != null) {
                    Files.createDirectories(vizHtml.getParent());
                }
                Files.writeString(vizHtml, o.reports.get("viz"), StandardCharsets.UTF_8);
                System.out.println("[info] wrote " + vizHtml);
            }
            return diag.exitCode();
        }
        if (fixModes != null) {
            // --fix: run only the front end, uncached; it plans (and with --write applies) comment-line insertions, then the front end runs
            // once more and its output must equal the output before the edit (comments change no code), else every file is restored.
            Path tmp = Files.createTempDirectory("caspien-fix");
            Path bak = tmp.resolve("bak");
            Files.createDirectories(bak);
            Map<String, String> fixOptions = new java.util.HashMap<>(feOptions);
            fixOptions.put("fix-mode", fixModes);
            fixOptions.put("fix-backup", bak.toString());
            if (fixWrite) {
                fixOptions.put("fix-write", "1");
            }
            if (fixStdlib) {
                fixOptions.put("fix-stdlib", "1");
            }
            CompilerOut fix = runFrontend(astGenDir, input, feConfigs, fixOptions, diag, null);
            if (diag.hasFatalError()) {
                return diag.exitCode();
            }
            System.out.print(fix.reports.get("fix"));
            String hash = fix.reports.get("fixHash");
            String blocksText = fix.reports.get("fixBlocks");
            Path manifest = bak.resolve("manifest.txt");
            if (fixWrite && Files.isRegularFile(manifest)) {
                Map<String, String> verifyOptions = new java.util.HashMap<>(feOptions);
                verifyOptions.put("fix-verify", hash);
                CompilerDiagnostics verify = new CompilerDiagnostics(noWarnings);
                runFrontend(astGenDir, input, feConfigs, verifyOptions, verify, null);
                if (verify.hasFatalError()) {
                    for (String l : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                        String[] p = CompilerText.split(l, '\t', 0);
                        Files.copy(Path.of(p[1]), Path.of(p[0]), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (fixModes.contains("unsafe-split") && blocksText != null) {
                        // The all-at-once rewrite did not verify. Try the blocks one at a time, last one in each file first (an edit never moves
                        // the lines above it), keeping every rewrite that compiles to identical bytecode and leaving the others as written.
                        List<String> blocks = new ArrayList<>();
                        for (String l : CompilerText.split(blocksText, '\n', 0)) {
                            if (!l.isBlank()) {
                                blocks.add(l);
                            }
                        }
                        sortBlocks(blocks);
                        int kept = 0, left = 0;
                        for (String blk : blocks) {
                            String[] bp = CompilerText.split(blk, '\t', 0);
                            Path bak2 = Files.createTempDirectory(tmp, "one");
                            Map<String, String> oneOptions = new java.util.HashMap<>(feOptions);
                            oneOptions.put("fix-mode", "unsafe-split");
                            oneOptions.put("fix-backup", bak2.toString());
                            oneOptions.put("fix-write", "1");
                            oneOptions.put("fix-only", bp[0] + ":" + bp[1]);
                            if (fixStdlib) {
                                oneOptions.put("fix-stdlib", "1");
                            }
                            CompilerDiagnostics d3 = new CompilerDiagnostics(noWarnings);
                            CompilerOut one = runFrontend(astGenDir, input, feConfigs, oneOptions, d3, null);
                            Path man2 = bak2.resolve("manifest.txt");
                            if (d3.hasFatalError() || !Files.isRegularFile(man2)) {
                                left++;
                                continue;
                            }
                            Map<String, String> check = new java.util.HashMap<>(feOptions);
                            check.put("fix-verify", one.reports.get("fixHash"));
                            CompilerDiagnostics v4 = new CompilerDiagnostics(noWarnings);
                            runFrontend(astGenDir, input, feConfigs, check, v4, null);
                            if (v4.hasFatalError()) {
                                for (String l : Files.readAllLines(man2, StandardCharsets.UTF_8)) {
                                    String[] p = CompilerText.split(l, '\t', 0);
                                    Files.copy(Path.of(p[1]), Path.of(p[0]), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                }
                                System.out.println("# kept as written (the rewrite changes the bytecode or does not compile): " + bp[0] + ":" + bp[1]);
                                left++;
                            } else {
                                kept++;
                            }
                        }
                        System.out.println("# unsafe-split, one block at a time: " + kept + " rewritten and verified, " + left + " left as written");
                        if (!fixModes.equals("unsafe-split")) {
                            System.out.println("# note: the other modes (" + fixModes + ") were not applied; run them again");
                        }
                        return 0;
                    }
                    System.err.println("[error] --fix: the program did not compile to the same bytecode after the edit; every file was restored");
                    return 1;
                }
                System.out.println("# verified: the program compiles to identical bytecode after the edit");
            }
            return 0;
        }
        if (audit) {
            // --audit: run only the front end, uncached, and print what it found (every `unsafe`, with file, line and the text in the braces)
            feOptions.put("audit", "1");
            if (auditNoStdlib) {
                feOptions.put("audit-no-stdlib", "1");
            }
            CompilerOut o = runFrontend(astGenDir, input, feConfigs, feOptions, diag, null);
            if (!diag.hasFatalError()) {
                System.out.print(o.reports.get("audit"));
            }
            return diag.exitCode();
        }
        if (fsReport) {
            feOptions.put("fs-report", "1");
        }
        runFrontendStage(cache, key1, astGenDir, input, feConfigs, feOptions, hobOut, diag);
        if (diag.hasFatalError()) return diag.exitCode();

        if (stopHob) {
            printSummary(hobOut, "higher-order bytecode", diag);
            return diag.exitCode();
        }

        // ---- Stage 2: Optimizer (bytecode -> bytecode, shallow passes) ----
        buildDir = ensureBuildDir(buildDir, output);
        Path optOut = buildDir.resolve("2_optimizer.hob.txt");
        String key2 = CompilerCache.sha("S2\n" + cache.classesHash(optimizerDir) + "\n" + CompilerCache.configView(compilerCfg, CompilerCache.REG_KEYS)
                + "\n--in--\n" + CompilerCache.fileHash(hobOut));
        // Every stage runs inside this JVM as a library (see runLibraryStage / runFrontend); only the assembler and linker are separate programs.
        String optText = runLibraryStage(cache, "s2", key2, optimizerDir, "caspien.optimizer.Optimizer", "optimize", "hob", "compiler.config",
                Files.readString(hobOut, StandardCharsets.UTF_8), hobOut.toString(), optOut, diag, "Optimizer");
        if (diag.hasFatalError()) return diag.exitCode();

        // ---- Stage 3: LowerOrderGenerator (bytecode -> low-order bytecode) ----
        Path lobOut = stopLob ? output : buildDir.resolve("3_lower_order_generator.lob.txt");
        String key3 = CompilerCache.sha("S3\n" + cache.classesHash(lowerOrderDir) + "\n" + CompilerCache.configView(compilerCfg, CompilerCache.OPT_KEYS)
                + "\n--in--\n" + CompilerCache.fileHash(optOut));
        runLibraryStage(cache, "s3", key3, lowerOrderDir, "caspien.lowerorder.LowerOrder", "generate", "lob", "compiler.config",
                optText, optOut.toString(), lobOut, diag, "LowerOrderGenerator");   // the Optimizer's text goes straight in
        if (diag.hasFatalError()) return diag.exitCode();
        if (stopLob) {
            printSummary(lobOut, "low-order bytecode", diag);
            return diag.exitCode();
        }

        // ---- Stage 4: Codegen (low-order bytecode -> assembly) ----
        Path asmOut = stopAsm ? output : buildDir.resolve("4_codegen.s");
        String key4 = CompilerCache.sha("S4\n" + cache.classesHash(codegenDir) + "\n" + codegenCfg + "\n--in--\n" + CompilerCache.fileHash(lobOut));
        runLibraryStage(cache, "s4", key4, codegenDir, "caspien.codegen.Codegen", "generate", "asm", "codegen.config",
                Files.readString(lobOut, StandardCharsets.UTF_8), lobOut.toString(), asmOut, diag, "Codegen");
        if (diag.hasFatalError()) return diag.exitCode();
        scanCodegenTodos(asmOut, diag);
        if (stopAsm) {
            printSummary(asmOut, "assembly", diag);
            return diag.exitCode();
        }

        // ---- Stage 5: assemble + link (real toolchain, chosen by codegen.config's own target) ----
        String tools = toolIdentity(target);
        String key5 = tools == null ? null : CompilerCache.sha("S5\n" + target + (jccPadding ? "\njcc" : "") + "\n" + tools + "\n--in--\n" + CompilerCache.fileHash(asmOut));
        CompilerStageLog log5 = new CompilerStageLog();
        if (cache.restore("s5", key5, output, false, log5)) {
            cache.note("s5", true);
        } else {
            assembleAndLink(target, asmOut, output, buildDir, diag, jccPadding);
            if (diag.hasFatalError()) return diag.exitCode();
            cache.note("s5", false);
            cache.store("s5", key5, output, log5, null);
        }

        printSummary(output, "binary (" + target + ")", diag);
        return diag.exitCode();
    }

    /** The first line of `--version` of every tool stage 5 runs, or null when one cannot be identified (then stage 5 is not cached). */
    private static String toolIdentity(String target) {
        boolean hostIsWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> tools;
        if (target.equals("linux")) {
            tools = List.of("as", "gcc");
        } else if (target.equals("windows_gnu")) {
            String compiler = hostIsWindows ? "gcc" : "x86_64-w64-mingw32-gcc";
            if (!hostIsWindows && !commandExists(compiler)) {
                compiler = "gcc";
            }
            tools = List.of(compiler);
        } else {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String t : tools) {
            try {
                Process p = new ProcessBuilder(t, "--version").redirectErrorStream(true).start();
                String first;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    first = r.readLine();
                    while (r.readLine() != null) { /* drain */ }
                }
                if (p.waitFor() != 0 || first == null) {
                    return null;
                }
                sb.append(t).append('=').append(first).append('\n');
            } catch (IOException | InterruptedException e) {
                return null;
            }
        }
        return sb.toString();
    }



    /**
     * Calls `className.method(paramTypes...)` of a stage's library entry class in this JVM. The stage's classes are loaded from its own
     * `out/` by a private class loader (one per call, so each stage keeps its own copy of the shared bytecode classes and any static
     * state of the stage dies with the call). `resultField` names the public String field of the Result holding the output text.
     * A stage that throws is reported like a stand-alone stage that crashed (stack trace as the error text, failed).
     */
    private static CompilerOut callLibrary(Path componentDir, String className, String method, String resultField, Class<?>[] paramTypes, Object... args)
            throws IOException {
        CompilerOut out = new CompilerOut();
        URL classes = componentDir.resolve("out").toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes}, ClassLoader.getPlatformClassLoader())) {
            Object result = Class.forName(className, true, loader).getMethod(method, paramTypes).invoke(null, args);
            Class<?> rc = result.getClass();
            out.text = (String) rc.getField(resultField).get(result);
            @SuppressWarnings("unchecked")
            List<String> m = (List<String>) rc.getField("messages").get(result);
            out.messages = new ArrayList<>(m);
            out.failed = rc.getField("failed").getBoolean(result);
            try {
                @SuppressWarnings("unchecked")
                List<String> d = (List<String>) rc.getField("deps").get(result);
                out.deps = d == null ? null : new ArrayList<>(d);
                @SuppressWarnings("unchecked")
                Map<String, String> r = (Map<String, String>) rc.getField("reports").get(result);
                out.reports = new java.util.HashMap<>(r);
            } catch (NoSuchFieldException none) {
                // only the front end has dependency and report lists
            }
        } catch (InvocationTargetException e) {
            StringWriter trace = new StringWriter();
            e.getCause().printStackTrace(new PrintWriter(trace));
            // the text a crashed stand-alone stage prints, so a crash is reported the same either way
            out.messages = new ArrayList<>(List.of(CompilerText.splitLineBreaks("Exception in thread \"main\" " + trace)));
            out.failed = true;
            out.text = null;
        } catch (ReflectiveOperationException e) {
            throw new IOException("the stage in " + componentDir + " could not be loaded from " + classes + " (is it built? see its README): " + e, e);
        }
        return out;
    }

    /** Replays a cache hit's notes and warnings, as if the stage had just run. */
    private static void replayCached(CompilerCache cache, String stage, CompilerStageLog log, CompilerDiagnostics diag) {
        for (String n : log.notes) {
            System.err.println(n);
        }
        if (!diag.suppressWarnings()) {
            for (String w : log.warnings) {
                System.err.println(w);
            }
        }
        diag.addWarnings(log.warnings.size());
        cache.note(stage, true);
    }

    /** One run of the front end (uncached): reports its messages through `diag`; `log` (nullable) collects its warnings and notes for the cache. */
    private static CompilerOut runFrontend(Path astGenDir, Path input, Map<String, String> configs, Map<String, String> options, CompilerDiagnostics diag,
                                   CompilerStageLog log) throws IOException {
        CompilerOut o = callLibrary(astGenDir, "caspien.frontend.Frontend", "compile", "hob", new Class<?>[]{String.class, Map.class, Map.class},
                input.toString(), configs, options);
        reportStageOutput(o.messages, o.failed ? 1 : 0, diag, "ASTGenerator", log);
        return o;
    }

    /** Stage 1 through the cache: a hit restores the HOB (if no source file changed) and replays its warnings; a miss runs the front end. */
    private static void runFrontendStage(CompilerCache cache, String key, Path astGenDir, Path input, Map<String, String> configs,
                                         Map<String, String> options, Path outputFile, CompilerDiagnostics diag) throws IOException {
        CompilerStageLog log = new CompilerStageLog();
        if (cache.restore("s1", key, outputFile, true, log)) {
            replayCached(cache, "s1", log, diag);
            return;
        }
        CompilerOut o = runFrontend(astGenDir, input, configs, options, diag, log);
        cache.note("s1", false);
        if (!diag.hasFatalError()) {
            Files.writeString(outputFile, o.text, StandardCharsets.UTF_8);
            cache.store("s1", key, outputFile, log, o.deps);
        }
    }

    /**
     * Runs a stage whose library entry is `className.method(inputText, origin, configText)` and returns its output text. A hit in the
     * cache restores the stored output and replays its notes/warnings; a miss reads `componentDir/configName`, calls the stage, writes
     * the result to `outputFile` and stores it.
     *
     * @param resultField the public String field of the stage's result class holding the output text
     * @return the output text, or null when the stage failed
     */
    private static String runLibraryStage(CompilerCache cache, String stage, String key, Path componentDir, String className, String method,
                                          String resultField, String configName, String inputText, String origin, Path outputFile,
                                          CompilerDiagnostics diag, String stageName) throws IOException {
        CompilerStageLog log = new CompilerStageLog();
        if (cache.restore(stage, key, outputFile, false, log)) {
            replayCached(cache, stage, log, diag);
            return Files.readString(outputFile, StandardCharsets.UTF_8);
        }
        String config = Files.readString(componentDir.resolve(configName), StandardCharsets.UTF_8);
        CompilerOut o = callLibrary(componentDir, className, method, resultField, new Class<?>[]{String.class, String.class, String.class},
                inputText, origin, config);
        reportStageOutput(o.messages, o.failed ? 1 : 0, diag, stageName, log);
        cache.note(stage, false);
        if (o.failed) {
            return null;
        }
        Files.writeString(outputFile, o.text, StandardCharsets.UTF_8);
        cache.store(stage, key, outputFile, log, null);
        return o.text;
    }

    private static Path ensureBuildDir(Path existing, Path output) throws IOException {
        if (existing != null) {
            return existing;
        }
        Path parent = output.getParent() != null ? output.getParent() : Paths.get("").toAbsolutePath();
        Path buildDir = parent.resolve(".build");
        Files.createDirectories(buildDir);
        return buildDir;
    }

    private static void printSummary(Path output, String kind, CompilerDiagnostics diag) {
        System.err.println("\n[info] done -- wrote " + kind + " to " + output
                + (diag.warningCount() > 0 && !diag.suppressWarnings()
                    ? " (" + diag.warningCount() + " warning" + (diag.warningCount() == 1 ? "" : "s") + ")"
                    : ""));
    }

    // ---- toolchain.config splitting ------------------------------------

    /**
     * Reads toolchain.config (fixed name, this orchestrator's own root
     * directory -- required, missing entirely is a fatal error, the
     * same convention every component below already uses for its own
     * config) and writes each "===name==="-delimited section verbatim
     * into the file each component expects, in that component's own
     * folder. compiler.config is shared byte-for-byte between
     * ASTGenerator and LowerOrderGenerator -- both need the identical
     * calling-convention data.
     */
    private static void splitToolchainConfig(Path root, Path astGenDir, Path optimizerDir, Path lowerOrderDir, Path codegenDir) throws IOException {
        Path configPath = root.resolve("toolchain.config");
        if (!Files.exists(configPath)) {
            throw new IOException("required config file not found: " + configPath);
        }
        List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        StringBuilder current = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("===") && trimmed.endsWith("===") && trimmed.length() > 6) {
                String name = trimmed.substring(3, trimmed.length() - 3).trim();
                current = new StringBuilder();
                sections.put(name, current);
            } else if (current != null) {
                current.append(line).append('\n');
            }
            // lines before the first header (the file's own top-of-file
            // comment block) are intentionally dropped -- they document
            // toolchain.config itself, not any one component's config
        }

        StringBuilder compilerCfg = sections.get("compiler.config");
        StringBuilder codegenCfg = sections.get("codegen.config");
        if (compilerCfg == null) {
            throw new IOException("toolchain.config is missing its '===compiler.config===' section");
        }
        if (codegenCfg == null) {
            throw new IOException("toolchain.config is missing its '===codegen.config===' section");
        }

        // Fail loudly here, before a single per-component config file is
        // written or any subprocess is launched, if the root config's two
        // halves don't actually agree with each other -- see
        // validateAbiConsistency's own doc comment for the real bug this
        // exists to catch (a stack-offset mismatch that otherwise only
        // surfaces later, silently, as a wrong value or a segfault, only
        // once a function happens to have enough parameters to overflow
        // its calling convention's registers).
        validateAbiConsistency(compilerCfg.toString(), codegenCfg.toString());

        // optional file-system policy for the type checker (FsPolicy): written when the section exists, removed otherwise
        StringBuilder fsCfg = sections.get("fs.config");
        if (fsCfg != null) {
            Files.writeString(astGenDir.resolve("fs.config"), fsCfg.toString(), StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(astGenDir.resolve("fs.config"));
        }
        Files.writeString(astGenDir.resolve("compiler.config"), compilerCfg.toString(), StandardCharsets.UTF_8);
        Files.writeString(lowerOrderDir.resolve("compiler.config"), compilerCfg.toString(), StandardCharsets.UTF_8);
        Files.writeString(optimizerDir.resolve("compiler.config"), compilerCfg.toString(), StandardCharsets.UTF_8);
        Files.writeString(codegenDir.resolve("codegen.config"), codegenCfg.toString(), StandardCharsets.UTF_8);
    }

    // ---- cross-config ABI-consistency validation -------------------------

    /**
     * `compiler.config`'s chosen default calling convention (used by
     * ASTGenerator/LowerOrderGenerator to decide, for every function with
     * no explicit "@call_convention(name)" decorator, which arguments are
     * register-transferred versus stack-transferred, and at exactly what
     * stack offset a stack-transferred one lands) and `codegen.config`'s
     * chosen target (used only by Codegen, to decide which real x86-64
     * ABI -- win64's rcx/rdx/r8/r9 + 32-byte shadow space, or SysV's
     * rdi/rsi/rdx/rcx/r8/r9 + no shadow space -- its own emitted assembly
     * actually implements) are two entirely separate config sections,
     * split out of this one root file with zero cross-checking between
     * them until this method existed: `splitToolchainConfig` used to just
     * copy each section's text verbatim, so nothing stopped someone
     * writing (or a working copy of this file being edited into) a
     * `default: win64` paired with a `target linux`, or the reverse.
     *
     * That specific combination is a real, silent bug, not merely an
     * unusual one: the two ABIs' argument-register counts and shadow-
     * space sizes differ, so a function whose parameters exceed
     * whichever register count `compiler.config`'s convention declares
     * gets its overflow argument's stack offset computed against the
     * *wrong* ABI's shadow-space size -- the callee ends up reading 32
     * bytes off from where the caller actually put the value (win64's
     * shadow space, present in the offset math but never physically
     * reserved by a `linux`-targeted caller). Found by hand-tracing real
     * stack addresses after a real test binary produced a wrong value/
     * segfault on exactly this combination; confirmed by hand that the
     * two "sensible" pairings (win64 default + windows_gnu
     * target; sysv_x64 default + linux target) both produce correct
     * offsets.
     *
     * This check is deliberately narrow -- it only compares the ABI
     * *shape* (argument-register counts, shadow-space size, and whether
     * argument position is one shared counter across both register banks
     * or two independent ones -- see `ArgCounters` on the ASTGenerator
     * side for what "shared" means) of `compiler.config`'s own *default*
     * convention against whatever ABI `codegen.config`'s target actually
     * implements. It does not, and cannot, validate a convention
     * selected per-function via `@call_convention(name)` -- that
     * decorator can legitimately name a convention whose shape doesn't
     * match the build's own target (an `extern` declaration describing a
     * foreign function's real ABI, say), and this orchestrator has no
     * way to know from `toolchain.config` alone which functions use one.
     * That narrower case is unchanged, pre-existing behavior, not
     * addressed here.
     */
    private static void validateAbiConsistency(String compilerCfgText, String codegenCfgText) throws IOException {
        String target = parseCodegenTargetFromText(codegenCfgText);
        CompilerAbiShape required = requiredAbiShapeFor(target);
        if (required == null) {
            // An unrecognized target is reported later, at the point
            // this orchestrator actually tries to assemble/link with it
            // (assembleAndLink's own "unknown codegen target" error) --
            // no ABI shape to check it against here.
            return;
        }

        Map<String, CompilerAbiShape> conventions = new LinkedHashMap<>();
        String defaultName = parseCallingConventions(compilerCfgText, conventions);
        if (defaultName == null) {
            throw new IOException("toolchain.config's '===compiler.config===' section is missing 'default: <conventionName>'");
        }
        CompilerAbiShape actual = conventions.get(defaultName);
        if (actual == null) {
            throw new IOException("toolchain.config's '===compiler.config===' section has 'default: " + defaultName
                    + "' but declares no calling convention by that name");
        }

        if (!actual.matches(required)) {
            throw new IOException("toolchain.config is self-contradictory: codegen target '" + target + "' uses the "
                    + required.abiName + " ABI (" + required.describe() + "), but compiler.config's default calling "
                    + "convention '" + defaultName + "' has a different shape (" + actual.describe() + "). A function "
                    + "with more parameters than " + defaultName + "'s own register count would have its overflow "
                    + "argument's stack offset computed against the wrong ABI's shadow-space size -- wrong values or "
                    + "a crash, only once that register count is actually exceeded. Fix by changing 'default' to a "
                    + "calling convention shaped like " + required.abiName + " (" + required.describe()
                    + "), or by changing codegen.config's target to one that matches " + defaultName + "'s own shape ("
                    + actual.describe() + ").");
        }
    }

    /** The real, fixed ABI shape a codegen target's own emitted assembly actually implements (see X86Backend's own isWinAbi()/argReg tables) -- null for a target this orchestrator doesn't recognize at all (reported separately, later). */
    private static CompilerAbiShape requiredAbiShapeFor(String target) {
        switch (target) {
            case "windows_gnu":
                return new CompilerAbiShape(4, 4, 32, true, "win64");
            case "linux":
                return new CompilerAbiShape(6, 8, 0, false, "SysV");
            default:
                return null;
        }
    }



    /**
     * A narrow, hand-written parser for just enough of `compiler.config`'s
     * own grammar (see the sibling component projects' own
     * `CompilerConfig.java` for the full, authoritative grammar this
     * mirrors -- no regex, matching this whole toolchain's "no regex,
     * ever" rule) to extract each declared convention's ABI shape and the
     * chosen default's name. Deliberately does not validate anything else
     * about the file (a malformed `cleanup`/`return-register`/`alignment`
     * value, an empty `argument-registers` list, etc.) -- each component
     * that actually loads this text as its own real `compiler.config`
     * already does that full validation itself, once it's written out and
     * that component's subprocess actually runs.
     */
    private static String parseCallingConventions(String text, Map<String, CompilerAbiShape> outShapes) {
        String[] lines = CompilerText.split(text, '\n', -1);
        String defaultName = null;

        String currentName = null;
        int currentIntRegs = 0, currentFloatRegs = 0, currentShadow = 0;
        boolean currentShared = false;
        int conventionIndent = -1;

        for (String raw : lines) {
            String noComment = stripCrAndComment(raw);
            String trimmed = noComment.trim();
            if (trimmed.isEmpty() || trimmed.equals("CallingConventions:")) {
                continue;
            }
            int indent = leadingWhitespace(noComment);

            if (startsWithLiteral(trimmed, "default:")) {
                defaultName = unquoteOrBare(trimmed.substring("default:".length()).trim());
                continue;
            }

            if (trimmed.endsWith(":") && colonIndexIgnoringTrailingColon(trimmed) < 0) {
                // A bare "name:" line with no "key: value" colon before
                // the trailing one -- a convention header, at whatever
                // indent the first one of these established.
                if (conventionIndent == -1) {
                    conventionIndent = indent;
                }
                if (indent == conventionIndent) {
                    if (currentName != null) {
                        outShapes.put(currentName, new CompilerAbiShape(currentIntRegs, currentFloatRegs, currentShadow, currentShared, null));
                    }
                    currentName = trimmed.substring(0, trimmed.length() - 1).trim();
                    currentIntRegs = 0;
                    currentFloatRegs = 0;
                    currentShadow = 0;
                    currentShared = false;
                    continue;
                }
            }

            if (currentName == null) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            switch (key) {
                case "argument-registers":
                    currentIntRegs = countBracketedListEntries(value);
                    break;
                case "argument-registers-float":
                    currentFloatRegs = countBracketedListEntries(value);
                    break;
                case "shadow-stack":
                    currentShadow = parseIntQuietly(value);
                    break;
                case "shared-argument-position":
                    currentShared = value.trim().equals("true");
                    break;
                default:
                    break; // cleanup/return-register(-float)/alignment -- not part of this narrow ABI-shape check
            }
        }
        if (currentName != null) {
            outShapes.put(currentName, new CompilerAbiShape(currentIntRegs, currentFloatRegs, currentShadow, currentShared, null));
        }
        return defaultName;
    }

    /** True only for a "name:" line, not a "key: value" line -- a "key: value" line's own colon sits before any trailing one, so this looks for exactly one colon and it being the last character. */
    private static int colonIndexIgnoringTrailingColon(String trimmed) {
        int idx = trimmed.indexOf(':');
        return (idx >= 0 && idx < trimmed.length() - 1) ? idx : -1;
    }

    private static int countBracketedListEntries(String text) {
        text = text.trim();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            return 0;
        }
        String inner = text.substring(1, text.length() - 1).trim();
        if (inner.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < inner.length(); i++) {
            if (inner.charAt(i) == ',') {
                count++;
            }
        }
        return count;
    }

    private static int parseIntQuietly(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1; // deliberately won't match any real required shape -- surfaces as a mismatch, not a crash here
        }
    }

    private static String parseCodegenTargetFromText(String codegenCfgText) {
        for (String line : CompilerText.split(codegenCfgText, '\n', -1)) {
            String t = stripCrAndComment(line).trim();
            if (t.isEmpty()) {
                continue;
            }
            String[] parts = CompilerText.splitSpace(t, 2);
            if (parts.length == 2 && parts[0].equals("target")) {
                return parts[1].trim().toLowerCase();
            }
        }
        return "";
    }

    private static String stripCrAndComment(String line) {
        String noCr = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        int i = 0;
        while (i < noCr.length() && (noCr.charAt(i) == ' ' || noCr.charAt(i) == '\t')) {
            i++;
        }
        if (i < noCr.length() && noCr.charAt(i) == '#') {
            return noCr.substring(0, i);
        }
        return noCr;
    }

    private static int leadingWhitespace(String s) {
        int n = 0;
        while (n < s.length() && (s.charAt(n) == ' ' || s.charAt(n) == '\t')) {
            n++;
        }
        return n;
    }

    private static boolean startsWithLiteral(String s, String prefix) {
        return s.length() >= prefix.length() && s.substring(0, prefix.length()).equals(prefix);
    }

    private static String unquoteOrBare(String text) {
        text = text.trim();
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** GNU as option that pads branches so none crosses or ends on a 32-byte boundary (the Intel Skylake-family JCC erratum workaround; binutils 2.34+) */
    private static final String JCC_PADDING_FLAG = "-mbranches-within-32B-boundaries";

    /** `key on|off` in codegen.config (default off) */
    private static boolean readCodegenFlag(Path codegenDir, String key) throws IOException {
        for (String line : Files.readAllLines(codegenDir.resolve("codegen.config"), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] parts = CompilerText.splitSpace(t, 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return parts[1].trim().equalsIgnoreCase("on");
            }
        }
        return false;
    }

    /** true when the assembler this target uses accepts the JCC padding option; otherwise a warning is printed and the option is left out */
    private static boolean assemblerSupportsJccPadding(String target) {
        boolean hostIsWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String tool = "as";
        if (target.equals("windows_gnu")) {
            tool = hostIsWindows ? "as" : "x86_64-w64-mingw32-as";
            if (!hostIsWindows && !commandExists(tool)) {
                tool = "as";
            }
        }
        try {
            Process p = new ProcessBuilder(tool, JCC_PADDING_FLAG, "--version").redirectErrorStream(true).start();
            drain(p.getInputStream());
            if (p.waitFor() == 0) {
                return true;
            }
        } catch (IOException | InterruptedException e) {
            // fall through to the warning
        }
        System.err.println("[warn] codegen.config says 'jcc-padding on', but '" + tool + "' does not accept " + JCC_PADDING_FLAG
                + " (binutils 2.34 or newer); building without it");
        return false;
    }

    private static String readCodegenTarget(Path codegenDir) throws IOException {
        for (String line : Files.readAllLines(codegenDir.resolve("codegen.config"), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] parts = CompilerText.splitSpace(t, 2);
            if (parts.length == 2 && parts[0].equals("target")) {
                return parts[1].trim().toLowerCase();
            }
        }
        throw new IOException("Codegen/codegen.config has no 'target' line");
    }

    // ---- reporting a stage's output ---------------------------------------

    /**
     * Classifies a stage's diagnostic lines ("[warning]", "[info]", "[note]", anything else) and prints them (the lines a stand-alone
     * run of the stage would print to stderr); a nonzero `exit` makes the stage fail.
     */
    private static void reportStageOutput(List<String> allLines, int exit, CompilerDiagnostics diag, String stageName, CompilerStageLog log) {
        List<String> warnings = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String line : allLines) {
            if (line.isBlank()) {
                continue;
            } else if (line.startsWith("[warning]")) {
                warnings.add(line);
            } else if (line.startsWith("[info]")) {
                infos.add(line);
            } else if (line.startsWith("[note]")) {
                notes.add(line);
            } else {
                errors.add(line);
            }
        }
        for (String info : infos) {
            System.err.println(info);
        }
        for (String note : notes) {
            System.err.println(note);
        }
        if (!diag.suppressWarnings()) {
            for (String w : warnings) {
                System.err.println(w);
            }
        }
        diag.addWarnings(warnings.size());
        if (log != null) {
            log.warnings.addAll(warnings);
            log.notes.addAll(notes);
        }
        if (exit != 0) {
            System.err.println("[error] " + stageName + " failed (exit " + exit + "):");
            for (String e : errors) {
                System.err.println("    " + e);
            }
            if (errors.isEmpty()) {
                System.err.println("    (no error output was captured -- exit code alone was nonzero)");
            }
            diag.fail(exit);
        } else {
            for (String line : errors) {
                System.err.println("[" + stageName + "] " + line);
            }
        }
    }

    /** Stable insertion sort: file name ascending, then line number descending (same order as the old List.sort). */
    private static void sortBlocks(List<String> blocks) {
        for (int i = 1; i < blocks.size(); i++) {
            String cur = blocks.get(i);
            int j = i - 1;
            while (j >= 0 && compareBlocks(blocks.get(j), cur) > 0) {
                blocks.set(j + 1, blocks.get(j));
                j--;
            }
            blocks.set(j + 1, cur);
        }
    }

    private static int compareBlocks(String x, String y) {
        String[] a1 = CompilerText.split(x, '\t', 0), b1 = CompilerText.split(y, '\t', 0);
        int c = a1[0].compareTo(b1[0]);
        return c != 0 ? c : Integer.compare(Integer.parseInt(b1[1]), Integer.parseInt(a1[1]));
    }

    static void drain(InputStream in) {
        try {
            byte[] buf = new byte[8192];
            while (in.read(buf) >= 0) {
                // discarded on purpose -- see runProcess's own doc comment
            }
        } catch (IOException ignored) {
            // the process exited; nothing left to drain
        }
    }

    // ---- codegen's embedded "not yet implemented" warnings --------------

    /**
     * Codegen doesn't print its "TODO(codegen): ... not yet
     * implemented" notices to stderr -- they're comments embedded
     * directly in the generated assembly text, one per mnemonic it
     * couldn't translate, so a partially-covered program's own output
     * makes clear exactly where it stops being trustworthy. This
     * orchestrator surfaces each one as a proper warning instead of
     * leaving them only discoverable by reading the .s file by hand.
     */
    private static void scanCodegenTodos(Path asmFile, CompilerDiagnostics diag) throws IOException {
        List<String> lines = Files.readAllLines(asmFile, StandardCharsets.UTF_8);
        int count = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.contains("TODO(codegen)")) {
                count++;
                if (!diag.suppressWarnings()) {
                    System.err.println("[warning] codegen: " + CompilerText.stripCommentLead(t));
                }
            }
        }
        diag.addWarnings(count);
    }

    // ---- final assemble + link ------------------------------------------

    /**
     * Turns Codegen's generated assembly into a real binary, using
     * whichever real toolchain matches the "target" Codegen/
     * codegen.config named (see toolchain.config's own comment on that
     * key) -- this is the one place this orchestrator picks an actual
     * external assembler/linker rather than a component of the
     * toolchain proper, since none of the four pipeline projects do
     * their own assembling.
     */
    private static void assembleAndLink(String target, Path asmFile, Path output, Path buildDir, CompilerDiagnostics diag, boolean jccPadding)
            throws IOException, InterruptedException {
        boolean hostIsWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        switch (target) {
            case "linux": {
                Path obj = buildDir.resolve("out.o");
                List<String> asCmd = new ArrayList<>(List.of("as", "--64"));
                if (jccPadding) {
                    asCmd.add(JCC_PADDING_FLAG);
                }
                asCmd.addAll(List.of(asmFile.toString(), "-o", obj.toString()));
                runToolProcess(asCmd, diag, "as");
                if (diag.hasFatalError()) return;
                runToolProcess(List.of("gcc", obj.toString(), "-o", output.toString(), "-no-pie", "-pthread", "-lm"), diag,
                        "gcc");
                return;
            }
            case "windows_gnu": {
                // Native gcc IS mingw-w64 when actually running on
                // Windows (what "gcc x86 windows" means in practice);
                // when cross-building from a non-Windows host instead,
                // the mingw-w64 cross-compiler is what provides a
                // matching "gcc" under its triplet-prefixed name.
                String compiler = hostIsWindows ? "gcc" : "x86_64-w64-mingw32-gcc";
                if (!hostIsWindows && !commandExists(compiler)) {
                    compiler = "gcc"; // fall back in case the host has its own cross-gcc set up under the plain name
                }
                // Codegen's output is always 64-bit assembly regardless of
                // host; force -m64 explicitly rather than trusting the
                // installed gcc/mingw's own default target (some mingw
                // builds default to 32-bit, which fails to assemble this
                // output with cryptic "only supported in 64-bit mode"
                // errors). "-static" is required here specifically because
                // this project's own real pthread_create/pthread_join/
                // pthread_exit stdlib bindings pull in mingw-w64's own
                // winpthreads: without it, the produced .exe dynamically
                // depends on libwinpthread-1.dll, which Wine (and a bare
                // Windows machine without mingw's runtime DLLs on PATH)
                // cannot resolve -- confirmed directly, this failed with
                // "Library libwinpthread-1.dll ... not found" under Wine
                // before "-static" was added. A plain, pthread-free program
                // (e.g. hello.caspien) links and runs identically either
                // way, so this is a strict improvement with no downside for
                // that case.
                List<String> ccCmd = new ArrayList<>(List.of(compiler, asmFile.toString(), "-o", output.toString(), "-m64", "-pthread",
                        "-static", "-lm", "-lntdll"));
                if (jccPadding) {
                    ccCmd.add("-Wa," + JCC_PADDING_FLAG);
                }
                runToolProcess(ccCmd, diag, compiler);
                return;
            }
            default:
                diag.fail(1);
                System.err.println("[error] unknown codegen target '" + target + "' -- expected 'linux' "
                        + "or 'windows_gnu'");
        }
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();
            drain(p.getInputStream());
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void runToolProcess(List<String> command, CompilerDiagnostics diag, String toolName)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process proc = pb.start();
        Thread stdoutDrain = new Thread(new CompilerDrain(proc.getInputStream()));
        stdoutDrain.setDaemon(true);
        stdoutDrain.start();

        List<String> errLines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    errLines.add(line);
                }
            }
        }
        int exit = proc.waitFor();
        stdoutDrain.join();

        // A real assembler/linker's own warnings (e.g. ld's missing
        // ".note.GNU-stack" notice) are routine and not this
        // toolchain's own concern -- shown only as plain info, never
        // treated as this orchestrator's warning count, and never
        // fatal by themselves.
        for (String line : errLines) {
            System.err.println("[" + toolName + "] " + line);
        }
        if (exit != 0) {
            System.err.println("[error] " + toolName + " failed (exit " + exit + ")");
            diag.fail(exit);
        }
    }

    // ---- diagnostics bookkeeping -----------------------------------------


}

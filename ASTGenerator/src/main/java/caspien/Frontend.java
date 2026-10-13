package caspien;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeSerializer;
import caspien.bytecodeemitter.BytecodeEmitter;
import caspien.bytecodeemitter.BytecodeEmitter;
import caspien.dupexpander.DupExpander;
import caspien.genericsexpander.GenericsExpander;
import caspien.importresolver.ImportResolver;
import caspien.lexer.Lexer;
import caspien.postpasses.AuditReport;
import caspien.parser.SourceParser;
import caspien.postpasses.FixTool;
import caspien.postpasses.TreeDump;
import caspien.postpasses.gasreport.GasReport;
import caspien.typechecker.TypeChecker;
import caspien.typechecker.TypeCheckerModel;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The front end as a library: a .caspien file in, higher-order bytecode (HOB) text out. Config comes in as text, results come
 * back in a {@link Result}; nothing is read from the working directory or the environment and nothing is printed.
 * The only files read are the program's own sources (the input, its imports, `ASM "file"`), because imports are resolved from disk.
 * The front end keeps static state (lexer/doc-comment registries, FsPolicy, TraceConfig, DepsLog, ...): use one class loader per
 * compile, as the orchestrator does. `Main` is the same thing as a stand-alone program.
 * Phases: lex, parse, imports, expansion, type check, emit into a BytecodeProgram, then serialize once.
 */
public final class Frontend {

    private Frontend() {
    }

    /** A failure whose message is printed exactly as given. */
    private static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    /**
     * `hob` = the program (null when `failed`); `messages` = the lines a stand-alone run prints to stderr, in order;
     * `deps` = every source file read; `reports` = optional texts by key: "audit", "fix", "fixHash", "fixBlocks", "viz", "debug".
     */
    public static final class Result {
        public final String hob;
        public final List<String> messages;
        public final boolean failed;
        public final List<String> deps;
        public final Map<String, String> reports;

        Result(String hob, List<String> messages, boolean failed, List<String> deps, Map<String, String> reports) {
            this.hob = hob;
            this.messages = messages;
            this.failed = failed;
            this.deps = deps;
            this.reports = reports;
        }
    }

    /**
     * @param inputPath absolute path of the main .caspien file
     * @param configs   texts by file name: "compiler.config" (required), "fs.config", "platform.config", "trace.config" (optional)
     * @param options   "root" (the project folder holding stdlib/, required); "fs-report", "audit", "audit-no-stdlib", "viz", "debug" = "1";
     *                  "fix-mode" (comma list; with "fix-write", "fix-stdlib" = "1", "fix-backup" = backup folder, "fix-only" = "file:line");
     *                  "fix-verify" = the expected hash of the HOB (fail when the HOB differs)
     */
    public static Result compile(String inputPath, Map<String, String> configs, Map<String, String> options) {
        List<String> messages = new ArrayList<>();
        Map<String, String> reports = new LinkedHashMap<>();
        String hobText = null;
        boolean failed = false;
        TypeCheckerModel.WARNINGS.clear();
        try {
            hobText = run(inputPath, configs, options, messages, reports);
        } catch (CompilerException ce) {
            messages.addAll(TypeCheckerModel.WARNINGS);
            TypeCheckerModel.WARNINGS.clear();
            messages.add(ce.getMessage());
            failed = true;
        } catch (Failure f) {
            messages.addAll(TypeCheckerModel.WARNINGS);
            TypeCheckerModel.WARNINGS.clear();
            messages.add(f.getMessage());
            failed = true;
        } catch (IOException ioe) {
            messages.addAll(TypeCheckerModel.WARNINGS);
            TypeCheckerModel.WARNINGS.clear();
            messages.add("[io error] " + ioe.getMessage());
            failed = true;
        }
        return new Result(failed ? null : hobText, messages, failed, DepsLog.files(), reports);
    }

    private static boolean on(Map<String, String> options, String key) {
        return "1".equals(options.get(key));
    }

    private static String run(String inputPath, Map<String, String> configs, Map<String, String> options, List<String> messages,
                              Map<String, String> reports) throws IOException {
        ProjectRoot.set(Paths.get(options.get("root")));
        FsPolicy.current = FsPolicy.parse(configs.get("fs.config"), "fs.config");
        FsPolicy.parsePlatform(configs.get("platform.config"));
        TraceConfig.parse(configs.get("trace.config"));
        boolean audit = on(options, "audit");
        boolean viz = on(options, "viz");
        String fixModes = options.get("fix-mode");

        DepsLog.record(inputPath);
        String source = new String(Files.readAllBytes(Paths.get(inputPath)), StandardCharsets.UTF_8);

        // __FILENAME of the top-level file is its base name, however the compiler was invoked.
        String displayFile = Paths.get(inputPath).getFileName().toString();
        List<Lexeme> tokens = new Lexer(source, inputPath, displayFile).tokenize();
        List<Token> parsed = SourceParser.parse(tokens);
        List<Token> resolved = new ImportResolver().resolve(parsed, inputPath);
        new DupExpander().expand(resolved);
        List<Token> expanded = new GenericsExpander().expand(resolved);

        CompilerConfig config = CompilerConfig.parse(configs.get("compiler.config"), "compiler.config");
        TraceConfig.scan(expanded);
        TypeChecker typeChecker = new TypeChecker(config);
        typeChecker.check(expanded);

        String auditReport = null;
        if (audit) {
            List<String> srcs = new ArrayList<>();
            for (String f : DepsLog.files()) {
                if (f.endsWith(".caspien")) {
                    srcs.add(f);
                }
            }
            auditReport = AuditReport.build(srcs, ProjectRoot.root(), typeChecker.model.unsafeTagsNeeded(), on(options, "audit-no-stdlib"));
        }

        BytecodeEmitter bytecodeEmitter = new BytecodeEmitter();
        BytecodeProgram hob = bytecodeEmitter.emit(expanded, typeChecker);
        String bytecode = new BytecodeSerializer().serialize(hob); // the only place the HOB becomes text, after emit() has finished
        GasReport.SPIN.putAll(bytecodeEmitter.spinBounds);
        if (fixModes == null && (!bytecodeEmitter.funcTermLabels.isEmpty() || !bytecodeEmitter.loopTermLabels.isEmpty())) {
            GasReport.checkLabels(bytecode, bytecodeEmitter.funcTermLabels, bytecodeEmitter.loopTermLabels);
        }
        messages.addAll(TypeCheckerModel.WARNINGS);
        TypeCheckerModel.WARNINGS.clear();

        if (audit) {
            GasReport.POS.putAll(bytecodeEmitter.sourcePos);
            reports.put("audit", auditReport + GasReport.build(bytecode).text);
        }

        String verify = options.get("fix-verify");
        if (verify != null && !verify.isEmpty() && !verify.trim().equals(FixTool.sha(bytecode))) {
            throw new Failure("[error] --fix verification: the higher-order bytecode differs after the edit");
        }
        if (fixModes != null && !fixModes.isEmpty()) {
            reports.put("fixHash", FixTool.sha(bytecode));
            FixTool.Run fix = FixTool.run(fixModes, on(options, "fix-write"), on(options, "fix-stdlib"), bytecodeEmitter, bytecode,
                    options.get("fix-backup"), typeChecker.model.unsafeSplitInfo(), options.get("fix-only"));
            reports.put("fix", fix.report);
            reports.put("fixBlocks", fix.blocks);
        }

        if (viz) {
            String page = GasReport.viz(bytecode, displayFile);
            if (page == null) {
                throw new Failure("[error] --viz: the program has no main function to draw");
            }
            reports.put("viz", page);
        }

        if (on(options, "fs-report")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
            typeChecker.impls.printFsReport(ps);
            ps.flush();
            messages.addAll(buf.toString(StandardCharsets.UTF_8).lines().toList());
        }

        if (on(options, "debug")) {
            StringBuilder debug = new StringBuilder();
            debug.append("# Caspien lexer output for ").append(inputPath).append("\n\n");
            for (Lexeme t : tokens) {
                debug.append(t.toString()).append("\n");
            }
            debug.append("\n# Caspien fully type-checked tree (every resolvedType annotated)\n\n");
            TreeDump.dumpLines(debug, expanded, 0);
            reports.put("debug", debug.toString());
        }
        return bytecode;
    }
}

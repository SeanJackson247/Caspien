package caspien.frontend;

import caspien.config.CompilerConfig;
import caspien.config.CompilerException;
import caspien.config.DepsLog;
import caspien.config.FsPolicy;
import caspien.config.ProjectRoot;
import caspien.config.TraceConfig;
import caspien.token.Lexeme;
import caspien.token.Token;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeSerializer;
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
import caspien.postpasses.FixRun;

/**
 * The front end as a library: a .caspien file in, higher-order bytecode (HOB) text out. Config comes in as text, results come
 * back in a {@link FrontendResult}; nothing is read from the working directory or the environment and nothing is printed.
 * The only files read are the program's own sources (the input, its imports, `ASM "file"`), because imports are resolved from disk.
 * The front end keeps static state (lexer/doc-comment registries, FsPolicy, TraceConfig, DepsLog, ...): use one class loader per
 * compile, as the orchestrator does. `Main` is the same thing as a stand-alone program.
 * Phases: lex, parse, imports, expansion, type check, emit into a BytecodeProgram, then serialize once.
 */
public final class Frontend {

    private Frontend() { }

    /**
     * @param inputPath absolute path of the main .caspien file
     * @param configs   texts by file name: "compiler.config" (required), "fs.config", "platform.config", "trace.config" (optional)
     * @param options   "root" (the project folder holding stdlib/, required); "fs-report", "audit", "audit-no-stdlib", "viz", "debug" = "1";
     *                  "fix-mode" (comma list; with "fix-write", "fix-stdlib" = "1", "fix-backup" = backup folder, "fix-only" = "file:line");
     *                  "fix-verify" = the expected hash of the HOB (fail when the HOB differs)
     */
    public static FrontendResult compile(String inputPath, Map<String, String> configs, Map<String, String> options) { return null; }

    private static boolean on(Map<String, String> options, String key) { return false; }

    private static String run(String inputPath, Map<String, String> configs, Map<String, String> options, List<String> messages,
                              Map<String, String> reports) throws IOException { return null; }
}

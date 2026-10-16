package caspien.postpasses;
import caspien.bytecodeemitter.BytecodeEmitter;
import caspien.config.CompilerException;
import caspien.config.DepsLog;
import caspien.config.DocComments;
import caspien.config.ProjectRoot;
import caspien.bytecodeemitter.BytecodeEmitter;
import caspien.postpasses.gasreport.GasReport;
import caspien.typechecker.TypeCheckerModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import caspien.typechecker.SplitInfo;
import caspien.config.DocComment;
import caspien.bytecode.Text;

/**
 * `--fix MODE[,MODE] [--write] [--fix-stdlib]`: the compiler as a tool that edits source, text only (it inserts whole comment lines and changes
 * nothing else). Modes:
 * <ul>
 * <li>`termination`: before every `func`, `for` and `loop` that has no `termination:` doc comment, insert one carrying the class the audit
 *     computes. A construct whose generic instances disagree, or that does not start its line, is listed and left alone. Existing labels are
 *     never changed (a stale one is reported; the compiler already refuses it).</li>
 * <li>`justify`: before every `unsafe` block that has no `justify:` doc comment, insert `/*! justify: TODO *&#47;`. The reason is for a
 *     person to write; the audit counts TODO as not yet justified. Existing justifications are never touched.</li>
 * </ul>
 * Without --write it only prints the plan. With --write the files are changed (originals saved so the orchestrator can restore them) and the
 * orchestrator rebuilds and compares the high-order bytecode: comments change no code, so it must be identical, else everything is restored.
 */
public final class FixTool {
    private final Map<String, TreeMap<Integer, List<String>>> inserts = new LinkedHashMap<>();   // file -> (insert-before line -> comment lines)
    private final List<String> plan = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int count;
    /** `unsafe-split`: whole-line replacements (file -> from line, to line (1-based, inclusive), new lines) and the written blocks they touch. */
    private final Map<String, List<Object[]>> replacements = new LinkedHashMap<>();
    private final Map<String, Set<Integer>> splitBlockLines = new LinkedHashMap<>();
    private final List<String> splitBlocks = new ArrayList<>();     // "file\tline" of every block that would change (the orchestrator's per-block fallback reads it)
    private int splits;

    /**
     * The bytecode as `--fix` compares it before and after an edit: source line numbers inside decorator lines are dropped (an edit that moves
     * lines moves them) and straight-line code after a `RET` up to the next label or the end of the function is dropped (unreachable; closing an
     * `unsafe` block early removes the drop code that sat after a `return` inside it). Everything else must be identical.
     */
    static String normalize(String hob) { return null; }

    public static String sha(String s) { return null; }

    private static String show(String file) { return null; }



    /** `only` (nullable) limits unsafe-split to one block, "file:line". */
    public static FixRun run(String modes, boolean write, boolean includeStdlib, BytecodeEmitter em, String hob, String backupDir,
                   List<SplitInfo> splitInfo, String only) throws IOException { return null; }

    private void planTermination(BytecodeEmitter em, String hob, List<String> files) throws IOException { }

    private void planJustify(List<String> files) throws IOException { }


    private static boolean isCommentLine(String l) { return false; }



    /**
     * `unsafe-split`: rewrite every written `unsafe` block (the type checker told us which tags each direct child statement of it needed) into
     * minimal blocks: adjacent statements with the same tag set share a block named with exactly those tags, a statement that needs no tag moves out
     * (an `unsafe` block is not a scope, so a `let` keeps its name). A bare `assume match` binds the rest of its block (a proof lives in the scope
     * it was made in). Blocks the plan cannot place (a tag used outside every statement, an unusual layout) are listed and left alone. Each new block
     * gets a `justify: TODO` scaffold; the block's existing justification stays on the first one.
     */
    private void planUnsafeSplit(List<SplitInfo> infos, List<String> files, String only) throws IOException { }

    private List<Object[]> replacementsFor(String file) { return null; }

    private Set<Integer> splitLinesFor(String file) { return null; }

    private void add(String file, int before, String text, String what) { }

    private static boolean startsWithKeyword(String line, String kw) { return false; }

    private static String indentOf(String line) { return null; }

    private static List<String> readLines(String file) throws IOException { return null; }

    private void apply(String backupDir) throws IOException { }
}

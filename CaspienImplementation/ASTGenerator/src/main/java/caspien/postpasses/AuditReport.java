package caspien.postpasses;
import caspien.config.DocComments;
import caspien.config.ProjectRoot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import caspien.config.DocComment;
import caspien.bytecode.Text;

/**
 * `--audit`: every use of `unsafe` in the source being compiled (the input and every file it imports), with file, line, the tags written
 * after the keyword and the text between the braces. Found by scanning the source text (comments, strings and character literals are skipped),
 * so it shows exactly what is written. For a statement block the type checker also says which tags the block really needed, which is the
 * useful figure for `unsafe unaudited{`.
 */
public final class AuditReport {


    private AuditReport() { }

    /** Line numbers of every `unsafe` statement block in a source text (what `--fix justify` labels). */
    static List<Integer> blockLines(String src) { return null; }

    static boolean isStdlib(Path p) { return false; }

    public static String build(List<String> files, Path base, Map<String, Set<String>> needed, boolean hideStdlib) throws IOException { return null; }

    /** The `justify:` doc comment standing before this block, or null. */
    private static DocComment justOf(AuditUse u, Path base) { return null; }

    private static void addTag(TreeMap<String, Integer> byTag, String tag) { }

    private static long countBlocks(List<AuditUse> uses) { return 0L; }

    private static int countNewlines(String s) { return 0; }

    private static List<AuditUse> concat(List<AuditUse> a, List<AuditUse> b) { return null; }

    private static void section(StringBuilder sb, String title, List<AuditUse> uses, Map<String, Set<String>> needed, Path base, boolean hide) { }

    private static String show(Path p, Path base) { return null; }

    // ---- scanner ------------------------------------------------------------------------------------------------------------------------------

    static List<AuditUse> scan(String file, String s) { return null; }

    private static int skipQuoted(String s, int i) { return 0; }
}

package caspien;

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

/**
 * `--audit`: every use of `unsafe` in the source being compiled (the input and every file it imports), with file, line, the tags written
 * after the keyword and the text between the braces. Found by scanning the source text (comments, strings and character literals are skipped),
 * so it shows exactly what is written. For a statement block the type checker also says which tags the block really needed, which is the
 * useful figure for `unsafe unaudited{`.
 */
final class AuditReport {
    private static final class Use {
        final String file;
        final int line;
        final boolean block;
        final String tags;
        final String text;     // the braces' content for a block, otherwise the source line
        final int firstLine;   // line number of the first content line
        Use(String file, int line, boolean block, String tags, String text, int firstLine) {
            this.file = file; this.line = line; this.block = block; this.tags = tags; this.text = text; this.firstLine = firstLine;
        }
    }

    private AuditReport() {
    }

    /** Line numbers of every `unsafe` statement block in a source text (what `--fix justify` labels). */
    static List<Integer> blockLines(String src) {
        List<Integer> out = new ArrayList<>();
        for (Use u : scan("", src)) {
            if (u.block) {
                out.add(u.line);
            }
        }
        return out;
    }

    static boolean isStdlib(Path p) {
        return p.startsWith(Paths.get("..", "stdlib").toAbsolutePath().normalize());
    }

    static String build(List<String> files, Path base, Map<String, Set<String>> needed, boolean hideStdlib) throws IOException {
        List<Use> mine = new ArrayList<>(), lib = new ArrayList<>();
        int fileCount = 0;
        for (String f : files) {
            Path p = Paths.get(f);
            String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            List<Use> uses = scan(show(p, base), src);
            if (!uses.isEmpty()) {
                fileCount++;
            }
            (isStdlib(p) ? lib : mine).addAll(uses);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# unsafe audit: ").append(files.size()).append(" source files (the input and everything it imports)\n");
        section(sb, "your code", mine, needed, base, false);
        section(sb, "standard library", lib, needed, base, hideStdlib);
        TreeMap<String, Integer> byTag = new TreeMap<>();
        int unaudited = 0, blocks = 0, exprs = 0, justified = 0, unjustifiedNone = 0, unjustifiedTodo = 0;
        for (Use u : concat(mine, lib)) {
            if (!u.block) { exprs++; continue; }
            blocks++;
            if (u.tags.isEmpty()) {
                byTag.merge("(none: declaration block)", 1, Integer::sum);
            }
            for (String t : u.tags.split(" ")) {
                if (!t.isEmpty()) {
                    byTag.merge(t, 1, Integer::sum);
                }
            }
            if (u.tags.equals("unaudited")) {
                unaudited++;
            }
            DocComments.Doc jd = justOf(u, base);
            if (jd == null) {
                unjustifiedNone++;
            } else if (DocComments.isTodo(jd.text)) {
                unjustifiedTodo++;
            } else {
                justified++;
            }
        }
        sb.append("\n# summary: ").append(blocks).append(" unsafe blocks (").append(mine.stream().filter(u -> u.block).count()).append(" in your code, ")
          .append(lib.stream().filter(u -> u.block).count()).append(" in the standard library) and ").append(exprs).append(" other uses of the keyword, in ")
          .append(fileCount).append(" files\n");
        sb.append("# unaudited blocks: ").append(unaudited).append(unaudited > 0 ? "  <- nobody has said why these are unsafe\n" : "\n");
        sb.append("# justified blocks: ").append(justified).append(" of ").append(blocks).append(" (").append(unjustifiedNone).append(" without a justify comment, ")
          .append(unjustifiedTodo).append(" with `justify: TODO`)\n");
        sb.append("# blocks naming each tag:");
        for (Map.Entry<String, Integer> e : byTag.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append('\n');
        return sb.toString();
    }

    /** The `justify:` doc comment standing before this block, or null. */
    private static DocComments.Doc justOf(Use u, Path base) {
        DocComments.Doc d = DocComments.find(base.resolve(u.file).toString(), u.line, "justify", "unsafe");
        return d != null ? d : DocComments.find(Paths.get(u.file).toAbsolutePath().normalize().toString(), u.line, "justify", "unsafe");
    }

    private static List<Use> concat(List<Use> a, List<Use> b) {
        List<Use> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static void section(StringBuilder sb, String title, List<Use> uses, Map<String, Set<String>> needed, Path base, boolean hide) {
        long blocks = uses.stream().filter(u -> u.block).count();
        sb.append("\n## ").append(title).append(": ").append(blocks).append(" blocks, ").append(uses.size() - blocks).append(" other uses");
        if (hide) {
            sb.append(" (not listed: --audit-no-stdlib)\n");
            return;
        }
        sb.append('\n');
        for (Use u : uses) {
            sb.append('\n').append(u.file).append(':').append(u.line).append("  ");
            if (!u.block) {
                sb.append("unsafe (not a block): ").append(u.text.trim()).append('\n');
                continue;
            }
            sb.append("unsafe").append(u.tags.isEmpty() ? "" : " " + u.tags).append(" {");
            Set<String> n = needed.get(Paths.get(base.resolve(u.file).toString()).toAbsolutePath().normalize() + ":" + u.line);
            if (n == null) {
                n = needed.get(Paths.get(u.file).toAbsolutePath().normalize() + ":" + u.line);
            }
            if (u.tags.equals("unaudited")) {
                sb.append("   UNAUDITED").append(n == null ? "" : "; it actually needs: " + (n.isEmpty() ? "nothing" : String.join(" ", n)));
            }
            sb.append('\n');
            DocComments.Doc jd = justOf(u, base);
            sb.append("       justify: ").append(jd == null ? "(none)" : DocComments.isTodo(jd.text) ? "TODO  <- not justified yet" : jd.text).append('\n');
            String[] lines = u.text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i == lines.length - 1 && lines[i].trim().isEmpty()) {
                    break;
                }
                sb.append(String.format("%6d | %s%n", u.firstLine + i, lines[i].replaceAll("\\s+$", "")));
            }
            sb.append("       }\n");
        }
    }

    private static String show(Path p, Path base) {
        Path abs = p.toAbsolutePath().normalize();
        if (abs.startsWith(Paths.get("..", "stdlib").toAbsolutePath().normalize().getParent())) {
            return base.relativize(abs).toString();
        }
        return abs.startsWith(base) ? base.relativize(abs).toString() : abs.toString();
    }

    // ---- scanner ------------------------------------------------------------------------------------------------------------------------------

    static List<Use> scan(String file, String s) {
        List<Use> out = new ArrayList<>();
        int n = s.length(), line = 1, i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') { line++; i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') { while (i < n && s.charAt(i) != '\n') i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) { if (s.charAt(i) == '\n') line++; i++; }
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') { i = skipQuoted(s, i); continue; }
            if (Character.isLetter(c) || c == '_') {
                int st = i;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                if (!s.substring(st, i).equals("unsafe")) continue;
                int useLine = line;
                // tags: words (with ':'), then '{' -> a block
                int j = i;
                List<String> tags = new ArrayList<>();
                boolean block = false;
                while (true) {
                    while (j < n && Character.isWhitespace(s.charAt(j))) j++;
                    if (j < n && s.charAt(j) == '{') { block = true; break; }
                    int w = j;
                    while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == ':')) j++;
                    if (j == w) break;
                    String word = s.substring(w, j);
                    if (word.endsWith(":") || j < n && s.charAt(j) == '<') break;   // `unsafe dyn:<u64>(...)` is an expression
                    tags.add(word);
                }
                if (!block) {
                    int ls = s.lastIndexOf('\n', st) + 1, le = s.indexOf('\n', st);
                    out.add(new Use(file, useLine, false, "", s.substring(ls, le < 0 ? n : le), useLine));
                    continue;
                }
                int open = j, depth = 0, k = open;
                while (k < n) {
                    char d = s.charAt(k);
                    if (d == '/' && k + 1 < n && s.charAt(k + 1) == '/') { while (k < n && s.charAt(k) != '\n') k++; continue; }
                    if (d == '/' && k + 1 < n && s.charAt(k + 1) == '*') {
                        k += 2;
                        while (k + 1 < n && !(s.charAt(k) == '*' && s.charAt(k + 1) == '/')) k++;
                        k += 2;
                        continue;
                    }
                    if (d == '"' || d == '\'') { k = skipQuoted(s, k); continue; }
                    if (d == '{') depth++;
                    if (d == '}') { depth--; if (depth == 0) break; }
                    k++;
                }
                String body = s.substring(open + 1, Math.min(k, n));
                int bodyLine = line + (int) s.substring(i, open + 1).chars().filter(x -> x == '\n').count();
                if (body.startsWith("\n")) { body = body.substring(1); bodyLine++; }
                out.add(new Use(file, useLine, true, String.join(" ", tags), body, bodyLine));
                // carry on scanning inside the block too: nested `unsafe` is its own instance
                continue;
            }
            i++;
        }
        return out;
    }

    private static int skipQuoted(String s, int i) {
        char q = s.charAt(i);
        int n = s.length(), k = i + 1;
        while (k < n && s.charAt(k) != q && s.charAt(k) != '\n') {
            if (s.charAt(k) == '\\') k++;
            k++;
        }
        return k + 1;
    }
}

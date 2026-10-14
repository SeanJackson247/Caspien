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
    static String normalize(String hob) {
        StringBuilder sb = new StringBuilder(hob.length());
        boolean dead = false;
        for (String ln : hob.split("\n", -1)) {
            if (dead) {
                if (ln.startsWith("@") && ln.endsWith(":") || ln.startsWith("FUNC_END")) {
                    dead = false;
                } else {
                    continue;
                }
            }
            if (ln.contains("_DECORATE ") && ln.endsWith("\"")) {
                ln = ln.replaceFirst(":\\d+\"$", "\"");
            }
            sb.append(ln).append('\n');
            if (ln.equals("RET") || ln.startsWith("RET ")) {
                dead = true;
            }
        }
        return sb.toString();
    }

    public static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(normalize(s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String show(String file) {
        Path abs = Paths.get(file).toAbsolutePath().normalize();
        Path base = ProjectRoot.root();
        return abs.startsWith(base) ? base.relativize(abs).toString() : abs.toString();
    }



    /** `only` (nullable) limits unsafe-split to one block, "file:line". */
    public static FixRun run(String modes, boolean write, boolean includeStdlib, BytecodeEmitter em, String hob, String backupDir,
                   List<SplitInfo> splitInfo, String only) throws IOException {
        FixTool t = new FixTool();
        Set<String> want = new LinkedHashSet<>(java.util.Arrays.asList(modes.split(",")));
        for (String m : want) {
            if (!m.equals("termination") && !m.equals("justify") && !m.equals("unsafe-split")) {
                throw new CompilerException("fix", "", 0, "unknown --fix mode '" + m + "' (modes: termination, justify, unsafe-split)");
            }
        }
        List<String> files = new ArrayList<>();
        for (String f : DepsLog.files()) {
            if (f.endsWith(".caspien") && (includeStdlib || !AuditReport.isStdlib(Paths.get(f)))) {
                files.add(f);
            }
        }
        if (want.contains("termination")) {
            t.planTermination(em, hob, files);
        }
        if (want.contains("unsafe-split")) {
            t.planUnsafeSplit(splitInfo, files, only);
        }
        if (want.contains("justify")) {
            t.planJustify(files);
        }
        String blocks = String.join("\n", t.splitBlocks) + "\n";
        StringBuilder sb = new StringBuilder();
        sb.append("# fix ").append(modes).append(write ? " (writing)" : " (dry run: nothing written, add --write)").append('\n');
        for (String p : t.plan) {
            sb.append(p).append('\n');
        }
        for (String n : t.notes) {
            sb.append("# note: ").append(n).append('\n');
        }
        if (t.splits > 0) {
            sb.append("# ").append(t.splits).append(" unsafe block").append(t.splits == 1 ? "" : "s").append(" rewritten into minimal blocks in ")
                    .append(t.replacements.size()).append(" file").append(t.replacements.size() == 1 ? "" : "s").append('\n');
        }
        sb.append("# ").append(t.count).append(" comment line").append(t.count == 1 ? "" : "s").append(" in ").append(t.inserts.size()).append(" file")
                .append(t.inserts.size() == 1 ? "" : "s").append('\n');
        if (write && (t.count > 0 || t.splits > 0)) {
            t.apply(backupDir);
        }
        return new FixRun(sb.toString(), blocks);
    }

    private void planTermination(BytecodeEmitter em, String hob, List<String> files) throws IOException {
        Set<String> mine = new LinkedHashSet<>();
        for (String f : files) {
            mine.add(DocComments.norm(f));
        }
        Map<String, String> classes = GasReport.classify(hob, em.termSites);
        // group generic instances: file|line|kind -> the set of classes
        Map<String, Set<String>> byConstruct = new LinkedHashMap<>();
        Map<String, String[]> first = new LinkedHashMap<>();
        for (String[] s : em.termSites) {
            if (s[2] == null || !mine.contains(DocComments.norm(s[2]))) {
                continue;
            }
            String w = classes.get(s[0] + "|" + (s[1] == null ? "" : s[1]));
            if (w == null) {
                continue;
            }
            String key = DocComments.norm(s[2]) + "|" + s[3] + "|" + s[4];
            byConstruct.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(w);
            first.putIfAbsent(key, s);
        }
        for (Map.Entry<String, Set<String>> e : byConstruct.entrySet()) {
            String[] s = first.get(e.getKey());
            String file = DocComments.norm(s[2]);
            int line = Integer.parseInt(s[3]);
            String kind = s[4];
            DocComment d = DocComments.find(s[2], line, "termination", kind);
            if (d != null) {
                if (e.getValue().size() == 1 && !e.getValue().contains(d.text)) {
                    notes.add(show(file) + ":" + line + " the `" + kind + "` is labelled `" + d.text + "` but its class is `" + e.getValue().iterator().next() + "` (labels are never changed by --fix)");
                }
                continue;
            }
            if (e.getValue().size() > 1) {
                notes.add(show(file) + ":" + line + " the `" + kind + "` has different classes in its generic instances (" + String.join(", ", e.getValue()) + "): not labelled");
                continue;
            }
            List<String> lines = readLines(file);
            if (line < 1 || line > lines.size() || !startsWithKeyword(lines.get(line - 1), kind)) {
                if (!s[0].startsWith("__")) {
                    notes.add(show(file) + ":" + line + " the `" + kind + "` does not start its line: not labelled");
                }
                continue;
            }
            int at = line;
            if (kind.equals("func")) {
                while (at > 1 && lines.get(at - 2).trim().startsWith("@")) {
                    at--;       // the label goes above the decorators
                }
            }
            String w = e.getValue().iterator().next();
            add(file, at, indentOf(lines.get(line - 1)) + "/*! termination: " + w + " */", kind + (kind.equals("func") ? " " + s[0] : ""));
        }
    }

    private void planJustify(List<String> files) throws IOException {
        for (String f : files) {
            String src = new String(Files.readAllBytes(Paths.get(f)), StandardCharsets.UTF_8);
            List<String> lines = readLines(DocComments.norm(f));
            for (int line : AuditReport.blockLines(src)) {
                if (DocComments.find(f, line, "justify", "unsafe") != null) {
                    continue;
                }
                if (splitBlockLines.getOrDefault(DocComments.norm(f), java.util.Collections.emptySet()).contains(line)) {
                    continue;       // being rewritten by unsafe-split, which writes its own justify scaffolds
                }
                if (line < 1 || line > lines.size() || !startsWithKeyword(lines.get(line - 1), "unsafe")) {
                    notes.add(show(f) + ":" + line + " the `unsafe` block does not start its line: not labelled");
                    continue;
                }
                add(DocComments.norm(f), line, indentOf(lines.get(line - 1)) + "/*! justify: TODO */", "unsafe");
            }
        }
    }

    private static final java.util.regex.Pattern OPEN_LINE = java.util.regex.Pattern.compile("^(\\s*)unsafe\\b[^{]*\\{\\s*(//.*)?$");
    private static final java.util.regex.Pattern CLOSE_LINE = java.util.regex.Pattern.compile("^\\s*\\}\\s*(//.*)?$");

    private static boolean isCommentLine(String l) {
        String t = l.trim();
        return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("@");     // a decorator line belongs to the statement below it
    }



    /**
     * `unsafe-split`: rewrite every written `unsafe` block (the type checker told us which tags each direct child statement of it needed) into
     * minimal blocks: adjacent statements with the same tag set share a block named with exactly those tags, a statement that needs no tag moves out
     * (an `unsafe` block is not a scope, so a `let` keeps its name). A bare `assume match` binds the rest of its block (a proof lives in the scope
     * it was made in). Blocks the plan cannot place (a tag used outside every statement, an unusual layout) are listed and left alone. Each new block
     * gets a `justify: TODO` scaffold; the block's existing justification stays on the first one.
     */
    private void planUnsafeSplit(List<SplitInfo> infos, List<String> files, String only) throws IOException {
        Set<String> mine = new LinkedHashSet<>();
        for (String f : files) {
            mine.add(DocComments.norm(f));
        }
        for (SplitInfo si : infos) {
            String file = DocComments.norm(si.file);
            if (!mine.contains(file)) {
                continue;
            }
            String where = show(file) + ":" + si.line;
            if (only != null && !only.isEmpty() && !only.equals(file + ":" + si.line)) {
                continue;
            }
            if (!si.unattributed.isEmpty()) {
                notes.add(where + " the `unsafe` block needs " + si.unattributed + " outside any single statement: not split");
                continue;
            }
            int n = si.childLine.size();
            if (n == 0) {
                continue;       // empty block, or one the checker never visited (an uninstantiated generic)
            }
            List<String> lines = readLines(file);
            int L = si.line;
            if (L < 1 || L > lines.size()) {
                continue;
            }
            java.util.regex.Matcher om = OPEN_LINE.matcher(lines.get(L - 1));
            if (!om.matches()) {
                String one = lines.get(L - 1);
                if (!one.contains("}")) {
                    notes.add(where + " the `unsafe` block does not open on a line of its own: not split");
                    continue;
                }
                // a one-line block has nothing to split, but `unaudited` can still be replaced by the tags it really needs
                Set<String> need = new java.util.TreeSet<>();
                for (Set<String> t : si.childTags) {
                    need.addAll(t);
                }
                java.util.regex.Matcher um = java.util.regex.Pattern.compile("\\bunsafe\\s+unaudited\\s*\\{").matcher(one);
                if (si.declared.contains("unaudited") && um.find() && !need.isEmpty()) {
                    String nl = one.substring(0, um.start()) + "unsafe " + String.join(" ", need) + "{" + one.substring(um.end());
                    List<String> repl = new ArrayList<>();
                    if (DocComments.find(si.file, L, "justify", "unsafe") == null) {
                        repl.add(indentOf(one) + "/*! justify: TODO */");
                    }
                    repl.add(nl);
                    replacements.computeIfAbsent(file, k -> new ArrayList<>()).add(new Object[]{L, L, repl});
                    splitBlockLines.computeIfAbsent(file, k -> new LinkedHashSet<>()).add(L);
                    splitBlocks.add(file + "\t" + L);
                    splits++;
                    plan.add(where + "  unsafe unaudited{ -> unsafe " + String.join(" ", need) + "{ (one line, retagged)");
                }
                continue;
            }
            String blockIndent = om.group(1);
            String openComment = om.group(2);
            int lastChild = si.childLine.get(n - 1);
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                int cl = si.childLine.get(i);
                if (cl <= L || cl > lines.size() || (i > 0 && cl <= si.childLine.get(i - 1))) {
                    ok = false;
                }
            }
            int close = -1;
            if (ok) {
                for (int i = lastChild + 1; i <= lines.size(); i++) {
                    String ln = lines.get(i - 1);
                    if (ln.trim().isEmpty()) {
                        continue;
                    }
                    if (indentOf(ln).equals(blockIndent) && CLOSE_LINE.matcher(ln).matches()) {
                        close = i;
                        break;
                    }
                    if (indentOf(ln).length() <= blockIndent.length()) {
                        break;
                    }
                }
            }
            String childIndent = ok ? indentOf(lines.get(si.childLine.get(0) - 1)) : "";
            if (close < 0 || !childIndent.startsWith(blockIndent) || childIndent.length() == blockIndent.length()) {
                notes.add(where + " the layout of the `unsafe` block is unusual (statements not indented under it, or no closing brace of its own): not split");
                continue;
            }
            for (int i = 0; i < n && ok; i++) {
                if (!indentOf(lines.get(si.childLine.get(i) - 1)).equals(childIndent) || isCommentLine(lines.get(si.childLine.get(i) - 1))) {
                    ok = false;
                }
            }
            if (!ok) {
                notes.add(where + " a statement of the `unsafe` block does not start its line: not split");
                continue;
            }
            // extents: child i owns the comment lines directly above it and everything up to the next child's own start
            int[] start = new int[n + 1];
            start[0] = L + 1;
            for (int i = 1; i < n; i++) {
                int j = si.childLine.get(i);
                while (j - 1 > si.childLine.get(i - 1) && isCommentLine(lines.get(j - 2))) {
                    j--;
                }
                start[i] = j;
            }
            start[n] = close;
            // units: tags per child, a bare `assume match` binds everything after it
            List<FixUnit> units = new ArrayList<>();
            boolean bound = false;
            for (int i = 0; i < n; i++) {
                FixUnit u = null;
                if (bound && !units.isEmpty()) {
                    u = units.get(units.size() - 1);
                    u.to = i;
                    u.tags = new java.util.TreeSet<>(u.tags);
                    u.tags.addAll(si.childTags.get(i));
                    continue;
                }
                u = new FixUnit();
                u.from = i;
                u.to = i;
                u.tags = new java.util.TreeSet<>(si.childTags.get(i));
                units.add(u);
                if (si.childAssume.get(i)) {
                    bound = true;
                }
            }
            List<FixUnit> groups = new ArrayList<>();
            for (FixUnit u : units) {
                FixUnit last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
                if (last != null && last.tags.equals(u.tags)) {
                    last.to = u.to;
                } else {
                    groups.add(u);
                }
            }
            if (groups.size() == 1 && groups.get(0).tags.equals(si.declared)) {
                continue;       // already minimal and exactly tagged (a missing justification is the `justify` mode's job)
            }
            // justification written above the block stays with the first new block
            int regionStart = L;
            List<String> justify = null;
            DocComment jd = DocComments.find(si.file, L, "justify", "unsafe");
            if (jd != null && jd.commentLine >= 1 && jd.commentLine < L) {
                regionStart = jd.commentLine;
                justify = new ArrayList<>(lines.subList(jd.commentLine - 1, L - 1));
            }
            List<String> out = new ArrayList<>();
            boolean firstTagged = true;
            String unit = childIndent.substring(blockIndent.length());
            StringBuilder desc = new StringBuilder();
            int plain = 0;
            for (FixUnit g : groups) {
                int from = start[g.from], to = start[g.to + 1] - 1;
                List<String> body = new ArrayList<>(lines.subList(from - 1, to));
                List<String> trailing = new ArrayList<>();
                while (!body.isEmpty() && body.get(body.size() - 1).trim().isEmpty()) {
                    trailing.add(0, body.remove(body.size() - 1));
                }
                if (g.tags.isEmpty()) {
                    for (String b : body) {
                        out.add(b.startsWith(unit) ? b.substring(unit.length()) : b);
                    }
                    plain += g.to - g.from + 1;
                } else {
                    if (firstTagged && justify != null) {
                        out.addAll(justify);
                    } else {
                        out.add(blockIndent + "/*! justify: TODO */");
                    }
                    out.add(blockIndent + "unsafe " + String.join(" ", g.tags) + "{" + (firstTagged && openComment != null ? " " + openComment : ""));
                    out.addAll(body);
                    out.add(blockIndent + "}");
                    firstTagged = false;
                    desc.append(desc.length() == 0 ? "" : ", ").append("unsafe ").append(String.join(" ", g.tags)).append(" (").append(g.to - g.from + 1 == 1 ? "1 statement" : (start.length > 0 ? (g.to - g.from + 1) + " statements" : "")).append(")");
                }
                out.addAll(trailing);
            }
            List<String> oldRegion = new ArrayList<>(lines.subList(regionStart - 1, close));
            if (out.equals(oldRegion)) {
                continue;
            }
            replacements.computeIfAbsent(file, k -> new ArrayList<>()).add(new Object[]{regionStart, close, out});
            splitBlockLines.computeIfAbsent(file, k -> new LinkedHashSet<>()).add(L);
            splitBlocks.add(file + "\t" + L);
            splits++;
            plan.add(where + "  unsafe " + String.join(" ", si.declared) + "{ (" + n + " statement" + (n == 1 ? "" : "s") + ") -> "
                    + (desc.length() == 0 ? "no unsafe needed" : desc.toString()) + (plain > 0 ? "; " + plain + " statement" + (plain == 1 ? "" : "s") + " outside" : ""));
        }
    }

    private void add(String file, int before, String text, String what) {
        inserts.computeIfAbsent(file, k -> new TreeMap<>()).computeIfAbsent(before, k -> new ArrayList<>()).add(text);
        plan.add(show(file) + ":" + before + "  + " + text.trim() + "    (" + what + ")");
        count++;
    }

    private static boolean startsWithKeyword(String line, String kw) {
        String t = line.trim();
        if (!t.startsWith(kw)) {
            return false;
        }
        return t.length() == kw.length() || !(Character.isLetterOrDigit(t.charAt(kw.length())) || t.charAt(kw.length()) == '_');
    }

    private static String indentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static List<String> readLines(String file) throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        List<String> l = new ArrayList<>(java.util.Arrays.asList(content.split("\r?\n", -1)));
        return l;
    }

    private void apply(String backupDir) throws IOException {
        StringBuilder manifest = new StringBuilder();
        int n = 0;
        Map<String, TreeMap<Integer, List<String>>> all = new LinkedHashMap<>(inserts);
        for (String f : replacements.keySet()) {
            all.putIfAbsent(f, new TreeMap<>());
        }
        for (Map.Entry<String, TreeMap<Integer, List<String>>> e : all.entrySet()) {
            Path p = Paths.get(e.getKey());
            String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            String eol = content.contains("\r\n") ? "\r\n" : "\n";
            List<String> lines = new ArrayList<>(java.util.Arrays.asList(content.split("\r?\n", -1)));
            // every edit as (position, lines removed, lines added), applied from the bottom up so earlier line numbers stay valid
            TreeMap<Integer, Object[]> edits = new TreeMap<>(java.util.Collections.reverseOrder());
            for (Map.Entry<Integer, List<String>> ins : e.getValue().entrySet()) {
                edits.put(ins.getKey() * 2 + 1, new Object[]{ins.getKey(), 0, ins.getValue()});    // an insertion sorts after a replacement that starts on the same line
            }
            for (Object[] rep : replacements.getOrDefault(e.getKey(), new ArrayList<>())) {
                int from = (Integer) rep[0], to = (Integer) rep[1];
                @SuppressWarnings("unchecked") List<String> nl = (List<String>) rep[2];
                edits.put(from * 2, new Object[]{from, to - from + 1, nl});
            }
            for (Object[] ed : edits.values()) {
                int at = (Integer) ed[0] - 1, del = (Integer) ed[1];
                @SuppressWarnings("unchecked") List<String> add = (List<String>) ed[2];
                for (int k = 0; k < del; k++) {
                    lines.remove(at);
                }
                lines.addAll(at, add);
            }
            if (backupDir != null) {
                Path bak = Paths.get(backupDir).resolve((n++) + ".bak");
                Files.write(bak, content.getBytes(StandardCharsets.UTF_8));
                manifest.append(p).append('\t').append(bak).append('\n');
            }
            Files.write(p, String.join(eol, lines).getBytes(StandardCharsets.UTF_8));
        }
        if (backupDir != null) {
            Files.write(Paths.get(backupDir).resolve("manifest.txt"), manifest.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}

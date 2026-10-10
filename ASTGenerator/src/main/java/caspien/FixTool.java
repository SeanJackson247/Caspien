package caspien;

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
final class FixTool {
    private final Map<String, TreeMap<Integer, List<String>>> inserts = new LinkedHashMap<>();   // file -> (insert-before line -> comment lines)
    private final List<String> plan = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int count;

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
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
        Path base = Paths.get("..").toAbsolutePath().normalize();
        return abs.startsWith(base) ? base.relativize(abs).toString() : abs.toString();
    }

    static String run(String modes, boolean write, boolean includeStdlib, BytecodeEmitter em, String hob, String backupDir) throws IOException {
        FixTool t = new FixTool();
        Set<String> want = new LinkedHashSet<>(java.util.Arrays.asList(modes.split(",")));
        for (String m : want) {
            if (!m.equals("termination") && !m.equals("justify")) {
                throw new CompilerException("fix", "", 0, "unknown --fix mode '" + m + "' (modes: termination, justify)");
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
        if (want.contains("justify")) {
            t.planJustify(files);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# fix ").append(modes).append(write ? " (writing)" : " (dry run: nothing written, add --write)").append('\n');
        for (String p : t.plan) {
            sb.append(p).append('\n');
        }
        for (String n : t.notes) {
            sb.append("# note: ").append(n).append('\n');
        }
        sb.append("# ").append(t.count).append(" comment line").append(t.count == 1 ? "" : "s").append(" in ").append(t.inserts.size()).append(" file")
                .append(t.inserts.size() == 1 ? "" : "s").append('\n');
        if (write && t.count > 0) {
            t.apply(backupDir);
        }
        return sb.toString();
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
            DocComments.Doc d = DocComments.find(s[2], line, "termination", kind);
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
                if (line < 1 || line > lines.size() || !startsWithKeyword(lines.get(line - 1), "unsafe")) {
                    notes.add(show(f) + ":" + line + " the `unsafe` block does not start its line: not labelled");
                    continue;
                }
                add(DocComments.norm(f), line, indentOf(lines.get(line - 1)) + "/*! justify: TODO */", "unsafe");
            }
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
        for (Map.Entry<String, TreeMap<Integer, List<String>>> e : inserts.entrySet()) {
            Path p = Paths.get(e.getKey());
            String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            String eol = content.contains("\r\n") ? "\r\n" : "\n";
            List<String> lines = new ArrayList<>(java.util.Arrays.asList(content.split("\r?\n", -1)));
            for (Map.Entry<Integer, List<String>> ins : e.getValue().descendingMap().entrySet()) {
                lines.addAll(ins.getKey() - 1, ins.getValue());
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

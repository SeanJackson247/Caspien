package caspien.lowerorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loop rotation (`loop-rotation: on`), the last register-form pass. A `for` (or `loop` that starts with an exit test) is emitted as
 *
 *   @X:  [pure header lines] ; R_BRC C s a b @E          (leave when NOT (a C b))
 *        body
 *        JMP @X
 *   @E:
 *
 * so every iteration executes a conditional jump AND an unconditional jump. The rotated form copies the header to the bottom:
 *
 *   @X:  [header] ; R_BRC C s a b @E                      (entry test, runs once)
 *   @Xr:
 *        body
 *        [header] ; R_BRC C' s a b @Xr                    (C' = the opposite condition: continue while (a C b))
 *   @E:
 *
 * one conditional jump per iteration. The original header stays where it is (it still serves the entry and any other jump to @X), so
 * the transformation is exact: the copy executes the same pure lines in the same register state as the next iteration's header would.
 * Applied only when
 *   - the back edge `JMP @X` is directly followed by @E (the target of the header's exit branch), so the fall-through of the copy lands
 *     where the original jump-to-exit landed;
 *   - the header is at most three lines that only define temporaries (`R_MOV`/`R_LD`/`R_LDI`/`R_LDD` into %t, simple `R_BIN` into %t)
 *     followed by an `R_BRC` or `R_BRCM` whose condition has an opposite; no label, call, store or other side effect in between.
 */
public class LoopRotationPass {

    private static final Map<String, String> OPPOSITE = Map.ofEntries(
            Map.entry("EQ", "NEQ"), Map.entry("NEQ", "EQ"),
            Map.entry("LT", "GT_EQ"), Map.entry("GT_EQ", "LT"),
            Map.entry("LT_EQ", "GT"), Map.entry("GT", "LT_EQ"),
            Map.entry("SLT", "SGT_EQ"), Map.entry("SGT_EQ", "SLT"),
            Map.entry("SLT_EQ", "SGT"), Map.entry("SGT", "SLT_EQ"));
    private static final java.util.Set<String> SIMPLE_BIN = java.util.Set.of("ADD", "SUB", "MUL", "AND", "OR", "BAND", "BOR", "BXOR", "SHL", "SHR", "SAR");
    private static final int MAX_HEADER_PURE_LINES = 3;

    private final boolean enabled;

    public LoopRotationPass(boolean enabled) {
        this.enabled = enabled;
    }

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return lines;
        }
        int n = lines.size();
        Map<String, Integer> labelAt = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (isLabel(lines.get(i))) {
                String t = t(lines.get(i), 0);
                labelAt.put(t.substring(0, t.length() - 1), i);
            }
        }
        Map<Integer, List<List<BytecodeToken>>> replaceAt = new HashMap<>();   // index of the back edge JMP -> lines that replace it
        Map<Integer, String> bodyLabelAfter = new HashMap<>();                  // index of the header branch -> new label inserted after it
        int counter = 0;
        for (int j = 0; j < n; j++) {
            List<BytecodeToken> jmp = lines.get(j);
            if (!isJmp(jmp)) {
                continue;
            }
            Integer at = labelAt.get(t(jmp, 1));
            if (at == null || at >= j) {
                continue;
            }
            int h = at + 1;
            while (h < j && isLabel(lines.get(h))) {
                h++;                       // other labels on the same address
            }
            int b = h;
            int pure = 0;
            while (b < j && isPure(lines.get(b)) && pure <= MAX_HEADER_PURE_LINES) {
                b++;
                pure++;
            }
            if (b >= j || pure > MAX_HEADER_PURE_LINES) {
                continue;
            }
            List<BytecodeToken> br = lines.get(b);
            if (!isBranch(br) || !OPPOSITE.containsKey(t(br, 1))) {
                continue;
            }
            String exit = t(br, 5);
            if (exit.equals(t(jmp, 1)) || !labelRunAfterContains(lines, j + 1, exit)) {
                continue;
            }
            // an existing replacement or insertion for this header means a second back edge to it: only the one before @E qualifies
            if (replaceAt.containsKey(j)) {
                continue;
            }
            String body = bodyLabelAfter.get(b);
            if (body == null) {
                body = "@loop_rot_" + (counter++);
                bodyLabelAfter.put(b, body);
            }
            List<List<BytecodeToken>> copy = new ArrayList<>();
            for (int k = h; k < b; k++) {
                copy.add(lines.get(k));
            }
            List<BytecodeToken> inv = new ArrayList<>(br);
            BytecodeToken c = br.get(1);
            inv.set(1, new BytecodeToken(OPPOSITE.get(c.text), c.file, c.line, c.kind));
            inv.set(5, new BytecodeToken(body, br.get(5).file, br.get(5).line, br.get(5).kind));
            copy.add(inv);
            replaceAt.put(j, copy);
        }
        if (replaceAt.isEmpty()) {
            return lines;
        }
        List<List<BytecodeToken>> out = new ArrayList<>(n + replaceAt.size() * 3);
        for (int i = 0; i < n; i++) {
            List<List<BytecodeToken>> rep = replaceAt.get(i);
            if (rep != null) {
                out.addAll(rep);
            } else {
                out.add(lines.get(i));
            }
            String lab = bodyLabelAfter.get(i);
            if (lab != null && usedBy(replaceAt, lab)) {
                BytecodeToken ref = lines.get(i).get(0);
                List<BytecodeToken> l = new ArrayList<>();
                l.add(new BytecodeToken(lab + ":", ref.file, ref.line, ref.kind));
                out.add(l);
            }
        }
        return out;
    }

    private static boolean usedBy(Map<Integer, List<List<BytecodeToken>>> replaceAt, String label) {
        for (List<List<BytecodeToken>> r : replaceAt.values()) {
            if (t(r.get(r.size() - 1), 5).equals(label)) {
                return true;
            }
        }
        return false;
    }

    /** True when the lines starting at `from` are labels only (up to the first non-label) and one of them is `label:`. */
    private static boolean labelRunAfterContains(List<List<BytecodeToken>> in, int from, String label) {
        for (int j = from; j < in.size(); j++) {
            List<BytecodeToken> l = in.get(j);
            if (!isLabel(l)) {
                return false;
            }
            if (t(l, 0).equals(label + ":")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPure(List<BytecodeToken> l) {
        if (l.isEmpty()) {
            return false;
        }
        String m = t(l, 0);
        switch (m) {
            case "R_MOV":
            case "R_LD":
                return l.size() == 4 && isTemp(t(l, 2));
            case "R_LDI":
                return l.size() >= 6 && isTemp(t(l, 2));
            case "R_LDD":
                return l.size() == 5 && isTemp(t(l, 2));
            case "R_BIN":
                return l.size() == 6 && SIMPLE_BIN.contains(t(l, 1)) && isTemp(t(l, 3));
            default:
                return false;
        }
    }

    private static boolean isTemp(String s) {
        return s.length() == 3 && s.startsWith("%t");
    }

    private static boolean isBranch(List<BytecodeToken> l) {
        return l.size() == 6 && (t(l, 0).equals("R_BRC") || t(l, 0).equals("R_BRCM")) && t(l, 5).startsWith("@");
    }

    private static boolean isLabel(List<BytecodeToken> l) {
        return l.size() == 1 && t(l, 0).startsWith("@") && t(l, 0).endsWith(":");
    }

    private static boolean isJmp(List<BytecodeToken> l) {
        return l.size() == 2 && t(l, 0).equals("JMP") && t(l, 1).startsWith("@");
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }
}

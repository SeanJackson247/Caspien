package caspien.lowerorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Conditional moves (`conditional-move: on`). A select between two register-or-constant values of a promoted variable is a compare and a branch
 * around a move; this pass turns the two shapes
 *
 *   one arm:   R_BRC C n a b @E ; R_MOV 8 %vK X | R_SETV m %vK #c ; @E:
 *   two arms:  R_BRC C n a b @L ; R_MOV 8 %vK X ; JMP @E ; @L: ; R_MOV 8 %vK Y ; @E:      (the labels are used only by these jumps)
 *
 * into one {@code R_CMOV C n a b %vK X [Y]}: %vK = (a C b) ? X : Y (one arm: %vK keeps its value when the test fails). The backend emits
 * `cmp`, `mov Y` (which leaves the flags alone) and `cmovCC X`: no jump, no mispredict. X and Y are a register or an immediate (a SETV
 * constant is cut to its width here); a source that reads memory is never speculated. Two arms are only merged when X is not %vK itself.
 */
public class ConditionalMovePass {

    private final boolean enabled;

    public ConditionalMovePass(boolean enabled) {
        this.enabled = enabled;
    }

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return lines;
        }
        int n = lines.size();
        Map<String, Integer> refs = new HashMap<>();
        for (List<BytecodeToken> l : lines) {
            if (l.size() == 6 && (t(l, 0).equals("R_BRC") || t(l, 0).equals("R_BRCM"))) {
                refs.merge(t(l, 5), 1, Integer::sum);
            } else if (l.size() == 2 && t(l, 0).equals("JMP")) {
                refs.merge(t(l, 1), 1, Integer::sum);
            } else {
                for (int k = 1; k < l.size(); k++) {
                    if (t(l, k).startsWith("@") && !t(l, k).endsWith(":")) {
                        refs.merge(t(l, k), 100, Integer::sum);     // any other mention (switch tables, address-of): never touch
                    }
                }
            }
        }
        List<List<BytecodeToken>> out = new ArrayList<>(n);
        boolean changed = false;
        int i = 0;
        while (i < n) {
            List<BytecodeToken> br = lines.get(i);
            if (br.size() == 6 && t(br, 0).equals("R_BRC") && t(br, 2).matches("[1248]") && i + 2 < n) {
                String lbl = t(br, 5);
                // one arm
                String[] x = source(lines.get(i + 1));
                if (x != null && isLabel(lines.get(i + 2), lbl)) {
                    out.add(cmov(br, x[0], x[1], null));
                    out.add(lines.get(i + 2));
                    i += 3;
                    changed = true;
                    continue;
                }
                // two arms
                if (x != null && i + 5 < n && refs.getOrDefault(lbl, 0) == 1 && isJmp(lines.get(i + 2)) && isLabel(lines.get(i + 3), lbl)) {
                    String end = t(lines.get(i + 2), 1);
                    String[] y = source(lines.get(i + 4));
                    if (y != null && x[0].equals(y[0]) && isLabel(lines.get(i + 5), end) && refs.getOrDefault(end, 0) == 1 && !x[1].equals(x[0])) {
                        out.add(cmov(br, x[0], x[1], y[1]));
                        out.add(lines.get(i + 5));
                        i += 6;
                        changed = true;
                        continue;
                    }
                }
            }
            out.add(br);
            i++;
        }
        return changed ? out : lines;
    }

    /** {variable, source} of `R_MOV 8 %vK src` (register or immediate) or `R_SETV m %vK #c` (the constant cut to m bytes, zero-extended), else null */
    private static String[] source(List<BytecodeToken> l) {
        if (l.size() != 4 || !t(l, 2).matches("%v[0-9]")) {
            return null;
        }
        String src = t(l, 3);
        if (t(l, 0).equals("R_MOV") && t(l, 1).equals("8") && (src.startsWith("%t") || src.startsWith("%v") || isNumImm(src))) {
            return new String[] {t(l, 2), src};
        }
        if (t(l, 0).equals("R_SETV") && isNumImm(src) && t(l, 1).matches("[1248]")) {
            int m = Integer.parseInt(t(l, 1));
            long v = Long.decode(src.substring(1));
            if (m < 8) {
                v &= (1L << (8 * m)) - 1;
            }
            return new String[] {t(l, 2), "#" + v};
        }
        return null;
    }

    private static boolean isNumImm(String s) {
        if (!s.startsWith("#") || s.length() < 2) {
            return false;
        }
        try {
            Long.decode(s.substring(1));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static List<BytecodeToken> cmov(List<BytecodeToken> br, String v, String x, String y) {
        BytecodeToken at = br.get(0);
        List<BytecodeToken> r = new ArrayList<>();
        r.add(new BytecodeToken("R_CMOV", at.file, at.line, at.kind));
        r.add(br.get(1));
        r.add(br.get(2));
        r.add(br.get(3));
        r.add(br.get(4));
        r.add(new BytecodeToken(v, at.file, at.line, at.kind));
        r.add(new BytecodeToken(x, at.file, at.line, at.kind));
        if (y != null) {
            r.add(new BytecodeToken(y, at.file, at.line, at.kind));
        }
        return r;
    }

    private static boolean isLabel(List<BytecodeToken> l, String name) {
        return l.size() == 1 && t(l, 0).equals(name + ":");
    }

    private static boolean isJmp(List<BytecodeToken> l) {
        return l.size() == 2 && t(l, 0).equals("JMP");
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }
}

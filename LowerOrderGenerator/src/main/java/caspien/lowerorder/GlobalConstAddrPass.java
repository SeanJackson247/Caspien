package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;

/**
 * A global array element at a constant index is one RIP-relative instruction. Runs after {@link IndexedAccessPass} (always on;
 * no register-form lines, nothing to match):
 * <pre>
 *   R_LEA %t0 &amp;W #9 8 ; R_LD 8 %v6 %t0      -&gt;   R_LD 8 %v6 &amp;W+72
 *   R_LEA %t0 &amp;W #16 8 ; R_ST 8 %t0 %t1     -&gt;   R_ST 8 &amp;W+128 %t1
 *   R_LEA %t0 &amp;f #3 8 ; R_LDX 8 %y0 %t0     -&gt;   R_LDX 8 %y0 &amp;f+24      (and R_STX)
 * </pre>
 * The address token {@code &sym+N} is a global plus a constant byte offset; the backend writes it as {@code sym+N(%rip)}.
 * Only widths 1, 2, 4, 8 (the backend's odd widths go through an address register), the lea temp must be dead after the access and
 * not read by it other than as the address, and every line between lea and access must be a known register-form line that does
 * not mention the temp (same rules as {@link FieldDisplacementPass}).
 */
public class GlobalConstAddrPass {

    private static final int MAX_GAP = 40;

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        int n = lines.size();
        List<List<BytecodeToken>> cur = new ArrayList<>(lines);
        boolean[] gone = new boolean[n];
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            if (gone[i]) {
                continue;
            }
            List<BytecodeToken> lea = cur.get(i);
            String sym = symbolWithOffset(lea);
            if (sym == null) {
                continue;
            }
            String x = t(lea, 1);
            for (int j = i + 1; j < n && j - i <= MAX_GAP; j++) {
                if (gone[j]) {
                    continue;
                }
                List<BytecodeToken> l = cur.get(j);
                if (!IndexedAccessPass.mentions(l, x)) {
                    if (!IndexedAccessPass.passable(l, x)) {
                        break;
                    }
                    continue;
                }
                List<BytecodeToken> fused = fuse(l, x, sym);
                boolean self = fused != null && fused.get(0).text.equals("R_LD") && t(l, 2).equals(x);
                if (fused != null && IndexedAccessPass.deadAfter(cur, gone, j + 1, x, self)) {
                    cur.set(j, fused);
                    gone[i] = true;
                    changed = true;
                }
                break;
            }
        }
        if (!changed) {
            return lines;
        }
        List<List<BytecodeToken>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!gone[i]) {
                out.add(cur.get(i));
            }
        }
        return out;
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }

    /** `R_LEA %tX &sym #k scale [extra]` -> "&sym+N" (N = k*scale + extra >= 0), else null */
    private static String symbolWithOffset(List<BytecodeToken> l) {
        if ((l.size() != 5 && l.size() != 6) || !t(l, 0).equals("R_LEA") || !t(l, 1).startsWith("%t") || !t(l, 2).startsWith("&")
                || t(l, 2).indexOf('+') >= 0 || !t(l, 3).startsWith("#")) {
            return null;
        }
        try {
            long k = Long.parseLong(t(l, 3).substring(1));
            long scale = Long.parseLong(t(l, 4));
            long extra = l.size() == 6 ? Long.parseLong(t(l, 5)) : 0L;
            if (!(scale == 1 || scale == 2 || scale == 4 || scale == 8)) {
                return null;
            }
            long off = k * scale + extra;
            if (off < 0 || off > Integer.MAX_VALUE) {
                return null;
            }
            return off == 0 ? t(l, 2) : t(l, 2) + "+" + off;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean okWidth(String s) {
        return s.equals("1") || s.equals("2") || s.equals("4") || s.equals("8");
    }

    private static List<BytecodeToken> fuse(List<BytecodeToken> l, String x, String sym) {
        BytecodeToken h = l.get(0);
        List<BytecodeToken> n = new ArrayList<>();
        String m = t(l, 0);
        if (m.equals("R_LD") && l.size() == 4 && okWidth(t(l, 1)) && t(l, 3).equals(x)) {
            n.add(h);
            n.add(l.get(1));
            n.add(l.get(2));
            n.add(new BytecodeToken(sym, h.file, h.line, h.kind));
            return n;
        }
        if (m.equals("R_ST") && l.size() == 4 && okWidth(t(l, 1)) && t(l, 2).equals(x) && !t(l, 3).equals(x)) {
            n.add(h);
            n.add(l.get(1));
            n.add(new BytecodeToken(sym, h.file, h.line, h.kind));
            n.add(l.get(3));
            return n;
        }
        if (m.equals("R_LDX") && l.size() == 4 && (t(l, 1).equals("4") || t(l, 1).equals("8")) && t(l, 3).equals(x)
                && (t(l, 2).startsWith("%x") || t(l, 2).startsWith("%y") || t(l, 2).startsWith("%z"))) {
            n.add(h);
            n.add(l.get(1));
            n.add(l.get(2));
            n.add(new BytecodeToken(sym, h.file, h.line, h.kind));
            return n;
        }
        if (m.equals("R_STX") && l.size() == 4 && (t(l, 1).equals("4") || t(l, 1).equals("8")) && t(l, 2).equals(x)
                && (t(l, 3).startsWith("%x") || t(l, 3).startsWith("%y") || t(l, 3).startsWith("%z"))) {
            n.add(h);
            n.add(l.get(1));
            n.add(new BytecodeToken(sym, h.file, h.line, h.kind));
            n.add(l.get(3));
            return n;
        }
        return null;
    }
}

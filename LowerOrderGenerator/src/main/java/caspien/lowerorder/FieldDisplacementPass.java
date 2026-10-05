package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Field access through a pointer as one instruction (always on; runs last, after {@link LengthCompareFusionPass}, on the final register-form
 * text; only the backend reads the result). A struct field read or written through a pointer in a register used to cost an address
 * instruction and the access:
 *
 * <pre>
 *   R_LEA %t0 %t0 #8 1 ; R_LD 8 %t0 %t0         ->  R_LDD 8 %t0 %t0 8          (movq 8(%rax), %rax, was addq $8, %rax; movq (%rax), %rax)
 *   R_LEA %t1 %v0 #16 1 ; R_ST 8 %t1 %t2         ->  R_STD 8 %v0 16 %t2
 *   R_LEA %t0 %t0 #8 1 ; R_LDX 8 %x0 %t0         ->  R_LDXD 8 %x0 %t0 8
 *   R_LEA %t0 %t0 #8 1 ; R_STX 8 %t0 %x0         ->  R_STXD 8 %t0 8 %x0
 * </pre>
 *
 * The lea has a register base and an immediate index (displacement = index * scale [+ the lea's own 6th token]). The lines between the lea
 * and its consumer must be known register-form mnemonics that neither mention the lea's temp nor write the base register (the base is read
 * later than before), and the lea's temp must be dead after the consumer (as in {@link IndexedAccessPass}). Access widths 1, 2, 4 and 8 only
 * (floats 4 and 8), displacements within 32 bits.
 */
public class FieldDisplacementPass {

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
            long disp = displacement(lea);
            if (disp == Long.MIN_VALUE) {
                continue;
            }
            String x = t(lea, 1), base = t(lea, 2);
            for (int j = i + 1; j < n && j - i <= MAX_GAP; j++) {
                if (gone[j]) {
                    continue;
                }
                List<BytecodeToken> l = cur.get(j);
                if (!IndexedAccessPass.mentions(l, x)) {
                    if (!IndexedAccessPass.passable(l, base)) {
                        break;
                    }
                    continue;
                }
                List<BytecodeToken> fused = fuse(l, x, base, disp);
                boolean self = fused != null && fused.get(0).text.equals("R_LDD") && t(l, 2).equals(x);
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

    /** the byte displacement of `R_LEA %tX %base #k scale [disp]` (register base, immediate index), or Long.MIN_VALUE when the line has another shape */
    private static long displacement(List<BytecodeToken> l) {
        if ((l.size() != 5 && l.size() != 6) || !t(l, 0).equals("R_LEA") || !t(l, 1).startsWith("%t")
                || !(t(l, 2).startsWith("%t") || t(l, 2).startsWith("%v")) || !t(l, 3).startsWith("#")) {
            return Long.MIN_VALUE;
        }
        try {
            long k = Long.parseLong(t(l, 3).substring(1));
            long scale = Long.parseLong(t(l, 4));
            long extra = l.size() == 6 ? Long.parseLong(t(l, 5)) : 0L;
            if (!(scale == 1 || scale == 2 || scale == 4 || scale == 8)) {
                return Long.MIN_VALUE;
            }
            long d = k * scale + extra;
            return (d > Integer.MIN_VALUE && d < Integer.MAX_VALUE) ? d : Long.MIN_VALUE;
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private static boolean okWidth(String s) {
        return s.equals("1") || s.equals("2") || s.equals("4") || s.equals("8");
    }

    /** the fused line for consumer l of the address in x, or null */
    private static List<BytecodeToken> fuse(List<BytecodeToken> l, String x, String base, long disp) {
        if (l.size() != 4) {
            return null;
        }
        BytecodeToken h = l.get(0);
        String m = t(l, 0);
        if (m.equals("R_LD") && okWidth(t(l, 1)) && t(l, 2).startsWith("%t") && t(l, 3).equals(x)) {
            return line(h, "R_LDD", t(l, 1), t(l, 2), base, String.valueOf(disp));
        }
        if (m.equals("R_ST") && okWidth(t(l, 1)) && t(l, 2).equals(x) && !t(l, 3).equals(x)
                && (t(l, 3).startsWith("#") || t(l, 3).startsWith("%t") || t(l, 3).startsWith("%v"))) {
            return line(h, "R_STD", t(l, 1), base, String.valueOf(disp), t(l, 3));
        }
        if (m.equals("R_LDX") && (t(l, 1).equals("4") || t(l, 1).equals("8")) && isXmm(t(l, 2)) && t(l, 3).equals(x)) {
            return line(h, "R_LDXD", t(l, 1), t(l, 2), base, String.valueOf(disp));
        }
        if (m.equals("R_STX") && (t(l, 1).equals("4") || t(l, 1).equals("8")) && t(l, 2).equals(x) && isXmm(t(l, 3))) {
            return line(h, "R_STXD", t(l, 1), base, String.valueOf(disp), t(l, 3));
        }
        return null;
    }

    private static List<BytecodeToken> line(BytecodeToken h, String... parts) {
        List<BytecodeToken> n = new ArrayList<>(parts.length);
        for (String p : parts) {
            n.add(new BytecodeToken(p, h.file, h.line, h.kind));
        }
        return n;
    }

    private static boolean isXmm(String tok) {
        return tok.startsWith("%x") || tok.startsWith("%y");
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }
}

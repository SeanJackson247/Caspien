package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Constant-bounds `match i in arr` / `match i into arr` check (always on, runs on the STACK-form text right after
 * StrengthReductionPass and before RegisterFormPass). MembershipLoweringPass builds the per-iteration bounds test of a
 * range with literal bounds by materialising the range on the stack and copying it into temps:
 *
 *   PUSH 8 $i ; PUSH 8 K0 ; PUSH 8 K1 ; POP $r 16 ; POP $l 8 ;
 *   PUSH 8 $l ; PUSH 8 $r ; GT_EQ_INT 8 ; PUSH 8 $l ; PUSH 8 $r+8 ; LT_INT 8 ; AND 1 ; CMP ; JMP @L
 *
 * ($r holds K0, $r+8 holds K1; $l holds a copy of $i). That is five stack operations and two frame stores per check, and
 * the `PUSH 8 $i` keeps the index out of a variable register. With the three temp slots mentioned nowhere else in the
 * function the whole thing is the same as
 *
 *   PUSH 8 $i ; PUSH 8 K0 ; GT_EQ_INT 8 ; PUSH 8 $i ; PUSH 8 K1 ; LT_INT 8 ; AND 1 ; CMP ; JMP @L
 *
 * and, when K0 is 0 (an unsigned index is always >= 0), just
 *
 *   PUSH 8 $i ; PUSH 8 K1 ; LT_INT 8 ; CMP ; JMP @L
 *
 * RegisterFormPass then fuses what is left. Only this exact 14-line shape is rewritten, only with decimal literal bounds in
 * 0..2^31-1, only for the unsigned compares (GT_EQ_INT / LT_INT), and only when no other line of the same function mentions
 * $l, $r or $r+8 (so the dropped stores cannot be observed).
 */
public class RangeCheckFusionPass {

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int n = lines.size();
        int funcStart = 0;
        int funcEnd = n - 1;
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = lines.get(i);
            if (isMn(l, "FUNC_START")) {
                funcStart = i;
                funcEnd = i;
                for (int j = i + 1; j < n; j++) {
                    if (isMn(lines.get(j), "FUNC_END")) { funcEnd = j; break; }
                }
                if (funcEnd == i) { funcEnd = n - 1; }
            }
            List<List<BytecodeToken>> rep = i + 13 < n ? match(lines, i, funcStart, funcEnd) : null;
            if (rep != null) {
                out.addAll(rep);
                i += 13;
            } else {
                out.add(l);
            }
        }
        return out;
    }

    private static List<List<BytecodeToken>> match(List<List<BytecodeToken>> ls, int i, int fs, int fe) {
        List<BytecodeToken> a0 = ls.get(i), a1 = ls.get(i + 1), a2 = ls.get(i + 2), a3 = ls.get(i + 3), a4 = ls.get(i + 4);
        if (!(isPush8(a0) && isSlot(t(a0, 2)))) { return null; }
        Long k0 = lit(a1), k1 = lit(a2);
        if (k0 == null || k1 == null) { return null; }
        if (!(a3.size() == 3 && t(a3, 0).equals("POP") && t(a3, 2).equals("16") && isSlot(t(a3, 1)))) { return null; }
        if (!(a4.size() == 3 && t(a4, 0).equals("POP") && t(a4, 2).equals("8") && isSlot(t(a4, 1)))) { return null; }
        String r = t(a3, 1), lt = t(a4, 1);
        Long ro = off(r), lo = off(lt);
        if (ro == null || lo == null) { return null; }
        String r8 = "$" + (ro + 8);
        List<BytecodeToken> b0 = ls.get(i + 5), b1 = ls.get(i + 6), b2 = ls.get(i + 7), b3 = ls.get(i + 8), b4 = ls.get(i + 9),
                b5 = ls.get(i + 10), b6 = ls.get(i + 11), b7 = ls.get(i + 12), b8 = ls.get(i + 13);
        if (!(isPush8(b0) && t(b0, 2).equals(lt) && isPush8(b1) && t(b1, 2).equals(r) && isOp(b2, "GT_EQ_INT", "8"))) { return null; }
        if (!(isPush8(b3) && t(b3, 2).equals(lt) && isPush8(b4) && t(b4, 2).equals(r8) && isOp(b5, "LT_INT", "8"))) { return null; }
        if (!(isOp(b6, "AND", "1") && b7.size() == 1 && t(b7, 0).equals("CMP") && b8.size() == 2 && t(b8, 0).equals("JMP"))) { return null; }
        // the three temp slots must be mentioned nowhere else in this function
        for (int j = fs; j <= fe && j < ls.size(); j++) {
            if (j >= i && j <= i + 13) { continue; }
            for (BytecodeToken tk : ls.get(j)) {
                String s = tk.text;
                if (s.equals(r) || s.equals(lt) || s.equals(r8)) { return null; }
            }
        }
        String idx = t(a0, 2);
        List<List<BytecodeToken>> rep = new ArrayList<>();
        boolean dropLower = k0 == 0;
        if (!dropLower) {
            rep.add(a0);
            rep.add(a1);                       // PUSH 8 K0
            rep.add(b2);                       // GT_EQ_INT 8
            rep.add(copy(a0, idx));
            rep.add(a2);                       // PUSH 8 K1
            rep.add(b5);                       // LT_INT 8
            rep.add(b6);                       // AND 1
        } else {
            rep.add(a0);
            rep.add(a2);                       // PUSH 8 K1
            rep.add(b5);                       // LT_INT 8
        }
        rep.add(b7);
        rep.add(b8);
        return rep;
    }

    private static List<BytecodeToken> copy(List<BytecodeToken> l, String unused) {
        return new ArrayList<>(l);
    }

    private static boolean isMn(List<BytecodeToken> l, String m) { return !l.isEmpty() && t(l, 0).equals(m); }
    private static String t(List<BytecodeToken> l, int i) { return l.get(i).text; }
    private static boolean isPush8(List<BytecodeToken> l) { return l.size() == 3 && t(l, 0).equals("PUSH") && t(l, 1).equals("8"); }
    private static boolean isOp(List<BytecodeToken> l, String m, String sz) { return l.size() == 2 && t(l, 0).equals(m) && t(l, 1).equals(sz); }
    private static boolean isSlot(String s) { return off(s) != null; }

    private static Long off(String s) {
        if (s.length() < 2 || s.charAt(0) != '$') { return null; }
        try { return Long.parseLong(s.substring(1)); } catch (NumberFormatException e) { return null; }
    }

    /** `PUSH 8 <decimal literal in 0..2^31-1>` -> the literal, else null. */
    private static Long lit(List<BytecodeToken> l) {
        if (!isPush8(l)) { return null; }
        String s = t(l, 2);
        for (int i = 0; i < s.length(); i++) { if (!Character.isDigit(s.charAt(i))) { return null; } }
        if (s.isEmpty() || s.length() > 10) { return null; }
        long v = Long.parseLong(s);
        return v <= Integer.MAX_VALUE ? v : null;
    }
}

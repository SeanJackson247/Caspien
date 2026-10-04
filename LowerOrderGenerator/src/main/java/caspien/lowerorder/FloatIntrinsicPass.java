package caspien.lowerorder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * libm's square root as one instruction (no switch of its own: it only fires on the float register-form text, so it is
 * off whenever the float registers are off). The call sequence of a user extern that IS libm's sqrt/sqrtf,
 *
 *   CC_START conv ; <argument expression> ; R_FARG 0 n SRC ; CALL sqrt 1 ; CC_END conv ; R_GETRETF n DST
 *
 * with SRC and DST both xmm-register operands (%xK variable or %yK temporary) becomes
 *
 *   R_FSQRT n DST SRC          (sqrtsd / sqrtss)
 *
 * so there is no call, no argument shuffling and, above all, no spill and reload of the float variables that live in xmm
 * registers around the call. Only an extern declared exactly as {@code EXTERN sqrt mut_f64 mut_f64} (sqrtf: mut_f32) is
 * touched; the one visible difference from the library call is that errno is not set for a negative argument (the result is
 * the same NaN). Runs after RegVarPromotionPass and FloatTempPass, which create the operands it matches.
 */
public class FloatIntrinsicPass {

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        Set<String> externs = new HashSet<>();
        for (List<BytecodeToken> l : lines) {
            if (l.size() == 4 && l.get(0).text.equals("EXTERN")) {
                String name = l.get(1).text;
                if (name.equals("sqrt") && l.get(2).text.equals("mut_f64") && l.get(3).text.equals("mut_f64")) {
                    externs.add("sqrt");
                } else if (name.equals("sqrtf") && l.get(2).text.equals("mut_f32") && l.get(3).text.equals("mut_f32")) {
                    externs.add("sqrtf");
                }
            }
        }
        if (externs.isEmpty()) {
            return lines;
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            if (i + 3 < lines.size() && matches(lines, i, externs)) {
                // the CC_START of this call was emitted before its argument expression: find it in what is already written
                String conv = lines.get(i + 2).size() > 1 ? lines.get(i + 2).get(1).text : "";
                int k = out.size() - 1;
                boolean plain = true;
                while (k >= 0) {
                    List<BytecodeToken> o = out.get(k);
                    String m = o.isEmpty() ? "" : o.get(0).text;
                    if (m.equals("CC_START")) break;
                    if (m.equals("CALL") || m.equals("CC_END") || m.equals("INVOKE") || m.equals("EXTERN_CALL") || m.startsWith("@") || m.equals("JMP")
                            || m.startsWith("R_BR") || m.equals("FUNC_START")) {
                        plain = false;
                        break;
                    }
                    k--;
                }
                List<BytecodeToken> farg = lines.get(i);
                List<BytecodeToken> get = lines.get(i + 3);
                if (plain && k >= 0 && out.get(k).size() == 2 && out.get(k).get(1).text.equals(lines.get(i + 2).get(1).text)) {
                    List<BytecodeToken> r = new ArrayList<>();
                    BytecodeToken ref = get.get(0);
                    r.add(new BytecodeToken("R_FSQRT", ref.file, ref.line, BytecodeToken.Kind.CODE));
                    r.add(get.get(1));
                    r.add(get.get(2));
                    r.add(farg.get(3));
                    out.remove(k);
                    out.add(r);
                    i += 4;
                    continue;
                }
            }
            out.add(lines.get(i));
            i++;
        }
        return out;
    }

    private static boolean xmm(String tok) {
        return tok.startsWith("%x") || tok.startsWith("%y");
    }

    /** i = the R_FARG line; the call, CC_END and R_GETRETF follow it. */
    private static boolean matches(List<List<BytecodeToken>> L, int i, Set<String> externs) {
        List<BytecodeToken> farg = L.get(i), call = L.get(i + 1), ce = L.get(i + 2), get = L.get(i + 3);
        if (farg.size() != 4 || !farg.get(0).text.equals("R_FARG") || !farg.get(1).text.equals("0")) return false;
        if (call.size() != 3 || !call.get(0).text.equals("CALL") || !externs.contains(call.get(1).text) || !call.get(2).text.equals("1")) return false;
        if (ce.size() != 2 || !ce.get(0).text.equals("CC_END")) return false;
        if (get.size() != 3 || !get.get(0).text.equals("R_GETRETF")) return false;
        String n = call.get(1).text.equals("sqrt") ? "8" : "4";
        return farg.get(2).text.equals(n) && get.get(1).text.equals(n) && xmm(farg.get(3).text) && xmm(get.get(2).text);
    }
}

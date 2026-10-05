package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Compare-and-branch fusion on the final register-form text (always on; a pure code-quality rewrite). A comparison whose
 * 0/1 result is only used to decide a jump no longer materialises that value (setcc / movzbq / test / je); it compares and
 * jumps:
 *
 *   R_BIN C 8 %tD a b ; R_BRF %tD @L                                   ->  R_BRC C 8 a b @L
 *   R_BIN C1 8 %tX a b ; R_BIN C2 8 %tY c d ; R_BIN AND 1 %tR %tX %tY ; R_BRF %tR @L
 *                                                                       ->  R_BRC C1 8 a b @L ; R_BRC C2 8 c d @L
 *
 * A float compare feeding a jump fuses the same way (`R_FCMP C n %tD a b ; R_BRF %tD @L` -> `R_BRC C Fn a b @L`, no setcc/movzbq/test).
 * "R_BRC C size a b @L" jumps to L when NOT (a C b). The second shape is the test of a `for` loop over a range
 * (start <= i and i < end); the two compares are pure reads, so evaluating the second only when the first held changes nothing.
 * 8-byte compares, and 1/2/4-byte compares whose operands are registers or immediates (the backend emits a sized `cmpb/cmpw/cmpl`, which looks at the low bytes only,
 * exactly what the unfused narrow compare did after extending its operands; a frame-slot operand stays unfused), and only when the compare result temps are
 * dead afterwards, which the register-form model guarantees (a temp is never live across a jump or label). Runs after
 * RegVarPromotionPass and FloatTempPass so neither needs to know the new line.
 */
public class BranchFusionPass {

    private static final Set<String> CMP = Set.of("EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ", "SLT", "SLT_EQ", "SGT", "SGT_EQ");

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            List<BytecodeToken> l = lines.get(i);
            // shape 2
            if (i + 3 < lines.size() && isCmp(l)) {
                List<BytecodeToken> l2 = lines.get(i + 1);
                List<BytecodeToken> l3 = lines.get(i + 2);
                List<BytecodeToken> l4 = lines.get(i + 3);
                if (isCmp(l2) && isAnd(l3) && isBrf(l4)) {
                    String x = t(l, 3), y = t(l2, 3);
                    String r = t(l3, 3);
                    boolean operands = (t(l3, 4).equals(x) && t(l3, 5).equals(y)) || (t(l3, 4).equals(y) && t(l3, 5).equals(x));
                    if (!x.equals(y) && operands && t(l4, 1).equals(r) && !mentions(l2, x) && !mentions(l, y)
                            && notBothImm(l) && notBothImm(l2)) {
                        // AND is commutative and both compares are pure reads: branch on the first, then on the second.
                        out.add(brc(l, l4));
                        out.add(brc(l2, l4));
                        i += 4;
                        continue;
                    }
                }
            }
            // shape 0: float compare + branch, `R_FCMP C n %tD a b ; R_BRF %tD @L` -> `R_BRC C Fn a b @L` (size "F4"/"F8" = a float compare)
            if (i + 1 < lines.size() && isFcmp(l) && isBrf(lines.get(i + 1)) && t(lines.get(i + 1), 1).equals(t(l, 3)) && notBothImm(l)) {
                List<BytecodeToken> n = brc(l, lines.get(i + 1));
                BytecodeToken sz = l.get(2);
                n.set(2, new BytecodeToken("F" + sz.text, sz.file, sz.line, sz.kind));
                out.add(n);
                i += 2;
                continue;
            }
            // shape 1
            if (i + 1 < lines.size() && isCmp(l) && isBrf(lines.get(i + 1)) && t(lines.get(i + 1), 1).equals(t(l, 3)) && notBothImm(l)) {
                out.add(brc(l, lines.get(i + 1)));
                i += 2;
                continue;
            }
            out.add(l);
            i++;
        }
        return out;
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }

    private static boolean isCmp(List<BytecodeToken> l) {
        if (l.size() != 6 || !t(l, 0).equals("R_BIN") || !CMP.contains(t(l, 1)) || !t(l, 3).startsWith("%t")) {
            return false;
        }
        String sz = t(l, 2);
        if (sz.equals("8")) {
            return true;
        }
        // a narrow compare looks at the low `size` bytes only; the backend emits a sized cmp, which needs register or immediate operands
        return (sz.equals("1") || sz.equals("2") || sz.equals("4")) && !t(l, 4).startsWith("$") && !t(l, 5).startsWith("$");
    }

    private static boolean isFcmp(List<BytecodeToken> l) {
        return l.size() == 6 && t(l, 0).equals("R_FCMP") && CMP.contains(t(l, 1)) && (t(l, 2).equals("4") || t(l, 2).equals("8"))
                && t(l, 3).startsWith("%t");
    }

    private static boolean isAnd(List<BytecodeToken> l) {
        return l.size() == 6 && t(l, 0).equals("R_BIN") && t(l, 1).equals("AND") && t(l, 2).equals("1")
                && t(l, 3).startsWith("%t") && t(l, 4).startsWith("%t") && t(l, 5).startsWith("%t");
    }

    private static boolean isBrf(List<BytecodeToken> l) {
        return l.size() == 3 && t(l, 0).equals("R_BRF") && t(l, 1).startsWith("%t");
    }

    private static boolean notBothImm(List<BytecodeToken> l) {
        return !(t(l, 4).startsWith("#") && t(l, 5).startsWith("#"));
    }

    private static boolean mentions(List<BytecodeToken> l, String tok) {
        return t(l, 4).equals(tok) || t(l, 5).equals(tok);
    }

    private static List<BytecodeToken> brc(List<BytecodeToken> cmp, List<BytecodeToken> brf) {
        BytecodeToken h = cmp.get(0);
        List<BytecodeToken> n = new ArrayList<>();
        n.add(new BytecodeToken("R_BRC", h.file, h.line, h.kind));
        n.add(cmp.get(1));
        n.add(cmp.get(2));
        n.add(cmp.get(4));
        n.add(cmp.get(5));
        n.add(brf.get(2));
        return n;
    }
}

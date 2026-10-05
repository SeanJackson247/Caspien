package caspien.lowerorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds the length load of a safe dynarray bounds check into the compare ({@code length-compare-fusion: on}; runs last, after
 * {@link IndexedAccessPass}, on the final register-form text). A safe access {@code match i in a { ... a[i] ... }} loads the length word
 * at the start of the array block and compares the index with it:
 *
 * <pre>
 *   R_MOV 8 %t0 $S ; R_LD 8 %t0 %t0 ; R_BRC LT 8 idx %t0 @out ; R_MOV 8 %t0 $S ; R_LDI 8 %t0 %t0 idx 8 16
 *   R_LD 8 %t0 %v0 ; R_BRC LT 8 idx %t0 @out                              (base hoisted into %v0 by LoopHoistPass)
 * </pre>
 *
 * becomes
 *
 * <pre>
 *   R_MOV 8 %t0 $S ; R_BRCM LT 8 idx %t0 @out ; R_LDI 8 %t0 %t0 idx 8 16       (cmpq (%rax), %r9: no length register, no base reload)
 *   R_BRCM LT 8 idx %v0 @out
 * </pre>
 *
 * {@code R_BRCM op 8 a %base @L} is {@code R_BRC} whose second operand is the 8-byte word at the address in {@code %base}; `a` is a register or
 * (first form only) a frame slot, which the backend loads into its scratch register first (with a slot index the second form would gain nothing). The first form
 * keeps the BASE in %t0 (it used to hold the length), so an identical {@code R_MOV 8 %t0 $S} right after the branch is dropped; when it is
 * not there the temp must be dead on the fall-through path. On the jump-out path the temp must be dead too (the first mention after the
 * label is a pure redefinition). The length register disappears in the second form, so the temp must be dead on both paths there as well.
 * Measured on lru_safe: 10% faster (the hand-patched assembly), same output.
 */
public class LengthCompareFusionPass {

    private final boolean enabled;

    public LengthCompareFusionPass(boolean enabled) {
        this.enabled = enabled;
    }

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return lines;
        }
        int n = lines.size();
        List<List<BytecodeToken>> cur = new ArrayList<>(lines);
        boolean[] gone = new boolean[n];
        Map<String, Integer> labelAt = new HashMap<>();
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = cur.get(i);
            if (l.size() == 1 && t(l, 0).startsWith("@") && t(l, 0).endsWith(":")) {
                labelAt.put(t(l, 0).substring(0, t(l, 0).length() - 1), i);
            }
        }
        boolean changed = false;
        for (int i = 0; i + 1 < n; i++) {
            if (gone[i]) {
                continue;
            }
            List<BytecodeToken> a = cur.get(i);
            // form 2: R_LD 8 %tA %vK ; R_BRC op 8 X %tA @L
            if (a.size() == 4 && t(a, 0).equals("R_LD") && t(a, 1).equals("8") && t(a, 2).startsWith("%t") && t(a, 3).startsWith("%v")) {
                List<BytecodeToken> b = cur.get(i + 1);
                String tA = t(a, 2);
                if (isLengthBranch(b, tA, false) && deadAfter(cur, gone, i + 2, tA) && deadAtTarget(cur, gone, labelAt, b, tA)) {
                    cur.set(i + 1, fused(b, t(a, 3)));
                    gone[i] = true;
                    changed = true;
                }
                continue;
            }
            // form 1: R_MOV 8 %tA $S ; R_LD 8 %tA %tA ; R_BRC op 8 X %tA @L
            if (i + 2 < n && a.size() == 4 && t(a, 0).equals("R_MOV") && t(a, 1).equals("8") && t(a, 2).startsWith("%t") && t(a, 3).startsWith("$")) {
                List<BytecodeToken> ld = cur.get(i + 1);
                List<BytecodeToken> b = cur.get(i + 2);
                String tA = t(a, 2);
                if (ld.size() == 4 && t(ld, 0).equals("R_LD") && t(ld, 1).equals("8") && t(ld, 2).equals(tA) && t(ld, 3).equals(tA)
                        && isLengthBranch(b, tA, true) && deadAtTarget(cur, gone, labelAt, b, tA)) {
                    boolean sameNext = i + 3 < n && !gone[i + 3] && sameLine(cur.get(i + 3), a);
                    if (sameNext || deadAfter(cur, gone, i + 3, tA)) {
                        cur.set(i + 2, fused(b, tA));
                        gone[i + 1] = true;
                        if (sameNext) {
                            gone[i + 3] = true;
                        }
                        changed = true;
                    }
                }
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

    /** `R_BRC op 8 X %tA @L` with a register X different from %tA: the length (%tA) is the second compared operand */
    private static boolean isLengthBranch(List<BytecodeToken> b, String tA, boolean slotIndexOk) {
        return b.size() == 6 && t(b, 0).equals("R_BRC") && t(b, 2).equals("8") && t(b, 4).equals(tA) && t(b, 5).startsWith("@")
                && (t(b, 3).startsWith("%t") || t(b, 3).startsWith("%v") || (slotIndexOk && t(b, 3).startsWith("$"))) && !t(b, 3).equals(tA);
    }

    private static List<BytecodeToken> fused(List<BytecodeToken> b, String base) {
        List<BytecodeToken> n = new ArrayList<>(b);
        n.set(0, new BytecodeToken("R_BRCM", b.get(0).file, b.get(0).line, b.get(0).kind));
        n.set(4, new BytecodeToken(base, b.get(4).file, b.get(4).line, b.get(4).kind));
        return n;
    }

    private static boolean sameLine(List<BytecodeToken> x, List<BytecodeToken> y) {
        if (x.size() != y.size()) {
            return false;
        }
        for (int k = 0; k < x.size(); k++) {
            if (!t(x, k).equals(t(y, k))) {
                return false;
            }
        }
        return true;
    }

    /** the temp is dead from line `from` on (never read before a pure redefinition, a label or a jump) */
    private static boolean deadAfter(List<List<BytecodeToken>> cur, boolean[] gone, int from, String x) {
        return IndexedAccessPass.deadAfter(cur, gone, from, x, false);
    }

    /** the temp is dead where the branch jumps to (its label must be defined in this function's text; unknown label: not dead) */
    private static boolean deadAtTarget(List<List<BytecodeToken>> cur, boolean[] gone, Map<String, Integer> labelAt, List<BytecodeToken> b, String x) {
        Integer at = labelAt.get(t(b, 5));
        return at != null && deadAfter(cur, gone, at + 1, x);
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }
}

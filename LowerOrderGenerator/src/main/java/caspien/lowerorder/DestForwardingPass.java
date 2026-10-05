package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two register-form peepholes on straight-line code, run last (after the fusion passes, so the indexed and displacement
 * loads are already in their final shape; only the backend reads the result). No switch: a function without variable
 * registers ({@code %v}) has nothing to match.
 *
 * <p><b>Destination forwarding.</b> A value computed into a temporary and copied into a variable register on the very next
 * line is computed into the variable register instead:
 * <pre>
 *   R_LDI 8 %t0 %t0 %v3 8 ; R_MOV 8 %v5 %t0     ->   R_LDI 8 %v5 %t0 %v3 8
 *   R_MOV 8 %t0 %v1       ; R_MOV 8 %v3 %t0     ->   R_MOV 8 %v3 %v1
 *   R_BIN SHR 8 %t0 %t0 #1 ; R_MOV 8 %v3 %t0    ->   R_BIN SHR 8 %v3 %t0 #1
 * </pre>
 * Producers: loads ({@code R_LD R_LDI R_LDD}: one instruction, the destination may equal an input), {@code R_MOV 8} from a
 * slot, an immediate or a register, and {@code R_BIN} ADD SUB MUL AND OR BAND BOR BXOR and SHL/SHR/SAR by an immediate. The temporary must be dead
 * after the copy (register-form temporaries are never live across a label or an unconditional jump; the next mention must
 * be a pure redefinition). The copy moved a full 64-bit register, so the value in the variable is the same as before.
 *
 * <p><b>Dead initialisation.</b> {@code R_MOV 8 %vJ <src>} (a constant, a register or a slot read) whose next mention of
 * {@code %vJ}, in straight-line code with only known register-form lines in between, is a pure redefinition that does not read
 * it, is deleted (the {@code let x = mut 0} followed by the real assignment that the source writes).
 */
public class DestForwardingPass {

    private static final Map<String, Integer> DEST = Map.ofEntries(
            Map.entry("R_MOV", 2), Map.entry("R_LD", 2), Map.entry("R_LDI", 2), Map.entry("R_LDD", 2), Map.entry("R_LEA", 1),
            Map.entry("R_BIN", 3), Map.entry("R_UN", 3), Map.entry("R_FBIN", 3), Map.entry("R_FCMP", 3), Map.entry("R_XTOG", 2));
    /** known lines that write no general register (or only the one DEST names) and are neither a branch nor a call */
    private static final Set<String> STRAIGHT = Set.of("R_ST", "R_STI", "R_STD", "R_LDX", "R_STX", "R_FBINX", "R_XMOV", "R_GTOX",
            "R_FSQRT", "R_RMW", "R_LDXI", "R_STXI", "R_LDXD", "R_STXD");
    private static final Set<String> BIN_OPS = Set.of("ADD", "SUB", "MUL", "AND", "OR", "BAND", "BOR", "BXOR");
    private static final Set<String> SHIFT_OPS = Set.of("SHL", "SHR", "SAR", "ROTL", "ROTR");

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        int n = lines.size();
        List<List<BytecodeToken>> cur = new ArrayList<>(lines);
        boolean[] gone = new boolean[n];
        boolean changed = false;
        for (int i = 0; i + 1 < n; i++) {
            if (gone[i] || gone[i + 1]) {
                continue;
            }
            List<BytecodeToken> a = cur.get(i), m = cur.get(i + 1);
            if (m.size() != 4 || !t(m, 0).equals("R_MOV") || !t(m, 1).equals("8") || !t(m, 2).startsWith("%v") || !t(m, 3).startsWith("%t")) {
                continue;
            }
            String tmp = t(m, 3), var = t(m, 2);
            int d = destIndex(a);
            if (d < 0 || !t(a, d).equals(tmp) || !forwardable(a, tmp)) {
                continue;
            }
            if (!deadAfter(cur, gone, i + 2, tmp)) {
                continue;
            }
            List<BytecodeToken> r = new ArrayList<>(a);
            r.set(d, m.get(2));
            cur.set(i, r);
            gone[i + 1] = true;
            changed = true;
            i++;
        }
        for (int i = 0; i < n; i++) {
            if (gone[i]) {
                continue;
            }
            List<BytecodeToken> l = cur.get(i);
            if (l.size() == 4 && t(l, 0).equals("R_MOV") && t(l, 1).equals("8") && t(l, 2).startsWith("%v")
                    && (t(l, 3).startsWith("#") || t(l, 3).startsWith("$") || t(l, 3).startsWith("%"))
                    && overwrittenBeforeRead(cur, gone, i + 1, t(l, 2))) {
                gone[i] = true;
                changed = true;
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

    private static int destIndex(List<BytecodeToken> l) {
        Integer d = DEST.get(t(l, 0));
        return d == null || d >= l.size() ? -1 : d;
    }

    /** a producer whose destination register may be a variable register */
    private static boolean forwardable(List<BytecodeToken> a, String tmp) {
        switch (t(a, 0)) {
            case "R_LD":
                return a.size() == 4;
            case "R_LDI":
                return a.size() == 6 || a.size() == 7;
            case "R_LDD":
                return a.size() == 5;
            case "R_MOV":
                return a.size() == 4 && t(a, 1).equals("8") && !t(a, 3).equals(tmp);
            case "R_BIN":
                if (a.size() != 6) {
                    return false;
                }
                if (BIN_OPS.contains(t(a, 1))) {
                    return true;
                }
                return SHIFT_OPS.contains(t(a, 1)) && t(a, 5).startsWith("#");
            default:
                return false;
        }
    }

    /** x (a temporary) is not read from line `from` on: the next mention is a pure redefinition, or a label / unconditional jump comes first */
    private static boolean deadAfter(List<List<BytecodeToken>> cur, boolean[] gone, int from, String x) {
        for (int j = from; j < cur.size(); j++) {
            if (gone[j]) {
                continue;
            }
            List<BytecodeToken> l = cur.get(j);
            if (l.isEmpty()) {
                return false;
            }
            if ((l.size() == 1 && t(l, 0).startsWith("@")) || t(l, 0).equals("JMP")) {
                return true;
            }
            if (!IndexedAccessPass.mentions(l, x)) {
                continue;
            }
            return pureRedefinition(l, x);
        }
        return true;
    }

    private static boolean pureRedefinition(List<BytecodeToken> l, String x) {
        int d = destIndex(l);
        if (d < 0 || !t(l, d).equals(x) || !(t(l, 0).equals("R_MOV") || t(l, 0).equals("R_LD") || t(l, 0).equals("R_LDI")
                || t(l, 0).equals("R_LDD") || t(l, 0).equals("R_LEA") || t(l, 0).equals("R_BIN") || t(l, 0).equals("R_UN")
                || t(l, 0).equals("R_FBIN") || t(l, 0).equals("R_FCMP") || t(l, 0).equals("R_XTOG"))) {
            return false;
        }
        for (int k = 1; k < l.size(); k++) {
            if (k != d && t(l, k).equals(x)) {
                return false;
            }
        }
        return true;
    }

    /** straight-line scan: the next line that mentions the variable x redefines it without reading it */
    private static boolean overwrittenBeforeRead(List<List<BytecodeToken>> cur, boolean[] gone, int from, String x) {
        for (int j = from; j < cur.size() && j < from + 60; j++) {
            if (gone[j]) {
                continue;
            }
            List<BytecodeToken> l = cur.get(j);
            if (l.isEmpty()) {
                return false;
            }
            String op = t(l, 0);
            boolean known = DEST.containsKey(op) || STRAIGHT.contains(op);
            if (!known) {
                return false;
            }
            if (!IndexedAccessPass.mentions(l, x)) {
                continue;
            }
            return pureRedefinition(l, x);
        }
        return false;
    }
}

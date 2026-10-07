package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copy forwarding (`copy-forward: on`). A temporary that only holds a copy of a promoted variable,
 *
 *   R_MOV 8 %t0 %v1 ; R_BIN ADD 8 %t0 %t0 #3 ; R_ST 8 %t2 %t0       (%t0 redefined afterwards)
 *
 * has its later reads replaced by the variable itself and the copy deleted:
 *
 *   R_BIN ADD 8 %t0 %v1 #3 ; R_ST 8 %t2 %t0
 *
 * (the 3-operand `add`/`lea`/`imul` forms then save the `mov`). Applied only when, scanning forward in straight-line code (at most
 * {@code WINDOW} lines), every mention of the temp is a read in a position the backend takes a variable register in, nothing
 * writes the variable or calls anything, and the scan ends at a pure redefinition of the temp (or a label / unconditional jump, where
 * temporaries are dead, as for {@link DestForwardingPass}). Only the variables %v0..%v5 are used (no implicit clobbers by division,
 * shifts or calls). Also deleted: the no-op `R_MOV 8 %vK %vK`.
 */
public class CopyForwardPass {

    private static final int WINDOW = 14;
    private static final Map<String, Integer> DEST = Map.ofEntries(
            Map.entry("R_MOV", 2), Map.entry("R_LD", 2), Map.entry("R_LDI", 2), Map.entry("R_LDD", 2), Map.entry("R_LEA", 1),
            Map.entry("R_BIN", 3), Map.entry("R_UN", 3), Map.entry("R_SETV", 2), Map.entry("R_DIVC", 3),
            Map.entry("R_FBIN", 3), Map.entry("R_FCMP", 3), Map.entry("R_XTOG", 2));
    /** lines that write no general register and are neither a branch nor a call */
    private static final Set<String> STRAIGHT = Set.of("R_ST", "R_STI", "R_STD", "R_LDX", "R_STX", "R_XMOV", "R_GTOX",
            "R_FSQRT", "R_LDXI", "R_STXI", "R_LDXD", "R_STXD");
    private static final Set<String> BIN_OPS = Set.of("ADD", "SUB", "MUL", "AND", "OR", "BAND", "BOR", "BXOR", "SHL", "SHR", "SAR");

    private final boolean enabled;

    public CopyForwardPass(boolean enabled) {
        this.enabled = enabled;
    }

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return lines;
        }
        int n = lines.size();
        List<List<BytecodeToken>> cur = new ArrayList<>(lines);
        boolean[] gone = new boolean[n];
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> m = cur.get(i);
            if (gone[i] || m.size() != 4 || !t(m, 0).equals("R_MOV") || !t(m, 1).equals("8")) {
                continue;
            }
            if (t(m, 2).startsWith("%v") && t(m, 2).equals(t(m, 3))) {
                gone[i] = true;
                changed = true;
                continue;
            }
            if (!t(m, 2).matches("%t[0-3]") || !t(m, 3).matches("%v[0-5]")) {
                continue;
            }
            List<List<BytecodeToken>> edits = plan(cur, gone, i, t(m, 2), m.get(3));
            if (edits == null) {
                continue;
            }
            for (int k = 0; k < edits.size(); k++) {
                if (edits.get(k) != null) {
                    cur.set(i + 1 + k, edits.get(k));
                }
            }
            gone[i] = true;
            changed = true;
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

    /** the rewritten lines after `from` (null entries = unchanged), or null when the copy cannot be forwarded */
    private static List<List<BytecodeToken>> plan(List<List<BytecodeToken>> cur, boolean[] gone, int from, String x, BytecodeToken v) {
        String var = v.text;
        List<List<BytecodeToken>> edits = new ArrayList<>();
        int seen = 0;
        for (int j = from + 1; j < cur.size() && seen < WINDOW; j++) {
            if (gone[j]) {
                edits.add(null);
                continue;
            }
            seen++;
            List<BytecodeToken> l = cur.get(j);
            edits.add(null);
            if (l.isEmpty()) {
                return null;
            }
            if ((l.size() == 1 && t(l, 0).startsWith("@")) || t(l, 0).equals("JMP")) {
                return edits;                       // temporaries are dead here
            }
            String op = t(l, 0);
            Integer d = DEST.get(op);
            if (d == null && !STRAIGHT.contains(op)) {
                return null;                        // branch, call, push, unknown
            }
            if (d != null && d >= l.size()) {
                return null;
            }
            List<BytecodeToken> r = null;
            for (int k = 1; k < l.size(); k++) {
                if (!t(l, k).equals(x) || (d != null && k == d)) {
                    continue;
                }
                if (!readSlot(l, k)) {
                    return null;
                }
                if (r == null) {
                    r = new ArrayList<>(l);
                }
                r.set(k, v);
            }
            if (r != null) {
                edits.set(edits.size() - 1, r);
            }
            if (d != null && t(l, d).equals(var)) {
                // the variable is rewritten here: fine only when the temp is dead from the next line on
                return DestForwardingPass.deadAfterPublic(cur, gone, j + 1, x) ? edits : null;
            }
            if (d != null && t(l, d).equals(x)) {
                return edits;                       // redefinition: the copy is dead from here on
            }
        }
        return null;
    }

    /** operand k of line l is a plain value read of a general register that the backend also accepts a variable register for */
    private static boolean readSlot(List<BytecodeToken> l, int k) {
        switch (t(l, 0)) {
            case "R_MOV":
                return l.size() == 4 && k == 3;
            case "R_SETV":
                return l.size() == 4 && k == 3;
            case "R_BIN":
                return l.size() == 6 && (k == 4 || k == 5) && BIN_OPS.contains(t(l, 1));
            case "R_UN":
                return false;
            case "R_ST":
                return l.size() == 4 && (k == 2 || k == 3);
            case "R_STD":
                return l.size() == 5 && (k == 2 || k == 4);
            case "R_STI":
                return (l.size() == 6 || l.size() == 7) && k == 5;
            case "R_LD":
                return l.size() == 4 && k == 3;
            case "R_LDD":
                return l.size() == 5 && k == 3;
            case "R_LEA":
                return l.size() >= 5 && k == 2;
            default:
                return false;
        }
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }
}

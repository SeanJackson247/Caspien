package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Indexed array access on the final register-form text (always on; a pure code-quality rewrite, runs after JumpCleanupPass).
 * A global array element used to cost two address instructions:
 *
 *   R_LEA %tX &sym %vK scale ; ... ; R_LD n %tD %tX        ->  R_LDI n %tD &sym %vK scale
 *   R_LEA %tX &sym %vK scale ; ... ; R_ST n %tX src        ->  R_STI n &sym %vK scale src
 *
 * (`leaq sym(%rip),%rax; leaq (%rax,%r13,8),%rax; movq (%rax),...` becomes `leaq sym(%rip),%rax; movq (%rax,%r13,8),...`:
 * RIP-relative addressing cannot carry an index register, so the base address still takes one lea.)
 * The float forms fuse the same way (`R_LDX n %xK|%yK %tX` -> `R_LDXI n %xK|%yK &sym %vK n`, `R_STX n %tX %xK|%yK` -> `R_STXI n &sym %vK n %xK|%yK`) with scale = access width (4 for f32, 8 for f64).
 * An R_LEA with a displacement (6 tokens, e.g. the safe dynarray header offset 16) fuses the same way; the displacement becomes the last token of the fused line.
 * Only a base that is a global, a frame slot or a register holding a pointer, an index held in a variable register (%v), and an access whose width equals the element size (scale 1, 2, 4 or 8; tests/indexed_narrow_test covers 1, 2 and 4, tests/indexed_bases_test the frame and pointer bases). The lines
 * between the lea and its consumer must be known register-form mnemonics that neither mention %tX nor write the index register
 * (the index is read later than before), and %tX must be dead after the consumer (never read again before a pure redefinition,
 * a label or a jump; register-form temps are never live across those).
 */
public class IndexedAccessPass {

    private static final int MAX_GAP = 40;

    /** position of the register a mnemonic writes (-1: writes none) */
    static final Map<String, Integer> DEST = Map.ofEntries(
            Map.entry("R_MOV", 2), Map.entry("R_LD", 2), Map.entry("R_LDI", 2), Map.entry("R_BIN", 3), Map.entry("R_UN", 3),
            Map.entry("R_LEA", 1), Map.entry("R_SETV", 2), Map.entry("R_RMW", 3), Map.entry("R_ST", -1), Map.entry("R_STI", -1),
            // float forms: only R_FBIN, R_FCMP and R_XTOG write a general register (a temp); the rest write xmm registers or memory
            Map.entry("R_FBIN", 3), Map.entry("R_FCMP", 3), Map.entry("R_XTOG", 2), Map.entry("R_GTOX", -1), Map.entry("R_LDX", -1),
            Map.entry("R_STX", -1), Map.entry("R_FBINX", -1), Map.entry("R_XMOV", -1), Map.entry("R_LDXI", -1), Map.entry("R_STXI", -1));

    static final Set<String> PURE_DEF = Set.of("R_MOV", "R_LD", "R_LDI", "R_LEA", "R_BIN", "R_UN", "R_FBIN", "R_FCMP", "R_XTOG");

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        int n = lines.size();
        List<List<BytecodeToken>> cur = new ArrayList<>(lines);
        boolean[] gone = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (gone[i]) {
                continue;
            }
            List<BytecodeToken> lea = cur.get(i);
            if (!isIndexedLea(lea)) {
                continue;
            }
            String x = t(lea, 1), idx = t(lea, 3);
            // a register base (temp or variable) is read later than before: it must not be rewritten in between either
            String base = t(lea, 2).startsWith("%") ? t(lea, 2) : null;
            for (int j = i + 1; j < n && j - i <= MAX_GAP; j++) {
                if (gone[j]) {
                    continue;
                }
                List<BytecodeToken> l = cur.get(j);
                if (!mentions(l, x)) {
                    if (!passable(l, idx) || (base != null && !passable(l, base))) {
                        break;
                    }
                    continue;
                }
                List<BytecodeToken> fused = fuse(lea, l, x);
                if (fused != null && deadAfter(cur, gone, j + 1, x, fused.get(0).text.equals("R_LDI") && t(l, 2).equals(x))) {
                    cur.set(j, fused);
                    gone[i] = true;
                }
                break;
            }
        }
        List<List<BytecodeToken>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!gone[i]) {
                out.add(cur.get(i));
            }
        }
        return out;
    }

    private static boolean isIndexedLea(List<BytecodeToken> l) {
        if ((l.size() != 5 && l.size() != 6) || !t(l, 0).equals("R_LEA") || !t(l, 1).startsWith("%t") || !t(l, 3).startsWith("%v")) {
            return false;
        }
        // base: a global, a frame slot, or a register (temp or variable) holding a pointer
        String b = t(l, 2);
        if (!(b.startsWith("&") || b.startsWith("$") || b.startsWith("%t") || b.startsWith("%v")) || b.equals(t(l, 3))) {
            return false;
        }
        String s = t(l, 4);
        return s.equals("8") || s.equals("4") || s.equals("2") || s.equals("1");
    }

    /** the fused line for consumer l of the address in x, or null */
    private static List<BytecodeToken> fuse(List<BytecodeToken> lea, List<BytecodeToken> l, String x) {
        if (l.size() != 4) {
            return null;
        }
        BytecodeToken h = l.get(0);
        List<BytecodeToken> n = new ArrayList<>();
        // float element (f32: scale 4, f64: scale 8): the access width must equal the scale
        if (t(l, 0).equals("R_LDX") && t(l, 1).equals(t(lea, 4)) && isXmm(t(l, 2)) && t(l, 3).equals(x)) {
            n.add(new BytecodeToken("R_LDXI", h.file, h.line, h.kind));
            n.add(l.get(1));
            n.add(l.get(2));
            n.add(lea.get(2));
            n.add(lea.get(3));
            n.add(lea.get(4));
            addDisp(n, lea);
            return n;
        }
        if (t(l, 0).equals("R_STX") && t(l, 1).equals(t(lea, 4)) && t(l, 2).equals(x) && isXmm(t(l, 3))) {
            n.add(new BytecodeToken("R_STXI", h.file, h.line, h.kind));
            n.add(l.get(1));
            n.add(lea.get(2));
            n.add(lea.get(3));
            n.add(lea.get(4));
            n.add(l.get(3));
            addDisp(n, lea);
            return n;
        }
        // integer element: the access width equals the element size (scale 1, 2, 4 or 8)
        if (!okSize(t(l, 1)) || !t(l, 1).equals(t(lea, 4))) {
            return null;
        }
        if (t(l, 0).equals("R_LD") && t(l, 2).startsWith("%t") && t(l, 3).equals(x)) {
            n.add(new BytecodeToken("R_LDI", h.file, h.line, h.kind));
            n.add(l.get(1));
            n.add(l.get(2));
            n.add(lea.get(2));
            n.add(lea.get(3));
            n.add(lea.get(4));
            addDisp(n, lea);
            return n;
        }
        if (t(l, 0).equals("R_ST") && t(l, 2).equals(x) && !t(l, 3).equals(x)
                && (t(l, 3).startsWith("#") || t(l, 3).startsWith("%t") || t(l, 3).startsWith("%v"))) {
            n.add(new BytecodeToken("R_STI", h.file, h.line, h.kind));
            n.add(l.get(1));
            n.add(lea.get(2));
            n.add(lea.get(3));
            n.add(lea.get(4));
            n.add(l.get(3));
            addDisp(n, lea);
            return n;
        }
        return null;
    }

    /** the lea's displacement (6th token, e.g. the 16-byte safe dynarray header) rides along as the fused line's last token */
    private static void addDisp(List<BytecodeToken> n, List<BytecodeToken> lea) {
        if (lea.size() == 6) {
            n.add(lea.get(5));
        }
    }

    private static boolean isXmm(String tok) {
        return tok.startsWith("%x") || tok.startsWith("%y");
    }

    private static boolean okSize(String s) {
        return s.equals("8") || s.equals("4") || s.equals("2") || s.equals("1");
    }

    /** a line that may sit between the lea and its consumer: known mnemonic, does not write the index register */
    static boolean passable(List<BytecodeToken> l, String idx) {
        Integer d = DEST.get(t(l, 0));
        if (d == null) {
            return false;
        }
        return d < 0 || d >= l.size() || !t(l, d).equals(idx);
    }

    static boolean mentions(List<BytecodeToken> l, String tok) {
        for (int k = 1; k < l.size(); k++) {
            if (t(l, k).equals(tok)) {
                return true;
            }
        }
        return false;
    }

    /** x is dead from line `from` on; selfDefined: the consumer itself redefines x */
    static boolean deadAfter(List<List<BytecodeToken>> cur, boolean[] gone, int from, String x, boolean selfDefined) {
        if (selfDefined) {
            return true;
        }
        for (int j = from; j < cur.size(); j++) {
            if (gone[j]) {
                continue;
            }
            List<BytecodeToken> l = cur.get(j);
            if (l.size() == 1 && t(l, 0).startsWith("@")) {
                return true;
            }
            if (t(l, 0).equals("JMP")) {
                return true;
            }
            if (!mentions(l, x)) {
                continue;
            }
            if (!PURE_DEF.contains(t(l, 0))) {
                return false;
            }
            int d = DEST.get(t(l, 0));
            if (d >= l.size() || !t(l, d).equals(x)) {
                return false;
            }
            for (int k = 1; k < l.size(); k++) {
                if (k != d && t(l, k).equals(x)) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }
}

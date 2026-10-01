package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jump cleanup on the final register-form text (always on; a pure code-quality rewrite, runs after BranchFusionPass).
 * `if c { break }` and friends leave chains such as
 *
 *   R_BRC EQ 8 a b @L1 ; JMP @Lend ; JMP @L1 ; @L1:
 *
 * which assemble to `jne L1; jmp Lend; jmp L1; L1:`. Three local rewrites, repeated until nothing changes:
 *   1. a JMP or R_BRC directly after an unconditional JMP is unreachable (nothing can fall into it; a label would stop the
 *      scan) and is dropped;
 *   2. a JMP whose target label is the next thing in the text (only labels in between) is dropped;
 *   3. `R_BRC C s a b @L1 ; JMP @L2 ; @L1:` becomes `R_BRC C' s a b @L2 ; @L1:` with C' the opposite condition
 *      ("R_BRC C" jumps when NOT (a C b), so jumping to L2 when (a C b) holds is the jump of the opposite condition).
 * A `JMP` directly after a bare `CMP` (stack form) is the conditional jump and is never treated as unconditional.
 * Labels are never removed, so every other reference stays valid. Only JMP / R_BRC lines are ever deleted.
 */
public class JumpCleanupPass {

    private static final Map<String, String> OPPOSITE = Map.ofEntries(
            Map.entry("EQ", "NEQ"), Map.entry("NEQ", "EQ"),
            Map.entry("LT", "GT_EQ"), Map.entry("GT_EQ", "LT"),
            Map.entry("LT_EQ", "GT"), Map.entry("GT", "LT_EQ"),
            Map.entry("SLT", "SGT_EQ"), Map.entry("SGT_EQ", "SLT"),
            Map.entry("SLT_EQ", "SGT"), Map.entry("SGT", "SLT_EQ"));

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> cur = lines;
        for (int round = 0; round < 8; round++) {
            List<List<BytecodeToken>> next = step(cur);
            boolean same = next.size() == cur.size();
            cur = next;
            if (same) {
                break;
            }
        }
        return cur;
    }

    private List<List<BytecodeToken>> step(List<List<BytecodeToken>> in) {
        List<List<BytecodeToken>> out = new ArrayList<>(in.size());
        int n = in.size();
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = in.get(i);
            // 1. dead jump right after an unconditional jump
            if (!out.isEmpty() && isJmp(out.get(out.size() - 1)) && !followsCmp(out, out.size() - 1) && (isJmp(l) || isBrc(l))) {
                continue;
            }
            // 2. jump to the label that follows
            if (isJmp(l) && !followsCmp(out, out.size()) && nextLabelsContain(in, i + 1, t(l, 1))) {
                continue;
            }
            // 3. conditional jump over an unconditional one
            if (isBrc(l) && i + 2 < n && isJmp(in.get(i + 1)) && isLabelDef(in.get(i + 2), t(l, 5)) && OPPOSITE.containsKey(t(l, 1))
                    && !t(in.get(i + 1), 1).equals(t(l, 5))) {
                List<BytecodeToken> inv = new ArrayList<>(l);
                BytecodeToken c = l.get(1);
                inv.set(1, new BytecodeToken(OPPOSITE.get(c.text), c.file, c.line, c.kind));
                inv.set(5, in.get(i + 1).get(1));
                out.add(inv);
                i++; // the JMP is consumed
                continue;
            }
            out.add(l);
        }
        return out;
    }

    /** In stack form a `JMP` right after a bare `CMP` is the conditional jump (jump when the compared value is false), not a plain one. */
    private static boolean followsCmp(List<List<BytecodeToken>> list, int idx) {
        return idx > 0 && list.get(idx - 1).size() == 1 && t(list.get(idx - 1), 0).equals("CMP");
    }

    private static boolean nextLabelsContain(List<List<BytecodeToken>> in, int from, String label) {
        for (int j = from; j < in.size(); j++) {
            List<BytecodeToken> l = in.get(j);
            if (!isLabel(l)) {
                return false;
            }
            if (t(l, 0).equals(label + ":")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLabel(List<BytecodeToken> l) {
        return l.size() == 1 && t(l, 0).startsWith("@") && t(l, 0).endsWith(":");
    }

    private static boolean isLabelDef(List<BytecodeToken> l, String label) {
        return isLabel(l) && t(l, 0).equals(label + ":");
    }

    private static boolean isJmp(List<BytecodeToken> l) {
        return l.size() == 2 && t(l, 0).equals("JMP") && t(l, 1).startsWith("@");
    }

    private static boolean isBrc(List<BytecodeToken> l) {
        return l.size() == 6 && t(l, 0).equals("R_BRC") && t(l, 5).startsWith("@");
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }
}

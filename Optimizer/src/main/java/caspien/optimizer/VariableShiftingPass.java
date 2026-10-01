package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Variable shifting: a local scalar that is assigned constants more than once, and is otherwise a variable elision candidate (every
 * assignment is "ADDR x T / PUSH literal / ASSIGN", never address-taken, no other kind of mention), is split at each reassignment:
 * a new variable of the same type is allocated (its ALLOC is hoisted next to x's), the reassignment now writes to it, and every
 * reference after that point is renamed to it. Each resulting variable is assigned once, so VariableElisionPass can then take them.
 *
 *     let x = 1; use(x); x = 2; use(x)      ->     x = 1; use(x); x__s1 = 2; use(x__s1)
 *
 * Only straight-line splits are made. A reassignment is split only if it is reached by every path to the references after it (no
 * forward jump from before it lands between it and a later reference) and is not inside a loop (no backward jump after it lands
 * before it); a reassignment inside an 'if' branch or a loop body is left alone, and so is x's first assignment. A function with a
 * catch block (its body is emitted at the top of the function, out of textual order) or inline assembly is skipped.
 *
 * Off unless "variable-shifting: on" is set in compiler.config (see VariableConfig).
 */
public class VariableShiftingPass implements OptimizationPass {

    private final boolean enabled;

    public VariableShiftingPass() {
        this(false);
    }

    public VariableShiftingPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "variable-shifting";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        Map<Integer, String> rename = new HashMap<>();           // line index -> new variable name (token 1 of that line)
        Map<Integer, List<List<BytecodeToken>>> insertAfter = new TreeMap<>(); // ALLOC line index -> new ALLOC lines
        for (int[] f : VarAnalysis.functions(lines)) {
            if (VarAnalysis.hasMnemonic(lines, f[0], f[1], "ASM_START") || hasCatchLabel(lines, f[0], f[1])) continue;
            Map<String, VarAnalysis.Var> vars = VarAnalysis.scan(lines, f[0], f[1]);
            Map<String, Integer> labels = VarAnalysis.labels(lines, f[0], f[1]);
            List<int[]> jumps = VarAnalysis.jumps(lines, f[0], f[1], labels);
            Set<String> used = new HashSet<>();
            for (int i = f[0]; i <= f[1]; i++) {
                for (BytecodeToken t : lines.get(i)) used.add(t.text);
            }
            for (VarAnalysis.Var v : vars.values()) {
                if (v.bad || v.assigns.size() < 2) continue;
                int first = v.assigns.get(0);
                boolean declFirst = true;
                for (int r : v.reads) {
                    if (r < first) declFirst = false;
                }
                if (!declFirst) continue;
                // every mention after a given point, in order (reads and assignments)
                TreeMap<Integer, Boolean> mentions = new TreeMap<>(); // line index -> isAssign
                for (int r : v.reads) mentions.put(r, false);
                for (int a : v.assigns) mentions.put(a, true);
                String cur = v.name;
                int serial = 0;
                List<List<BytecodeToken>> newAllocs = new ArrayList<>();
                for (Map.Entry<Integer, Boolean> me : mentions.entrySet()) {
                    int idx = me.getKey();
                    if (me.getValue() && idx != first && canSplit(idx, mentions, jumps)) {
                        String nn;
                        do {
                            serial++;
                            nn = v.name + "__s" + serial;
                        } while (used.contains(nn));
                        used.add(nn);
                        cur = nn;
                        List<BytecodeToken> al = lines.get(v.allocIdx);
                        List<BytecodeToken> na = VarAnalysis.withToken(al, 1, nn);
                        newAllocs.add(na);
                    }
                    if (!cur.equals(v.name)) {
                        rename.put(idx, cur);
                    }
                }
                if (!newAllocs.isEmpty()) {
                    insertAfter.computeIfAbsent(v.allocIdx, k -> new ArrayList<>()).addAll(newAllocs);
                }
            }
        }
        if (rename.isEmpty()) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size() + 8);
        for (int i = 0; i < lines.size(); i++) {
            String nn = rename.get(i);
            out.add(nn == null ? lines.get(i) : VarAnalysis.withToken(lines.get(i), 1, nn));
            List<List<BytecodeToken>> extra = insertAfter.get(i);
            if (extra != null) out.addAll(extra);
        }
        return new PassResult(out, true);
    }

    /** A reassignment at line a can start a new version only if it is reached on every path to the later mentions and is not in a loop. */
    private static boolean canSplit(int a, TreeMap<Integer, Boolean> mentions, List<int[]> jumps) {
        if (VarAnalysis.inLoop(a, jumps)) return false;
        for (int m : mentions.tailMap(a, false).keySet()) {
            if (!VarAnalysis.dominated(a, m, jumps)) return false;
        }
        return true;
    }

    private static boolean hasCatchLabel(List<List<BytecodeToken>> L, int s, int e) {
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            if (l.size() == 1 && l.get(0).text.startsWith("@catch_")) return true;
        }
        return false;
    }
}

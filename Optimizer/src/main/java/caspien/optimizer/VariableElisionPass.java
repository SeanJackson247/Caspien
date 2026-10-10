package caspien.optimizer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Variable elision (constant propagation for a variable that is assigned exactly once): finds
 *
 *     ADDR x T
 *     PUSH <literal> T2
 *     ASSIGN ...
 *
 * where x is a local scalar that is never assigned again and never has its address taken, removes the assignment (and x's ALLOC),
 * and replaces every read "PUSH x T" with "PUSH <literal> T". With ConstantFoldingPass running in the same inner loop, the
 * substituted literals then fold in turn.
 *
 * Safe only if every read is reached through the assignment, so a read must sit after the assignment in the text and no forward jump
 * from before the assignment may land between it and the read (an assignment inside an 'if' branch does not license reads after the
 * branch). A variable with any other kind of mention (compound assignment, ++, address taken, struct field, parameter, ...) is left
 * alone. Only integers (u/s 8..64), f32/f64 and bool; a negative literal in a narrow type is not propagated. A function containing
 * inline assembly is skipped.
 *
 * Off unless "variable-elision: on" is set in compiler.config (see VariableConfig).
 */
public class VariableElisionPass implements OptimizationPass {

    private final boolean enabled;

    public VariableElisionPass() {
        this(false);
    }

    public VariableElisionPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "variable-elision";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        Set<Integer> remove = new HashSet<>();
        Map<Integer, String> replaceRead = new HashMap<>();   // line index of "PUSH x T" -> literal text
        // per function (keyed by FUNC_START index): elided name -> non-negative integer literal, for rewriting range types like imut_range(0,imut_n)
        TreeMap<Integer, Object[]> boundLits = new TreeMap<>();
        for (int[] f : VarAnalysis.functions(lines)) {
            if (VarAnalysis.hasMnemonic(lines, f[0], f[1], "ASM_START")) continue;
            Map<String, VarAnalysis.Var> vars = VarAnalysis.scan(lines, f[0], f[1]);
            Map<String, Integer> labels = VarAnalysis.labels(lines, f[0], f[1]);
            List<int[]> jumps = VarAnalysis.jumps(lines, f[0], f[1], labels);
            Map<String, String> lits = new HashMap<>();
            boundLits.put(f[0], new Object[] {f[1], lits});
            for (VarAnalysis.Var v : vars.values()) {
                if (v.bad || v.assigns.size() != 1 || v.name.startsWith("gt_trace_")) continue;   // read only by the backend's TRACE_CAPTURE walk
                int a = v.assigns.get(0);
                boolean ok = true;
                for (int r : v.reads) {
                    if (r < a || !VarAnalysis.dominated(a, r, jumps)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                String lit = lines.get(a + 1).get(1).text;
                remove.add(a);
                remove.add(a + 1);
                remove.add(a + 2);
                remove.add(v.allocIdx);
                if (lit.matches("[0-9]+")) lits.put(v.name, lit);
                for (int r : v.reads) {
                    replaceRead.put(r, lit);
                }
            }
        }
        if (remove.isEmpty()) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (remove.contains(i)) continue;
            String lit = replaceRead.get(i);
            List<BytecodeToken> row = lit == null ? lines.get(i) : VarAnalysis.withToken(lines.get(i), 1, lit);
            Map.Entry<Integer, Object[]> fe = boundLits.floorEntry(i);
            if (fe != null && i <= (Integer) fe.getValue()[0]) {
                @SuppressWarnings("unchecked")
                Map<String, String> lits = (Map<String, String>) fe.getValue()[1];
                if (!lits.isEmpty()) row = rewriteRangeTypes(row, lits);
            }
            out.add(row);
        }
        return new PassResult(out, true);
    }

    /**
     * A hidden for-range's type text spells a variable bound by name, e.g. {@code imut_range(0,imut_n)}. Once n is elided the name is
     * dangling, and LoopUnrollingPass (which reads literal trip counts only from this text) could not use the known bound. Rewrites
     * such bounds to the literal: {@code imut_range(0,4)}. Every token spelling the type is rewritten the same way, so they stay equal.
     */
    private static List<BytecodeToken> rewriteRangeTypes(List<BytecodeToken> row, Map<String, String> lits) {
        List<BytecodeToken> res = row;
        for (int k = 0; k < row.size(); k++) {
            String s = row.get(k).text;
            int open = s.indexOf("range(");
            if (open < 0 || !s.endsWith(")")) continue;
            String inner = s.substring(open + 6, s.length() - 1);
            int comma = inner.indexOf(',');
            if (comma < 0) continue;
            String lo = boundText(inner.substring(0, comma), lits), hi = boundText(inner.substring(comma + 1), lits);
            String ns = s.substring(0, open + 6) + lo + "," + hi + ")";
            if (!ns.equals(s)) {
                if (res == row) res = new ArrayList<>(row);
                BytecodeToken o = row.get(k);
                res.set(k, new BytecodeToken(ns, o.file, o.line, o.kind));
            }
        }
        return res;
    }

    private static String boundText(String part, Map<String, String> lits) {
        String nm = part.startsWith("imut_") ? part.substring(5) : part.startsWith("mut_") ? part.substring(4) : null;
        if (nm != null && lits.containsKey(nm)) return lits.get(nm);
        return part;
    }
}

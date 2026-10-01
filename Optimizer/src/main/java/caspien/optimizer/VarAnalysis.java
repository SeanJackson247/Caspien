package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared analysis for VariableElisionPass and VariableShiftingPass: for one function, finds the local scalar variables whose every
 * mention is one of exactly three shapes -- their own "ALLOC name T", a read "PUSH name T" (not immediately followed by ADDR_OF, which
 * would take the variable's address), or a constant assignment "ADDR name T / PUSH literal T2 / ASSIGN ..." -- and records where each
 * read and assignment sits. Any other mention (a compound assignment, ++, an address taken, a swap, a struct field, ...) makes the
 * variable "bad": neither pass touches it.
 *
 * Also provides the two control-flow questions both passes ask, answered from the jumps and labels of the function text:
 *   dominated(a, m): can a read at line m be reached without passing line a? (a forward jump sitting before a that lands between a and m)
 *   inLoop(a):       does a backward jump after line a land before it (a is inside a loop)?
 */
final class VarAnalysis {

    private VarAnalysis() {
    }

    static final class Var {
        String name, type;
        int allocs, allocIdx = -1;
        boolean bad;
        final List<Integer> reads = new ArrayList<>();
        final List<Integer> assigns = new ArrayList<>();   // index of the "ADDR name T" line; the literal PUSH is at +1
    }

    /** [start, end] line indices (FUNC_START .. FUNC_END inclusive) of every function. */
    static List<int[]> functions(List<List<BytecodeToken>> L) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < L.size(); i++) {
            String m = mnemonic(L.get(i));
            if ("FUNC_START".equals(m)) {
                start = i;
            } else if ("FUNC_END".equals(m) && start >= 0) {
                out.add(new int[] {start, i});
                start = -1;
            }
        }
        return out;
    }

    static String mnemonic(List<BytecodeToken> line) {
        if (line.isEmpty() || line.get(0).kind != BytecodeToken.Kind.CODE) return null;
        return line.get(0).text;
    }

    static boolean hasMnemonic(List<List<BytecodeToken>> L, int s, int e, String m) {
        for (int i = s; i <= e; i++) {
            if (m.equals(mnemonic(L.get(i)))) return true;
        }
        return false;
    }

    /** A label definition line is one token "@name:". */
    static Map<String, Integer> labels(List<List<BytecodeToken>> L, int s, int e) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            if (l.size() == 1 && l.get(0).kind == BytecodeToken.Kind.CODE && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":")) {
                String t = l.get(0).text;
                m.put(t.substring(0, t.length() - 1), i);
            }
        }
        return m;
    }

    /** Every "JMP @label" as {lineIndex, targetLabelLineIndex}; a target outside the function is ignored. */
    static List<int[]> jumps(List<List<BytecodeToken>> L, int s, int e, Map<String, Integer> labels) {
        List<int[]> out = new ArrayList<>();
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            if (l.size() == 2 && "JMP".equals(mnemonic(l))) {
                Integer t = labels.get(l.get(1).text);
                if (t != null) out.add(new int[] {i, t});
            }
        }
        return out;
    }

    static boolean dominated(int a, int m, List<int[]> jumps) {
        for (int[] j : jumps) {
            if (j[0] < a && j[1] > a && j[1] <= m) return false;
        }
        return true;
    }

    static boolean inLoop(int a, List<int[]> jumps) {
        for (int[] j : jumps) {
            if (j[0] > a && j[1] < a) return true;
        }
        return false;
    }

    /** The scan described in the class comment, for the function spanning lines s..e. */
    static Map<String, Var> scan(List<List<BytecodeToken>> L, int s, int e) {
        Map<String, Var> vars = new LinkedHashMap<>();
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            if (l.size() == 3 && "ALLOC".equals(mnemonic(l)) && !l.get(1).text.startsWith("$") && ConstantFoldingPass.base(l.get(2).text) != null) {
                Var v = vars.computeIfAbsent(l.get(1).text, k -> new Var());
                v.name = l.get(1).text;
                v.type = l.get(2).text;
                v.allocs++;
                v.allocIdx = i;
            }
        }
        for (Var v : vars.values()) {
            if (v.allocs != 1) v.bad = true;
        }
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            for (int t = 1; t < l.size(); t++) {
                BytecodeToken tok = l.get(t);
                if (tok.kind != BytecodeToken.Kind.CODE) continue;
                String txt = tok.text;
                Var v = vars.get(txt);
                if (v == null) {
                    int dot = txt.indexOf('.');
                    if (dot > 0) v = vars.get(txt.substring(0, dot));
                }
                if (v == null) continue;
                if (i == v.allocIdx && t == 1) continue; // its own ALLOC
                String m = mnemonic(l);
                if (t == 1 && txt.equals(v.name) && l.size() == 3 && "PUSH".equals(m) && !"ADDR_OF".equals(i + 1 <= e ? mnemonic(L.get(i + 1)) : null)) {
                    v.reads.add(i);
                } else if (t == 1 && txt.equals(v.name) && l.size() == 3 && "ADDR".equals(m) && i + 2 <= e && isConstAssign(L, i, v)) {
                    v.assigns.add(i);
                } else {
                    v.bad = true;
                }
            }
        }
        return vars;
    }

    /** "ADDR x T" at i, "PUSH literal T2" at i+1 (same base type as x), "ASSIGN ..." at i+2. */
    private static boolean isConstAssign(List<List<BytecodeToken>> L, int i, Var v) {
        ConstantFoldingPass.Lit lit = ConstantFoldingPass.lit(L.get(i + 1));
        if (lit == null) return false;
        String vb = ConstantFoldingPass.base(v.type);
        if (vb == null || !vb.equals(lit.base) || !vb.equals(ConstantFoldingPass.base(L.get(i).get(2).text))) return false;
        if (ConstantFoldingPass.isInt(vb) && lit.i.signum() < 0 && ConstantFoldingPass.width(vb) < 64) return false; // a negative narrow literal push is not relied on
        return "ASSIGN".equals(mnemonic(L.get(i + 2)));
    }

    static List<BytecodeToken> withToken(List<BytecodeToken> line, int idx, String text) {
        List<BytecodeToken> out = new ArrayList<>(line);
        BytecodeToken o = line.get(idx);
        out.set(idx, new BytecodeToken(text, o.file, o.line, o.kind));
        return out;
    }
}

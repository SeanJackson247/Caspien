package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds a provably-constant branch condition and removes the branch it guards (switch: {@code dead-control-flow-removal: on|off}, default off).
 *
 * The pattern is the three lines {@code PUSH true|false T ; CMP ; JMP L}, where {@code CMP ; JMP L} is "jump to L when the condition is false":
 *   - {@code PUSH true}:  the jump never happens, the three lines are deleted and execution falls through into the branch body; the code at L
 *                         (typically the else branch) is dead when nothing else refers to L;
 *   - {@code PUSH false}: the jump always happens, the three lines become {@code JMP L} and the code between it and L (the branch body) is dead
 *                         unless something jumps into it.
 * What is dead is decided by flow reachability over the function (fallthrough, JMP, CMP+JMP both ways; RET, THROW and an unconditional JMP end a
 * path; the roots are the function start, every label that is not one of the compiler's structured if/loop/for/match labels, and every label
 * referenced by something other than a JMP -- catch entries, unwinding callsites). Only lines that become unreachable BECAUSE of the rewrite are
 * deleted; code that was already unreachable before it is left alone. Declarations (ALLOC, ARG, REGVAR, ...) are never deleted. Afterwards a {@code JMP L} directly followed by the definition of L is dropped.
 * Only functions without ASM_START are touched. No other unreachable-code analysis is done.
 */
public class DeadControlFlowRemovalPass implements OptimizationPass {

    private final boolean enabled;

    public DeadControlFlowRemovalPass() {
        this(false);
    }

    public DeadControlFlowRemovalPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "dead-control-flow-removal";
    }

    private static final String[] STRUCTURAL = {"@if_branch_", "@end_of_if_", "@loop_", "@loop_end_", "@for_", "@for_end_", "@match_branch_", "@end_of_match_"};
    private static final Set<String> DECLS = new HashSet<>(List.of("ALLOC", "ARG", "REGVAR", "RETURNS", "FUNC_DECORATE", "STRING", "ALLOC_STATIC"));

    private static boolean isStructural(String label) {
        for (String p : STRUCTURAL) {
            if (label.startsWith(p)) return true;
        }
        return false;
    }

    private static boolean isLabelDef(List<BytecodeToken> l) {
        return l.size() == 1 && l.get(0).kind == BytecodeToken.Kind.CODE && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":");
    }

    private static String labelName(List<BytecodeToken> l) {
        String t = l.get(0).text;
        return t.substring(0, t.length() - 1);
    }

    private static boolean isBoolLit(List<BytecodeToken> l, String lit) {
        return "PUSH".equals(VarAnalysis.mnemonic(l)) && l.size() == 3 && l.get(1).text.equals(lit);
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> input) {
        if (!enabled) {
            return new PassResult(input, false);
        }
        List<List<BytecodeToken>> lines = new ArrayList<>(input);
        boolean changed = false;
        boolean again = true;
        while (again) {
            again = false;
            for (int[] f : VarAnalysis.functions(lines)) {
                if (VarAnalysis.hasMnemonic(lines, f[0], f[1], "ASM_START")) continue;
                if (rewriteOne(lines, f[0], f[1])) {
                    changed = true;
                    again = true;
                    break;
                }
            }
        }
        return new PassResult(changed ? lines : input, changed);
    }

    /** Applies the first applicable rewrite in [s, e]; returns whether one was made. */
    private static boolean rewriteOne(List<List<BytecodeToken>> L, int s, int e) {
        for (int i = s + 1; i + 2 <= e; i++) {
            List<BytecodeToken> p = L.get(i);
            boolean isTrue = isBoolLit(p, "true"), isFalse = isBoolLit(p, "false");
            if (!isTrue && !isFalse) continue;
            List<BytecodeToken> c = L.get(i + 1), j = L.get(i + 2);
            if (!"CMP".equals(VarAnalysis.mnemonic(c)) || c.size() != 1) continue;
            if (!"JMP".equals(VarAnalysis.mnemonic(j)) || j.size() != 2) continue;
            // Lines that are already unreachable before this rewrite are none of this pass's business.
            Set<List<BytecodeToken>> alreadyDead = newIdentitySet();
            boolean[] before = reachable(L, s, e);
            for (int k = s; k <= e; k++) {
                if (!before[k - s]) alreadyDead.add(L.get(k));
            }
            int newEnd;
            if (isTrue) {
                L.subList(i, i + 3).clear();                       // never jumps: fall through into the branch
                newEnd = e - 3;
            } else {
                L.set(i, j);                                       // always jumps
                L.subList(i + 1, i + 3).clear();
                newEnd = e - 2;
            }
            boolean[] after = reachable(L, s, newEnd);
            boolean[] keep = new boolean[newEnd - s + 1];
            for (int k = s; k <= newEnd; k++) {
                List<BytecodeToken> l = L.get(k);
                boolean dead = !after[k - s] && !alreadyDead.contains(l);
                String m = VarAnalysis.mnemonic(l);
                if (dead && m != null && (DECLS.contains(m) || m.equals("FUNC_START") || m.equals("FUNC_END"))) dead = false;
                keep[k - s] = !dead;
            }
            // A label that a kept line still names must stay: code that was unreachable before this rewrite (for example the jump that
            // follows an inlined early return) is left alone, and it may still refer to a label whose only reachable jumps just vanished.
            boolean again = true;
            while (again) {
                again = false;
                Set<String> named = new HashSet<>();
                for (int k = s; k <= newEnd; k++) {
                    List<BytecodeToken> l = L.get(k);
                    if (!keep[k - s] || isLabelDef(l)) continue;
                    for (BytecodeToken t : l) if (t.text.startsWith("@")) named.add(t.text);
                }
                for (int k = s; k <= newEnd; k++) {
                    if (!keep[k - s] && isLabelDef(L.get(k)) && named.contains(labelName(L.get(k)))) { keep[k - s] = true; again = true; }
                }
            }
            List<List<BytecodeToken>> out = new ArrayList<>();
            for (int k = s; k <= newEnd; k++) if (keep[k - s]) out.add(L.get(k));
            L.subList(s, newEnd + 1).clear();
            L.addAll(s, out);
            dropJumpToNext(L, s, s + out.size() - 1);
            return true;
        }
        return false;
    }

    private static Set<List<BytecodeToken>> newIdentitySet() {
        return java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }

    /**
     * Flow reachability over the lines [s, e] of one function. Roots: the function start and every label that is not one of the compiler's
     * structured control-flow labels or that is referenced by anything other than a JMP (catch entries, unwinding callsites, ...). A line
     * flows into the next one unless it is RET, THROW or a JMP that is not the second half of "CMP ; JMP" (which also flows on); JMP flows to its label.
     */
    private static boolean[] reachable(List<List<BytecodeToken>> L, int s, int e) {
        int n = e - s + 1;
        boolean[] seen = new boolean[n];
        Map<String, Integer> labels = new HashMap<>();
        Set<String> otherRefs = new HashSet<>();
        for (int k = s; k <= e; k++) {
            List<BytecodeToken> l = L.get(k);
            if (isLabelDef(l)) {
                labels.put(labelName(l), k);
                continue;
            }
            if ("JMP".equals(VarAnalysis.mnemonic(l))) continue;
            for (BytecodeToken t : l) {
                if (t.text.startsWith("@")) otherRefs.add(t.text);
            }
        }
        java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
        work.add(s);
        for (Map.Entry<String, Integer> en : labels.entrySet()) {
            if (!isStructural(en.getKey()) || otherRefs.contains(en.getKey())) work.add(en.getValue());
        }
        while (!work.isEmpty()) {
            int k = work.poll();
            while (k <= e && !seen[k - s]) {
                seen[k - s] = true;
                List<BytecodeToken> l = L.get(k);
                String m = VarAnalysis.mnemonic(l);
                if ("FUNC_END".equals(m) || "RET".equals(m) || "THROW".equals(m)) break;
                if ("JMP".equals(m) && l.size() == 2) {
                    Integer tgt = labels.get(l.get(1).text);
                    if (tgt != null) work.add(tgt);
                    boolean conditional = k > s && "CMP".equals(VarAnalysis.mnemonic(L.get(k - 1)));
                    if (!conditional) break;
                }
                k++;
            }
        }
        return seen;
    }

    /** Removes an unconditional "JMP L" directly followed by "L:" in [s, e]. */
    private static void dropJumpToNext(List<List<BytecodeToken>> L, int s, int e) {
        int fs = Math.min(s, L.size() - 1);
        int fe = Math.min(e, L.size() - 1);
        for (int k = fe - 1; k > fs; k--) {
            List<BytecodeToken> l = L.get(k), nx = L.get(k + 1);
            if (!"JMP".equals(VarAnalysis.mnemonic(l)) || l.size() != 2 || !isLabelDef(nx)) continue;
            if (!l.get(1).text.equals(labelName(nx))) continue;
            if ("CMP".equals(VarAnalysis.mnemonic(L.get(k - 1)))) continue;
            L.remove(k);
        }
    }
}

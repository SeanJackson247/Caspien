package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unrolls counted `for` loops whose trip count can be read straight off the bytecode, and nothing else.
 *
 * A loop qualifies only when the hidden range's type text carries two integer literals, e.g.
 * {@code ALLOC $for_range_3 imut_range(0,4)}. A bound that is a variable shows up as
 * {@code imut_range(2,mut_n)} and is never unrolled: this pass does no constant propagation, no induction
 * analysis and no folding (those are other passes' jobs; because the optimizer repeats until nothing changes,
 * a bound another pass later turns into a literal is picked up on a later round). {@code loop{}} has no
 * counted structure in the bytecode and is never touched.
 *
 * The recognised shape (emitted by the front end for every `for`):
 * <pre>
 *   ADDR $for_range_N T ; PUSH lo ; PUSH hi ; ASSIGN T T T
 *   ADDR i vt ; PUSH $for_range_N.start indeterminate_u64 ; ASSIGN vt vt vt
 *   @for_A: ; PUSH i vt ; PUSH $for_range_N T ; IN vt T indeterminate_bool ; CMP ; JMP @for_end_B
 *   ...body...
 *   ADDR i vt ; PUSH i vt ; INC vt indeterminate_u64 ; ASSIGN vt indeterminate_u64 indeterminate_u64 ; JMP @for_A
 *   @for_end_B:
 * </pre>
 * Anything that does not match exactly is left alone.
 *
 * Full unroll (trip count N within the configured limit): the test and back-jump go, the body is repeated N
 * times with the induction variable's own increment between copies (the variable stays a real variable; it is
 * not replaced by a constant).
 *
 * Partial unroll (factor U, N >= 2U): the hidden range's high bound becomes lo + floor(N/U)*U (in the literal
 * and everywhere the type text appears), the loop body holds U copies each followed by the increment, and the
 * N mod U leftover iterations follow the loop as plain copies. The loop test failing jumps to a new label in
 * front of the leftover copies; a `break` in the body still jumps to the original end label, which now sits
 * after them. The partially unrolled loop's header label is renamed so it is never matched again.
 *
 * Copies get fresh labels (numbering continues above the highest number in the program, keeping each label's
 * name prefix, so a copied inner `for` is still recognisable to a later round). A loop is skipped when its body
 * contains an allocation or declaration line, mentions its own range or header label, or defines a label that
 * is referenced from outside the body (a try block, for instance). Innermost candidates go first; an outer loop
 * is considered on a later round with its now larger body.
 */
public class LoopUnrollingPass implements OptimizationPass {

    private final UnrollConfig cfg;
    private final Map<String, Long> grown = new HashMap<>();
    private int nextLabel = 0;

    public LoopUnrollingPass() {
        this(UnrollConfig.disabled());
    }

    public LoopUnrollingPass(UnrollConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return "loop-unrolling";
    }

    // ---- small helpers over the token lines --------------------------------------------------------------

    private static String tok(List<BytecodeToken> line, int i) {
        return i < line.size() ? line.get(i).text : null;
    }

    private static boolean is(List<BytecodeToken> line, int size, String first) {
        return line.size() == size && line.get(0).kind == BytecodeToken.Kind.CODE && line.get(0).text.equals(first);
    }

    private static boolean isLabelDef(List<BytecodeToken> line) {
        return line.size() == 1 && line.get(0).kind == BytecodeToken.Kind.CODE
                && line.get(0).text.startsWith("@") && line.get(0).text.endsWith(":");
    }

    private static boolean allDigits(String s) {
        if (s == null || s.isEmpty() || s.length() > 12) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** "@for_12:" -> "for_12"; "@for_12" -> "for_12". */
    private static String labelName(String t) {
        String s = t.substring(1);
        return s.endsWith(":") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean labelWithPrefix(List<BytecodeToken> line, String prefix) {
        return isLabelDef(line) && allDigits(labelName(line.get(0).text).startsWith(prefix)
                ? labelName(line.get(0).text).substring(prefix.length()) : null);
    }

    private static BytecodeToken retext(BytecodeToken t, String text) {
        return new BytecodeToken(text, t.file, t.line, t.kind);
    }

    // ---- candidate loop ----------------------------------------------------------------------------------

    private static final class Loop {
        int start;            // index of "ADDR $for_range_N T"
        int bodyStart;        // first body line
        int incStart;         // first line of the 4-line increment (= body end, exclusive)
        int jmpIdx;           // "JMP @for_A"
        int endIdx;           // "@for_end_B:"
        String rangeName, rangeType, var, varType, headLabel, endLabel;
        long lo, hi;
        int allocIdx = -1;    // "ALLOC $for_range_N T" near the top of the function
    }

    private static Loop match(List<List<BytecodeToken>> L, int s, int fnEnd) {
        if (s + 13 >= fnEnd) return null;
        List<BytecodeToken> a = L.get(s);
        if (!is(a, 3, "ADDR") || !a.get(1).text.startsWith("$for_range_")) return null;
        String rangeName = a.get(1).text, T = a.get(2).text;
        int open = T.indexOf("range(");
        if (open < 0 || !T.endsWith(")")) return null;
        String inner = T.substring(open + 6, T.length() - 1);
        int comma = inner.indexOf(',');
        if (comma < 0) return null;
        String loS = inner.substring(0, comma), hiS = inner.substring(comma + 1);
        if (!allDigits(loS) || !allDigits(hiS)) return null;
        long lo = Long.parseLong(loS), hi = Long.parseLong(hiS);
        if (hi <= lo) return null;
        List<BytecodeToken> p1 = L.get(s + 1), p2 = L.get(s + 2), as = L.get(s + 3);
        if (!is(p1, 3, "PUSH") || !p1.get(1).text.equals(loS)) return null;
        if (!is(p2, 3, "PUSH") || !p2.get(1).text.equals(hiS)) return null;
        if (!is(as, 4, "ASSIGN") || !as.get(1).text.equals(T) || !as.get(2).text.equals(T) || !as.get(3).text.equals(T)) return null;
        List<BytecodeToken> av = L.get(s + 4);
        if (!is(av, 3, "ADDR") || av.get(1).text.startsWith("$")) return null;
        String var = av.get(1).text, vt = av.get(2).text;
        List<BytecodeToken> st = L.get(s + 5);
        if (!is(st, 3, "PUSH") || !st.get(1).text.equals(rangeName + ".start")) return null;
        List<BytecodeToken> as2 = L.get(s + 6);
        if (!is(as2, 4, "ASSIGN") || !as2.get(1).text.equals(vt) || !as2.get(2).text.equals(vt) || !as2.get(3).text.equals(vt)) return null;
        if (!labelWithPrefix(L.get(s + 7), "for_")) return null;
        String head = L.get(s + 7).get(0).text;
        head = head.substring(0, head.length() - 1);
        List<BytecodeToken> t1 = L.get(s + 8), t2 = L.get(s + 9), t3 = L.get(s + 10), t4 = L.get(s + 11), t5 = L.get(s + 12);
        if (!is(t1, 3, "PUSH") || !t1.get(1).text.equals(var) || !t1.get(2).text.equals(vt)) return null;
        if (!is(t2, 3, "PUSH") || !t2.get(1).text.equals(rangeName) || !t2.get(2).text.equals(T)) return null;
        if (!is(t3, 4, "IN") || !t3.get(1).text.equals(vt) || !t3.get(2).text.equals(T)) return null;
        if (!is(t4, 1, "CMP")) return null;
        if (!is(t5, 2, "JMP") || !t5.get(1).text.startsWith("@for_end_")) return null;
        String endLabel = t5.get(1).text;
        int endIdx = -1;
        for (int k = s + 13; k < fnEnd; k++) {
            List<BytecodeToken> l = L.get(k);
            if (isLabelDef(l) && l.get(0).text.equals(endLabel + ":")) {
                endIdx = k;
                break;
            }
        }
        if (endIdx < 0 || endIdx - 5 < s + 13) return null;
        int jmp = endIdx - 1;
        if (!is(L.get(jmp), 2, "JMP") || !L.get(jmp).get(1).text.equals(head)) return null;
        int inc = jmp - 4;
        List<BytecodeToken> i1 = L.get(inc), i2 = L.get(inc + 1), i3 = L.get(inc + 2), i4 = L.get(inc + 3);
        if (!is(i1, 3, "ADDR") || !i1.get(1).text.equals(var) || !i1.get(2).text.equals(vt)) return null;
        if (!is(i2, 3, "PUSH") || !i2.get(1).text.equals(var) || !i2.get(2).text.equals(vt)) return null;
        if (!is(i3, 3, "INC") || !i3.get(1).text.equals(vt)) return null;
        if (!is(i4, 4, "ASSIGN")) return null;
        Loop lp = new Loop();
        lp.start = s;
        lp.bodyStart = s + 13;
        lp.incStart = inc;
        lp.jmpIdx = jmp;
        lp.endIdx = endIdx;
        lp.rangeName = rangeName;
        lp.rangeType = T;
        lp.var = var;
        lp.varType = vt;
        lp.headLabel = head;
        lp.endLabel = endLabel;
        lp.lo = lo;
        lp.hi = hi;
        return lp;
    }

    /** Is this candidate safe to copy? Also fills in allocIdx. */
    private static boolean copyable(List<List<BytecodeToken>> L, Loop lp, int fnStart, int fnEnd) {
        Set<String> defined = new HashSet<>();
        for (int k = lp.bodyStart; k < lp.incStart; k++) {
            List<BytecodeToken> l = L.get(k);
            if (l.isEmpty()) continue;
            String f = l.get(0).text;
            if (l.get(0).kind == BytecodeToken.Kind.CODE && (f.equals("ALLOC") || f.equals("ALLOC_STATIC") || f.equals("FUNC_START")
                    || f.equals("FUNC_END") || f.equals("STRUCT_START") || f.equals("GLOBAL") || f.equals("REGVAR"))) {
                return false;
            }
            for (BytecodeToken t : l) {
                if (t.kind != BytecodeToken.Kind.CODE) continue;
                if (t.text.equals(lp.headLabel) || t.text.equals(lp.headLabel + ":") || t.text.equals(lp.rangeName) || t.text.startsWith(lp.rangeName + ".")) return false;
            }
            if (isLabelDef(l)) {
                defined.add(labelName(l.get(0).text));
            }
        }
        if (!defined.isEmpty()) {
            for (int k = fnStart; k < fnEnd; k++) {
                if (k >= lp.bodyStart && k < lp.incStart) continue;
                for (BytecodeToken t : L.get(k)) {
                    if (t.kind == BytecodeToken.Kind.CODE && t.text.startsWith("@") && defined.contains(labelName(t.text))) {
                        return false; // a label of the body is used from outside it (try block, etc.)
                    }
                }
            }
        }
        for (int k = fnStart; k < lp.start; k++) {
            List<BytecodeToken> l = L.get(k);
            if (is(l, 3, "ALLOC") && l.get(1).text.equals(lp.rangeName)) {
                lp.allocIdx = k;
                break;
            }
        }
        return true;
    }

    // ---- the pass ----------------------------------------------------------------------------------------

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!cfg.enabled) {
            return new PassResult(lines, false);
        }
        int maxNum = 0;
        for (List<BytecodeToken> l : lines) {
            for (BytecodeToken t : l) {
                if (t.kind == BytecodeToken.Kind.CODE && t.text.startsWith("@")) {
                    String n = labelName(t.text);
                    int us = n.lastIndexOf('_');
                    if (us >= 0 && allDigits(n.substring(us + 1))) {
                        maxNum = Math.max(maxNum, (int) Math.min(Long.parseLong(n.substring(us + 1)), 1_000_000_000L));
                    }
                }
            }
        }
        nextLabel = Math.max(nextLabel, maxNum + 1);

        List<List<BytecodeToken>> work = new ArrayList<>(lines);
        boolean changed = false;
        int i = 0;
        while (i < work.size()) {
            List<BytecodeToken> l = work.get(i);
            if (!is(l, 2, "FUNC_START")) {
                i++;
                continue;
            }
            int fnStart = i;
            int fnEnd = fnStart + 1;
            while (fnEnd < work.size() && !(work.get(fnEnd).size() >= 1 && work.get(fnEnd).get(0).text.equals("FUNC_END"))) {
                fnEnd++;
            }
            String fname = l.get(1).text;
            List<Loop> cands = new ArrayList<>();
            for (int k = fnStart + 1; k < fnEnd; k++) {
                Loop lp = match(work, k, fnEnd);
                if (lp != null && copyable(work, lp, fnStart, fnEnd)) {
                    cands.add(lp);
                }
            }
            // innermost candidates only (no other candidate inside), applied from the back so earlier indices stay valid
            List<Loop> inner = new ArrayList<>();
            for (Loop a : cands) {
                boolean hasInner = false;
                for (Loop b : cands) {
                    if (b != a && b.start > a.start && b.endIdx < a.endIdx) {
                        hasInner = true;
                        break;
                    }
                }
                if (!hasInner) inner.add(a);
            }
            int delta = 0;
            for (int c = inner.size() - 1; c >= 0; c--) {
                Loop lp = inner.get(c);
                int added = apply(work, lp, fname);
                if (added != Integer.MIN_VALUE) {
                    changed = true;
                    delta += added;
                }
            }
            i = fnEnd + delta + 1;
            if (i <= fnStart) i = fnStart + 1;
        }
        return changed ? new PassResult(work, true) : new PassResult(lines, false);
    }

    /** Applies the chosen unroll to work in place; returns the change in line count, or MIN_VALUE if nothing was done. */
    private int apply(List<List<BytecodeToken>> work, Loop lp, String fname) {
        long n = lp.hi - lp.lo;
        int bodyLines = lp.incStart - lp.bodyStart;
        if (bodyLines > cfg.maxBodyLines) {
            return Integer.MIN_VALUE;
        }
        long unit = bodyLines + 4L;
        long budget = cfg.maxGrowth - grown.getOrDefault(fname, 0L);
        boolean full = cfg.fullMaxTrips > 0 && n <= cfg.fullMaxTrips && (n - 1) * unit <= budget;
        int u = cfg.factor;
        boolean partial = false;
        long rem = 0;
        if (!full && u >= 2 && n >= 2L * u) {
            rem = n % u;
            long extra = (u - 1 + rem) * unit + 3;
            partial = extra <= budget;
        }
        if (!full && !partial) {
            return Integer.MIN_VALUE;
        }
        int oldSize = lp.endIdx - lp.start + 1;
        List<List<BytecodeToken>> out = new ArrayList<>();
        BytecodeToken ref = work.get(lp.start).get(0);
        List<List<BytecodeToken>> body = new ArrayList<>(work.subList(lp.bodyStart, lp.incStart));
        List<List<BytecodeToken>> inc = new ArrayList<>(work.subList(lp.incStart, lp.jmpIdx));

        if (full) {
            for (int k = lp.start; k <= lp.start + 6; k++) out.add(work.get(k));
            // When the body only READS the induction variable (every mention is a plain "PUSH var type"), copy c gets the literal
            // lo+c in its place and the increments between copies go: the variable is a constant in each copy, so the folding passes
            // can see through "i * 8", "bits >> i" and the like. The variable is dead after the loop (it is scoped to it).
            boolean subst = onlyReadsVar(body, lp);
            for (long c = 0; c < n; c++) {
                List<List<BytecodeToken>> copy = c == 0 ? body : freshCopy(body);
                out.addAll(subst ? substVar(copy, lp, lp.lo + c) : copy);
                if (!subst && c < n - 1) out.addAll(inc);
            }
            out.add(work.get(lp.endIdx));
        } else {
            long trips = n / u;
            long newHi = lp.lo + trips * u;
            String newType = lp.rangeType.substring(0, lp.rangeType.indexOf('(') + 1) + lp.lo + "," + newHi + ")";
            for (int k = lp.start; k <= lp.start + 6; k++) {
                List<BytecodeToken> src = work.get(k);
                List<BytecodeToken> row = new ArrayList<>();
                for (BytecodeToken t : src) {
                    if (t.text.equals(lp.rangeType)) row.add(retext(t, newType));
                    else if (k == lp.start + 2 && t.text.equals(Long.toString(lp.hi)) && row.size() == 1) row.add(retext(t, Long.toString(newHi)));
                    else row.add(t);
                }
                out.add(row);
            }
            String fuLabel = "@fu_" + lp.headLabel.substring("@for_".length());
            String remLabel = "@for_rem_" + (nextLabel++);
            out.add(label(ref, fuLabel + ":"));
            for (int k = lp.start + 8; k <= lp.start + 12; k++) {
                List<BytecodeToken> src = work.get(k);
                List<BytecodeToken> row = new ArrayList<>();
                for (BytecodeToken t : src) {
                    if (t.text.equals(lp.rangeType)) row.add(retext(t, newType));
                    else if (k == lp.start + 12 && t.text.equals(lp.endLabel)) row.add(retext(t, remLabel));
                    else row.add(t);
                }
                out.add(row);
            }
            for (int c = 0; c < u; c++) {
                out.addAll(c == 0 ? body : freshCopy(body));
                out.addAll(inc);
            }
            List<BytecodeToken> back = new ArrayList<>();
            back.add(retext(ref, "JMP"));
            back.add(retext(ref, fuLabel));
            out.add(back);
            out.add(label(ref, remLabel + ":"));
            for (long c = 0; c < rem; c++) {
                out.addAll(freshCopy(body));
                if (c < rem - 1) out.addAll(inc);
            }
            out.add(work.get(lp.endIdx));
            if (lp.allocIdx >= 0) {
                List<BytecodeToken> al = new ArrayList<>();
                for (BytecodeToken t : work.get(lp.allocIdx)) {
                    al.add(t.text.equals(lp.rangeType) ? retext(t, newType) : t);
                }
                work.set(lp.allocIdx, al);
            }
        }
        // replace [start, endIdx] by out
        for (int k = lp.endIdx; k >= lp.start; k--) work.remove(k);
        work.addAll(lp.start, out);
        int added = out.size() - oldSize;
        grown.merge(fname, (long) Math.max(added, 0), Long::sum);
        return added;
    }

    /** True when every mention of the loop variable in the body is a plain "PUSH var vt" that is not directly followed by ADDR_OF. */
    private static boolean onlyReadsVar(List<List<BytecodeToken>> body, Loop lp) {
        // Innermost loops only: substituting a literal into a body that holds another loop would give that loop literal bounds, and the
        // nested full unrolling that follows multiplies the code (n-body: 2.5x the code, 1.7x slower).
        for (List<BytecodeToken> l : body) {
            if (l.size() == 1 && (l.get(0).text.startsWith("@for_") || l.get(0).text.startsWith("@fu_"))) return false;
            // A range proof (`match i in a` -> IN) on a literal would stay as a run-time check on constants and cost the register form.
            if (!l.isEmpty() && l.get(0).text.equals("IN")) return false;
        }
        for (int k = 0; k < body.size(); k++) {
            List<BytecodeToken> l = body.get(k);
            boolean mentions = false;
            for (int t = 1; t < l.size(); t++) {
                if (l.get(t).kind == BytecodeToken.Kind.CODE && l.get(t).text.equals(lp.var)) {
                    mentions = true;
                    if (t != 1) return false;
                }
            }
            if (!mentions) continue;
            if (!is(l, 3, "PUSH") || !l.get(2).text.equals(lp.varType)) return false;
            if (k + 1 < body.size() && !body.get(k + 1).isEmpty() && body.get(k + 1).get(0).text.equals("ADDR_OF")) return false;
        }
        return true;
    }

    /** The body with every "PUSH var vt" turned into "PUSH value vt". */
    private static List<List<BytecodeToken>> substVar(List<List<BytecodeToken>> body, Loop lp, long value) {
        List<List<BytecodeToken>> out = new ArrayList<>(body.size());
        for (List<BytecodeToken> l : body) {
            if (is(l, 3, "PUSH") && l.get(1).text.equals(lp.var) && l.get(2).text.equals(lp.varType)) {
                List<BytecodeToken> row = new ArrayList<>(l);
                row.set(1, retext(l.get(1), Long.toString(value)));
                out.add(row);
            } else {
                out.add(l);
            }
        }
        return out;
    }

    private static List<BytecodeToken> label(BytecodeToken ref, String text) {
        List<BytecodeToken> l = new ArrayList<>();
        l.add(retext(ref, text));
        return l;
    }

    /** A copy of the body in which every label it defines (and every use of one) gets a fresh name. */
    private List<List<BytecodeToken>> freshCopy(List<List<BytecodeToken>> body) {
        Map<String, String> map = new HashMap<>();
        for (List<BytecodeToken> l : body) {
            if (isLabelDef(l)) {
                String old = labelName(l.get(0).text);
                int us = old.lastIndexOf('_');
                String fresh = (us >= 0 && allDigits(old.substring(us + 1)))
                        ? old.substring(0, us + 1) + (nextLabel++)
                        : old + "_u" + (nextLabel++);
                map.put(old, fresh);
            }
        }
        List<List<BytecodeToken>> out = new ArrayList<>(body.size());
        for (List<BytecodeToken> l : body) {
            if (map.isEmpty()) {
                out.add(l);
                continue;
            }
            List<BytecodeToken> row = new ArrayList<>(l.size());
            for (BytecodeToken t : l) {
                if (t.kind == BytecodeToken.Kind.CODE && t.text.startsWith("@")) {
                    boolean colon = t.text.endsWith(":");
                    String repl = map.get(labelName(t.text));
                    row.add(repl == null ? t : retext(t, "@" + repl + (colon ? ":" : "")));
                } else {
                    row.add(t);
                }
            }
            out.add(row);
        }
        return out;
    }
}

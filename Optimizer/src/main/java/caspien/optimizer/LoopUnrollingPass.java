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
        int headIdx;          // index of "@for_A:" (after any FOR_DECORATE lines)
        boolean forced, dont; // @unroll / @dont(unroll) written on the loop
        int factor;           // @unroll(N): N, or 0 for a plain @unroll (full)
        String pos;           // "file:line" of the decorator, for messages
    }

    /** The most lines one @unroll may add to a function (a safety net; the preset budgets do not apply to a forced unroll). */
    static final long HARD_CAP = 20000L;

    private static boolean isForDeco(List<BytecodeToken> l) {
        return (l.size() == 2 || l.size() == 3) && l.get(0).kind == BytecodeToken.Kind.CODE && l.get(0).text.equals("FOR_DECORATE");
    }

    private static String stripQuotes(String t) {
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
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
        if (hi < lo) return null;   // hi == lo: an empty range, removed by a full unroll of zero copies
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
        int h = s + 7;
        boolean forced = false, dont = false;
        int factor = 0;
        String pos = null;
        while (h < fnEnd && isForDeco(L.get(h))) {
            String dt = L.get(h).get(1).text;
            if (dt.equals("@unroll")) {
                forced = true;
            } else if (dt.startsWith("@unroll(") && dt.endsWith(")") && allDigits(dt.substring(8, dt.length() - 1))) {
                forced = true;
                factor = Integer.parseInt(dt.substring(8, dt.length() - 1));
            } else if (dt.equals("@dont(unroll)")) {
                dont = true;
            }
            if (L.get(h).size() == 3) pos = stripQuotes(L.get(h).get(2).text);
            h++;
        }
        if (h + 6 >= fnEnd) return null;
        if (!labelWithPrefix(L.get(h), "for_")) return null;
        String head = L.get(h).get(0).text;
        head = head.substring(0, head.length() - 1);
        List<BytecodeToken> t1 = L.get(h + 1), t2 = L.get(h + 2), t3 = L.get(h + 3), t4 = L.get(h + 4), t5 = L.get(h + 5);
        if (!is(t1, 3, "PUSH") || !t1.get(1).text.equals(var) || !t1.get(2).text.equals(vt)) return null;
        if (!is(t2, 3, "PUSH") || !t2.get(1).text.equals(rangeName) || !t2.get(2).text.equals(T)) return null;
        if (!is(t3, 4, "IN") || !t3.get(1).text.equals(vt) || !t3.get(2).text.equals(T)) return null;
        if (!is(t4, 1, "CMP")) return null;
        if (!is(t5, 2, "JMP") || !t5.get(1).text.startsWith("@for_end_")) return null;
        String endLabel = t5.get(1).text;
        int endIdx = -1;
        for (int k = h + 6; k < fnEnd; k++) {
            List<BytecodeToken> l = L.get(k);
            if (isLabelDef(l) && l.get(0).text.equals(endLabel + ":")) {
                endIdx = k;
                break;
            }
        }
        if (endIdx < 0 || endIdx - 5 < h + 6) return null;
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
        lp.bodyStart = h + 6;
        lp.headIdx = h;
        lp.forced = forced;
        lp.dont = dont;
        lp.factor = factor;
        lp.pos = pos;
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
        if (!cfg.enabled && !anyUnrollDecorator(lines)) {
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
                    // an @unroll loop is not held back by an inner loop that will stay a loop
                    if (b != a && b.start > a.start && b.endIdx < a.endIdx && (!a.forced || plan(b, fname) != null)) {
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
            delta += flushAllocs(work, fnStart);
            i = fnEnd + delta + 1;
            if (i <= fnStart) i = fnStart + 1;
        }
        return changed ? new PassResult(work, true) : new PassResult(lines, false);
    }

    private static final class Plan {
        final boolean full;
        final int u;
        final long rem;
        Plan(boolean full, int u, long rem) {
            this.full = full;
            this.u = u;
            this.rem = rem;
        }
    }

    private static boolean anyUnrollDecorator(List<List<BytecodeToken>> lines) {
        for (List<BytecodeToken> l : lines) {
            if (isForDeco(l) && l.get(1).text.startsWith("@unroll")) return true;
        }
        return false;
    }

    /** What would be done to this loop (null = leave it). Pure: no messages. */
    private Plan plan(Loop lp, String fname) {
        if (lp.dont) return null;
        long n = lp.hi - lp.lo;
        if (lp.forced) {
            long unit = (lp.incStart - lp.bodyStart) + 4L;
            int f = lp.factor;
            if (f == 0 || n <= f) {
                return (n - 1) * unit > HARD_CAP ? null : new Plan(true, 0, 0);
            }
            long rem = n % f;
            return (f - 1 + rem) * unit + 3 > HARD_CAP ? null : new Plan(false, f, rem);
        }
        return heuristicPlan(lp, fname);
    }

    private Plan heuristicPlan(Loop lp, String fname) {
        if (!cfg.enabled) {
            return null;
        }
        long n = lp.hi - lp.lo;
        int bodyLines = lp.incStart - lp.bodyStart;
        if (bodyLines > cfg.maxBodyLines) {
            return null;
        }
        long unit = bodyLines + 4L;
        long budget = cfg.maxGrowth - grown.getOrDefault(fname, 0L);
        boolean full = cfg.fullMaxTrips > 0 && n <= cfg.fullMaxTrips && (n - 1) * unit <= budget;
        if (full) {
            return new Plan(true, 0, 0);
        }
        int u = cfg.factor;
        if (u >= 2 && n >= 2L * u) {
            long rem = n % u;
            long extra = (u - 1 + rem) * unit + 3;
            if (extra <= budget) {
                return new Plan(false, u, rem);
            }
        }
        return null;
    }

    private final Set<String> noted = new HashSet<>();

    private static void note(String pos, String msg) {
        System.err.println("[note] " + (pos != null ? pos + " - " : "") + msg);
    }

    /** Applies the chosen unroll to work in place; returns the change in line count, or MIN_VALUE if nothing was done. */
    private int apply(List<List<BytecodeToken>> work, Loop lp, String fname) {
        long n = lp.hi - lp.lo;
        Plan pl = plan(lp, fname);
        if (pl == null) {
            if (lp.dont && heuristicPlan(lp, fname) != null && noted.add("dont:" + lp.pos + ":" + lp.headLabel)) {
                note(lp.pos, "@dont(unroll): this loop is kept as a loop (the heuristic would have unrolled it)");
            }
            return Integer.MIN_VALUE;
        }
        boolean full = pl.full;
        int u = pl.u;
        long rem = pl.rem;
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
            boolean subst = onlyReadsVar(body, lp, lp.forced || cfg.nested);
            List<String> locals = subst ? bodyLocals(work, lp) : new ArrayList<>();
            for (long c = 0; c < n; c++) {
                List<List<BytecodeToken>> copy = c == 0 ? body : freshCopy(body);
                if (c > 0 && !locals.isEmpty()) copy = renameLocals(work, lp, copy, locals);
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
            for (int k = lp.headIdx + 1; k <= lp.headIdx + 5; k++) {
                List<BytecodeToken> src = work.get(k);
                List<BytecodeToken> row = new ArrayList<>();
                for (BytecodeToken t : src) {
                    if (t.text.equals(lp.rangeType)) row.add(retext(t, newType));
                    else if (k == lp.headIdx + 5 && t.text.equals(lp.endLabel)) row.add(retext(t, remLabel));
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
        if (lp.forced) {
            note(lp.pos, "@unroll: " + (full ? "unrolled fully" : "unrolled by " + u) + " (" + n + " iterations, " + (added >= 0 ? "+" : "") + added + " lines)");
        }
        return added;
    }

    /**
     * Run once after the optimizer has settled: every @unroll still sitting on a loop was not honoured, so say why
     * ("[warning] file:line - ..."). Only now, because a bound that is a variable early on can turn into a literal in a later round.
     */
    public void reportUnhonoured(List<List<BytecodeToken>> L) {
        for (int d = 0; d < L.size(); d++) {
            List<BytecodeToken> dl = L.get(d);
            if (!isForDeco(dl) || !dl.get(1).text.startsWith("@unroll")) continue;
            String pos = dl.size() == 3 ? stripQuotes(dl.get(2).text) : null;
            int first = d;
            while (first > 0 && isForDeco(L.get(first - 1))) first--;
            int fnStart = d;
            while (fnStart > 0 && !is(L.get(fnStart), 2, "FUNC_START")) fnStart--;
            int fnEnd = d;
            while (fnEnd < L.size() && !(L.get(fnEnd).size() >= 1 && L.get(fnEnd).get(0).text.equals("FUNC_END"))) fnEnd++;
            int s = first - 7;
            String reason;
            Loop lp = s >= fnStart ? match(L, s, fnEnd) : null;
            String range = s >= fnStart && is(L.get(s), 3, "ADDR") && L.get(s).get(1).text.startsWith("$for_range_") ? L.get(s).get(2).text : null;
            if (range == null || range.indexOf("range(") < 0) {
                reason = "this loop is not a counted `for` the unroller recognises";
            } else {
                String inner = range.substring(range.indexOf("range(") + 6, range.length() - 1);
                int comma = inner.indexOf(',');
                boolean lit = comma > 0 && allDigits(inner.substring(0, comma)) && allDigits(inner.substring(comma + 1));
                if (!lit) {
                    reason = "its bounds are not compile-time constants (range " + inner.replace(",", "..") + ")";
                } else if (lp == null) {
                    reason = Long.parseLong(inner.substring(comma + 1)) <= Long.parseLong(inner.substring(0, comma))
                            ? "the loop never runs" : "an earlier pass changed the loop's shape";
                } else if (lp.dont) {
                    reason = "the loop is also marked @dont(unroll)";
                } else if (!copyable(L, lp, fnStart, fnEnd)) {
                    reason = "it defines a label that is used from outside the loop body (a try block, for instance)";
                } else if (plan(lp, "") == null) {
                    reason = "it would add more than " + HARD_CAP + " lines";
                } else {
                    reason = "an inner loop that cannot be unrolled is in the way";
                }
            }
            System.err.println("[warning] " + (pos != null ? pos + " - " : "") + "@unroll not honoured: " + reason);
        }
    }

    /** True when every mention of the loop variable in the body is a plain "PUSH var vt" that is not directly followed by ADDR_OF. */
    private static boolean onlyReadsVar(List<List<BytecodeToken>> body, Loop lp, boolean nestedOk) {
        // Innermost loops only unless `nestedOk` (@unroll, or `loop-unroll-nested: on`): substituting a literal into a body that holds another loop
        // gives that loop literal bounds, and the nested full unrolling that follows multiplies the code (n-body: 2.5x the code). Range proofs on the literal are folded away by ConstantFoldingPass (`IN`).
        for (List<BytecodeToken> l : body) {
            if (!nestedOk && l.size() == 1 && (l.get(0).text.startsWith("@for_") || l.get(0).text.startsWith("@fu_"))) return false;
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

    // ---- per-copy locals of a fully unrolled body that holds an inner loop ----------------------------------------------------------------
    // Every copy of the body would declare the same locals (`let j0 = mut (i + 1)`, the hidden `$for_range_N` of an inner `for`): one slot assigned
    // once per copy, so VariableElision (once-assigned literal) refused them and the inner loops of the later copies kept variable bounds. Copies 1..n-1
    // get their own slots (`name__uK`, new ALLOC lines queued in `pendingAllocs`, inserted after the original ALLOC once the function is done).

    /** [function start, function end] around line idx of work */
    private static int[] functionOf(List<List<BytecodeToken>> work, int idx) {
        int s = idx;
        while (s > 0 && !is(work.get(s), 2, "FUNC_START")) s--;
        int e = idx;
        while (e < work.size() && !(work.get(e).size() >= 1 && work.get(e).get(0).text.equals("FUNC_END"))) e++;
        return new int[] {s, e};
    }

    /** whether token text `t` names variable `name`: the name itself, `name.field`, or a bound of a range type (`imut_range(0,imut_name)`) */
    private static boolean namesVar(String t, String name) {
        if (t.equals(name) || t.startsWith(name + ".")) return true;
        int open = t.indexOf("range(");
        if (open < 0 || !t.endsWith(")")) return false;
        String inner = t.substring(open + 6, t.length() - 1);
        int comma = inner.indexOf(',');
        if (comma < 0) return false;
        return boundIs(inner.substring(0, comma), name) || boundIs(inner.substring(comma + 1), name);
    }

    private static boolean boundIs(String b, String name) {
        String nm = b.startsWith("imut_") ? b.substring(5) : b.startsWith("mut_") ? b.substring(4) : b;
        return nm.equals(name);
    }

    private static String renameTok(String t, Map<String, String> map) {
        String r = map.get(t);
        if (r != null) return r;
        int dot = t.indexOf('.');
        if (dot > 0) {
            r = map.get(t.substring(0, dot));
            if (r != null && !t.contains("range(")) return r + t.substring(dot);
        }
        int open = t.indexOf("range(");
        if (open >= 0 && t.endsWith(")")) {
            String inner = t.substring(open + 6, t.length() - 1);
            int comma = inner.indexOf(',');
            if (comma > 0) {
                String lo = renameBound(inner.substring(0, comma), map), hi = renameBound(inner.substring(comma + 1), map);
                return t.substring(0, open + 6) + lo + "," + hi + ")";
            }
        }
        return t;
    }

    private static String renameBound(String b, Map<String, String> map) {
        String pre = b.startsWith("imut_") ? "imut_" : b.startsWith("mut_") ? "mut_" : "";
        String r = map.get(b.substring(pre.length()));
        return r == null ? b : pre + r;
    }

    /** Locals declared in the body: ALLOC'd in this function, mentioned only inside the body span, body holding an inner loop. */
    private List<String> bodyLocals(List<List<BytecodeToken>> work, Loop lp) {
        List<String> res = new ArrayList<>();
        boolean nested = false;
        for (int k = lp.bodyStart; k < lp.incStart && !nested; k++) {
            for (BytecodeToken t : work.get(k)) {
                if (t.text.startsWith("$for_range_")) {
                    nested = true;
                    break;
                }
            }
        }
        if (!nested) return res;
        int[] f = functionOf(work, lp.start);
        for (int a = f[0]; a <= f[1]; a++) {
            List<BytecodeToken> l = work.get(a);
            if (!is(l, 3, "ALLOC")) continue;
            String name = l.get(1).text;
            if (name.equals(lp.var) || name.equals(lp.rangeName) || name.equals("gt_routine_address") || name.equals("gt_error_message") || name.startsWith("gt_trace_")) continue;
            boolean inside = false, outside = false;
            for (int k = f[0]; k <= f[1] && !outside; k++) {
                if (k == a || is(work.get(k), 3, "ALLOC")) continue;   // other ALLOC lines only spell the name in a range type; they are renamed with their variable
                boolean in = k >= lp.bodyStart && k < lp.incStart;
                List<BytecodeToken> row = work.get(k);
                for (int t = 1; t < row.size(); t++) {
                    if (row.get(t).kind != BytecodeToken.Kind.CODE || !namesVar(row.get(t).text, name)) continue;
                    if (in) inside = true; else { outside = true; break; }
                }
            }
            if (inside && !outside) res.add(name);
        }
        return res;
    }

    /** `copy` with the given locals renamed to fresh names; queues the matching ALLOC lines. */
    private List<List<BytecodeToken>> renameLocals(List<List<BytecodeToken>> work, Loop lp, List<List<BytecodeToken>> copy, List<String> locals) {
        Map<String, String> map = new HashMap<>();
        int[] f = functionOf(work, lp.start);
        for (String nm : locals) {
            map.put(nm, nm + "__u" + (nextLabel++));
        }
        for (int a = f[0]; a <= f[1]; a++) {
            List<BytecodeToken> l = work.get(a);
            if (is(l, 3, "ALLOC") && map.containsKey(l.get(1).text)) {
                List<BytecodeToken> na = new ArrayList<>(l);
                na.set(1, retext(l.get(1), map.get(l.get(1).text)));
                na.set(2, retext(l.get(2), renameTok(l.get(2).text, map)));
                pendingAllocs.add(new Object[] {l, na});
            }
        }
        List<List<BytecodeToken>> out = new ArrayList<>(copy.size());
        for (List<BytecodeToken> l : copy) {
            List<BytecodeToken> row = null;
            for (int t = 1; t < l.size(); t++) {
                BytecodeToken tok = l.get(t);
                if (tok.kind != BytecodeToken.Kind.CODE) continue;
                String r = renameTok(tok.text, map);
                if (!r.equals(tok.text)) {
                    if (row == null) row = new ArrayList<>(l);
                    row.set(t, retext(tok, r));
                }
            }
            out.add(row == null ? l : row);
        }
        return out;
    }

    private final List<Object[]> pendingAllocs = new ArrayList<>();

    /** inserts the queued ALLOC lines after their originals (by identity); returns how many lines were added */
    private int flushAllocs(List<List<BytecodeToken>> work, int fnStart) {
        int added = 0;
        for (Object[] pa : pendingAllocs) {
            @SuppressWarnings("unchecked")
            List<BytecodeToken> orig = (List<BytecodeToken>) pa[0];
            @SuppressWarnings("unchecked")
            List<BytecodeToken> na = (List<BytecodeToken>) pa[1];
            for (int k = fnStart; k < work.size(); k++) {
                if (work.get(k) == orig) {
                    work.add(k + 1, na);
                    added++;
                    break;
                }
            }
        }
        pendingAllocs.clear();
        return added;
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

package caspien.postpasses.gasreport;
import caspien.config.CompilerException;
import caspien.config.DocComments;
import caspien.postpasses.Palette;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import caspien.config.DocComment;
import caspien.bytecode.Text;

/**
 * `--audit`, worst-case execution cost in ABSTRACT GAS. Works on the emitted high-order bytecode (before any optimiser pass), so the figure
 * does not depend on the hardware, the optimiser switches or the target: every HOB operation costs a fixed number of gas units (table in
 * {@link #cost}, documented in docs/COMPILER_REFERENCE.md). The result for a function is the cost of its most expensive path:
 * <ul>
 * <li>branches take the dearer side; a `try` makes every throwing call site a possible jump to its catch block (and a call a possible jump to
 *     its unwind pad), so the catch bodies count on the worst path;</li>
 * <li>a `for` loop whose range has literal bounds costs bound * (header + worst iteration) + the final header test, exactly; any other
 *     loop (`loop {}`, a range with a variable bound, which includes the range argument of a `@recursive` function) is not bounded (classed finite / unbound / none / indirect, see `tag`), and the
 *     figure shown is then a lower bound (one iteration) tagged with the reason;</li>
 * <li>a call costs {@link #CALL_COST} plus the callee's worst case; unbounded callees make the caller unbounded;</li>
 * <li>external calls, inline assembly, indirect calls, `memcopy` sizes, sleeping and thread operations are charged a fixed amount and listed as
 *     NOT MODELLED (their real cost is outside the compiler's knowledge).</li>
 * </ul>
 * The loop bounds are literals only: no value tracking, and a callee is costed once for all callers (its own variable bounds stay unbounded).
 */

public final class GasReport {
    final GasReportCore core = new GasReportCore(this);

    /** Public statics (the report entry points and the maps the front end fills) live in GasReportCore; delegated here for callers. */
    public static final Map<String, String> POS = GasReportCore.POS;
    public static final Map<String, Long> SPIN = GasReportCore.SPIN;

    public static Result build(String hob) {
        return GasReportCore.build(hob);
    }

    public static String viz(String hob, String title) {
        return GasReportCore.viz(hob, title);
    }

    public static Map<String, String> classify(String hob, List<String[]> sites) {
        return GasReportCore.classify(hob, sites);
    }

    public static void checkLabels(String hob, Map<String, DocComment> funcs, Map<String, DocComment> loops) {
        GasReportCore.checkLabels(hob, funcs, loops);
    }

    public List<V> evalSeg(Fn f, int from, int to, int at, int depth) {
        return (List<V>) evalRun(segFrame(f, from, to, at, depth));
    }

    private GasEvalFrame segFrame(Fn f, int from, int to, int at, int depth) {
        GasEvalFrame fr = new GasEvalFrame(GasEvalFrame.EVAL_SEG, f);
        fr.from = from;
        fr.to = to;
        fr.at = at;
        fr.depth = depth;
        fr.i = from;
        fr.st = new ArrayList<>();
        return fr;
    }

    /** Runs the evaluator frames (evalSeg, valueAt, stableValue call each other) on an explicit stack; each step may ask for a child frame. */
    private Object evalRun(GasEvalFrame root) {
        Deque<GasEvalFrame> stack = new ArrayDeque<>();
        stack.push(root);
        while (true) {
            GasEvalFrame fr = stack.peek();
            GasEvalFrame child;
            switch (fr.kind) {
                case GasEvalFrame.EVAL_SEG: child = stepEvalSeg(fr); break;
                case GasEvalFrame.VALUE_AT: child = stepValueAt(fr); break;
                default: child = stepStable(fr); break;
            }
            if (child != null) {
                stack.push(child);
                continue;
            }
            stack.pop();
            if (stack.isEmpty()) {
                return fr.result;
            }
            stack.peek().got = fr.result;
        }
    }

    private GasEvalFrame stepEvalSeg(GasEvalFrame fr) {
        Fn f = fr.f;
        List<V> st = fr.st;
        if (fr.phase == 1) {
            // the value of a variable asked for below is in `got`
            fr.phase = 0;
            V v = (V) fr.got;
            if (v == null) {
                return fr.finish(null);
            }
            if (fr.field == null) {
                st.add(v);
            } else if (v.range) {
                st.add(V.sc(fr.field.equals("start") ? v.a : v.b));
            } else {
                return fr.finish(null);
            }
            fr.i++;
        }
        for (; fr.i < fr.to; fr.i++) {
            String[] p = Text.split(core.ln[fr.i], ' ', 0);
            switch (p[0]) {
                case "PUSH": {
                    if (p.length < 2) {
                        return fr.finish(null);
                    }
                    String t = p[1];
                    if (Text.isDigits(t)) {
                        st.add(V.sc(new BigInteger(t)));
                        break;
                    }
                    String base = t, field = null;
                    if (t.endsWith(".start") || t.endsWith(".end")) {
                        int d = t.lastIndexOf('.');
                        base = t.substring(0, d);
                        field = t.substring(d + 1);
                    }
                    fr.field = field;
                    fr.phase = 1;
                    return valueFrame(f, base, fr.at, fr.depth + 1);
                }
                case "ADD": case "SUB": case "MUL": {
                    if (st.size() < 2 || GasReportCore.narrowType(p)) {
                        return fr.finish(null);
                    }
                    V y = st.remove(st.size() - 1), x = st.remove(st.size() - 1);
                    if (x.range || y.range) {
                        return fr.finish(null);
                    }
                    BigInteger r = p[0].equals("ADD") ? x.a.add(y.a) : p[0].equals("SUB") ? x.a.subtract(y.a) : x.a.multiply(y.a);
                    if (!GasReportCore.small(r)) {
                        return fr.finish(null);
                    }
                    st.add(V.sc(r));
                    break;
                }
                case "INC": case "DEC": {
                    if (st.isEmpty() || GasReportCore.narrowType(p) || st.get(st.size() - 1).range) {
                        return fr.finish(null);
                    }
                    BigInteger r = st.remove(st.size() - 1).a.add(BigInteger.valueOf(p[0].equals("INC") ? 1 : -1));
                    if (!GasReportCore.small(r)) {
                        return fr.finish(null);
                    }
                    st.add(V.sc(r));
                    break;
                }
                default:
                    return fr.finish(null);
            }
        }
        return fr.finish(st);
    }

    private GasEvalFrame valueFrame(Fn f, String var, int at, int depth) {
        GasEvalFrame fr = new GasEvalFrame(GasEvalFrame.VALUE_AT, f);
        fr.var = var;
        fr.at = at;
        fr.depth = depth;
        return fr;
    }

    /** The value of the (from, to) segment read as one variable assignment (scalar or range), or null. */
    private V assignedValue(String[] p, List<V> st) {
        if (st == null) {
            return null;
        }
        if (p.length >= 3 && p[2].contains("range")) {
            if (st.size() == 2 && !st.get(0).range && !st.get(1).range) {
                return new V(st.get(0).a, st.get(1).a, true);
            }
            return st.size() == 1 && st.get(0).range ? st.get(0) : null;
        }
        return st.size() == 1 && !st.get(0).range ? st.get(0) : null;
    }

    public boolean addressTaken(Fn f, String var) {
        for (int i = f.start; i < f.end; i++) {
            if (core.ln[i].startsWith("ADDR_OF ")) {
                for (String t : Text.split(core.ln[i], ' ', 0)) {
                    if (t.equals(var)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public V valueAt(Fn f, String var, int at, int depth) {
        return (V) evalRun(valueFrame(f, var, at, depth));
    }

    private GasEvalFrame stepValueAt(GasEvalFrame fr) {
        Fn f = fr.f;
        String var = fr.var;
        int at = fr.at;
        if (fr.phase == 0) {
            if (fr.depth > 60 || addressTaken(f, var)) {
                return fr.finish(null);
            }
            fr.phase = 1;
            GasEvalFrame c = new GasEvalFrame(GasEvalFrame.STABLE, f);
            c.var = var;
            c.at = at;
            c.depth = fr.depth;
            return c;
        }
        if (fr.phase == 2) {
            // the segment of the last write to `var` has been evaluated
            return fr.finish(assignedValue(fr.p, castSeg(fr.got)));
        }
        V stable = (V) fr.got;
        if (stable != null) {
            return fr.finish(stable);
        }
        for (int i = at - 1; i > f.start; i--) {
            String line = core.ln[i];
            if (GasReportCore.isLabel(line)) {
                return fr.finish(null);
            }
            String[] p = Text.split(line, ' ', 0);
            if (p[0].equals("ADDR") && p.length >= 2 && p[1].equals(var)) {
                int j = i + 1;
                while (j < f.end && !core.ln[j].startsWith("ASSIGN ")) {
                    if (GasReportCore.isLabel(core.ln[j])) {
                        return fr.finish(null);
                    }
                    j++;
                }
                if (j >= f.end) {
                    return fr.finish(null);
                }
                fr.p = p;
                fr.phase = 2;
                return segFrame(f, i + 1, j, i, fr.depth);
            }
            if (p[0].equals("ARG") && p.length >= 2 && p[1].equals(var)) {
                return fr.finish(f.args.get(var));
            }
            if (!p[0].equals("PUSH") && !p[0].equals("ADDR") && !p[0].equals("ASSIGN") && !p[0].equals("ALLOC")) {
                for (int k = 1; k < p.length; k++) {
                    if (p[k].equals(var)) {
                        return fr.finish(null);
                    }
                }
            }
        }
        return fr.finish(null);
    }

    @SuppressWarnings("unchecked")
    private static List<V> castSeg(Object o) {
        return (List<V>) o;
    }

    public V stableValue(Fn f, String var, int at, int depth) {
        GasEvalFrame fr = new GasEvalFrame(GasEvalFrame.STABLE, f);
        fr.var = var;
        fr.at = at;
        fr.depth = depth;
        return (V) evalRun(fr);
    }

    private GasEvalFrame stepStable(GasEvalFrame fr) {
        Fn f = fr.f;
        String var = fr.var;
        if (fr.phase == 1) {
            return fr.finish(assignedValue(Text.split(core.ln[fr.write], ' ', 0), castSeg(fr.got)));
        }
        int write = -1, writes = 0;
        boolean param = false;
        for (int i = f.start + 1; i < f.end; i++) {
            String line = core.ln[i];
            if (line.startsWith("ARG ") && Text.split(line, ' ', 0)[1].equals(var)) {
                param = true;
            }
            if (line.startsWith("ADDR ") && Text.split(line, ' ', 0)[1].equals(var)) {
                boolean selfCopy = param && i + 2 < f.end && core.ln[i + 1].startsWith("PUSH " + var + " ") && core.ln[i + 2].startsWith("ASSIGN ");
                if (!selfCopy) {
                    writes++;
                    write = i;
                }
            }
        }
        if (param && writes == 0) {
            return fr.finish(f.args.get(var));
        }
        if (!param && writes == 1 && write < fr.at && runsFirst(f, write)) {
            int j = write + 1;
            while (j < f.end && !core.ln[j].startsWith("ASSIGN ")) {
                j++;
            }
            fr.write = write;
            fr.phase = 1;
            return segFrame(f, write + 1, j, write, fr.depth);
        }
        return fr.finish(null);
    }

    /** True when line `w` is reached on every path from the function start and only once: nothing before it jumps (but the old landing-pad skip) or is a label (but the landing pads). */
    public boolean runsFirst(Fn f, int w) {
        for (int i = f.start + 1; i < w; i++) {
            String line = core.ln[i];
            if (GasReportCore.isLabel(line)) {
                if (!line.startsWith("gt_routine__") && !line.startsWith("end_of_gt_routine__")) {
                    return false;
                }
            } else if (GasReportCore.op(line).equals("JMP") && !GasReportCore.arg(line).startsWith("end_of_gt_routine__")) {
                return false;
            }
        }
        return true;
    }

    public BigInteger scalarAt(Fn f, int idx) {
        List<V> st = evalSeg(f, idx, idx + 1, idx, 0);
        return st != null && st.size() == 1 && !st.get(0).range ? st.get(0).a : null;
    }

    /** Iteration count of a `for` whose range is not literal in its type: the range the hidden `$for_range_N` variable was built from, when known here. */
    public BigInteger rangeCount(Fn f, Loop lp, String type) {
        String name = null;
        for (int i = lp.head + 1; i < lp.back && i < lp.head + 6; i++) {
            if (core.ln[i].startsWith("PUSH $for_range_")) {
                name = Text.split(core.ln[i], ' ', 0)[1];
                break;
            }
        }
        if (name == null) {
            return null;
        }
        V v = valueAt(f, name, lp.head, 0);
        return v != null && v.range ? v.b.subtract(v.a).max(BigInteger.ZERO) : null;
    }

    /** The parameter values call line `callLine` of `caller` passes to `callee` (only those known), or empty. */
    public Map<String, V> callArgs(Fn caller, int callLine, Map<String, V> callerArgs, Fn callee) {
        Map<String, V> out = new HashMap<>();
        int cs = callLine - 1, depth = 0;
        while (cs >= caller.start) {
            String o = GasReportCore.op(core.ln[cs]);
            if (o.equals("CC_END")) {
                depth++;
            } else if (o.equals("CC_START")) {
                if (depth == 0) {
                    break;
                }
                depth--;
            }
            cs--;
        }
        if (cs < caller.start) {
            return out;
        }
        List<V> slots = new ArrayList<>();
        int segStart = cs + 1, d = 0;
        for (int i = cs + 1; i < callLine; i++) {
            String o = GasReportCore.op(core.ln[i]);
            if (o.equals("CC_START")) {
                d++;
            } else if (o.equals("CC_END")) {
                d--;
            } else if (d == 0 && core.ln[i].startsWith("POP ARG")) {
                List<V> st = evalSeg(caller, segStart, i, i, 0);
                slots.add(st != null && st.size() == 1 && !st.get(0).range ? st.get(0) : null);
                segStart = i + 1;
            }
        }
        List<String[]> params = new ArrayList<>();
        for (int i = callee.start; i < callee.end; i++) {
            if (core.ln[i].startsWith("ARG ")) {
                params.add(Text.split(core.ln[i], ' ', 0));
            } else if (core.ln[i].startsWith("ALLOC ")) {
                break;
            }
        }
        int need = 0;
        for (String[] pr : params) {
            need += pr.length >= 3 && Text.isRangeType(pr[2]) ? 2 : 1;
        }
        if (need != slots.size()) {
            return out;
        }
        int k = 0;
        for (String[] pr : params) {
            boolean rng = pr.length >= 3 && Text.isRangeType(pr[2]);
            V x = slots.get(k++);
            if (rng) {
                V y = slots.get(k++);
                if (x != null && y != null) {
                    out.put(pr[1], new V(x.a, y.a, true));
                }
            } else if (x != null) {
                out.put(pr[1], x);
            }
        }
        return out;
    }

    /** Pairs each allocation with the label its null check jumps to on success (everything between is the whitelisted check and the failure branch). */
    public void mapOkLabels(Fn f) {
        f.okLabelAlloc.clear();
        for (int i = f.start + 1; i < f.end; i++) {
            if (!GasReportCore.isAlloc(GasReportCore.op(core.ln[i]))) {
                continue;
            }
            for (int k = i + 1; k < f.end; k++) {
                String ok = GasReportCore.op(core.ln[k]);
                boolean label = GasReportCore.isLabel(core.ln[k]);
                if (label && core.ln[k].contains("_ok_")) {
                    f.okLabelAlloc.put(k, i);
                    break;
                }
                if (!label && !GasReportCore.POST_ALLOC.contains(ok) && !ok.equals("GT_UNWIND")) {
                    break;
                }
            }
        }
    }

    /**
     * Which free certainly releases how many bytes (Fn.credit), by a must-analysis over the lines: an owned local or parameter is assigned from an allocation
     * of known size (`new`, `dyn`, `clone`, `resize`), from another owned slot (a move) or from a call that returns an owned block of known size; every
     * path that reaches a `GT_DESTRUCT` of that slot must agree, else nothing is credited. A free is credited only up to what is certain (a lower bound), so
     * the live-heap figure stays an upper bound: blocks that cross into a struct member, an unknown callee or a path-dependent slot are never credited.
     * Also fills Fn.paramFreed (owned parameters destructed on every exit) and Fn.retLo (bytes of the owned block returned).
     */
    public void analyzeCredits(Fn f) {
        f.credit.clear();
        f.edgeCredit.clear();
        f.edgeCall.clear();
        f.padEvals.clear();
        Map<String, String> slotType = new HashMap<>();
        List<String> params = new ArrayList<>();
        String retType = "";
        for (int i = f.start; i < f.end; i++) {
            String l = core.ln[i];
            if (l.startsWith("RETURNS ")) {
                retType = GasReportCore.firstType(l);
            }
            if (l.startsWith("ALLOC ") || l.startsWith("ARG ")) {
                String[] p = Text.split(l, ' ', 0);
                if (p.length >= 3 && p[2].startsWith("owns_")) {
                    slotType.put(p[1], p[2]);
                }
                if (l.startsWith("ARG ")) {
                    params.add(p[1]);
                }
            }
        }
        f.paramFreed = new boolean[params.size()];
        f.retLo = 0;
        f.retDeep = 0;
        if (core.structSize == null) {
            parseStructs();
        }
        for (int i = f.start; i < f.end; i++) {
            if (core.ln[i].startsWith("ALLOC ")) {
                String[] p = Text.split(core.ln[i], ' ', 0);
                String st = p.length >= 3 ? Text.stripQualifierPrefixes(p[2]) : "";
                if (p.length >= 3 && !p[2].startsWith("owns_") && core.ownsStructs.contains(st)) {
                    slotType.put(p[1], "inline_" + st);     // a struct held directly that owns memory: dropped like an owner at scope end
                }
            }
        }
        f.deepOk = deepOk(f, slotType);
        if (slotType.isEmpty()) {
            return;
        }
        // the target of every `ASSIGN`: the closest unmatched `ADDR` of the same type
        Map<Integer, Integer> lhs = new HashMap<>();
        java.util.ArrayDeque<Integer> stk = new java.util.ArrayDeque<>();
        for (int i = f.start + 1; i < f.end; i++) {
            String o = GasReportCore.op(core.ln[i]);
            if (o.equals("ADDR")) {
                stk.push(i);
            } else if (o.equals("ASSIGN") && !stk.isEmpty() && GasReportCore.addrType(core.ln[stk.peek()]).equals(GasReportCore.firstType(core.ln[i]))) {
                lhs.put(i, stk.pop());
            }
        }
        St[] in = flow(f, f.start + 1, new St(), slotType, lhs);
        f.credit.putAll(creditsFrom(f, in, slotType));
        // parameter frees and the returned block, from the settled in-states
        boolean[] freed = new boolean[params.size()];
        java.util.Arrays.fill(freed, true);
        boolean anyExit = false;
        long retMin = Long.MAX_VALUE, retDeepMin = Long.MAX_VALUE;
        for (int i = f.start + 1; i < f.end; i++) {
            St s = in[i];
            if (s == null) {
                continue;
            }
            String o = GasReportCore.op(core.ln[i]);
            if (o.equals("RET") || o.equals("GT_UNWIND")) {
                anyExit = true;
                for (int k = 0; k < params.size(); k++) {
                    freed[k] &= slotType.containsKey(params.get(k)) && s.dead.contains(params.get(k));
                }
                if (o.equals("RET") && retType.startsWith("owns_")) {
                    long v = i > 0 && core.ln[i - 1].startsWith("PUSH ") && slotType.containsKey(GasReportCore.arg(core.ln[i - 1])) ? s.lo.getOrDefault(GasReportCore.arg(core.ln[i - 1]), 0L) : 0L;
                    retMin = Math.min(retMin, v);
                    long dv = i > 0 && core.ln[i - 1].startsWith("PUSH ") && slotType.containsKey(GasReportCore.arg(core.ln[i - 1])) ? s.deep.getOrDefault(GasReportCore.arg(core.ln[i - 1]), 0L) : 0L;
                    retDeepMin = Math.min(retDeepMin, dv);
                }
            }
        }
        if (anyExit) {
            f.paramFreed = freed;
        }
        f.retLo = retMin == Long.MAX_VALUE ? 0 : retMin;
        f.retDeep = retDeepMin == Long.MAX_VALUE ? 0 : retDeepMin;
        // Catch blocks and landing pads are shared by every call that may unwind to them, so the join above knows little about what is live when one
        // is entered. Each entry edge gets the credits of the pad analysed from the state at that edge alone (cloned context).
        Map<String, Map<Integer, Long>> bySig = new HashMap<>();
        for (int i = f.start + 1; i < f.end; i++) {
            if (in[i] == null) {
                continue;
            }
            String o = GasReportCore.op(core.ln[i]);
            Integer t = null;
            St entry = null;
            if (o.equals("PUSH_LABEL") && !GasReportCore.arg(core.ln[i]).startsWith("@for") && !GasReportCore.arg(core.ln[i]).startsWith("@loop")) {
                t = f.labels.get(GasReportCore.arg(core.ln[i]));
                for (int k = i + 1; k < f.end && k < i + 200; k++) {
                    String ok = GasReportCore.op(core.ln[k]);
                    if (ok.equals("CALL")) {
                        entry = in[k];
                        f.edgeCall.put(i, k);
                        break;
                    }
                    if (ok.equals("PUSH_LABEL") || ok.equals("RET")) {
                        break;
                    }
                }
            } else if (o.equals("JMP") && GasReportCore.arg(core.ln[i]).startsWith("@catch_") && !(i > 0 && core.ln[i - 1].equals("CMP"))) {
                t = f.labels.get(GasReportCore.arg(core.ln[i]));
                entry = in[i];
            }
            if (t == null || entry == null) {
                continue;
            }
            Map<Integer, Long> cm = creditsFrom(f, flow(f, t, entry, slotType, lhs), slotType);
            String sig = cm.toString();
            Map<Integer, Long> shared = bySig.get(sig);
            if (shared == null) {
                shared = cm;
                bySig.put(sig, shared);
            }
            f.edgeCredit.put(i, shared);
        }
    }

    /** Fixpoint of the credit state over the lines reachable from `start` with the state `init` there. */
    public St[] flow(Fn f, int start, St init, Map<String, String> slotType, Map<Integer, Integer> lhs) {
        St[] in = new St[f.end + 1];
        java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
        in[start] = init.copy();
        work.add(start);
        while (!work.isEmpty()) {
            int i = work.poll();
            if (i >= f.end) {
                continue;
            }
            St out = transferCredit(f, i, in[i], slotType, lhs, in);
            for (int t : successors(f, i)) {
                if (t >= f.end || t < 0) {
                    continue;
                }
                if (in[t] == null) {
                    in[t] = out.copy();
                    work.add(t);
                } else {
                    St j = St.join(in[t], out);
                    if (!j.same(in[t])) {
                        in[t] = j;
                        work.add(t);
                    }
                }
            }
        }
        return in;
    }

    /** Bytes certainly released at each free line, from the settled in-states of a flow. */
    public Map<Integer, Long> creditsFrom(Fn f, St[] in, Map<String, String> slotType) {
        Map<Integer, Long> out = new java.util.TreeMap<>();
        for (int i = f.start + 1; i < f.end; i++) {
            St s = in[i];
            if (s == null) {
                continue;
            }
            String o = GasReportCore.op(core.ln[i]);
            long cr = 0;
            if (o.equals("GT_DESTRUCT") && Text.split(core.ln[i], ' ', 0).length == 2) {
                cr = s.lo.getOrDefault(GasReportCore.arg(core.ln[i]), 0L) + (f.deepOk ? s.deep.getOrDefault(GasReportCore.arg(core.ln[i]), 0L) : 0L);
            } else if ((o.equals("RESIZE") || o.equals("URESIZE")) && i >= 3) {
                int srcAt = o.equals("RESIZE") ? i - 3 : i - 2;
                boolean simple = core.ln[i - 1].startsWith("PUSH ") && (o.equals("URESIZE") || core.ln[i - 2].startsWith("PUSH ")) && core.ln[srcAt].startsWith("PUSH ");
                String y = simple ? GasReportCore.arg(core.ln[srcAt]) : null;
                if (y != null && slotType.containsKey(y)) {
                    cr = s.lo.getOrDefault(y, 0L);
                }
            } else if (o.equals("CALL")) {
                Fn cf = core.fns.get(GasReportCore.arg(core.ln[i]));
                if (cf != null && cf.paramFreed.length > 0) {
                    int depth = 0;
                    for (int k = i - 1; k > f.start; k--) {
                        String ok = GasReportCore.op(core.ln[k]);
                        if (ok.equals("CC_END")) {
                            depth++;
                        } else if (ok.equals("CC_START")) {
                            if (depth == 0) {
                                break;
                            }
                            depth--;
                        } else if (depth == 0 && ok.equals("POP") && core.ln[k].startsWith("POP ARG") && GasReportCore.addrType(core.ln[k]).startsWith("owns_") && core.ln[k - 1].startsWith("PUSH ")) {
                            int idx;
                            try {
                                idx = Integer.parseInt(GasReportCore.arg(core.ln[k]).substring(3));
                            } catch (NumberFormatException ex) {
                                continue;
                            }
                            String y = GasReportCore.arg(core.ln[k - 1]);
                            if (idx < cf.paramFreed.length && cf.paramFreed[idx] && slotType.containsKey(y) && in[k - 1] != null) {
                                cr += in[k - 1].lo.getOrDefault(y, 0L) + (f.deepOk && cf.deepOk ? in[k - 1].deep.getOrDefault(y, 0L) : 0L);
                            }
                        }
                    }
                }
            }
            if (cr > 0) {
                out.put(i, cr);
            }
        }
        return out;
    }

    /** Whether some path from the function's entry reaches a normal `RET` (false = every path ends in a throw / unwind, or never ends). */
    public boolean canReturn(Fn f) {
        Set<Integer> seen = new HashSet<>();
        Deque<Integer> work = new ArrayDeque<>();
        work.add(f.start + 1);
        while (!work.isEmpty()) {
            int i = work.poll();
            if (i <= f.start || i >= f.end || !seen.add(i)) {
                continue;
            }
            if (GasReportCore.op(core.ln[i]).equals("RET")) {
                return true;
            }
            work.addAll(successors(f, i));
        }
        return false;
    }

    public List<Integer> successors(Fn f, int i) {
        List<Integer> r = new ArrayList<>(2);
        String line = core.ln[i], o = GasReportCore.op(line);
        if (o.equals("RET") || o.equals("EXIT") || o.equals("EXIT_THREAD") || o.equals("GT_UNWIND")) {
            return r;   // (a THROW falls into the unwinding code behind it: its destructs, then GT_UNWIND)
        }
        if (o.equals("JMP")) {
            Integer t = f.labels.get(GasReportCore.arg(line));
            if (t != null) {
                r.add(t);
            }
            if (i > 0 && core.ln[i - 1].equals("CMP") || t == null) {
                r.add(i + 1);
            }
            return r;
        }
        r.add(i + 1);
        if (o.equals("CALL")) {
            for (int k = i - 1; k > f.start; k--) {
                if (GasReportCore.op(core.ln[k]).equals("PUSH_LABEL")) {
                    Integer t = f.labels.get(GasReportCore.arg(core.ln[k]));
                    if (t != null && !GasReportCore.arg(core.ln[k]).startsWith("@for") && !GasReportCore.arg(core.ln[k]).startsWith("@loop")) {
                        r.add(t);
                    }
                    break;
                }
            }
        }
        return r;
    }

    public St transferCredit(Fn f, int i, St s, Map<String, String> slotType, Map<Integer, Integer> lhs, St[] in) {
        String line = core.ln[i], o = GasReportCore.op(line);
        if (o.equals("GT_DESTRUCT") && Text.split(line, ' ', 0).length == 2 && slotType.containsKey(GasReportCore.arg(line))) {
            St r = s.copy();
            r.lo.remove(GasReportCore.arg(line));
            r.deep.remove(GasReportCore.arg(line));
            r.dead.add(GasReportCore.arg(line));
            return r;
        }
        if (!o.equals("ASSIGN") || !lhs.containsKey(i)) {
            return s;
        }
        int a = lhs.get(i);
        String x = GasReportCore.arg(core.ln[a]);
        if (!slotType.containsKey(x)) {
            return s;
        }
        boolean inline = slotType.get(x).startsWith("inline_");
        if (!inline && !GasReportCore.firstType(line).startsWith("owns_")) {
            return s;
        }
        St r = s.copy();
        long def = 0, deep = 0;
        int w = i - a - 1;
        if (in[a] != null) {
            // blocks moved into the members of the value being built: owned slots pushed between the ADDR and here that no longer hold their block (the null-out ran)
            for (int k = a + 1; k < i; k++) {
                if (core.ln[k].startsWith("PUSH ") && slotType.containsKey(GasReportCore.arg(core.ln[k])) && !s.lo.containsKey(GasReportCore.arg(core.ln[k])) && !s.deep.containsKey(GasReportCore.arg(core.ln[k]))) {
                    String c = GasReportCore.arg(core.ln[k]);
                    if (in[a].lo.containsKey(c) || in[a].deep.containsKey(c)) {
                        deep += in[a].lo.getOrDefault(c, 0L) + in[a].deep.getOrDefault(c, 0L);
                    }
                }
            }
        }
        if (w == 1 && core.ln[a + 1].startsWith("PUSH null")) {
            def = 0;
        } else if (w == 1 && core.ln[a + 1].startsWith("PUSH ") && slotType.containsKey(GasReportCore.arg(core.ln[a + 1]))) {
            String y = GasReportCore.arg(core.ln[a + 1]);
            def = r.lo.getOrDefault(y, 0L);
            deep = r.deep.getOrDefault(y, 0L);
            r.lo.remove(y);                      // a move: the source no longer holds the block
            r.deep.remove(y);
        } else if (i - 3 > a && GasReportCore.op(core.ln[i - 1]).equals("PUSH_RET") && GasReportCore.op(core.ln[i - 2]).equals("CC_END") && GasReportCore.op(core.ln[i - 3]).equals("CALL")) {
            Fn cf = core.fns.get(GasReportCore.arg(core.ln[i - 3]));
            def = cf == null ? 0 : cf.retLo;
            deep = cf == null || !cf.deepOk ? 0 : cf.retDeep;
        } else {
            int j = -1;
            for (int k = i - 1; k > a; k--) {
                if (GasReportCore.isAlloc(GasReportCore.op(core.ln[k]))) {
                    j = k;
                    break;
                }
            }
            boolean tailOk = j > 0;
            for (int k = j + 1; tailOk && k < i; k++) {
                String ok = GasReportCore.op(core.ln[k]);
                if (!(GasReportCore.isLabel(core.ln[k])) && !GasReportCore.POST_ALLOC.contains(ok)) {
                    tailOk = false;
                }
            }
            if (tailOk) {
                def = allocBytes(j, f).longValue();
            }
        }
        if (inline) {
            def = 0;
        }
        if (def > 0 || (inline && deep > 0)) {
            r.lo.put(x, def);
            r.deep.put(x, deep);
        } else {
            r.lo.remove(x);
            r.deep.remove(x);
        }
        if (inline && r.deep.containsKey(x) && !r.lo.containsKey(x)) {
            r.lo.put(x, 0L);
        }
        return r;
    }

    /**
     * True when nothing in f can make a destruct free less (or more) than the blocks its owner was built with: no member that owns memory is read out
     * (a move), written (a replacement) or reached through a reference passed to another function, and no tracked slot has its address taken. The
     * `name.member = null` statements right after a `GT_DESTRUCT name` are the drop glue clearing what was just freed and do not count.
     */
    public boolean deepOk(Fn f, Map<String, String> slotType) {
        Set<Integer> idiom = new HashSet<>();
        for (int i = f.start + 1; i < f.end; i++) {
            if (GasReportCore.op(core.ln[i]).equals("GT_DESTRUCT") && Text.split(core.ln[i], ' ', 0).length == 2) {
                String name = GasReportCore.arg(core.ln[i]);
                int k = i + 1;
                while (k < f.end && core.ln[k].startsWith("ADDR " + name + " ")) {
                    int j = k;
                    while (j < f.end && !core.ln[j].startsWith("ASSIGN ")) {
                        j++;
                    }
                    for (int m = k; m <= j && m < f.end; m++) {
                        idiom.add(m);
                    }
                    k = j + 1;
                }
            }
        }
        for (int i = f.start + 1; i < f.end; i++) {
            if (idiom.contains(i)) {
                continue;
            }
            String[] p = Text.split(core.ln[i], ' ', 0);
            if (p.length < 2) {
                continue;
            }
            switch (p[0]) {
                case "PUSH_FIELDNAME":
                    if (core.ownsMemberNames.contains(p[1])) {
                        return false;
                    }
                    break;
                case "PUSH": case "ADDR": {
                    int d = p[1].lastIndexOf('.');
                    if (d > 0 && core.ownsMemberNames.contains(p[1].substring(d + 1))) {
                        return false;
                    }
                    if (p[0].equals("PUSH") && slotType.containsKey(p[1]) && i + 1 < f.end && core.ln[i + 1].startsWith("POP ARG") && !GasReportCore.addrType(core.ln[i + 1]).startsWith("owns_")) {
                        return false;   // handed to a function by reference: it may move a member out or replace it
                    }
                    break;
                }
                case "ADDR_OF":
                    for (String t : p) {
                        if (slotType.containsKey(t)) {
                            return false;
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return true;
    }

    public void parseStructs() {
        core.structSize = new HashMap<>();
        String cur = null;
        long total = 0;
        for (String l : core.ln) {
            if (l.startsWith("STRUCT_START ")) {
                cur = l.substring(13).trim();
                total = 0;
                core.structMembers.put(cur, new ArrayList<>());
            } else if (cur != null && l.startsWith("STRUCT_MEMBER ")) {
                String[] p = Text.split(l, ' ', 0);
                core.structMembers.get(cur).add(p[p.length - 1]);
                if (p[p.length - 1].contains("owns_")) {
                    core.ownsMemberNames.add(p[1]);
                    core.ownsStructs.add(cur);
                }
                total += (sizeOfKnown(p[p.length - 1]) + 7) / 8 * 8;
            } else if (cur != null && l.startsWith("STRUCT_PADDING ")) {
                total += Long.parseLong(l.substring(15).trim());
            } else if (l.equals("STRUCT_END") && cur != null) {
                core.structSize.put(cur, Math.max(8, (total + 7) / 8 * 8));
                cur = null;
            }
        }
    }

    public long sizeOf(String t) {
        if (core.structSize == null) {
            parseStructs();
        }
        return sizeOfKnown(t);
    }

    /** sizeOf once the struct table exists; array dimensions are folded in a loop (innermost first). */
    private long sizeOfKnown(String t) {
        Deque<Long> dims = new ArrayDeque<>();
        String x = t;
        long size;
        while (true) {
            while (true) {
                if (x.startsWith("mut_")) {
                    x = x.substring(4);
                } else if (x.startsWith("imut_")) {
                    x = x.substring(5);
                } else if (x.startsWith("indeterminate_")) {
                    x = x.substring(14);
                } else {
                    break;
                }
            }
            if (x.startsWith("owns_") || x.startsWith("ref_") || x.startsWith("raw_") || x.startsWith("auto_") || x.startsWith("static_") || x.equals("code_addr")) {
                size = 8;
                break;
            }
            if (x.startsWith("range")) {
                size = 16;
                break;
            }
            if (x.startsWith("dynarray(") || x.startsWith("owns_dynarray")) {
                size = 8;
                break;
            }
            String[] arr = Text.splitArrayType(x, 2);
            if (arr != null) {
                dims.push(Long.parseLong(arr[1]));
                x = arr[0];
                continue;
            }
            size = leafSize(x);
            break;
        }
        while (!dims.isEmpty()) {
            size = dims.pop() * ((size + 7) / 8 * 8);
        }
        return size;
    }

    private long leafSize(String x) {
        if (Text.isSizedIntName(x)) {
            return Math.max(1, Integer.parseInt(x.substring(1)) / 8);
        }
        switch (x) {
            case "bool": case "char":
                return 1;
            case "f32":
                return 4;
            case "f64":
                return 8;
            case "void":
                return 0;
            default:
                break;
        }
        if (x.startsWith("atomic_")) {
            return 8;
        }
        Long ss = core.structSize.get(x);
        return ss != null ? ss : 8;
    }

    /** A literal integer pushed by the line at idx (`PUSH 12 type`), or null. */
    public BigInteger literalAt(int idx) {
        if (idx < 0 || !core.ln[idx].startsWith("PUSH ")) {
            return null;
        }
        String[] p = Text.split(core.ln[idx], ' ', 0);
        return p.length >= 2 && Text.isDigits(p[1]) ? new BigInteger(p[1]) : null;
    }

    public BigInteger deepBytes(String t0, int depth0) {
        Deque<Object[]> work = new ArrayDeque<>();   // {type, depth}
        work.push(new Object[]{t0, depth0});
        BigInteger total = BigInteger.ZERO;
        while (!work.isEmpty()) {
            Object[] w = work.pop();
            String t = (String) w[0];
            int depth = (Integer) w[1];
            if (depth > 8 || GasReportCore.dynElem(t) != null) {
                return null;
            }
            total = total.add(BigInteger.valueOf((sizeOf(t) + 7) / 8 * 8));
            List<String> ms = core.structMembers.get(t);
            if (ms != null) {
                for (String m : ms) {
                    if (m.contains("owns_")) {
                        String pt = GasReportCore.pointee(m);
                        if (pt == null) {
                            continue;
                        }
                        if (m.contains("dynarray")) {
                            return null;
                        }
                        work.push(new Object[]{pt, depth + 1});
                    }
                }
            }
        }
        return total;
    }

    public void verifyLabels(Map<String, DocComment> funcs, Map<String, DocComment> loops) {
        for (Fn f : core.fns.values()) {
            core.solve(f, new ArrayList<>());
        }
        for (Map.Entry<String, DocComment> e : funcs.entrySet()) {
            String word = funcWord(e.getKey());
            DocComment d = e.getValue();
            if (word != null && !word.equals(d.text)) {
                Fn f = core.fns.get(e.getKey());
                throw new CompilerException("termination", d.file, d.targetLine, "function `" + GasReportCore.shown(f.name) + "` is labelled `" + d.text
                        + "` but its termination class is `" + word + "`" + (GasReportCore.worstOf(f.ub[GasReportCore.GAS]) == 0 ? "" : " (" + GasReportCore.tag(f.ub[GasReportCore.GAS]) + ")"));
            }
        }
        for (Map.Entry<String, DocComment> e : loops.entrySet()) {
            int bar = e.getKey().indexOf('|');
            String word = loopWord(e.getKey().substring(0, bar), e.getKey().substring(bar + 1));
            DocComment d = e.getValue();
            if (word != null && !word.equals(d.text)) {
                throw new CompilerException("termination", d.file, d.targetLine, "`" + d.kind + "` in `" + GasReportCore.shown(e.getKey().substring(0, bar)) + "` is labelled `" + d.text
                        + "` but its termination class is `" + word + "`");
            }
        }
    }

    /** The class word of a function (solved already), or null when it is not in the program. */
    public String funcWord(String name) {
        Fn f = core.fns.get(name);
        return f == null ? null : GasReportCore.wordOf(GasReportCore.worstOf(f.ub[GasReportCore.GAS]));
    }

    /** The class word of one loop (see loopClass), or null when the loop is not found. */
    public String loopWord(String fnName, String label) {
        Fn f = core.fns.get(fnName);
        if (f == null || !f.labels.containsKey(label)) {
            return null;     // optimised away before the report could see it, or not emitted
        }
        Set<String> savedUb = new LinkedHashSet<>(f.ub[GasReportCore.GAS]);
        Eval ev = new Eval(core, f, GasReportCore.GAS);
        Loop lp = ev.loops.get(f.labels.get(label));
        if (lp == null) {
            return null;
        }
        int cls = loopClass(ev, f, lp);
        f.ub[GasReportCore.GAS].clear();
        f.ub[GasReportCore.GAS].addAll(savedUb);
        return GasReportCore.wordOf(cls);
    }

    /** The class of one loop alone: its own bound/exit, every call and indirect call inside it, and the loops nested in it. */
    public int loopClass(Eval ev, Fn f, Loop lp) {
        ev.solveLoop(lp);
        int cls = lp.ownClass;
        for (Loop in : ev.loops.values()) {
            if (in != lp && in.head > lp.head && in.end < lp.end) {
                ev.solveLoop(in);
                cls = Math.max(cls, in.ownClass);
            }
        }
        for (int i = lp.head + 1; i < lp.end; i++) {
            String o = GasReportCore.op(core.ln[i]);
            if (o.equals("INVOKE")) {
                cls = Math.max(cls, GasReportCore.UNK);
            } else if (o.equals("CALL")) {
                Fn cf = f.calleeAt.get(i);
                if (cf == null) {
                    cf = core.fns.get(GasReportCore.arg(core.ln[i]));
                }
                if (cf == null) {
                    continue;
                }
                int w = GasReportCore.worstOf(cf.ub[GasReportCore.GAS]);
                cls = Math.max(cls, w == GasReportCore.NONT ? GasReportCore.DIV : w);
            }
        }
        return cls;
    }

    public BigInteger allocBytes(int idx, Fn f) {
        String line = core.ln[idx];
        String[] p = Text.split(line, ' ', 0);
        String o = p[0];
        if (core.structSize == null) {
            parseStructs();
        }
        switch (o) {
            case "NEW": {
                return BigInteger.valueOf((sizeOf(p[1]) + 7) / 8 * 8);
            }
            case "NEW_DYN": {
                String e = GasReportCore.dynElem(p[1]);
                long es = e == null ? 8 : (sizeOf(e) + 7) / 8 * 8;
                return BigInteger.valueOf(GasReportCore.DYN_HEADER).add(BigInteger.valueOf(es).multiply(new BigInteger(p[p.length - 1])));
            }
            case "NEW_UDYN": {
                String e = GasReportCore.dynElem(p[1]);
                long es = e == null ? 8 : (sizeOf(e) + 7) / 8 * 8;
                return BigInteger.valueOf(es).multiply(new BigInteger(p[p.length - 1])).max(BigInteger.ONE);
            }
            case "NEW_FROM_STRING": case "NEW_FROM_USTRING": {
                // the text literal pushed just before: STRING <id> "text"
                String id = idx > 0 && core.ln[idx - 1].startsWith("PUSH string_id") ? Text.split(core.ln[idx - 1], ' ', 0)[1] : null;
                Integer len = id == null ? null : stringLength(id);
                if (len == null) {
                    GasReportCore.unb(f, GasReportCore.R(GasReportCore.FIN, "text length of a `dyn(...)` is not a literal"));
                    return BigInteger.valueOf(GasReportCore.DYN_HEADER);
                }
                return BigInteger.valueOf(o.equals("NEW_FROM_STRING") && !p[1].contains("unsafe_dynarray") ? GasReportCore.DYN_HEADER + len : Math.max(1, len + 1));
            }
            case "RESIZE": {
                String e = GasReportCore.dynElem(p[1]);
                long es = e == null ? 8 : (sizeOf(e) + 7) / 8 * 8;
                // source, count, fill: the count is the push right after the source. A scalar fill is one push, but a struct fill (`Holder{n= null}`) is several
                // (class id, members), so the count is found from the source push (the push of the dynarray itself), not counted back from the instruction.
                int cntAt = idx - 2;
                for (int k = idx - 1; k > f.start && k >= idx - 12; k--) {
                    if (core.ln[k].startsWith("PUSH ") && core.ln[k].endsWith(" " + p[1]) && core.ln[k + 1].startsWith("PUSH ")) {
                        cntAt = k + 1;
                        break;
                    }
                }
                BigInteger n = scalarAt(f, cntAt);
                if (n == null || !core.ln[idx - 1].startsWith("PUSH ")) {
                    GasReportCore.unb(f, GasReportCore.R(GasReportCore.FIN, "`resize` count is not a literal"));
                    return BigInteger.valueOf(GasReportCore.DYN_HEADER);
                }
                return BigInteger.valueOf(GasReportCore.DYN_HEADER).add(BigInteger.valueOf(es).multiply(n));
            }
            case "URESIZE": {
                String e = GasReportCore.dynElem(p[1]);
                long es = e == null ? 8 : (sizeOf(e) + 7) / 8 * 8;
                BigInteger n = scalarAt(f, idx - 1);
                if (n == null) {
                    GasReportCore.unb(f, GasReportCore.R(GasReportCore.FIN, "unsafe `resize` count is not a literal"));
                    return BigInteger.ONE;
                }
                return BigInteger.valueOf(es).multiply(n).max(BigInteger.ONE);
            }
            case "CLONE": {
                String pt = GasReportCore.pointee(p[1]);
                BigInteger d = pt == null || p[1].contains("dynarray") ? null : deepBytes(pt, 0);
                if (d == null) {
                    GasReportCore.unb(f, GasReportCore.R(GasReportCore.FIN, "`clone` of a block whose size is only known at run time"));
                    return BigInteger.valueOf(GasReportCore.DYN_HEADER);
                }
                return d;
            }
            default:
                GasReportCore.unb(f, GasReportCore.R(GasReportCore.FIN, "`" + o.toLowerCase() + "` size is only known at run time"));
                return BigInteger.valueOf(GasReportCore.DYN_HEADER);
        }
    }

    /** Byte length of the string literal `id` (escapes \n \t \r \0 \\ \" counted as one byte), or null when unknown. */
    public Integer stringLength(String id) {
        for (String l : core.ln) {
            if (l.startsWith("STRING " + id + " ")) {
                String q = l.substring(("STRING " + id + " ").length());
                if (q.length() < 2 || q.charAt(0) != '"' || q.charAt(q.length() - 1) != '"') {
                    return null;
                }
                q = q.substring(1, q.length() - 1);
                int n = 0;
                for (int i = 0; i < q.length(); i++) {
                    char c = q.charAt(i);
                    if (c == '\\' && i + 1 < q.length()) {
                        char d = q.charAt(i + 1);
                        if ("ntr0\\\"'".indexOf(d) >= 0) {
                            i++;
                        } else {
                            return null;
                        }
                    }
                    n += String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                }
                return n;
            }
        }
        return null;
    }

    /**
     * Estimated frame of a function: 16 (return address, frame pointer) + every ALLOC and ARG slot rounded up to 8 + an allowance for what the
     * later stages add (callee-saved register saves, temporaries, hidden slots): 64 + 8 bytes per 24 lines of the function body. The allowance
     * was calibrated so the estimate is never below the real frame of the compiled function (tests/stack_check.sh).
     */
    public long frameBytes(Fn f) {
        long total = 16;
        for (int i = f.start; i < f.end; i++) {
            String l = core.ln[i];
            if (l.startsWith("ALLOC ") || l.startsWith("ARG ") || l.startsWith("ALLOC_STATIC ")) {
                String[] p = Text.split(l, ' ', 0);
                total += (sizeOf(p[p.length - 1]) + 7) / 8 * 8;
            }
        }
        long lines = f.end - f.start + 1;
        return total + 64 + 8 * ((lines + 23) / 24);
    }

    public long stackDepth(String n, Map<String, Long> frame, Map<String, Long> depth, Map<String, String> via, Map<String, Set<String>> why, List<String> path) {
        if (depth.containsKey(n)) {
            return depth.get(n);
        }
        if (path.contains(n)) {
            return 0;
        }
        Deque<GasStackWalk> stack = new ArrayDeque<>();
        path.add(n);
        stack.push(new GasStackWalk(n, core.fns.get(n)));
        long ret = 0;
        while (!stack.isEmpty()) {
            GasStackWalk w = stack.peek();
            if (w.pending != null) {
                String c = w.pending;
                w.pending = null;
                absorbCallee(w, c, ret, why);
            }
            boolean descended = false;
            while (w.next < w.calls.size()) {
                String c = w.calls.get(w.next++);
                Fn cf = core.fns.get(c);
                if (cf == null) {
                    continue;
                }
                if (path.contains(c)) {
                    throw new IllegalStateException("audit: call cycle through " + c + " in a program that type-checked (compiler bug)");
                }
                frame.putIfAbsent(c, frameBytes(cf));
                if (depth.containsKey(c)) {
                    absorbCallee(w, c, depth.get(c), why);
                    continue;
                }
                w.pending = c;
                path.add(c);
                stack.push(new GasStackWalk(c, cf));
                descended = true;
                break;
            }
            if (descended) {
                continue;
            }
            // all callees done: close this function
            Fn f = core.fns.get(w.name);
            for (int i = f.start; i < f.end; i++) {
                if (GasReportCore.op(core.ln[i]).equals("INVOKE")) {
                    w.why.add(GasReportCore.R(GasReportCore.UNK, "indirect call (INVOKE), the callee is not known"));
                }
            }
            path.remove(path.size() - 1);
            long d = frame.get(w.name) + w.best;
            depth.put(w.name, d);
            if (w.bestVia != null) {
                via.put(w.name, w.bestVia);
            }
            why.put(w.name, w.why);
            ret = d;
            stack.pop();
        }
        return ret;
    }

    private void absorbCallee(GasStackWalk w, String c, long d, Map<String, Set<String>> why) {
        if (!why.get(c).isEmpty()) {
            w.why.add(GasReportCore.callReason(c, why.get(c), false));
        }
        if (d > w.best || w.bestVia == null) {
            w.best = d;
            w.bestVia = c;
        }
    }

    /** The termination class word of a function for the page (the word the audit prints after its gas figure). */
    public String termWord(String name) {
        String w = funcWord(name);
        return w == null ? "bound" : w;
    }

    public String vizPage(String title) {
        Fn entry = core.entryFn();
        if (entry == null) {
            return null;
        }
        for (Fn f : core.fns.values()) {
            core.solve(f, new ArrayList<>());
        }
        parseStructs();
        Map<String, Long> frame = new HashMap<>(), depth = new HashMap<>();
        Map<String, String> via = new HashMap<>();
        Map<String, Set<String>> why = new HashMap<>();
        frame.put(entry.name, frameBytes(entry));
        stackDepth(entry.name, frame, depth, via, why, new ArrayList<>());
        StringBuilder nodes = new StringBuilder();
        nodes.append("{\"name\":").append(GasReportCore.jsonStr(entry.name)).append(",\"stack\":").append(depth.get(entry.name)).append(",\"term\":")
                .append(GasReportCore.jsonStr(termWord(entry.name))).append("}");
        StringBuilder callees = new StringBuilder();
        for (String c : entry.calls) {
            Fn cf = core.fns.get(c);
            if (cf == null || c.equals(entry.name)) {
                continue;
            }
            if (callees.length() > 0) {
                callees.append(",");
            }
            callees.append("{\"name\":").append(GasReportCore.jsonStr(c)).append(",\"stack\":").append(depth.get(c)).append(",\"term\":")
                    .append(GasReportCore.jsonStr(termWord(c))).append("}");
        }
        return GasReportCore.VIZ_TEMPLATE.replace("@@TITLE@@", title.replace("<", "&lt;")).replace("@@MAIN@@", nodes).replace("@@CALLEES@@", callees).replace("@@TERM@@", Palette.terminationJson());
    }

    public GasReport(String hob) {
        List<String> l = new ArrayList<>();
        for (String s : Text.split(hob, '\n', 0)) {
            s = s.trim();
            if (!s.isEmpty()) {
                l.add(s);
            }
        }
        core.ln = l.toArray(new String[0]);
        Fn cur = null;
        for (int i = 0; i < core.ln.length; i++) {
            if (core.ln[i].startsWith("FUNC_START ")) {
                cur = new Fn();
                cur.name = core.ln[i].substring(11).trim();
                cur.start = i;
                core.fns.put(cur.name, cur);
            } else if (core.ln[i].equals("FUNC_END") && cur != null) {
                cur.end = i;
                cur = null;
            } else if (cur != null && GasReportCore.isLabel(core.ln[i])) {
                cur.labels.put(core.ln[i].substring(0, core.ln[i].length() - 1), i);
            } else if (cur != null && core.ln[i].startsWith("FUNC_DECORATE ")) {
                cur.deco.add(GasReportCore.arg(core.ln[i]));
            }
        }
    }
}

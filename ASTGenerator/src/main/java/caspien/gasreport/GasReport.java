package caspien.gasreport;
import caspien.*;

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

    public static GasReportCore.Result build(String hob) {
        return GasReportCore.build(hob);
    }

    public static String viz(String hob, String title) {
        return GasReportCore.viz(hob, title);
    }

    public static Map<String, String> classify(String hob, List<String[]> sites) {
        return GasReportCore.classify(hob, sites);
    }

    public static void checkLabels(String hob, Map<String, DocComments.Doc> funcs, Map<String, DocComments.Doc> loops) {
        GasReportCore.checkLabels(hob, funcs, loops);
    }

    /** Evaluates the reverse-Polish lines from..to-1 (PUSH of a literal / variable / range field, ADD SUB MUL INC DEC) as seen at line `at`; null when anything else shows up. */
    public List<GasReportCore.V> evalSeg(GasReportCore.Fn f, int from, int to, int at, int depth) {
        List<GasReportCore.V> st = new ArrayList<>();
        for (int i = from; i < to; i++) {
            String[] p = core.ln[i].split(" ");
            switch (p[0]) {
                case "PUSH": {
                    if (p.length < 2) {
                        return null;
                    }
                    String t = p[1];
                    if (t.matches("\\d+")) {
                        st.add(GasReportCore.V.sc(new BigInteger(t)));
                        break;
                    }
                    String base = t, field = null;
                    if (t.endsWith(".start") || t.endsWith(".end")) {
                        int d = t.lastIndexOf('.');
                        base = t.substring(0, d);
                        field = t.substring(d + 1);
                    }
                    GasReportCore.V v = valueAt(f, base, at, depth + 1);
                    if (v == null) {
                        return null;
                    }
                    if (field == null) {
                        st.add(v);
                    } else if (v.range) {
                        st.add(GasReportCore.V.sc(field.equals("start") ? v.a : v.b));
                    } else {
                        return null;
                    }
                    break;
                }
                case "ADD": case "SUB": case "MUL": {
                    if (st.size() < 2 || GasReportCore.narrowType(p)) {
                        return null;
                    }
                    GasReportCore.V y = st.remove(st.size() - 1), x = st.remove(st.size() - 1);
                    if (x.range || y.range) {
                        return null;
                    }
                    BigInteger r = p[0].equals("ADD") ? x.a.add(y.a) : p[0].equals("SUB") ? x.a.subtract(y.a) : x.a.multiply(y.a);
                    if (!GasReportCore.small(r)) {
                        return null;
                    }
                    st.add(GasReportCore.V.sc(r));
                    break;
                }
                case "INC": case "DEC": {
                    if (st.isEmpty() || GasReportCore.narrowType(p) || st.get(st.size() - 1).range) {
                        return null;
                    }
                    BigInteger r = st.remove(st.size() - 1).a.add(BigInteger.valueOf(p[0].equals("INC") ? 1 : -1));
                    if (!GasReportCore.small(r)) {
                        return null;
                    }
                    st.add(GasReportCore.V.sc(r));
                    break;
                }
                default:
                    return null;
            }
        }
        return st;
    }

    public boolean addressTaken(GasReportCore.Fn f, String var) {
        for (int i = f.start; i < f.end; i++) {
            if (core.ln[i].startsWith("ADDR_OF ")) {
                for (String t : core.ln[i].split(" ")) {
                    if (t.equals(var)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The value variable `var` certainly has when control reaches line `at`: found by walking back through straight-line code (no label in between,
     * so every path to `at` runs through what is read) to its last write, or to the parameter declaration (then the value the caller passes).
     * null = not known. A variable whose address is taken anywhere is never known.
     */
    public GasReportCore.V valueAt(GasReportCore.Fn f, String var, int at, int depth) {
        if (depth > 60 || addressTaken(f, var)) {
            return null;
        }
        GasReportCore.V stable = stableValue(f, var, at, depth);
        if (stable != null) {
            return stable;
        }
        for (int i = at - 1; i > f.start; i--) {
            String line = core.ln[i];
            if (GasReportCore.isLabel(line)) {
                return null;
            }
            String[] p = line.split(" ");
            if (p[0].equals("ADDR") && p.length >= 2 && p[1].equals(var)) {
                int j = i + 1;
                while (j < f.end && !core.ln[j].startsWith("ASSIGN ")) {
                    if (GasReportCore.isLabel(core.ln[j])) {
                        return null;
                    }
                    j++;
                }
                if (j >= f.end) {
                    return null;
                }
                List<GasReportCore.V> st = evalSeg(f, i + 1, j, i, depth);
                if (st == null) {
                    return null;
                }
                if (p.length >= 3 && p[2].contains("range")) {
                    if (st.size() == 2 && !st.get(0).range && !st.get(1).range) {
                        return new GasReportCore.V(st.get(0).a, st.get(1).a, true);
                    }
                    return st.size() == 1 && st.get(0).range ? st.get(0) : null;
                }
                return st.size() == 1 && !st.get(0).range ? st.get(0) : null;
            }
            if (p[0].equals("ARG") && p.length >= 2 && p[1].equals(var)) {
                return f.args.get(var);
            }
            if (!p[0].equals("PUSH") && !p[0].equals("ADDR") && !p[0].equals("ASSIGN") && !p[0].equals("ALLOC")) {
                for (int k = 1; k < p.length; k++) {
                    if (p[k].equals(var)) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * A variable that is written once only (or a parameter that is never written but copied into its own slot at the top) has that one value for the
     * rest of the function, labels or not: a parameter's value, or the value of its single write when that write is straight-line code from the start
     * of the function (no label before it, so it runs exactly once, before everything that follows).
     */
    public GasReportCore.V stableValue(GasReportCore.Fn f, String var, int at, int depth) {
        int write = -1, writes = 0;
        boolean param = false;
        for (int i = f.start + 1; i < f.end; i++) {
            String line = core.ln[i];
            if (line.startsWith("ARG ") && line.split(" ")[1].equals(var)) {
                param = true;
            }
            if (line.startsWith("ADDR ") && line.split(" ")[1].equals(var)) {
                boolean selfCopy = param && i + 2 < f.end && core.ln[i + 1].startsWith("PUSH " + var + " ") && core.ln[i + 2].startsWith("ASSIGN ");
                if (!selfCopy) {
                    writes++;
                    write = i;
                }
            }
        }
        if (param && writes == 0) {
            return f.args.get(var);
        }
        if (!param && writes == 1 && write < at && runsFirst(f, write)) {
            int j = write + 1;
            while (j < f.end && !core.ln[j].startsWith("ASSIGN ")) {
                j++;
            }
            List<GasReportCore.V> st = evalSeg(f, write + 1, j, write, depth);
            if (st == null) {
                return null;
            }
            String[] p = core.ln[write].split(" ");
            if (p.length >= 3 && p[2].contains("range")) {
                if (st.size() == 2 && !st.get(0).range && !st.get(1).range) {
                    return new GasReportCore.V(st.get(0).a, st.get(1).a, true);
                }
                return st.size() == 1 && st.get(0).range ? st.get(0) : null;
            }
            return st.size() == 1 && !st.get(0).range ? st.get(0) : null;
        }
        return null;
    }

    /** True when line `w` is reached on every path from the function start and only once: nothing before it jumps (but the old landing-pad skip) or is a label (but the landing pads). */
    public boolean runsFirst(GasReportCore.Fn f, int w) {
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

    public BigInteger scalarAt(GasReportCore.Fn f, int idx) {
        List<GasReportCore.V> st = evalSeg(f, idx, idx + 1, idx, 0);
        return st != null && st.size() == 1 && !st.get(0).range ? st.get(0).a : null;
    }

    /** Iteration count of a `for` whose range is not literal in its type: the range the hidden `$for_range_N` variable was built from, when known here. */
    public BigInteger rangeCount(GasReportCore.Fn f, GasReportCore.Loop lp, String type) {
        String name = null;
        for (int i = lp.head + 1; i < lp.back && i < lp.head + 6; i++) {
            if (core.ln[i].startsWith("PUSH $for_range_")) {
                name = core.ln[i].split(" ")[1];
                break;
            }
        }
        if (name == null) {
            return null;
        }
        GasReportCore.V v = valueAt(f, name, lp.head, 0);
        return v != null && v.range ? v.b.subtract(v.a).max(BigInteger.ZERO) : null;
    }

    /** The parameter values call line `callLine` of `caller` passes to `callee` (only those known), or empty. */
    public Map<String, GasReportCore.V> callArgs(GasReportCore.Fn caller, int callLine, Map<String, GasReportCore.V> callerArgs, GasReportCore.Fn callee) {
        Map<String, GasReportCore.V> out = new HashMap<>();
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
        List<GasReportCore.V> slots = new ArrayList<>();
        int segStart = cs + 1, d = 0;
        for (int i = cs + 1; i < callLine; i++) {
            String o = GasReportCore.op(core.ln[i]);
            if (o.equals("CC_START")) {
                d++;
            } else if (o.equals("CC_END")) {
                d--;
            } else if (d == 0 && core.ln[i].startsWith("POP ARG")) {
                List<GasReportCore.V> st = evalSeg(caller, segStart, i, i, 0);
                slots.add(st != null && st.size() == 1 && !st.get(0).range ? st.get(0) : null);
                segStart = i + 1;
            }
        }
        List<String[]> params = new ArrayList<>();
        for (int i = callee.start; i < callee.end; i++) {
            if (core.ln[i].startsWith("ARG ")) {
                params.add(core.ln[i].split(" "));
            } else if (core.ln[i].startsWith("ALLOC ")) {
                break;
            }
        }
        int need = 0;
        for (String[] pr : params) {
            need += pr.length >= 3 && pr[2].matches("(imut_|mut_)?range") ? 2 : 1;
        }
        if (need != slots.size()) {
            return out;
        }
        int k = 0;
        for (String[] pr : params) {
            boolean rng = pr.length >= 3 && pr[2].matches("(imut_|mut_)?range");
            GasReportCore.V x = slots.get(k++);
            if (rng) {
                GasReportCore.V y = slots.get(k++);
                if (x != null && y != null) {
                    out.put(pr[1], new GasReportCore.V(x.a, y.a, true));
                }
            } else if (x != null) {
                out.put(pr[1], x);
            }
        }
        return out;
    }

    /** Pairs each allocation with the label its null check jumps to on success (everything between is the whitelisted check and the failure branch). */
    public void mapOkLabels(GasReportCore.Fn f) {
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
    public void analyzeCredits(GasReportCore.Fn f) {
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
                String[] p = l.split(" ");
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
                String[] p = core.ln[i].split(" ");
                String st = p.length >= 3 ? p[2].replaceFirst("^((mut|imut|indeterminate)_)+", "") : "";
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
        GasReportCore.St[] in = flow(f, f.start + 1, new GasReportCore.St(), slotType, lhs);
        f.credit.putAll(creditsFrom(f, in, slotType));
        // parameter frees and the returned block, from the settled in-states
        boolean[] freed = new boolean[params.size()];
        java.util.Arrays.fill(freed, true);
        boolean anyExit = false;
        long retMin = Long.MAX_VALUE, retDeepMin = Long.MAX_VALUE;
        for (int i = f.start + 1; i < f.end; i++) {
            GasReportCore.St s = in[i];
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
            GasReportCore.St entry = null;
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
            f.edgeCredit.put(i, bySig.computeIfAbsent(sig, k -> cm));
        }
    }

    /** Fixpoint of the credit state over the lines reachable from `start` with the state `init` there. */
    public GasReportCore.St[] flow(GasReportCore.Fn f, int start, GasReportCore.St init, Map<String, String> slotType, Map<Integer, Integer> lhs) {
        GasReportCore.St[] in = new GasReportCore.St[f.end + 1];
        java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
        in[start] = init.copy();
        work.add(start);
        while (!work.isEmpty()) {
            int i = work.poll();
            if (i >= f.end) {
                continue;
            }
            GasReportCore.St out = transferCredit(f, i, in[i], slotType, lhs, in);
            for (int t : successors(f, i)) {
                if (t >= f.end || t < 0) {
                    continue;
                }
                if (in[t] == null) {
                    in[t] = out.copy();
                    work.add(t);
                } else {
                    GasReportCore.St j = GasReportCore.St.join(in[t], out);
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
    public Map<Integer, Long> creditsFrom(GasReportCore.Fn f, GasReportCore.St[] in, Map<String, String> slotType) {
        Map<Integer, Long> out = new java.util.TreeMap<>();
        for (int i = f.start + 1; i < f.end; i++) {
            GasReportCore.St s = in[i];
            if (s == null) {
                continue;
            }
            String o = GasReportCore.op(core.ln[i]);
            long cr = 0;
            if (o.equals("GT_DESTRUCT") && core.ln[i].split(" ").length == 2) {
                cr = s.lo.getOrDefault(GasReportCore.arg(core.ln[i]), 0L) + (f.deepOk ? s.deep.getOrDefault(GasReportCore.arg(core.ln[i]), 0L) : 0L);
            } else if ((o.equals("RESIZE") || o.equals("URESIZE")) && i >= 3) {
                int srcAt = o.equals("RESIZE") ? i - 3 : i - 2;
                boolean simple = core.ln[i - 1].startsWith("PUSH ") && (o.equals("URESIZE") || core.ln[i - 2].startsWith("PUSH ")) && core.ln[srcAt].startsWith("PUSH ");
                String y = simple ? GasReportCore.arg(core.ln[srcAt]) : null;
                if (y != null && slotType.containsKey(y)) {
                    cr = s.lo.getOrDefault(y, 0L);
                }
            } else if (o.equals("CALL")) {
                GasReportCore.Fn cf = core.fns.get(GasReportCore.arg(core.ln[i]));
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
    public boolean canReturn(GasReportCore.Fn f) {
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

    public List<Integer> successors(GasReportCore.Fn f, int i) {
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

    public GasReportCore.St transferCredit(GasReportCore.Fn f, int i, GasReportCore.St s, Map<String, String> slotType, Map<Integer, Integer> lhs, GasReportCore.St[] in) {
        String line = core.ln[i], o = GasReportCore.op(line);
        if (o.equals("GT_DESTRUCT") && line.split(" ").length == 2 && slotType.containsKey(GasReportCore.arg(line))) {
            GasReportCore.St r = s.copy();
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
        GasReportCore.St r = s.copy();
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
            GasReportCore.Fn cf = core.fns.get(GasReportCore.arg(core.ln[i - 3]));
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
    public boolean deepOk(GasReportCore.Fn f, Map<String, String> slotType) {
        Set<Integer> idiom = new HashSet<>();
        for (int i = f.start + 1; i < f.end; i++) {
            if (GasReportCore.op(core.ln[i]).equals("GT_DESTRUCT") && core.ln[i].split(" ").length == 2) {
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
            String[] p = core.ln[i].split(" ");
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
                String[] p = l.split(" ");
                core.structMembers.get(cur).add(p[p.length - 1]);
                if (p[p.length - 1].contains("owns_")) {
                    core.ownsMemberNames.add(p[1]);
                    core.ownsStructs.add(cur);
                }
                total += (sizeOf(p[p.length - 1]) + 7) / 8 * 8;
            } else if (cur != null && l.startsWith("STRUCT_PADDING ")) {
                total += Long.parseLong(l.substring(15).trim());
            } else if (l.equals("STRUCT_END") && cur != null) {
                core.structSize.put(cur, Math.max(8, (total + 7) / 8 * 8));
                cur = null;
            }
        }
    }

    /** Size in bytes of a value of HOB type `t` (an estimate for anything not obviously sized: 8). */
    public long sizeOf(String t) {
        if (core.structSize == null) {
            parseStructs();
        }
        String x = t;
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
            return 8;
        }
        if (x.startsWith("range")) {
            return 16;
        }
        if (x.startsWith("dynarray(") || x.startsWith("owns_dynarray")) {
            return 8;
        }
        java.util.regex.Matcher arr = java.util.regex.Pattern.compile("(.*)\\[(\\d+)\\]$").matcher(x);
        if (arr.matches()) {
            return Long.parseLong(arr.group(2)) * ((sizeOf(arr.group(1)) + 7) / 8 * 8);
        }
        java.util.regex.Matcher prim = java.util.regex.Pattern.compile("[us](\\d+)").matcher(x);
        if (prim.matches()) {
            return Math.max(1, Integer.parseInt(prim.group(1)) / 8);
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
        String[] p = core.ln[idx].split(" ");
        return p.length >= 2 && p[1].matches("\\d+") ? new BigInteger(p[1]) : null;
    }

    /** Bytes of a deep clone of a block of type `t` (a struct: its own size plus every owned member's block; a dynarray: runtime size, null). */
    public BigInteger deepBytes(String t, int depth) {
        if (depth > 8) {
            return null;
        }
        String e = GasReportCore.dynElem(t);
        if (e != null) {
            return null;
        }
        BigInteger total = BigInteger.valueOf((sizeOf(t) + 7) / 8 * 8);
        List<String> ms = core.structMembers.get(t);
        if (ms != null) {
            for (String m : ms) {
                if (m.contains("owns_")) {
                    String pt = GasReportCore.pointee(m);
                    if (pt == null) {
                        continue;
                    }
                    BigInteger d = m.contains("dynarray") ? null : deepBytes(pt, depth + 1);
                    if (d == null) {
                        return null;
                    }
                    total = total.add(d);
                }
            }
        }
        return total;
    }

    public void verifyLabels(Map<String, DocComments.Doc> funcs, Map<String, DocComments.Doc> loops) {
        for (GasReportCore.Fn f : core.fns.values()) {
            core.solve(f, new ArrayList<>());
        }
        for (Map.Entry<String, DocComments.Doc> e : funcs.entrySet()) {
            String word = funcWord(e.getKey());
            DocComments.Doc d = e.getValue();
            if (word != null && !word.equals(d.text)) {
                GasReportCore.Fn f = core.fns.get(e.getKey());
                throw new CompilerException("termination", d.file, d.targetLine, "function `" + GasReportCore.shown(f.name) + "` is labelled `" + d.text
                        + "` but its termination class is `" + word + "`" + (GasReportCore.worstOf(f.ub[GasReportCore.GAS]) == 0 ? "" : " (" + GasReportCore.tag(f.ub[GasReportCore.GAS]) + ")"));
            }
        }
        for (Map.Entry<String, DocComments.Doc> e : loops.entrySet()) {
            int bar = e.getKey().indexOf('|');
            String word = loopWord(e.getKey().substring(0, bar), e.getKey().substring(bar + 1));
            DocComments.Doc d = e.getValue();
            if (word != null && !word.equals(d.text)) {
                throw new CompilerException("termination", d.file, d.targetLine, "`" + d.kind + "` in `" + GasReportCore.shown(e.getKey().substring(0, bar)) + "` is labelled `" + d.text
                        + "` but its termination class is `" + word + "`");
            }
        }
    }

    /** The class word of a function (solved already), or null when it is not in the program. */
    public String funcWord(String name) {
        GasReportCore.Fn f = core.fns.get(name);
        return f == null ? null : GasReportCore.wordOf(GasReportCore.worstOf(f.ub[GasReportCore.GAS]));
    }

    /** The class word of one loop (see loopClass), or null when the loop is not found. */
    public String loopWord(String fnName, String label) {
        GasReportCore.Fn f = core.fns.get(fnName);
        if (f == null || !f.labels.containsKey(label)) {
            return null;     // optimised away before the report could see it, or not emitted
        }
        Set<String> savedUb = new LinkedHashSet<>(f.ub[GasReportCore.GAS]);
        GasReportCore.Eval ev = core.new Eval(f, GasReportCore.GAS);
        GasReportCore.Loop lp = ev.loops.get(f.labels.get(label));
        if (lp == null) {
            return null;
        }
        int cls = loopClass(ev, f, lp);
        f.ub[GasReportCore.GAS].clear();
        f.ub[GasReportCore.GAS].addAll(savedUb);
        return GasReportCore.wordOf(cls);
    }

    /** The class of one loop alone: its own bound/exit, every call and indirect call inside it, and the loops nested in it. */
    public int loopClass(GasReportCore.Eval ev, GasReportCore.Fn f, GasReportCore.Loop lp) {
        ev.solveLoop(lp);
        int cls = lp.ownClass;
        for (GasReportCore.Loop in : ev.loops.values()) {
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
                GasReportCore.Fn cf = f.calleeAt.get(i);
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

    public BigInteger allocBytes(int idx, GasReportCore.Fn f) {
        String line = core.ln[idx];
        String[] p = line.split(" ");
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
                String id = idx > 0 && core.ln[idx - 1].startsWith("PUSH string_id") ? core.ln[idx - 1].split(" ")[1] : null;
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
    public long frameBytes(GasReportCore.Fn f) {
        long total = 16;
        for (int i = f.start; i < f.end; i++) {
            String l = core.ln[i];
            if (l.startsWith("ALLOC ") || l.startsWith("ARG ") || l.startsWith("ALLOC_STATIC ")) {
                String[] p = l.split(" ");
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
        GasReportCore.Fn f = core.fns.get(n);
        Set<String> w = new LinkedHashSet<>();
        if (path.contains(n)) {
            return 0;
        }
        path.add(n);
        long best = 0;
        String bestVia = null;
        for (String c : f.calls) {
            GasReportCore.Fn cf = core.fns.get(c);
            if (cf == null) {
                continue;
            }
            if (path.contains(c)) {
                throw new IllegalStateException("audit: call cycle through " + c + " in a program that type-checked (compiler bug)");
            }
            frame.putIfAbsent(c, frameBytes(cf));
            long d = stackDepth(c, frame, depth, via, why, path);
            if (!why.get(c).isEmpty()) {
                w.add(GasReportCore.callReason(c, why.get(c), false));
            }
            if (d > best || bestVia == null) {
                best = d;
                bestVia = c;
            }
        }
        for (int i = f.start; i < f.end; i++) {
            if (GasReportCore.op(core.ln[i]).equals("INVOKE")) {
                w.add(GasReportCore.R(GasReportCore.UNK, "indirect call (INVOKE), the callee is not known"));
            }
        }
        path.remove(path.size() - 1);
        long d = frame.get(n) + best;
        depth.put(n, d);
        if (bestVia != null) {
            via.put(n, bestVia);
        }
        why.put(n, w);
        return d;
    }

    /** The termination class word of a function for the page (the word the audit prints after its gas figure). */
    public String termWord(String name) {
        String w = funcWord(name);
        return w == null ? "bound" : w;
    }

    public String vizPage(String title) {
        GasReportCore.Fn entry = core.entryFn();
        if (entry == null) {
            return null;
        }
        for (GasReportCore.Fn f : core.fns.values()) {
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
            GasReportCore.Fn cf = core.fns.get(c);
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
        for (String s : hob.split("\n")) {
            s = s.trim();
            if (!s.isEmpty()) {
                l.add(s);
            }
        }
        core.ln = l.toArray(new String[0]);
        GasReportCore.Fn cur = null;
        for (int i = 0; i < core.ln.length; i++) {
            if (core.ln[i].startsWith("FUNC_START ")) {
                cur = new GasReportCore.Fn();
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

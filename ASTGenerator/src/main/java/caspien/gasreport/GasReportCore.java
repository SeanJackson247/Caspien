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

/** GasReportCore: fields, nested types and static helpers of GasReport (part 1 of 2; see README.md in this folder). */
final class GasReportCore {
    private final GasReport hub;

    GasReportCore(GasReport hub) {
        this.hub = hub;
    }

    public static final int CALL_COST = 5;
    /** Lines per section before "(+n more)"; the env var CASPIEN_AUDIT_ALL=1 (used by the tests) lifts the limit. */
    public static final int CAP = "1".equals(System.getenv("CASPIEN_AUDIT_ALL")) ? Integer.MAX_VALUE - 2 : 40;
    public static final int GAS = 0, HEAP = 1, COUNT = 2, LIVE = 3, NMET = 4;     // the path metrics: gas, heap bytes requested, number of allocation operations

    public static final class Fn {
        public String name;
        public int start, end;                       // line indexes: FUNC_START line .. FUNC_END line
        public Map<String, Integer> labels = new HashMap<>();
        public final BigInteger[] val = new BigInteger[NMET];                                // per metric: GAS, HEAP (bytes), COUNT, LIVE (net live bytes)
        public BigInteger livePeak = BigInteger.ZERO;                                        // LIVE: the peak of live heap bytes during a run, relative to entry
        public final Map<Integer, Long> credit = new HashMap<>();                            // line -> bytes a free at that line (GT_DESTRUCT, a resize's old block, a call's moved-in arguments) certainly releases
        public final Map<Integer, Integer> okLabelAlloc = new HashMap<>();                   // LIVE: success label (`@new_ok_N`, `@alloc_ok_N`) line -> the allocation line it confirms (a failed allocation holds no block)
        public final Map<Integer, Map<Integer, Long>> edgeCredit = new HashMap<>();          // LIVE: line of an edge into a catch block / landing pad -> the credits of that pad analysed from that edge's state
        public final Map<Integer, Integer> edgeCall = new HashMap<>();                       // LIVE: PUSH_LABEL line -> the call that may unwind through it (its moved-in arguments are freed on that path too)
        public final Map<String, Object> padEvals = new HashMap<>();                         // credit-map signature -> Eval (kept untyped here: Eval is an inner class)
        public long retLo;                                                                   // bytes of the owned block it returns (certain lower bound)
        public long retDeep;                                                                 // bytes of the blocks owned through members of the block it returns (certain lower bound)
        public boolean deepOk;                                                               // members that own memory are never moved out, replaced or shared by reference here: a destruct frees the whole tree it was built with
        public boolean[] paramFreed = new boolean[0];                                        // per ARG: every exit path destructs that owned parameter
        @SuppressWarnings("unchecked")
        final Set<String>[] ub = new Set[]{new LinkedHashSet<String>(), new LinkedHashSet<String>(), new LinkedHashSet<String>(), new LinkedHashSet<String>()};   // unbounded reasons per metric
        public int sites;                            // allocation operations written in the body
        public Set<String> notModelled = new LinkedHashSet<>();
        public Set<String> calls = new LinkedHashSet<>();
        public boolean busy;
        public boolean done;
        public boolean throwsDecorated;
        public final Set<String> deco = new HashSet<>();   // FUNC_DECORATE names (@event_loop, @with_tick, @tick, ...)
        public Map<String, V> args = new HashMap<>();                                        // parameter values this solve assumes (empty = none known): a specialisation per call context
        public final Map<Integer, Fn> calleeAt = new HashMap<>();                            // CALL line -> the (possibly specialised) callee whose figures that call uses

        /** A copy that shares the analysis results independent of argument values (credits, labels) and gets its own figures. */
        public Fn specialised() {
            Fn c = new Fn();
            c.name = name;
            c.start = start;
            c.end = end;
            c.labels = labels;
            c.credit.putAll(credit);
            c.okLabelAlloc.putAll(okLabelAlloc);
            c.edgeCredit.putAll(edgeCredit);
            c.edgeCall.putAll(edgeCall);
            c.retLo = retLo;
            c.retDeep = retDeep;
            c.deepOk = deepOk;
            c.paramFreed = paramFreed;
            c.sites = sites;
            c.notModelled = notModelled;
            c.calls = calls;
            c.throwsDecorated = throwsDecorated;
            c.deco.addAll(deco);
            return c;
        }
    }

    /** A value known at analysis time: a scalar, or the range a..b. */
    public static final class V {
        public final BigInteger a, b;
        public final boolean range;
        public V(BigInteger a, BigInteger b, boolean range) {
            this.a = a;
            this.b = b;
            this.range = range;
        }
        public static V sc(BigInteger x) {
            return new V(x, null, false);
        }
        @Override
        public String toString() {
            return range ? a + ".." + b : a.toString();
        }
    }

    /** Source positions told by the front end for `--audit` causes: "function" and "function|loop label" -> "file:line". Empty = not known. */
    public static final Map<String, String> POS = new HashMap<>();
    /** "function|loop label" -> the retry limit of a `match @lock` spin (> 0 literal, -1 run-time value), from the compiler's lowering. */
    public static final Map<String, Long> SPIN = new HashMap<>();

    public String[] ln;                // trimmed non-empty lines
    public final Map<String, Fn> fns = new LinkedHashMap<>();

    public static final class Result {
        public String text;
        public final Map<String, String> gasByFunction = new LinkedHashMap<>();   // for tests: name -> "123" or ">=123"
        public final Map<String, String> stackByFunction = new LinkedHashMap<>();
        public final Map<String, String> heapByFunction = new LinkedHashMap<>();
        public final Map<String, String> countByFunction = new LinkedHashMap<>();
        public final Map<String, String> liveByFunction = new LinkedHashMap<>();    // name -> "peak/leaves" (peak prefixed ">=" when unbounded)
        public Result(String text) {
            this.text = text;
        }
    }

    /** Builds the report section for the program whose HOB is given; `entry` functions are `main` and `__caspien_main`. */
    public static Result build(String hob) {
        GasReport g = new GasReport(hob);
        final Result[] out = new Result[1];
        Thread t = new Thread(null, () -> out[0] = g.core.report(), "gas", 1L << 29);   // deep recursion over long functions
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    // ---- the cost table ----------------------------------------------------------------------------------------------------------------

    /** Gas of one operation, not counting the callee of a CALL. Operations that are free bookkeeping (declarations, calling-convention markers) cost 0. */
    public static int cost(String op) {
        switch (op) {
            case "ALLOC": case "ALLOC_STATIC": case "ARG": case "RETURNS": case "FUNC_START": case "FUNC_END": case "FUNC_DECORATE":
            case "STRUCT_START": case "STRUCT_END": case "STRUCT_MEMBER": case "STRUCT_PADDING": case "STRUCT_DECORATE": case "ENUM":
            case "STRING": case "EXTERN": case "GLOBAL": case "CC_START": case "CC_END": case "VARARGS_XMM_COUNT": case "PUSH_LABEL":
            case "ASM_START": case "ASM_END":
                return 0;
            case "DEREF": case "LOOKUP": case "LOOKUP_LHS": case "DOT": case "LEN":
                return 2;
            case "MUL":
                return 3;
            case "DIV": case "MOD":
                return 20;
            case "CALL":
                return CALL_COST;
            case "THROW": case "EXIT": case "EXIT_THREAD": case "ATOMIC_SWAP": case "ATOMIC_ASSIGN": case "ATOMIC_PUSH": case "STACK_LOCK":
            case "SLEEP": case "YIELD": case "INVOKE":
                return 10;
            case "MEMCOPY": case "GT_REGISTER": case "GT_ALIVE_CHECK": case "GT_REF_ID": case "GT_REF_RESOLVE":
                return 20;
            case "GT_DESTRUCT": case "GT_DESTRUCT_ADDR": case "GT_DESTRUCT_TAIL": case "GT_MOVED":
                return 30;
            case "EXTERN_CALL":
                return 50;
            case "NEW": case "NEW_DYN": case "NEW_UDYN": case "NEW_FROM_STRING": case "NEW_FROM_USTRING": case "RESIZE": case "URESIZE":
            case "CLONE": case "CLONE_FILL": case "CLONE_DYN": case "GT_INIT":
                return 100;
            default:
                return 1;   // data movement, arithmetic, comparison, jumps, stores
        }
    }

    public static String op(String line) {
        int sp = line.indexOf(' ');
        return sp < 0 ? line : line.substring(0, sp);
    }

    public static String arg(String line) {
        int sp = line.indexOf(' ');
        if (sp < 0) {
            return "";
        }
        String r = line.substring(sp + 1).trim();
        int sp2 = r.indexOf(' ');
        return sp2 < 0 ? r : r.substring(0, sp2);
    }

    // ---- analysis ----------------------------------------------------------------------------------------------------------------------

    public static final int CONT = 0, BRK = 1, RET = 2, ALL = 3;                     // which kind of path a walk looks for

    /**
     * A path value: `n` = net effect of a path (gas, bytes, a count; for LIVE the net change of live heap bytes), `p` = the peak of the running
     * total along the path (never below 0, the empty prefix). Plain metrics only ever read `n` (their p equals n). `neg` = "no such path".
     */
    public static final class Val {
        public final BigInteger n, p;
        public final boolean neg;
        public Val(BigInteger n, BigInteger p, boolean neg) {
            this.n = n;
            this.p = p;
            this.neg = neg;
        }
        public static final Val NEG = new Val(BigInteger.ZERO, BigInteger.ZERO, true);
        public static final Val ZERO = new Val(BigInteger.ZERO, BigInteger.ZERO, false);
        /** A path that ends in a throw or a process exit: its peak counts, it leaves nothing for the caller (its net is -infinity, so any returning path wins the net). */
        public static final Val NONET = new Val(BigInteger.TEN.pow(30).negate(), BigInteger.ZERO, false);
        public static Val of(BigInteger c) {
            return new Val(c, c.max(BigInteger.ZERO), false);
        }
        public static Val of(long c) {
            return of(BigInteger.valueOf(c));
        }
        /** a, then b. */
        public static Val seq(Val a, Val b) {
            if (a.neg || b.neg) {
                return NEG;
            }
            return new Val(a.n.add(b.n), a.p.max(a.n.add(b.p)), false);
        }
        /** the dearer of two alternative paths (componentwise: an upper bound for both the net and the peak). */
        public static Val alt(Val a, Val b) {
            if (a.neg) {
                return b;
            }
            if (b.neg) {
                return a;
            }
            return new Val(a.n.max(b.n), a.p.max(b.p), false);
        }
        /** c repeated k times, back to back. */
        public static Val rep(BigInteger k, Val c) {
            if (c.neg || k.signum() == 0) {
                return ZERO;
            }
            BigInteger peak = c.n.signum() > 0 ? k.subtract(BigInteger.ONE).multiply(c.n).add(c.p) : c.p;
            return new Val(k.multiply(c.n), peak, false);
        }
    }

    public static final class Loop {
        public int head, end, back;        // label line, exit label line, the unconditional JMP back to the head
        public BigInteger count;           // null = not a literal bound
        public String why;
        public boolean isFor;
        public Val exitCost;               // cost of the paths that leave the loop and continue after it (add the tail); NEG impossible
        public Val retCost = Val.NEG;      // cost of the paths that return/throw/exit from inside the loop (no tail)
        public boolean done, computing;
        public int ownClass;               // the loop's own termination class (not its callees'): 0 bound, FIN run-time `for`, UNB `loop` with an exit, NONT `loop` with none
    }

    /** Per-function analysis state. */
    public final class Eval {
        public final Fn f;
        public final int mt;                                          // GAS, HEAP, COUNT or LIVE
        public final Map<Integer, Loop> loops = new HashMap<>();      // head line -> loop
        public final Map<Long, Val[]> memo = new HashMap<>();         // (context, mode) -> per-line memo

        public final Map<Integer, Long> cred;                         // LIVE: the credits this walk uses (the function's own, or those of a pad analysed from one entry edge)

        public Eval(Fn f, int mt) {
            this(f, mt, f.credit);
        }

        public Eval(Fn f, int mt, Map<Integer, Long> cred) {
            this.f = f;
            this.mt = mt;
            this.cred = cred;
            for (Map.Entry<String, Integer> e : f.labels.entrySet()) {
                String lab = e.getKey();
                String kind = lab.startsWith("@for_") && !lab.startsWith("@for_end_") && !lab.startsWith("@for_cont_") ? "for"
                        : lab.startsWith("@loop_") && !lab.startsWith("@loop_end_") ? "loop" : null;
                if (kind == null) {
                    continue;
                }
                int n;
                try {
                    n = Integer.parseInt(lab.substring(kind.length() + 2));
                } catch (NumberFormatException ex) {
                    continue;
                }
                Integer endLine = f.labels.get("@" + kind + "_end_" + (n + 1));
                if (endLine == null) {
                    continue;
                }
                Loop lp = new Loop();
                lp.head = e.getValue();
                lp.end = endLine;
                lp.back = -1;
                lp.isFor = kind.equals("for");
                for (int i = endLine - 1; i > lp.head; i--) {
                    if (ln[i].equals("JMP " + lab)) {
                        lp.back = i;
                        break;
                    }
                }
                if (lp.back < 0) {
                    continue;
                }
                Long spin = lp.isFor ? null : SPIN.get(f.name + "|" + lab);
                if (!lp.isFor) {
                    lp.why = null;      // classified in solveLoop (needs to know whether the body can leave the loop)
                    if (spin != null && spin > 0) {
                        lp.count = BigInteger.valueOf(spin).add(BigInteger.ONE);     // retries 1..limit, then the give-up test breaks on the next pass
                    } else if (spin != null) {
                        String at = POS.get(f.name + "|" + lab);
                        lp.why = R(FIN, "`match @lock` retries a number of times only known at run time (`default(n)` with a run-time n)" + (at != null ? " (" + at + ")" : ""));
                    }
                } else {
                    String type = null;
                    for (int i = lp.head + 1; i < lp.back && i < lp.head + 6; i++) {
                        if (ln[i].startsWith("PUSH $for_range_")) {
                            String[] p = ln[i].split(" ");
                            type = p[p.length - 1];
                            break;
                        }
                    }
                    BigInteger c = null;
                    if (type != null) {
                        int a = type.indexOf("range(");
                        int comma = type.lastIndexOf(',');
                        if (a >= 0 && comma > a && type.endsWith(")")) {
                            String lo = type.substring(a + 6, comma), hi = type.substring(comma + 1, type.length() - 1);
                            if (lo.matches("-?\\d+") && hi.matches("-?\\d+")) {
                                c = new BigInteger(hi).subtract(new BigInteger(lo)).max(BigInteger.ZERO);
                            }
                        }
                    }
                    if (c == null && type != null && f.args != null) {
                        c = hub.rangeCount(f, lp, type);
                    }
                    lp.count = c;
                    if (c == null) {
                        String dep = null;
                        if (type != null && type.indexOf(',') > 0 && type.endsWith(")")) {
                            String hi = type.substring(type.lastIndexOf(',') + 1, type.length() - 1).replaceFirst("^(imut_|mut_)", "");
                            if (hi.matches("[A-Za-z_][A-Za-z_0-9.]*")) {
                                dep = hi;
                            }
                        }
                        String at = POS.get(f.name + "|" + lab);
                        lp.why = R(FIN, "`for` runs a number of times only known at run time" + (dep != null ? ": depends on `" + dep + "`" : " (" + (type == null ? lab : type) + ")") + (at != null ? " (" + at + ")" : ""));
                    }
                }
                loops.put(lp.head, lp);
            }
        }

        /** Costs of a whole loop statement: exitCost (normal exit or `break`; the code after the loop is added by the caller) and retCost (a `return` inside). */
        public void solveLoop(Loop lp) {
            if (lp.done || lp.computing) {
                return;
            }
            lp.computing = true;
            Val cont = walk(lp.head + 1, lp.head, CONT);   // header + body up to the back edge
            Val brk = walk(lp.head + 1, lp.head, BRK);     // ... up to a `break` / the final header test
            Val ret = walk(lp.head + 1, lp.head, RET);     // ... up to a return/throw/exit
            Val hdr = lp.isFor ? headerCost(lp) : Val.ZERO;
            BigInteger n;
            if (lp.count != null) {
                n = lp.count;
                lp.ownClass = 0;
            } else {
                // a count or a live-heap figure does not depend on the bound of a loop that does not grow it
                String why = lp.why;
                if (!lp.isFor && why == null) {
                    boolean exit = !brk.neg || !ret.neg;     // a reachable `break`, `return` or `throw` (also from a call that can throw) inside the body
                    why = exit ? R(UNB, "`loop` with no static bound, it can leave through `break`/`return`/`throw` (" + labelOf(lp) + ")")
                            : R(hub.runsFirst(f, lp.head) ? NONT : DIV, "`loop` with no `break`, `return` or `throw` that leaves it (" + labelOf(lp) + ")");
                }
                lp.ownClass = why.charAt(0) - '0';
                if (!lp.isFor && lp.why == null) {
                    lp.ownClass = (!brk.neg || !ret.neg) ? UNB : NONT;
                }
                if (mt == GAS || (!cont.neg && cont.n.signum() > 0)) {
                    f.ub[mt].add(why);
                }
                n = BigInteger.ONE;        // lower bound: one iteration
            }
            if (n.signum() == 0) {
                lp.exitCost = hdr;
            } else {
                BigInteger nm1 = n.subtract(BigInteger.ONE);
                Val c0 = cont.neg ? Val.ZERO : cont;
                Val full = lp.isFor || !cont.neg ? Val.seq(Val.rep(n, c0), hdr) : Val.NEG;
                if (!lp.isFor) {
                    full = brk.neg ? cont : Val.NEG;   // a `loop` only ends through `break`
                }
                lp.exitCost = Val.alt(full, brk.neg ? Val.NEG : Val.seq(Val.rep(nm1, c0), brk));
                if (lp.exitCost.neg) {
                    lp.exitCost = c0;
                }
                lp.retCost = ret.neg ? Val.NEG : Val.seq(Val.rep(nm1, c0), ret);
            }
            lp.done = true;
            lp.computing = false;
        }

        public String labelOf(Loop lp) {
            String label = ln[lp.head].substring(0, ln[lp.head].length() - 1);
            String pos = POS.get(f.name + "|" + label);
            return pos != null ? pos : label;      // source file:line when the front end told us, else the HOB label
        }

        public Val headerCost(Loop lp) {
            Val c = Val.ZERO;
            for (int i = lp.head + 1; i < lp.back; i++) {
                String o = op(ln[i]);
                c = Val.seq(c, lineCost(i));
                if (o.equals("JMP") && ln[i - 1].equals("CMP")) {
                    break;
                }
            }
            return c;
        }

        public Val walk(int i, int ctx, int mode) {
            Val[] m = memo.computeIfAbsent(((long) ctx << 3) | mode, k -> new Val[ln.length + 1]);
            return walkMemo(i, ctx, mode, m);
        }

        /** Terminal value of reaching line i in a loop context, or null when i is an ordinary line. */
        public Val terminal(int i, int ctx, int mode) {
            if (ctx < 0) {
                return null;
            }
            Loop cl = loops.get(ctx);
            if (i == cl.back) {
                return mode == CONT ? lineCost(i) : Val.NEG;
            }
            if (i == cl.end) {
                return mode == BRK ? Val.ZERO : Val.NEG;
            }
            return null;
        }

        public Val walkMemo(int start, int ctx, int mode, Val[] m) {
            List<Integer> run = new ArrayList<>();
            int i = start;
            Val tail;
            boolean asm = false;
            while (true) {
                if (i >= f.end) {
                    tail = ctx < 0 ? Val.ZERO : Val.NEG;
                    break;
                }
                if (m[i] != null) {
                    tail = m[i];
                    break;
                }
                Val term = terminal(i, ctx, mode);
                if (term != null) {
                    tail = term;
                    break;
                }
                String line = ln[i];
                String o = op(line);
                if (asm) {
                    if (o.equals("ASM_END")) {
                        asm = false;
                    } else {
                        run.add(i);
                        i++;
                        continue;
                    }
                }
                if (isLabel(line)) {
                    Loop lp = loops.get(i);
                    if (lp != null && i != ctx) {
                        tail = block(lp, ctx, mode);
                        break;
                    }
                    run.add(i);
                    i++;
                    continue;
                }
                if (o.equals("ASM_START")) {
                    asm = true;
                    f.notModelled.add("inline assembly");
                    run.add(i);
                    i++;
                    continue;
                }
                if (o.equals("JMP")) {
                    Integer t = f.labels.get(arg(line));
                    boolean cond = i > 0 && ln[i - 1].equals("CMP");
                    if (t == null) {
                        run.add(i);
                        i++;
                        continue;
                    }
                    Val a = !cond && arg(line).startsWith("@catch_") ? padTarget(i, t, ctx, mode) : walkTarget(t, ctx, mode);
                    Val b = cond ? walk(i + 1, ctx, mode) : Val.NEG;
                    run.add(i);
                    tail = Val.alt(a, b);
                    break;
                }
                if (o.equals("RET") || o.equals("THROW") || o.equals("EXIT") || o.equals("EXIT_THREAD")) {
                    run.add(i);
                    tail = ctx < 0 || mode == RET ? (mt == LIVE && !o.equals("RET") ? Val.NONET : Val.ZERO) : Val.NEG;
                    break;
                }
                if (o.equals("PUSH_LABEL")) {
                    Integer t = f.labels.get(arg(line));
                    if (t != null && !arg(line).startsWith("@for") && !arg(line).startsWith("@loop")) {
                        // a call after this point may unwind to that catch block / landing pad: its cost is an alternative continuation
                        Val alt = padTarget(i, t, ctx, mode);
                        Val nxt = walk(i + 1, ctx, mode);
                        run.add(i);
                        tail = Val.alt(alt, nxt);
                        break;
                    }
                }
                run.add(i);
                i++;
            }
            Val acc = tail;
            for (int k = run.size() - 1; k >= 0; k--) {
                int idx = run.get(k);
                acc = Val.seq(lineCost(idx), acc);
                m[idx] = acc;
            }
            return run.isEmpty() ? tail : m[run.get(0)];
        }

        /** A whole inner loop met while walking: its exit continues after the loop; a return inside it is a terminal path. */
        public Val block(Loop lp, int ctx, int mode) {
            solveLoop(lp);
            Val r = Val.seq(lp.exitCost, walk(lp.end, ctx, mode));
            if (!lp.retCost.neg && (ctx < 0 || mode == RET)) {
                r = Val.alt(r, lp.retCost);
            }
            return r;
        }

        /** The way into a catch block / landing pad from the edge at line `edge`: with the credits of that pad analysed from the state at that edge. */
        public Val padTarget(int edge, int target, int ctx, int mode) {
            Map<Integer, Long> cm = mt == LIVE ? f.edgeCredit.get(edge) : null;
            Val v;
            if (cm == null || cm == cred) {
                v = walkTarget(target, ctx, mode);
            } else {
                Eval e = (Eval) f.padEvals.computeIfAbsent(cm.toString(), k -> new Eval(f, mt, cm));
                v = e.walkTarget(target, ctx, mode);
            }
            Integer call = mt == LIVE ? f.edgeCall.get(edge) : null;
            if (call != null && !v.neg) {
                // the callee that unwinds also frees the owned arguments moved into it
                v = Val.seq(new Val(BigInteger.valueOf(-cred.getOrDefault(call, 0L)), BigInteger.ZERO, false), v);
            }
            return v;
        }

        public Val walkTarget(int target, int ctx, int mode) {
            Val term = terminal(target, ctx, mode);
            if (term != null) {
                return term;
            }
            if (ctx >= 0 && target == loops.get(ctx).head) {
                return mode == CONT ? Val.ZERO : Val.NEG;     // `continue`: the iteration is over
            }
            Loop lp = loops.get(target);
            if (lp != null && target != ctx) {
                return block(lp, ctx, mode);
            }
            return walk(target, ctx, mode);
        }

        public Val lineCost(int idx) {
            String line = ln[idx];
            String o = op(line);
            if (isLabel(line) && !(mt == LIVE && f.okLabelAlloc.containsKey(idx))) {
                return Val.ZERO;
            }
            Val c;
            if (mt == LIVE) {
                Integer allocAt = f.okLabelAlloc.get(idx);
                if (isLabel(line) && allocAt == null) {
                    return Val.ZERO;
                }
                int ai = allocAt != null ? allocAt : idx;
                String ao = op(ln[ai]);
                if (isAlloc(ao) && (allocAt != null || !f.okLabelAlloc.containsValue(ai))) {
                    // charged on the success edge (the label after the null check) when there is one: a failed allocation holds no block
                    long cr = cred.getOrDefault(ai, 0L);
                    BigInteger b = hub.allocBytes(ai, f);
                    // the new block exists before the old one of a `resize` is released: the peak sees both (every resize may move), the net only the difference
                    c = new Val(b.subtract(BigInteger.valueOf(cr)), b, false);
                } else if (o.equals("GT_DESTRUCT")) {
                    c = new Val(BigInteger.valueOf(-cred.getOrDefault(idx, 0L)), BigInteger.ZERO, false);
                } else {
                    c = Val.ZERO;
                }
            } else {
                c = Val.of(mt == GAS ? BigInteger.valueOf(cost(o)) : !isAlloc(o) ? BigInteger.ZERO : mt == COUNT ? BigInteger.ONE : hub.allocBytes(idx, f));
            }
            switch (o) {
                case "CALL": {
                    f.calls.add(arg(line));
                    Fn cf = f.calleeAt.get(idx);
                    if (cf == null) {
                        cf = fns.get(arg(line));
                    }
                    if (cf != null && cf.val[mt] != null) {
                        if (mt == LIVE) {
                            // the callee's net is what it leaves live; the arguments it frees (known by the caller) are credited here
                            BigInteger cr = BigInteger.valueOf(cred.getOrDefault(idx, 0L));
                            return new Val(cf.val[mt].subtract(cr), cf.livePeak.max(cf.val[mt].subtract(cr)), false);
                        }
                        return Val.seq(c, Val.of(cf.val[mt]));
                    }
                    break;
                }
                case "EXTERN_CALL":
                    f.notModelled.add("external call " + arg(line));
                    break;
                case "MEMCOPY":
                    f.notModelled.add("`memcopy` (size not counted)");
                    break;
                case "SLEEP": case "YIELD": case "STACK_LOCK":
                    f.notModelled.add(o.toLowerCase() + " (waits)");
                    break;
                case "ATOMIC_PUSH":
                    f.notModelled.add("thread operation");
                    break;
                case "INVOKE":
                    f.ub[mt].add(R(UNK, "indirect call (INVOKE), the callee is not known"));
                    break;
                default:
                    break;
            }
            return c;
        }
    }

    /** Operations that ask the allocator for memory (a `resize` counts as one: it may grow in place or move). */
    /** A label line: `@name:` (structured control flow) or `name:` (the old landing-pad shape, `gt_routine__f:` / `end_of_gt_routine__f:`). */
    public static boolean isLabel(String line) {
        return line.endsWith(":") && line.indexOf(' ') < 0;
    }

    public static boolean isAlloc(String o) {
        switch (o) {
            case "NEW": case "NEW_DYN": case "NEW_UDYN": case "NEW_FROM_STRING": case "NEW_FROM_USTRING": case "RESIZE": case "URESIZE":
            case "CLONE": case "CLONE_DYN":
                return true;
            default:
                return false;
        }
    }

    public final Map<String, Fn> specs = new HashMap<>();
    public static final int MAX_SPECS = 4000;

    public static boolean unboundedAnywhere(Fn f) {
        for (int m = 0; m < NMET; m++) {
            if (!f.ub[m].isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---- values known from call sites ---------------------------------------------------------------------------------------------------

    public static boolean small(BigInteger x) {
        return x.signum() >= 0 && x.compareTo(BigInteger.ONE.shiftLeft(63)) < 0;
    }

    public static boolean narrowType(String[] p) {
        for (int k = 1; k < p.length; k++) {
            if (p[k].matches(".*[us](8|16|32)")) {
                return true;
            }
        }
        return false;
    }

    // ---- freed bytes (the credits of the live-heap figure) ------------------------------------------------------------------------------

    /** Forward state of the credit analysis: per owned slot the bytes of the block it certainly holds (absent = unknown / null / moved), and the owned parameters certainly destructed. */
    public static final class St {
        public final Map<String, Long> lo = new HashMap<>();
        public final Map<String, Long> deep = new HashMap<>();      // per slot: bytes of the blocks moved into the members of what it holds (certain; counted only when the function is deepOk)
        public final Set<String> dead = new HashSet<>();
        public St copy() {
            St c = new St();
            c.lo.putAll(lo);
            c.deep.putAll(deep);
            c.dead.addAll(dead);
            return c;
        }
        public boolean same(St o) {
            return lo.equals(o.lo) && deep.equals(o.deep) && dead.equals(o.dead);
        }
        /** Must-join: a slot keeps its bytes only when every path agrees on at least them (the minimum). */
        public static St join(St a, St b) {
            St r = new St();
            for (Map.Entry<String, Long> e : a.lo.entrySet()) {
                Long o = b.lo.get(e.getKey());
                if (o != null) {
                    r.lo.put(e.getKey(), Math.min(o, e.getValue()));
                }
            }
            for (Map.Entry<String, Long> e : a.deep.entrySet()) {
                Long o = b.deep.get(e.getKey());
                if (o != null) {
                    r.deep.put(e.getKey(), Math.min(o, e.getValue()));
                }
            }
            r.dead.addAll(a.dead);
            r.dead.retainAll(b.dead);
            return r;
        }
    }

    public static String addrType(String l) {
        String[] p = l.split(" ");
        return p.length > 2 ? p[2] : "";
    }

    public static String firstType(String l) {
        String[] p = l.split(" ");
        return p.length > 1 ? p[1] : "";
    }

    public static final java.util.Set<String> POST_ALLOC = new HashSet<>(java.util.Arrays.asList(
            "GT_REGISTER", "DUP_TOP", "PUSH", "EQ", "CMP", "JMP", "GT_DESTRUCT", "ASSIGN", "ADDR", "PUSH_LABEL"));

    // ---- stack depth -------------------------------------------------------------------------------------------------------------------

    public Map<String, Long> structSize;
    public Map<String, List<String>> structMembers = new HashMap<>();
    public final Set<String> ownsMemberNames = new HashSet<>();     // names of struct members that own memory (any struct)
    public final Set<String> ownsStructs = new HashSet<>();         // structs with such a member
    public Map<String, Long> sizeCache = new HashMap<>();

    // ---- heap bytes --------------------------------------------------------------------------------------------------------------------

    public static final int DYN_HEADER = 16;     // safe dynarray header: [len][cap]

    /** The element type of `...dynarray(T)` (balanced parentheses), or null. */
    public static String dynElem(String t) {
        int i = t.indexOf("dynarray(");
        if (i < 0) {
            return null;
        }
        int start = i + 9, depth = 1, j = start;
        while (j < t.length() && depth > 0) {
            char c = t.charAt(j++);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return t.substring(start, j - 1);
    }

    public static String pointee(String t) {
        String x = t;
        while (x.startsWith("mut_") || x.startsWith("imut_") || x.startsWith("indeterminate_")) {
            x = x.startsWith("mut_") ? x.substring(4) : x.startsWith("imut_") ? x.substring(5) : x.substring(14);
        }
        for (String p : new String[]{"owns_", "ref_", "raw_", "auto_"}) {
            if (x.startsWith(p)) {
                x = x.substring(p.length());
                if (x.startsWith("some_")) {
                    x = x.substring(5);
                }
                while (x.startsWith("mut_") || x.startsWith("imut_") || x.startsWith("indeterminate_")) {
                    x = x.startsWith("mut_") ? x.substring(4) : x.startsWith("imut_") ? x.substring(5) : x.substring(14);
                }
                return x;
            }
        }
        return null;
    }

    /** Bytes requested by the allocation operation at line idx (a lower bound plus an UNBOUNDED reason when the size is only known at run time). */
    // ---- termination labels (`termination:` doc comments) ---------------------------------------------------------------------------------

    /**
     * Compares every `termination:` doc comment with the class the analysis computes: a function's class is the worst class on its
     * reachable paths, callees included (the class `--audit` prints for it); a loop's class is that of the loop alone (its own bound or exit,
     * its calls, the loops inside it); a `loop {}` that nothing can leave is `none` whether or not a path reaches it. Any difference, in
     * either direction, is a compile error. `funcs` is keyed by HOB function name, `loops` by function name + "|" + loop label.
     */
    public static void checkLabels(String hob, Map<String, DocComments.Doc> funcs, Map<String, DocComments.Doc> loops) {
        GasReport g = new GasReport(hob);
        final RuntimeException[] err = new RuntimeException[1];
        Thread t = new Thread(null, () -> {
            try {
                g.verifyLabels(funcs, loops);
            } catch (RuntimeException e) {
                err[0] = e;
            }
        }, "gas-labels", 1L << 29);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (err[0] != null) {
            throw err[0];
        }
    }

    /** Class words for every site ({hobFunction, loopLabel or null, ...}): key = function + "|" + (label or ""). Used by `--fix termination`. */
    public static Map<String, String> classify(String hob, List<String[]> sites) {
        GasReport g = new GasReport(hob);
        final Map<String, String> out = new LinkedHashMap<>();
        Thread t = new Thread(null, () -> {
            for (Fn f : g.core.fns.values()) {
                g.core.solve(f, new ArrayList<>());
            }
            for (String[] s : sites) {
                String w = s[1] == null ? g.funcWord(s[0]) : g.loopWord(s[0], s[1]);
                if (w != null) {
                    out.put(s[0] + "|" + (s[1] == null ? "" : s[1]), w);
                }
            }
        }, "gas-classify", 1L << 29);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    public static String shown(String hobName) {
        return hobName;
    }

    // ---- terminology: every reason string starts with one digit, its class ----------------------------------------------------------------
    public static final int FIN = 1, UNK = 2, UNB = 3, DIV = 4, NONT = 5;

    public static String R(int cls, String text) {
        return (char) ('0' + cls) + text;
    }

    public static int worstOf(Set<String> reasons) {
        int w = 0;
        for (String r : reasons) {
            w = Math.max(w, r.charAt(0) - '0');
        }
        return w;
    }

    public static String wordOf(int cls) {
        switch (cls) {
            case FIN: return "finite";
            case UNK: return "indirect";
            case UNB: return "unbound";
            case DIV: return "conditional";
            case NONT: return "none";
            default: return "bound";
        }
    }

    /** The reason a caller inherits from a callee: the callee's worst class (a callee that never ends only makes the caller able to diverge). */
    public static String callReason(String callee, Set<String> calleeReasons, boolean onEveryPath) {
        int w = worstOf(calleeReasons);
        String at = POS.get(callee);
        return R(w == NONT && !onEveryPath ? DIV : w, "calls " + callee + (at != null ? " (defined at " + at + ")" : "") + " (" + wordOf(w) + ")");
    }

    /** `bound`, or the worst class followed by the reasons (those of a milder class carry their own word). */
    public static String tag(Set<String> reasons) {
        if (reasons.isEmpty()) {
            return "bound";
        }
        int w = worstOf(reasons);
        StringBuilder sb = new StringBuilder(wordOf(w)).append(": ");
        boolean first = true;
        List<String> sorted = new ArrayList<>(reasons);
        sorted.sort((a, b) -> Integer.compare(b.charAt(0), a.charAt(0)));      // worst class first (stable)
        for (String r : sorted) {
            if (!first) {
                sb.append("; ");
            }
            first = false;
            int c = r.charAt(0) - '0';
            sb.append(c == w ? "" : "(" + wordOf(c) + ") ").append(r.substring(1));
        }
        return sb.toString();
    }

    public static void unb(Fn f, String why) {
        f.ub[HEAP].add(why);
        f.ub[LIVE].add(why);
    }

    // ---- visualizer (--viz) -------------------------------------------------------------------------------------------------------------

    /** The HTML page of `--viz`: the entry function as a central circle and the functions it calls on a ring around it, every circle sized by the
     *  worst-case stack depth (the same figure as the stack section: frame + deepest callee) of its function. */
    public static String viz(String hob, String title) {
        GasReport g = new GasReport(hob);
        final String[] out = new String[1];
        Thread t = new Thread(null, () -> out[0] = g.vizPage(title), "viz", 1L << 29);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    public static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\"";
    }

    public static final String VIZ_TEMPLATE = String.join("\n",
        "<!doctype html>",
        "<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
        "<title>@@TITLE@@ (Caspien viz)</title>",
        "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}canvas{display:block;width:100vw;height:100vh;background:#000}</style>",
        "</head><body><canvas id=\"c\"></canvas><script>",
        "// circle area is proportional to the worst-case stack depth in bytes (frame + deepest callee) of the function",
        "const MAIN = @@MAIN@@;",
        "const CALLEES = [@@CALLEES@@];",
        "// colours and outline styles per termination class: caspien.Palette (the one place the tools' look is decided)",
        "const TERM = @@TERM@@;",
        "const cv = document.getElementById('c'), cx = cv.getContext('2d');",
        "function draw(){",
        "  const dpr = window.devicePixelRatio || 1, W = innerWidth, H = innerHeight;",
        "  cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); cx.setTransform(dpr, 0, 0, dpr, 0, 0);",
        "  cx.fillStyle = '#000'; cx.fillRect(0, 0, W, H);",
        "  const mx = W / 2, my = H / 2, u = Math.min(W, H), n = CALLEES.length, R = 0.34 * u;",
        "  const maxS = Math.max(MAIN.stack, ...CALLEES.map(c => c.stack), 1);",
        "  // one scale for every circle: the biggest takes at most 0.14 of the view, and no circle may touch the main circle or its neighbours on the ring",
        "  let k = 0.14 * u / Math.sqrt(maxS);",
        "  if (n > 0) {",
        "    const maxC = Math.max(...CALLEES.map(c => Math.sqrt(c.stack)));",
        "    k = Math.min(k, 0.88 * R / (Math.sqrt(MAIN.stack) + maxC));",
        "    if (n > 1) k = Math.min(k, 0.88 * R * Math.sin(Math.PI / n) / maxC);",
        "  }",
        "  const rad = s => Math.max(3, k * Math.sqrt(s));",
        "  cx.strokeStyle = '#4b5563'; cx.lineWidth = 1;",
        "  if (n > 0) { cx.beginPath(); cx.arc(mx, my, R, 0, 2 * Math.PI); cx.stroke(); }",
        "  const pts = CALLEES.map((c, i) => { const a = -Math.PI / 2 + 2 * Math.PI * i / n; return [mx + R * Math.cos(a), my + R * Math.sin(a), c]; });",
        "  for (const [x, y] of pts) { cx.beginPath(); cx.moveTo(mx, my); cx.lineTo(x, y); cx.stroke(); }",
        "  function disc(x, y, r, node) {",
        "    const t = TERM[node.term] || TERM.bound;",
        "    cx.beginPath(); cx.arc(x, y, r, 0, 2 * Math.PI); cx.fillStyle = t.fill; cx.fill();",
        "    cx.strokeStyle = t.line; cx.setLineDash([]); cx.lineWidth = 2;",
        "    const ring = (rr, dash, w) => { cx.beginPath(); cx.arc(x, y, rr, 0, 2 * Math.PI); cx.setLineDash(dash); cx.lineWidth = w; cx.stroke(); cx.setLineDash([]); };",
        "    // a second channel besides the hue: every class has its own outline",
        "    if (t.ring === 'solid') ring(r + 3, [], 1.5);",
        "    else if (t.ring === 'double') { ring(r + 3, [], 1.5); ring(r + 7, [], 1.5); }",
        "    else if (t.ring === 'dashed') ring(r + 4, [6, 4], 2);",
        "    else if (t.ring === 'thick') ring(r + 4, [], 4);",
        "    else if (t.ring === 'dotted') ring(r + 4, [1.5, 4], 2.5);",
        "    else ring(r + 4, [9, 3, 2, 3], 2);",
        "  }",
        "  for (const [x, y, c] of pts) disc(x, y, rad(c.stack), c);",
        "  disc(mx, my, rad(MAIN.stack), MAIN);",
        "}",
        "addEventListener('resize', draw); draw();",
        "</script></body></html>",
        "");

    // ---- report ------------------------------------------------------------------------------------------------------------------------

    public Fn entryFn() {
        for (String e : new String[]{"__caspien_main", "main"}) {
            if (fns.containsKey(e)) {
                return fns.get(e);
            }
        }
        return null;
    }

    public Result report() {
        for (Fn f : fns.values()) {
            solve(f, new ArrayList<>());
        }
        Result res = new Result("");
        // An event-loop program: the @event_loop function (emitted as `main`) is boilerplate whose loop{} has no bound by design and is not
        // analysed; the program's worst cases are those of its slices, the @with_tick function (the user's main, emitted as `__caspien_main`) and @tick.
        Fn loopFn = null;
        List<Fn> slices = new ArrayList<>();
        for (Fn f : fns.values()) {
            if (f.deco.contains("@event_loop")) {
                loopFn = f;
            }
        }
        for (String d : new String[]{"@with_tick", "@tick"}) {
            for (Fn f : fns.values()) {
                if (f.deco.contains(d) && loopFn != null) {
                    slices.add(f);
                }
            }
        }
        final boolean eventMode = loopFn != null && !slices.isEmpty();
        Fn entry = eventMode ? slices.get(0) : entryFn();
        StringBuilder sb = new StringBuilder();
        if (entry == null) {
            sb.append("\n# worst-case execution cost\n  no entry point found\n");
            res.text = sb.toString();
            return res;
        }
        Set<String> reach = new LinkedHashSet<>();
        List<Fn> roots = new ArrayList<>();      // the entry point (or the event-loop slices) and every thread entry (`par`): each runs on its own stack
        if (eventMode) {
            for (Fn sl : slices) {
                roots.add(sl);
                collect(sl, reach);
            }
        } else {
            roots.add(entry);
            collect(entry, reach);
        }
        for (Fn f : fns.values()) {
            if (f.name.startsWith("__trampoline_")) {
                roots.add(f);
                collect(f, reach);
            }
        }
        final Fn loopFnF = loopFn;
        final Set<String> pinned = new LinkedHashSet<>();
        for (Fn r : roots) {
            if (!r.name.startsWith("__trampoline_")) {
                pinned.add(r.name);
            }
        }
        final java.util.function.Function<String, String> rootLabel = n -> {
            Fn f = fns.get(n);
            if (eventMode && f != null && f.deco.contains("@with_tick")) {
                return n + " (slice: @with_tick)";
            }
            if (eventMode && f != null && f.deco.contains("@tick")) {
                return n + " (slice: @tick)";
            }
            return n + (n.equals(entry.name) ? " (entry)" : n.startsWith("__trampoline_") ? " (thread entry)" : "");
        };
        List<String> order = new ArrayList<>(reach);
        sortedSection(sb, res, order, pinned, rootLabel, GAS, "\n# worst-case execution cost (abstract gas; HOB operations before optimisation, table in docs/COMPILER_REFERENCE.md)\n", "gas");
        for (Fn r : (eventMode ? slices : List.of(entry))) {
            Set<String> eub = r.ub[GAS];
            sb.append("# summary: ").append(r.name).append(" costs ").append(eub.isEmpty() ? "" : "at least ").append(r.val[GAS]).append(" gas")
                    .append(eub.isEmpty() ? " in the worst case (bound)" : " (" + wordOf(worstOf(eub)) + ": " + eub.size() + " reason" + (eub.size() == 1 ? "" : "s") + " above)").append(", ")
                    .append(reach.size()).append(" functions reachable\n");
        }

        // stack depth
        Map<String, Long> frame = new HashMap<>();
        for (String n : reach) {
            frame.put(n, hub.frameBytes(fns.get(n)));
        }
        Map<String, Long> depth = new HashMap<>();
        Map<String, String> via = new HashMap<>();
        Map<String, Set<String>> sub = new HashMap<>();     // unbounded reasons for the stack
        for (String n : reach) {
            hub.stackDepth(n, frame, depth, via, sub, new ArrayList<>());
        }
        final long loopFrame = eventMode ? hub.frameBytes(loopFnF) : 0;
        if (eventMode) {
            for (Fn sl : slices) {
                depth.put(sl.name, depth.get(sl.name) + loopFrame);   // a slice runs below the event loop's own frame
            }
        }
        sb.append("\n# stack depth (an estimate from the bytecode before register allocation: per function 16 bytes for the return address and frame pointer, every local and\n")
                .append("#   argument rounded up to 8 bytes, and an allowance for temporaries and register saves; the call structure is that of the source, optimiser inlining\n")
                .append("#   merges frames and is not modelled; threads started with `par` have their own stack; see docs/COMPILER_REFERENCE.md)\n");
        if (eventMode) {
            sb.append("#   a slice's figure includes the ").append(loopFrame).append(" bytes of the event loop's own frame, which it runs below\n");
        }
        List<String> so = new ArrayList<>(reach);
        so.sort((a, b) -> depth.get(b).compareTo(depth.get(a)));
        int shown = 0;
        for (String n : so) {
            boolean isRoot = roots.stream().anyMatch(r -> r.name.equals(n));
            if (shown++ >= CAP && !isRoot) {
                continue;
            }
            Set<String> why = sub.get(n);
            String label = rootLabel.apply(n);
            String val = (why.isEmpty() ? "" : ">= ") + depth.get(n) + " bytes";
            res.stackByFunction.put(n, (why.isEmpty() ? "" : ">=") + depth.get(n));
            sb.append(String.format("  %-34s %s  %s%n", label, val, tag(why)));
            if (isRoot) {
                StringBuilder path = new StringBuilder("      deepest path: ");
                if (eventMode && pinned.contains(n)) {
                    path.append("event loop (").append(loopFrame).append(") > ");
                }
                String cur = n;
                while (cur != null) {
                    path.append(cur).append(" (").append(frame.get(cur)).append(")");
                    cur = via.get(cur);
                    if (cur != null) {
                        path.append(" > ");
                    }
                }
                sb.append(path).append("\n");
            }
            Fn f = fns.get(n);
            Set<String> ext = new LinkedHashSet<>();
            for (String nm : f.notModelled) {
                if (nm.startsWith("external call")) {
                    ext.add(nm.substring(14));
                }
            }
            if (!ext.isEmpty() && isRoot) {
                sb.append("      not counted: stack used by external calls (").append(String.join(", ", ext)).append(")\n");
            }
        }
        long threadRoots = roots.stream().filter(r -> r.name.startsWith("__trampoline_")).count();
        for (Fn r : (eventMode ? slices : List.of(entry))) {
            sb.append("# summary: ").append(r.name).append(" needs ").append(sub.get(r.name).isEmpty() ? "" : "at least ").append(depth.get(r.name))
                    .append(" bytes of stack").append(sub.get(r.name).isEmpty() ? " (bound)" : " (" + wordOf(worstOf(sub.get(r.name))) + ")").append(" for safe code, ")
                    .append(threadRoots).append(" thread entr").append(threadRoots == 1 ? "y" : "ies").append("\n");
        }

        // heap
        sb.append("\n# heap memory (the most bytes one run can request from the allocator: new, dyn, resize and clone, at their full size, headers included, frees not credited,\n")
                .append("#   so it also bounds the peak live heap; ghost-table growth and the allocator's own overhead are not counted; the number of allocation operations is in brackets)\n");
        List<String> ho = new ArrayList<>(reach);
        ho.sort((a, b) -> fns.get(b).val[HEAP].compareTo(fns.get(a).val[HEAP]));
        shown = 0;
        for (String n : ho) {
            Fn f = fns.get(n);
            if (f.val[COUNT].signum() == 0 && f.ub[COUNT].isEmpty() && !pinned.contains(n)) {
                continue;
            }
            if (shown++ >= CAP && !pinned.contains(n)) {
                continue;
            }
            String val = (f.ub[HEAP].isEmpty() ? "" : ">= ") + f.val[HEAP] + " bytes";
            res.heapByFunction.put(n, (f.ub[HEAP].isEmpty() ? "" : ">=") + f.val[HEAP]);
            res.countByFunction.put(n, (f.ub[COUNT].isEmpty() ? "" : ">=") + f.val[COUNT]);
            sb.append(String.format("  %-34s %s  (%s%d allocation operation%s)%s%n", rootLabel.apply(n).replace(" (thread entry)", ""), val,
                    f.ub[COUNT].isEmpty() ? "" : ">= ", f.val[COUNT], f.val[COUNT].equals(BigInteger.ONE) ? "" : "s",
                    "  " + tag(f.ub[HEAP])));
        }
        for (Fn r : (eventMode ? slices : List.of(entry))) {
            Set<String> hub = r.ub[HEAP];
            sb.append("# summary: ").append(r.name).append(" requests ").append(hub.isEmpty() ? "at most " : "at least ").append(r.val[HEAP]).append(" bytes of heap")
                    .append(hub.isEmpty() ? " (bound)" : " (" + wordOf(worstOf(hub)) + ")").append(" in ").append(r.ub[COUNT].isEmpty() ? "at most " : "at least ").append(r.val[COUNT]).append(" allocation operations\n");
        }
        sb.append("\n# peak live heap (the most bytes that can be live at the same time during one run: frees are credited where they certainly release a block that this function or a callee\n")
                .append("#   made, and every `resize` counts as a moving realloc, the old and the new block live together; a block moved into a struct member, a path-dependent slot or code the\n")
                .append("#   analysis cannot follow is never credited, so the figure stays an upper bound; `leaves` = bytes still live when the function returns, e.g. a block it hands back)\n");
        List<String> lo = new ArrayList<>(reach);
        lo.sort((a, b) -> fns.get(b).livePeak.compareTo(fns.get(a).livePeak));
        shown = 0;
        for (String n : lo) {
            Fn f = fns.get(n);
            res.liveByFunction.put(n, (f.ub[LIVE].isEmpty() ? "" : ">=") + f.livePeak + "/" + f.val[LIVE]);
            if (f.livePeak.signum() == 0 && f.ub[LIVE].isEmpty() && !pinned.contains(n)) {
                continue;
            }
            if (shown++ >= CAP && !pinned.contains(n)) {
                continue;
            }
            sb.append(String.format("  %-34s peak %s%s bytes  (leaves %s%s)%s%n", rootLabel.apply(n).replace(" (thread entry)", ""), f.ub[LIVE].isEmpty() ? "" : ">= ", f.livePeak,
                    f.ub[LIVE].isEmpty() ? "" : ">= ", f.val[LIVE].max(BigInteger.ZERO), "  " + tag(f.ub[LIVE])));
        }
        for (Fn r : (eventMode ? slices : List.of(entry))) {
            sb.append("# summary: ").append(r.name).append(r.ub[LIVE].isEmpty() ? " has at most " : " has at least ").append(r.livePeak).append(" bytes of heap live at once")
                    .append(r.ub[LIVE].isEmpty() ? " (bound)" : " (" + wordOf(worstOf(r.ub[LIVE])) + ")").append("\n");
        }
        if (eventMode) {
            StringBuilder top = new StringBuilder();
            top.append("\n# event loop: the @event_loop function (emitted as `").append(loopFnF.name).append("`) only schedules the slices; it is NOT walked; its termination class is read off the tick (below).\n")
                    .append("#   Worst case of one run of each slice, to completion (stack includes the event loop's own ").append(loopFrame).append("-byte frame; a tick's heap figure is per call):\n");
            for (Fn sl : slices) {
                boolean g = sl.ub[GAS].isEmpty(), h = sl.ub[HEAP].isEmpty(), st = sub.get(sl.name).isEmpty();
                top.append(String.format("  %-34s gas %s%s   stack %s%d bytes   heap %s%s bytes (%s%s allocation operation%s), peak live %s%s bytes%n", rootLabel.apply(sl.name),
                        g ? "" : ">= ", sl.val[GAS], st ? "" : ">= ", depth.get(sl.name), h ? "" : ">= ", sl.val[HEAP],
                        sl.ub[COUNT].isEmpty() ? "" : ">= ", sl.val[COUNT], sl.val[COUNT].equals(BigInteger.ONE) ? "" : "s", sl.ub[LIVE].isEmpty() ? "" : ">= ", sl.livePeak));
            }
            Fn tickFn = null, mainFn = null;
            for (Fn sl : slices) {
                if (sl.deco.contains("@tick")) {
                    tickFn = sl;
                } else if (sl.deco.contains("@with_tick")) {
                    mainFn = sl;
                }
            }
            if (tickFn != null) {
                // The loop itself is not walked; how it can end is decided by the tick (the loop ends only when the tick, or `main` before it, throws).
                top.append("#   The loop ends only when ").append(tickFn.name).append(" throws, so its termination class follows the tick:\n");
                if (!tickFn.deco.contains("@throws")) {
                    top.append("#     loop: none (").append(tickFn.name).append(" never throws, the loop has no exit)\n");
                } else if (hub.canReturn(tickFn)) {
                    top.append("#     loop: unbound (").append(tickFn.name).append(" throws on some paths: the loop runs until a tick throws, and no bound on the number of ticks is known)\n");
                } else {
                    top.append("#     loop: bound (").append(tickFn.name).append(" throws on every path: the loop ends with the first tick, exactly one tick runs)\n");
                }
            }
            if (mainFn != null && mainFn.deco.contains("@throws")) {
                top.append("#   ").append(mainFn.name).append(hub.canReturn(mainFn) ? " may throw before the loop starts (the program then ends with no tick)\n"
                        : " throws on every path: the loop is never entered\n");
            }
            sb.insert(0, top);
        }
        res.text = sb.toString();
        return res;
    }

    public void sortedSection(StringBuilder sb, Result res, List<String> order, Set<String> pinned,
            java.util.function.Function<String, String> label, int mt, String title, String key) {
        sb.append(title);
        order.sort((a, b) -> {
            boolean pa = pinned.contains(a), pb = pinned.contains(b);
            if (pa != pb) {
                return pa ? -1 : 1;
            }
            if (pa) {
                return 0;   // pinned roots keep the order they were given in (stable sort)
            }
            return fns.get(b).val[mt].compareTo(fns.get(a).val[mt]);
        });
        int shown = 0;
        for (String n : order) {
            Fn f = fns.get(n);
            Set<String> ub = f.ub[mt];
            String val = (ub.isEmpty() ? "" : ">= ") + f.val[mt];
            res.gasByFunction.put(n, val);
            if (shown++ >= CAP && !pinned.contains(n)) {
                continue;
            }
            sb.append(String.format("  %-34s %s  %s%n", label.apply(n).replace(" (thread entry)", n.startsWith("__trampoline_") ? " (thread entry)" : ""), val, tag(ub)));
            if (!f.notModelled.isEmpty()) {
                sb.append("      not modelled: ").append(String.join("; ", f.notModelled)).append("\n");
            }
        }
        if (order.size() > CAP + pinned.size()) {
            sb.append("  (+").append(order.size() - CAP - pinned.size()).append(" more reachable functions)\n");
        }
    }

    public void collect(Fn f, Set<String> out) {
        if (!out.add(f.name)) {
            return;
        }
        for (String c : f.calls) {
            Fn cf = fns.get(c);
            if (cf != null) {
                collect(cf, out);
            }
        }
    }

    /** Worst-case gas and heap count of f including callees (callees are solved first and their figures spliced into the CALL lines). */
    public void solve(Fn f, List<String> stack) {
        if (f.done) {
            return;
        }
        if (stack.contains(f.name)) {
            return;
        }
        stack.add(f.name);
        for (int i = f.start; i < f.end; i++) {
            String o = op(ln[i]);
            if (isAlloc(o)) {
                f.sites++;
            }
            if (!o.equals("CALL")) {
                continue;
            }
            String c = arg(ln[i]);
            f.calls.add(c);
            Fn cf = fns.get(c);
            if (cf == null) {
                f.notModelled.add("call to " + c + " (no body)");
                continue;
            }
            if (!stack.contains(c)) {
                solve(cf, stack);
            }
        }
        hub.mapOkLabels(f);
        hub.analyzeCredits(f);
        runEvals(f, new HashMap<>(), stack);
        f.done = true;
        stack.remove(stack.size() - 1);
    }

    /**
     * The figures of f under the parameter values `args` (empty = nothing known): picks for every call the callee to use (a callee that is
     * unbounded without argument values is solved again with the values this call passes, when they are known), then walks the paths.
     */
    public void runEvals(Fn f, Map<String, V> args, List<String> stack) {
        f.args = args;
        f.calleeAt.clear();
        @SuppressWarnings("unchecked")
        Set<String>[] callUb = new Set[]{new LinkedHashSet<String>(), new LinkedHashSet<String>(), new LinkedHashSet<String>(), new LinkedHashSet<String>()};
        for (int i = f.start; i < f.end; i++) {
            if (!op(ln[i]).equals("CALL")) {
                continue;
            }
            String c = arg(ln[i]);
            Fn cf = fns.get(c);
            if (cf == null) {
                continue;
            }
            if (stack.contains(c)) {
                // The type checker rejects every call cycle (only the @recursive tail self-call survives, and it is a loop by now).
                throw new IllegalStateException("audit: call cycle through " + c + " in a program that type-checked (compiler bug)");
            }
            Fn use = cf;
            if (unboundedAnywhere(cf) && specs.size() < MAX_SPECS) {
                Map<String, V> cc = hub.callArgs(f, i, args, cf);
                if (!cc.isEmpty()) {
                    String key = c + "|" + new TreeMap<>(cc);
                    use = specs.get(key);
                    if (use == null) {
                        use = cf.specialised();
                        specs.put(key, use);
                        stack.add(c);
                        runEvals(use, cc, stack);
                        stack.remove(stack.size() - 1);
                    }
                }
            }
            f.calleeAt.put(i, use);
            for (int m = 0; m < NMET; m++) {
                if (!use.ub[m].isEmpty()) {
                    callUb[m].add(callReason(c, use.ub[m], hub.runsFirst(f, i)));
                }
            }
        }
        for (int m = 0; m < NMET; m++) {
            f.ub[m].clear();
        }
        for (int m = 0; m < NMET; m++) {
            Val v = new Eval(f, m).walk(f.start + 1, -1, ALL);
            f.val[m] = v.neg || v.n.compareTo(BigInteger.TEN.pow(20).negate()) < 0 ? BigInteger.ZERO : v.n;
            if (m == LIVE) {
                f.livePeak = v.neg ? BigInteger.ZERO : v.p;
            }
            f.ub[m].addAll(callUb[m]);
        }
    }
}

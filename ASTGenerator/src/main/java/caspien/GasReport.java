package caspien;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 *     loop (`loop {}`, a range with a variable bound, which includes the range argument of a `@recursive` function) is UNBOUNDED, and the
 *     figure shown is then a lower bound (one iteration) tagged with the reason;</li>
 * <li>a call costs {@link #CALL_COST} plus the callee's worst case; unbounded callees make the caller unbounded;</li>
 * <li>external calls, inline assembly, indirect calls, `memcopy` sizes, sleeping and thread operations are charged a fixed amount and listed as
 *     NOT MODELLED (their real cost is outside the compiler's knowledge).</li>
 * </ul>
 * The loop bounds are literals only: no value tracking, and a callee is costed once for all callers (its own variable bounds stay unbounded).
 */
final class GasReport {
    static final int CALL_COST = 5;
    /** Lines per section before "(+n more)"; the env var CASPIEN_AUDIT_ALL=1 (used by the tests) lifts the limit. */
    private static final int CAP = "1".equals(System.getenv("CASPIEN_AUDIT_ALL")) ? Integer.MAX_VALUE - 2 : 40;
    private static final int GAS = 0, HEAP = 1, COUNT = 2;     // the path metrics: gas, heap bytes requested, number of allocation operations

    private static final class Fn {
        String name;
        int start, end;                       // line indexes: FUNC_START line .. FUNC_END line
        Map<String, Integer> labels = new HashMap<>();
        final BigInteger[] val = new BigInteger[3];                                   // per metric: GAS, HEAP (bytes), COUNT
        @SuppressWarnings("unchecked")
        final Set<String>[] ub = new Set[]{new LinkedHashSet<String>(), new LinkedHashSet<String>(), new LinkedHashSet<String>()};   // unbounded reasons per metric
        int sites;                            // allocation operations written in the body
        Set<String> notModelled = new LinkedHashSet<>();
        Set<String> calls = new LinkedHashSet<>();
        boolean busy;
        boolean done;
        boolean throwsDecorated;
        final Set<String> deco = new HashSet<>();   // FUNC_DECORATE names (@event_loop, @with_tick, @tick, ...)
    }

    private final String[] ln;                // trimmed non-empty lines
    private final Map<String, Fn> fns = new LinkedHashMap<>();

    private GasReport(String hob) {
        List<String> l = new ArrayList<>();
        for (String s : hob.split("\n")) {
            s = s.trim();
            if (!s.isEmpty()) {
                l.add(s);
            }
        }
        ln = l.toArray(new String[0]);
        Fn cur = null;
        for (int i = 0; i < ln.length; i++) {
            if (ln[i].startsWith("FUNC_START ")) {
                cur = new Fn();
                cur.name = ln[i].substring(11).trim();
                cur.start = i;
                fns.put(cur.name, cur);
            } else if (ln[i].equals("FUNC_END") && cur != null) {
                cur.end = i;
                cur = null;
            } else if (cur != null && ln[i].startsWith("@") && ln[i].endsWith(":")) {
                cur.labels.put(ln[i].substring(0, ln[i].length() - 1), i);
            } else if (cur != null && ln[i].startsWith("FUNC_DECORATE ")) {
                cur.deco.add(arg(ln[i]));
            }
        }
    }

    static final class Result {
        String text;
        final Map<String, String> gasByFunction = new LinkedHashMap<>();   // for tests: name -> "123" or ">=123"
        final Map<String, String> stackByFunction = new LinkedHashMap<>();
        final Map<String, String> heapByFunction = new LinkedHashMap<>();
        final Map<String, String> countByFunction = new LinkedHashMap<>();
        Result(String text) {
            this.text = text;
        }
    }

    /** Builds the report section for the program whose HOB is given; `entry` functions are `main` and `__caspien_main`. */
    static Result build(String hob) {
        GasReport g = new GasReport(hob);
        final Result[] out = new Result[1];
        Thread t = new Thread(null, () -> out[0] = g.report(), "gas", 1L << 29);   // deep recursion over long functions
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
    static int cost(String op) {
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
            case "MEMCOPY": case "GT_REGISTER": case "GT_ALIVE_CHECK":
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

    private static String op(String line) {
        int sp = line.indexOf(' ');
        return sp < 0 ? line : line.substring(0, sp);
    }

    private static String arg(String line) {
        int sp = line.indexOf(' ');
        if (sp < 0) {
            return "";
        }
        String r = line.substring(sp + 1).trim();
        int sp2 = r.indexOf(' ');
        return sp2 < 0 ? r : r.substring(0, sp2);
    }

    // ---- analysis ----------------------------------------------------------------------------------------------------------------------

    private static final BigInteger NEG = BigInteger.ONE.shiftLeft(200).negate();   // "no such path"
    private static final int CONT = 0, BRK = 1, RET = 2, ALL = 3;                     // which kind of path a walk looks for

    private static BigInteger add(BigInteger a, BigInteger b) {
        return a.signum() < 0 || b.signum() < 0 ? NEG : a.add(b);
    }

    private static final class Loop {
        int head, end, back;        // label line, exit label line, the unconditional JMP back to the head
        BigInteger count;           // null = not a literal bound
        String why;
        boolean isFor;
        BigInteger exitCost;        // cost of the paths that leave the loop and continue after it (add the tail); NEG impossible
        BigInteger retCost = NEG;   // cost of the paths that return/throw/exit from inside the loop (no tail)
        boolean done, computing;
    }

    /** Per-function analysis state. */
    private final class Eval {
        final Fn f;
        final int mt;                                          // GAS, HEAP or COUNT
        final Map<Integer, Loop> loops = new HashMap<>();      // head line -> loop
        final Map<Long, BigInteger[]> memo = new HashMap<>();  // (context, mode) -> per-line memo

        Eval(Fn f, int mt) {
            this.f = f;
            this.mt = mt;
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
                if (!lp.isFor) {
                    lp.why = "unbounded `loop` (" + lab + ")";
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
                    lp.count = c;
                    if (c == null) {
                        lp.why = "`for` bound is not a literal (" + (type == null ? lab : type) + ")";
                    }
                }
                loops.put(lp.head, lp);
            }
        }

        /** Costs of a whole loop statement: exitCost (normal exit or `break`; the code after the loop is added by the caller) and retCost (a `return` inside). */
        void solveLoop(Loop lp) {
            if (lp.done || lp.computing) {
                return;
            }
            lp.computing = true;
            BigInteger cont = walk(lp.head + 1, lp.head, CONT);   // header + body up to the back edge
            BigInteger brk = walk(lp.head + 1, lp.head, BRK);     // ... up to a `break` / the final header test
            BigInteger ret = walk(lp.head + 1, lp.head, RET);     // ... up to a return/throw/exit
            BigInteger hdr = lp.isFor ? headerCost(lp) : BigInteger.ZERO;
            BigInteger n;
            if (lp.count != null) {
                n = lp.count;
            } else {
                if (mt == GAS || cont.signum() > 0) {     // an allocation count does not depend on the bound of a loop that allocates nothing
                    f.ub[mt].add(lp.why);
                }
                n = BigInteger.ONE;        // lower bound: one iteration
            }
            if (n.signum() == 0) {
                lp.exitCost = hdr;
            } else {
                BigInteger nm1 = n.subtract(BigInteger.ONE);
                BigInteger full = lp.isFor || cont.signum() >= 0 ? add(n.multiply(cont.signum() < 0 ? BigInteger.ZERO : cont), hdr) : NEG;
                if (!lp.isFor) {
                    full = brk.signum() < 0 ? cont : NEG;   // a `loop` only ends through `break`
                }
                lp.exitCost = max(full, brk.signum() < 0 ? NEG : add(nm1.multiply(cont.signum() < 0 ? BigInteger.ZERO : cont), brk));
                if (lp.exitCost.signum() < 0) {
                    lp.exitCost = cont.signum() < 0 ? BigInteger.ZERO : cont;
                }
                lp.retCost = ret.signum() < 0 ? NEG : add(nm1.multiply(cont.signum() < 0 ? BigInteger.ZERO : cont), ret);
            }
            lp.done = true;
            lp.computing = false;
        }

        BigInteger headerCost(Loop lp) {
            BigInteger c = BigInteger.ZERO;
            for (int i = lp.head + 1; i < lp.back; i++) {
                String o = op(ln[i]);
                c = c.add(lineCost(i));
                if (o.equals("JMP") && ln[i - 1].equals("CMP")) {
                    break;
                }
            }
            return c;
        }

        BigInteger walk(int i, int ctx, int mode) {
            BigInteger[] m = memo.computeIfAbsent(((long) ctx << 3) | mode, k -> new BigInteger[ln.length + 1]);
            return walkMemo(i, ctx, mode, m);
        }

        private BigInteger max(BigInteger a, BigInteger b) {
            return a.compareTo(b) >= 0 ? a : b;
        }

        /** Terminal value of reaching line i in a loop context, or null when i is an ordinary line. */
        private BigInteger terminal(int i, int ctx, int mode) {
            if (ctx < 0) {
                return null;
            }
            Loop cl = loops.get(ctx);
            if (i == cl.back) {
                return mode == CONT ? lineCost(i) : NEG;
            }
            if (i == cl.end) {
                return mode == BRK ? BigInteger.ZERO : NEG;
            }
            return null;
        }

        private BigInteger walkMemo(int start, int ctx, int mode, BigInteger[] m) {
            List<Integer> run = new ArrayList<>();
            int i = start;
            BigInteger tail;
            boolean asm = false;
            while (true) {
                if (i >= f.end) {
                    tail = ctx < 0 ? BigInteger.ZERO : NEG;
                    break;
                }
                if (m[i] != null) {
                    tail = m[i];
                    break;
                }
                BigInteger term = terminal(i, ctx, mode);
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
                if (line.startsWith("@") && line.endsWith(":")) {
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
                    BigInteger a = walkTarget(t, ctx, mode);
                    BigInteger b = cond ? walk(i + 1, ctx, mode) : NEG;
                    run.add(i);
                    tail = max(a, b);
                    break;
                }
                if (o.equals("RET") || o.equals("THROW") || o.equals("EXIT") || o.equals("EXIT_THREAD")) {
                    run.add(i);
                    tail = ctx < 0 || mode == RET ? BigInteger.ZERO : NEG;
                    break;
                }
                if (o.equals("PUSH_LABEL")) {
                    Integer t = f.labels.get(arg(line));
                    if (t != null && !arg(line).startsWith("@for") && !arg(line).startsWith("@loop")) {
                        // a call after this point may unwind to that catch block / landing pad: its cost is an alternative continuation
                        BigInteger alt = walkTarget(t, ctx, mode);
                        BigInteger nxt = walk(i + 1, ctx, mode);
                        run.add(i);
                        tail = max(alt, nxt);
                        break;
                    }
                }
                run.add(i);
                i++;
            }
            BigInteger acc = tail;
            for (int k = run.size() - 1; k >= 0; k--) {
                int idx = run.get(k);
                acc = add(acc, lineCost(idx));
                m[idx] = acc;
            }
            return run.isEmpty() ? tail : m[run.get(0)];
        }

        /** A whole inner loop met while walking: its exit continues after the loop; a return inside it is a terminal path. */
        private BigInteger block(Loop lp, int ctx, int mode) {
            solveLoop(lp);
            BigInteger r = add(lp.exitCost, walk(lp.end, ctx, mode));
            if (lp.retCost.signum() >= 0 && (ctx < 0 || mode == RET)) {
                r = max(r, lp.retCost);
            }
            return r;
        }

        BigInteger walkTarget(int target, int ctx, int mode) {
            BigInteger term = terminal(target, ctx, mode);
            if (term != null) {
                return term;
            }
            if (ctx >= 0 && target == loops.get(ctx).head) {
                return mode == CONT ? BigInteger.ZERO : NEG;     // `continue`: the iteration is over
            }
            Loop lp = loops.get(target);
            if (lp != null && target != ctx) {
                return block(lp, ctx, mode);
            }
            return walk(target, ctx, mode);
        }

        BigInteger lineCost(int idx) {
            String line = ln[idx];
            String o = op(line);
            if (line.startsWith("@")) {
                return BigInteger.ZERO;
            }
            BigInteger c = mt == GAS ? BigInteger.valueOf(cost(o)) : !isAlloc(o) ? BigInteger.ZERO : mt == COUNT ? BigInteger.ONE : allocBytes(idx, f);
            switch (o) {
                case "CALL": {
                    f.calls.add(arg(line));
                    Fn cf = fns.get(arg(line));
                    if (cf != null && cf.val[mt] != null) {
                        return c.add(cf.val[mt]);
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
                    f.ub[mt].add("indirect call (INVOKE)");
                    break;
                default:
                    break;
            }
            return c;
        }
    }

    /** Operations that ask the allocator for memory (a `resize` counts as one: it may grow in place or move). */
    static boolean isAlloc(String o) {
        switch (o) {
            case "NEW": case "NEW_DYN": case "NEW_UDYN": case "NEW_FROM_STRING": case "NEW_FROM_USTRING": case "RESIZE": case "URESIZE":
            case "CLONE": case "CLONE_DYN":
                return true;
            default:
                return false;
        }
    }


    // ---- report ------------------------------------------------------------------------------------------------------------------------

    private Fn entryFn() {
        for (String e : new String[]{"__caspien_main", "main"}) {
            if (fns.containsKey(e)) {
                return fns.get(e);
            }
        }
        return null;
    }

    private Result report() {
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
                    .append(eub.isEmpty() ? " in the worst case" : " (no upper bound: " + eub.size() + " reason" + (eub.size() == 1 ? "" : "s") + " above)").append(", ")
                    .append(reach.size()).append(" functions reachable\n");
        }

        // stack depth
        Map<String, Long> frame = new HashMap<>();
        for (String n : reach) {
            frame.put(n, frameBytes(fns.get(n)));
        }
        Map<String, Long> depth = new HashMap<>();
        Map<String, String> via = new HashMap<>();
        Map<String, Set<String>> sub = new HashMap<>();     // unbounded reasons for the stack
        for (String n : reach) {
            stackDepth(n, frame, depth, via, sub, new ArrayList<>());
        }
        final long loopFrame = eventMode ? frameBytes(loopFnF) : 0;
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
            sb.append(String.format("  %-34s %s%s%n", label, val, why.isEmpty() ? "" : "  UNBOUNDED: " + String.join("; ", why)));
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
                    .append(" bytes of stack").append(sub.get(r.name).isEmpty() ? "" : " (no upper bound)").append(" for safe code, ")
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
                    f.ub[HEAP].isEmpty() ? "" : "  UNBOUNDED: " + String.join("; ", f.ub[HEAP])));
        }
        for (Fn r : (eventMode ? slices : List.of(entry))) {
            Set<String> hub = r.ub[HEAP];
            sb.append("# summary: ").append(r.name).append(" requests ").append(hub.isEmpty() ? "at most " : "at least ").append(r.val[HEAP]).append(" bytes of heap")
                    .append(hub.isEmpty() ? "" : " (no upper bound)").append(" in ").append(r.ub[COUNT].isEmpty() ? "at most " : "at least ").append(r.val[COUNT]).append(" allocation operations\n");
        }
        if (eventMode) {
            StringBuilder top = new StringBuilder();
            top.append("\n# event loop: the @event_loop function (emitted as `").append(loopFnF.name).append("`) only schedules the slices; its loop{} has no bound by design and it is NOT analysed.\n")
                    .append("#   Worst case of one run of each slice, to completion (stack includes the event loop's own ").append(loopFrame).append("-byte frame; a tick's heap figure is per call):\n");
            for (Fn sl : slices) {
                boolean g = sl.ub[GAS].isEmpty(), h = sl.ub[HEAP].isEmpty(), st = sub.get(sl.name).isEmpty();
                top.append(String.format("  %-34s gas %s%s   stack %s%d bytes   heap %s%s bytes (%s%s allocation operation%s)%n", rootLabel.apply(sl.name),
                        g ? "" : ">= ", sl.val[GAS], st ? "" : ">= ", depth.get(sl.name), h ? "" : ">= ", sl.val[HEAP],
                        sl.ub[COUNT].isEmpty() ? "" : ">= ", sl.val[COUNT], sl.val[COUNT].equals(BigInteger.ONE) ? "" : "s"));
            }
            sb.insert(0, top);
        }
        res.text = sb.toString();
        return res;
    }

    private void sortedSection(StringBuilder sb, Result res, List<String> order, Set<String> pinned,
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
            sb.append(String.format("  %-34s %s%s%n", label.apply(n).replace(" (thread entry)", n.startsWith("__trampoline_") ? " (thread entry)" : ""), val, ub.isEmpty() ? "" : "  UNBOUNDED: " + String.join("; ", ub)));
            if (!f.notModelled.isEmpty()) {
                sb.append("      not modelled: ").append(String.join("; ", f.notModelled)).append("\n");
            }
        }
        if (order.size() > CAP + pinned.size()) {
            sb.append("  (+").append(order.size() - CAP - pinned.size()).append(" more reachable functions)\n");
        }
    }

    private void collect(Fn f, Set<String> out) {
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
    private void solve(Fn f, List<String> stack) {
        if (f.done) {
            return;
        }
        if (stack.contains(f.name)) {
            return;
        }
        stack.add(f.name);
        @SuppressWarnings("unchecked")
        Set<String>[] callUb = new Set[]{new LinkedHashSet<String>(), new LinkedHashSet<String>(), new LinkedHashSet<String>()};
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
            if (stack.contains(c)) {
                for (int m = 0; m < 3; m++) {
                    callUb[m].add("recursion through " + c);
                }
                continue;
            }
            solve(cf, stack);
            for (int m = 0; m < 3; m++) {
                if (!cf.ub[m].isEmpty()) {
                    callUb[m].add("calls " + c + " (unbounded)");
                }
            }
        }
        for (int m = 0; m < 3; m++) {
            f.ub[m].clear();
        }
        for (int m = 0; m < 3; m++) {
            f.val[m] = new Eval(f, m).walk(f.start + 1, -1, ALL);
            f.ub[m].addAll(callUb[m]);
        }
        f.done = true;
        stack.remove(stack.size() - 1);
    }

    // ---- stack depth -------------------------------------------------------------------------------------------------------------------

    private Map<String, Long> structSize;
    private Map<String, List<String>> structMembers = new HashMap<>();
    private Map<String, Long> sizeCache = new HashMap<>();

    private void parseStructs() {
        structSize = new HashMap<>();
        String cur = null;
        long total = 0;
        for (String l : ln) {
            if (l.startsWith("STRUCT_START ")) {
                cur = l.substring(13).trim();
                total = 0;
                structMembers.put(cur, new ArrayList<>());
            } else if (cur != null && l.startsWith("STRUCT_MEMBER ")) {
                String[] p = l.split(" ");
                structMembers.get(cur).add(p[p.length - 1]);
                total += (sizeOf(p[p.length - 1]) + 7) / 8 * 8;
            } else if (cur != null && l.startsWith("STRUCT_PADDING ")) {
                total += Long.parseLong(l.substring(15).trim());
            } else if (l.equals("STRUCT_END") && cur != null) {
                structSize.put(cur, Math.max(8, (total + 7) / 8 * 8));
                cur = null;
            }
        }
    }

    /** Size in bytes of a value of HOB type `t` (an estimate for anything not obviously sized: 8). */
    private long sizeOf(String t) {
        if (structSize == null) {
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
        Long ss = structSize.get(x);
        return ss != null ? ss : 8;
    }

    // ---- heap bytes --------------------------------------------------------------------------------------------------------------------

    private static final int DYN_HEADER = 16;     // safe dynarray header: [len][cap]

    /** The element type of `...dynarray(T)` (balanced parentheses), or null. */
    private static String dynElem(String t) {
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

    private static String pointee(String t) {
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

    /** A literal integer pushed by the line at idx (`PUSH 12 type`), or null. */
    private BigInteger literalAt(int idx) {
        if (idx < 0 || !ln[idx].startsWith("PUSH ")) {
            return null;
        }
        String[] p = ln[idx].split(" ");
        return p.length >= 2 && p[1].matches("\\d+") ? new BigInteger(p[1]) : null;
    }

    /** Bytes of a deep clone of a block of type `t` (a struct: its own size plus every owned member's block; a dynarray: runtime size, null). */
    private BigInteger deepBytes(String t, int depth) {
        if (depth > 8) {
            return null;
        }
        String e = dynElem(t);
        if (e != null) {
            return null;
        }
        BigInteger total = BigInteger.valueOf((sizeOf(t) + 7) / 8 * 8);
        List<String> ms = structMembers.get(t);
        if (ms != null) {
            for (String m : ms) {
                if (m.contains("owns_")) {
                    String pt = pointee(m);
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

    /** Bytes requested by the allocation operation at line idx (a lower bound plus an UNBOUNDED reason when the size is only known at run time). */
    private BigInteger allocBytes(int idx, Fn f) {
        String line = ln[idx];
        String[] p = line.split(" ");
        String o = p[0];
        if (structSize == null) {
            parseStructs();
        }
        switch (o) {
            case "NEW": {
                return BigInteger.valueOf((sizeOf(p[1]) + 7) / 8 * 8);
            }
            case "NEW_DYN": {
                String e = dynElem(p[1]);
                long es = e == null ? 8 : (sizeOf(e) + 7) / 8 * 8;
                return BigInteger.valueOf(DYN_HEADER).add(BigInteger.valueOf(es).multiply(new BigInteger(p[p.length - 1])));
            }
            case "NEW_UDYN": {
                String e = dynElem(p[1]);
                long es = e == null ? 8 : (sizeOf(e) + 7) / 8 * 8;
                return BigInteger.valueOf(es).multiply(new BigInteger(p[p.length - 1])).max(BigInteger.ONE);
            }
            case "NEW_FROM_STRING": case "NEW_FROM_USTRING": {
                // the text literal pushed just before: STRING <id> "text"
                String id = idx > 0 && ln[idx - 1].startsWith("PUSH string_id") ? ln[idx - 1].split(" ")[1] : null;
                Integer len = id == null ? null : stringLength(id);
                if (len == null) {
                    f.ub[HEAP].add("text length of a `dyn(...)` is not a literal");
                    return BigInteger.valueOf(DYN_HEADER);
                }
                return BigInteger.valueOf(o.equals("NEW_FROM_STRING") ? DYN_HEADER + len : Math.max(1, len + 1));
            }
            case "RESIZE": {
                String e = dynElem(p[1]);
                long es = e == null ? 8 : (sizeOf(e) + 7) / 8 * 8;
                BigInteger n = literalAt(idx - 2);          // source, count, fill: the count is the second-last single push
                if (n == null || !ln[idx - 1].startsWith("PUSH ")) {
                    f.ub[HEAP].add("`resize` count is not a literal");
                    return BigInteger.valueOf(DYN_HEADER);
                }
                return BigInteger.valueOf(DYN_HEADER).add(BigInteger.valueOf(es).multiply(n));
            }
            case "URESIZE": {
                String e = dynElem(p[1]);
                long es = e == null ? 8 : (sizeOf(e) + 7) / 8 * 8;
                BigInteger n = literalAt(idx - 1);
                if (n == null) {
                    f.ub[HEAP].add("unsafe `resize` count is not a literal");
                    return BigInteger.ONE;
                }
                return BigInteger.valueOf(es).multiply(n).max(BigInteger.ONE);
            }
            case "CLONE": {
                String pt = pointee(p[1]);
                BigInteger d = pt == null || p[1].contains("dynarray") ? null : deepBytes(pt, 0);
                if (d == null) {
                    f.ub[HEAP].add("`clone` of a block whose size is only known at run time");
                    return BigInteger.valueOf(DYN_HEADER);
                }
                return d;
            }
            default:
                f.ub[HEAP].add("`" + o.toLowerCase() + "` size is only known at run time");
                return BigInteger.valueOf(DYN_HEADER);
        }
    }

    /** Byte length of the string literal `id` (escapes \n \t \r \0 \\ \" counted as one byte), or null when unknown. */
    private Integer stringLength(String id) {
        for (String l : ln) {
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
    private long frameBytes(Fn f) {
        long total = 16;
        for (int i = f.start; i < f.end; i++) {
            String l = ln[i];
            if (l.startsWith("ALLOC ") || l.startsWith("ARG ") || l.startsWith("ALLOC_STATIC ")) {
                String[] p = l.split(" ");
                total += (sizeOf(p[p.length - 1]) + 7) / 8 * 8;
            }
        }
        long lines = f.end - f.start + 1;
        return total + 64 + 8 * ((lines + 23) / 24);
    }

    private long stackDepth(String n, Map<String, Long> frame, Map<String, Long> depth, Map<String, String> via, Map<String, Set<String>> why, List<String> path) {
        if (depth.containsKey(n)) {
            return depth.get(n);
        }
        Fn f = fns.get(n);
        Set<String> w = new LinkedHashSet<>();
        if (path.contains(n)) {
            return 0;
        }
        path.add(n);
        long best = 0;
        String bestVia = null;
        for (String c : f.calls) {
            Fn cf = fns.get(c);
            if (cf == null) {
                continue;
            }
            if (path.contains(c)) {
                w.add("recursion through " + c);
                continue;
            }
            frame.putIfAbsent(c, frameBytes(cf));
            long d = stackDepth(c, frame, depth, via, why, path);
            if (!why.get(c).isEmpty()) {
                w.add("calls " + c + " (unbounded)");
            }
            if (d > best || bestVia == null) {
                best = d;
                bestVia = c;
            }
        }
        for (int i = f.start; i < f.end; i++) {
            if (op(ln[i]).equals("INVOKE")) {
                w.add("indirect call (INVOKE)");
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

    // ---- visualizer (--viz) -------------------------------------------------------------------------------------------------------------

    /** The HTML page of `--viz`: the entry function as a central circle and the functions it calls on a ring around it, every circle sized by the
     *  worst-case stack depth (the same figure as the stack section: frame + deepest callee) of its function. */
    static String viz(String hob, String title) {
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

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\"";
    }

    private String vizPage(String title) {
        Fn entry = entryFn();
        if (entry == null) {
            return null;
        }
        for (Fn f : fns.values()) {
            solve(f, new ArrayList<>());
        }
        parseStructs();
        Map<String, Long> frame = new HashMap<>(), depth = new HashMap<>();
        Map<String, String> via = new HashMap<>();
        Map<String, Set<String>> why = new HashMap<>();
        frame.put(entry.name, frameBytes(entry));
        stackDepth(entry.name, frame, depth, via, why, new ArrayList<>());
        StringBuilder nodes = new StringBuilder();
        nodes.append("{\"name\":").append(jsonStr(entry.name)).append(",\"stack\":").append(depth.get(entry.name)).append("}");
        StringBuilder callees = new StringBuilder();
        for (String c : entry.calls) {
            Fn cf = fns.get(c);
            if (cf == null || c.equals(entry.name)) {
                continue;
            }
            if (callees.length() > 0) {
                callees.append(",");
            }
            callees.append("{\"name\":").append(jsonStr(c)).append(",\"stack\":").append(depth.get(c)).append("}");
        }
        return VIZ_TEMPLATE.replace("@@TITLE@@", title.replace("<", "&lt;")).replace("@@MAIN@@", nodes).replace("@@CALLEES@@", callees);
    }

    private static final String VIZ_TEMPLATE = String.join("\n",
        "<!doctype html>",
        "<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
        "<title>@@TITLE@@ (Caspien viz)</title>",
        "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}canvas{display:block;width:100vw;height:100vh;background:#000}</style>",
        "</head><body><canvas id=\"c\"></canvas><script>",
        "// circle area is proportional to the worst-case stack depth in bytes (frame + deepest callee) of the function",
        "const MAIN = @@MAIN@@;",
        "const CALLEES = [@@CALLEES@@];",
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
        "    k = Math.min(k, 0.95 * R / (Math.sqrt(MAIN.stack) + maxC));",
        "    if (n > 1) k = Math.min(k, 0.95 * R * Math.sin(Math.PI / n) / maxC);",
        "  }",
        "  const rad = s => Math.max(3, k * Math.sqrt(s));",
        "  cx.strokeStyle = '#fff'; cx.fillStyle = '#fff'; cx.lineWidth = 1;",
        "  if (n > 0) { cx.beginPath(); cx.arc(mx, my, R, 0, 2 * Math.PI); cx.stroke(); }",
        "  const pts = CALLEES.map((c, i) => { const a = -Math.PI / 2 + 2 * Math.PI * i / n; return [mx + R * Math.cos(a), my + R * Math.sin(a), c]; });",
        "  for (const [x, y] of pts) { cx.beginPath(); cx.moveTo(mx, my); cx.lineTo(x, y); cx.stroke(); }",
        "  for (const [x, y, c] of pts) { cx.beginPath(); cx.arc(x, y, rad(c.stack), 0, 2 * Math.PI); cx.fill(); }",
        "  cx.beginPath(); cx.arc(mx, my, rad(MAIN.stack), 0, 2 * Math.PI); cx.fill();",
        "}",
        "addEventListener('resize', draw); draw();",
        "</script></body></html>",
        "");
}

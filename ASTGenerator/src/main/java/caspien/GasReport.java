package caspien;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
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

    private static final class Fn {
        String name;
        int start, end;                       // line indexes: FUNC_START line .. FUNC_END line
        Map<String, Integer> labels = new HashMap<>();
        BigInteger gas;
        Set<String> unbounded = new LinkedHashSet<>();   // reasons
        Set<String> notModelled = new LinkedHashSet<>();
        Set<String> calls = new LinkedHashSet<>();
        boolean busy;
        boolean done;
        boolean throwsDecorated;
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
            }
        }
    }

    static final class Result {
        String text;
        final Map<String, String> gasByFunction = new LinkedHashMap<>();   // for tests: name -> "123" or ">=123"
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
        final Map<Integer, Loop> loops = new HashMap<>();      // head line -> loop
        final Map<Long, BigInteger[]> memo = new HashMap<>();  // (context, mode) -> per-line memo

        Eval(Fn f) {
            this.f = f;
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
                f.unbounded.add(lp.why);
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
            BigInteger c = BigInteger.valueOf(cost(o));
            switch (o) {
                case "CALL": {
                    f.calls.add(arg(line));
                    BigInteger cg = callGas.get(arg(line));
                    if (cg != null) {
                        return c.add(cg);
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
                    f.unbounded.add("indirect call (INVOKE)");
                    break;
                default:
                    break;
            }
            return c;
        }
    }

    private Result report() {
        // resolve every function with callee costs spliced into `cost`: do it by a second pass keyed on CALL targets
        Map<String, BigInteger> g = new LinkedHashMap<>();
        Map<String, Set<String>> why = new HashMap<>();
        for (Fn f : fns.values()) {
            solve(f, g, why, new ArrayList<>());
        }
        StringBuilder sb = new StringBuilder();
        Result res = new Result("");
        String[] entries = {"__caspien_main", "main"};
        Fn entry = null;
        for (String e : entries) {
            if (fns.containsKey(e)) {
                entry = fns.get(e);
                break;
            }
        }
        Set<String> reach = new LinkedHashSet<>();
        if (entry != null) {
            collect(entry, reach);
        }
        sb.append("\n# worst-case execution cost (abstract gas; HOB operations before optimisation, table in docs/COMPILER_REFERENCE.md)\n");
        if (entry == null) {
            sb.append("  no entry point found\n");
            res.text = sb.toString();
            return res;
        }
        List<String> order = new ArrayList<>(reach);
        final String entryName = entry.name;
        order.sort((a, b) -> {
            if (a.equals(entryName)) {
                return -1;
            }
            if (b.equals(entryName)) {
                return 1;
            }
            return g.get(b).compareTo(g.get(a));
        });
        int shown = 0;
        for (String n : order) {
            Fn f = fns.get(n);
            Set<String> ub = why.get(n);
            String val = (ub.isEmpty() ? "" : ">= ") + g.get(n).toString();
            res.gasByFunction.put(n, val);
            if (shown++ >= 40 && !n.equals(entry.name)) {
                continue;
            }
            sb.append(String.format("  %-34s %s%s%n", n + (n.equals(entry.name) ? " (entry)" : ""), val, ub.isEmpty() ? "" : "  UNBOUNDED: " + String.join("; ", ub)));
            if (!f.notModelled.isEmpty()) {
                sb.append("      not modelled: ").append(String.join("; ", f.notModelled)).append("\n");
            }
        }
        if (order.size() > 41) {
            sb.append("  (+").append(order.size() - 41).append(" more reachable functions)\n");
        }
        Set<String> eub = why.get(entry.name);
        sb.append("# summary: ").append(entry.name).append(" costs ").append(eub.isEmpty() ? "" : "at least ").append(g.get(entry.name)).append(" gas")
                .append(eub.isEmpty() ? " in the worst case" : " (no upper bound: " + eub.size() + " reason" + (eub.size() == 1 ? "" : "s") + " above)").append(", ")
                .append(reach.size()).append(" functions reachable\n");
        res.text = sb.toString();
        return res;
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

    /** Worst-case gas of f including callees (memoised in g). The callee gas is added per CALL on the worst path by re-evaluating with callee costs. */
    private BigInteger solve(Fn f, Map<String, BigInteger> g, Map<String, Set<String>> why, List<String> stack) {
        if (g.containsKey(f.name) && why.containsKey(f.name)) {
            return g.get(f.name);
        }
        if (stack.contains(f.name)) {
            return BigInteger.ZERO;
        }
        stack.add(f.name);
        // first make sure the callees are solved (their gas feeds the CALL lines)
        List<String> calleeNames = new ArrayList<>();
        for (int i = f.start; i < f.end; i++) {
            if (op(ln[i]).equals("CALL")) {
                calleeNames.add(arg(ln[i]));
            }
        }
        Set<String> ub = new LinkedHashSet<>();
        for (String c : calleeNames) {
            Fn cf = fns.get(c);
            if (cf == null) {
                f.notModelled.add("call to " + c + " (no body)");
                continue;
            }
            if (stack.contains(c)) {
                ub.add("recursion through " + c);
                continue;
            }
            solve(cf, g, why, stack);
            if (!why.get(c).isEmpty()) {
                ub.add("calls " + c + " (unbounded)");
            }
        }
        callGas = g;
        f.unbounded.clear();
        Eval ev = new Eval(f);
        BigInteger own = ev.walk(f.start + 1, -1, ALL);
        ub.addAll(f.unbounded);
        g.put(f.name, own);
        why.put(f.name, ub);
        stack.remove(stack.size() - 1);
        return own;
    }

    private Map<String, BigInteger> callGas = new HashMap<>();
}

package caspien.postpasses.gasreport;
import caspien.config.DocComments;

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
    final GasReport hub;

    GasReportCore(GasReport hub) {
        this.hub = hub;
    }

    public static final int CALL_COST = 5;
    /** Lines per section before "(+n more)"; the env var CASPIEN_AUDIT_ALL=1 (used by the tests) lifts the limit. */
    public static final int CAP = "1".equals(System.getenv("CASPIEN_AUDIT_ALL")) ? Integer.MAX_VALUE - 2 : 40;
    public static final int GAS = 0, HEAP = 1, COUNT = 2, LIVE = 3, NMET = 4;     // the path metrics: gas, heap bytes requested, number of allocation operations





    /** Source positions told by the front end for `--audit` causes: "function" and "function|loop label" -> "file:line". Empty = not known. */
    public static final Map<String, String> POS = new HashMap<>();
    /** "function|loop label" -> the retry limit of a `match @lock` spin (> 0 literal, -1 run-time value), from the compiler's lowering. */
    public static final Map<String, Long> SPIN = new HashMap<>();

    public String[] ln;                // trimmed non-empty lines
    public final Map<String, Fn> fns = new LinkedHashMap<>();



    /** Builds the report section for the program whose HOB is given; `entry` functions are `main` and `__caspien_main`. */
    public static Result build(String hob) {
        GasReport g = new GasReport(hob);
        GasReportThread job = new GasReportThread(g);
        Thread t = new Thread(null, job, "gas", 1L << 29);   // deep recursion over long functions
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return job.result;
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





    /** Runs walk frames (which may belong to different Evals) on an explicit stack; a step returns a child frame to run first, or null when it has finished. */
    static Val runWalk(GasWalkFrame root) {
        java.util.Deque<GasWalkFrame> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (true) {
            GasWalkFrame fr = stack.peek();
            GasWalkFrame child = fr.ev.step(fr);
            if (child != null) {
                if (fr.replaced) {
                    stack.pop();
                }
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
    public static void checkLabels(String hob, Map<String, DocComment> funcs, Map<String, DocComment> loops) {
        GasReport g = new GasReport(hob);
        GasLabelsThread job = new GasLabelsThread(g, funcs, loops);
        Thread t = new Thread(null, job, "gas-labels", 1L << 29);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (job.error != null) {
            throw job.error;
        }
    }

    /** Class words for every site ({hobFunction, loopLabel or null, ...}): key = function + "|" + (label or ""). Used by `--fix termination`. */
    public static Map<String, String> classify(String hob, List<String[]> sites) {
        GasReport g = new GasReport(hob);
        GasClassifyThread job = new GasClassifyThread(g, sites);
        Thread t = new Thread(null, job, "gas-classify", 1L << 29);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return job.out;
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
        new GasSort().sort(sorted, GasSort.CLASS_DESC);      // worst class first (stable)
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
        GasVizThread job = new GasVizThread(g, title);
        Thread t = new Thread(null, job, "viz", 1L << 29);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return job.page;
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
        List<String> order = new ArrayList<>(reach);
        sortedSection(sb, res, order, pinned, eventMode, entry, GAS, "\n# worst-case execution cost (abstract gas; HOB operations before optimisation, table in docs/COMPILER_REFERENCE.md)\n", "gas");
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
        GasSort sorter = new GasSort();
        sorter.depth = depth;
        sorter.sort(so, GasSort.STACK_DESC);
        int shown = 0;
        for (String n : so) {
            boolean isRoot = isRootName(roots, n);
            if (shown++ >= CAP && !isRoot) {
                continue;
            }
            Set<String> why = sub.get(n);
            String label = rootLabel(n, eventMode, entry);
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
        long threadRoots = 0;
        for (Fn r : roots) {
            if (r.name.startsWith("__trampoline_")) {
                threadRoots++;
            }
        }
        for (Fn r : (eventMode ? slices : List.of(entry))) {
            sb.append("# summary: ").append(r.name).append(" needs ").append(sub.get(r.name).isEmpty() ? "" : "at least ").append(depth.get(r.name))
                    .append(" bytes of stack").append(sub.get(r.name).isEmpty() ? " (bound)" : " (" + wordOf(worstOf(sub.get(r.name))) + ")").append(" for safe code, ")
                    .append(threadRoots).append(" thread entr").append(threadRoots == 1 ? "y" : "ies").append("\n");
        }

        // heap
        sb.append("\n# heap memory (the most bytes one run can request from the allocator: new, dyn, resize and clone, at their full size, headers included, frees not credited,\n")
                .append("#   so it also bounds the peak live heap; ghost-table growth and the allocator's own overhead are not counted; the number of allocation operations is in brackets)\n");
        List<String> ho = new ArrayList<>(reach);
        sorter.fns = fns;
        sorter.sort(ho, GasSort.HEAP_DESC);
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
            sb.append(String.format("  %-34s %s  (%s%d allocation operation%s)%s%n", rootLabel(n, eventMode, entry).replace(" (thread entry)", ""), val,
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
        sorter.sort(lo, GasSort.LIVE_DESC);
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
            sb.append(String.format("  %-34s peak %s%s bytes  (leaves %s%s)%s%n", rootLabel(n, eventMode, entry).replace(" (thread entry)", ""), f.ub[LIVE].isEmpty() ? "" : ">= ", f.livePeak,
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
                top.append(String.format("  %-34s gas %s%s   stack %s%d bytes   heap %s%s bytes (%s%s allocation operation%s), peak live %s%s bytes%n", rootLabel(sl.name, eventMode, entry),
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

    private String rootLabel(String n, boolean eventMode, Fn entry) {
        Fn f = fns.get(n);
        if (eventMode && f != null && f.deco.contains("@with_tick")) {
            return n + " (slice: @with_tick)";
        }
        if (eventMode && f != null && f.deco.contains("@tick")) {
            return n + " (slice: @tick)";
        }
        return n + (n.equals(entry.name) ? " (entry)" : n.startsWith("__trampoline_") ? " (thread entry)" : "");
    }

    private static boolean isRootName(List<Fn> roots, String n) {
        for (Fn r : roots) {
            if (r.name.equals(n)) {
                return true;
            }
        }
        return false;
    }

    public void sortedSection(StringBuilder sb, Result res, List<String> order, Set<String> pinned,
            boolean eventMode, Fn entry, int mt, String title, String key) {
        sb.append(title);
        GasSort sorter = new GasSort();
        sorter.fns = fns;
        sorter.pinned = pinned;
        sorter.mt = mt;
        sorter.sort(order, GasSort.PINNED_THEN_METRIC);
        int shown = 0;
        for (String n : order) {
            Fn f = fns.get(n);
            Set<String> ub = f.ub[mt];
            String val = (ub.isEmpty() ? "" : ">= ") + f.val[mt];
            res.gasByFunction.put(n, val);
            if (shown++ >= CAP && !pinned.contains(n)) {
                continue;
            }
            sb.append(String.format("  %-34s %s  %s%n", rootLabel(n, eventMode, entry).replace(" (thread entry)", n.startsWith("__trampoline_") ? " (thread entry)" : ""), val, tag(ub)));
            if (!f.notModelled.isEmpty()) {
                sb.append("      not modelled: ").append(String.join("; ", f.notModelled)).append("\n");
            }
        }
        if (order.size() > CAP + pinned.size()) {
            sb.append("  (+").append(order.size() - CAP - pinned.size()).append(" more reachable functions)\n");
        }
    }

    public void collect(Fn start, Set<String> out) {
        java.util.Deque<Fn> work = new java.util.ArrayDeque<>();
        work.push(start);
        while (!work.isEmpty()) {
            Fn f = work.pop();
            if (!out.add(f.name)) {
                continue;
            }
            List<Fn> callees = new ArrayList<>();
            for (String c : f.calls) {
                Fn cf = fns.get(c);
                if (cf != null) {
                    callees.add(cf);
                }
            }
            for (int i = callees.size() - 1; i >= 0; i--) {
                work.push(callees.get(i));
            }
        }
    }

    public void solve(Fn f, List<String> stack) {
        java.util.Deque<GasSolveFrame> frames = new java.util.ArrayDeque<>();
        frames.push(new GasSolveFrame(GasSolveFrame.SOLVE, f));
        while (!frames.isEmpty()) {
            GasSolveFrame fr = frames.peek();
            GasSolveFrame child = fr.kind == GasSolveFrame.SOLVE ? stepSolve(fr, stack) : stepRunEvals(fr, stack);
            if (child != null) {
                frames.push(child);
            } else {
                frames.pop();
            }
        }
    }

    /** Solves the callees first (each in its own frame), then the function itself. Returns a child frame or null when done. */
    private GasSolveFrame stepSolve(GasSolveFrame fr, List<String> stack) {
        Fn f = fr.f;
        if (!fr.entered) {
            if (f.done || stack.contains(f.name)) {
                return null;
            }
            stack.add(f.name);
            fr.entered = true;
            fr.i = f.start;
        }
        if (fr.phase == 0) {
            while (fr.i < f.end) {
                int i = fr.i++;
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
                    return new GasSolveFrame(GasSolveFrame.SOLVE, cf);
                }
            }
            hub.mapOkLabels(f);
            hub.analyzeCredits(f);
            fr.phase = 1;
            GasSolveFrame run = new GasSolveFrame(GasSolveFrame.RUN_EVALS, f);
            run.args = new HashMap<>();
            return run;
        }
        f.done = true;
        stack.remove(stack.size() - 1);
        return null;
    }

    public void runEvals(Fn f, Map<String, V> args, List<String> stack) {
        java.util.Deque<GasSolveFrame> frames = new java.util.ArrayDeque<>();
        GasSolveFrame first = new GasSolveFrame(GasSolveFrame.RUN_EVALS, f);
        first.args = args;
        frames.push(first);
        while (!frames.isEmpty()) {
            GasSolveFrame child = stepRunEvals(frames.peek(), stack);
            if (child != null) {
                frames.push(child);
            } else {
                frames.pop();
            }
        }
    }

    /** One function's figures under `args`; a callee that has to be specialised is run in a child frame first. */
    @SuppressWarnings("unchecked")
    private GasSolveFrame stepRunEvals(GasSolveFrame fr, List<String> stack) {
        Fn f = fr.f;
        Map<String, V> args = fr.args;
        if (fr.callUb == null) {
            f.args = args;
            f.calleeAt.clear();
            fr.callUb = new Set[]{new LinkedHashSet<String>(), new LinkedHashSet<String>(), new LinkedHashSet<String>(), new LinkedHashSet<String>()};
            fr.i = f.start;
        }
        Set<String>[] callUb = fr.callUb;
        if (fr.pendingCallee != null) {
            // the specialised callee has been run
            stack.remove(stack.size() - 1);
            fr.i = finishCall(fr, fr.i - 1, fr.pendingCallee, fr.pendingUse);
            fr.pendingCallee = null;
            fr.pendingUse = null;
        }
        while (fr.i < f.end) {
            int i = fr.i++;
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
                        fr.pendingCallee = c;
                        fr.pendingUse = use;
                        GasSolveFrame child = new GasSolveFrame(GasSolveFrame.RUN_EVALS, use);
                        child.args = cc;
                        return child;
                    }
                }
            }
            finishCall(fr, i, c, use);
        }
        for (int m = 0; m < NMET; m++) {
            f.ub[m].clear();
        }
        for (int m = 0; m < NMET; m++) {
            Val v = new Eval(this, f, m).walk(f.start + 1, -1, ALL);
            f.val[m] = v.neg || v.n.compareTo(BigInteger.TEN.pow(20).negate()) < 0 ? BigInteger.ZERO : v.n;
            if (m == LIVE) {
                f.livePeak = v.neg ? BigInteger.ZERO : v.p;
            }
            f.ub[m].addAll(callUb[m]);
        }
        return null;
    }

    /** Records the callee chosen for the CALL at line i and its unbounded reasons; returns the next line to look at. */
    private int finishCall(GasSolveFrame fr, int i, String c, Fn use) {
        Fn f = fr.f;
        f.calleeAt.put(i, use);
        for (int m = 0; m < NMET; m++) {
            if (!use.ub[m].isEmpty()) {
                fr.callUb[m].add(callReason(c, use.ub[m], hub.runsFirst(f, i)));
            }
        }
        return i + 1;
    }
}

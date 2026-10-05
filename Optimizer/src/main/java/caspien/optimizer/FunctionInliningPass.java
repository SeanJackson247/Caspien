package caspien.optimizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Function inlining: a direct call "CC_START / args / CALL f / CC_END [PUSH_RET T]" is replaced by the body of f.
 *
 * How a call site is rewritten
 *   - Every argument (the lines before its "POP ARGn T") is assigned to a fresh local that stands for the matching parameter:
 *     "ADDR p__iK T / <argument lines> / ASSIGN T T T". Arguments are evaluated left to right, exactly as before.
 *   - The callee's own locals and labels are renamed per inlined copy (x -> x__iK, @lbl_12 -> @lbl_<fresh>; the numeric-suffix
 *     shape is kept so loop unrolling still recognises a copied `for` loop), and all their ALLOC lines move to the top of the caller.
 *   - A callee that is one straight-line body ending in a single `RET` just leaves its value on the stack (the PUSH_RET of the call
 *     site is dropped). Otherwise each `RET` stores into a result local (found by walking the return expression back with the
 *     operand-stack effect of each line) and jumps to the end of the copy, where the result is pushed. A void `RET` is a jump. A call
 *     whose result is discarded drops the (pure) return expression instead.
 *
 * Throw / catch programs: every function carries `gt_routine_address` (and `gt_error_message`) slots and stages a per-call-site label
 * into its own slot before each call it makes. The two slots are dead scaffolding when the body never mentions them, and are never
 * copied (the caller's own slots stand in: the inlined code writes and reads the caller's, which is exactly what the callee's
 * frame slots would have been copied into on unwind). A callee that stages its own calls, throws (THROW is a no-op marker and is
 * dropped) or unwinds is inlined too: each `GT_UNWIND [MSG]` becomes a `JMP` to the label the CALLER staged for this call (its
 * landing pad or `@catch_N`), the message having been written into the caller's slot already. The callee's own landing pads and
 * catch blocks are copied with renamed labels. The staging triple of the call site is dropped (nothing reads it any more).
 *
 * Never inlined (the callee): a function with any decorator other than @pub / @pure / @recursive / @inline / @throws (so never
 * @async, the ghost-table hooks, par/await/sleep glue), `main`, anything on a call cycle, a function using EXIT / inline assembly /
 * a function-local static, a callee that unwinds at a call site with no staged label (old-form pads in non-throw programs), an
 * a function with a by-value struct, array or dynarray parameter, one that reaches outside itself with a label, and one whose local names collide with tokens the renamer cannot tell apart.
 * A bare `range` parameter is supported: the call site passes it as two words (start, end), which are assigned to the copy's range
 * variable like a `lo..hi` literal; variable names inside range type text (`imut_range(mut_lo,mut_hi)`) are renamed along with the
 * variables. A parameter that the callee declares again as a local of the same type (the loop-converted recursive form) is one variable.
 * A call through a function pointer (INVOKE) is not a `CALL` and an extern is not a function with a body: neither is ever touched.
 * A recursive function has already been turned into a loop by the front end, so it is inlined like any other function.
 *
 * Where a call sits matters: with anything else on the operand stack beneath it (an operand of an enclosing expression) only a
 * straight-line callee of plain scalar operations is inlined; `let x = f(..)` (one address beneath) with a branching callee uses the
 * result local and pushes the address after the copy.
 *
 * Configuration: see InlineConfig (presets off|conservative|balanced|aggressive with size / depth / growth limits).
 */
public class FunctionInliningPass implements OptimizationPass {

    private final InlineConfig cfg;
    private int roundsDone = 0;
    private final Map<String, Long> grown = new HashMap<>();
    private int fresh = -1;
    /** Original size per function and for the program (first round), and lines added so far: the relative budgets. */
    private final Map<String, Long> origSize = new HashMap<>();
    private long origProgram = -1;
    private long totalAdded = 0;
    /** Number of call sites inlined over the whole run (for diagnostics). */
    public int inlinedSites = 0;

    public FunctionInliningPass() {
        this(InlineConfig.disabled());
    }

    public FunctionInliningPass(InlineConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return "function-inlining";
    }

    // ------------------------------------------------------------------------------------------------------------------
    // small helpers

    private static String mn(List<BytecodeToken> l) {
        return l.isEmpty() || l.get(0).kind != BytecodeToken.Kind.CODE ? null : l.get(0).text;
    }

    private static String tok(List<BytecodeToken> l, int i) {
        return i < l.size() ? l.get(i).text : null;
    }

    private static boolean is(List<BytecodeToken> l, int size, String first) {
        return l.size() == size && first.equals(mn(l));
    }

    private static boolean isLabelDef(List<BytecodeToken> l) {
        return l.size() == 1 && l.get(0).kind == BytecodeToken.Kind.CODE && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":");
    }

    private static BytecodeToken retext(BytecodeToken t, String text) {
        return new BytecodeToken(text, t.file, t.line, t.kind);
    }

    private static List<BytecodeToken> mk(BytecodeToken ref, String... parts) {
        List<BytecodeToken> l = new ArrayList<>(parts.length);
        for (String p : parts) l.add(new BytecodeToken(p, ref.file, ref.line, BytecodeToken.Kind.CODE));
        return l;
    }

    private static boolean allDigits(String s) {
        if (s == null || s.isEmpty() || s.length() > 12) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    private static final Set<String> OK_DECORATORS = new HashSet<>(Arrays.asList("@pub", "@pure", "@recursive", "@inline", "@throws", "@lock"));
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList("EXIT", "EXIT_THREAD",
            "ASM_START", "ASM_END", "RECURSIVE_CALL", "ALLOC_STATIC", "FUNC_START", "FUNC_END", "STRUCT_START", "GLOBAL", "REGVAR", "STRING",
            "EXTERN", "ENUM", "STRUCT_MEMBER", "STRUCT_END", "STRUCT_DECORATE", "STRUCT_PADDING"));
    private static final Set<String> BINARY = new HashSet<>(Arrays.asList("ADD", "SUB", "MUL", "DIV", "MOD", "AND", "OR", "EQ", "NEQ", "LT",
            "GT", "LT_EQ", "GT_EQ", "SHL", "SHR", "ROTL", "ROTR", "BITS_OR", "BITS_AND", "BITS_XOR", "IN", "WITHIN", "LOOKUP", "LOOKUP_LHS", "DOT", "DOT_LHS"));
    private static final Set<String> UNARY = new HashSet<>(Arrays.asList("NEG", "NOT", "BITS_NOT", "SEXT", "ZEXT", "TRUNC", "FCONV", "DEREF", "ADDR_OF",
            "NEG_FLOAT", "LEN", "PROMOTE_F32_TO_F64"));
    private static final Set<String> PUSH1 = new HashSet<>(Arrays.asList("PUSH", "ADDR", "PUSH_FIELDNAME", "ATOMIC_PUSH"));
    /** What a plain scalar body may contain when other operands are live beneath the call. */
    private static final Set<String> SIMPLE = new HashSet<>(Arrays.asList("ALLOC", "ADDR", "PUSH", "ASSIGN", "ADD", "SUB", "MUL", "DIV", "MOD",
            "AND", "OR", "EQ", "NEQ", "LT", "GT", "LT_EQ", "GT_EQ", "NEG", "NOT", "SEXT", "ZEXT", "TRUNC", "FCONV", "SHL", "SHR", "ROTL", "ROTR", "BITS_OR", "BITS_AND", "BITS_XOR", "BITS_NOT",
            "RET", "INC", "DEC"));
    private static final Set<String> BOUNDARY = new HashSet<>(Arrays.asList("ASSIGN", "ATOMIC_ASSIGN", "JMP", "CMP", "ALLOC", "ARG", "RETURNS",
            "FUNC_DECORATE", "FUNC_START", "CC_END", "CC_START", "GT_DESTRUCT", "RET", "POP"));
    private static final Set<String> SCALARS = new HashSet<>(Arrays.asList("u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "f32", "f64",
            "bool", "char"));

    /** A parameter type the inliner can copy by a plain scalar ASSIGN: a scalar, or any pointer. */
    static boolean scalarType(String t) {
        if (t == null || t.indexOf('(') >= 0 || t.indexOf('[') >= 0) return false;
        for (String p : new String[] {"raw_", "owns_", "ref_", "auto_", "static_"}) {
            if (t.startsWith(p)) return true;
        }
        String b = t;
        for (String p : new String[] {"mut_", "imut_", "indeterminate_"}) {
            if (b.startsWith(p)) {
                b = b.substring(p.length());
                break;
            }
        }
        return SCALARS.contains(b);
    }

    /** A bare range parameter (`imut_range` / `mut_range`, no bounds in the type): passed as two argument words, start then end. */
    static boolean rangeParam(String t) {
        return "imut_range".equals(t) || "mut_range".equals(t);
    }

    private static int wordsOf(String paramType) {
        return rangeParam(paramType) ? 2 : 1;
    }

    private static final String[] MUT_PREFIXES = {"mut_", "imut_", "indeterminate_"};

    /**
     * A range type's text embeds the names of the variables that bound it (`imut_range(mut_lo,mut_hi)`); when the variables are
     * renamed for an inlined copy the names inside the text must follow, or elision/unrolling would read stale names.
     */
    static String renameRangeText(String x, Map<String, String> local) {
        if (x.indexOf("range(") < 0) return x;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            int q = x.indexOf("range(", i);
            int close = q < 0 ? -1 : x.indexOf(')', q + 6);
            if (q < 0 || close < 0) {
                sb.append(x.substring(i));
                break;
            }
            sb.append(x, i, q + 6);
            String[] parts = x.substring(q + 6, close).split(",", -1);
            for (int k = 0; k < parts.length; k++) {
                if (k > 0) sb.append(',');
                String part = parts[k];
                String out = part;
                if (local.containsKey(part)) {
                    out = local.get(part);
                } else {
                    for (String pre : MUT_PREFIXES) {
                        if (part.startsWith(pre) && local.containsKey(part.substring(pre.length()))) {
                            out = pre + local.get(part.substring(pre.length()));
                            break;
                        }
                    }
                }
                sb.append(out);
            }
            sb.append(')');
            i = close + 1;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // callee analysis

    private static final class Ret {
        int idx;
        boolean isVoid;
        String type;
        int vStart = -1, vTail = -1;
        boolean pure;
        boolean tail;
    }

    private static final class Callee {
        String name;
        List<String[]> params = new ArrayList<>();
        List<String[]> allocs = new ArrayList<>();
        List<List<BytecodeToken>> code = new ArrayList<>();
        boolean eligible;
        int scaffoldSlots;
        /** the body contains THROW / GT_UNWIND: it can leave through the caller's call-site label. */
        boolean unwinds;
        boolean usesAddr, usesMsg;
        /** the body defines a `@catch_N` label (a `?catch` / `try ... catch` of its own). */
        boolean hasCatch;
        /** every statement leaves the operand stack as it found it (no ignored call result, no unknown mnemonic): safe to run with operands of an enclosing expression beneath. */
        boolean balanced;
        boolean controlFlow;
        boolean simple;
        List<Ret> rets = new ArrayList<>();
        Set<String> locals = new HashSet<>();
        Set<String> globals = new HashSet<>();
        Set<String> labels = new LinkedHashSet<>();
        int lines;
        /** number of `CALL name` lines in the program at the start of this round */
        int calls;
        String why = "";
        /** @inline: inline every call site that is safe, whatever the preset's size and growth limits say. */
        boolean forced;
        /** @dont(inline): never. */
        boolean dont;
        String pos;
    }

    private static final Set<String> NAME_POS = new HashSet<>(Arrays.asList("PUSH", "ADDR", "GT_DESTRUCT", "ATOMIC_PUSH"));

    /**
     * Simulates the operand-stack depth over the body in text order. True when it is 0 at every RET (after the returned value is
     * popped), every statement boundary the walk sees and the end, and every mnemonic has a known effect. A call whose result is ignored
     * leaves its PUSH_RET word on the stack: harmless in a frame of its own (RET resets rsp), but once the body runs inline in the middle of
     * an expression that word sits between the pending operands and the code that consumes them.
     */
    private static boolean stackBalanced(List<List<BytecodeToken>> code) {
        int depth = 0;
        for (List<BytecodeToken> l : code) {
            if (isLabelDef(l)) continue;
            String m = mn(l);
            if (m == null) return false;
            int pops, pushes;
            if (m.equals("JMP") || m.equals("GT_DESTRUCT") || m.equals("CC_START") || m.equals("CC_END") || m.equals("CALL")
                    || m.equals("EXTERN_CALL") || m.equals("VARARGS_XMM_COUNT")) {
                continue;
            } else if (m.equals("GT_UNWIND")) {
                depth = 0;
                continue;
            } else if (m.equals("PUSH_LABEL")) {
                // a landing-pad / catch address is staged as ADDR gt_routine_address / PUSH_LABEL / ASSIGN
                pops = 0;
                pushes = 1;
            } else if (m.equals("RET")) {
                pops = l.size() >= 2 && l.get(1).text.equals("imut_void") ? 0 : 1;
                if (depth - pops != 0) return false;
                depth = 0;
                continue;
            } else if (m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) {
                pops = 2;
                pushes = 0;
            } else if (m.equals("CMP") || m.equals("POP")) {
                pops = 1;
                pushes = 0;
            } else if (PUSH1.contains(m) || m.equals("PUSH_RET")) {
                pops = 0;
                pushes = 1;
            } else if (BINARY.contains(m)) {
                pops = 2;
                pushes = 1;
            } else if (UNARY.contains(m) || m.equals("INC") || m.equals("DEC")) {
                pops = 1;
                pushes = 1;
            } else {
                return false;
            }
            depth = depth - pops + pushes;
            if (depth < 0) return false;
        }
        return depth == 0;
    }

    private static boolean pureLine(List<BytecodeToken> l) {
        String m = mn(l);
        return m != null && (PUSH1.contains(m) || BINARY.contains(m) || UNARY.contains(m));
    }

    /** Walks back from line `tail` over an expression that leaves one value; returns its first line, or -1 if the shape is not understood. */
    private static int walkBack(List<List<BytecodeToken>> code, int tail) {
        int need = 1;
        int k = tail;
        while (k >= 0) {
            List<BytecodeToken> l = code.get(k);
            if (isLabelDef(l)) {
                // a label of a body that was inlined into this expression: no effect on the operand stack
                k--;
                continue;
            }
            String m = mn(l);
            if (m == null) return -1;
            int pops, pushes;
            if (m.equals("JMP") || m.equals("GT_DESTRUCT")) {
                k--;
                continue;
            } else if (m.equals("CC_END") && !(k + 1 < code.size() && is(code.get(k + 1), 2, "PUSH_RET"))) {
                // a call whose result is not used (a statement inlined into this expression): skip the whole bracket
                int depth = 0;
                int j = k;
                for (; j >= 0; j--) {
                    String jm = mn(code.get(j));
                    if ("CC_END".equals(jm)) depth++;
                    else if ("CC_START".equals(jm)) {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                if (j < 0) return -1;
                k = j - 1;
                continue;
            } else if (m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) {
                pops = 2;
                pushes = 0;
            } else if (m.equals("CMP") || m.equals("POP")) {
                pops = 1;
                pushes = 0;
            } else if (PUSH1.contains(m)) {
                pops = 0;
                pushes = 1;
            } else if (BINARY.contains(m)) {
                pops = 2;
                pushes = 1;
            } else if (UNARY.contains(m) || m.equals("INC") || m.equals("DEC")) {
                pops = 1;
                pushes = 1;
            } else if (m.equals("PUSH_RET")) {
                pops = 0;
                pushes = 1;
                if (k == 0 || !is(code.get(k - 1), 2, "CC_END")) return -1;
            } else {
                return -1;
            }
            need = need - pushes + pops;
            if (need < 0) return -1;
            if (m.equals("PUSH_RET")) {
                int depth = 0;
                int j = k - 1;
                for (; j >= 0; j--) {
                    String jm = mn(code.get(j));
                    if ("CC_END".equals(jm)) depth++;
                    else if ("CC_START".equals(jm)) {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                if (j < 0) return -1;
                k = j;
                if (need == 0) return k;
                k--;
                continue;
            }
            if (need == 0) return k;
            k--;
        }
        return -1;
    }

    private Callee analyse(List<List<BytecodeToken>> L, int s, int e, Set<String> cyclic) {
        Callee c = new Callee();
        c.name = L.get(s).get(1).text;
        String retType = null;
        boolean ok = true;
        for (int i = s + 1; i < e; i++) {
            List<BytecodeToken> l = L.get(i);
            String m = mn(l);
            if (m == null) {
                c.why = "non-code line";
                ok = false;
                continue;
            }
            if (m.equals("FUNC_DECORATE")) {
                String dt = tok(l, 1);
                boolean posOk = l.size() == 3 && l.get(2).kind == BytecodeToken.Kind.STRING;   // an optional "file:line" operand
                if (!(l.size() == 2 || posOk) || !(OK_DECORATORS.contains(dt) || "@dont(inline)".equals(dt))) {
                    c.why = "decorator " + dt;
                    ok = false;
                } else if (dt.equals("@inline")) {
                    c.forced = true;
                } else if (dt.equals("@dont(inline)")) {
                    c.dont = true;
                }
                if (posOk) c.pos = unq(l.get(2).text);
                continue;
            }
            if (m.equals("RETURNS")) {
                retType = tok(l, 1);
                continue;
            }
            if (m.equals("ARG") && l.size() == 3) {
                c.params.add(new String[] {l.get(1).text, l.get(2).text});
                continue;
            }
            if (m.equals("ALLOC") && l.size() == 3) {
                if (l.get(1).text.equals("gt_routine_address") || l.get(1).text.equals("gt_error_message")) {
                    // ghost-table / throw scaffolding slot. It is dead weight unless something in the body uses it; a body that does
                    // (staging writes, THROW, unwinding) is refused by the mention check below. The slots are NOT copied to the caller
                    // (the caller has its own), so the inlined code carries no scaffolding.
                    c.scaffoldSlots++;
                    continue;
                }
                boolean dup = false;
                for (String[] pp : c.params) {
                    if (pp[0].equals(l.get(1).text)) {
                        // a parameter that is declared again as a local of the same type (the loop-converted recursive form does this):
                        // one variable, exactly as the frame layout already treats it
                        if (!pp[1].equals(l.get(2).text)) {
                            c.why = "parameter " + pp[0] + " redeclared with another type";
                            ok = false;
                        }
                        dup = true;
                    }
                }
                for (String[] aa : c.allocs) {
                    if (aa[0].equals(l.get(1).text)) {
                        if (!aa[1].equals(l.get(2).text)) {
                            c.why = "local " + aa[0] + " declared twice with different types";
                            ok = false;
                        }
                        dup = true;
                    }
                }
                if (!dup) c.allocs.add(new String[] {l.get(1).text, l.get(2).text});
                continue;
            }
            if (m.equals("THROW")) {
                // a pure marker (the message write and the unwind follow as ordinary lines); it carries nothing the copy needs
                c.unwinds = true;
                continue;
            }
            if (m.equals("GT_UNWIND")) {
                c.unwinds = true;
                if (!(l.size() == 1 || (l.size() == 2 && l.get(1).text.equals("MSG")))) {
                    c.why = "GT_UNWIND shape";
                    ok = false;
                }
            }
            if (m.equals("PUSH_LABEL")) {
                if (l.size() != 3 || !(l.get(1).text.startsWith("@gt_callsite__") || l.get(1).text.startsWith("@catch_"))) {
                    c.why = "PUSH_LABEL of something other than a landing pad / catch";
                    ok = false;
                }
            }
            if (EXCLUDED.contains(m)) {
                c.why = m;
                ok = false;
            }
            c.code.add(l);
        }
        for (List<BytecodeToken> l : c.code) {
            for (int t = 1; t < l.size(); t++) {
                BytecodeToken tk = l.get(t);
                if (tk.kind == BytecodeToken.Kind.CODE && tk.text.equals("gt_routine_address")) c.usesAddr = true;
                if (tk.kind == BytecodeToken.Kind.CODE && tk.text.equals("gt_error_message")) c.usesMsg = true;
            }
        }
        c.lines = e - s - 1;
        if (retType == null) {
            c.why = "no RETURNS";
            ok = false;
        }
        if (c.name.equals("main")) {
            c.why = "main";
            ok = false;
        }
        if (cyclic.contains(c.name)) {
            c.why = "call cycle";
            ok = false;
        }
        if (c.lines > cfg.maxCalleeLines && !c.forced) {
            c.why = "too long";
            ok = false;
        }
        if (c.dont) {
            c.why = "@dont(inline)";
            ok = false;
        }
        if (!cfg.enabled && !c.forced) {
            c.why = "inlining is off and the function is not marked @inline";
            ok = false;
        }
        for (String[] p : c.params) {
            if (!scalarType(p[1]) && !rangeParam(p[1])) {
                c.why = "param type " + p[1];
                ok = false;
            }
            c.locals.add(p[0]);
        }
        for (String[] a : c.allocs) c.locals.add(a[0]);
        if (c.locals.size() != c.params.size() + c.allocs.size()) {
            c.why = "duplicate parameter names";
            ok = false;
        }
        // labels, jumps, names
        for (List<BytecodeToken> l : c.code) {
            if (isLabelDef(l)) {
                String t = l.get(0).text;
                c.labels.add(t.substring(0, t.length() - 1));
                if (t.startsWith("@catch_")) c.hasCatch = true;
            }
        }
        boolean flow = false;
        boolean simple = true;
        for (List<BytecodeToken> l : c.code) {
            String m = mn(l);
            if (isLabelDef(l) || "JMP".equals(m) || "CMP".equals(m)) flow = true;
            if (!isLabelDef(l) && !SIMPLE.contains(m)) simple = false;
            if ("ASSIGN".equals(m) && !(l.size() == 4 && scalarType(l.get(1).text) && scalarType(l.get(2).text) && scalarType(l.get(3).text))) simple = false;
            if ("ADDR".equals(m) && l.size() == 3 && !scalarType(l.get(2).text)) simple = false;
            if ("PUSH".equals(m) && l.size() == 3 && !scalarType(l.get(2).text)) simple = false;
            for (int t = 1; t < l.size(); t++) {
                BytecodeToken tk = l.get(t);
                if (tk.kind != BytecodeToken.Kind.CODE) {
                    continue;
                }
                String x = tk.text;
                if (x.startsWith("@")) {
                    String nm = x.endsWith(":") ? x.substring(0, x.length() - 1) : x;
                    if (!c.labels.contains(nm)) {
                        c.why = "label " + nm + " not defined in callee";
                        ok = false;
                    }
                    continue;
                }
                boolean namePos = t == 1 && NAME_POS.contains(m) && (l.size() == 3 || "GT_DESTRUCT".equals(m));
                String root = x.indexOf('.') > 0 ? x.substring(0, x.indexOf('.')) : x;
                if (namePos) {
                    if (!c.locals.contains(root) && !root.equals("gt_routine_address") && !root.equals("gt_error_message") && !x.isEmpty() && !Character.isDigit(x.charAt(0)) && !x.startsWith("'") && !x.startsWith("\"")
                            && !x.equals("true") && !x.equals("false") && !x.equals("null") && !x.startsWith("-")) {
                        c.globals.add(root);
                    }
                } else if (t == 1 && ("PUSH_FIELDNAME".equals(m) || "CALL".equals(m) || "EXTERN_CALL".equals(m))) {
                    // a field name, not a variable
                } else if (t >= 2 && NAME_POS.contains(m)) {
                    // type operand
                } else if (c.locals.contains(x) || c.locals.contains(root)) {
                    c.why = "local name " + x + " used in an unrecognised position (" + m + ")";
                    ok = false;
                }
            }
        }
        c.controlFlow = flow;
        c.balanced = stackBalanced(c.code);
        c.simple = simple;
        // returns
        for (int i = 0; i < c.code.size(); i++) {
            List<BytecodeToken> l = c.code.get(i);
            if (!"RET".equals(mn(l))) continue;
            if (l.size() != 2) {
                c.why = "RET shape";
                ok = false;
                continue;
            }
            Ret r = new Ret();
            r.idx = i;
            r.type = l.get(1).text;
            r.isVoid = r.type.equals("imut_void");
            r.tail = i == c.code.size() - 1;
            if (!r.isVoid) {
                int t = i - 1;
                while (t >= 0 && "GT_DESTRUCT".equals(mn(c.code.get(t)))) t--;
                if (t >= 0) {
                    r.vTail = t;
                    r.vStart = walkBack(c.code, t);
                    if (r.vStart >= 0) {
                        r.pure = true;
                        for (int q = r.vStart; q <= r.vTail; q++) {
                            if (!pureLine(c.code.get(q))) r.pure = false;
                        }
                    }
                }
            }
            c.rets.add(r);
        }
        if (c.rets.size() != 1 || !c.rets.get(0).tail) c.controlFlow = true;
        c.eligible = ok;
        if (c.forced && !ok) forcedWhy.put(c.name, c.why);
        if (c.forced && c.pos != null) forcedPos.putIfAbsent(c.name, c.pos);
        if (!ok && System.getenv("CASPIEN_INLINE_WHY") != null) System.err.println("[inline] " + c.name + " refused: " + c.why);
        return c;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // the pass

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        int maxRounds = cfg.enabled ? Math.max(cfg.maxDepth, anyInlineDecorator(lines) ? FORCED_ROUNDS : 0) : (anyInlineDecorator(lines) ? FORCED_ROUNDS : 0);
        if (roundsDone >= maxRounds) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> work = lines;
        boolean any = false;
        while (roundsDone < maxRounds) {
            List<List<BytecodeToken>> next = round(work);
            if (next == null) break;
            work = next;
            any = true;
            roundsDone++;
        }
        return any ? new PassResult(work, true) : new PassResult(lines, false);
    }

    private static String unq(String t) {
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    private final Map<String, Integer> forcedSites = new LinkedHashMap<>();
    private final Map<String, String> forcedPos = new HashMap<>();
    private final Map<String, String> forcedWhy = new HashMap<>();

    /**
     * Run once after the optimizer has settled: what @inline did. "[note] file:line - @inline: f inlined at K call sites"; a call to an
     * @inline function that is still a call is a "[warning] ... @inline not honoured at N call sites: <reason>".
     */
    public void reportForced(List<List<BytecodeToken>> L) {
        Map<String, Integer> remaining = new HashMap<>();
        Map<String, String> pos = new LinkedHashMap<>(forcedPos);
        for (int i = 0; i < L.size(); i++) {
            List<BytecodeToken> l = L.get(i);
            if (is(l, 2, "CALL")) remaining.merge(l.get(1).text, 1, Integer::sum);
            if ("FUNC_DECORATE".equals(mn(l)) && l.size() == 3 && "@inline".equals(l.get(1).text)) {
                // the nearest FUNC_START above names the function
                int j = i;
                while (j > 0 && !is(L.get(j), 2, "FUNC_START")) j--;
                pos.putIfAbsent(L.get(j).get(1).text, unq(l.get(2).text));
            }
        }
        for (Map.Entry<String, String> e : pos.entrySet()) {
            String f = e.getKey();
            int inl = forcedSites.getOrDefault(f, 0), rem = remaining.getOrDefault(f, 0);
            String where = e.getValue() == null ? "" : e.getValue() + " - ";
            String shown = f.contains("__") ? f.substring(0, f.indexOf("__")) : f;
            if (inl > 0 && rem == 0) {
                System.err.println("[note] " + where + "@inline: " + shown + " inlined at " + inl + " call site" + (inl == 1 ? "" : "s"));
            } else if (rem > 0) {
                System.err.println("[warning] " + where + "@inline not honoured at " + rem + " call site" + (rem == 1 ? "" : "s") + " of " + shown + " ("
                        + inl + " inlined): " + forcedWhy.getOrDefault(f, "the call is somewhere inlined code cannot go: an enclosing expression with values pending, an argument that is not a scalar, ..."));
            }
        }
    }

    /** Rounds of inlining available to @inline functions (a chain of marked functions needs one round per level). */
    private static final int FORCED_ROUNDS = 8;

    private static boolean anyInlineDecorator(List<List<BytecodeToken>> lines) {
        for (List<BytecodeToken> l : lines) {
            if ("FUNC_DECORATE".equals(mn(l)) && l.size() >= 2 && "@inline".equals(l.get(1).text)) return true;
        }
        return false;
    }

    private final class Ctx {
        String caller;
        Set<String> callerNames = new HashSet<>();
        Map<String, Callee> callees;
        List<List<BytecodeToken>> newAllocs = new ArrayList<>();
        long added = 0;
        boolean changed = false;
    }

    /** One round: every call site whose callee (as it stands now) is eligible is inlined. Returns null if nothing changed. */
    private List<List<BytecodeToken>> round(List<List<BytecodeToken>> L) {
        int maxNum = 0;
        for (List<BytecodeToken> l : L) {
            for (BytecodeToken t : l) {
                if (t.kind != BytecodeToken.Kind.CODE) continue;
                String x = t.text;
                if (!(x.startsWith("@") || x.startsWith("$for_range_"))) continue;
                if (x.endsWith(":")) x = x.substring(0, x.length() - 1);
                int us = x.lastIndexOf('_');
                if (us >= 0 && allDigits(x.substring(us + 1))) {
                    maxNum = Math.max(maxNum, (int) Math.min(Long.parseLong(x.substring(us + 1)), 1_000_000_000L));
                }
            }
        }
        fresh = Math.max(fresh, maxNum + 1);

        List<int[]> fns = VarAnalysis.functions(L);
        if (origProgram < 0) {
            origProgram = L.size();
            for (int[] f : fns) origSize.put(L.get(f[0]).get(1).text, (long) (f[1] - f[0] + 1));
        }
        // call graph -> functions on a cycle
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (int[] f : fns) {
            Set<String> out = new HashSet<>();
            for (int i = f[0] + 1; i < f[1]; i++) {
                List<BytecodeToken> l = L.get(i);
                if (is(l, 2, "CALL")) out.add(l.get(1).text);
            }
            graph.put(L.get(f[0]).get(1).text, out);
        }
        Set<String> cyclic = new HashSet<>();
        for (String n : graph.keySet()) {
            Set<String> seen = new HashSet<>();
            List<String> stack = new ArrayList<>(graph.get(n));
            while (!stack.isEmpty()) {
                String x = stack.remove(stack.size() - 1);
                if (!seen.add(x)) continue;
                if (x.equals(n)) {
                    cyclic.add(n);
                    break;
                }
                Set<String> nx = graph.get(x);
                if (nx != null) stack.addAll(nx);
            }
        }
        Map<String, Callee> callees = new HashMap<>();
        for (int[] f : fns) {
            Callee c = analyse(L, f[0], f[1], cyclic);
            callees.put(c.name, c);
        }
        Map<String, Integer> callCount = new HashMap<>();
        for (List<BytecodeToken> l : L) {
            if (is(l, 2, "CALL")) callCount.merge(l.get(1).text, 1, Integer::sum);
        }
        for (Callee c : callees.values()) c.calls = callCount.getOrDefault(c.name, 0);

        List<List<BytecodeToken>> out = new ArrayList<>(L.size());
        boolean changed = false;
        int pos = 0;
        for (int[] f : fns) {
            while (pos < f[0]) out.add(L.get(pos++));
            Ctx cx = new Ctx();
            cx.caller = L.get(f[0]).get(1).text;
            cx.callees = callees;
            for (int i = f[0] + 1; i < f[1]; i++) {
                List<BytecodeToken> l = L.get(i);
                String m = mn(l);
                if (("ALLOC".equals(m) || "ARG".equals(m)) && l.size() >= 3) {
                    cx.callerNames.add(l.get(1).text);
                }
            }
            List<List<BytecodeToken>> body = expandRange(L, f[0] + 1, f[1], cx, true, false);
            if (cx.changed) {
                changed = true;
                List<List<BytecodeToken>> fn = new ArrayList<>();
                fn.add(L.get(f[0]));
                int p = 0;
                while (p < body.size()) {
                    String m = mn(body.get(p));
                    if ("FUNC_DECORATE".equals(m) || "RETURNS".equals(m) || "ARG".equals(m) || "ALLOC".equals(m) || "ALLOC_STATIC".equals(m)) {
                        fn.add(body.get(p));
                        p++;
                    } else {
                        break;
                    }
                }
                fn.addAll(cx.newAllocs);
                while (p < body.size()) fn.add(body.get(p++));
                fn.add(L.get(f[1]));
                out.addAll(fn);
                grown.merge(cx.caller, cx.added, Long::sum);
            } else {
                for (int i = f[0]; i <= f[1]; i++) out.add(L.get(i));
            }
            pos = f[1] + 1;
        }
        while (pos < L.size()) out.add(L.get(pos++));
        return changed ? out : null;
    }

    /** Index of the CC_END matching the CC_START at k, or -1. */
    private static int matchEnd(List<List<BytecodeToken>> L, int k, int to) {
        int depth = 0;
        for (int i = k; i < to; i++) {
            String m = mn(L.get(i));
            if ("CC_START".equals(m)) depth++;
            else if ("CC_END".equals(m)) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** What is on the operand stack beneath a call site whose CC_START would be appended to `out`: 0 = nothing, 1 = one address, 2 = other. */
    private int classify(List<List<BytecodeToken>> out, int cut, boolean baseClean) {
        int n = out.size() - cut;
        if (!baseClean) return 2;
        if (n == 0) return 0;
        if (boundary(out.get(n - 1))) return 0;
        if (is(out.get(n - 1), 3, "ADDR") && n >= 2 && boundary(out.get(n - 2))) return 1;
        return 2;
    }

    /** True if a "POP ARGn" / "POP FARGn" of the bracket being built (depth 0 of `out`) has already put a value in an argument register. */
    private static boolean argRegisterLoaded(List<List<BytecodeToken>> out) {
        int depth = 0;
        for (List<BytecodeToken> l : out) {
            String m = mn(l);
            if ("CC_START".equals(m)) depth++;
            else if ("CC_END".equals(m)) depth--;
            else if (depth == 0 && "POP".equals(m) && l.size() == 3 && (l.get(1).text.startsWith("ARG") || l.get(1).text.startsWith("FARG"))) return true;
        }
        return false;
    }

    /**
     * regsLoaded: this region sits inside the argument list of an enclosing call that has already loaded an argument register. Inlined code
     * runs with no register protection (a real nested call saves and restores the loaded argument registers around itself; the backend's own
     * block copies, shifts and divides use rdi, rsi, rdx, rcx as scratch), so a call site in that position is never inlined.
     */
    private List<List<BytecodeToken>> expandRange(List<List<BytecodeToken>> L, int from, int to, Ctx cx, boolean baseClean, boolean regsLoaded) {
        List<List<BytecodeToken>> out = new ArrayList<>();
        int k = from;
        while (k < to) {
            List<BytecodeToken> line = L.get(k);
            if (!is(line, 2, "CC_START")) {
                out.add(line);
                k++;
                continue;
            }
            int e = matchEnd(L, k, to);
            if (e < 0) {
                out.add(line);
                k++;
                continue;
            }
            List<BytecodeToken> callLine = L.get(e - 1);
            boolean isCall = e - 1 > k && is(callLine, 2, "CALL");
            int argsEnd = isCall ? e - 1 : e; // exclusive end of the argument region
            int cut = stagedLabel(out) != null ? 3 : 0;
            int cls = classify(out, cut, baseClean);
            boolean loaded = regsLoaded || argRegisterLoaded(out);
            List<List<BytecodeToken>> args = expandRange(L, k + 1, argsEnd, cx, cls == 0, loaded);
            boolean hasRet = e + 1 < to && is(L.get(e + 1), 2, "PUSH_RET");
            boolean done = false;
            if (isCall) {
                Callee c = cx.callees.get(callLine.get(1).text);
                if (c != null && c.eligible && !c.name.equals(cx.caller) && !loaded) {
                    done = tryInline(c, line, args, hasRet ? L.get(e + 1) : null, out, cut, cls, cx);
                }
            }
            if (done) {
                k = e + 1 + (hasRet ? 1 : 0);
            } else {
                out.add(line);
                out.addAll(args);
                if (isCall) out.add(callLine);
                out.add(L.get(e));
                k = e + 1;
            }
        }
        return out;
    }

    /** True if `l` is empty or made only of null-out triples "ADDR name T / PUSH null T / ASSIGN T T T" (a moved owns variable being cleared). */
    private static boolean moveNullOuts(List<List<BytecodeToken>> l) {
        if (l.size() % 3 != 0) return false;
        for (int i = 0; i < l.size(); i += 3) {
            List<BytecodeToken> a = l.get(i), b = l.get(i + 1), c = l.get(i + 2);
            if (!(is(a, 3, "ADDR") && is(b, 3, "PUSH") && b.get(1).text.equals("null") && is(c, 4, "ASSIGN"))) return false;
        }
        return true;
    }

    /** The label staged for this call site by "ADDR gt_routine_address / PUSH_LABEL X / ASSIGN" (a landing pad or a catch), or null. */
    private String stagedLabel(List<List<BytecodeToken>> out) {
        int n = out.size();
        if (n < 3) return null;
        List<BytecodeToken> a = out.get(n - 3), b = out.get(n - 2), c = out.get(n - 1);
        boolean ok = is(a, 3, "ADDR") && a.get(1).text.equals("gt_routine_address") && is(b, 3, "PUSH_LABEL")
                && (b.get(1).text.startsWith("@gt_callsite__") || b.get(1).text.startsWith("@catch_")) && is(c, 4, "ASSIGN");
        return ok ? b.get(1).text : null;
    }

    private static boolean boundary(List<BytecodeToken> l) {
        if (l.isEmpty()) return false;
        if (isLabelDef(l)) return true;
        return BOUNDARY.contains(mn(l));
    }

    private boolean tryInline(Callee c, List<BytecodeToken> ccStart, List<List<BytecodeToken>> args, List<BytecodeToken> pushRet,
            List<List<BytecodeToken>> out, int cut, int cls, Ctx cx) {
        // ---- split the argument region into per-parameter segments
        List<List<List<BytecodeToken>>> segs = new ArrayList<>();
        List<List<BytecodeToken>> pops = new ArrayList<>();
        List<List<BytecodeToken>> cur = new ArrayList<>();
        int depth = 0;
        for (List<BytecodeToken> l : args) {
            String m = mn(l);
            if ("CC_START".equals(m)) depth++;
            if (depth == 0) {
                if ("VARARGS_XMM_COUNT".equals(m) || "DUP_TOP".equals(m) || "CALL".equals(m) || "EXTERN_CALL".equals(m) || "INVOKE".equals(m)) return false;
                if ("POP".equals(m)) {
                    if (l.size() != 3 || !(l.get(1).text.startsWith("ARG") || l.get(1).text.startsWith("FARG"))) return false;
                    segs.add(cur);
                    pops.add(l);
                    cur = new ArrayList<>();
                    continue;
                }
            }
            if ("CC_END".equals(m)) depth--;
            cur.add(l);
        }
        // What follows the last POP: the owns-move null-out of the variable just passed (`ADDR o / PUSH null / ASSIGN`, one triple per moved
        // owns argument). It runs after the argument is read and before the callee starts, so it goes right after the parameter copies.
        List<List<BytecodeToken>> post = cur;
        if (depth != 0 || !moveNullOuts(post)) return false;
        int words = 0;
        for (String[] pp : c.params) words += wordsOf(pp[1]);
        if (segs.size() != words) return false;
        for (int i = 0; i < segs.size(); i++) {
            if (!scalarType(pops.get(i).get(2).text)) return false;
        }
        // ---- site context
        boolean valueMode = pushRet != null;
        boolean clean = cls == 0;
        boolean addr1 = cls == 1;
        int n = out.size() - cut;
        List<BytecodeToken> addrLine = addr1 ? out.get(n - 1) : null;
        if (addr1 && !valueMode) return false;
        // ---- callee shape vs. site
        boolean voidCallee = c.rets.isEmpty() || c.rets.get(0).isVoid;
        for (Ret r : c.rets) {
            if (r.isVoid != voidCallee) return false;
        }
        if (valueMode && voidCallee && !c.rets.isEmpty()) return false;
        if (valueMode && c.rets.isEmpty()) return false; // a value call whose callee never returns a value
        String mode;
        if (!valueMode) {
            mode = "DISCARD";
            if (!voidCallee) {
                for (Ret r : c.rets) {
                    if (r.vStart < 0 || !r.pure) return false;
                }
            }
        } else {
            boolean stackOk = !c.controlFlow && c.rets.size() == 1 && c.rets.get(0).tail && c.rets.get(0).vStart >= 0;
            if (addr1 && (c.controlFlow || !c.balanced)) stackOk = false;
            if (stackOk) {
                mode = "STACK";
            } else {
                mode = "TEMP";
                for (Ret r : c.rets) {
                    if (r.vStart < 0) return false;
                }
            }
        }
        // A body that leaves words behind (an ignored call result) must not run with operands beneath it: they would sit between the
        // pending operands and the code that consumes them (a STACK-mode value must be directly above the address beneath it). Only
        // the result-variable form with one address beneath is safe, because the address is pushed again after the copy.
        if (!c.balanced && cls != 0 && !(cls == 1 && mode.equals("TEMP"))) return false;
        // ---- growth
        long est = (long) c.code.size() + c.allocs.size() + words + c.params.size() * 2L + 4;
        long cap = cfg.maxGrowth;
        if (cfg.growthFactor > 0) cap = Math.min(cap, Math.max(InlineConfig.GROWTH_FLOOR, cfg.growthFactor * origSize.getOrDefault(cx.caller, 1L)));
        if (c.forced) {
            cap = Long.MAX_VALUE;   // @inline: the size limits of the preset do not apply (only the safety rules above and below)
        }
        if (grown.getOrDefault(cx.caller, 0L) + cx.added + est > cap) return false;
        if (!c.forced && cfg.totalFactor > 0 && totalAdded + est > Math.max(InlineConfig.TOTAL_FLOOR, cfg.totalFactor * origProgram)) {
            if (System.getenv("CASPIEN_INLINE_WHY") != null) System.err.println("[inline] program growth budget reached: " + cx.caller + " keeps a call to " + c.name);
            return false;
        }
        // A big callee with several call sites would be copied into each of them; measured to lose more than it gains (see InlineConfig).
        if (!c.forced && cfg.maxMultiCalleeLines > 0 && c.lines > cfg.maxMultiCalleeLines && c.calls > 1) {
            if (System.getenv("CASPIEN_INLINE_WHY") != null) System.err.println("[inline] " + c.name + " (" + c.lines + " lines, " + c.calls + " call sites) stays a call in " + cx.caller);
            return false;
        }
        // A callee with a catch of its own is entered by a real unwind with whatever the statement had pushed at the call still on the
        // operand stack (the callee's frame is not there to reset rsp any more), and its catch then falls out through the end label. With
        // operands of an enclosing expression beneath the site, those extra words would sit between them and the code that consumes them.
        if (c.hasCatch && cls == 2) return false;
        // ---- unwinding: the callee leaves through the label the caller staged for this call
        String unwindTo = cut == 3 ? out.get(out.size() - 2).get(1).text : null;
        if (c.unwinds && unwindTo == null) return false;
        if (c.usesAddr && !cx.callerNames.contains("gt_routine_address")) return false;
        if (c.usesMsg && !cx.callerNames.contains("gt_error_message")) return false;
        // A jump straight into a catch label lands on the backend's float-variable reload; RegVarPromotionPass puts an R_XSPILL before
        // every such jump, so float variables need no special treatment here.
        // ---- global-name capture: the callee's globals must not be shadowed by a caller local
        for (String g : c.globals) {
            if (cx.callerNames.contains(g)) return false;
        }

        // ---- build
        int inst = fresh++;
        Map<String, String> local = new HashMap<>();
        for (String[] p : c.params) local.put(p[0], renameVar(p[0], inst));
        for (String[] a : c.allocs) local.put(a[0], renameVar(a[0], inst));
        Map<String, String> lab = new HashMap<>();
        for (String lb : c.labels) lab.put(lb, renameLabel(lb, inst));
        String endLabel = "@inl_end_" + (fresh++);
        BytecodeToken ref = ccStart.get(0);
        String retVar = "__ret_i" + inst;

        List<List<BytecodeToken>> blk = new ArrayList<>();
        int sg = 0;
        for (String[] pp : c.params) {
            String pn = local.get(pp[0]);
            String pt = pp[1];
            blk.add(mk(ref, "ADDR", pn, pt));
            if (rangeParam(pt)) {
                // the two argument words (start, end) are built into the range value exactly as a `lo..hi` literal is
                blk.addAll(segs.get(sg));
                blk.addAll(segs.get(sg + 1));
                blk.add(mk(ref, "ASSIGN", pt, pt, pt));
                sg += 2;
            } else {
                blk.addAll(segs.get(sg));
                blk.add(mk(ref, "ASSIGN", pt, pops.get(sg).get(2).text, pt));
                sg++;
            }
        }
        blk.addAll(post);
        String retT = null;
        for (Ret r : c.rets) {
            if (!r.isVoid) retT = r.type;
        }
        Set<Integer> skip = new HashSet<>();
        Map<Integer, List<List<BytecodeToken>>> before = new HashMap<>();
        Map<Integer, List<List<BytecodeToken>>> after = new HashMap<>();
        boolean usesEnd = false;
        for (Ret r : c.rets) {
            if (!r.isVoid && mode.equals("DISCARD")) {
                for (int q = r.vStart; q <= r.vTail; q++) skip.add(q);
            }
            if (!r.isVoid && mode.equals("TEMP")) {
                before.computeIfAbsent(r.vStart, z -> new ArrayList<>()).add(mk(ref, "ADDR", retVar, retT));
                after.computeIfAbsent(r.vTail, z -> new ArrayList<>()).add(mk(ref, "ASSIGN", retT, retT, retT));
            }
            if (mode.equals("STACK")) {
                skip.add(r.idx);
            } else if (r.tail) {
                skip.add(r.idx);
            } else {
                usesEnd = true;
            }
        }
        for (int i = 0; i < c.code.size(); i++) {
            List<List<BytecodeToken>> b = before.get(i);
            if (b != null) blk.addAll(b);
            if (!skip.contains(i)) {
                List<BytecodeToken> l = c.code.get(i);
                Ret rr = null;
                for (Ret r : c.rets) {
                    if (r.idx == i) rr = r;
                }
                if (rr != null) {
                    blk.add(mk(ref, "JMP", endLabel));
                } else if ("GT_UNWIND".equals(mn(l))) {
                    blk.add(mk(ref, "JMP", unwindTo));
                } else {
                    blk.add(rename(l, local, lab));
                }
            }
            List<List<BytecodeToken>> a = after.get(i);
            if (a != null) blk.addAll(a);
        }
        if (usesEnd) blk.add(mk(ref, endLabel + ":"));
        if (mode.equals("TEMP")) {
            if (addr1) blk.add(addrLine);
            blk.add(mk(ref, "PUSH", retVar, retT));
        }

        // ---- commit
        for (int i = 0; i < cut; i++) out.remove(out.size() - 1);
        if (mode.equals("TEMP") && addr1) out.remove(out.size() - 1);
        out.addAll(blk);
        int newLocals = c.params.size() + c.allocs.size() + (mode.equals("TEMP") ? 1 : 0);
        for (String[] p : c.params) cx.newAllocs.add(mk(ref, "ALLOC", local.get(p[0]), renameRangeText(p[1], local)));
        for (String[] a : c.allocs) cx.newAllocs.add(mk(ref, "ALLOC", local.get(a[0]), renameRangeText(a[1], local)));
        if (mode.equals("TEMP")) cx.newAllocs.add(mk(ref, "ALLOC", retVar, retT));
        cx.callerNames.addAll(local.values());
        cx.added += blk.size() + newLocals;
        totalAdded += blk.size() + newLocals;
        cx.changed = true;
        inlinedSites++;
        if (c.forced) {
            forcedSites.merge(c.name, 1, Integer::sum);
            forcedPos.putIfAbsent(c.name, c.pos);
        }
        return true;
    }

    private String renameVar(String name, int inst) {
        if (name.startsWith("$for_range_") && allDigits(name.substring("$for_range_".length()))) {
            return "$for_range_" + (fresh++);
        }
        return name + "__i" + inst;
    }

    private String renameLabel(String lb, int inst) {
        String x = lb.substring(1); // drop '@'
        int us = x.lastIndexOf('_');
        if (us >= 0 && allDigits(x.substring(us + 1))) {
            return "@" + x.substring(0, us + 1) + (fresh++);
        }
        return "@" + x + "_i" + inst + "_" + (fresh++);
    }

    private static List<BytecodeToken> rename(List<BytecodeToken> l, Map<String, String> local, Map<String, String> lab) {
        boolean touch = false;
        List<BytecodeToken> out = new ArrayList<>(l.size());
        String m = mn(l);
        for (int t = 0; t < l.size(); t++) {
            BytecodeToken tk = l.get(t);
            if (tk.kind != BytecodeToken.Kind.CODE || t == 0 && !isLabelDef(l)) {
                out.add(tk);
                continue;
            }
            String x = tk.text;
            if (x.startsWith("@")) {
                boolean colon = x.endsWith(":");
                String nm = colon ? x.substring(0, x.length() - 1) : x;
                String r = lab.get(nm);
                if (r != null) {
                    out.add(retext(tk, r + (colon ? ":" : "")));
                    touch = true;
                    continue;
                }
                out.add(tk);
                continue;
            }
            if (t == 1 && NAME_POS.contains(m)) {
                int dot = x.indexOf('.');
                String root = dot > 0 ? x.substring(0, dot) : x;
                String r = local.get(root);
                if (r != null) {
                    out.add(retext(tk, dot > 0 ? r + x.substring(dot) : r));
                    touch = true;
                    continue;
                }
            }
            if (x.indexOf("range(") >= 0) {
                String y = renameRangeText(x, local);
                if (!y.equals(x)) {
                    out.add(retext(tk, y));
                    touch = true;
                    continue;
                }
            }
            out.add(tk);
        }
        return touch ? out : l;
    }
}

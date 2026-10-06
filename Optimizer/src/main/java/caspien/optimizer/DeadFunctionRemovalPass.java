package caspien.optimizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes every function that is never called and never has its address taken (switch: {@code dead-function-removal: on|off}, default off).
 *
 * Does nothing at all when the program contains an {@code INVOKE} (a call through a function pointer: the target is not known statically and
 * a pointer value could reach any function), and does nothing when there is no {@code main} (a library: everything is an entry point).
 *
 * Otherwise a function is live when it is reachable from a root through CALL / RECURSIVE_CALL sites or through any other line that names it as an
 * operand (a function whose address is taken, e.g. {@code PUSH f static_imut_func(..)}, counts as used). Roots: {@code main}; any {@code export}ed function (it carries an {@code EXPORT} line and is called from C); the four ghost-table hooks
 * {@code gt_init}, {@code gt_register}, {@code gt_alive_check}, {@code gt_destruct}, which the backend calls by fixed name with no CALL in the bytecode;
 * any function carrying a decorator other than {@code @pub}/{@code @throws}/{@code @recursive}/{@code @pure} (the compiler looks such functions up by
 * decorator, e.g. @gt_*, @par_call, @await_call, @sleep, @async, @lock, @unlock, @guard); and any function named on a line outside every function (global initialisers). Reachability, rather than
 * "is there a call site", means two functions that only call each other, or a function that only calls itself, are removed too. Only complete
 * FUNC_START..FUNC_END blocks are deleted. Most effective right after DeadControlFlowRemovalPass, which is what turns "called from a removed
 * branch" into "never called".
 */
public class DeadFunctionRemovalPass implements OptimizationPass {

    private final boolean enabled;

    public DeadFunctionRemovalPass() {
        this(false);
    }

    public DeadFunctionRemovalPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "dead-function-removal";
    }

    private static final Set<String> HOOKS = new HashSet<>(List.of("gt_init", "gt_register", "gt_alive_check", "gt_destruct", "gt_moved"));
    private static final Set<String> INERT_DECORATORS = new HashSet<>(List.of("@pub", "@throws", "@recursive", "@pure", "@pure(rt)", "@non(deterministic)", "@inline", "@dont(inline)"));

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        List<int[]> funcs = VarAnalysis.functions(lines);
        if (funcs.isEmpty()) {
            return new PassResult(lines, false);
        }
        Map<String, Integer> byName = new HashMap<>();   // function name -> index into funcs
        for (int k = 0; k < funcs.size(); k++) {
            List<BytecodeToken> st = lines.get(funcs.get(k)[0]);
            if (st.size() >= 2) byName.put(st.get(1).text, k);
        }
        if (!byName.containsKey("main")) {
            return new PassResult(lines, false);
        }
        for (List<BytecodeToken> l : lines) {
            if ("INVOKE".equals(VarAnalysis.mnemonic(l))) {
                return new PassResult(lines, false);
            }
        }
        boolean[] live = new boolean[funcs.size()];
        ArrayDeque<Integer> work = new ArrayDeque<>();
        // roots
        for (Map.Entry<String, Integer> en : byName.entrySet()) {
            int k = en.getValue();
            if (en.getKey().equals("main") || HOOKS.contains(en.getKey()) || hasActiveDecorator(lines, funcs.get(k)) || isExported(lines, funcs.get(k))) {
                markLive(live, work, k);
            }
        }
        // references from outside every function
        int fi = 0;
        for (int i = 0; i < lines.size(); i++) {
            while (fi < funcs.size() && funcs.get(fi)[1] < i) fi++;
            boolean inside = fi < funcs.size() && funcs.get(fi)[0] <= i && i <= funcs.get(fi)[1];
            if (inside) continue;
            for (BytecodeToken t : lines.get(i)) {
                if (t.kind != BytecodeToken.Kind.CODE) continue;
                Integer k = byName.get(t.text);
                if (k != null) markLive(live, work, k);
            }
        }
        while (!work.isEmpty()) {
            int k = work.poll();
            int[] f = funcs.get(k);
            for (int i = f[0] + 1; i < f[1]; i++) {
                for (BytecodeToken t : lines.get(i)) {
                    if (t.kind != BytecodeToken.Kind.CODE) continue;
                    Integer c = byName.get(t.text);
                    if (c != null) markLive(live, work, c);
                }
            }
        }
        Set<Integer> remove = new HashSet<>();
        boolean any = false;
        for (int k = 0; k < funcs.size(); k++) {
            if (live[k]) continue;
            any = true;
            for (int i = funcs.get(k)[0]; i <= funcs.get(k)[1]; i++) remove.add(i);
        }
        if (!any) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (!remove.contains(i)) out.add(lines.get(i));
        }
        return new PassResult(out, true);
    }

    private static void markLive(boolean[] live, ArrayDeque<Integer> work, int k) {
        if (!live[k]) {
            live[k] = true;
            work.add(k);
        }
    }

    /** An `export`ed function (it carries an EXPORT line) is called from C, so it is a root even when nothing in Caspien calls it. */
    private static boolean isExported(List<List<BytecodeToken>> L, int[] f) {
        for (int i = f[0] + 1; i < f[1]; i++) {
            if ("EXPORT".equals(VarAnalysis.mnemonic(L.get(i)))) return true;
        }
        return false;
    }

    /** A function is a root when one of the FUNC_DECORATE lines right after its FUNC_START is not one of the inert ones. */
    private static boolean hasActiveDecorator(List<List<BytecodeToken>> L, int[] f) {
        for (int i = f[0] + 1; i < f[1]; i++) {
            List<BytecodeToken> l = L.get(i);
            if (!"FUNC_DECORATE".equals(VarAnalysis.mnemonic(l))) break;
            if (l.size() >= 2 && !INERT_DECORATORS.contains(l.get(1).text)) return true;
        }
        return false;
    }
}

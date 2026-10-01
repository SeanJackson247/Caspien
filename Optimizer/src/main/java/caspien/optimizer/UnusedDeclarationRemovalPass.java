package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes declarations nothing refers to any more (switch: {@code unused-declaration-removal: on|off}, default off): {@code EXTERN} lines, {@code GLOBAL}
 * and {@code ALLOC_STATIC} declarations (a name and its {@code name.member} lines), and {@code STRING} literals. It is the follow-on to
 * DeadFunctionRemovalPass and DeadControlFlowRemovalPass: a function or branch that disappeared often leaves its externs, statics and strings behind.
 *
 * A declaration is used when any CODE token on any other line is its name (for a global also {@code name.member}); tokens of quoted strings are not
 * references. Kept unconditionally: the externs the backend calls by fixed name with no reference in the bytecode ({@code malloc}, {@code realloc},
 * {@code strlen}, {@code exit}, {@code pthread_exit}, {@code sched_yield}, {@code free}) and the global {@code ghost_table}, whose lock state the backend
 * reads and writes directly. An {@code ALLOC_STATIC} whose name is declared more than once (a function-local static of the same name in several
 * functions) is left alone. The whole pass does nothing when the program has no {@code main} (a library exports its declarations) or contains
 * inline assembly ({@code ASM_START}, whose text may name symbols).
 */
public class UnusedDeclarationRemovalPass implements OptimizationPass {

    private final boolean enabled;

    public UnusedDeclarationRemovalPass() {
        this(false);
    }

    public UnusedDeclarationRemovalPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "unused-declaration-removal";
    }

    private static final Set<String> IMPLICIT_EXTERNS = new HashSet<>(List.of(
            "malloc", "realloc", "free", "strlen", "exit", "pthread_exit", "sched_yield"));
    private static final Set<String> IMPLICIT_GLOBALS = new HashSet<>(List.of("ghost_table"));

    private static String base(String name) {
        int d = name.indexOf('.');
        return d < 0 ? name : name.substring(0, d);
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        boolean hasMain = false;
        for (List<BytecodeToken> l : lines) {
            String m = VarAnalysis.mnemonic(l);
            if ("ASM_START".equals(m)) return new PassResult(lines, false);
            if ("FUNC_START".equals(m) && l.size() == 2 && l.get(1).text.equals("main")) hasMain = true;
        }
        if (!hasMain) {
            return new PassResult(lines, false);
        }
        // declaration name -> line indices that declare it (externs and strings: one line; globals/statics: the base line and every base.member line)
        Map<String, List<Integer>> decls = new HashMap<>();
        Map<String, Integer> staticBaseCount = new HashMap<>();
        Set<String> staticNames = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            List<BytecodeToken> l = lines.get(i);
            String m = VarAnalysis.mnemonic(l);
            if (m == null || l.size() < 2) continue;
            String n = l.get(1).text;
            switch (m) {
                case "EXTERN":
                case "STRING":
                    decls.computeIfAbsent(m + ":" + n, k -> new ArrayList<>()).add(i);
                    break;
                case "GLOBAL":
                    decls.computeIfAbsent("GLOBAL:" + base(n), k -> new ArrayList<>()).add(i);
                    break;
                case "ALLOC_STATIC":
                    decls.computeIfAbsent("ALLOC_STATIC:" + base(n), k -> new ArrayList<>()).add(i);
                    if (n.indexOf('.') < 0) staticBaseCount.merge(n, 1, Integer::sum);
                    staticNames.add(base(n));
                    break;
                default:
                    break;
            }
        }
        if (decls.isEmpty()) {
            return new PassResult(lines, false);
        }
        // every name mentioned by a CODE token on a line that does not itself declare it
        Set<String> used = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            List<BytecodeToken> l = lines.get(i);
            String m = VarAnalysis.mnemonic(l);
            String own = null;
            if (m != null && l.size() >= 2 && (m.equals("EXTERN") || m.equals("STRING"))) own = m + ":" + l.get(1).text;
            else if (m != null && l.size() >= 2 && (m.equals("GLOBAL") || m.equals("ALLOC_STATIC"))) own = m + ":" + base(l.get(1).text);
            for (int k = 0; k < l.size(); k++) {
                BytecodeToken t = l.get(k);
                if (t.kind != BytecodeToken.Kind.CODE) continue;
                if (k == 0 && own != null) continue;   // the mnemonic itself
                String b = base(t.text);
                for (String key : new String[] {"EXTERN:" + t.text, "STRING:" + t.text, "GLOBAL:" + b, "ALLOC_STATIC:" + b}) {
                    if (decls.containsKey(key) && !key.equals(own)) used.add(key);
                }
                // a declaration line's own name token (token 1) is not a use of itself; a later token naming the same thing is not either
            }
        }
        Set<Integer> remove = new HashSet<>();
        for (Map.Entry<String, List<Integer>> en : decls.entrySet()) {
            String key = en.getKey();
            if (used.contains(key)) continue;
            String nm = key.substring(key.indexOf(':') + 1);
            if (key.startsWith("EXTERN:") && IMPLICIT_EXTERNS.contains(nm)) continue;
            if (key.startsWith("GLOBAL:") && IMPLICIT_GLOBALS.contains(nm)) continue;
            if (key.startsWith("ALLOC_STATIC:") && staticBaseCount.getOrDefault(nm, 0) != 1) continue;
            remove.addAll(en.getValue());
        }
        if (remove.isEmpty()) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (!remove.contains(i)) out.add(lines.get(i));
        }
        return new PassResult(out, true);
    }
}

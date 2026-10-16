package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.bytecode.BytecodeTokenKind;

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
public class UnusedDeclarationRemovalPass {

    private final boolean enabled;

    public UnusedDeclarationRemovalPass() { }

    public UnusedDeclarationRemovalPass(boolean enabled) { }

    private static final Set<String> IMPLICIT_EXTERNS = new HashSet<>(List.of(
            "malloc", "realloc", "free", "strlen", "exit", "pthread_exit", "sched_yield"));
    private static final Set<String> IMPLICIT_GLOBALS = new HashSet<>(List.of("ghost_table"));

    private static String base(String name) { return null; }

    public PassResult run(BytecodeProgram lines) { return null; }

    private static void addDecl(Map<String, List<Integer>> decls, String key, int i) { }
}

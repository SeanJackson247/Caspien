package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.bytecode.BytecodeTokenKind;

import java.util.ArrayDeque;
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
public class DeadFunctionRemovalPass {

    private final boolean enabled;

    public DeadFunctionRemovalPass() { }

    public DeadFunctionRemovalPass(boolean enabled) { }

    private static final Set<String> HOOKS = new HashSet<>(List.of("gt_init", "gt_register", "gt_alive_check", "gt_destruct", "gt_moved"));
    private static final Set<String> INERT_DECORATORS = new HashSet<>(List.of("@pub", "@throws", "@recursive", "@pure", "@pure(rt)", "@non(deterministic)", "@inline", "@dont(inline)"));

    public PassResult run(BytecodeProgram lines) { return null; }

    private static void markLive(boolean[] live, ArrayDeque<Integer> work, int k) { }

    /** An `export`ed function (it carries an EXPORT line) is called from C, so it is a root even when nothing in Caspien calls it. */
    private static boolean isExported(BytecodeProgram L, int[] f) { return false; }

    /** A function is a root when one of the FUNC_DECORATE lines right after its FUNC_START is not one of the inert ones. */
    private static boolean hasActiveDecorator(BytecodeProgram L, int[] f) { return false; }
}

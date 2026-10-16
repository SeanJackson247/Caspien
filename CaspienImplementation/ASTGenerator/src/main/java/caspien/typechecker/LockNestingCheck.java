package caspien.typechecker;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;

import java.util.*;

/**
 * Nested locks are banned: while a user lock is held (the OPEN case of `match @lock`, or the body of a `lock x{}` block), nothing may acquire another one,
 * neither directly nor through any function it calls. Ordering is the only thing that keeps nested spin locks from deadlocking, and the language has no
 * lock order, so there is no safe nesting to allow (the same lock again can never succeed: the lock records no owner).
 *
 * The exemption is the ghost table: its own lock is taken by the `@gt_*` functions and everything they call, from every allocation, `match Some` and drop.
 * It is always the innermost lock, held briefly, and that code never calls user code, so it cannot be part of a cycle. Calls into `@gt_*` are made by the
 * code generator and never appear as call tokens, and functions reachable from the `@gt_*` functions are skipped here, so they are exempt by construction.
 * A `call()` through a function pointer is not followed (it is `unsafe call`, the programmer vouches for it).
 *
 * Second rule, same walk: nothing may WAIT while a lock is held (`yield`, `sleep`, `par`, `await`, directly or through any function it calls). A lock holder
 * then only runs code that depends on no other thread, so it always lets go, and a thread spinning on the lock is waiting on code that cannot be waiting on
 * anything. (Externs and `unsafe` loops are not judged: they are already marked `unsafe` and the programmer vouches for them.)
 */
public final class LockNestingCheck {


    public final Map<String, LockNestingInfo> byName = new LinkedHashMap<>();
    public final Set<String> exempt;

    public LockNestingCheck(Set<String> exempt) { }

    public static void run(TypeChecker checker, Set<String> exempt) { }

    public void add(FuncInfo f) { }

    public void collect(TypeChecker checker) { }

    /** Pre-order walk with an explicit stack ({token, held}); children are pushed in reverse so the visiting order is the recursive one. */
    public void walk(Token start, boolean startHeld, LockNestingInfo in) { }

    /** Pushes left, right, next, childs, sub so that they pop in that order. */
    private static void pushChildren(Deque<Object[]> work, Token t, boolean held) { }

    /** "yield", "sleep", "par" or "await" when `t` is that operation, else null. */
    public static String waitKindOf(Token t) { return null; }

    public void acquire(Token t, boolean held, LockNestingInfo in) { }

    /** The calls a function makes, then (shortest first) the chain down to a function that takes a lock; null when none does. */
    public List<String> pathToLock(String from, Set<String> seen) { return null; }

    /** The calls a function makes down to a function that waits, shortest first; null when none does. */
    public List<String> pathToWait(String from, Set<String> seen) { return null; }

    /** Depth-first search over callees; the open frames are the path. forWait: stop at a waiting function, else at one that takes a lock. */
    private List<String> pathTo(String from, Set<String> seen, boolean forWait) { return null; }

    public void check() { }
}

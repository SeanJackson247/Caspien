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

    public LockNestingCheck(Set<String> exempt) {
        this.exempt = exempt;
    }

    public static void run(TypeChecker checker, Set<String> exempt) {
        LockNestingCheck c = new LockNestingCheck(exempt);
        c.collect(checker);
        c.check();
    }

    public void add(FuncInfo f) {
        if (f == null || f.funcToken == null || f.mangledName == null || byName.containsKey(f.mangledName) || exempt.contains(f.mangledName)) {
            return;
        }
        LockNestingInfo in = new LockNestingInfo(f);
        byName.put(f.mangledName, in);
        walk(f.funcToken, false, in);
    }

    public void collect(TypeChecker checker) {
        for (List<FuncInfo> l : checker.declarations.getFunctions().values()) {
            l.forEach(this::add);
        }
        for (List<ImplInfo> l : checker.declarations.getImpls().values()) {
            for (ImplInfo impl : l) {
                impl.methods.values().forEach(this::add);
                impl.staticMethods.values().forEach(this::add);
                impl.genericMethodInstances.values().forEach(this::add);
            }
        }
        for (LibraryInfo lib : checker.declarations.getLibraries().values()) {
            for (List<FuncInfo> l : lib.ownFunctions.values()) {
                l.forEach(this::add);
            }
        }
    }

    /** Pre-order walk with an explicit stack ({token, held}); children are pushed in reverse so the visiting order is the recursive one. */
    public void walk(Token start, boolean startHeld, LockNestingInfo in) {
        Deque<Object[]> work = new ArrayDeque<>();
        work.push(new Object[]{start, startHeld});
        while (!work.isEmpty()) {
            Object[] item = work.pop();
            Token t = (Token) item[0];
            boolean held = (Boolean) item[1];
            if (t == null) {
                continue;
            }
            if (t.chk.isLockSpinLoop) {
                acquire(t, held, in);
                Token ifTok = t.childs != null && !t.childs.isEmpty() && !t.childs.get(0).childs.isEmpty() ? t.childs.get(0).childs.get(0) : null;
                if (ifTok == null) {
                    pushChildren(work, t, held);
                    continue;
                }
                if (t.chk.preLoopInit != null) {
                    for (int i = t.chk.preLoopInit.size() - 1; i >= 0; i--) {
                        work.push(new Object[]{t.chk.preLoopInit.get(i), held});
                    }
                }
                work.push(new Object[]{ifTok.next, held});                                           // CLOSED case: not held
                for (int i = ifTok.childs.size() - 1; i >= 0; i--) {
                    work.push(new Object[]{ifTok.childs.get(i), true});                              // OPEN case: the lock is held
                }
                work.push(new Object[]{ifTok.sub == null || ifTok.sub.isEmpty() ? null : ifTok.sub.get(0), held});   // the acquire attempt
                continue;
            }
            String waitKind = waitKindOf(t);
            if (waitKind != null) {
                if (held) {
                    throw new CompilerException("type", t.file, t.line, "'" + waitKind + "' while a lock is held -- nothing may wait inside a critical section (the thread waited for "
                            + "may need this very lock, and a lock holder must always let go); release the lock first");
                }
                if (in.waitKind == null) {
                    in.waitKind = waitKind;
                }
            }
            if (t.chk.resolvedCallTarget != null) {
                in.callees.add(t.chk.resolvedCallTarget);
                if (held) {
                    in.heldCalls.add(t);
                }
            }
            pushChildren(work, t, held);
        }
    }

    /** Pushes left, right, next, childs, sub so that they pop in that order. */
    private static void pushChildren(Deque<Object[]> work, Token t, boolean held) {
        if (t.sub != null) {
            for (int i = t.sub.size() - 1; i >= 0; i--) {
                work.push(new Object[]{t.sub.get(i), held});
            }
        }
        if (t.childs != null) {
            for (int i = t.childs.size() - 1; i >= 0; i--) {
                work.push(new Object[]{t.childs.get(i), held});
            }
        }
        work.push(new Object[]{t.next, held});
        work.push(new Object[]{t.right, held});
        work.push(new Object[]{t.left, held});
    }

    /** "yield", "sleep", "par" or "await" when `t` is that operation, else null. */
    public static String waitKindOf(Token t) {
        if (t.type == TokenType.KEYWORD && t.text.equals("yield")) {
            return "yield";
        }
        if (t.type == TokenType.OPERATOR && (t.text.equals("par") || t.text.equals("await"))) {
            return t.text;
        }
        if (t.type == TokenType.OPERATOR && t.text.equals("CALL") && t.left != null && t.left.type == TokenType.KEYWORD && t.left.text.equals("sleep")) {
            return "sleep";
        }
        return null;
    }

    public void acquire(Token t, boolean held, LockNestingInfo in) {
        in.acquires = true;
        if (held) {
            throw new CompilerException("type", t.file, t.line, "a lock is taken while another lock is held -- nested locks are not allowed (the lock records no owner, so the same "
                    + "lock can never be taken again, and different locks need an order the language does not have); release the first one before taking the second");
        }
    }

    /** The calls a function makes, then (shortest first) the chain down to a function that takes a lock; null when none does. */
    public List<String> pathToLock(String from, Set<String> seen) {
        return pathTo(from, seen, in -> in.acquires);
    }

    /** The calls a function makes down to a function that waits, shortest first; null when none does. */
    public List<String> pathToWait(String from, Set<String> seen) {
        return pathTo(from, seen, in -> in.waitKind != null);
    }

    /** Depth-first search over callees; the open frames are the path. */
    private List<String> pathTo(String from, Set<String> seen, java.util.function.Predicate<LockNestingInfo> hit) {
        List<String> names = new ArrayList<>();
        Deque<Iterator<String>> iters = new ArrayDeque<>();
        String enter = from;
        while (true) {
            if (enter != null) {
                LockNestingInfo in = byName.get(enter);
                if (in == null || !seen.add(enter)) {
                    enter = null;
                } else if (hit.test(in)) {
                    names.add(enter);
                    return names;
                } else {
                    names.add(enter);
                    iters.push(in.callees.iterator());
                    enter = null;
                }
            }
            if (iters.isEmpty()) {
                return null;
            }
            Iterator<String> it = iters.peek();
            if (it.hasNext()) {
                enter = it.next();
            } else {
                iters.pop();
                names.remove(names.size() - 1);
            }
        }
    }

    public void check() {
        for (LockNestingInfo in : byName.values()) {
            for (Token call : in.heldCalls) {
                List<String> w = pathToWait(call.chk.resolvedCallTarget, new HashSet<>());
                if (w != null) {
                    throw new CompilerException("type", call.file, call.line, "'" + call.chk.resolvedCallTarget + "' is called while a lock is held, and it waits ('"
                            + byName.get(w.get(w.size() - 1)).waitKind + "'" + (w.size() > 1 ? ", through " + String.join(" > ", w) : "") + ") -- nothing may wait inside a critical section");
                }
                List<String> p = pathToLock(call.chk.resolvedCallTarget, new HashSet<>());
                if (p != null) {
                    throw new CompilerException("type", call.file, call.line, "'" + call.chk.resolvedCallTarget + "' is called while a lock is held, and it takes a lock"
                            + (p.size() > 1 ? " (through " + String.join(" > ", p) + ")" : "") + " -- nested locks are not allowed, directly or through calls; the standard "
                            + "library's own allocation lock is the only exception");
                }
            }
        }
    }
}

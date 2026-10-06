package caspien;

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
 */
final class LockNestingCheck {
    private static final class Info {
        final TypeChecker.FuncInfo fn;
        boolean acquires;                                         // takes a lock directly
        final Set<String> callees = new LinkedHashSet<>();         // every resolved call target in the body
        final List<Token> heldCalls = new ArrayList<>();           // calls made while a lock is held
        Info(TypeChecker.FuncInfo fn) {
            this.fn = fn;
        }
    }

    private final Map<String, Info> byName = new LinkedHashMap<>();
    private final Set<String> exempt;

    private LockNestingCheck(Set<String> exempt) {
        this.exempt = exempt;
    }

    static void run(TypeChecker checker, Set<String> exempt) {
        LockNestingCheck c = new LockNestingCheck(exempt);
        c.collect(checker);
        c.check();
    }

    private void add(TypeChecker.FuncInfo f) {
        if (f == null || f.funcToken == null || f.mangledName == null || byName.containsKey(f.mangledName) || exempt.contains(f.mangledName)) {
            return;
        }
        Info in = new Info(f);
        byName.put(f.mangledName, in);
        walk(f.funcToken, false, in);
    }

    private void collect(TypeChecker checker) {
        for (List<TypeChecker.FuncInfo> l : checker.getFunctions().values()) {
            l.forEach(this::add);
        }
        for (List<TypeChecker.ImplInfo> l : checker.getImpls().values()) {
            for (TypeChecker.ImplInfo impl : l) {
                impl.methods.values().forEach(this::add);
                impl.staticMethods.values().forEach(this::add);
                impl.genericMethodInstances.values().forEach(this::add);
            }
        }
        for (TypeChecker.LibraryInfo lib : checker.getLibraries().values()) {
            for (List<TypeChecker.FuncInfo> l : lib.ownFunctions.values()) {
                l.forEach(this::add);
            }
        }
    }

    private void walk(Token t, boolean held, Info in) {
        if (t == null) {
            return;
        }
        if (t.isLockSpinLoop) {
            acquire(t, held, in);
            Token ifTok = t.childs != null && !t.childs.isEmpty() && !t.childs.get(0).childs.isEmpty() ? t.childs.get(0).childs.get(0) : null;
            if (ifTok == null) {
                walkChildren(t, held, in);
                return;
            }
            walk(ifTok.sub == null || ifTok.sub.isEmpty() ? null : ifTok.sub.get(0), held, in);   // the acquire attempt
            for (Token c : ifTok.childs) {
                walk(c, true, in);                                                                // OPEN case: the lock is held
            }
            walk(ifTok.right, held, in);                                                          // CLOSED case: not held
            if (t.preLoopInit != null) {
                for (Token c : t.preLoopInit) {
                    walk(c, held, in);
                }
            }
            return;
        }
        if (t.type == TokenType.KEYWORD && t.text.equals("lock") && t.lockAcquireCall != null) {
            acquire(t, held, in);
            walk(t.sub == null || t.sub.isEmpty() ? null : t.sub.get(0), held, in);
            for (Token c : t.childs) {
                walk(c, true, in);
            }
            return;
        }
        if (t.resolvedCallTarget != null) {
            in.callees.add(t.resolvedCallTarget);
            if (held) {
                in.heldCalls.add(t);
            }
        }
        walkChildren(t, held, in);
    }

    private void walkChildren(Token t, boolean held, Info in) {
        walk(t.left, held, in);
        walk(t.right, held, in);
        if (t.childs != null) {
            for (Token c : t.childs) {
                walk(c, held, in);
            }
        }
        if (t.sub != null) {
            for (Token c : t.sub) {
                walk(c, held, in);
            }
        }
    }

    private void acquire(Token t, boolean held, Info in) {
        in.acquires = true;
        if (held) {
            throw new CompilerException("type", t.file, t.line, "a lock is taken while another lock is held -- nested locks are not allowed (the lock records no owner, so the same "
                    + "lock can never be taken again, and different locks need an order the language does not have); release the first one before taking the second");
        }
    }

    /** The calls a function makes, then (shortest first) the chain down to a function that takes a lock; null when none does. */
    private List<String> pathToLock(String from, Set<String> seen) {
        Info in = byName.get(from);
        if (in == null || !seen.add(from)) {
            return null;
        }
        if (in.acquires) {
            return new ArrayList<>(List.of(from));
        }
        for (String c : in.callees) {
            List<String> p = pathToLock(c, seen);
            if (p != null) {
                p.add(0, from);
                return p;
            }
        }
        return null;
    }

    private void check() {
        for (Info in : byName.values()) {
            for (Token call : in.heldCalls) {
                List<String> p = pathToLock(call.resolvedCallTarget, new HashSet<>());
                if (p != null) {
                    throw new CompilerException("type", call.file, call.line, "'" + call.resolvedCallTarget + "' is called while a lock is held, and it takes a lock"
                            + (p.size() > 1 ? " (through " + String.join(" > ", p) + ")" : "") + " -- nested locks are not allowed, directly or through calls; the standard "
                            + "library's own allocation lock is the only exception");
                }
            }
        }
    }
}

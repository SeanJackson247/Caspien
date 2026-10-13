package caspien.typechecker;
import caspien.CompilerException;
import caspien.Token;
import caspien.TokenType;

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
    public static final class Info {
        public final TypeCheckerModel.FuncInfo fn;
        public boolean acquires;                                         // takes a lock directly
        public String waitKind;                                          // first waiting operation in the body ("yield", "sleep", "par", "await"), null if none
        public final Set<String> callees = new LinkedHashSet<>();         // every resolved call target in the body
        public final List<Token> heldCalls = new ArrayList<>();           // calls made while a lock is held
        public Info(TypeCheckerModel.FuncInfo fn) {
            this.fn = fn;
        }
    }

    public final Map<String, Info> byName = new LinkedHashMap<>();
    public final Set<String> exempt;

    public LockNestingCheck(Set<String> exempt) {
        this.exempt = exempt;
    }

    public static void run(TypeChecker checker, Set<String> exempt) {
        LockNestingCheck c = new LockNestingCheck(exempt);
        c.collect(checker);
        c.check();
    }

    public void add(TypeCheckerModel.FuncInfo f) {
        if (f == null || f.funcToken == null || f.mangledName == null || byName.containsKey(f.mangledName) || exempt.contains(f.mangledName)) {
            return;
        }
        Info in = new Info(f);
        byName.put(f.mangledName, in);
        walk(f.funcToken, false, in);
    }

    public void collect(TypeChecker checker) {
        for (List<TypeCheckerModel.FuncInfo> l : checker.declarations.getFunctions().values()) {
            l.forEach(this::add);
        }
        for (List<TypeCheckerModel.ImplInfo> l : checker.declarations.getImpls().values()) {
            for (TypeCheckerModel.ImplInfo impl : l) {
                impl.methods.values().forEach(this::add);
                impl.staticMethods.values().forEach(this::add);
                impl.genericMethodInstances.values().forEach(this::add);
            }
        }
        for (TypeCheckerModel.LibraryInfo lib : checker.declarations.getLibraries().values()) {
            for (List<TypeCheckerModel.FuncInfo> l : lib.ownFunctions.values()) {
                l.forEach(this::add);
            }
        }
    }

    public void walk(Token t, boolean held, Info in) {
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
        if (t.resolvedCallTarget != null) {
            in.callees.add(t.resolvedCallTarget);
            if (held) {
                in.heldCalls.add(t);
            }
        }
        walkChildren(t, held, in);
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

    public void walkChildren(Token t, boolean held, Info in) {
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

    public void acquire(Token t, boolean held, Info in) {
        in.acquires = true;
        if (held) {
            throw new CompilerException("type", t.file, t.line, "a lock is taken while another lock is held -- nested locks are not allowed (the lock records no owner, so the same "
                    + "lock can never be taken again, and different locks need an order the language does not have); release the first one before taking the second");
        }
    }

    /** The calls a function makes, then (shortest first) the chain down to a function that takes a lock; null when none does. */
    public List<String> pathToLock(String from, Set<String> seen) {
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

    /** The calls a function makes down to a function that waits, shortest first; null when none does. */
    public List<String> pathToWait(String from, Set<String> seen) {
        Info in = byName.get(from);
        if (in == null || !seen.add(from)) {
            return null;
        }
        if (in.waitKind != null) {
            return new ArrayList<>(List.of(from));
        }
        for (String c : in.callees) {
            List<String> p = pathToWait(c, seen);
            if (p != null) {
                p.add(0, from);
                return p;
            }
        }
        return null;
    }

    public void check() {
        for (Info in : byName.values()) {
            for (Token call : in.heldCalls) {
                List<String> w = pathToWait(call.resolvedCallTarget, new HashSet<>());
                if (w != null) {
                    throw new CompilerException("type", call.file, call.line, "'" + call.resolvedCallTarget + "' is called while a lock is held, and it waits ('"
                            + byName.get(w.get(w.size() - 1)).waitKind + "'" + (w.size() > 1 ? ", through " + String.join(" > ", w) : "") + ") -- nothing may wait inside a critical section");
                }
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

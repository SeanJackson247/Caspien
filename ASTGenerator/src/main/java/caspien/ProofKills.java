package caspien;

import java.util.*;

/**
 * A `match Some(x){...}` proof says "the object x refers to is alive HERE". It is checked once, at the top of the body, so anything that can destroy an
 * allocation between the check and a later use of x ends the proof. This class records, while TypeChecker walks a function in source order,
 *   - every use of an alive proof (`use`, from `isProvenAlive`),
 *   - every event that may destroy an allocation (`kill`: a direct one; `call`: a call whose callee may destroy),
 * and, after the whole program is checked, `check` decides: a use is legal when some proof covering it saw no destroying event before it (in source
 * order) and none inside a loop the event and the use share (the next iteration runs the use after the event).
 *
 * What may destroy (the kill list, decided by the owner 8 Oct 2026):
 *   - an `unsafe` block or a function declared inside `unsafe{}` (no exception for the standard library; wherever it needs a proof back it writes `assume match`),
 *   - assignment over a slot that owns memory,
 *   - `resize` of a safe dynarray, `par`, `await`,
 *   - moving an owner into `new` / `dyn` (the failure path frees it),
 *   - a call to a function that does any of these, or that takes an owning parameter (it may drop it), or whose target is not known.
 * Scope end of a block nested inside the proof's body is not a kill: it only drops locals declared after the proof was made.
 * Not covered yet: a `ref some` parameter inside the callee that destroys its own target (the caller's side is covered).
 */
final class ProofKills {
    static final class Ev {
        final int seq;
        final int[] loops;
        final Token at;
        final String what;       // description of a direct kill, or null for a call
        final String callee;     // mangled name for a call, else null
        Ev(int seq, int[] loops, Token at, String what, String callee) {
            this.seq = seq;
            this.loops = loops;
            this.at = at;
            this.what = what;
            this.callee = callee;
        }
    }

    static final class Use {
        final int seq;
        final int[] loops;
        final Token at;
        final List<Token.MatchPattern> cands;
        Use(int seq, int[] loops, Token at, List<Token.MatchPattern> cands) {
            this.seq = seq;
            this.loops = loops;
            this.at = at;
            this.cands = cands;
        }
    }

    static final class Facts {
        String directKill;                       // first direct kill description, null if none
        final Set<String> callees = new LinkedHashSet<>();
    }

    private static int seq = 0;
    private static int loopSerial = 0;
    private static final ArrayList<Integer> loopIds = new ArrayList<>();
    private static final IdentityHashMap<Token.MatchPattern, List<Ev>> events = new IdentityHashMap<>();
    private static final List<Use> uses = new ArrayList<>();
    private static final Map<String, Facts> facts = new HashMap<>();
    private static final ArrayDeque<TypeChecker.FuncInfo> funcStack = new ArrayDeque<>();
    /** Implicit proofs of `ref some` locals: a `ref some` is alive when it is made, exactly like a fresh `match Some`. Keyed "function|slot" and "function". */
    private static final Map<String, List<Token.MatchPattern>> implicitBySlot = new HashMap<>();
    private static final Map<String, List<Token.MatchPattern>> implicitByFunc = new HashMap<>();

    private ProofKills() {
    }

    static void reset() {
        seq = 0;
        loopSerial = 0;
        loopIds.clear();
        events.clear();
        uses.clear();
        facts.clear();
        funcStack.clear();
        implicitBySlot.clear();
        implicitByFunc.clear();
    }

    // ---- recording (called by TypeChecker while it walks) ----

    static void enterFunction(TypeChecker.FuncInfo f) {
        funcStack.push(f);
        if (f.mangledName != null) {
            Facts fa = facts.computeIfAbsent(f.mangledName, k -> new Facts());
            if ("unsafe".equals(f.safetyTag) && fa.directKill == null) {
                fa.directKill = "it is declared inside unsafe{}";
            }
        }
    }

    static void exitFunction() {
        funcStack.pop();
    }

    static TypeChecker.FuncInfo currentFunction() {
        return funcStack.peek();
    }

    static List<Integer> saveLoops() {
        List<Integer> saved = new ArrayList<>(loopIds);
        return saved;
    }

    /** A function body is checked from scratch (a lazily checked generic mid-way through another body): its loops are its own. */
    static void restoreLoops(List<Integer> saved) {
        loopIds.clear();
        loopIds.addAll(saved);
    }

    static void clearLoops() {
        loopIds.clear();
    }

    static void enterLoop() {
        loopIds.add(++loopSerial);
    }

    static void exitLoop() {
        loopIds.remove(loopIds.size() - 1);
    }

    static int loopDepth() {
        return loopIds.size();
    }

    private static int[] snapshot() {
        int[] a = new int[loopIds.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = loopIds.get(i);
        }
        return a;
    }

    /** Remember how deep in loops the proofs were made, so only loops inside a proof's body count as "shared" later. */
    static void stamp(List<Token.MatchPattern> patterns) {
        for (Token.MatchPattern p : patterns) {
            if (p.loopDepth < 0) {
                p.loopDepth = loopIds.size();
            }
        }
    }

    private static String funcKey() {
        TypeChecker.FuncInfo f = funcStack.peek();
        return f == null || f.mangledName == null ? "?" : f.mangledName;
    }

    /** A `ref some` local was just assigned: from here until it is assigned again it is "proven alive" by its own creation. */
    static void implicit(String slot) {
        String fk = funcKey();
        List<Token.MatchPattern> old = implicitBySlot.get(fk + "|" + slot);
        if (old != null) {
            for (Token.MatchPattern o : old) {
                o.invalidated = true;
            }
        }
        Token.MatchPattern p = new Token.MatchPattern(slot, "alive", null);
        p.loopDepth = loopIds.size();
        implicitBySlot.computeIfAbsent(fk + "|" + slot, k -> new ArrayList<>()).add(p);
        implicitByFunc.computeIfAbsent(fk, k -> new ArrayList<>()).add(p);
    }

    /** A read of a `ref some` local: it must still be covered by its creation proof. */
    static void refSomeRead(Token at, List<Token.MatchPattern> active) {
        List<Token.MatchPattern> all = implicitBySlot.get(funcKey() + "|" + at.text);
        if (all == null) {
            return;
        }
        List<Token.MatchPattern> cands = new ArrayList<>();
        for (Token.MatchPattern p : all) {
            if (!p.invalidated) {
                cands.add(p);
            }
        }
        for (Token.MatchPattern p : active) {       // a `match Some(r)` or `assume match Some(r)` made after the free proves it again
            if (!p.invalidated && p.kind.equals("alive") && at.text.equals(p.slotKey)) {
                cands.add(p);
            }
        }
        if (!cands.isEmpty()) {
            use(at, cands);
        }
    }

    /** True when something that may destroy was recorded after this alive proof was made (only the post-check pass knows whether it really can). */
    static boolean hasEvents(Token.MatchPattern p) {
        List<Ev> l = events.get(p);
        return l != null && !l.isEmpty();
    }

    static void use(Token at, List<Token.MatchPattern> cands) {
        uses.add(new Use(++seq, snapshot(), at, cands));
    }

    /** A direct destroying event in the function being checked. */
    static void kill(List<Token.MatchPattern> active, Token at, String what) {
        TypeChecker.FuncInfo f = funcStack.peek();
        if (f != null && f.mangledName != null) {
            Facts fa = facts.computeIfAbsent(f.mangledName, k -> new Facts());
            if (fa.directKill == null) {
                fa.directKill = what + " at " + at.file + ":" + at.line;
            }
        }
        record(active, new Ev(++seq, snapshot(), at, what, null));
    }

    static void call(List<Token.MatchPattern> active, Token at, String callee) {
        TypeChecker.FuncInfo f = funcStack.peek();
        if (f != null && f.mangledName != null) {
            facts.computeIfAbsent(f.mangledName, k -> new Facts()).callees.add(callee);
        }
        record(active, new Ev(++seq, snapshot(), at, null, callee));
    }

    private static void record(List<Token.MatchPattern> active, Ev e) {
        for (Token.MatchPattern p : active) {
            if (p.kind.equals("alive") && !p.invalidated) {
                events.computeIfAbsent(p, k -> new ArrayList<>()).add(e);
            }
        }
        List<Token.MatchPattern> imp = implicitByFunc.get(funcKey());
        if (imp != null) {
            for (Token.MatchPattern p : imp) {
                if (!p.invalidated) {
                    events.computeIfAbsent(p, k -> new ArrayList<>()).add(e);
                }
            }
        }
    }

    // ---- the decision ----

    private static final class Graph {
        final Map<String, TypeChecker.FuncInfo> known = new HashMap<>();
        final Set<String> externs;
        final Map<String, Boolean> memo = new HashMap<>();
        Graph(Set<String> externs) {
            this.externs = externs;
        }
    }

    /** null when the callee cannot destroy; else the chain "f > g" ending in the reason. */
    private static String mayDestroy(String callee, Graph g, Set<String> path) {
        if (g.externs.contains(callee)) {
            return null;                                          // an extern call needs `unsafe` at the call site (already a kill there), or is declared safe
        }
        TypeChecker.FuncInfo fi = g.known.get(callee);
        if (fi == null) {
            return callee + " (target not known)";
        }
        if (!path.add(callee)) {
            return null;
        }
        Facts fa = facts.get(callee);
        if (fa != null && fa.directKill != null) {
            path.remove(callee);
            return callee + ": " + fa.directKill;
        }
        for (TypeChecker.TypeInfo pt : fi.paramTypes) {
            if (pt != null && "owns".equals(pt.storage)) {
                path.remove(callee);
                return callee + ": it takes an owning parameter, which it may drop";
            }
        }
        if (fa != null) {
            for (String c : fa.callees) {
                String r = mayDestroy(c, g, path);
                if (r != null) {
                    path.remove(callee);
                    return callee + " > " + r;
                }
            }
        }
        path.remove(callee);
        return null;
    }

    private static boolean sharedLoop(Ev e, Use u, int from) {
        for (int k = Math.max(from, 0); k < e.loops.length && k < u.loops.length; k++) {
            if (e.loops[k] == u.loops[k]) {
                return true;
            }
        }
        return false;
    }

    static void check(TypeChecker checker, Collection<TypeChecker.FuncInfo> allFuncs, Set<String> externNames) {
        Graph g = new Graph(externNames);
        for (TypeChecker.FuncInfo f : allFuncs) {
            if (f != null && f.mangledName != null) {
                g.known.put(f.mangledName, f);
            }
        }
        for (Use u : uses) {
            boolean ok = false;
            String reason = null;
            Ev last = null;
            for (Token.MatchPattern p : u.cands) {
                boolean killed = false;
                for (Ev e : events.getOrDefault(p, Collections.emptyList())) {
                    boolean before = e.seq < u.seq;
                    if (!before && !sharedLoop(e, u, p.loopDepth)) {
                        continue;
                    }
                    String why = e.what != null ? e.what : mayDestroy(e.callee, g, new HashSet<>());
                    if (why != null) {
                        killed = true;
                        reason = e.what != null ? e.what : "the call to " + why;
                        last = e;
                        break;
                    }
                }
                if (!killed) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                String name = u.at.text != null ? u.at.text : "the reference";
                throw new CompilerException("type", u.at.file, u.at.line,
                        "'" + name + "' is used after something that may free the object it refers to: " + reason + " (" + last.at.file + ":" + last.at.line
                                + "). The reference was alive when it was made or matched; that ended there. Match it again after the free, or restructure so nothing frees in between");
            }
        }
    }
}

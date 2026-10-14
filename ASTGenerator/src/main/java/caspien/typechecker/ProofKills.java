package caspien.typechecker;
import caspien.config.CompilerException;
import caspien.token.Token;

import java.util.*;
import caspien.token.MatchPattern;

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
 * `ref some` parameters (incl. `self`) start with an implicit proof like `ref some` locals. A proof knows the struct type it points at; an event knows the
 * types it can destroy (ownership closure), so freeing an unrelated type does not end it. Not covered: refs stored in struct members.
 */
public final class ProofKills {






    public static int suppressReads = 0;
    public static int seq = 0;
    public static int loopSerial = 0;
    public static final ArrayList<Integer> loopIds = new ArrayList<>();
    public static final IdentityHashMap<MatchPattern, List<ProofKillsEv>> events = new IdentityHashMap<>();
    public static final List<ProofKillsUse> uses = new ArrayList<>();
    public static final Map<String, ProofKillsFacts> facts = new HashMap<>();
    public static final ArrayDeque<FuncInfo> funcStack = new ArrayDeque<>();
    /** Implicit proofs of `ref some` locals: a `ref some` is alive when it is made, exactly like a fresh `match Some`. Keyed "function|slot" and "function". */
    public static final Map<String, List<MatchPattern>> implicitBySlot = new HashMap<>();
    public static final Map<String, List<MatchPattern>> implicitByFunc = new HashMap<>();

    public ProofKills() {
    }

    public static void reset() {
        seq = 0;
        suppressReads = 0;
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

    public static void enterFunction(FuncInfo f) {
        funcStack.push(f);
        if (f.mangledName != null) {
            ProofKillsFacts fa = factsFor(f.mangledName);
            if ("unsafe".equals(f.safetyTag) && fa.directKill == null) {
                fa.directKill = "it is declared inside unsafe{}";
                fa.directAll = true;
            }
        }
    }

    public static void exitFunction() {
        funcStack.pop();
    }

    public static FuncInfo currentFunction() {
        return funcStack.peek();
    }

    public static List<Integer> saveLoops() {
        List<Integer> saved = new ArrayList<>(loopIds);
        return saved;
    }

    /** A function body is checked from scratch (a lazily checked generic mid-way through another body): its loops are its own. */
    public static void restoreLoops(List<Integer> saved) {
        loopIds.clear();
        loopIds.addAll(saved);
    }

    public static void clearLoops() {
        loopIds.clear();
    }

    public static void enterLoop() {
        loopIds.add(++loopSerial);
    }

    public static void exitLoop() {
        loopIds.remove(loopIds.size() - 1);
    }

    public static int loopDepth() {
        return loopIds.size();
    }

    public static int[] snapshot() {
        int[] a = new int[loopIds.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = loopIds.get(i);
        }
        return a;
    }

    /** Remember how deep in loops the proofs were made, so only loops inside a proof's body count as "shared" later. */
    public static void stamp(List<MatchPattern> patterns) {
        for (MatchPattern p : patterns) {
            if (p.loopDepth < 0) {
                p.loopDepth = loopIds.size();
            }
        }
    }

    public static String funcKey() {
        FuncInfo f = funcStack.peek();
        return f == null || f.mangledName == null ? "?" : f.mangledName;
    }

    /** A `ref some` local was just assigned: from here until it is assigned again it is "proven alive" by its own creation. */
    public static void implicit(String slot, String pointee) {
        String fk = funcKey();
        List<MatchPattern> old = implicitBySlot.get(fk + "|" + slot);
        if (old != null) {
            for (MatchPattern o : old) {
                o.invalidated = true;
            }
        }
        MatchPattern p = new MatchPattern(slot, "alive", null);
        p.loopDepth = loopIds.size();
        p.pointee = pointee;
        String slotKey = fk + "|" + slot;
        List<MatchPattern> bySlot = implicitBySlot.get(slotKey);
        if (bySlot == null) {
            bySlot = new ArrayList<>();
            implicitBySlot.put(slotKey, bySlot);
        }
        bySlot.add(p);
        List<MatchPattern> byFunc = implicitByFunc.get(fk);
        if (byFunc == null) {
            byFunc = new ArrayList<>();
            implicitByFunc.put(fk, byFunc);
        }
        byFunc.add(p);
    }

    /** A read of a `ref some` local: it must still be covered by its creation proof. */
    public static void refSomeRead(Token at, List<MatchPattern> active) {
        if (suppressReads > 0) {
            return;
        }
        List<MatchPattern> all = implicitBySlot.get(funcKey() + "|" + at.text);
        if (all == null) {
            return;
        }
        List<MatchPattern> cands = new ArrayList<>();
        for (MatchPattern p : all) {
            if (!p.invalidated) {
                cands.add(p);
            }
        }
        for (MatchPattern p : active) {       // a `match Some(r)` or `assume match Some(r)` made after the free proves it again
            if (!p.invalidated && p.kind.equals("alive") && at.text.equals(p.slotKey)) {
                cands.add(p);
            }
        }
        if (!cands.isEmpty()) {
            use(at, cands);
        }
    }

    /** True when something that may destroy was recorded after this alive proof was made (only the post-check pass knows whether it really can). */
    public static boolean hasEvents(MatchPattern p) {
        List<ProofKillsEv> l = events.get(p);
        return l != null && !l.isEmpty();
    }

    public static void use(Token at, List<MatchPattern> cands) {
        use(at, cands, null);
    }

    public static void use(Token at, List<MatchPattern> cands, String name) {
        uses.add(new ProofKillsUse(++seq, snapshot(), at, cands, name));
    }

    /** A direct destroying event in the function being checked. */
    public static void kill(List<MatchPattern> active, Token at, String what) {
        kill(active, at, what, null, null);
    }

    /** `memberRoot`: when the freed object is a member reached through variable v (`v.backing`, `v.n[i]`), v's own target is not what is freed. */
    public static void kill(List<MatchPattern> active, Token at, String what, String memberRoot, Set<String> types) {
        FuncInfo f = funcStack.peek();
        if (f != null && f.mangledName != null) {
            ProofKillsFacts fa = factsFor(f.mangledName);
            if (fa.directKill == null) {
                fa.directKill = what + " at " + at.file + ":" + at.line;
            }
            if (types == null) {
                fa.directAll = true;
            } else {
                fa.directTypes.addAll(types);
            }
        }
        record(active, new ProofKillsEv(++seq, snapshot(), at, what, null, memberRoot, types));
    }

    public static void call(List<MatchPattern> active, Token at, String callee) {
        FuncInfo f = funcStack.peek();
        if (f != null && f.mangledName != null) {
            factsFor(f.mangledName).callees.add(callee);
        }
        record(active, new ProofKillsEv(++seq, snapshot(), at, null, callee, null, null));
    }

    private static ProofKillsFacts factsFor(String name) {
        ProofKillsFacts fa = facts.get(name);
        if (fa == null) {
            fa = new ProofKillsFacts();
            facts.put(name, fa);
        }
        return fa;
    }

    private static List<ProofKillsEv> eventsFor(MatchPattern p) {
        List<ProofKillsEv> list = events.get(p);
        if (list == null) {
            list = new ArrayList<>();
            events.put(p, list);
        }
        return list;
    }

    public static void record(List<MatchPattern> active, ProofKillsEv e) {
        for (MatchPattern p : active) {
            if (p.kind.equals("alive") && !p.invalidated && !(e.skipSlot != null && e.skipSlot.equals(p.slotKey))) {
                eventsFor(p).add(e);
            }
        }
        List<MatchPattern> imp = implicitByFunc.get(funcKey());
        if (imp != null) {
            for (MatchPattern p : imp) {
                if (!p.invalidated && !(e.skipSlot != null && e.skipSlot.equals(p.slotKey))) {
                    eventsFor(p).add(e);
                }
            }
        }
    }

    // ---- the decision ----





    /** null when the callee cannot destroy. */
    public static ProofKillsSumm summarize(String root, ProofKillsGraph g, Set<String> path) {
        // frames: {callee, its ProofKillsSumm, iterator over the callees it still has to visit}
        java.util.Deque<Object[]> frames = new java.util.ArrayDeque<>();
        String enter = root;
        ProofKillsSumm ret = null;
        boolean haveRet = false;
        while (true) {
            if (enter != null) {
                String callee = enter;
                enter = null;
                FuncInfo fi = g.known.get(callee);
                if (g.externs.contains(callee)) {
                    haveRet = true;                               // an extern call needs `unsafe` at the call site (already a kill there), or is declared safe
                } else if (fi == null) {
                    ProofKillsSumm unknown = new ProofKillsSumm();
                    unknown.reason = callee + " (target not known)";
                    unknown.all = true;
                    ret = unknown;
                    haveRet = true;
                } else if (!path.add(callee)) {
                    haveRet = true;
                } else {
                    ProofKillsSumm s = new ProofKillsSumm();
                    ProofKillsFacts fa = facts.get(callee);
                    if (fa != null && fa.directKill != null) {
                        s.reason = callee + ": " + fa.directKill;
                        s.all |= fa.directAll;
                        s.types.addAll(fa.directTypes);
                    }
                    for (TypeInfo pt : fi.paramTypes) {
                        if (pt != null && "owns".equals(pt.storage)) {
                            if (s.reason == null) {
                                s.reason = callee + ": it takes an owning parameter, which it may drop";
                            }
                            Set<String> c = g.closure.destroyedTypes(pt);
                            if (c == null) {
                                s.all = true;
                            } else {
                                s.types.addAll(c);
                            }
                        }
                    }
                    java.util.Iterator<String> callees = fa != null ? fa.callees.iterator() : java.util.Collections.<String>emptyIterator();
                    frames.push(new Object[]{callee, s, callees});
                }
            }
            if (haveRet) {
                haveRet = false;
                if (frames.isEmpty()) {
                    return ret;
                }
                Object[] parent = frames.peek();
                if (ret != null) {
                    ProofKillsSumm s = (ProofKillsSumm) parent[1];
                    if (s.reason == null) {
                        s.reason = parent[0] + " > " + ret.reason;
                    }
                    s.all |= ret.all;
                    s.types.addAll(ret.types);
                }
                ret = null;
            }
            Object[] fr = frames.peek();
            @SuppressWarnings("unchecked")
            java.util.Iterator<String> callees = (java.util.Iterator<String>) fr[2];
            if (callees.hasNext()) {
                enter = callees.next();
                continue;
            }
            frames.pop();
            path.remove((String) fr[0]);
            ProofKillsSumm s = (ProofKillsSumm) fr[1];
            ret = s.reason == null ? null : s;
            haveRet = true;
        }
    }

    public static boolean kills(MatchPattern p, boolean all, Set<String> types) {
        return all || types == null || p.pointee == null || types.contains(p.pointee);
    }

    public static boolean sharedLoop(ProofKillsEv e, ProofKillsUse u, int from) {
        for (int k = Math.max(from, 0); k < e.loops.length && k < u.loops.length; k++) {
            if (e.loops[k] == u.loops[k]) {
                return true;
            }
        }
        return false;
    }

    public static void check(TypeChecker checker, Collection<FuncInfo> allFuncs, Set<String> externNames) {
        ProofKillsGraph g = new ProofKillsGraph(externNames, checker.declarations);
        for (FuncInfo f : allFuncs) {
            if (f != null && f.mangledName != null) {
                g.known.put(f.mangledName, f);
            }
        }
        for (ProofKillsUse u : uses) {
            boolean ok = false;
            String reason = null;
            ProofKillsEv last = null;
            for (MatchPattern p : u.cands) {
                boolean killed = false;
                for (ProofKillsEv e : events.getOrDefault(p, Collections.emptyList())) {
                    boolean before = e.seq < u.seq;
                    if (!before && !sharedLoop(e, u, p.loopDepth)) {
                        continue;
                    }
                    String why;
                    boolean all;
                    Set<String> types;
                    if (e.what != null) {
                        why = e.what;
                        all = e.types == null;
                        types = e.types;
                    } else {
                        ProofKillsSumm sm = summarize(e.callee, g, new HashSet<>());
                        why = sm == null ? null : "the call to " + sm.reason;
                        all = sm != null && sm.all;
                        types = sm == null ? null : sm.types;
                    }
                    if (why != null && kills(p, all, types)) {
                        killed = true;
                        reason = why;
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
                String name = u.name != null ? u.name : u.at.text != null ? u.at.text : "the reference";
                throw new CompilerException("type", u.at.file, u.at.line,
                        "'" + name + "' is used after something that may free the object it refers to: " + reason + " (" + last.at.file + ":" + last.at.line
                                + "). The reference was alive when it was made or matched; that ended there. Match it again after the free, or restructure so nothing frees in between");
            }
        }
    }
}

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

    public ProofKills() { }

    public static void reset() { }

    // ---- recording (called by TypeChecker while it walks) ----

    public static void enterFunction(FuncInfo f) { }

    public static void exitFunction() { }

    public static FuncInfo currentFunction() { return null; }

    public static List<Integer> saveLoops() { return null; }

    /** A function body is checked from scratch (a lazily checked generic mid-way through another body): its loops are its own. */
    public static void restoreLoops(List<Integer> saved) { }

    public static void clearLoops() { }

    public static void enterLoop() { }

    public static void exitLoop() { }

    public static int loopDepth() { return 0; }

    public static int[] snapshot() { return null; }

    /** Remember how deep in loops the proofs were made, so only loops inside a proof's body count as "shared" later. */
    public static void stamp(List<MatchPattern> patterns) { }

    public static String funcKey() { return null; }

    /** A `ref some` local was just assigned: from here until it is assigned again it is "proven alive" by its own creation. */
    public static void implicit(String slot, String pointee) { }

    /** A read of a `ref some` local: it must still be covered by its creation proof. */
    public static void refSomeRead(Token at, List<MatchPattern> active) { }

    /** True when something that may destroy was recorded after this alive proof was made (only the post-check pass knows whether it really can). */
    public static boolean hasEvents(MatchPattern p) { return false; }

    public static void use(Token at, List<MatchPattern> cands) { }

    public static void use(Token at, List<MatchPattern> cands, String name) { }

    /** A direct destroying event in the function being checked. */
    public static void kill(List<MatchPattern> active, Token at, String what) { }

    /** `memberRoot`: when the freed object is a member reached through variable v (`v.backing`, `v.n[i]`), v's own target is not what is freed. */
    public static void kill(List<MatchPattern> active, Token at, String what, String memberRoot, Set<String> types) { }

    public static void call(List<MatchPattern> active, Token at, String callee) { }

    private static ProofKillsFacts factsFor(String name) { return null; }

    private static List<ProofKillsEv> eventsFor(MatchPattern p) { return null; }

    public static void record(List<MatchPattern> active, ProofKillsEv e) {
        for (MatchPattern p : active) { }
        List<MatchPattern> imp = implicitByFunc.get(funcKey());
        if (imp != null) { }
    }

    // ---- the decision ----





    /** null when the callee cannot destroy. */
    public static ProofKillsSumm summarize(String root, ProofKillsGraph g, Set<String> path) { return null; }

    public static boolean kills(MatchPattern p, boolean all, Set<String> types) { return false; }

    public static boolean sharedLoop(ProofKillsEv e, ProofKillsUse u, int from) { return false; }

    public static void check(TypeChecker checker, Collection<FuncInfo> allFuncs, Set<String> externNames) { }
}

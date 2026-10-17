package caspien.token;

import java.util.List;

/**
 * "The compiler just needs to store these assertions about its type
 * system" -- confirmed directly. One entry per leaf condition in a
 * "match" statement's (possibly "and"-chained) condition, set by
 * TypeChecker.checkMatch on the "match" KEYWORD token itself. Not
 * yet consumed anywhere (no narrowing happens inside the match block
 * this round, confirmed directly: "later we will address how these
 * change the type checking within the match block itself") -- purely
 * recorded for a future pass to read.
 */
public final class MatchPattern {
    /** The left operand's slot (a bare variable name or simple "a.b" path) -- null if the operand isn't a trackable slot at all (e.g. a call result). */
    public final String slotKey;
    /** "instanceof", "implements", "in", or "alive" (the bare-expression form). */
    public final String kind;
    /** The right-hand type/interface name for "instanceof"/"implements" -- null for "in"/"alive". */
    public final String targetName;
    /** For "in" only: the right operand's own slot (a bare variable name or simple "a.b" path) -- null if untrackable, or for any other kind. Lets a later bounds-check confirm a proof is being reused against the exact same target it was proven against, not a different one that merely has the same index variable. */
    public final String rightSlotKey;
    /**
     * Set once a real write anywhere within this proof's own
     * guarded region reassigns the exact slot(s) this proof
     * depends on (`slotKey` or `rightSlotKey`) -- see
     * `TypeChecker.invalidateMatchPatternsForWrite`. Deliberately
     * mutable, unlike every other field here: a `MatchPattern`
     * instance is created exactly once, when its own `match`/`for`/
     * `lock` condition is checked, and from then on the *same
     * object* (never a copy) is shared by reference down through
     * every nested scope's own copy-and-extend `activeMatchPatterns`
     * list for as long as that proof's own guarded region lexically
     * continues -- so flipping this one field here is visible to
     * every later proof-check anywhere in that same region, however
     * deeply nested through an 'if'/loop/nested 'match', while a
     * completely different, later `match` on the same variable
     * builds its own fresh `MatchPattern` object (starting
     * `invalidated = false` again) that this can never affect.
     * Found and fixed directly: "match b != 0{ b = mut 0; a / b }"
     * and "match i into arr{ arr = resize(arr, 1, 0); arr[i] = ...
     * }" both used to compile and then crash/corrupt memory at
     * runtime -- the proof was checked once, against the slot's
     * value *at the moment the match condition ran*, and never
     * invalidated by a *later* write to that same slot within its
     * own guarded body.
     */
    public boolean invalidated = false;
    /** Loop nesting depth at which this proof was made (set by ProofKills.stamp); -1 until stamped. */
    public int loopDepth = -1;
    /** Struct type name of the object a `ref some` proof points at (null = unknown: any destroying event ends it). */
    public String pointee = null;
    /**
     * For "enum_field_variants" only (a real "match receiver.field
     * {...}" case block's own proof, `targetName` holding "field")
     * -- the exact set of variants this specific case's own body is
     * proven to cover (a case's own "|"-chain, or, for a `default`
     * case, whatever no earlier case already claimed). Both
     * `TypeChecker.requireStructLockProof` (the original, struct-
     * level "@lock(match self.x : X)" feature) and the newer,
     * method-level enum-style "@lock(...)" both read this same
     * proof, checking their own required set is a superset of it --
     * a genuinely general mechanism, granted for *any* "." match
     * condition, not only one a struct happens to have its own
     * lock qualifier for.
     */
    public final List<String> provenVariants;
    /** "alive" only: the proof is on a nullable `ref` slot (an id), so a use of the subject reads the proof's address slot / resolves the id. */
    public boolean onId;
    public MatchPattern(String slotKey, String kind, String targetName) {
        this(slotKey, kind, targetName, (String) null);
    }
    public MatchPattern(String slotKey, String kind, String targetName, String rightSlotKey) {
        this.slotKey = slotKey;
        this.kind = kind;
        this.targetName = targetName;
        this.rightSlotKey = rightSlotKey;
        this.provenVariants = null;
    }
    public MatchPattern(String slotKey, String kind, String targetName, List<String> provenVariants) {
        this.slotKey = slotKey;
        this.kind = kind;
        this.targetName = targetName;
        this.rightSlotKey = null;
        this.provenVariants = provenVariants;
    }
}

package caspien.token;

import java.util.List;

/**
 * A parsed "@name(args)" or bare "@name" line -- confirmed directly,
 * parsing is deliberately simple: if a line starts with '@' it's a
 * decorator, and it's a decorator to the end of that line. Attached
 * by Parser's stripDecoratorsDeep to the raw first token of whatever
 * line follows a run of decorator lines, then copied onto the final
 * "gathered" token by whichever gather* method builds it (so it
 * survives being wrapped into a new Token instance, e.g.
 * gatherFunc's own KEYWORD("func") node).
 */
public final class Decorator {
    public final String name;
    public final List<String> args; // raw text -- a STRING literal's own text (quotes stripped) or a bare identifier's text
    public final List<Boolean> argIsString; // parallel to `args`: true if that argument was written as a quoted string, false if a bare identifier -- needed to re-emit it correctly (can't be guessed back from the text alone, e.g. an identifier argument could itself look like "C")
    public final int line;
    public final String file;
    /**
     * "@lock(match self.x : X)... on a struct rather than a
     * method," confirmed directly -- this shape is a genuinely
     * different grammar from every other decorator argument (a
     * bare identifier or string), so it's never folded into
     * `args`/`argIsString` above at all; both fields here stay null
     * for every decorator except this one. `lockFieldName` is "x"
     * (the discriminant field); `lockVariants` is "X" as written,
     * one entry per '|'-chained variant (e.g. ["TRUE","FALSE"] for
     * "TRUE|FALSE").
     */
    public String lockFieldName;
    public List<String> lockVariants;
    /**
     * "@lock(match x in self.x2)... support the and operator,
     * allowing... match i in self.backing and i2 in self.backing,"
     * confirmed directly -- a second, genuinely different '@lock'
     * qualifier shape, method-only (never on a struct), and never
     * mixed with the enum-style fields just above within one
     * decorator ("it doesnt mix and match with the enum style,"
     * confirmed directly). `lockInParams`/`lockInFields` are
     * parallel lists, one entry per 'and'-chained clause -- the
     * i-th param goes with the i-th field ("i in self.backing" is
     * `lockInParams[0]="i"`, `lockInFields[0]="backing"`).
     */
    public List<String> lockInParams;
    public List<String> lockInFields;
    /**
     * Parallel to `lockInParams`/`lockInFields`, one entry per
     * 'and'-chained clause -- the literal operator text written at
     * that clause ("in" or "within"). "I want the within operator
     * to be able to be used... in a @lock(match) decorator,"
     * confirmed directly: `within` is accepted at exactly the same
     * grammar position `in` is (Parser.parseLockInQualifier), but
     * which one was written still matters for validation
     * (TypeChecker.validateLockInClause checks the clause against
     * `checkIn`'s or `checkWithin`'s own type rules, whichever was
     * actually written) and for interface conformance
     * (`describeLockMismatch` requires the same operator at the
     * same position, not just the same param/field). The proof it
     * grants once satisfied is identical either way -- "the same
     * type assurance to the type system," confirmed directly --
     * `assumedLockPatterns`/`requireMethodLockProofs` both still key
     * off the single, shared "in" `MatchPattern` kind regardless of
     * which operator this list says was actually written.
     */
    public List<String> lockInOperators;
    /**
     * Parallel to `lockInParams`/`lockInFields`/`lockInOperators`,
     * one entry per 'and'-chained clause -- true when that clause's
     * own parameter was written wrapped in "Some(...)"
     * ("@lock(match Some(i) in self.x)"), the same index-bounds-plus
     * -element-alive sugar the bare-match/for-match forms support,
     * extended here to the method-lock qualifier. Interface
     * conformance requires an exact match on this flag at each
     * clause position, the same "exact-match discipline every other
     * part of a lock clause already gets" precedent the operator
     * itself (`lockInOperators`) already established -- a plain
     * `in`-style implementation can never satisfy a `Some(...)`-
     * wrapped interface clause, or vice versa, even though both
     * grant a superset/subset-related proof.
     */
    public List<Boolean> lockInSomeWrapped;
    /**
     * Lifetime contract (docs/LifetimeContracts.md): "@lock match(lifetime(a) >= lifetime(b) > lifetime(c))" is stored as a decorator named
     * "lifetime" with `lifetimeTerms` = [a, b, c] and `lifetimeOps` = [">=", ">"] (operator i sits between term i and term i+1); both stay null
     * for every other decorator.
     */
    public List<String> lifetimeTerms;
    public List<String> lifetimeOps;
    public Decorator(String name, List<String> args, List<Boolean> argIsString, int line, String file) {
        this.name = name;
        this.args = args;
        this.argIsString = argIsString;
        this.line = line;
        this.file = file;
    }
}

package caspien;

import java.util.ArrayList;
import java.util.List;

/**
 * The one and only token class used throughout the whole compiler
 * pipeline, from raw lexing through to RPN tree construction and type
 * checking. Later stages populate fields (childs, sub, left, right,
 * resolvedType) that the lexer stage leaves null/empty.
 */
public class Token {

    public TokenType type;

    /** Raw/normalized text of the token, e.g. "42", "+", "elseif", "myVar". */
    public String text;

    /** 1-indexed source line number this token started on. */
    public int line;

    /** File this token originated from (path as given on the command line or via import). */
    public String file;

    /**
     * For string/char tokens: the fully escape-processed value.
     * For other token types this is left null and `text` is used directly.
     */
    public String literalValue;

    /**
     * Which quote character opened this literal ('\'', '"', or '`').
     * Set on QUOTED_LITERAL tokens by the raw scan; used by the later
     * classification pass to decide STRING vs CHAR, then no longer
     * meaningful once that pass has run.
     */
    public char quoteDelimiter;

    /** Comments pinned to this token (nearest non-whitespace token rule). */
    public List<Token> pinnedComments = new ArrayList<>();

    /** Children of a delineator token ( [ { etc, populated during the nesting stage. */
    public List<Token> childs = new ArrayList<>();

    /** Statements/lines scooped up into a keyword-block token (if/loop/func/etc), populated later. */
    public List<Token> sub = new ArrayList<>();

    /** Populated when this token becomes an operator node in the RPN/expression tree. */
    public Token left;
    public Token right;

    /**
     * True if this operator token is being used in its unary (prefix,
     * e.g. '-' negation or '!') form rather than its binary form. Set by
     * RpnConverter when an operator is placed into RPN output, since the
     * same token text ('-') can be either depending on context, and the
     * later tree-building stage needs to know how many operands to pop
     * for this specific token. Meaningless for non-operator tokens.
     */
    public boolean unary;

    /**
     * Set by TypeChecker on a "CALL" operator node once overload/method
     * resolution picks a specific concrete function -- the mangled name
     * BytecodeEmitter should actually target. Needed because a method
     * call's CALL node has a '.' node as its left (receiver.methodName),
     * not a plain function-name VARREF the way an ordinary call does,
     * so there's no single token text BytecodeEmitter could read the
     * target off of uniformly without this. Set for *every* CALL node,
     * including ordinary ones (whose mangled name is just their plain
     * name), so BytecodeEmitter has one single, uniform place to read
     * the call target from either way.
     */
    public String resolvedCallTarget;

    /**
     * Set on every CALL-shaped node (ordinary/recursive call, extern
     * call, and the "call()" builtin/INVOKE alike) to the calling
     * convention that call site actually resolved to -- "@call_convention"
     * on the target func/extern's own declaration, or, for an INVOKE
     * (the callee isn't statically known at all -- only its
     * function-pointer *type* is), whatever that type's own
     * "@call_convention" resolved to; compiler.config's own declared
     * default when neither is present. Read directly by
     * BytecodeEmitter.emitCallSequence -- never re-derived or re-looked-
     * up there, same "narrow, purpose-built field set once during
     * checking" discipline resolvedCallTarget/resolvedType/etc. already
     * follow.
     */
    public String resolvedCallConvention;

    /** Populated during type checking. */
    public String resolvedType;

    /**
     * Declaration-site generic type-parameter names, e.g. the ["T"] in
     * "struct List<T>{...}" or the ["K","V"] in "impl<K,V> Pair<K,V>{...}".
     * Set by Parser on the struct/func/interface/impl KEYWORD token itself
     * (same token that carries .sub). Null for a non-generic declaration.
     * Consumed and cleared entirely by GenericsExpander; TypeChecker never
     * sees a non-null typeParams -- with ONE deliberate exception: "impl
     * with its own independent type params." When an impl declares a type
     * parameter that isn't consumed matching its target struct's own
     * generics (e.g. the "U" in "impl<T,U> Container<T>{...}"),
     * GenericsExpander tags each individual METHOD token that actually
     * references that residual name with its own typeParams (just the
     * referenced subset) instead of clearing it -- marking "this specific
     * method is itself a residual-generic-method template," resolved on
     * demand by TypeChecker at each explicit ":<U>" call site
     * (TypeChecker.ImplInfo.genericMethodTemplates/instantiateGenericMethod).
     * The impl declaration's own typeParams is still always cleared to
     * null, same as ever -- only a qualifying method token carries this.
     */
    public List<String> typeParams;

    /**
     * Parallel to `typeParams` (same size, same index correspondence): the
     * declared bound interface name for each type parameter, e.g. the
     * [null, "Comparable"] for "func f<T,U: Comparable>(...)" -- null at an
     * index means that particular type parameter has no declared bound.
     * Null entirely (not just all-null-entries) when `typeParams` itself
     * carries no bounds at all -- the common, unbounded-generics case.
     * Set by Parser alongside `typeParams`. Consumed by GenericsExpander at
     * instantiation time (see `Token.pendingGenericBoundChecks`); never
     * read by TypeChecker directly.
     */
    public List<String> typeParamBounds;

    /**
     * Set by GenericsExpander on a freshly-monomorphized declaration
     * (struct/func/interface/library) whenever the template it was
     * generated from declared one or more bound type parameters
     * (`typeParamBounds`) -- one entry per bound parameter that was
     * actually declared, recording the concrete type argument substituted
     * in for it and the interface name it must satisfy. Consumed once, by
     * TypeChecker.validateGenericBounds, right after every struct/impl is
     * collected (so `structImplementsInterface` is reliable) -- this is
     * the enforcement half of "generic bounds/constraints," checked only
     * at each concrete instantiation site, never as a structural
     * requirement on the template body itself.
     */
    public List<GenericBoundCheck> pendingGenericBoundChecks;

    /** One entry of `pendingGenericBoundChecks` -- see its own doc comment. */
    public static final class GenericBoundCheck {
        public final String typeParamName;
        public final String concreteTypeName;
        public final String interfaceName;
        public GenericBoundCheck(String typeParamName, String concreteTypeName, String interfaceName) {
            this.typeParamName = typeParamName;
            this.concreteTypeName = concreteTypeName;
            this.interfaceName = interfaceName;
        }
    }

    /**
     * Set by GenericsExpander on a monomorphized copy, right before it
     * clears `typeParams` back to null (since the copy is now concrete)
     * -- lets a later pass (TypeChecker's `@guard` validation) tell "this
     * was never generic at all" apart from "this was generic, and has
     * now been correctly monomorphized," which look identical
     * (`typeParams == null`) by the time TypeChecker ever sees either
     * one.
     */
    public boolean wasGenericTemplate;

    /**
     * Usage-site generic type arguments, e.g. the raw token groups for
     * "Point" in "List:<Point>{...}" (expression context, base token was
     * lexed with the merged ":<" operator) or for "T" in a bare "List<T>"
     * type annotation (struct member / func param / return type / impl
     * header context, no colon). Each element is the raw token sequence for
     * one comma-separated type argument. Null for an ordinary non-generic
     * reference. Consumed and cleared entirely by GenericsExpander; by the
     * time TypeChecker runs, no token anywhere has a non-null genericArgs.
     */
    public List<List<Token>> genericArgs;

    /**
     * "extends X" lines' target names, collected on an interface or
     * library KEYWORD token (struct extends no longer exists, and is a
     * parse error); an interface's extends list has no cap. Null/empty
     * for a declaration with no extends clause at all.
     */
    public List<String> extendsNames;

    /**
     * "implements A, B" header clause on a struct, giving the
     * interface names it declares conformance to -- purely a compile-time
     * contract check (a matching "impl X for Y{...}" must exist somewhere
     * in the compilation unit), never populated with method bodies here,
     * since struct bodies can't contain funcs. Null/empty if no
     * "implements" clause was written.
     */
    public List<String> implementsNames;

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
    public static final class Decorator {
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
        public Decorator(String name, List<String> args, List<Boolean> argIsString, int line, String file) {
            this.name = name;
            this.args = args;
            this.argIsString = argIsString;
            this.line = line;
            this.file = file;
        }
    }

    /** Null/empty if this token has no decorators. See the Decorator class doc for how these get here. */
    public List<Decorator> decorators;

    /** On an 'unsafe' block statement only: the tags written after the keyword (`unsafe deref extern{`), in source order; empty/null otherwise. */
    public List<String> unsafeTags;

    /** True on an 'unsafe' block the compiler wrote itself (e.g. an async trampoline): exempt from the tag rules. */
    public boolean synthesizedUnsafe;

    /**
     * Set on a "let" KEYWORD token by Parser when the source wrote
     * "let static name = ...". Confirmed directly: only legal as one of
     * the leading statements of a function's own top-level body (never
     * inside a nested block), the initializer must be a compile-time
     * constant (no variables, no function calls -- "no runtime logic in
     * a compile time event," same restriction already established for
     * 'dup'), and the resolved type must be mutable.
     */
    public boolean isStatic;

    /**
     * "const can go next to let for variables, before any <> type
     * setting," confirmed directly -- "let const name = ..."/"let
     * static const name = ...". Set only on a "let" token itself
     * (never a type annotation -- see TypeInfo.isConst for that
     * separate, parameter/struct-member case). "A variable marked
     * const can never be assigned outside of its declaration,"
     * confirmed directly -- enforced by TypeChecker wherever an
     * ordinary reassignment target is resolved back to its own
     * declaring 'let', the exact same "not the compiler's own internal
     * move-out null-store" carve-out already confirmed directly for
     * this: "no moving it is fine... its the pointer itself which has
     * this constancy."
     */
    public boolean isConst;

    /**
     * True only for a gathered "func" token declared with the "static"
     * modifier directly inside an "impl"/"interface" block -- a
     * completely separate concept from `isStatic` just above (that one
     * is for "let static" variables). "self can't be used in these
     * funcs or their signatures -- at all," confirmed directly, and
     * they're dispatched purely at compile time by a bare type name on
     * the left of "." ("TypeName.method(...)"), never an instance --
     * the strict opposite of an ordinary method, which is only ever
     * reachable through one.
     */
    public boolean isStaticMethod;

    /**
     * True only for a gathered "match" token in the "match b{
     * LABEL:{...} ... }" case-block form -- "we need to support match
     * on an enum," confirmed directly, later extended to also cover
     * matching on an `f32`'s own finite/infinite/nan state ("I now
     * wish to expand the match single_varref_token{} statement...
     * consider... let f = 0.25... match f{ finite:{...} ... }",
     * confirmed directly) -- a genuinely different statement shape
     * from the ordinary condition-based "match EXPR{...}" (its own
     * body is a sequence of case blocks, not ordinary statements),
     * detected purely from the shape of its own body at parse time (a
     * bare `VARREF ':' '{'` first line), since whether the condition
     * is enum-typed or f32-typed (which determines which this actually
     * is) isn't known until type-checking. When true, `sub` holds only
     * the match's own condition expression (the switched-on value),
     * and `childs` holds one Token per case -- each case's own `.sub`
     * holds one Token per label covered by that case (more than one
     * only ever for the `|`-chained form, e.g. "infinite|nan:{...}",
     * confirmed directly this chaining is a real, deliberately shared
     * mechanism, not float-specific despite only ever being described
     * for `f32`), and *that* Token's own `.childs` holds the case's
     * own gathered statement body. An enum case's own single label is
     * still just a one-element `.sub` -- there's no separate,
     * enum-only representation left over from before this was
     * generalized.
     */
    public boolean isCaseMatch;

    /**
     * "for match i in x{...}" -- set only on a gathered "for" KEYWORD
     * token itself, when its condition's own raw tokens started with a
     * "match" keyword immediately after "for" (Parser strips that
     * "match" token out of `.sub` before RPN conversion ever sees it,
     * leaving `.sub` holding exactly the same "i in x" shape an
     * ordinary "for i in x{...}" already has). "for match i in
     * x{...} is exactly the same as: for i in x{...} except within the
     * for loop, it gets the same type assertion it would from: match i
     * in x{...}," confirmed directly -- bytecode-identical to an
     * ordinary "for", but TypeChecker.checkForLoop additionally injects
     * the "i in x" MatchPattern proof into the loop body's own scope
     * only (never the enclosing function), enabling a
     * "@lock(match i in x)"-gated call from inside the loop body.
     */
    public boolean isForMatch;

    /**
     * Set only on a "CALL" OPERATOR token (never any other node shape),
     * by TypeChecker.checkTry, immediately before it recursively
     * type-checks that exact call node -- true only for the one call
     * directly wrapped by a "try ... catch { ... }" ("try foo() catch
     * { ... }": this flag is set on the CALL node for "foo()", not on
     * the "try" token itself, which has its own `sub`/`childs`
     * instead). Read back by every one of TypeChecker's call-
     * resolution sites (checkCall, checkLibraryCall, checkStaticMethodCall,
     * checkImplMethodCall, and the interface-dispatch call site) once
     * the callee is known, to enforce the "@throws" contract both
     * directions: a call to an "@throws" function with this flag unset
     * is a hard error (missing "try"/"catch"), and a call with this
     * flag set whose callee is *not* "@throws" is equally an error
     * (pointless "try" around something that can never throw). See
     * TypeChecker.requireThrowsWrapping.
     */
    public boolean insideTry;

    /** Set by TypeChecker.checkNew on the constructor CALL node directly under a `try`-covered `new X(...)`: that one `try` covers the constructor's own throw as well as the allocation (there is no syntax to wrap the inner call separately), so a `@throws` constructor needs no `try` of its own there. See TypeChecker.requireThrowsWrapping. */
    public boolean coveredByThrowingNew;

    /**
     * Set only on the gathered "try" KEYWORD token itself (never the
     * wrapped CALL node), by Parser.gatherTryCatchSpan: the catch
     * parameter's own name, from the now-mandatory "catch(e){...}"
     * shape -- "Its always one argument untyped - doesnt have to be
     * called e, but anything other one plain argument is an error,"
     * confirmed directly. Always exactly one identifier; never null
     * once parsing succeeds (a bare "catch{...}" with no "(...)" at
     * all, or anything other than one plain identifier inside the
     * parens, is a parse error -- see gatherTryCatchSpan's own doc
     * comment). Declared into the catch block's own child scope by
     * TypeChecker.checkTry, alongside checking that block's statements,
     * so a 'throw' inside "catch(e){...}" can read `e` back exactly
     * like any other local variable (see checkThrow's own "throw e"
     * handling). UPDATE (real message propagation): `e` now carries a
     * real runtime value -- BytecodeEmitter.emitHoistedCatchBlocks gives
     * this exact name its own ordinary `ALLOC` (hoisted like any other
     * local) and, the moment this catch block starts running, snapshots
     * the frame's own reserved `gt_error_message` slot into it once
     * (see that slot's own doc comment on `emitGtRoutineAlloc`) --
     * deliberately a one-time copy into an independent local, not a
     * live alias to the shared slot, so a nested `try`/`catch` inside
     * this same catch body (legal -- "the catch blocks just have normal
     * code in them") can never retroactively corrupt an outer catch's
     * own already-bound `e` by overwriting the one shared slot.
     */
    public String catchParamName;

    /**
     * "try { ... }" -- a plain lexical-scope block, distinct from (but
     * sharing the same "try" KEYWORD text as) the throwing "try EXPR
     * catch(e){...}" construct `catchParamName`/`sub` above belong to.
     * Set only by `Parser`'s own "try" pre-scan when the token
     * immediately following "try" is a "{" block (no wrapped
     * expression, no "catch" at all) -- every downstream stage
     * (RpnConverter/TreeBuilder/TypeChecker/BytecodeEmitter) checks
     * this flag first to tell the two "try"-keyword shapes apart,
     * rather than inferring it from `sub`/`catchParamName` being
     * null/empty. "It's a syntax error if there isn't at least one
     * regular try statement in the try block. In terms of the AST,
     * it's a lexical scope as far as variable resolution goes, but it
     * compiles to nothing in the hob and lob," confirmed directly --
     * `TypeChecker.checkTryBlock` enforces the first half,
     * `BytecodeEmitter`'s own "try" case (guarded on this flag) emits
     * nothing for the wrapper itself, mirroring 'unsafe'/'safe' exactly.
     */
    public boolean isTryBlock;

    /**
     * "assume match expression;" (false) or "assume match
     * expression{ ... }" (true), confirmed directly -- set only on an
     * "assume" KEYWORD token itself. The no-block form has no trailing
     * block at all (`childs` stays empty) -- this flag is what
     * TypeChecker's own handling reads to tell the two forms apart,
     * since an empty `childs` alone can't (a block that happens to be
     * empty would look identical).
     */
    public boolean hasBlock;

    /**
     * "@lock(match self.x : X)... X may also be a variant chain,"
     * confirmed directly -- non-null only on an "assume" token whose
     * own condition was written in this enum-style shape ("m.state :
     * OK|FAIL", not "self.x", since this is a real statement, not a
     * decorator argument). Set directly by the parser, at the raw-
     * token level, before ':' ever reaches the ordinary expression
     * pipeline -- ':' is already fully committed to self-bolt syntax
     * there ("x:method()"), so this shape can never be represented as
     * an ordinary expression tree node the way every other match
     * condition is; `sub` holds only the left-hand "x.field" access
     * itself (built completely normally, through the same RPN/tree
     * pipeline as any other expression), and this field holds the
     * right-hand variant names directly, already parsed, needing no
     * further building at all.
     */
    public List<String> assumeFieldVariants;

    /**
     * "I want to support using integer literals (positive only) to
     * dictate what those numbers are," confirmed directly -- set only
     * on an enum variant's own VARREF token (Parser.gatherEnum), null
     * if that variant was written bare, with no explicit ":"/"="
     * value. TypeChecker.collectEnum reads this directly off each of
     * `enum`'s own `childs` to build EnumInfo.explicitValues.
     */
    public Long explicitEnumValue;

    /**
     * "we will store unique ranges (pairs of u64s) as variants,"
     * confirmed directly -- set only on a *range-valued* enum
     * variant's own VARREF token (`Parser.gatherEnum`), mutually
     * exclusive with `explicitEnumValue` (an enum is either plain,
     * u64-valued, or range-valued -- never mixed). Both null unless
     * this variant carries an explicit range (either written directly,
     * `NAME: A..B`, or computed by the nested-hierarchy desugaring --
     * see `Parser.parseNestedEnumVariant`). Null for a bare, no-value
     * variant, exactly like `explicitEnumValue`'s own null case.
     * `TypeChecker.collectEnum` reads both directly off each of
     * `enum`'s own `childs` to build `EnumInfo.explicitRangeStarts`/
     * `explicitRangeEnds`.
     */
    public Long explicitRangeStart;
    public Long explicitRangeEnd;

    /**
     * "compiler generated... ASSIGN a null... immediately after that
     * move," confirmed directly -- set by TypeChecker.markMovedIfOwned
     * directly on the moved-*from* expression node itself (the same
     * node `slotKeyOf` already resolves a real, nameable slot from --
     * this stays null for a move whose own source has no such slot at
     * all, e.g. a fresh `new`/call-result consumed directly, since
     * there's nothing to null out there in the first place).
     * BytecodeEmitter reads this at each of the handful of places a
     * move can happen (assignment, a call argument, a struct-literal
     * field) to emit the null-out at the correct point -- *before* the
     * value is actually handed off for a call argument specifically
     * (right after the PUSH/POP that captures it, before the CALL that
     * could throw), never after; see CLAUDE.md's own note on this for
     * why "after" is unsound. Every consumer of this flag needs a
     * genuinely *nameable* source, not just a `TypeInfo` -- exactly
     * what `slotKeyOf` already exists to answer.
     */
    public boolean isOwnershipMoveSource;

    /**
     * Set on an "INSTANTIATE" node by `TypeChecker.checkInstantiate`
     * when this struct literal is the new, deliberate OTC ("One True
     * Construction") violation this project's locked-struct feature
     * allows: the struct is `@lock(match self.x : X)`-tagged, and the
     * literal provides *only* the lock/discriminant field's own value
     * -- a direct, literal `EnumName.Variant` reference already proven
     * (at type-check time) to NOT satisfy the lock -- with every other
     * member deliberately omitted, since the struct's own existing
     * lock-proof mechanism already makes them permanently unreadable.
     * Read by `BytecodeEmitter.emitInstantiate`, which for this one
     * case pushes only the lock field's own value (never iterating the
     * struct's full declared member list the ordinary, ordinary-OTC
     * path does) and emits a trailing "STACK_LOCK &lt;structName&gt;"
     * line instead of the ordinary per-member push sequence.
     */
    public boolean isLockViolationConstruct;

    /**
     * Set on a "CALL" node by `TypeChecker.checkCall` when this call
     * targets a function whose return type is a plain struct value (no
     * storage modifier) -- Return Value Optimization: "returning structs
     * on the stack by manipulating a hidden struct on the stack in the
     * parent frame," confirmed directly. Only ever set once `checkCall`
     * has also confirmed this call sits in one of the two positions
     * that's legal for it (a 'let' binding's own right-hand side, or a
     * plain assignment's right-hand side with a bare-variable target --
     * see `TypeChecker.insideStructRvoDestinationPosition`'s own doc
     * comment for exactly why those two and nothing else, for now).
     * Read by `BytecodeEmitter.emitAssign`, which for this one case
     * emits a completely different sequence than an ordinary call
     * result: no `PUSH_RET`/`ASSIGN` copy at all -- instead, the callee
     * is handed the destination's own address directly, as an implicit
     * leading argument, and writes the struct's fields into it directly
     * (see `BytecodeEmitter.emitStructRvoAssign`).
     */
    public boolean isStructRvoCall;

    /**
     * Set by `Parser.gatherConstructorImpl` on the synthesized "func"-
     * shaped token it produces for "impl constructor for ConcreteName
     * (params) self { BODY }" -- everywhere else, this token is handled
     * as an entirely ordinary top-level function declaration (same
     * "func" text, same `.sub`/`.childs` shape `gatherFunc` always
     * produces), so this flag exists purely so `TypeChecker.collectFunc`
     * can register it under the shared, per-struct, `$`-prefixed
     * overload-list key (`constructorConcreteName`'s own struct name)
     * instead of its own literal name, and so the OTC-instantiation gate
     * (`checkInstantiate`) can recognize "this scope is inside one of
     * this struct's own constructor bodies."
     */
    /** True on every method/constructor token cloned out of a generic `impl<T>` template by GenericsExpander.generateImplInstantiation. TypeChecker treats a by-value-struct parameter on such a token as "this method is unavailable for this T" (error only if called) instead of a hard error. */
    public boolean fromGenericInstance;

    public boolean isConstructorDecl;

    /** Only meaningful when `isConstructorDecl` is true -- the concrete struct name this constructor was declared for ("MyClass" in "impl constructor for MyClass(...) self {...}"), before any name-mangling. */
    public String constructorConcreteName;

    /**
     * Set on a "CALL" node (the "dyn" builtin's own CALL) by
     * `TypeChecker.checkDynBuiltinCore` when this call's single argument
     * resolved to a string, not an array literal -- "Have dyn also take
     * a static imut string, returning a dyn of chars," confirmed
     * directly. Read by `BytecodeEmitter`'s own "dyn" case to choose the
     * "NEW_FROM_STRING" emission shape (push the string value, then one
     * runtime-character-copying instruction) instead of the ordinary
     * per-array-element push sequence "dyn([...])" always used before
     * this.
     */
    public boolean dynFromStringSource;

    /**
     * Set on a "CALL" node (the exact same node `resolvedCallTarget`
     * lives on) by `requireRecursiveCallReturnSiteOnly` once it's
     * confirmed this self-call is both the sole expression of its own
     * enclosing 'return' statement and properly 'within'-wrapped --
     * i.e. this is a legal '@recursive' tail-call site. Read back later
     * by `lowerRecursiveFunctionToLoop`'s own tree walk
     * (`replaceRecursiveReturnsInLines`) to find exactly which 'return'
     * statements need rewriting into the iterative loop form, without
     * re-deriving any of the restrictions that already proved this call
     * site legal in the first place.
     */
    public boolean isRecursiveTailCallSite;

    /**
     * Set only on an INTEGER/FLOAT literal token built directly by
     * `Lexer` for one of the built-in numeric-limit constants
     * (`u64Max`, `s8Min`, `f32Max`, ...) -- see
     * `Lexer.NUMERIC_LIMIT_CONSTANTS`'s own doc comment. Holds the
     * exact base type this literal must resolve as (`"u8"`, `"s64"`,
     * `"f32"`, ...), read back by
     * `TypeChecker.resolveExprTypeInner`'s own INTEGER/FLOAT cases to
     * override their ordinary default (`indeterminate u64`/
     * `indeterminate f32`) with a concrete, `imut`-mutability type of
     * exactly this base type instead. `null` for every ordinary
     * literal token -- the overwhelming majority -- which keeps
     * resolving exactly as it always has.
     */
    public String forcedLiteralType;

    /** True on an INTEGER token the lexer normalised from a `0x...`/`0b...` literal (its text is already the decimal value); keeps `0xFF.5` from being read as a float. */
    public boolean radixLiteral;

    /**
     * Set on an "in" operator Token itself by `TypeChecker.checkIn`
     * (never on `left`/`right`) when this specific "in" is the new
     * "plain u64 in GuaranteedEnumName" shape -- "guaranteed" meaning
     * an enum whose every variant carries an explicit discriminant
     * value (`EnumInfo.explicitValues != null`). Distinguishes this
     * shape from the ordinary range/dynarray "in" at every later
     * stage that needs to treat it differently, most importantly
     * `BytecodeEmitter`'s own "in" case: the right side here is a bare
     * enum *type* name, never a value expression, so it must be
     * emitted the same "push the name itself, don't evaluate it as an
     * expression" way `checkInstanceof`'s own right operand already
     * is, not walked through the ordinary `emitExpr` an rvalue "in"'s
     * right side gets.
     */
    public boolean isGuaranteedEnumMembershipCheck;

    /**
     * "u64 in string" shape -- a plain u64 byte/char-code membership
     * check against a string's own bytes. Distinguishes this shape from
     * the ordinary range/dynarray "in" at every later stage that
     * needs to treat it differently, most importantly `BytecodeEmitter`'s
     * own "in" case: unlike the ordinary shape (a numeric bounds/length
     * comparison against values already resolvable from the operands
     * alone), this one needs a real runtime byte-by-byte scan (the
     * string has no stored length -- see `strlen`/the `LEN_SCAN`
     * precedent for the identical "no stored length, must scan" shape
     * an unsafe dynarray's own `len()` already has), so it's emitted
     * under its own dedicated mnemonic ("IN_SCAN"), never plain "IN".
     */
    public boolean isStringMembershipCheck;

    /** Set by checkAs (`x as f64`) and checkConvertBuiltin (`wrap:<f32>(x)`, `wrap:<f64>(x)`) for an f32<->f64 conversion: emits FCONV srcType dstType. */
    public boolean isFloatConvert = false;
    /** Set by checkAs when a narrowing cast was authorized by an active "x fits T" proof: emits TRUNC. */
    public boolean isTruncCast = false;
    /** For a "fits" match condition: the checked value's integer base type, and the target integer base type. */
    public String fitsSourceBase;
    public String fitsTargetBase;

    /**
     * "as" shape only -- true for the zero-cost alias-relabel case
     * (`value as SomeAlias`, legal only when the two sides' fully
     * resolved storage+baseType are already structurally identical --
     * see `checkAs`'s alias branch). Set by `checkAs`, read by
     * `BytecodeEmitter`'s own `emitAs`: this case emits no instruction
     * at all, the same "zero bytecode presence" treatment the `mut`/
     * `imut` MODIFIER case an earlier revision of `emitAs` also had
     * (since removed as dead code -- `mut`/`imut` no longer parses as the
     * right-hand side of `as` at all; see `TypeChecker.checkAs`'s own doc
     * comment), since a pure compile-time rename between two
     * identically-represented types has nothing for a runtime instruction
     * to do. False for the other "as"
     * shape (same-family integer widening), where `isSignedWideningCast`
     * below is the one that matters instead.
     */
    public boolean isAliasCast;

    /**
     * "as" shape only, meaningless when `isAliasCast` is true -- true
     * when the same-family integer-widening cast's family is signed
     * (`leftType.baseType.charAt(0) == 's'`), false when unsigned. Set
     * by `checkAs`, read by `BytecodeEmitter`'s own `emitAs` to choose
     * "SEXT" (sign-extend) or "ZEXT" (zero-extend) in place of the old,
     * single "CAST" mnemonic -- "cast just being instances where the
     * compiler can unambiguously do it," confirmed directly, so both new
     * mnemonics stay scoped to exactly what `checkAs` already supports
     * today (no pointer/float casts -- those are planned as stdlib
     * functions instead, out of scope here).
     */
    public boolean isSignedWideningCast;

    /**
     * "par or await would be mandatory," confirmed directly -- set on a
     * "CALL" Token itself (never on the "await"/"par" wrapper) by
     * checkAwait/checkPar, immediately before delegating to the
     * ordinary checkCall dispatch, so checkCall can enforce both
     * directions of the "@async" requirement itself: calling an
     * "@async" function without this set is rejected, and setting this
     * on a call to a function that *isn't* "@async" is rejected too.
     * Also read directly by BytecodeEmitter's own emitCall, to decide
     * between plain "CALL" and "AWAIT_CALL"/"PAR_CALL" -- "AWAIT" or
     * "PAR", or null for an ordinary call.
     */
    public String asyncCallKind;

    /**
     * Set on a "CALL" Token by TypeChecker.checkSleepCall, the moment a
     * "sleep(...)" keyword call site is resolved -- read by
     * BytecodeEmitter.emitCallOrBuiltin to route this call through
     * emitSleepCall (a dedicated "SLEEP <realFuncName> <durationType>"
     * bytecode line, not an ordinary "CALL") instead of the generic
     * "CALL"/"EXTERN_CALL" path every other call site (including
     * "@par_call"/"@await_call"'s own real function calls) still uses.
     * "<realFuncName>" is `resolvedCallTarget`'s own value (the real
     * "@sleep"-decorated function's mangled name) carried directly on
     * the "SLEEP" line itself as a plain operand -- never a hardcoded
     * literal the codegen stage has to already know, the same
     * "resolved dynamically, never guessed" property "@par_call"/
     * "@await_call" already have, just made visible in the bytecode
     * text itself here rather than only in `resolvedCallTarget`.
     */
    public boolean isSleepCall;

    /**
     * Set on a "CALL" Token by the compiler-synthesized async trampoline
     * body (see TypeChecker's trampoline synthesis for "par"/"await")
     * for its own single, direct call into the real "@async"-decorated
     * function it wraps -- this is the one legitimate place a plain,
     * un-"par"/"await"-wrapped call to an "@async" function is allowed:
     * checkCall's ordinary "'@async' can only be called through par or
     * await" rejection is bypassed exactly here, and only here, since
     * this call site genuinely *is* the thread body actually running
     * that function, not a second, illegitimate direct invocation of
     * it. Never set by anything else, never read anywhere but that one
     * check.
     */
    public boolean isTrampolineOwnCall;

    /**
     * Set on the same "CALL" Token as asyncCallKind, by checkPar/
     * checkAwait, once the async handle type this specific call site
     * needs (see TypeChecker.getOrCreateAsyncHandleType) is known --
     * the struct's own bare name (asyncHandleStructName) and its full
     * canonical type text (asyncHandleCanonicalType, "owns_some_..."),
     * both read directly by BytecodeEmitter's own emitAsyncCall to
     * construct/consume the handle at the call site, entirely at the
     * bytecode-emission level (no further Token-tree synthesis needed
     * for this part).
     */
    public String asyncHandleStructName;
    public String asyncHandleCanonicalType;

    /**
     * Set alongside asyncHandleStructName/asyncHandleCanonicalType, by
     * TypeChecker.prepareAsyncCallSite: the real, already-synthesized
     * trampoline function's own name for *this* call site (see
     * TypeChecker.synthesizeAsyncTrampoline's own "selfDestructHidden"
     * parameter) -- ordinarily just "__trampoline_<realFuncName>", the
     * same one every "par"/"await" call site against a given "@async"
     * function shares, EXCEPT for a void "@async" function's own "par"
     * call sites, which get their own distinct, self-destructing
     * trampoline instead (see BytecodeEmitter.emitAsyncCall's own doc
     * comment on the void-"par" handle-cleanup problem this solves).
     * BytecodeEmitter reads this directly rather than re-deriving a
     * trampoline name from op.resolvedCallTarget, since the two can now
     * genuinely differ for the same underlying "@async" function.
     */
    public String asyncTrampolineName;

    /**
     * "I want a @pub{} block for being able to group multiple fields
     * as public in a struct... This special @pub{} block is only for
     * structs, not impls," confirmed directly -- set on a struct
     * member declaration line's own first token when it came from
     * inside one of these blocks (Parser.stripDecoratorsDeep unwraps
     * the block entirely at parse time, flattening its own lines
     * directly into the struct's ordinary member-line list, each one
     * marked this way, rather than kept as a real, nested block
     * structure the rest of the compiler would need to know about).
     * Deliberately a distinct mechanism from the ordinary "@name"
     * decorator-attachment path (which expects nothing but whitespace
     * after a decorator's own name/args on the same line, and would
     * reject the trailing '{' outright) -- "pub" is not a decorator
     * argument's own name that happens to need special block syntax,
     * this flag exists specifically because that general mechanism
     * cannot, and structurally should not, be extended to support it.
     */
    public boolean isPubBlockMember;

    /**
     * Set by TypeChecker's placement pre-pass (checkFunctionBody) on a
     * "let" token that's genuinely in the allowed leading-run position.
     * checkAssign rejects any isStatic 'let' that reaches it without
     * this flag set -- catches a 'static let' written inside a nested
     * block, which structurally never gets scanned by that pre-pass at
     * all (it only walks the function's own top-level line list).
     */
    public boolean staticApproved;

    /**
     * Set by TypeChecker.checkLookup on a "LOOKUP" node -- true when this
     * exact access relied on a live "in"/"into" bounds proof rather than
     * being trivially safe from compile-time-known facts alone (a
     * literal index into a target whose length is baked into its own
     * type). Read by requireLookupWriteAuthorized (checkAssign/
     * checkCompoundAssign) to decide whether writing through this "[]"
     * additionally needs a write-specific "into" proof -- a literal,
     * provably-safe index never does, matching how it never needed an
     * "in" proof to be read either.
     */
    public boolean isLookupProofReliant;

    /**
     * Set only on an "in"/"into" OPERATOR node whose own `.left` is a
     * "Some(...)" CALL -- the "match Some(i) in/into x{...}" sugar
     * (TypeChecker.checkSomeIndexBoundsCondition). Holds the already
     * type-checked, throwaway "origin[i]" LOOKUP node this shape's own
     * inner element-alive proof was built and validated against, so
     * BytecodeEmitter can emit its own real "GT_ALIVE_CHECK" for it
     * directly (`emitExpr(someIndexElementExpr); line("GT_ALIVE_CHECK");`),
     * without needing to re-derive/re-unwrap "range(origin)" targets a
     * second time on the emitter side. Null for every other node.
     */
    public Token someIndexElementExpr;

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
    public static final class MatchPattern {
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

    /** Null unless this is a "match" KEYWORD token that's already been type-checked. */
    public List<MatchPattern> matchPatterns;

    /**
     * Set by TypeChecker on a "CALL" node for the "sizeof"/"len"
     * builtins -- the compile-time-computed constant value, structurally
     * separate from resolvedType's text (never parsed back out of it).
     * BytecodeEmitter reads this directly to emit "PUSH value u64"
     * instead of an ordinary CALL -- "gets me the integer value, direct
     * replacement in bytecode - no instruction," confirmed directly.
     */
    public Long builtinConstantValue;

    /**
     * Set by TypeChecker on a "sizeof" CALL node whose argument is a struct (or an array of structs): the
     * struct's name. A struct's real size includes the hidden ___type field and alignment padding, and the
     * struct member reordering pass may still change the layout, so the front end does NOT fold it:
     * BytecodeEmitter emits the symbolic `SIZEOF <name> <type>` and the Optimizer's SizeofResolutionPass
     * replaces it with `PUSH <laid-out size> <type>` once the layout is final. builtinConstantValue keeps
     * only a rough estimate (used for decisions such as "is the pointee wider than one byte").
     */
    public String sizeofStruct;

    /** Like sizeofStruct, for the scale of C-style pointer arithmetic on a raw pointer to a struct (see pointerScale). */
    public String pointerScaleStruct;

    /**
     * C-style pointer arithmetic on a 'raw' pointer: set by TypeChecker on a
     * "++"/"--"/"+="/"-="/binary "+"/"-" node whose pointer operand's pointee is
     * wider than one byte -- the pointee size in bytes, by which
     * BytecodeEmitter scales the integer step/operand (0 = no scaling, the
     * node is ordinary integer arithmetic or a byte-sized pointee).
     */
    public long pointerScale;

    /** With pointerScale/binary "+"/"-": 1 = pointer +/- integer, 2 = integer + pointer, 3 = pointer - pointer (element count). 0 = not pointer arithmetic. */
    public int pointerArithKind;

    /** Set by TypeChecker on a "CALL" node for the "call" builtin -- the number of arguments passed (not counting the function-pointer argument itself), read by BytecodeEmitter to emit "INVOKE n". */
    public Integer invokeArgCount;

    /**
     * "When an owns value leaves scope, we need it to be deleted... GT_
     * DESTRUCT my_owns," confirmed directly. Set by TypeChecker at every
     * point control can leave a scope holding live 'owns' slots -- a
     * block's own natural closing point (on the block/loop/function's
     * own token), and separately on any 'return'/'break' statement that
     * jumps out early -- in the exact order BytecodeEmitter should emit
     * GT_DESTRUCT for each (reverse declaration order, already excluding
     * anything moved or, for 'return' specifically, the exact slot being
     * returned). Null/absent wherever nothing needs destructing.
     */
    public List<String> destructOnExit;

    /**
     * "guarantees the unlock() call happens on every exit path from the
     * block -- natural end, return, break," confirmed directly for
     * "lock EXPR{...}" and, separately, for a "match @lock EXPR{...}"
     * spin-lock's own OPEN case body -- the identical "things owed on
     * scope exit" unwind-list treatment destructOnExit already gets for
     * 'owns' locals, adapted here to call unlock() (or, for a spin-lock,
     * swap back to the CLOSED variant) instead of GT_DESTRUCT. Each
     * entry is an already fully type-checked, compiler-synthesized CALL
     * (or ATOMIC_SWAP-producing) expression node -- built once, then
     * reused by reference at every exit point that owes it, the same
     * "reuse the original, already-resolved token directly" precedent
     * an enum-match's own if-chain lowering already established for its
     * own condition clones. Null/absent wherever nothing is owed.
     */
    public List<Token> unlockOnExit;

    /**
     * Set only on a "CALL" node TypeChecker itself builds (never parsed
     * from real source) for the lock()/unlock() calls a "lock EXPR{...}"
     * block's own lowering synthesizes -- the one, narrow exemption from
     * the "a bare, user-written lock()/unlock() call requires 'unsafe'"
     * restriction (checkGuardLockDiscipline): the block form is this
     * language's own guaranteed-unwind-safe way in, so its own
     * compiler-generated calls must never be held to the restriction
     * that exists specifically to steer *user* code away from an
     * unguaranteed bare call.
     */
    public boolean isCompilerSynthesizedLockCall;

    /** Set by TypeChecker on a "lock" KEYWORD (block form) statement -- the synthesized, already-type-checked EXPR.lock() call BytecodeEmitter emits right at the block's own start, before its body. */
    public Token lockAcquireCall;

    /**
     * Set only on the two ".field" DOT nodes `checkLockMatchStatement`
     * itself builds (the acquire attempt's own left operand, and the
     * release assignment's own left operand) -- the one, narrow
     * exemption from the "a 'swap'-tagged struct field can only ever be
     * touched through its own 'match @lock', or from 'unsafe' code"
     * restriction (checkDot's own requireSwapFieldAccessAuthorized): a
     * "match @lock" statement's own compiler-generated ATOMIC_SWAP/
     * release access is exactly the mediated access this restriction
     * exists to require, so it must never be held to the restriction
     * that exists specifically to steer *user* code away from touching
     * the field directly, unmediated.
     */
    public boolean isCompilerSynthesizedSwapAccess;

    /**
     * Set only on a "loop" KEYWORD token `checkLockMatchStatement`
     * itself lowers a "match @lock" statement into, and only when its
     * own CLOSED case used the "CLOSED:default"/"CLOSED:default(args)"
     * shorthand -- one already-type-checked, compiler-synthesized
     * "let" assign per entry (the shared "$lm_tries" per-iteration
     * counter, initialized once to 0, followed by one per real
     * configuration parameter the invoked "impl default match @lock"
     * policy declares), each to be emitted exactly once, immediately
     * before the loop's own start label -- never once per iteration,
     * unlike every other statement inside the loop's own body.
     * BytecodeEmitter.emitLoop reads this directly; null/absent for
     * every ordinary loop (including an ordinary "match @lock" whose
     * CLOSED case is a literal "{...}" body, not this shorthand).
     */
    public List<Token> preLoopInit;

    /** Set only on the "loop" KEYWORD token `checkLockMatchStatement` lowers a "match @lock" statement into: this loop is a swap-lock spin loop, so a `continue` marked `isLockRetryContinue` inside its CLOSED case retries it (jumps to the loop's start label, after any `preLoopInit`, so retry counters are not reset). */
    public boolean isLockSpinLoop;

    /** Set only on a "continue" KEYWORD token written directly in the CLOSED case of a "match @lock": it means "retry the acquire", not the try-block `continue` of a catch body. */
    public boolean isLockRetryContinue;

    /**
     * "let:<FullType> name = expr" -- confirmed directly, refined
     * across a design conversation into: the raw token span of a
     * complete type annotation (storage where applicable, mutability
     * always -- "really the full type," confirmed directly), extracted
     * by Parser.mergeLetInLine from between the ':<' and its matching
     * '>' immediately after 'let'/'let static', before the unrelated
     * generic-instantiation collapse pass ever runs (so it can never be
     * mistaken for one -- 'let' is a KEYWORD, never a VARREF, so that
     * pass's own "VARREF ':<' ... '>'" scan was already structurally
     * incapable of matching this shape even before extraction). Set
     * only on the "let" KEYWORD token itself; null/absent for an
     * ordinary, unbound declaration.
     */
    public List<Token> typeBound;

    /**
     * "ASM name { ... }" or "ASM name \"path\"" -- set on the gathered
     * "ASM" KEYWORD declaration token by Parser.gatherAsm: exactly one
     * of asmBlockText (the raw, verbatim block body -- already fully
     * extracted and validated by Lexer.scanAsmBlock, an ASM_BLOCK
     * token's own text) or asmFilePath (the raw string-literal path,
     * unresolved -- TypeChecker reads and validates the actual file,
     * the same way it would any other real work) is non-null.
     */
    public String asmBlockText;
    public String asmFilePath;

    /**
     * Set by TypeChecker on an "asm_name;" invocation statement's own
     * VARREF token (the bare identifier that *is* the whole statement)
     * once it's been validated as a legal reference to a known ASM
     * declaration -- the fully resolved raw content to splice in,
     * copied from whichever declaration it resolved to (root-level or
     * an enclosing function-local one), so BytecodeEmitter never needs
     * to re-look-up anything by name. Null on every other VARREF
     * (including one that merely shares text with some ASM name but
     * was rejected as an illegal non-statement use -- that case never
     * reaches bytecode emission at all, since TypeChecker throws on it
     * first).
     */
    public String resolvedAsmContent;

    public Token(TokenType type, String text, int line, String file) {
        this.type = type;
        this.text = text;
        this.line = line;
        this.file = file;
    }

    /**
     * Deep-clones this raw, pre-gather token tree -- used only by
     * TypeChecker's "impl default match @lock" template instantiation
     * (TypeChecker.DefaultLockMatchPolicy), which needs a genuinely
     * fresh copy of the policy's own raw parameter list/body at every
     * "CLOSED:default"/"CLOSED:default(args)" use site: reusing the
     * same raw template tokens across multiple sites would be unsafe,
     * since gathering/RPN-conversion/tree-building/type-checking all
     * mutate tokens in place, and a first use site's own check would
     * otherwise permanently corrupt the shape every later, independent
     * use site would need to start from.
     *
     * Deliberately narrower than a general-purpose Token clone -- it
     * only copies the fields a token can actually carry *before*
     * Parser.gatherKeywordBlocks/RpnConverter/TreeBuilder ever touch it
     * (type/text/line/file, a literal's own value/delimiter, a
     * numeric-limit constant's own forced type, and -- recursively --
     * every nested '('/'['/'{' delineator's own childs). Pinned
     * comments/decorators/the handful of flags "let"-merging can
     * already have set (isStatic/isConst/typeBound) are shared by
     * reference, not deep-cloned -- each is read-only, per-declaration
     * metadata that nothing downstream ever mutates in place, the same
     * "safe to share, nothing mutates it" reasoning
     * lowerEnumMatchToIfChain/checkLockBlock's own reused (never
     * cloned) sub-trees already established elsewhere in this
     * compiler. Every field only ever set by a later stage
     * (resolvedType, destructOnExit, matchPatterns, sub/left/right, and
     * so on) is left at its default, since a raw, pre-gather token can
     * never carry one yet.
     */
    public Token deepCloneRaw() {
        Token clone = new Token(this.type, this.text, this.line, this.file);
        clone.literalValue = this.literalValue;
        clone.quoteDelimiter = this.quoteDelimiter;
        clone.forcedLiteralType = this.forcedLiteralType;
        clone.pinnedComments = this.pinnedComments;
        clone.decorators = this.decorators;
        clone.unsafeTags = this.unsafeTags;
        clone.isStatic = this.isStatic;
        clone.isConst = this.isConst;
        clone.typeBound = this.typeBound;
        clone.childs = new ArrayList<>();
        for (Token child : this.childs) {
            clone.childs.add(child.deepCloneRaw());
        }
        return clone;
    }

    /**
     * Deep-clones this already-CHECKED token tree: every instance field is copied, and every Token reachable through a field (directly or
     * inside a List) is itself cloned (shared sub-trees stay shared inside the copy, via the identity memo). Non-token values (strings,
     * numbers, TypeInfo text, MatchPattern records) are shared by reference: they are read-only once a statement has been checked.
     * Used only by `TypeChecker.lowerRecursiveFunctionToLoop`, to place a second copy of the already-validated base-case block after the
     * recursion loop (the raw-only `deepCloneRaw` above cannot do this: it runs before checking and drops every resolved field).
     */
    public Token deepCloneChecked() {
        return cloneChecked(new java.util.IdentityHashMap<>());
    }

    private Token cloneChecked(java.util.IdentityHashMap<Token, Token> memo) {
        Token done = memo.get(this);
        if (done != null) {
            return done;
        }
        Token clone = new Token(this.type, this.text, this.line, this.file);
        memo.put(this, clone);
        try {
            for (java.lang.reflect.Field f : Token.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                f.set(clone, cloneCheckedValue(f.get(this), memo));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("internal error: cannot clone a checked token", e);
        }
        return clone;
    }

    private static Object cloneCheckedValue(Object v, java.util.IdentityHashMap<Token, Token> memo) {
        if (v instanceof Token) {
            return ((Token) v).cloneChecked(memo);
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<?>) v) {
                out.add(cloneCheckedValue(o, memo));
            }
            return out;
        }
        return v;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append("(").append(text).append(")");
        sb.append("[").append(file).append(":").append(line).append("]");
        if (!pinnedComments.isEmpty()) {
            sb.append(" //").append(pinnedComments.size()).append(" comment(s)");
        }
        return sb.toString();
    }
}

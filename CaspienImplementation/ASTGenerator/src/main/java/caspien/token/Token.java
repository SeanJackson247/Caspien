package caspien.token;

import java.util.ArrayList;
import java.util.List;

/**
 * The one and only token class used throughout the whole compiler
 * pipeline, from raw lexing through to RPN tree construction and type
 * checking. Later stages populate fields (childs, sub, left, right,
 * resolvedType) that the lexer stage leaves null/empty.
 */
public class Token {

    /** What the TypeChecker recorded for the BytecodeEmitter (see TokenChecked). */
    public final TokenChecked chk = new TokenChecked();

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


    /** Comments pinned to this token (nearest non-whitespace token rule). */
    public List<Token> pinnedComments = new ArrayList<>();

    /** Children of a delineator token ( [ { etc, populated during the nesting stage. */
    public List<Token> childs = new ArrayList<>();

    /** Statements/lines scooped up into a keyword-block token (if/loop/func/etc), populated later. */
    public List<Token> sub = new ArrayList<>();

    /** Populated when this token becomes an operator node in the RPN/expression tree. */
    public Token left;
    public Token right;

    /** The following branch of an if/elseif/else or match/elsematch chain (set on the chain head's branches only; an operator never has one). */
    public Token next;

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



    /** Null/empty if this token has no decorators. See the Decorator class doc for how these get here. */
    public List<Decorator> decorators;

    /** On an 'unsafe' block statement only: the tags written after the keyword (`unsafe deref extern{`), in source order; empty/null otherwise. */
    public List<String> unsafeTags;


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


    public Token(TokenType type, String text, int line, String file) { }

    // ---- named views of `sub`: what it holds depends on the keyword (docs/Parser.md, "Keyword-block token shapes") ----

    private static final java.util.Set<String> DECL_KINDS = java.util.Set.of("func", "struct", "enum", "interface", "library", "type", "const", "extern", "interface_method", "impl_default_lock_match");
    private static final java.util.Set<String> FUNC_KINDS = java.util.Set.of("func", "extern", "interface_method", "impl_default_lock_match");
    private static final java.util.Set<String> COND_KINDS = java.util.Set.of("if", "elseif", "match", "elsematch", "lock");
    private static final java.util.Set<String> OPERAND_KINDS = java.util.Set.of("return", "throw", "try", "assume");

    private Token subAt(int index, java.util.Set<String> kinds, String role) { return null; }

    /** func/struct/enum/interface/library/type/const/extern: the name token. */
    public Token declName() { return null; }

    /** func/extern: the `(` token whose childs are the flat parameter list. */
    public Token paramsTok() { return null; }

    /** func/extern: the tokens between `)` and the body (empty = void). */
    public List<Token> returnTypeTokens() { return null; }

    /** if/elseif/match/elsematch/lock: the tested expression (a tree root once TreeBuilder has run). */
    public Token condition() { return null; }

    /** return/throw/try (non-block)/assume: the single expression the statement carries. */
    public Token operand() { return null; }

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
    public Token deepCloneRaw() { return null; }

    /** The raw-clone copy of one token's own fields, with an empty childs list. */
    private static Token rawShell(Token src) { return null; }

    /**
     * Deep-clones this already-CHECKED token tree: every instance field is copied, and every Token reachable through a field (directly or
     * inside a List) is itself cloned (shared sub-trees stay shared inside the copy, via the identity memo). Non-token values (strings,
     * numbers, TypeInfo text, MatchPattern records) are shared by reference: they are read-only once a statement has been checked.
     * Used only by `TypeChecker.lowerRecursiveFunctionToLoop`, to place a second copy of the already-validated base-case block after the
     * recursion loop (the raw-only `deepCloneRaw` above cannot do this: it runs before checking and drops every resolved field).
     */
    public Token deepCloneChecked() { return null; }

    @Override
    public String toString() { return null; }
}

package caspien.genericsexpander;
import caspien.token.TokenCloner;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;
import caspien.parser.Parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import caspien.token.GenericBoundCheck;

/**
 * New pipeline stage, runs on the fully import-resolved, dup-expanded root
 * list, before TypeChecker.
 *
 * Monomorphizes every generic struct/func/interface/impl into one concrete,
 * mangled copy per unique combination of type arguments actually used
 * anywhere in the program, and rewrites every usage site (both the ":<...>"
 * expression form and the bare "<...>" annotation form) to a plain
 * reference to the mangled name. By the time TypeChecker runs, no generic
 * syntax survives anywhere -- same "expand away entirely" approach already
 * used for 'dup' and for 'for'-loops in this codebase, and consistent with
 * "resolve generics, then the rest of type checking": an unbound template
 * is never emitted and never type-checked.
 *
 * Known, deliberate scope limits for this first version (flagged rather
 * than silently glossed over):
 *  - No generic-function overloading: at most one generic template may be
 *    registered per name, for func/struct/interface/library alike -- a
 *    second one sharing a name is a hard, direct compile error
 *    (`registerTemplate`), not merely "unsupported": a real, previously-
 *    latent bug here let a second same-named template silently overwrite
 *    the first in this class's own registration map, with zero warning,
 *    since a plain `Map.put` never rejects a collision on its own --
 *    confirmed directly by testing, not assumed, before this check
 *    existed (two same-arity `func f<T>(...)` overloads compiled with no
 *    error at all, one of them simply vanishing; a later, unrelated call
 *    site then failed with a confusing "no overload matches"/"expects N
 *    type arguments" error that gave no hint the real cause was an
 *    earlier, silently-discarded declaration). Plain (non-generic)
 *    overloads are unaffected and still handled entirely by TypeChecker
 *    as before.
 *  - No unification/inference: every instantiation site must already carry
 *    fully concrete (or already-generic-and-resolvable) type arguments.
 *  - Function-pointer-shaped type annotations are not scanned for nested
 *    generic type arguments (their own inner parameter/return types are
 *    left untouched) -- follow-up item if that combination is ever needed.
 *  - Substitution of a bound type-parameter name inside a template's body
 *    is a blanket VARREF text-replace, not scope-aware -- a generic
 *    parameter name should not be reused as a member/parameter/local name
 *    inside that same template's own body, or it will be incorrectly
 *    substituted too. Given this language's existing "no compile-time
 *    constant beyond literal arithmetic" minimalism, this is treated as an
 *    acceptable, documented restriction rather than solved with a full
 *    scoped-substitution pass.
 */
public class GenericsExpander {

    private final Map<String, Token> structTemplates = new HashMap<>();
    private final Map<String, Token> funcTemplates = new HashMap<>();
    private final Map<String, Token> interfaceTemplates = new HashMap<>();
    private final Map<String, Token> libraryTemplates = new HashMap<>();
    /** impl templates, keyed by the concrete-side base name they target (List, not List<T>). */
    private final Map<String, List<Token>> implTemplatesByTarget = new LinkedHashMap<>();

    /** "TemplateName<arg1,arg2,...>" -> already-generated mangled name. Reserved before generation to break cycles. */
    private final Map<String, String> memo = new HashMap<>();

    private final List<Token> output = new ArrayList<>();

    /**
     * NOTE ON SHAPE: every entry in `rootEntries` (and every entry this
     * method places into `output`) is a LINE token wrapping exactly one
     * real declaration in `.childs.get(0)` -- the same "wrapAsLine" shape
     * `Parser` already produces for every root-level construct, and the
     * exact shape `TypeChecker.check`/`collectDeclarations` already expects
     * (`lineTok.childs.get(0)`). This method unwraps to inspect/register
     * each declaration, but always keeps (or re-creates) that LINE wrapper
     * around anything placed into `output`, so the list handed to
     * TypeChecker looks structurally identical to what it already expects,
     * just with every generic construct fully monomorphized away.
     */
    public List<Token> expand(List<Token> rootEntries) { return null; }

    /**
     * Registers a generic struct/func/interface/library template into its
     * own by-name map, rejecting outright a second template sharing that
     * exact name (generic-function/struct/interface/library "overloading"
     * -- more than one generic template per name -- is not supported; see
     * this class's own doc comment for the real, previously-silent bug
     * this closes). `kindLabel` is purely for the error message.
     */
    private void registerTemplate(Map<String, Token> templates, String kindLabel, String name, Token entry) { }

    private Token wrapAsLine(Token declaration) { return null; }

    private void registerImplTemplate(Token implEntry, Token concreteNameTok) { }

    private void validateImplTargetPattern(Token implEntry, Token targetTok) { }

    // ---- Step A: annotation-context scanning --------------------------

    private void scanAnnotationContexts(Token start) { }

    private void scanFuncOrMethodSignature(Token funcOrMethod) { }

    /** Splits on top-level ',' / TERMINATOR into "name : type..." groups, mirroring TypeChecker's own splitIntoRawGroups. */
    /**
     * Splits a flat "name : type [, name : type ...]" token list (a struct
     * member line, or a func/method's raw parameter list) into individual
     * segments on top-level ',' / TERMINATOR, and scans each segment's type
     * tokens for a bare generic-argument list.
     *
     * Segments are found and processed strictly left-to-right using a live
     * `flat.subList(...)` view for whichever segment is currently being
     * scanned -- not a copy -- specifically so that when
     * `scanTypeTokenSpan` removes the consumed "<...>" tokens from that
     * view, the removal genuinely propagates back into `flat` itself
     * (the same list object `TypeChecker` will read later). Because a
     * removal shrinks `flat`, the position of the *next* separator is
     * re-located fresh after each segment rather than computed once
     * up front.
     */
    private void scanRawNameTypeGroups(List<Token> flat) { }

    /** Next top-level ',' or TERMINATOR at or after `from`; a ',' inside a type-argument list ("Pair<K, V>") is not a separator. */
    private int findNextSeparator(List<Token> flat, int from) { return 0; }

    /**
     * Scans one flat type-annotation token span (MODIFIER* VARREF
     * ['<' args '>'] DELINEATOR('[')*) for a bare generic-argument list
     * following the base-type VARREF, consuming it out of `span` in place
     * and attaching the parsed groups to that VARREF's .genericArgs --
     * after this call, `span` looks exactly like an ordinary, non-generic
     * type annotation to every later stage (TypeChecker.resolveTypeAnnotation
     * needs zero changes).
     */
    private void scanTypeTokenSpan(List<Token> span) { }

    /** One span: only the first base-type VARREF counts; nested argument groups are queued, then the post step. */
    private void stepSpan(GenericsTask task, List<GenericsTask> out) { }

    private GenericsTask spanTask(List<Token> span) { return null; }

    private List<List<Token>> splitTopLevelCommaGroupsBare(List<Token> tokens) { return null; }

    // ---- generic walk + per-token processing ---------------------------

    /** Runs the walk over `node` (every nested generic usage is resolved and instantiated). */
    private void walkAndProcess(Token node) { }

    /** Drives the task stack until it is empty; tasks queue their follow-ups in run order. */
    private void run(GenericsTask first) { }

    private void step(GenericsTask task, List<GenericsTask> out) { }

    private String mangleName(String templateName, List<String> argNames) { return null; }

    /** The synchronous part of one instantiation (lookups, clone, substitution, output); queues the follow-up walks. */
    private void beginInstantiation(String templateName, List<String> argNames, List<Token> argTokens,
            String mangled, Token usageTok, List<GenericsTask> out) { }

    /**
     * Builds the "must implement this interface" checks a freshly
     * monomorphized instantiation still owes, one per type parameter the
     * template actually declared a bound on (`func f<T: SomeInterface>`) --
     * enforcement itself happens later, in TypeChecker.validateGenericBounds,
     * once every struct/impl is fully collected and `structImplementsInterface`
     * is reliable; this only records *what* needs checking, right at the
     * one instantiation site GenericsExpander already has both pieces of
     * information (which concrete type was substituted for which bound
     * parameter) in hand at the same time.
     */
    private List<GenericBoundCheck> buildPendingBoundChecks(Token template, List<String> argNames) { return null; }

    /** The synchronous part of one impl instantiation; queues the walks (interface name, then the impl itself). */
    private void beginImplInstantiation(Token implTpl, List<String> structArgNames, List<Token> structArgTokens,
            String mangledStructName, List<GenericsTask> out) { }

    /**
     * An impl declaring its own type parameter(s) whose target isn't
     * generic at all (or, if it is generic, doesn't consume every one of
     * the impl's own declared params) -- every param never consumed is
     * "residual," used purely inside this impl's own methods (the "impl
     * with independent type params" feature). This impl itself is already
     * concrete (nothing to monomorphize per-target-instantiation, since the
     * target struct isn't being instantiated here at all), so it's placed
     * directly into `output`, with each qualifying method tagged the same
     * way `generateImplInstantiation` tags one for its own residual params.
     */
    private void processImplWithOnlyResidualParams(Token lineEntry) { }

    /**
     * Computes which of implTpl's own declared type params were not
     * consumed by `consumedNames` (the impl's own param names that were
     * successfully matched against the target struct's own generic
     * arguments -- empty when the target isn't generic at all), and tags
     * each method token in implClone.childs whose own subtree actually
     * references one or more of those residual names with its own
     * .typeParams (just the referenced subset, in implTpl's own declared
     * order) and .typeParamBounds -- a deliberate, new exception to "every
     * method token TypeChecker ever sees has typeParams == null" (see
     * Token.typeParams' own doc comment): here it instead marks "this
     * specific method is itself a residual-type-param template," consumed
     * later by TypeChecker at each explicit ":<...>" call site. A method
     * that doesn't reference any residual param at all is left completely
     * untouched -- an ordinary, non-generic method, unaffected by any of
     * this. `implTpl`/`implClone` may be the same object (see
     * processImplWithOnlyResidualParams, which never clones at all) -- this
     * method always finishes reading implTpl's own fields before writing
     * anything back, so that's safe either way. implTpl.typeParams itself
     * is always cleared to null on implClone at the end -- a residual param
     * only ever lives on the individual methods that use it from here on,
     * never on the impl declaration itself.
     */
    private void tagResidualGenericMethods(Token implTpl, Token implClone, Set<String> consumedNames) { }

    /**
     * Recursively scans `node`'s own subtree (left/right/childs/sub,
     * genericArgs, typeBound) for a VARREF whose text is exactly `name` --
     * used to decide whether a given impl method actually uses one of its
     * own impl's residual type parameters at all, so a plain, non-generic
     * sibling method on the same impl is left completely untouched.
     */
    private boolean referencesName(Token root, String name) { return false; }

    private Token renamedCopy(Token nameTok, String newName) { return null; }

    /** Blanket VARREF-text substitution -- see class doc for the documented shadowing caveat. */
    /**
     * `subFileMap` (template param name -> the real call site's own file,
     * i.e. the file the actual type-argument token was written in) is a
     * known, narrow limitation fix -- "a generic stdlib function's own
     * monomorphized tokens carry the template's declaring file... not the
     * real call site's," confirmed directly as the root cause of a
     * false-positive private-member-access error when a generic stdlib
     * function is instantiated with a caller's own private struct as its
     * type argument. Every substituted VARREF's own `.file` is corrected
     * to the real call site's file alongside its `.text`, so a later
     * privacy check (`checkTypeNameVisibility`, keyed on `at.file`) sees
     * the reference as if it were written at the real call site, not
     * inside the template's own body. `null` is accepted (some callers --
     * `impl_default_lock_match`'s own use of a structurally similar
     * substitution helper elsewhere, or any future caller with no
     * real per-argument file to thread through -- have none to give), in
     * which case only `.text` is substituted, exactly as before this fix.
     */
    private void substituteParams(Token root, Map<String, String> subMap, Map<String, String> subFileMap) { }

    /** Pushes left, right, next, childs, sub (and genericArgs, typeBound when asked) so that they pop in that order. */
    private void pushChildrenReversed(Token node, Deque<Token> stack, boolean withTypeParts) { }

    private static void pushNonNull(Deque<Token> stack, Token t) { }

    // ---- deep clone -----------------------------------------------------

    private Token deepClone(Token src) { return null; }
}

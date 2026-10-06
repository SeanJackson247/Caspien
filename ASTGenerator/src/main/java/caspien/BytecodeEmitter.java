package caspien;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 7 (final stage of the first deliverable): walks the fully
 * type-checked tree and emits the spec's stack-based bytecode as plain
 * text. Assembly generation is explicitly out of scope for this
 * deliverable -- this is the target artifact.
 *
 * Instruction shapes, per the spec:
 *   - PUSH value type
 *   - a binary operator: OPNAME leftType rightType returnType
 *   - a unary operator: OPNAME operandType returnType
 *   - ALLOC name type          (hoisted to the top of its enclosing {} scope)
 *   - ASSIGN leftType rightType returnType
 *   - RET type                 (RET imut_void for an empty/void return)
 *   - CALL funcName             (the one exception: takes the function
 *                                name as a literal argument rather than
 *                                type triples; call arguments are PUSHed
 *                                separately before it, per spec)
 *   - ',' emits nothing at all -- just compile left then right
 *   - FUNC_START/RETURNS/ARG.../FUNC_END, STRUCT_START/STRUCT_MEMBER.../
 *     STRUCT_END, and ENUM name member... exactly as given in the spec
 *
 * Two things the spec's own examples leave genuinely ambiguous or
 * unspecified, resolved here and documented (see CLAUDE.md for the
 * fuller reasoning):
 *   - if/elseif/else chaining: the given example only shows a single
 *     'if' with no chain, and its literal shape (jump straight to the
 *     chain's *final* end label on a false condition) would be wrong
 *     for a real chain -- it would skip subsequent elseif/else branches
 *     instead of falling through to check them. Each branch here jumps
 *     to the *next* branch's label on false (or the shared end label,
 *     for the last branch) -- which is a strict generalization that
 *     collapses to exactly the spec's given shape when there's no
 *     elseif/else at all.
 *   - postfix '++' and compound assignment (+=, -=, etc.) aren't given
 *     bytecode shapes in the spec. Both are desugared here into
 *     existing primitives rather than inventing new opcodes: "x++"
 *     compiles as if it were "x = x .INC" (push target, push value,
 *     INC, ASSIGN); "x += y" compiles as if it were "x = x + y" (push
 *     target, push x, push y, ADD, ASSIGN).
 */
public class BytecodeEmitter {

    /** The mangled name of the function currently being emitted -- set/cleared around each emitFunc call, read by emitCall to detect a self-call (needed for RECURSIVE_CALL vs CALL). */
    private String currentFuncMangledName;
    /** Same lifetime as `currentFuncMangledName`, set/cleared alongside it -- needed by `emitDestructList` to look up an `owns` local's own type (`TypeChecker.FuncInfo.ownsLocalTypes`) when nulling a slot out after its own natural-end-of-scope destruct. */
    private TypeChecker.FuncInfo currentFuncInfo;

    /**
     * One entry per call site (within the function currently being
     * emitted) that staged the reserved `gt_routine_address` slot to
     * point at a call-site-specific unwind landing pad -- see
     * `stageCallSiteForUnwind`'s own doc comment for the whole design.
     * Reset to a fresh, empty list at the start of every
     * `emitFuncUnderName` call (saved/restored exactly like
     * `currentFuncMangledName`/`currentFuncInfo`/`inGtSuppressedContext`
     * already are), populated as ordinary `CALL`/`RECURSIVE_CALL` sites
     * are emitted, and drained by `emitCallSiteUnwindLandingPads` right
     * before this same function's own `FUNC_END` -- by which point every
     * call site in its body has already been visited, so the full,
     * final list is known.
     */
    private List<PendingUnwindLandingPad> pendingCallSiteUnwindLandingPads = new ArrayList<>();

    /**
     * Maps each "try" token (within the function currently being
     * emitted) to the catch-block label already assigned to it by
     * `emitHoistedCatchBlocks`, before a single statement of the
     * function's real body is emitted -- so that when emission later
     * reaches the call site a "try" directly wraps (`emitTryGuardedCall`),
     * the label to redirect into already exists. Reset to a fresh, empty
     * map at the start of every `emitFuncUnderName` call, same lifetime
     * as `pendingCallSiteUnwindLandingPads`.
     */
    private Map<Token, String> tryCatchLabels = new HashMap<>();
    /**
     * The identical `tryCatchLabels` shape (per-function, populated by a
     * pre-pass before any of this function's own statements are
     * emitted, save/restored around `emitFuncUnderName`), for "try
     * { ... }" blocks instead of real catch nodes: `tryBlockLabels`
     * (keyed by the try-block's own Token) is where its own "after"
     * label lives, and `continueTargetLabels` (keyed by a 'continue'
     * Token) is which try-block's label that particular 'continue'
     * targets -- see `collectTryBlockLabels`'s own doc comment for why
     * this needs to be precomputed rather than tracked with a live,
     * push-as-you-go stack the way `loopEndLabels`/'break' gets away
     * with.
     */
    private Map<Token, String> tryBlockLabels = new HashMap<>();
    private Map<Token, String> continueTargetLabels = new HashMap<>();

    /**
     * "Early on in the lowering, the TRY eats the next two
     * instructions," confirmed directly: rather than emitting a
     * standalone "TRY &lt;label&gt;" line of its own ahead of the
     * wrapped call's own staging block, a "try" now redirects that
     * exact staging write -- the "ADDR gt_routine_address" / "PUSH_LABEL
     * ..." / "ASSIGN ..." triple `stageCallSiteForUnwindNames` already
     * emits ahead of every ordinary call -- to point at the catch
     * block's own label directly, in place of a fresh, ordinary
     * `@gt_callsite__...` landing-pad label. One-shot: set immediately
     * before `emitExpr`ing the exact call node a "try" directly wraps
     * (`emitTryGuardedCall`), consumed and cleared by the very next
     * `stageCallSiteForUnwindNames` call -- which is guaranteed to be
     * the staging write for that exact call (`emitCall`'s own
     * `stageCallSiteForUnwind` call happens before any argument of that
     * call is evaluated), so it can never leak into an unrelated,
     * ordinary call nested inside that same call's own argument list
     * (e.g. the plain `g()` in "try f(g()) catch{...}" -- g's own
     * staging must still get its own ordinary landing pad, never be
     * redirected into f's catch block).
     */
    private String pendingTryCatchLabel;

    /** One buffered call-site unwind landing pad -- see `pendingCallSiteUnwindLandingPads`'s own doc comment. */
    private static final class PendingUnwindLandingPad {
        final String label;
        final List<String> destructNames;
        final List<Token> unlockCalls;
        final Token at;

        PendingUnwindLandingPad(String label, List<String> destructNames, List<Token> unlockCalls, Token at) {
            this.label = label;
            this.destructNames = destructNames;
            this.unlockCalls = unlockCalls;
            this.at = at;
        }
    }

    /**
     * "Generation of ghost table instructions... don't happen in the
     * ghost table decorated functions... function calls within these
     * functions need to be to dundered duplicates of the actual
     * functions, which have the GT instructions removed... this needs
     * to descend the whole way down," confirmed directly. Two pieces
     * of state, both computed once at the start of emit() and read
     * throughout:
     *
     * gtDecoratedFuncMangledNames: the (up to four) gt_init/
     * gt_alive_check/gt_destruct/gt_register functions' own mangled
     * names -- these are emitted exactly once, under their real name,
     * but with gt-suppression active for their own body (they never
     * get a dundered duplicate of themselves -- nothing ever calls
     * them via ordinary CALL).
     *
     * gtReachableMangledNames: every function/method transitively
     * called (CALL/RECURSIVE_CALL only -- an extern has no body to
     * duplicate, and a function-pointer target isn't statically known
     * at all, see the '@pure'-style ban on call() below) from within
     * any of the four gt-decorated functions' own bodies. Each of
     * these gets emitted *twice*: once completely normally (ordinary
     * callers elsewhere in the program see real GT_DESTRUCT/
     * GT_ALIVE_CHECK behavior), and once more as a "__"-prefixed
     * dundered duplicate with gt-suppression active -- never re-typed-
     * checked, always the exact same already-checked tree, emitted a
     * second time under a different bytecode identity.
     */
    private Set<String> gtDecoratedFuncMangledNames = new HashSet<>();
    private Set<String> gtReachableMangledNames = new HashSet<>();
    /** True while emitting either a gt-decorated function's own body, or any dundered ("__"-prefixed) duplicate -- both get identical treatment: GT_DESTRUCT is silently skipped, a bare 'match' condition that would need GT_ALIVE_CHECK is a hard error instead, and any CALL/RECURSIVE_CALL target found in gtReachableMangledNames is redirected to its own "__"-prefixed duplicate. */
    private boolean inGtSuppressedContext = false;

    private static final Map<String, String> ARITH_NAMES = new HashMap<>();
    static {
        ARITH_NAMES.put("+", "ADD");
        ARITH_NAMES.put("-", "SUB");
        ARITH_NAMES.put("*", "MUL");
        ARITH_NAMES.put("/", "DIV");
        ARITH_NAMES.put("%", "MOD");
    }
    private static final Map<String, String> COMPOUND_TO_ARITH = new HashMap<>();
    static {
        COMPOUND_TO_ARITH.put("+=", "+");
        COMPOUND_TO_ARITH.put("-=", "-");
        COMPOUND_TO_ARITH.put("*=", "*");
        COMPOUND_TO_ARITH.put("/=", "/");
        COMPOUND_TO_ARITH.put("%=", "%");
    }
    private static final Map<String, String> COMPARISON_NAMES = new HashMap<>();
    static {
        COMPARISON_NAMES.put("<", "LT");
        COMPARISON_NAMES.put(">", "GT");
        COMPARISON_NAMES.put("<=", "LT_EQ");
        COMPARISON_NAMES.put(">=", "GT_EQ");
        COMPARISON_NAMES.put("==", "EQ");
        COMPARISON_NAMES.put("!=", "NEQ");
    }

    private final StringBuilder out = new StringBuilder();
    private int labelCounter = 0;
    /**
     * Every distinct string literal's raw (unescaped) content -> its
     * generated hoisted id ("string_id1", "string_id2", ...), in first-
     * occurrence order. Char literals are deliberately excluded --
     * "not char literals," confirmed directly -- CHAR still goes
     * through the ordinary inline PUSH 'c' type shape unchanged.
     * Populated lazily, purely as a side effect of the single existing
     * top-to-bottom emission pass (via literalValueOf's STRING case,
     * the one and only place a string literal's text is ever turned
     * into bytecode text anywhere in this class), then emitted as
     * "STRING id \"text\"" lines prepended to the very front of the
     * finished output right before emit() returns -- "right to the top
     * of the entire bytecode," confirmed directly, ahead of even
     * GLOBAL lines.
     */
    private final Map<String, String> hoistedStrings = new LinkedHashMap<>();

    /** Two identical string literals anywhere in the program -- whether both are ordinary PUSHed expressions, or one is a PUSH and the other is a GLOBAL/ALLOC_STATIC folded constant, or anything else -- always share the same id: a real string pool, not "one id per occurrence." */
    private String hoistedStringId(String rawValue) {
        return hoistedStrings.computeIfAbsent(rawValue, v -> "string_id" + (hoistedStrings.size() + 1));
    }

    /** Set at the start of emit(); used by emitInstantiate to read a struct's declared member order. */
    private TypeChecker checker;
    /** Stack of enclosing loops' end labels, innermost last; 'break' jumps to the top of this. */
    private final List<String> loopEndLabels = new ArrayList<>();
    /**
     * One entry per enclosing loop, parallel to `loopEndLabels`: where a loop-`continue` jumps. For a `loop` it is
     * its start label; for a `for` it is a label right before the step, created on the first `continue` that
     * needs it (so a program without one gets exactly the same labels and numbering as before).
     */
    private final List<String[]> loopContinueTargets = new ArrayList<>();
    /** Start labels of the enclosing 'match @lock' spin loops, innermost last: the target of a lock-retry 'continue'. */
    private final List<String> lockRetryLabels = new ArrayList<>();

    /**
     * "These blocks are not reflected in the bytecode output," confirmed
     * directly -- recursively expands "unsafe"/"safe" wrapper tokens at
     * root into their own contents inline (mirroring TypeChecker's
     * flattenRootLines, minus the nesting validation, since that already
     * ran and this only executes on an already-valid tree), so every
     * declaration wrapped in one is emitted exactly as if the wrapper
     * weren't there at all.
     */
    private List<Token> flattenRootLinesForEmit(List<Token> lines) {
        List<Token> out = new ArrayList<>();
        for (Token lineTok : lines) {
            Token t = lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && (t.text.equals("unsafe") || t.text.equals("safe"))) {
                out.addAll(flattenRootLinesForEmit(t.childs));
            } else {
                out.add(lineTok);
            }
        }
        return out;
    }

    public String emit(List<Token> rootLines, TypeChecker checker) {
        this.checker = checker;
        Map<String, TypeChecker.StructInfo> structs = checker.getStructs();
        Map<String, TypeChecker.EnumInfo> enums = checker.getEnums();
        computeGtReachableFunctions();
        Set<String> lockExempt = new HashSet<>(gtReachableMangledNames);
        lockExempt.addAll(gtDecoratedFuncMangledNames);
        LockNestingCheck.run(checker, lockExempt);

        // Globals first, all of them, regardless of where in the file
        // they were declared -- confirmed directly by example (every
        // 'GLOBAL' line appears before any 'FUNC_START'). Emitted via
        // the exact same folding logic ("same static instantiation
        // rules as the static variables," confirmed directly) local
        // statics already use, just with a different leading mnemonic.
        for (String name : checker.getGlobalOrder()) {
            emitStaticAlloc("GLOBAL", name, checker.getGlobals().get(name).canonical(),
                    checker.getGlobalRhs().get(name));
        }

        for (Token lineTok : flattenRootLinesForEmit(rootLines)) {
            Token t = lineTok.childs.get(0);
            if (t.type == TokenType.OPERATOR && t.text.equals("=")) {
                continue; // a global -- already emitted above
            }
            if (t.type == TokenType.VARREF) {
                // "asm_name;" at the root -- TypeChecker already
                // resolved and validated this as a real ASM invocation
                // (resolvedAsmContent is only ever set there); nothing
                // else can legally reach here as a bare VARREF root
                // line at all.
                emitAsm(t.resolvedAsmContent);
                continue;
            }
            switch (t.text) {
                case "struct":
                    emitStruct(structs.get(t.sub.get(0).text));
                    break;
                case "type":
                    break; // no bytecode representation at all -- a type alias fully erases (confirmed directly: "there's no aliases in the bytecode"), every usage site already resolved to the real underlying type by TypeChecker
                case "const":
                    break; // no bytecode representation at all, same reasoning as 'type' just above -- a const fully erases too, every reference already substituted with the literal it names by TypeChecker
                case "enum":
                    emitEnum(enums.get(t.sub.get(0).text));
                    break;
                case "func":
                    emitFunc(findFuncInfo(checker, t));
                    break;
                case "interface":
                    break; // no bytecode representation at all -- confirmed directly
                case "impl":
                    for (Token methodTok : t.childs) {
                        // A generic constructor ("impl<T> constructor for
                        // Concrete<T>(...) self {...}") is registered by
                        // TypeChecker.collectImpl into the shared
                        // `functions` overload map, not this impl's own
                        // `methods`/`staticMethods` (see collectImpl's own
                        // doc comment on its `isConstructorDecl` branch),
                        // so its FuncInfo is looked up the same way a
                        // plain top-level function's is, not via
                        // findImplMethodInfo (which only ever searches
                        // this concrete type's own ImplInfo.methods/
                        // staticMethods/genericMethodInstances, and would
                        // throw "no FuncInfo for impl method" here).
                        if (methodTok.isConstructorDecl) {
                            emitFunc(findFuncInfo(checker, methodTok));
                        } else {
                            emitFunc(findImplMethodInfo(checker, t, methodTok));
                        }
                    }
                    break;
                case "library":
                    // No STRUCT_START/etc. of its own -- a library has no
                    // instances at all, so its own functions emit exactly
                    // like a plain top-level func/static method: ordinary
                    // FUNC_START/FUNC_END blocks under their own already-
                    // full-signature-mangled names (see
                    // TypeChecker.collectLibrary's own mangledName). An
                    // inherited (library "extends") function is never re-emitted
                    // here -- it only exists once, under its own
                    // originally-declaring library's root token, exactly
                    // the same "no duplication, just a naming-convention
                    // reference" shape interface conformance already has.
                    for (Token funcTok : t.childs) {
                        emitFunc(findLibraryFuncInfo(checker, t, funcTok));
                    }
                    break;
                case "extern":
                    emitExtern(checker.getExterns().get(t.sub.get(0).text));
                    break;
                case "export":
                    break; // no bytecode representation of its own -- already folded into the target func's own FUNC_START block as an "EXPORT" line (see emitFunc)
                case "impl_default_lock_match":
                    break; // a pure template declaration -- no bytecode representation of its own at all; only a fresh, deep-cloned copy checked at each use site is ever emitted, folded directly into that use site's own "match @lock" spin-loop bytecode
                case "ASM":
                    // Named: nothing here -- it self-emits only at each
                    // "asm_name;" invocation site instead (handled by
                    // the VARREF branch above, wherever it's actually
                    // used, possibly nowhere at all). Unnamed: this
                    // declaration point is the *only* place it can ever
                    // be emitted -- "spat out there and then."
                    if (t.sub.isEmpty()) {
                        emitAsm(t.resolvedAsmContent);
                    }
                    break;
                default:
                    throw new IllegalStateException("unexpected top-level token: " + t.text);
            }
        }
        return emitHoistedStringsPrefix() + out.toString();
    }

    /**
     * "STRING string_id1 \"Hello World!\"" -- one line per distinct
     * string literal encountered anywhere during the pass that just
     * finished (hoistedStrings is fully populated by now -- it's
     * populated purely as a side effect of that same single top-to-
     * bottom walk, via literalValueOf's STRING case), in first-
     * occurrence order, prepended ahead of everything else in the
     * final output -- including the GLOBAL lines that already come
     * first among everything *else*.
     */
    private String emitHoistedStringsPrefix() {
        StringBuilder prefix = new StringBuilder();
        for (Map.Entry<String, String> entry : hoistedStrings.entrySet()) {
            prefix.append("STRING ").append(entry.getValue()).append(" \"")
                    .append(escapeForBytecode(entry.getKey())).append("\"\n");
        }
        return prefix.toString();
    }

    /**
     * Builds gtDecoratedFuncMangledNames (the up-to-four gt_init/
     * gt_alive_check/gt_destruct/gt_register functions' own mangled
     * names) and gtReachableMangledNames (everything transitively
     * CALLed from within any of their bodies) via a plain BFS over
     * Token.resolvedCallTarget -- already fully resolved by
     * TypeChecker, nothing re-derived here. Also enforces the one
     * restriction that has to be checked here rather than at an
     * ordinary emission site: "call()" (INVOKE) is banned anywhere in
     * this whole reachable set, the same way it's already banned
     * inside a '@pure' function -- a function-pointer's target isn't
     * known at compile time, so there's no way to guarantee *it* also
     * only reaches dundered duplicates. Checked eagerly, over every
     * reachable body, rather than lazily at whatever point emission
     * happens to reach it.
     */
    private void computeGtReachableFunctions() {
        Deque<String> queue = new ArrayDeque<>();
        for (String decoratorName : Arrays.asList("gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved")) {
            TypeChecker.FuncInfo info = checker.getGhostTableFunction(decoratorName);
            if (info == null) {
                continue;
            }
            gtDecoratedFuncMangledNames.add(info.mangledName);
            collectCallTargetsAndBanInvoke(info.funcToken, queue);
        }
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!gtReachableMangledNames.add(name)) {
                continue; // already visited
            }
            TypeChecker.FuncInfo info = findFuncInfoByMangledName(name);
            if (info != null) {
                collectCallTargetsAndBanInvoke(info.funcToken, queue);
            }
            // info == null means this name resolved to an extern -- no
            // body to duplicate or recurse into, and EXTERN_CALL is a
            // wholly separate emission path that never consults
            // gtReachableMangledNames at all, so nothing further is
            // needed for it here.
        }
    }

    /** Every FuncInfo (plain function or impl method) whose own mangledName matches, searched across both namespaces -- the only way to go from a bare mangled-name string (all Token.resolvedCallTarget ever carries) back to the actual body to recurse into. */
    private TypeChecker.FuncInfo findFuncInfoByMangledName(String mangledName) {
        for (List<TypeChecker.FuncInfo> overloads : checker.getFunctions().values()) {
            for (TypeChecker.FuncInfo info : overloads) {
                if (info.mangledName.equals(mangledName)) {
                    return info;
                }
            }
        }
        for (List<TypeChecker.ImplInfo> impls : checker.getImpls().values()) {
            for (TypeChecker.ImplInfo impl : impls) {
                for (TypeChecker.FuncInfo info : impl.methods.values()) {
                    if (info.mangledName.equals(mangledName)) {
                        return info;
                    }
                }
                for (TypeChecker.FuncInfo info : impl.staticMethods.values()) {
                    if (info.mangledName.equals(mangledName)) {
                        return info;
                    }
                }
            }
        }
        for (TypeChecker.LibraryInfo lib : checker.getLibraries().values()) {
            for (List<TypeChecker.FuncInfo> overloads : lib.ownFunctions.values()) {
                for (TypeChecker.FuncInfo info : overloads) {
                    if (info.mangledName.equals(mangledName)) {
                        return info;
                    }
                }
            }
        }
        return null;
    }

    /** Generic recursive walk (left/right/childs/sub -- every field a Token tree can branch through) over one function body, collecting every Token.resolvedCallTarget it finds (a CALL/RECURSIVE_CALL/method-call/extern-call target -- resolvedCallTarget is never set on anything else) into `queue`, and throwing immediately on any "call()" (INVOKE) use found anywhere within it. */
    private void collectCallTargetsAndBanInvoke(Token node, Deque<String> queue) {
        if (node == null) {
            return;
        }
        if (node.resolvedCallTarget != null) {
            queue.add(node.resolvedCallTarget);
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left != null
                && node.left.type == TokenType.VARREF && node.left.text.equals("call")) {
            throw new CompilerException("type", node.file, node.line,
                    "'call' (a function-pointer invocation) cannot be used anywhere reachable from a "
                            + "ghost-table-decorated function -- the target isn't known at compile time, so "
                            + "there's no way to guarantee it only reaches dundered duplicates too");
        }
        collectCallTargetsAndBanInvoke(node.left, queue);
        collectCallTargetsAndBanInvoke(node.right, queue);
        if (node.childs != null) {
            for (Token c : node.childs) {
                collectCallTargetsAndBanInvoke(c, queue);
            }
        }
        if (node.sub != null) {
            for (Token c : node.sub) {
                collectCallTargetsAndBanInvoke(c, queue);
            }
        }
    }

    /** "ASM_START" / the raw content, verbatim, exactly as written / "ASM_END" -- shared by every emission site (root declaration, root invocation, function-body declaration, function-body invocation) since the shape is identical regardless of where it's spliced in. */
    private void emitAsm(String rawContent) {
        line("ASM_START");
        out.append(rawContent);
        if (rawContent.isEmpty() || rawContent.charAt(rawContent.length() - 1) != '\n') {
            out.append('\n');
        }
        line("ASM_END");
    }

    private TypeChecker.FuncInfo findImplMethodInfo(TypeChecker checker, Token implTok, Token methodTok) {
        String concreteName = implTok.sub.get(implTok.sub.size() - 1).text;
        for (TypeChecker.ImplInfo implInfo : checker.getImpls().get(concreteName)) {
            for (TypeChecker.FuncInfo info : implInfo.methods.values()) {
                if (info.funcToken == methodTok) {
                    return info;
                }
            }
            for (TypeChecker.FuncInfo info : implInfo.staticMethods.values()) {
                if (info.funcToken == methodTok) {
                    return info;
                }
            }
            // "impl with its own independent type params" -- a call-
            // site-instantiated residual-generic method (see
            // ImplInfo.genericMethodInstances' own doc comment) never
            // lives in methods/staticMethods above at all, only here.
            for (TypeChecker.FuncInfo info : implInfo.genericMethodInstances.values()) {
                if (info.funcToken == methodTok) {
                    return info;
                }
            }
        }
        throw new IllegalStateException("no FuncInfo for impl method '" + methodTok.sub.get(0).text + "'");
    }

    private TypeChecker.FuncInfo findLibraryFuncInfo(TypeChecker checker, Token libraryTok, Token funcTok) {
        String name = libraryTok.sub.get(0).text;
        for (List<TypeChecker.FuncInfo> overloads : checker.getLibraries().get(name).ownFunctions.values()) {
            for (TypeChecker.FuncInfo info : overloads) {
                if (info.funcToken == funcTok) {
                    return info;
                }
            }
        }
        throw new IllegalStateException("no FuncInfo for library function '" + funcTok.sub.get(0).text + "'");
    }

    private TypeChecker.FuncInfo findFuncInfo(TypeChecker checker, Token funcTok) {
        String name = funcTok.sub.get(0).text;
        for (TypeChecker.FuncInfo info : checker.getFunctions().get(name)) {
            if (info.funcToken == funcTok) {
                return info;
            }
        }
        throw new IllegalStateException("no FuncInfo for '" + name + "'");
    }

    /**
     * A name may be declared again in a sibling scope with another type (the slot is shared and sized for the widest use, see
     * tests/same_name_locals_test), EXCEPT when one of the types needs a generated drop (owned memory inside it): the drop-glue pass resolves `GT_DESTRUCT name`
     * through one name -> type map, so with two different types behind one name the destruct of the other one ran the wrong drop
     * (a catch parameter `e` plus a local `e` that owns a DynamicArray leaked its elements; an owned Node and an owned Holder
     * under one name dropped through the wrong layout). Rename one of them.
     */
    private void requireOneOwnsTypePerSlotName(TypeChecker.FuncInfo info, String allocLines) {
        Map<String, String> seen = new HashMap<>();
        for (String l : allocLines.split("\n")) {
            if (!l.startsWith("ALLOC ")) {
                continue;
            }
            String[] parts = l.split(" ", 3);
            if (parts.length < 3) {
                continue;
            }
            String before = seen.putIfAbsent(parts[1], parts[2]);
            if (before != null && !before.equals(parts[2]) && (dropNeedsGlue(before) || dropNeedsGlue(parts[2]))) {
                throw new CompilerException("type", info.funcToken.file, info.funcToken.line,
                        "the name '" + parts[1] + "' is declared with two different types in this function ('" + before + "' and '"
                                + parts[2] + "') and one of them needs more than a plain free when it is dropped -- a name shared by sibling scopes (a catch parameter "
                                + "counts) must have one owning type, so rename one of them");
            }
        }
    }

    /** True when destructing a local of this canonical type needs generated drop glue: an owned dynarray whose elements own memory, or an (owned or inline) struct with an owning member. */
    private boolean dropNeedsGlue(String type) {
        String t = type;
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String pre : new String[] {"owns_", "some_", "mut_", "imut_", "indeterminate_"}) {
                if (t.startsWith(pre)) {
                    t = t.substring(pre.length());
                    stripped = true;
                }
            }
        }
        if (t.startsWith("dynarray(") && t.endsWith(")")) {
            return checker.elementTextOwnsMemory(t.substring("dynarray(".length(), t.length() - 1));
        }
        if (t.startsWith("unsafe_dynarray(")) {
            return false;
        }
        return checker.elementTextOwnsMemory(t) && !t.startsWith("dynarray(");
    }

    private void line(String s) {
        out.append(s).append('\n');
    }

    // ---- top-level declarations ------------------------------------------

    private void emitStruct(TypeChecker.StructInfo info) {
        line("STRUCT_START " + info.name);
        emitDecorators("STRUCT_DECORATE", info.declTok.decorators);
        // "the Class ID of the struct is pushed first as a secret
        // argument on every OTC... it is to be in there definitions,"
        // confirmed directly -- a real hidden field, present in the
        // struct's own bytecode definition (not merely a compile-time-
        // only concept), never user-declared and never appearing in
        // `info.members` (so it's exempt from struct-literal field-
        // exhaustiveness and ordinary "." member access) -- see
        // emitInstantiate for where its value is actually populated, at
        // every construction site. "@untyped are excempt" -- classId is
        // null for those, so they get no hidden field, and no layout
        // slot, at all. Emitted *before* the user-declared members,
        // matching emitInstantiate's/emitStaticAlloc's own "pushed
        // first" ordering -- computeStructLayout (below) is what
        // actually walks this order to assign every member's own byte
        // offset, so this ordering is load-bearing, not cosmetic: laying
        // classId last would put it at the *end* of the struct's own
        // memory layout instead of offset 0.
        for (StructLayoutEntry entry : computeStructLayout(info).entries) {
            line(entry.paddingBytes > 0
                    ? "STRUCT_PADDING " + entry.paddingBytes
                    : "STRUCT_MEMBER " + entry.memberName + " " + entry.canonicalType);
        }
        line("STRUCT_END");
    }

    // ---- struct layout / alignment (padding round) ------------------------
    //
    // "All structs get padded by the compiler (not the optimizer) --
    // appears in higher order bytecode... with the padding interdispersed
    // as needed," confirmed directly: real, natural/C-like alignment is
    // baked directly into a struct's own STRUCT_START/STRUCT_MEMBER/
    // STRUCT_END declaration here, rather than left for a later stage to
    // recompute from a flat, unpadded member sum the way
    // AddressLoweringPass/SizeCalculator always used to (see that class's
    // own long-standing "structs when not pointers are their actual size
    // -- ignores alignment/padding entirely, flag that as worthy of
    // review later" comment -- this is that review). "@unpadded gets a
    // not supported error for now" -- registerStructLike already rejects
    // it outright (see that method), so every struct that ever reaches
    // this section is always padded; there is no unpadded branch to keep
    // here.
    //
    // Rule: each field is aligned to its own natural alignment (equal to
    // its own size for every leaf shape -- nothing in this language is
    // ever over-aligned relative to its own width); a struct's own
    // alignment is the max of its members' own (computed recursively --
    // a struct made entirely of u8 fields is 1-aligned, one containing a
    // u64 anywhere is 8-aligned); padding is inserted immediately before
    // any field whose running offset isn't already a multiple of its own
    // alignment; and the struct's own total size is rounded up to its
    // own overall alignment at the end (ordinary C-style trailing struct
    // padding, so an array of these structs keeps every element
    // correctly aligned too).
    //
    // Walk `info.members` once, in order, with the hidden classId field
    // prepended (structs have no inheritance, so there is no parent part).
    //
    // Deliberately duplicates its own tiny size/alignment table rather
    // than reusing TypeChecker's own private PRIMITIVE_SIZE (used only
    // by the unrelated 'sizeof' builtin, which has its own, deliberately
    // different rules -- e.g. 'sizeof' on an array type returns the
    // element size, not the real total layout size, and never accounts
    // for alignment either -- a separate, still-flagged, still-untouched
    // concern) -- matching this project's established precedent of every
    // sizing table living independently in whichever stage actually
    // needs it (the optimizer's own SizeCalculator already keeps a fully
    // independent copy of this same table rather than reaching into the
    // compiler project at all).

    private static final Map<String, Long> LAYOUT_PRIMITIVE_SIZE = new HashMap<>();
    static {
        LAYOUT_PRIMITIVE_SIZE.put("u8", 1L); LAYOUT_PRIMITIVE_SIZE.put("s8", 1L);
        LAYOUT_PRIMITIVE_SIZE.put("u16", 2L); LAYOUT_PRIMITIVE_SIZE.put("s16", 2L);
        LAYOUT_PRIMITIVE_SIZE.put("u32", 4L); LAYOUT_PRIMITIVE_SIZE.put("s32", 4L);
        LAYOUT_PRIMITIVE_SIZE.put("u64", 8L); LAYOUT_PRIMITIVE_SIZE.put("s64", 8L);
        LAYOUT_PRIMITIVE_SIZE.put("f32", 4L);
        LAYOUT_PRIMITIVE_SIZE.put("f64", 8L);
        LAYOUT_PRIMITIVE_SIZE.put("bool", 1L);
        LAYOUT_PRIMITIVE_SIZE.put("char", 1L);
        LAYOUT_PRIMITIVE_SIZE.put("code_addr", 8L);
        LAYOUT_PRIMITIVE_SIZE.put("string", 8L);
        LAYOUT_PRIMITIVE_SIZE.put("void", 0L);
    }
    private static final long LAYOUT_POINTER_SIZE = 8L;
    private static final Set<String> LAYOUT_STORAGE_KEYWORDS =
            new HashSet<>(Arrays.asList("owns", "ref", "raw", "auto", "static"));

    /** One real STRUCT_MEMBER line (memberName/canonicalType set, paddingBytes 0) or one pure STRUCT_PADDING gap (memberName/canonicalType null, paddingBytes > 0) -- computeStructLayout's own output, in final declared-plus-padding order. */
    private static final class StructLayoutEntry {
        final String memberName;
        final String canonicalType;
        final long paddingBytes;

        private StructLayoutEntry(String memberName, String canonicalType, long paddingBytes) {
            this.memberName = memberName;
            this.canonicalType = canonicalType;
            this.paddingBytes = paddingBytes;
        }

        static StructLayoutEntry member(String name, String canonicalType) {
            return new StructLayoutEntry(name, canonicalType, 0);
        }

        static StructLayoutEntry padding(long bytes) {
            return new StructLayoutEntry(null, null, bytes);
        }
    }

    private static final class StructLayout {
        final List<StructLayoutEntry> entries;
        final long size;
        final long align;

        StructLayout(List<StructLayoutEntry> entries, long size, long align) {
            this.entries = entries;
            this.size = size;
            this.align = align;
        }
    }

    private final Map<String, StructLayout> structLayoutCache = new HashMap<>();

    /** `info`'s own real STRUCT_MEMBER/STRUCT_PADDING sequence plus its own total (size, alignment) in bytes -- memoized per struct name; struct types form a DAG (self-referential struct shapes are illegal), so plain recursion is safe and always terminates. */
    private StructLayout computeStructLayout(TypeChecker.StructInfo info) {
        StructLayout cached = structLayoutCache.get(info.name);
        if (cached != null) {
            return cached;
        }
        List<StructLayoutEntry> entries = new ArrayList<>();
        long offset = 0;
        long maxAlign = 1;
        if (info.classId != null) {
            entries.add(StructLayoutEntry.member("___type", "imut_u64"));
            offset += 8;
            maxAlign = 8;
        }
        for (Map.Entry<String, TypeChecker.TypeInfo> e : info.members.entrySet()) {
            String canonical = e.getValue().canonical();
            long[] sizeAlign = layoutSizeAndAlignOf(canonical);
            long align = sizeAlign[1] <= 0 ? 1 : sizeAlign[1];
            long misalign = offset % align;
            if (misalign != 0) {
                long pad = align - misalign;
                entries.add(StructLayoutEntry.padding(pad));
                offset += pad;
            }
            entries.add(StructLayoutEntry.member(e.getKey(), canonical));
            offset += sizeAlign[0];
            if (align > maxAlign) {
                maxAlign = align;
            }
        }
        long misalignEnd = offset % maxAlign;
        if (misalignEnd != 0) {
            long pad = maxAlign - misalignEnd;
            entries.add(StructLayoutEntry.padding(pad));
            offset += pad;
        }
        StructLayout layout = new StructLayout(entries, offset, maxAlign);
        structLayoutCache.put(info.name, layout);
        return layout;
    }

    /** [size, align] in bytes for one member's own full canonical type text ("storage?_some?_mutability_atomic?_baseType", TypeInfo.canonical()'s own format) -- a storage-bearing type is always one 8-byte, 8-aligned pointer word regardless of pointee (the identical "just a pointer" reasoning emitArgTransfer's own single-word argument case already uses), otherwise this strips down to the bare baseType and defers to layoutSizeAndAlignOfBaseType. */
    private long[] layoutSizeAndAlignOf(String canonical) {
        String rest = canonical;
        int firstUnderscore = rest.indexOf('_');
        if (firstUnderscore > 0 && LAYOUT_STORAGE_KEYWORDS.contains(rest.substring(0, firstUnderscore))) {
            return new long[]{LAYOUT_POINTER_SIZE, LAYOUT_POINTER_SIZE};
        }
        if (firstUnderscore > 0 && rest.substring(0, firstUnderscore).equals("some")) {
            rest = rest.substring(firstUnderscore + 1);
        }
        int secondUnderscore = rest.indexOf('_');
        String baseType = secondUnderscore > 0 ? rest.substring(secondUnderscore + 1) : rest;
        if (baseType.startsWith("atomic_")) {
            baseType = baseType.substring("atomic_".length());
        }
        return layoutSizeAndAlignOfBaseType(baseType);
    }

    /** [size, align] in bytes for a bare baseType (no storage/mutability prefix) -- "a range should be 16 bytes, 8-aligned" (two plain u64 bounds); a fixed array's own alignment is its element's own alignment, its size the element's size times its length; a dynarray is always one 8-byte, 8-aligned handle; a nested struct recurses into its own already-computed layout; anything else unknown here (an enum name -- every enum in this language fits in one u64 word, SizeCalculator's own identical fallback, confirmed against real compiled output for an enum-typed struct field) falls back to a conservative one-word, 8-aligned default rather than guessing. */
    private long[] layoutSizeAndAlignOfBaseType(String baseType) {
        if (baseType.equals("range") || baseType.startsWith("range(")) {
            return new long[]{16, 8};
        }
        if (!baseType.isEmpty() && baseType.charAt(baseType.length() - 1) == ']') {
            int open = baseType.lastIndexOf('[');
            if (open >= 0) {
                String digits = baseType.substring(open + 1, baseType.length() - 1);
                boolean allDigits = !digits.isEmpty();
                for (int i = 0; allDigits && i < digits.length(); i++) {
                    allDigits = Character.isDigit(digits.charAt(i));
                }
                String elem = baseType.substring(0, open);
                long[] elemSizeAlign = layoutSizeAndAlignOfBaseType(elem);
                long length = allDigits ? Long.parseLong(digits) : -1;
                long size = length < 0 ? LAYOUT_POINTER_SIZE : length * elemSizeAlign[0];
                return new long[]{size, elemSizeAlign[1]};
            }
        }
        if (baseType.startsWith("dynarray(") || baseType.startsWith("unsafe_dynarray(")) {
            return new long[]{LAYOUT_POINTER_SIZE, LAYOUT_POINTER_SIZE};
        }
        TypeChecker.StructInfo nested = checker.getStructs().get(baseType);
        if (nested != null) {
            StructLayout nestedLayout = computeStructLayout(nested);
            return new long[]{nestedLayout.size, nestedLayout.align};
        }
        Long prim = LAYOUT_PRIMITIVE_SIZE.get(baseType);
        if (prim != null) {
            return new long[]{prim, prim};
        }
        return new long[]{LAYOUT_POINTER_SIZE, LAYOUT_POINTER_SIZE};
    }

    private void emitEnum(TypeChecker.EnumInfo info) {
        // "currently, ENUM_DEF BLACK WHITE... but if the variants were
        // guaranteed, you'd have... ENUM_DEF BLACK 0 WHITE 1,"
        // confirmed directly -- "only change the format if the values
        // are explicit," confirmed directly, so the bare, name-only
        // format is untouched for every enum that never used
        // ":"/"=" at all.
        // A range-valued enum ("we will store unique ranges (pairs of
        // u64s) as variants," confirmed directly) gets its own third
        // format, "NAME start..end" per variant -- distinct from the
        // plain u64-valued "NAME value" shape above it (never emitted
        // together; collectEnum already guarantees an enum is exactly
        // one of plain/u64-valued/range-valued).
        StringBuilder sb = new StringBuilder("ENUM ").append(info.name);
        for (int i = 0; i < info.variants.size(); i++) {
            sb.append(' ').append(info.variants.get(i));
            if (info.explicitValues != null) {
                sb.append(' ').append(info.explicitValues.get(i));
            } else if (info.isRangeValued()) {
                sb.append(' ').append(info.explicitRangeStarts.get(i))
                        .append("..").append(info.explicitRangeEnds.get(i));
            }
        }
        line(sb.toString());
    }

    /**
     * Emits an ordinary function/method exactly once under its real
     * name -- with gt-suppression active for its own body only if it's
     * itself one of the (up to four) gt-decorated functions -- and,
     * separately, a second time as a "__"-prefixed dundered duplicate
     * (gt-suppression always active, `EXPORT` never emitted -- an
     * internal duplicate has no business being exported for C
     * linkage) if this function is anywhere in gtReachableMangledNames.
     * The two emissions are otherwise identical: same already-checked
     * tree, walked twice, never re-type-checked.
     */
    /**
     * "func main(int,raw mut raw mut char){ let args =
     * make_safe_args(i,args_ptrs); return dundered_main(args); },"
     * confirmed directly -- synthesized directly as bytecode (no
     * synthetic Token/FuncInfo tree routed through the ordinary
     * checking/emission pipeline at all), since nothing about this
     * wrapper was ever written as real source for TypeChecker to have
     * validated in the first place. `userMain` here is the *original*
     * FuncInfo (already emitted under "__caspien_main" by the caller, just
     * before this runs) -- used only to read its own return type and
     * calling convention, never re-emitted itself.
     *
     * Gets its own full `gt_routine` treatment via
     * `emitGtRoutineAlloc`/`emitGtRoutineBody` (a minimal, synthetic
     * `FuncInfo` whose own `ownsLocalTypes` holds exactly the one local
     * this wrapper ever declares, "args") -- this genuinely is "the true main" now
     * (there's no '@event_loop' in this branch at all -- see emitFunc's
     * own dispatch), so it needs the identical `EXIT`-ending, reserved-
     * slot treatment every other real entry point already gets, not a
     * bespoke, simpler one.
     */
    private void emitSafeArgsMainWrapper(TypeChecker.FuncInfo userMain) {
        TypeChecker.FuncInfo makeSafeArgs = checker.getFunctions().get("make_safe_args").get(0);
        TypeChecker.TypeInfo argcType = new TypeChecker.TypeInfo(null, "imut", "s32");
        TypeChecker.TypeInfo argvType = new TypeChecker.TypeInfo("raw", "mut",
                new TypeChecker.TypeInfo("raw", "mut", "char").canonical());
        TypeChecker.TypeInfo argsType = checker.safeArgsType();

        line("FUNC_START main");
        line("RETURNS " + userMain.returnType.canonical());

        TypeChecker.FuncInfo wrapperInfo = new TypeChecker.FuncInfo();
        wrapperInfo.ownsLocalTypes.put("args", argsType);
        currentFuncMangledName = "main";
        currentFuncInfo = wrapperInfo;

        // Same "RETURNS, then ARG*, then ALLOCs" ordering
        // `emitFuncUnderName` now uses for every ordinary function --
        // this wrapper's body is hand-synthesized bytecode, not a real
        // `emitBlock` call, but it declares exactly one local ("args")
        // and gets the identical treatment: "argc"/"argv" (this real,
        // OS-facing "main"'s own two parameters) as bare `ARG` lines
        // right after `RETURNS` -- genuinely just signature facts here,
        // never a real frame slot or load sequence; see "ARG-to-ALLOC
        // lowering" in the sibling `caspien-optimizer` project's own
        // CLAUDE.md for where and how those get turned into a real
        // `ALLOC`+load sequence -- then `gt_routine_address`, then
        // "args" (this wrapper's own one real, hand-emitted local), then
        // the gt_routine body, only then GT_INIT and the rest of this
        // wrapper's own hand-written sequence. This is the real C-ABI
        // entry point the OS/C runtime itself calls; it carries no
        // `@call_convention` decorator of its own (no source syntax to
        // write one on a synthesized function), so `ArgToAllocLoweringPass`
        // resolves its convention the same way it resolves any other
        // undecorated function's: this project's own `compiler.config`
        // `default:`.
        List<String> osArgNames = Arrays.asList("argc", "argv");
        List<String> osArgTypes = Arrays.asList(argcType.canonical(), argvType.canonical());
        emitParamDecls(osArgNames, osArgTypes);
        emitGtRoutineAlloc(wrapperInfo, "main", false);
        line("ALLOC args " + argsType.canonical());
        emitGtRoutineBody(wrapperInfo, "main", false);

        // "after my allocation and my static allocations, but before
        // any of my expressions" -- it's exactly as much "the true
        // main" as an ordinary one (see this method's own doc comment)
        // -- so it gets the identical GT_INIT placement, right after
        // its one hoisted ALLOC and before the call-sequence that
        // constitutes its own single "expression"
        // (`let args = make_safe_args(...)`).
        if (checker.usesOwnsRefDynNew()) {
            requireGhostTableFunctionPresent("gt_init", userMain.funcToken);
            requireGhostTableFunctionPresent("gt_moved", userMain.funcToken);
            line("GT_INIT");
        }
        List<PendingUnwindLandingPad> previousPendingLandingPads = pendingCallSiteUnwindLandingPads;
        pendingCallSiteUnwindLandingPads = new ArrayList<>();
        line("ADDR args " + argsType.canonical());
        Token argcTok = syntheticVarref("argc", argcType.canonical());
        Token argvTok = syntheticVarref("argv", argvType.canonical());
        // Staged with an empty destruct list -- "args" doesn't exist yet
        // at this point (this call's own return value is what creates
        // it) -- see `stageCallSiteForUnwindNames`'s own doc comment.
        stageCallSiteForUnwindNames(Collections.emptyList(), Collections.emptyList(), userMain.funcToken);
        emitCallSequence(null, Arrays.asList(argcTok, argvTok), () -> line("CALL make_safe_args"),
                argsType.canonical(), makeSafeArgs.callConvention);
        line("ASSIGN " + argsType.canonical() + " " + argsType.canonical() + " " + argsType.canonical());

        Token argsTok = syntheticVarref("args", argsType.canonical());
        argsTok.isOwnershipMoveSource = true;
        boolean isVoid = userMain.returnType.canonical().equals("imut_void");
        // Also staged with an empty destruct list -- "args" ownership is
        // moving into this exact call (`argsTok.isOwnershipMoveSource`),
        // so there is nothing left of this wrapper's own to destruct if
        // it unwinds back out of "__caspien_main".
        stageCallSiteForUnwindNames(Collections.emptyList(), Collections.emptyList(), userMain.funcToken);
        emitCallSequence(null, Arrays.asList(argsTok), () -> line("CALL __caspien_main"),
                isVoid ? null : userMain.returnType.canonical(), userMain.callConvention);
        if (isVoid) {
            line("RET imut_void");
        } else {
            line("RET " + userMain.returnType.canonical());
        }
        // This wrapper is always emitted under "main" (the true root),
        // never `gtSuppressed` -- the identical landing-pad placement
        // `emitFuncUnderName` uses for every ordinary function.
        emitCallSiteUnwindLandingPads(wrapperInfo, "main", false);
        line("FUNC_END");
        currentFuncMangledName = null;
        currentFuncInfo = null;
        pendingCallSiteUnwindLandingPads = previousPendingLandingPads;
    }

    /** A minimal VARREF token, just enough for `emitExpr`'s own VARREF case ("PUSH name type") and `emitAssignTarget`'s own bare-name case -- used only to feed a synthesized name into the same, real emission machinery every ordinary call/assignment already goes through, never type-checked (there's no real source for it to have come from). */
    private Token syntheticVarref(String name, String resolvedType) {
        Token t = new Token(TokenType.VARREF, name, 0, "<synthesized>");
        t.resolvedType = resolvedType;
        return t;
    }

    private void emitFunc(TypeChecker.FuncInfo info) {
        // "In the final emitted bytecode, dunder the main and have the
        // @event_loop function be the actual main," confirmed directly.
        // 'main' itself is renamed to "__caspien_main" (this compiler's own
        // existing "__"-prefixed dundered-duplicate convention, already
        // used for the unrelated GT_DESTRUCT-suppression case just
        // below); the '@event_loop' function is emitted *as* "main" in
        // its place, so the bytecode's own real entry point becomes
        // whatever a real assembly backend would recognize "main" to
        // be, regardless of what either function was actually named in
        // source.
        TypeChecker.FuncInfo eventLoop = checker.getEventLoopFunc();
        if (eventLoop != null) {
            if (info == eventLoop) {
                emitFuncUnderName(info, "main", false, false);
                return;
            }
            if (info.name.equals("main")) {
                emitFuncUnderName(info, "__caspien_main", false, false);
                return;
            }
        } else if (info.name.equals("main") && "safe_args".equals(info.mainArgShape)) {
            // "func main(int,raw mut raw mut char){ let args =
            // make_safe_args(i,args_ptrs); return dundered_main(args);
            // }," confirmed directly -- no '@event_loop' exists to
            // already be "the real entry point," so the compiler
            // itself synthesizes that same shape directly as bytecode
            // (`emitSafeArgsMainWrapper`, just below): the real,
            // C-ABI-shaped "main" the OS/C runtime actually calls,
            // which builds the safe, wrapped value and hands it to the
            // user's own (dundered) 'main'.
            emitFuncUnderName(info, "__caspien_main", false, false);
            emitSafeArgsMainWrapper(info);
            return;
        }
        boolean isGtDecorated = gtDecoratedFuncMangledNames.contains(info.mangledName);
        emitFuncUnderName(info, info.mangledName, isGtDecorated, info.isExported);
        if (gtReachableMangledNames.contains(info.mangledName)) {
            emitFuncUnderName(info, "__" + info.mangledName, true, false);
        }
    }

    /**
     * "I need a garbage collection routine at the top of all my
     * functions," confirmed directly. Emitted once, right after this
     * function's own "ARG" lines, before its ordinary body -- a
     * reserved slot ("gt_routine_address", always the very first thing
     * this function's own frame holds, before any other local),
     * pointing at a label this function's own body never reaches
     * except by explicitly jumping there (a `JMP` right over it, the
     * same "no JMPing... at runtime" shape `assume match` already
     * established for a different reason), containing exactly one
     * `GT_DESTRUCT` per `owns` local this function ever declares
     * (`FuncInfo.ownsLocalTypes`, already accumulated across every
     * nested scope within it, not just its own root one), followed by
     * however this particular function's own unwind boundary is
     * resolved (see the three-way choice below).
     *
     * "The only functions that doesnt have GT_UNWIND is the true main,
     * being main or the @event_loop as the case may be, and @async
     * functions," confirmed directly -- `emittedName` (not
     * `info.name`) is what's checked against "main" specifically,
     * since dundering has already resolved, by the time this runs,
     * which of `main`/`@event_loop` (if either) is truly emitted under
     * that name.
     *
     * Entirely skipped for a gt-suppressed function (one of the (up to
     * four) real, user-written ghost-table functions themselves, or
     * either's own dundered duplicate) -- the exact same reasoning
     * `emitDestructList` already applies to an *ordinary* `GT_DESTRUCT`
     * inside one of these (calling `gt_destruct` from within
     * `gt_destruct` itself would recurse forever), extended here to
     * the whole mechanism: no reserved slot, no label, no `GT_UNWIND`.
     *
     * NOTE FOR THE FUTURE BYTECODE-TO-ASSEMBLY GENERATOR: this is the
     * one place in the whole bytecode format a raw code address (not a
     * variable's value, not a literal) is ever pushed as an operand --
     * `PUSH_LABEL`, deliberately a different mnemonic from plain
     * `PUSH`, precisely so this is never confused with reading a
     * named slot's own current value. `gt_routine_address` itself is
     * typed `code_addr`, a pseudo-type with no equivalent anywhere
     * else in this language's own type system -- pointer-sized, never
     * user-visible, never subject to ordinary type-checking (there is
     * none here to subject it to).
     *
     * "We static alloc, then we Alloc, including the special variable
     * for the unwinding being the first thing, then we have the rest
     * of the functionality. We do not have our routines mixed in with
     * our allocs," confirmed directly -- `GT_UNWIND`'s own correctness
     * depends on every function's own "ALLOC gt_routine_address" being
     * at the exact same, fixed offset from the base pointer in
     * literally every frame, and every local's own `ALLOC` needs its
     * frame slot reserved before anything else in the function runs.
     * This method is therefore split into two halves that
     * `emitFuncUnderName` calls at two different points, never
     * adjacent to each other: `emitGtRoutineAlloc` (just the reserved
     * slot's own `ALLOC`, emitted first among all of this function's
     * ordinary allocations -- ahead of every local `emitFuncUnderName`
     * has already collected from anywhere in the function body, no
     * matter how deeply nested) and `emitGtRoutineBody` (the label,
     * jump-around, `GT_DESTRUCT` list, and `EXIT`/`GT_UNWIND`/
     * `EXIT_THREAD` ending), which only runs once every single
     * `ALLOC`/`ALLOC_STATIC` this function will ever need has already
     * been emitted -- so the bytecode text itself is now the actual
     * source of truth for "every local's slot exists before any other
     * functionality runs," not something a later assembly stage has to
     * separately know to honor.
     */
    private void emitGtRoutineAlloc(TypeChecker.FuncInfo info, String emittedName, boolean gtSuppressed) {
        if (gtSuppressed) {
            return;
        }
        if (!checker.usesOwnsRefDynNew() && !checker.usesThrow()) {
            return;
        }
        line("ALLOC gt_routine_address code_addr");
        // A second reserved slot, right alongside the first -- "another
        // secret variable, like the one used for gt unwinding routine's
        // address, this one is used for the error message... a secret
        // address variable, and a secret message variable," confirmed
        // directly. Unlike `gt_routine_address` (needed the moment
        // *anything* `owns`/`ref`/`dyn`/`new`-shaped could ever unwind
        // through this frame), this one is meaningless without `throw`
        // specifically -- gated on `checker.usesThrow()` alone, narrower
        // than the `||` just above. Typed `static_imut_string`, the
        // exact same type a thrown message already resolves to ("a
        // pointer just like any other pointer to a static imut string,"
        // confirmed directly) -- an entirely ordinary type, unlike
        // `gt_routine_address`'s own pseudo-type `code_addr`, so nothing
        // about reading or writing it needs special-casing anywhere
        // `static imut string` already flows. See `emitThrow` (the
        // write) and `emitHoistedCatchBlocks` (the read, snapshotted
        // once into each catch's own bound parameter) for the two ends
        // of this slot's own lifetime. `GT_UNWIND MSG`'s real x86-64 codegen
        // (X86Backend.java, the Codegen project -- a genuinely separate,
        // already-working backend, not merely a documented contract)
        // implements the actual one-frame-further propagation itself:
        // it reads this slot out of the unwinding (child) frame before
        // its epilogue retargets `rbp`, then writes that value into the
        // caller's own identically-offset slot right after, exactly
        // mirroring how it already propagates `gt_routine_address` by
        // reading it out of the (now-caller's) frame post-epilogue. That
        // copy only ever runs when this slot is present somewhere in the
        // compilation unit at all (see X86Backend's own
        // `programHasErrorMessage`), since -- unlike `gt_routine_address`
        // -- it isn't ALLOC'd in every `gt_routine`-bearing frame, only
        // ones reachable when `checker.usesThrow()` is true program-wide.
        if (checker.usesThrow()) {
            line("ALLOC gt_error_message static_imut_string");
        }
    }

    /**
     * "GT_UNWIND" or, in a program that uses throw (every frame then has the fixed `gt_error_message` slot at rbp-16, right behind
     * `gt_routine_address` at rbp-8), "GT_UNWIND MSG": the backend copies this frame's message slot into the caller's before leaving, so
     * a `catch` in any frame up the chain sees the message that was thrown, not its own never-written slot.
     */
    private String gtUnwindLine() {
        return checker.usesThrow() ? "GT_UNWIND MSG" : "GT_UNWIND";
    }

    /** The rest of the `gt_routine` prologue -- see `emitGtRoutineAlloc`'s doc comment for why this is split out and why it must run only after every ALLOC/ALLOC_STATIC in the function has already been emitted. */
    private void emitGtRoutineBody(TypeChecker.FuncInfo info, String emittedName, boolean gtSuppressed) {
        if (gtSuppressed) {
            return;
        }
        // "the simple/complicated set distinction wasn't for the
        // codebase itself, it was me trying to help you compartmentalize
        // the build out, for efficiency... I see there's an issue that
        // GT_UNWIND is unconditionally in every non-main. It should
        // actually only be there if the final compilation unit includes
        // that GT_INIT at the main," confirmed directly -- the whole
        // `gt_routine` prologue (the reserved slot, the label, and
        // whichever of GT_UNWIND/EXIT/EXIT_THREAD it ends in) is real
        // scaffolding this program only actually needs when something
        // could reach it: either the program uses `owns`/`ref`/`dyn`/
        // `new` anywhere at all (the exact same whole-program trigger
        // `GT_INIT` itself already uses -- `checker.usesOwnsRefDynNew()`),
        // or the program uses `throw` anywhere at all (whose own bytecode
        // always names its enclosing function's `gt_routine` label as a
        // real operand -- `checker.usesThrow()`, see its own doc comment
        // for why this second trigger is needed alongside the first). A
        // program using neither gets no `gt_routine` scaffolding
        // anywhere, in any function, not even `main` -- there is nothing
        // left that could ever reference it. (Kept as the identical
        // gate `emitGtRoutineAlloc` already checked, so the two halves
        // agree on whether this function gets any gt_routine machinery
        // at all.)
        if (!checker.usesOwnsRefDynNew() && !checker.usesThrow()) {
            return;
        }
        // UPDATE (per-throw-site/per-call-site unwinding): the whole-
        // function `gt_routine` label below -- one shared destruct list
        // for "every owns local this function ever declares," reached
        // only via a `THROW`'s own direct `jmp` from within this same
        // function -- is now entirely superseded whenever the program
        // uses `throw` anywhere. See `TypeChecker.checkThrow`'s own doc
        // comment, `emitThrow`, `stageCallSiteForUnwind`, and
        // `emitCallSiteUnwindLandingPads` for the real, precise
        // replacement: a `throw` now destructs exactly the `owns` locals
        // alive at that lexical point (inline, no label needed -- its
        // own destination is resolved at compile time, so there's
        // nothing to jump *to*), and an unwind bubbling up from a deeper
        // call lands at a landing pad specific to the exact call site
        // that made it, staged into `gt_routine_address` immediately
        // before that call (`stageCallSiteForUnwind`) rather than at
        // this whole-function label. Nothing here is reachable any more
        // once `usesThrow()` is true -- not even by a bug, since nothing
        // else in the emitted bytecode ever names `gt_routine__<func>`
        // any more either -- so there is genuinely nothing left to emit
        // for this function's *old-style* prologue at all; only the
        // reserved slot's own `ALLOC` (`emitGtRoutineAlloc`, already
        // emitted) and this function's own call-site landing pads
        // (`emitCallSiteUnwindLandingPads`, emitted separately, once
        // every call site in this function's body is known) remain.
        // When `usesThrow()` is false but `usesOwnsRefDynNew()` is true,
        // the old design below is kept completely unchanged -- there is
        // no `throw` anywhere in the whole program for anything to ever
        // unwind through, so the simpler, whole-function-list shape is
        // still exactly as correct (and exactly as untouched) as it
        // always was.
        if (checker.usesThrow()) {
            return;
        }
        String gtRoutineLabel = "gt_routine__" + emittedName;
        String endLabel = "end_of_" + gtRoutineLabel;
        line("ADDR gt_routine_address code_addr");
        line("PUSH_LABEL " + gtRoutineLabel + " code_addr");
        line("ASSIGN code_addr code_addr code_addr");
        line("JMP " + endLabel);
        line(gtRoutineLabel + ":");
        for (String name : info.ownsLocalTypes.keySet()) {
            line("GT_DESTRUCT " + name);
        }
        if (emittedName.equals("main")) {
            line("EXIT");
        } else if (info.isAsync) {
            line("EXIT_THREAD");
        } else {
            line(gtUnwindLine());
        }
        line(endLabel + ":");
    }

    /**
     * Stages the reserved `gt_routine_address` slot, immediately before
     * an ordinary `CALL`/`RECURSIVE_CALL`, to point at a landing pad
     * scoped to exactly *this* call site -- the caller's own half of the
     * "set the special pointer at the call site" design (see the
     * conversation this was designed in, and `TypeChecker.checkThrow`'s
     * own doc comment for the throw-site half). If the callee (or
     * anything it calls, transitively, at any depth) ever throws and
     * unwinds all the way back out to here, `GT_UNWIND` will tear down
     * that deeper frame and read *this* frame's own slot -- landing
     * precisely at the label this call staged, which destructs exactly
     * what's alive in `currentFuncMangledName` at this exact point
     * (`op.destructOnExit`, computed by `TypeChecker` the identical way
     * a `return` right here would have been) before continuing the
     * unwind one frame further up.
     *
     * Nothing about the propagation mechanism itself is new -- still one
     * fixed slot, still one `GT_UNWIND` reading "whatever's now the
     * current frame." What's new is that a function re-stages its own
     * slot before every single call it makes, rather than writing it
     * once in its own prologue -- see `emitGtRoutineBody`'s own doc
     * comment for why the old, single, whole-function value is gone
     * entirely once the program uses `throw` anywhere.
     *
     * A complete no-op whenever `checker.usesThrow()` is false (nothing
     * in the whole program could ever throw, so there's nothing to
     * stage) or inside gt-suppressed context (a ghost-table function's
     * own body, or its dundered duplicate -- `checkThrow` already
     * forbids `throw` inside one of the four real ghost-table functions
     * outright, and a call made *from* one is never itself an unwind
     * boundary worth staging for, the same recursion-avoidance reasoning
     * `emitDestructList`/`emitGtRoutineBody` already apply here).
     */
    private void stageCallSiteForUnwind(Token op) {
        List<String> destructNames = op.destructOnExit != null ? op.destructOnExit : Collections.emptyList();
        List<Token> unlocks = op.unlockOnExit != null ? op.unlockOnExit : Collections.emptyList();
        stageCallSiteForUnwindNames(destructNames, unlocks, op);
    }

    /**
     * The real staging work `stageCallSiteForUnwind` does, factored out
     * so `emitSafeArgsMainWrapper`'s own two hand-synthesized calls
     * (`make_safe_args`, `__main` -- real bytecode built directly, never
     * routed through `TypeChecker.checkCall`, so there's no real
     * `Token.destructOnExit` for them to read) can stage themselves too,
     * with an explicit, hand-reasoned destruct list instead: empty for
     * both of this wrapper's own calls, since its only owns local
     * ("args") is either not yet constructed (the `make_safe_args` call)
     * or has just had its ownership moved into the call itself (the
     * `__main` call, `argsTok.isOwnershipMoveSource`) -- so there is
     * never anything of this wrapper's own left to destruct if either
     * call unwinds back into it.
     */
    private void stageCallSiteForUnwindNames(List<String> destructNames, List<Token> unlockCalls, Token at) {
        if (!checker.usesThrow() || inGtSuppressedContext) {
            return;
        }
        String label;
        if (pendingTryCatchLabel != null) {
            // "The TRY eats the next two instructions" -- redirect this
            // exact staging write straight at the catch block already
            // hoisted to this frame's own top, instead of a fresh,
            // ordinary landing-pad label of our own. No entry is added
            // to `pendingCallSiteUnwindLandingPads` for this call site --
            // nothing should ever land at an intermediate pad here, only
            // directly in the catch block, so there is no separate pad
            // for `emitCallSiteUnwindLandingPads` to later emit.
            label = pendingTryCatchLabel;
            pendingTryCatchLabel = null;
        } else {
            label = newLabel("gt_callsite__" + currentFuncMangledName);
            pendingCallSiteUnwindLandingPads.add(new PendingUnwindLandingPad(label, destructNames, unlockCalls, at));
        }
        line("ADDR gt_routine_address code_addr");
        line("PUSH_LABEL " + label + " code_addr");
        line("ASSIGN code_addr code_addr code_addr");
    }

    /**
     * Shared by `emitExpr`'s and `emitStatement`'s own "try" cases (the
     * expression- and bare-statement-position forms of "try foo()
     * catch{...}"): stages the one-shot `pendingTryCatchLabel` redirect
     * (see its own doc comment) around emitting the exact call node a
     * "try" directly wraps -- `tryTok.sub.get(0)`, always a plain CALL
     * per `TypeChecker.checkTry`'s own validation -- so that call's own
     * staging write lands in the catch block instead of a fresh landing
     * pad. The explicit clear after `emitExpr` returns is a pure safety
     * net (`stageCallSiteForUnwindNames` already consumes and clears it
     * itself, the moment it actually fires); it guarantees this can
     * never leak into unrelated code even if that call somehow didn't
     * happen (it always does today -- see `pendingTryCatchLabel`'s own
     * doc comment for why). No label is needed here for where a caught
     * throw "resumes" afterward -- `TypeChecker.checkTry` now requires
     * every catch body to be terminating (end in its own 'return' or
     * 'throw'), so a caught throw never falls back through this call
     * site's own normal-path code at all; see `emitHoistedCatchBlocks`'s
     * own doc comment for what that buys.
     */
    private void emitTryGuardedCall(Token tryTok) {
        pendingTryCatchLabel = tryCatchLabels.get(tryTok);
        emitExpr(tryTok.sub.get(0));
        pendingTryCatchLabel = null;
    }

    /**
     * Emits every call-site landing pad `stageCallSiteForUnwind` staged
     * while this function's own body was being emitted -- placed after
     * the real body's own natural-end destruct list but still ahead of
     * "FUNC_END" (dead code during ordinary execution, reached only by
     * an unwind reading this exact frame's own `gt_routine_address`
     * slot; the leading `JMP` guarantees normal fall-through control
     * flow never wanders into it), the identical "jump-around" placement
     * the old, now-superseded whole-function `gt_routine` label used.
     *
     * Each landing pad: its own precise `GT_DESTRUCT` list (exactly what
     * was alive at that one specific call site, never this function's
     * whole-function superset), then the same three-way root/async/
     * ordinary ending `emitGtRoutineBody` already established --
     * `EXIT` for the true root, `EXIT_THREAD` for an `@async` function,
     * `GT_UNWIND` (continue the chain one frame further up) otherwise.
     */
    private void emitCallSiteUnwindLandingPads(TypeChecker.FuncInfo info, String emittedName, boolean gtSuppressed) {
        if (gtSuppressed || pendingCallSiteUnwindLandingPads.isEmpty()) {
            return;
        }
        String skipLabel = newLabel("end_of_gt_callsites__" + emittedName);
        line("JMP " + skipLabel);
        for (PendingUnwindLandingPad pad : pendingCallSiteUnwindLandingPads) {
            line(pad.label + ":");
            if (!pad.destructNames.isEmpty()) {
                requireGhostTableFunctionPresent("gt_destruct", pad.at);
            }
            for (String name : pad.destructNames) {
                line("GT_DESTRUCT " + name);
            }
            emitUnlockList(pad.unlockCalls, pad.at);
            if (emittedName.equals("main")) {
                line("EXIT");
            } else if (info.isAsync) {
                line("EXIT_THREAD");
            } else {
                line(gtUnwindLine());
            }
        }
        line(skipLabel + ":");
    }

    private void emitFuncUnderName(TypeChecker.FuncInfo info, String emittedName, boolean gtSuppressed,
            boolean allowExport) {
        boolean previousGtContext = inGtSuppressedContext;
        inGtSuppressedContext = gtSuppressed;
        currentFuncMangledName = emittedName;
        currentFuncInfo = info;
        List<PendingUnwindLandingPad> previousPendingLandingPads = pendingCallSiteUnwindLandingPads;
        pendingCallSiteUnwindLandingPads = new ArrayList<>();
        Map<Token, String> previousTryCatchLabels = tryCatchLabels;
        tryCatchLabels = new HashMap<>();
        Map<Token, String> previousTryBlockLabels = tryBlockLabels;
        tryBlockLabels = new HashMap<>();
        Map<Token, String> previousContinueTargetLabels = continueTargetLabels;
        continueTargetLabels = new HashMap<>();
        // Precomputes every try-block's own "after" label, and every
        // 'continue's own target among them, before a single statement
        // of this function's body -- hoisted catch or otherwise -- is
        // emitted. See `collectTryBlockLabels`'s own doc comment for why
        // this can't wait until the try-block's own normal-position
        // emission the way an ordinary fresh label generation would.
        collectTryBlockLabels(info.funcToken.childs, new ArrayDeque<>());
        line("FUNC_START " + emittedName);
        if (allowExport && info.isExported) {
            // "This symbol will be available to an assembly file linking
            // to this one" -- confirmed directly. Always the func's own
            // bare, unmangled name (isExported is only ever set on a
            // plain top-level func, never an impl method, so
            // mangledName already equals name here regardless).
            line("EXPORT");
        }
        emitDecorators("FUNC_DECORATE", info.funcToken.decorators, true);
        line("RETURNS " + info.returnType.canonical());
        // "ARG name type" -- one bare declaration line per parameter, in
        // declared order, right after "RETURNS". Confirmed directly this
        // is genuinely high-order-bytecode work, not low-order: naming
        // a parameter and stating its canonical type is a signature fact
        // the type checker already fully owns, nothing about it involves
        // a real frame address or byte size. Turning "ARG" into a real
        // `ALLOC` (correctly positioned relative to `gt_routine_address`
        // and every ordinary local) plus its own load sequence is
        // "ARG-to-ALLOC lowering" -- genuinely low-order, address-
        // lowering work, and it now happens entirely in the sibling
        // `caspien-optimizer` project's own `ArgToAllocLoweringPass`,
        // confirmed directly this compiler-side version of it was wrong
        // from the start: "i just said i wanted the ARG to ALLOC
        // conversions to take place in the optimizer." See that
        // project's CLAUDE.md, "ARG-to-ALLOC lowering," for the full,
        // corrected design.
        if (isStructByValueReturn(info.returnType)) {
            // Return Value Optimization: a function that returns a struct
            // by value gets one synthetic, hidden leading parameter --
            // "$ret_dest", never collidable with a real user identifier
            // (the Lexer can never produce '$' inside one, the same
            // established precedent the for-loop's own hidden range
            // variable already relies on) -- the caller-owned destination
            // address this function will write its result directly into.
            // Emitted before every real, user-declared ARG line so it
            // always lands in the very first argument-register slot,
            // matching the real x86-64 "hidden sret pointer" ABI
            // convention (System V RDI / win64 RCX) this feature
            // implements. `ArgToAllocLoweringPass` (in the sibling
            // `caspien-optimizer` project) needs zero changes to handle
            // this -- confirmed directly, it already converts any
            // declared "ARG name type" line into an ALLOC+load sequence
            // with no special-casing by name at all.
            line("ARG $ret_dest raw_mut_" + info.returnType.baseType);
        }
        emitParamDecls(info.paramNames, canonicalParamTypes(info));
        // "We static alloc, then we Alloc, including the special
        // variable for the unwinding being the first thing, then we
        // have the rest of the functionality. We do not have our
        // routines mixed in with our allocs... the whole point of all
        // of this is to limit the amount of work done, not more.
        // Obviously the allocs are all supposed to be together,
        // eventually optimized to a single instruction manipulating
        // the stack pointers," confirmed directly -- every
        // ALLOC/ALLOC_STATIC this function will ever need, from
        // anywhere in its body no matter how deeply nested (an 'if'/
        // 'loop'/'for'/'match'/'lock'/'unsafe'/'safe'/'assume'
        // body, at any depth), is collected up front here and emitted
        // contiguously, before a single byte of the gt_routine
        // machinery or the function's own statement bodies. This is a
        // genuine, whole-function *textual* hoist -- not merely a
        // convention a later assembly stage is trusted to compensate
        // for -- so `emitBlock` (and `emitPreLoopInit`/`emitForLoop`)
        // no longer emit any ALLOC/ALLOC_STATIC of their own at all,
        // only the runtime initialization (the ASSIGN each declaration
        // still needs) at its original position. Parameters are not
        // part of this hoist at all any more -- they're still bare
        // `ARG` lines above, not `ALLOC`s, until the optimizer's own
        // `ArgToAllocLoweringPass` runs.
        List<Runnable> staticAllocs = new ArrayList<>();
        List<Runnable> ordinaryAllocs = new ArrayList<>();
        collectHoistedAllocs(info.funcToken.childs, staticAllocs, ordinaryAllocs);
        for (Runnable r : staticAllocs) {
            r.run();
        }
        emitGtRoutineAlloc(info, emittedName, gtSuppressed);
        final int allocScanStart = out.length();
        for (Runnable r : ordinaryAllocs) {
            r.run();
        }
        requireOneOwnsTypePerSlotName(info, out.substring(allocScanStart));
        // Locals discovered while emitting the body (see `declareHiddenLocal`) get their ALLOC lines spliced in right here once the
        // body is done: a variable cannot be used before it is declared, so no pre-pass is needed to find them.
        final int savedLateAllocPos = lateAllocPos;
        final List<String> savedLateAllocs = lateAllocs;
        lateAllocPos = out.length();
        lateAllocs = new ArrayList<>();
        emitGtRoutineBody(info, emittedName, gtSuppressed);
        emitHoistedCatchBlocks(info);
        // "this goes in @event_loop instead" when '@with_tick' --
        // `emittedName` is already the post-dundering, actually-
        // emitted bytecode name by this point (the identical
        // "check the emitted name, not the source name" precedent
        // `emitGtRoutineBody` already established for its own,
        // separate EXIT-vs-GT_UNWIND choice), so this is exactly the
        // one call to `emitBlock` that ever represents the real
        // program-entry-point function's own top-level body -- true
        // whether that's the ordinary 'main' itself or the '@event_loop'
        // function emitted in its place.
        emitBlock(info.funcToken.childs, emittedName.equals("main"));
        emitDestructList(info.funcToken.destructOnExit, info.funcToken);
        // Every call site in this function's own body has now been
        // visited (the block above is done), so the full, final list of
        // this function's own call-site landing pads is known -- emit
        // them here, after the real body's own natural-end destruct list
        // but still ahead of "FUNC_END", the same "dead unless jumped
        // into" placement the old whole-function `gt_routine` label used
        // (see `emitCallSiteUnwindLandingPads`'s own doc comment).
        emitCallSiteUnwindLandingPads(info, emittedName, gtSuppressed);
        line("FUNC_END");
        if (!lateAllocs.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String a : lateAllocs) {
                sb.append(a).append('\n');
            }
            out.insert(lateAllocPos, sb);
        }
        lateAllocPos = savedLateAllocPos;
        lateAllocs = savedLateAllocs;
        currentFuncMangledName = null;
        currentFuncInfo = null;
        inGtSuppressedContext = previousGtContext;
        pendingCallSiteUnwindLandingPads = previousPendingLandingPads;
        tryCatchLabels = previousTryCatchLabels;
        tryBlockLabels = previousTryBlockLabels;
        continueTargetLabels = previousContinueTargetLabels;
    }

    /**
     * True for a plain (no storage modifier) struct-typed `TypeInfo` --
     * exactly `TypeChecker.isStructByValueReturn`'s own condition,
     * duplicated here since this class only ever sees `checker`'s already-
     * built struct table, not that private helper itself. Return Value
     * Optimization applies to a function whenever this is true of its own
     * declared return type -- see `emitFuncUnderName`'s hidden "$ret_dest"
     * parameter, `emitReturn`'s struct-RVO branch, and `emitStructRvoAssign`.
     */
    private boolean isStructByValueReturn(TypeChecker.TypeInfo t) {
        return t.storage == null && checker.getStructs().containsKey(t.baseType);
    }

    /** `info.paramTypes`, each already reduced to its own `canonical()` text -- the shape `emitParamDecls` needs, computed once per call rather than repeated inline at every use site. */
    private List<String> canonicalParamTypes(TypeChecker.FuncInfo info) {
        List<String> out = new ArrayList<>();
        for (TypeChecker.TypeInfo t : info.paramTypes) {
            out.add(t.canonical());
        }
        return out;
    }

    /**
     * "ARG name type" -- a genuine signature annotation, never a real
     * instruction. This is deliberately the *entire* extent of this
     * compiler's own involvement in a parameter's own bytecode
     * representation: no frame slot, no load sequence, no register-vs-
     * stack decision, nothing address-related at all. Turning this line
     * into a real `ALLOC` (correctly positioned relative to
     * `gt_routine_address` and every ordinary local) plus its own
     * `ADDR`/`PUSH ARGn`/`ASSIGN` load sequence is "ARG-to-ALLOC
     * lowering" -- confirmed directly this is genuinely low-order,
     * address-lowering work and belongs entirely in the sibling
     * `caspien-optimizer` project's own `ArgToAllocLoweringPass`, not
     * here: naming a parameter and stating its canonical type is a
     * signature fact the type checker already fully owns; nothing about
     * it needs a real frame address or byte size to exist yet. See that
     * project's CLAUDE.md, "ARG-to-ALLOC lowering," for the full,
     * corrected design (including how it decides where in the frame a
     * parameter's own synthesized `ALLOC` belongs, given this compiler
     * emits `ARG` right after `RETURNS` -- textually *before*
     * `gt_routine_address`'s own `ALLOC` -- while the frame layout needs
     * parameters positioned *after* it).
     */
    private void emitParamDecls(List<String> paramNames, List<String> paramCanonicalTypes) {
        for (int i = 0; i < paramNames.size(); i++) {
            line("ARG " + paramNames.get(i) + " " + paramCanonicalTypes.get(i));
        }
    }

    /**
     * "extern printf(static imut string,...) void" -- signature-only,
     * no body (there's nothing here for this compilation unit to
     * implement; the final linked assembly is expected to already
     * provide it). A START/END-wrapped multi-line block was the first
     * shape tried here (mirroring FUNC_START/FUNC_END), but there's no
     * nested body of variable length between them the way an actual
     * function has -- just a name, a return type, and a flat list of
     * param types, exactly the shape ENUM's single line already
     * handles for its own flat variant list. One line, matching that
     * precedent: name, return type, then each fixed param type in
     * order, then a trailing "VARARGS" token if the declaration ended
     * in '...'.
     */
    private void emitExtern(TypeChecker.ExternInfo info) {
        // "info.linkName," not "info.name" -- purely cosmetic here (this
        // declaration line is itself a no-op at the codegen stage, "ELF
        // resolves an EXTERN'd symbol via the linker automatically, with
        // no explicit declaration needed in GAS"), but kept matching the
        // real linked symbol ("EXTERN_CALL"'s own operand, see
        // emitExternCall) rather than the Caspien-side identifier, so a
        // reader of raw bytecode output never sees two different names
        // for what is, at the machine-code level, one and the same
        // extern.
        StringBuilder sb = new StringBuilder("EXTERN ").append(info.linkName)
                .append(' ').append(info.returnType.canonical());
        for (TypeChecker.TypeInfo paramType : info.paramTypes) {
            sb.append(' ').append(paramType.canonical());
        }
        if (info.hasVarargs) {
            sb.append(" VARARGS");
        }
        line(sb.toString());
    }

    /**
     * "these labels should also be able to take arguments," confirmed
     * directly, reproduced verbatim in the emitted line rather than
     * dropped -- a string argument keeps its quotes, a bare identifier
     * doesn't get any added. One line per decorator, in declaration
     * order, matching the "FUNC_DECORATE @pure" example exactly
     * (mnemonic + one space + the decorator's own written form).
     */
    private void emitDecorators(String mnemonic, List<Token.Decorator> decorators) {
        emitDecorators(mnemonic, decorators, false);
    }

    /** withPos: append the decorator's source position as a quoted "file:line" operand (the optimizer reports on it). */
    private void emitDecorators(String mnemonic, List<Token.Decorator> decorators, boolean withPos) {
        if (decorators == null) {
            return;
        }
        for (Token.Decorator d : decorators) {
            StringBuilder sb = new StringBuilder("@").append(d.name);
            if (!d.args.isEmpty()) {
                sb.append('(');
                for (int i = 0; i < d.args.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    String a = d.args.get(i);
                    sb.append(d.argIsString.get(i) ? "\"" + escapeForBytecode(a) + "\"" : a);
                }
                sb.append(')');
            }
            boolean wantsPos = !mnemonic.equals("FUNC_DECORATE")
                    || d.name.equals("inline") || (d.name.equals("dont") && d.args.size() == 1 && d.args.get(0).equals("inline"));
            if (withPos && wantsPos && d.file != null && d.line > 0) {
                sb.append(" \"").append(escapeForBytecode(d.file + ":" + d.line)).append('"');
            }
            line(mnemonic + " " + sb);
        }
    }

    // ---- blocks: hoisted ALLOCs, then each statement in order -------------

    private void emitBlock(List<Token> lines) {
        emitBlock(lines, false);
    }

    /**
     * "In the final bytecode, in my main, if I have any owns, ref, dyn
     * or new in the final compilation unit, then I must have GT_INIT
     * in my main() after my allocation and my static allocations, but
     * before any of my expressions," confirmed directly -- `isEntryPointBody`
     * is `true` for exactly one call to this method in the whole
     * program: the one `emitFuncUnderName` makes for whichever
     * function is actually emitted under the bytecode name "main" (see
     * that method's own comment) -- never for a nested block (an
     * `if`/loop/`unsafe`/`safe` body, every one of which calls the
     * plain, single-argument overload above, which always passes
     * `false`), and never for any other function's own top-level body.
     *
     * No longer hoists any ALLOC/ALLOC_STATIC of its own -- every one
     * of those, for the *entire* enclosing function, has already been
     * emitted by `emitFuncUnderName` (via `collectHoistedAllocs`)
     * before this method ever runs, so GT_INIT here is already
     * genuinely "after my allocation and my static allocations" for
     * the whole function, not just this one block. A `let` line's own
     * runtime initialization (its ASSIGN) is still emitted right here,
     * at its original position, via the ordinary `emitStatement`
     * dispatch below -- only the frame-reservation moved, not the
     * value computation.
     */
    private void emitBlock(List<Token> lines, boolean isEntryPointBody) {
        if (isEntryPointBody && checker.usesOwnsRefDynNew()) {
            Token at = currentFuncInfo != null && currentFuncInfo.funcToken != null ? currentFuncInfo.funcToken
                    : (lines.isEmpty() ? null : lines.get(0));
            requireGhostTableFunctionPresent("gt_init", at);
            requireGhostTableFunctionPresent("gt_moved", at);
            line("GT_INIT");
        }
        for (Token lineTok : lines) {
            Token stmt = lineTok.childs.get(0);
            Token letTok = letDeclarationOf(stmt);
            if (letTok != null && letTok.isStatic) {
                continue; // no runtime init to emit -- the value is already baked into ALLOC_STATIC, hoisted to the top of the function
            }
            emitStatement(stmt);
        }
    }

    /**
     * "When an owns value leaves scope, we need it to be deleted, we
     * achieve this with: GT_DESTRUCT my_owns," confirmed directly. Reads
     * TypeChecker's precomputed list (already in the exact order to
     * emit them: reverse declaration order, already excluding anything
     * moved or return-preserved) and emits one instruction per name --
     * this function does no computation of its own at all.
     *
     * "If I emit a ghost table interaction, then I must have that
     * corresponding decorated function in the final compilation unit,"
     * confirmed directly -- checked here, right at the point a real
     * GT_DESTRUCT is about to be emitted (an empty/null list emits
     * nothing and needs no function backing it at all), rather than
     * unconditionally requiring @gt_destruct to exist even in a program
     * that never actually destructs anything.
     */
    private void emitDestructList(List<String> names, Token at) {
        if (names == null || names.isEmpty()) {
            return;
        }
        if (inGtSuppressedContext) {
            // "Generation of ghost table instructions... don't happen
            // in the ghost table decorated functions," confirmed
            // directly -- silently skipped here (not an error): this
            // is implicit, compiler-inserted behavior, not something
            // the programmer wrote directly, so there's nothing to
            // flag -- unlike a bare 'match' condition below, which the
            // programmer *did* write directly, and which does error.
            return;
        }
        requireGhostTableFunctionPresent("gt_destruct", at);
        for (String name : names) {
            line("GT_DESTRUCT " + name);
            // "double check the ownership model for any other
            // potential leaks," confirmed directly -- surfaced a real
            // one: a loop-body-local `owns` variable shares the same
            // hoisted `ALLOC` slot across every iteration, so without
            // this, a slot destructed at one iteration's own natural
            // end would still hold that same, now-freed pointer at the
            // *start* of the next -- stale, not null. If a throw
            // (once that machinery exists) reached that slot before
            // its own re-declaration ran again, a blind re-destruct on
            // an already-freed address that some unrelated allocation
            // has since reused would corrupt live, unrelated data --
            // not a theoretical risk, since reusing a just-freed
            // address is exactly what a real allocator routinely does.
            // Nulled here, immediately, the same "the slot's own value
            // is always the truth" principle the move-out fix already
            // established -- applied uniformly to every call site of
            // this method, not only loop bodies specifically, since a
            // one-time (non-looping) block exit has the identical
            // "stale until something else overwrites it" shape, just
            // without the repetition that makes a loop the case most
            // likely to actually hit it.
            TypeChecker.TypeInfo type = currentFuncInfo != null ? currentFuncInfo.ownsLocalTypes.get(name) : null;
            if (type != null && checker.isInlineOwningStruct(type)) {
                // an inline owning struct: its members were dropped by the GT_DESTRUCT above; leave them null
                Token local = new Token(TokenType.VARREF, name, 0, "");
                local.resolvedType = type.canonical();
                emitInlineOwnsNullOut(local, type.baseType);
            } else if (type != null) {
                line("ADDR " + name + " " + type.canonical());
                line("PUSH null " + type.canonical());
                line("ASSIGN " + type.canonical() + " " + type.canonical() + " " + type.canonical());
            }
        }
    }

    /**
     * The lock-release counterpart of emitDestructList -- emits each
     * already-type-checked, compiler-synthesized unlock() call
     * (TypeChecker.Token.unlockOnExit) as an ordinary expression
     * statement, in the order TypeChecker already put them in (reverse
     * acquisition order). Each is a real CALL node, so this is just an
     * ordinary emitExpr per entry -- no dedicated bytecode shape of its
     * own, the same "reuse the ordinary call-emission machinery" the
     * calls themselves were built to get for free at type-check time.
     */
    private void emitUnlockList(List<Token> unlockCalls, Token at) {
        if (unlockCalls == null || unlockCalls.isEmpty()) {
            return;
        }
        for (Token unlockCall : unlockCalls) {
            emitExpr(unlockCall);
        }
    }

    /** Shared by emitDestructList/emitMatchCondition -- see emitDestructList's own doc comment for the rule this enforces. */
    private void requireGhostTableFunctionPresent(String decoratorName, Token at) {
        if (checker.getGhostTableFunction(decoratorName) == null) {
            throw new CompilerException("bytecode", at.file, at.line,
                    "emitting a '@" + decoratorName + "' ghost-table interaction here requires a function "
                            + "decorated '@" + decoratorName + "' to exist somewhere in the compilation unit "
                            + "(none was found)");
        }
    }

    /**
     * "let static ... = ..." -- the value is a compile-time constant
     * (TypeChecker.checkAssign/collectGlobal already validated this: no
     * function calls, and no variable references besides an earlier
     * global), so instead of the ordinary runtime ALLOC+PUSH+PUSH+ASSIGN
     * sequence, the folded value is emitted directly as part of
     * allocation itself. A scalar becomes one "MNEMONIC name type value"
     * line; a struct becomes a container line ("MNEMONIC name
     * imut_StructName", no value -- the container itself is never a
     * scalar) followed by one "MNEMONIC name.member type value" line per
     * member (in the same declaration order ordinary struct-literal
     * emission already uses), confirmed directly by example, including
     * for a struct nested inside a struct (dotted paths compose the same
     * way one level deeper: "name.outer.inner"). `mnemonic` is
     * "ALLOC_STATIC" for a local static, "GLOBAL" for a root-level one --
     * confirmed directly these are "subject to the same static
     * instantiation rules," so the only difference is the leading word.
     */
    private void emitStaticAlloc(String mnemonic, String name, String resolvedType, Token rhs) {
        Token unwrapped = unwrapStaticExpr(rhs);
        String structName = staticStructNameOf(unwrapped);
        if (structName != null) {
            line(mnemonic + " " + name + " imut_" + structName);
            TypeChecker.StructInfo structInfo = checker.getStructs().get(structName);
            // The hidden "___type" (classId) field a real, runtime
            // "X{...}" literal always gets, pushed first, as a secret
            // argument, by emitInstantiate -- found missing here directly
            // ("its an issue in the compiler... it should be just like
            // the other fields"): the static path iterated
            // `structInfo.members` (the user-declared fields only) and
            // never emitted a "name.___type" line at all, so a
            // classId-bearing static struct's own runtime type tag was
            // simply never initialized -- correct only by coincidence
            // whenever the struct's own classId happens to be 0 (whatever
            // zero-initializes unclaimed static storage by default gets
            // it right without anyone asking it to). Fixed by giving the
            // static path the exact same "@untyped are exempt" gate
            // `emitInstantiate`/`emitStruct` both already use
            // (`structInfo.classId == null` for one), emitting the
            // classId as an ordinary "name.___type imut_u64 classId" line
            // -- deliberately emitted *before* the user-declared members
            // below, matching `emitInstantiate`'s own "pushed first"
            // ordering, though nothing downstream actually depends on
            // this particular ordering the way a real stack push would.
            Map<String, Token> valueByMember = new HashMap<>();
            for (Token lineTok : unwrapped.right.childs) {
                Token assignNode = lineTok.childs.get(0);
                valueByMember.put(assignNode.left.text, assignNode.right);
            }
            // Walk the struct's real layout (hidden ___type first, members in order, padding gaps). The backend places each
            // dotted "name.member" line at the running sum of the sizes before it, so the padding gaps must be lines too
            // ("name.$padN"); without them every member after a gap (a bool followed by a u64, say) was read at the wrong offset.
            int padCount = 0;
            for (StructLayoutEntry entry : computeStructLayout(structInfo).entries) {
                if (entry.paddingBytes > 0) {
                    line(mnemonic + " " + name + ".$pad" + (padCount++) + " imut_u8[" + entry.paddingBytes + "]");
                } else if (entry.memberName.equals("___type")) {
                    line(mnemonic + " " + name + ".___type imut_u64 " + structInfo.classId);
                } else {
                    emitStaticAlloc(mnemonic, name + "." + entry.memberName, entry.canonicalType, valueByMember.get(entry.memberName));
                }
            }
            return;
        }
        List<Token> arrayElements = staticArrayElements(unwrapped);
        if (arrayElements != null) {
            // "let static arr = mut [1,2,3]" -- a fixed-array literal is
            // a composite with no single-line runtime representation,
            // the identical struct/range shape above: one no-value
            // container line, then one real line per positional element
            // ("name.0", "name.1", ...), matching the struct case's own
            // dotted-member convention but for a numeric index instead
            // of a field name.
            line(mnemonic + " " + name + " " + resolvedType);
            String elemType = staticArrayElementType(resolvedType, unwrapped);
            for (int i = 0; i < arrayElements.size(); i++) {
                emitStaticAlloc(mnemonic, name + "." + i, elemType, arrayElements.get(i));
            }
            return;
        }
        if (isRangeType(resolvedType)) {
            // "a range folds information into its type as its
            // available... always exactly two u64 fields either way" --
            // a range value, like a struct value, is a composite with
            // no single-line runtime representation of its own, so it
            // gets the identical container-plus-named-subfields shape a
            // struct-typed static already established just above: one
            // no-value container line, then one real line per field
            // ("name.start"/"name.end", matching the qualified-name
            // convention an ordinary, non-static range's own PUSH
            // already uses -- see "emitPush"'s own range-field-access
            // handling in the codegen stage).
            long[] bounds = rangeBoundValues(unwrapped);
            line(mnemonic + " " + name + " " + resolvedType);
            line(mnemonic + " " + name + ".start imut_u64 " + bounds[0]);
            line(mnemonic + " " + name + ".end imut_u64 " + bounds[1]);
            return;
        }
        line(mnemonic + " " + name + " " + resolvedType + " " + foldStaticConstant(rhs));
    }

    /**
     * Strips a leading (mut|imut) wrapper, a transparent 'as' cast, and
     * redundant grouping parens -- the shapes a static/global
     * initializer's own top-level RHS may legally be wrapped in -- down
     * to the real, underlying expression node those wrappers carry no
     * runtime representation for. Shared by every static-initializer
     * shape check that needs to see past them (struct-literal
     * detection, range detection) rather than each re-implementing its
     * own unwrap loop.
     */
    private Token unwrapStaticExpr(Token node) {
        while (true) {
            if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
                node = node.childs.get(0);
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.text.equals("as")) {
                node = node.left;
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.unary
                    && (node.text.equals("mut") || node.text.equals("imut"))) {
                node = node.left;
                continue;
            }
            return node;
        }
    }

    /**
     * The array literal's own element expressions, in written order, if
     * `rhs` is a "[" (array literal) DELINEATOR node -- else null (not
     * an array literal). Mirrors the ordinary, non-static array-literal
     * shape `emitExpr`'s own DELINEATOR "[" case already collects via
     * `collectCommaArgs`.
     */
    private List<Token> staticArrayElements(Token rhs) {
        if (rhs.type != TokenType.DELINEATOR || !rhs.text.equals("[")) {
            return null;
        }
        List<Token> elements = new ArrayList<>();
        collectCommaArgs(rhs.childs.get(0), elements);
        return elements;
    }

    /**
     * Strips a static array type's own trailing "[N]" length suffix,
     * leaving the per-element type text every positional sub-line
     * ("name.0", "name.1", ...) is emitted with -- e.g. "mut_u64[3]" ->
     * "mut_u64". Hand-scanned, no regex, per this project's standing
     * rule.
     */
    private String staticArrayElementType(String resolvedType, Token at) {
        int bracket = resolvedType.lastIndexOf('[');
        if (bracket < 0) {
            throw new CompilerException("bytecode", at.file, at.line,
                    "expected an array type here, got '" + resolvedType + "'");
        }
        return resolvedType.substring(0, bracket);
    }

    /** The struct name if `rhs` is a struct-literal ("INSTANTIATE") node, else null (a plain scalar). */
    private String staticStructNameOf(Token rhs) {
        if (rhs.type == TokenType.OPERATOR && rhs.text.equals("INSTANTIATE")) {
            return rhs.left.text;
        }
        return null;
    }

    /**
     * True when a static/global's own canonical resolved type names a
     * range -- checked by stripping the one optional leading storage
     * segment and the one mutability segment off the front (a range's
     * own canonical text is otherwise never wrapped in anything else),
     * then testing for the literal "range(" prefix every literal-bound
     * range type this compiler ever produces carries.
     */
    private boolean isRangeType(String resolvedType) {
        String t = resolvedType;
        for (String storage : new String[]{"raw_", "owns_", "ref_", "auto_", "static_"}) {
            if (t.startsWith(storage)) {
                t = t.substring(storage.length());
                break;
            }
        }
        for (String mutability : new String[]{"mut_", "imut_", "indeterminate_"}) {
            if (t.startsWith(mutability)) {
                t = t.substring(mutability.length());
                break;
            }
        }
        return t.startsWith("range(");
    }

    /**
     * Resolves a range-typed static initializer's own two literal
     * bounds -- either a literal "a..b" construction (folding each side
     * through the ordinary `foldStaticConstant`) or a range-valued
     * enum-variant reference ("Span.SMALL"), whose own bounds never
     * appear as real, separate sub-expressions at all -- they're baked
     * directly into the variant reference's own resolved type text
     * ("imut_range(0,10)"), read back out by `parseEmbeddedRangeBounds`
     * the same way this codegen stage's own runtime `resolveEnumVariantRangeBounds` already does for the identical shape.
     */
    private long[] rangeBoundValues(Token node) {
        if (node.type == TokenType.OPERATOR && node.text.equals("..")) {
            return new long[]{Long.parseLong(foldStaticConstant(node.left)),
                    Long.parseLong(foldStaticConstant(node.right))};
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && isQualifiedNameableDot(node)) {
            return parseEmbeddedRangeBounds(node.resolvedType, node);
        }
        throw new CompilerException("bytecode", node.file, node.line,
                "a 'static' range initializer must be a literal 'a..b' range or a reference to a "
                        + "range-valued enum variant, not '" + node.text + "'");
    }

    /** Hand-scans "...range(A,B)" out of a resolved range type string -- no regex, per this project's standing rule. */
    private long[] parseEmbeddedRangeBounds(String type, Token at) {
        int open = type.indexOf("range(");
        if (open < 0) {
            throw new CompilerException("bytecode", at.file, at.line,
                    "expected a literal-bound range type here, got '" + type + "'");
        }
        int start = open + "range(".length();
        int comma = type.indexOf(',', start);
        int close = comma < 0 ? -1 : type.indexOf(')', comma);
        if (comma < 0 || close < 0) {
            throw new CompilerException("bytecode", at.file, at.line,
                    "malformed range type string '" + type + "'");
        }
        long a = Long.parseLong(type.substring(start, comma));
        long b = Long.parseLong(type.substring(comma + 1, close));
        return new long[]{a, b};
    }

    /**
     * Folds a compile-time-constant expression down to a single literal
     * value's bytecode text -- literal leaves pass straight through
     * (reusing `literalValueOf`), an 'as' cast is transparent (it only
     * ever changes mutability or widens an integer's *type*, never its
     * printed value), and integer arithmetic (+,-,*,/,%, unary '-') is
     * evaluated directly, the same restricted shape 'dup' counts already
     * use (see DupExpander). Enum-variant defaults aren't handled here
     * yet -- no example covers that shape, so it's left as a follow-up
     * rather than guessed at.
     */
    private String foldStaticConstant(Token node) {
        if (node.sizeofStruct != null && node.left != null && node.left.type == TokenType.VARREF
                && node.left.text.equals("sizeof")) {
            // "let static n = sizeof(Struct)": a global/static initializer is a data line, not code,
            // so the Optimizer's SIZEOF resolution never sees it; fold the real laid-out size here.
            // The value is fixed here, so the Optimizer's StructMemberReorderingPass must not
            // change this struct's size: STRUCT_PIN names it (the pass always strips the line).
            line("STRUCT_PIN " + node.sizeofStruct);
            return Long.toString(computeStructLayout(checker.getStructs().get(node.sizeofStruct)).size);
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left != null
                && node.left.type == TokenType.VARREF && node.left.text.equals("sizeof")) {
            return Long.toString(node.builtinConstantValue);
        }
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
            return foldStaticConstant(node.childs.get(0));
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("as")) {
            return foldStaticConstant(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.unary
                && (node.text.equals("mut") || node.text.equals("imut"))) {
            // "let static x = mut 42" -- the RHS is virtually always
            // wrapped this way, since a bare literal is indeterminate
            // and a static/global's value must be concrete. The
            // wrapper itself carries no runtime representation (see
            // "A mutability-only value carries no runtime
            // representation" in the language reference) -- fold
            // straight through to the operand, the same way 'as' is
            // already treated as transparent just above.
            return foldStaticConstant(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("static")) {
            // "let static sp = static \"hello\"" -- 'static' applied
            // directly to a string/char literal (the only shape that
            // can ever actually reach here; a reference to an existing
            // 'let static'/global is separately, structurally blocked
            // from ever type-checking at all for a *local* static's own
            // initializer -- see CLAUDE.md's "Known gaps" for the
            // identical, pre-existing Scope-safety limitation already
            // documented for globals) carries no runtime representation
            // of its own either -- emitAddressOf's own ordinary,
            // non-static codegen for this exact shape is just
            // "emitExpr(op.left), nothing more," confirmed directly
            // against that method's own doc comment -- so folding
            // straight through to the literal is the identical
            // "nothing to add" behavior, not a guess.
            return foldStaticConstant(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("atomic")) {
            // "let static myAtomic = mut atomic '!'" -- 'atomic' is a
            // pure type-modifier wrapper (see "The 'atomic' modifier
            // and 'swap' operator": "this doesnt require new bytecode
            // operations because the type data is there in the
            // instruction... isAtomic is embedded directly into the
            // type string"), carrying no runtime representation of its
            // own, exactly like the 'mut'/'imut'/'static' wrappers
            // already stripped just above -- a real, separate,
            // previously-latent bug found by direct testing (compiling
            // a real 'let static x = mut atomic <literal>' probe and
            // inspecting the actual emitted GLOBAL/ALLOC_STATIC line):
            // without this case, control fell through all the way to
            // the generic operator-node fallback and then to
            // literalValueOf's own 'default: return node.text' branch,
            // which returned the literal operator text "atomic" itself
            // as the supposed initial value -- silently corrupting
            // every atomic global/static's own folded constant, not a
            // hypothetical edge case.
            return foldStaticConstant(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && isQualifiedNameableDot(node)) {
            // "let static c = Color.GREEN" -- a plain (never
            // range-valued -- emitStaticAlloc's own isRangeType check
            // routes that shape to its own, separate container/start/
            // end handling before this method is ever reached)
            // enum-variant reference. Folds to the same dot-qualified
            // name text an ordinary, non-static PUSH of the identical
            // expression already uses (qualifiedDotName) -- this
            // codegen stage's own "isEnumVariantLiteral" resolution
            // already expects exactly this shape.
            return qualifiedDotName(node);
        }
        if (node.type == TokenType.OPERATOR && !node.unary) {
            long left = Long.parseLong(foldStaticConstant(node.left));
            long right = Long.parseLong(foldStaticConstant(node.right));
            switch (node.text) {
                case "+": return Long.toString(left + right);
                case "-": return Long.toString(left - right);
                case "*": return Long.toString(left * right);
                case "/": return Long.toString(left / right);
                case "%": return Long.toString(left % right);
                default:
                    break;
            }
        }
        if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("-")) {
            String folded = foldStaticConstant(node.left);
            if (folded.contains(".") || folded.contains("e") || folded.contains("E")) {
                // A float constant ("let static y = mut -1.16", or a negative element of
                // a static float array): negate the text itself. parseLong threw a
                // NumberFormatException here and crashed the compiler.
                return folded.startsWith("-") ? folded.substring(1) : "-" + folded;
            }
            return Long.toString(-Long.parseLong(folded));
        }
        if (node.type == TokenType.VARREF && checker.getGlobals().containsKey(node.text)) {
            // A global may reference an earlier global (unlike a local
            // static, which uses a fully empty scope) -- fold through to
            // that global's own already-folded value rather than
            // printing its bare name.
            return foldStaticConstant(checker.getGlobalRhs().get(node.text));
        }
        return literalValueOf(node);
    }

    /** Returns the merged 'let' token if this statement is a `let` declaration, else null. */
    private Token letDeclarationOf(Token stmt) {
        if (stmt.type == TokenType.OPERATOR && stmt.text.equals("=")
                && stmt.left.type == TokenType.KEYWORD && stmt.left.text.equals("let")) {
            return stmt.left;
        }
        return null;
    }

    // ---- whole-function ALLOC hoisting -----------------------------------

    /**
     * Recursively walks a function's entire body -- every nested
     * 'if'/'match'/'loop'/'for'/'lock'/'unsafe'/'safe'/'assume'
     * block, at any depth -- collecting one `Runnable` per
     * ALLOC/ALLOC_STATIC this function will ever need to emit, in the
     * exact same program order `emitBlock` and friends would otherwise
     * have emitted them in, but without emitting anything itself.
     * `emitFuncUnderName` runs every collected static `Runnable` first,
     * then the reserved `gt_routine_address` slot, then every collected
     * ordinary `Runnable` -- so the actual initialization each
     * declaration still needs (an ASSIGN, emitted separately by
     * `emitBlock`/`emitPreLoopInit`/`emitForLoop` at the declaration's
     * original position) is the only thing left where the source put
     * it; the frame-reservation itself is genuinely, textually grouped
     * at the top of the function's own bytecode.
     */
    private void collectHoistedAllocs(List<Token> lines, List<Runnable> staticAllocs, List<Runnable> ordinaryAllocs) {
        for (Token lineTok : lines) {
            Token stmt = lineTok.childs.get(0);
            Token letTok = letDeclarationOf(stmt);
            if (letTok != null) {
                Token nameTok = letTok.childs.get(0);
                if (letTok.isStatic) {
                    Token assignNode = stmt;
                    staticAllocs.add(() -> emitStaticAlloc("ALLOC_STATIC", nameTok.text, letTok.resolvedType,
                            assignNode.right));
                } else {
                    ordinaryAllocs.add(() -> line("ALLOC " + nameTok.text + " " + letTok.resolvedType));
                }
                // A 'let' declaration's own right-hand side can itself be
                // a "try...catch(e){...}" ("let x = try foo() catch(e)
                // {...}") -- `letDeclarationOf` matching above must never
                // skip hoisting *that* catch's own bound parameter (and
                // anything declared inside its body), so this check runs
                // unconditionally alongside it, not instead of it.
                Token tryNodeUnderLet = tryNodeOf(stmt);
                if (tryNodeUnderLet != null) {
                    collectTryHoistedAllocs(tryNodeUnderLet, staticAllocs, ordinaryAllocs);
                }
                continue;
            }
            // Found and fixed while adding real runtime backing for
            // `catch(e){...}`'s own bound parameter: a "try...catch(e)
            // {...}" was previously never hoisted through *any* path --
            // `collectHoistedAllocsFromStatement`'s own switch only ever
            // sees a KEYWORD-typed `stmt`, but the two assignment shapes
            // ("let x = try..." above, and the plain reassignment "x =
            // try..." here -- `stmt.type == OPERATOR "="` in both cases)
            // never reached it at all, and even the bare "try...catch(e)
            // {...}" statement form's own nested `let`s were silently
            // never hoisted either. `tryNodeOf` recognizes all three
            // shapes uniformly (see its own doc comment), so checking it
            // once, right here, ahead of the ordinary switch dispatch,
            // closes the gap for every shape at once.
            Token tryNode = tryNodeOf(stmt);
            if (tryNode != null) {
                collectTryHoistedAllocs(tryNode, staticAllocs, ordinaryAllocs);
                continue;
            }
            collectHoistedAllocsFromStatement(stmt, staticAllocs, ordinaryAllocs);
        }
    }

    /** Shared by both `collectHoistedAllocs` call sites that find a "try" node (under a 'let', or standalone): hoists the catch's own bound parameter (`ALLOC <catchParamName> static_imut_string`, initialized later by `emitHoistedCatchBlocks`'s own one-time snapshot write) plus, recursively, anything the catch body itself declares. */
    private void collectTryHoistedAllocs(Token tryNode, List<Runnable> staticAllocs, List<Runnable> ordinaryAllocs) {
        ordinaryAllocs.add(() -> line("ALLOC " + tryNode.catchParamName + " static_imut_string"));
        collectHoistedAllocs(tryNode.childs, staticAllocs, ordinaryAllocs);
    }

    /** The non-'let' half of `collectHoistedAllocs` -- recurses into whichever nested block(s), if any, this statement carries, plus the two implicit shapes ('for's hidden range/loop variables, a 'loop's own preLoopInit) that declare a local without going through an ordinary 'let' line at all. */
    private void collectHoistedAllocsFromStatement(Token stmt, List<Runnable> staticAllocs,
            List<Runnable> ordinaryAllocs) {
        if (stmt.type != TokenType.KEYWORD) {
            return;
        }
        switch (stmt.text) {
            case "if":
            case "match":
                for (Token b = stmt; b != null; b = b.right) {
                    collectHoistedAllocs(b.childs, staticAllocs, ordinaryAllocs);
                }
                return;
            case "loop":
                if (stmt.preLoopInit != null) {
                    for (Token assign : stmt.preLoopInit) {
                        Token letTok = assign.left;
                        Token nameTok = letTok.childs.get(0);
                        ordinaryAllocs.add(() -> line("ALLOC " + nameTok.text + " " + letTok.resolvedType));
                    }
                }
                collectHoistedAllocs(stmt.childs, staticAllocs, ordinaryAllocs);
                return;
            case "for": {
                Token rangeAssign = stmt.sub.get(0);
                Token loopVarAssign = stmt.sub.get(1);
                ordinaryAllocs.add(() -> line(
                        "ALLOC " + rangeVarNameOf(rangeAssign) + " " + rangeAssign.left.resolvedType));
                ordinaryAllocs.add(() -> line("ALLOC " + loopVarAssign.left.childs.get(0).text + " "
                        + loopVarAssign.left.resolvedType));
                collectHoistedAllocs(stmt.childs, staticAllocs, ordinaryAllocs);
                return;
            }
            case "unsafe":
            case "safe":
            case "lock":
                collectHoistedAllocs(stmt.childs, staticAllocs, ordinaryAllocs);
                return;
            case "assume":
                if (stmt.hasBlock) {
                    collectHoistedAllocs(stmt.childs, staticAllocs, ordinaryAllocs);
                }
                return;
            case "try":
                // Only the plain "try { ... }" block form (`isTryBlock`)
                // is ever reached through this switch -- a throwing
                // "try...catch(e){...}" is always intercepted earlier, by
                // `collectHoistedAllocs`'s own `tryNodeOf` check (see its
                // doc comment for why `tryNodeOf` now excludes this
                // shape), before this method is ever called for one.
                // Compiles to nothing of its own (see `emitStatement`'s
                // own "try" case), so there's nothing to hoist here
                // beyond its own body's declarations.
                collectHoistedAllocs(stmt.childs, staticAllocs, ordinaryAllocs);
                return;
            default:
                return;
        }
    }

    // ---- whole-function catch-block hoisting -------------------------------

    /**
     * "The catch block is hoisted to the top of the stack frame, so like
     * how before in the stackframe the first command was to jump over
     * the centralised gt routine, back when there was one, now the
     * command will be to jump over the catch blocks. The catch blocks
     * just have normal code in them," confirmed directly. Mirrors
     * `emitCallSiteUnwindLandingPads`'s own "JMP skipLabel / pads... /
     * skipLabel:" shape, but placed at the *start* of the function's
     * real body (right after the gt_routine prologue, before a single
     * ordinary statement is emitted) rather than at the end right before
     * "FUNC_END" -- this is the literal "first command" the doc comment
     * above describes. Every "try" token anywhere in this function's
     * body, at any nesting depth (including inside another catch
     * block's own body -- "the catch blocks just have normal code in
     * them," which can itself contain a nested "try"), is collected up
     * front here via `collectTryCatchNodes`, given its own fresh label,
     * and its catch block emitted here, once, ahead of the real body.
     * `tryCatchLabels` is populated before any of this function's own
     * statements are emitted, so `emitTryGuardedCall` (reached from
     * `emitExpr`'s/`emitStatement`'s own "try" case) always finds its
     * label already assigned by the time it redirects the wrapped
     * call's own staging write here (`stageCallSiteForUnwindNames`'s own
     * `pendingTryCatchLabel` handling) -- the real unwind-into-catch
     * wiring, not merely a structural placeholder any more.
     *
     * No trailing `JMP` is emitted after a catch body's own code, and no
     * per-try "where does this resume" label exists at all: "we dont
     * need that special after label. Instead what we need is our catch
     * block to be terminating -- return or throw inside," confirmed
     * directly, and enforced at the structural level -- `TypeChecker.
     * checkTry` now requires every catch body to definitely end in its
     * own `return` or `throw` (reusing the same "does every path
     * through this list of statements definitely end in a return"
     * analysis a function body's own "not every path returns" check
     * already applies). A caught throw is redirected straight into this
     * block (never back through the wrapped call's own normal return
     * path), so once a catch body's own required terminating statement
     * runs (`emitReturn`/`emitThrow`, both already genuinely
     * control-flow-terminating -- `RET`/`EXIT`/`GT_UNWIND`, no
     * fallthrough), there is nothing left for this block to jump to:
     * it never falls off its own bottom. This also fully resolves what
     * was previously an open question ("what value does `x` get in
     * `let x = try foo() catch{...}`, on the caught path?") -- there is
     * no such path any more: reaching the code after a `try`/`catch`
     * statement now categorically means the call returned normally,
     * never that it threw and was caught, so `x` is always the real
     * return value there, nothing else. Still open: lock release during
     * an unwind through a `try` (the pre-existing "no lock release
     * during unwind" gap this project's history already documents for
     * `throw`/call-site landing pads generally, unrelated to this).
     */
    private void emitHoistedCatchBlocks(TypeChecker.FuncInfo info) {
        List<Token> tryNodes = new ArrayList<>();
        collectTryCatchNodes(info.funcToken.childs, tryNodes);
        if (tryNodes.isEmpty()) {
            return;
        }
        for (Token tryNode : tryNodes) {
            tryCatchLabels.put(tryNode, newLabel("catch"));
        }
        String skipLabel = newLabel("end_of_catches__" + currentFuncMangledName);
        line("JMP " + skipLabel);
        for (Token tryNode : tryNodes) {
            line(tryCatchLabels.get(tryNode) + ":");
            // One-time snapshot of this frame's own `gt_error_message`
            // slot into the catch's own bound parameter -- see
            // `Token.catchParamName`'s own doc comment for why this is
            // a copy into an independent local (hoisted below, in
            // `collectHoistedAllocsFromStatement`'s new "try" case),
            // never a live alias to the shared slot. Emitted first,
            // before a single statement of the catch body's own code,
            // so `e` already holds the right value for every
            // subsequent read exactly like an ordinary local would.
            line("ADDR " + tryNode.catchParamName + " static_imut_string");
            line("PUSH gt_error_message static_imut_string");
            line("ASSIGN static_imut_string static_imut_string static_imut_string");
            emitBlock(tryNode.childs);
            // Deliberately no `emitDestructList`/trailing `JMP` here --
            // `TypeChecker.checkTry` guarantees this block's own last
            // statement is a `return` or `throw`, and both already emit
            // their own full destruct list (`returnTok.destructOnExit`/
            // `throwTok.destructOnExit`) and their own genuinely
            // terminating instruction; there is no "falling off the
            // end" case left for this method to handle.
        }
        line(skipLabel + ":");
    }

    /** Recurses into whichever nested block(s), if any, each statement in `lines` carries -- the identical traversal shape `collectHoistedAllocsFromStatement` already uses -- collecting every "try" token found, in program order. */
    private void collectTryCatchNodes(List<Token> lines, List<Token> collected) {
        for (Token lineTok : lines) {
            Token stmt = lineTok.childs.get(0);
            Token tryNode = tryNodeOf(stmt);
            if (tryNode != null) {
                collected.add(tryNode);
                // "the catch blocks just have normal code in them" -- which
                // can itself contain another "try", so recurse into this
                // one's own catch body too.
                collectTryCatchNodes(tryNode.childs, collected);
                continue;
            }
            if (stmt.type != TokenType.KEYWORD) {
                continue;
            }
            switch (stmt.text) {
                case "if":
                case "match":
                    for (Token b = stmt; b != null; b = b.right) {
                        collectTryCatchNodes(b.childs, collected);
                    }
                    break;
                case "loop":
                case "for":
                case "unsafe":
                case "safe":
                case "lock":
                    collectTryCatchNodes(stmt.childs, collected);
                    break;
                case "try":
                    // Only the plain "try { ... }" block form ever
                    // reaches here -- a throwing "try...catch(e){...}"
                    // is already caught by `tryNodeOf` above, before
                    // this switch even runs. Recurse into its own body
                    // regardless, so a real try/catch nested inside it
                    // is still discovered and hoisted.
                    collectTryCatchNodes(stmt.childs, collected);
                    break;
                case "assume":
                    if (stmt.hasBlock) {
                        collectTryCatchNodes(stmt.childs, collected);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * The `collectTryCatchNodes` traversal template, adapted to a different
     * job: rather than collecting real try/catch nodes to hoist, this walks
     * the whole function body once, tracking a stack of lexically enclosing
     * "try { ... }" blocks (`isTryBlock`), so that:
     *   (a) every "try { ... }" block gets a fresh label, recorded into
     *       `tryBlockLabels`, BEFORE any of its own (possibly hoisted)
     *       catch bodies are emitted, and
     *   (b) every 'continue' statement gets its target label -- the
     *       label of its nearest lexically enclosing try-block -- recorded
     *       into `continueTargetLabels`, resolved here rather than at
     *       emission time, since a 'continue' living inside a real catch
     *       body is emitted from `emitHoistedCatchBlocks`, BEFORE the
     *       function's own real body (where the try-block itself lexically
     *       sits, and where a live push/pop stack would normally be
     *       updated) is ever reached. See `emitContinue`'s own doc comment
     *       for the full story of why a live stack can't work here.
     *
     * This must recurse into a real try/catch's own catch body too (via
     * `tryNodeOf`, exactly like `collectTryCatchNodes` does) -- that's
     * exactly where a 'continue' statement lives.
     */
    private void collectTryBlockLabels(List<Token> lines, Deque<Token> enclosingTryBlocks) {
        for (Token lineTok : lines) {
            Token stmt = lineTok.childs.get(0);
            Token tryNode = tryNodeOf(stmt);
            if (tryNode != null) {
                collectTryBlockLabels(tryNode.childs, enclosingTryBlocks);
                continue;
            }
            if (stmt.type != TokenType.KEYWORD) {
                continue;
            }
            switch (stmt.text) {
                case "if":
                case "match":
                    for (Token b = stmt; b != null; b = b.right) {
                        collectTryBlockLabels(b.childs, enclosingTryBlocks);
                    }
                    break;
                case "loop":
                case "for":
                case "unsafe":
                case "safe":
                case "lock":
                    collectTryBlockLabels(stmt.childs, enclosingTryBlocks);
                    break;
                case "try":
                    // Only the plain "try { ... }" block form ever reaches
                    // here -- a throwing "try...catch(e){...}" is already
                    // caught by `tryNodeOf` above. Assign its label up
                    // front, before recursing, so a 'continue' anywhere
                    // inside it (including inside a nested real catch
                    // body) can resolve against it immediately.
                    tryBlockLabels.put(stmt, newLabel("after_try_block"));
                    enclosingTryBlocks.push(stmt);
                    collectTryBlockLabels(stmt.childs, enclosingTryBlocks);
                    enclosingTryBlocks.pop();
                    break;
                case "assume":
                    if (stmt.hasBlock) {
                        collectTryBlockLabels(stmt.childs, enclosingTryBlocks);
                    }
                    break;
                case "continue":
                    if (!enclosingTryBlocks.isEmpty()) {
                        continueTargetLabels.put(stmt, tryBlockLabels.get(enclosingTryBlocks.peek()));
                    }
                    // If the stack is empty, this 'continue' has no
                    // enclosing try-block -- already rejected by
                    // TypeChecker, so nothing further to do here.
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Returns the "try" token a statement carries, whether the statement
     * IS one (the bare "try foo() catch{...}" statement form) or is a
     * "let x = try foo() catch{...}"/"x = try foo() catch{...}"
     * assignment whose right-hand side is one -- the only two shapes
     * `Parser.gatherTryCatchSpan` and `TypeChecker.checkTry` ever
     * produce (see the whole-line-consuming constraint on the grammar
     * itself: "try ... catch { ... }" always ends its own line, so it's
     * never embedded any deeper inside a larger expression than this).
     * Null if `stmt` carries no "try" at all.
     *
     * UPDATE (throwing 'new'): now also sees through a leading `mut`/
     * `imut` wrapper directly around the "try" (`let p = mut try new
     * Point{...} catch(e){...}`) -- a genuinely new combination a
     * throwing CALL never needed (a callee's own declared return type
     * is never indeterminate, so nothing ever had to wrap a `try` in
     * `mut`/`imut` before), but a `new`'s own operand can be, exactly
     * like any other bare literal/struct-literal elsewhere in the
     * language -- so `try`'s own resolved type can likewise need that
     * same wrapper, sitting *outside* the whole `try...catch`, not
     * inside it. Without this, this statement's own "try" would go
     * entirely undiscovered -- no hoisted catch body, no
     * `tryCatchLabels` entry, silently wrong (not even an error)
     * bytecode.
     */
    private Token tryNodeOf(Token stmt) {
        // A plain "try { ... }" block (`isTryBlock`) shares this same
        // KEYWORD text but is a completely different construct -- no
        // wrapped call, no catch, no `pendingTryCatchLabel`/hoisted-
        // catch-block machinery involved at all (see `emitStatement`'s
        // own "try" case) -- so it's excluded here, the same way this
        // method already only ever matches the throwing shape.
        if (stmt.type == TokenType.KEYWORD && stmt.text.equals("try") && !stmt.isTryBlock) {
            return stmt;
        }
        if (stmt.type == TokenType.OPERATOR && stmt.text.equals("=") && stmt.right != null) {
            Token rhs = stmt.right;
            while (rhs.type == TokenType.OPERATOR && (rhs.text.equals("mut") || rhs.text.equals("imut"))
                    && rhs.left != null) {
                rhs = rhs.left;
            }
            if (rhs.type == TokenType.KEYWORD && rhs.text.equals("try") && !rhs.isTryBlock) {
                return rhs;
            }
        }
        // "return ?f(x)" / "return try f(x) catch(e){...}": the returned expression is the try (through any mut/imut wrapper).
        // Without this the catch was never hoisted or labelled, and the call was emitted as a plain one (the catch silently ignored).
        if (stmt.type == TokenType.KEYWORD && stmt.text.equals("return") && stmt.sub != null && stmt.sub.size() == 1) {
            Token rhs = stmt.sub.get(0);
            while (rhs.type == TokenType.OPERATOR && (rhs.text.equals("mut") || rhs.text.equals("imut"))
                    && rhs.left != null) {
                rhs = rhs.left;
            }
            if (rhs.type == TokenType.KEYWORD && rhs.text.equals("try") && !rhs.isTryBlock) {
                return rhs;
            }
        }
        return null;
    }

    // ---- statements -----------------------------------------------------

    private void emitStatement(Token stmt) {
        if (stmt.type == TokenType.KEYWORD) {
            switch (stmt.text) {
                case "if":
                    emitIfChain(stmt);
                    return;
                case "loop":
                    emitLoop(stmt);
                    return;
                case "for":
                    emitForLoop(stmt);
                    return;
                case "match":
                    emitMatchChain(stmt);
                    return;
                case "lock":
                    // "lock EXPR{...}" -- EXPR.lock() / body / EXPR.
                    // unlock(), with the unlock() call also guaranteed
                    // on every return/break inside the body (each
                    // already carries its own unlockOnExit, emitted at
                    // its own emitReturn/emitBreak site).
                    emitExpr(stmt.lockAcquireCall);
                    emitBlock(stmt.childs);
                    emitDestructList(stmt.destructOnExit, stmt);
                    emitUnlockList(stmt.unlockOnExit, stmt);
                    return;
                case "unsafe":
                case "safe":
                    // "These blocks are not reflected in the bytecode
                    // output," confirmed directly -- purely a compile-
                    // time safety-context marker (see TypeChecker's
                    // Scope.currentSafety/checkSafetyCallGraph), nothing
                    // to emit for the wrapper itself, just its own
                    // statements.
                    emitBlock(stmt.childs);
                    emitDestructList(stmt.destructOnExit, stmt);
                    return;
                case "assume":
                    // "This just effects the validation of the code
                    // during the type checking stage, the bytecode
                    // emitted is as if the assume match doesn't exist,"
                    // confirmed directly -- for the block form, exactly
                    // the same "no wrapper, just its own statements"
                    // shape 'unsafe'/'safe' just above already has (no
                    // JMP, no condition ever evaluated at runtime). The
                    // no-block form has no `stmt.childs` at all --
                    // there is nothing nested under it to emit here;
                    // whatever statements follow it in source are
                    // already direct siblings in the *surrounding*
                    // block's own emitBlock loop, not children of this
                    // token, so nothing further is needed for that case
                    // either.
                    if (stmt.hasBlock) {
                        emitBlock(stmt.childs);
                        emitDestructList(stmt.destructOnExit, stmt);
                    }
                    return;
                case "return":
                    emitReturn(stmt);
                    return;
                case "throw":
                    emitThrow(stmt);
                    return;
                case "try":
                    // Two, unrelated shapes share this one KEYWORD text
                    // (Token.isTryBlock tells them apart -- see its own
                    // doc comment). A plain "try { ... }" block compiles
                    // to nothing of its own -- "it compiles to nothing
                    // in the hob and lob," confirmed directly -- the
                    // exact same "no wrapper, just its own statements"
                    // shape 'unsafe'/'safe' just above already have,
                    // *except* for one label: a nested catch's own
                    // 'continue' needs somewhere to jump to (see
                    // `emitContinue`). That label is precomputed by
                    // `collectTryBlockLabels`, before this function's own
                    // body (and its hoisted catch blocks) are emitted --
                    // NOT generated fresh here -- because a real catch
                    // body containing 'continue' is hoisted to the top of
                    // the function (`emitHoistedCatchBlocks`), reached
                    // before this try-block's own lexical position is
                    // ever walked; a fresh label generated only when
                    // control reaches here would already be too late.
                    // Otherwise, this is the bare "try foo() catch(e) {
                    // ... }" statement form -- same shape as emitExpr's
                    // own "try" case (reached for the expression-position
                    // form, e.g. "let x = try foo() catch(e){...}"), just
                    // without an "ADDR"/"ASSIGN" around it: the call's
                    // return value, if any, is simply left on the stack,
                    // same as any other plain expression-statement (see
                    // the "plain expression statement" comment at the
                    // bottom of this method).
                    if (stmt.isTryBlock) {
                        emitBlock(stmt.childs);
                        emitDestructList(stmt.destructOnExit, stmt);
                        line(tryBlockLabels.get(stmt) + ":");
                        return;
                    }
                    emitTryGuardedCall(stmt);
                    return;
                case "break":
                    emitBreak(stmt);
                    return;
                case "continue":
                    emitContinue(stmt);
                    return;
                case "yield":
                    // "Never any arguments, but becomes: YIELD... in the
                    // bytecode, which will produce the assembly
                    // necessary to yield the current thread," confirmed
                    // directly -- a single, bare mnemonic, no operands.
                    line("YIELD");
                    return;
                case "ASM":
                    // Named: nothing here -- see the "ASM" case in
                    // emit()'s own root-level dispatch for the identical
                    // reasoning (self-emits only at each "asm_name;"
                    // invocation site instead). Unnamed: this
                    // declaration point is the only place it can ever
                    // be emitted.
                    if (stmt.sub.isEmpty()) {
                        emitAsm(stmt.resolvedAsmContent);
                    }
                    return;
                default:
                    throw new IllegalStateException("unexpected statement keyword: " + stmt.text);
            }
        }
        if (stmt.type == TokenType.VARREF && stmt.resolvedAsmContent != null) {
            // "asm_name;" -- TypeChecker already validated this exact
            // statement as a real ASM invocation (resolvedAsmContent is
            // only ever set there, and only for a genuine one); an
            // ordinary bare-variable-read statement (ordinary VARREF,
            // resolvedAsmContent left null) falls through to the plain
            // expression-statement handling below completely unchanged.
            emitAsm(stmt.resolvedAsmContent);
            return;
        }
        // plain expression statement (its result, if any, is simply left
        // on the stack -- v1 doesn't emit a POP/discard for statement-level
        // expression results; acceptable per the project's stated priority
        // of correctness over efficiency at this stage).
        emitExpr(stmt);
    }

    private void emitIfChain(Token ifTok) {
        String endLabel = newLabel("end_of_if");
        List<Token> branches = new ArrayList<>();
        for (Token b = ifTok; b != null; b = b.right) {
            branches.add(b);
        }

        for (int i = 0; i < branches.size(); i++) {
            Token branch = branches.get(i);
            boolean isLast = (i == branches.size() - 1);
            String nextLabel = isLast ? endLabel : newLabel("if_branch");
            boolean hasCondition = !branch.sub.isEmpty();

            if (hasCondition) {
                Token cond = branch.sub.get(0);
                if (isThenVariant(cond)) {
                    // "&&then"/"||then" as the condition's own root
                    // operator -- see emitShortCircuit's own doc comment
                    // for the full cascading-jump design this replaces
                    // the ordinary eager "emitExpr; CMP; JMP" pair with.
                    emitShortCircuit(cond, nextLabel, null);
                } else {
                    emitExpr(cond);
                    line("CMP");
                    line("JMP " + nextLabel);
                }
            }
            emitBlock(branch.childs);
            emitDestructList(branch.destructOnExit, branch);
            line("JMP " + endLabel);
            if (!isLast) {
                line(nextLabel + ":");
            }
        }
        line(endLabel + ":");
    }

    /**
     * "A match, in the bytecode disolves to CMP and JMP as if it were an
     * if," confirmed directly -- a single conditional block, no elseif/
     * else chain, so this is exactly one branch of emitIfChain's own
     * shape rather than the full loop over a chain. The condition
     * itself (an "instanceof"/"implements"/"in" operator, an "and"-
     * chain of those, or a bare expression) is emitted completely
     * normally through the ordinary emitExpr dispatch -- nothing match-
     * specific about how any of those individual pieces get emitted,
     * only the CMP/JMP wrapper around the whole thing is new. The bare-
     * expression condition specifically needs one new instruction
     * (GT_ALIVE_CHECK, confirmed directly by name) to turn whatever was
     * pushed into the bool CMP itself needs -- "for now we must set the
     * default behaviour even if it is not practical on its own," so this
     * always confirms alive, matching TypeChecker.checkMatch's own "no
     * real liveness tracking exists yet" scope for this round.
     */
    /**
     * "I want chains, else match and elsematch," confirmed directly --
     * a direct mirror of emitIfChain's own jump structure (a shared
     * endLabel, each branch falling through to it once done, an
     * intermediate label per non-final branch to jump to on failure),
     * using emitMatchCondition in place of plain emitExpr+CMP for each
     * branch's own condition, since match conditions need the special
     * instanceof/implements/in/GT_ALIVE_CHECK/IS_BASE handling ordinary
     * boolean expressions don't. A bare 'else' branch (no condition at
     * all) unconditionally falls into its own block, exactly like an
     * 'if' chain's own trailing 'else' already does.
     */
    private void emitMatchChain(Token matchTok) {
        String endLabel = newLabel("end_of_match");
        List<Token> branches = new ArrayList<>();
        for (Token b = matchTok; b != null; b = b.right) {
            branches.add(b);
        }

        for (int i = 0; i < branches.size(); i++) {
            Token branch = branches.get(i);
            boolean isLast = (i == branches.size() - 1);
            String nextLabel = isLast ? endLabel : newLabel("match_branch");
            boolean hasCondition = !branch.sub.isEmpty();

            if (hasCondition) {
                emitMatchCondition(branch.sub.get(0));
                line("CMP");
                line("JMP " + nextLabel);
            }
            emitBlock(branch.childs);
            emitDestructList(branch.destructOnExit, branch);
            line("JMP " + endLabel);
            if (!isLast) {
                line(nextLabel + ":");
            }
        }
        line(endLabel + ":");
    }

    /** Emits a match condition's bool result onto the stack -- ordinary operator emission for instanceof/implements/in, plus GT_ALIVE_CHECK for the bare form, recursing through "and" so every leaf (whichever kind) gets the right treatment before being combined. */
    private void emitMatchCondition(Token node) {
        if (node.type == TokenType.OPERATOR && node.text.equals("and")) {
            emitMatchCondition(node.left);
            emitMatchCondition(node.right);
            line("AND " + node.left.resolvedType + " " + node.right.resolvedType + " " + node.resolvedType);
            return;
        }
        if (node.someIndexElementExpr != null) {
            // "match Some(i) in/into x{...}" -- sugar for "match i
            // in/into x{ match Some(x[i]){...} }," confirmed directly.
            // Emitted as a single combined boolean (bounds AND
            // element-alive), gating entry into the one wrapped block,
            // rather than two literally nested match blocks --
            // functionally identical (the block only ever runs when
            // both hold), and the simpler of the two shapes to emit
            // correctly. node.left is the "Some(...)" CALL -- its own
            // argument (the plain index) is what actually gets pushed
            // for the bounds check, never the CALL node itself (which
            // would otherwise emit a real, meaningless GT_ALIVE_CHECK
            // on the *index*, not the element).
            Token indexExpr = singleBuiltinArg(node.left);
            emitExpr(indexExpr);
            emitExpr(node.right);
            line("IN " + indexExpr.resolvedType + " " + node.right.resolvedType + " " + node.resolvedType);
            if (inGtSuppressedContext) {
                throw new CompilerException("type", node.file, node.line,
                        "'Some(...)' (which needs '@gt_alive_check') can't be used here -- this "
                                + "function is a ghost-table-decorated function, or is reachable "
                                + "from one, and ghost-table instructions can never be generated "
                                + "in that whole reachable set");
            }
            requireGhostTableFunctionPresent("gt_alive_check", node);
            emitExpr(node.someIndexElementExpr);
            line("GT_ALIVE_CHECK");
            line("AND " + node.resolvedType + " indeterminate_bool " + node.resolvedType);
            return;
        }
        if (node.type == TokenType.OPERATOR
                && (node.text.equals("instanceof") || node.text.equals("implements") || node.text.equals("in")
                        || node.text.equals("within") || node.text.equals("into"))) {
            // "within" as a match condition emits exactly like "within"
            // as a bare expression (PUSH left / PUSH right / WITHIN
            // ...), the same "ordinary expression, plus a proof
            // recorded separately (by checkMatchCondition)" split "in"
            // already has -- confirmed directly, bytecode emission for
            // "within" is identical to "in" throughout. "into" likewise
            // -- "it compiles to the same bytecode as it would if it
            // were an IN operator," confirmed directly -- emitOperator's
            // own "in" case handles "into" identically, zero new
            // mnemonics.
            emitExpr(node);
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left.type == TokenType.VARREF
                && node.left.text.equals("Some")) {
            // "the bytecode is the same," confirmed directly -- emitExpr
            // itself already emits the exact GT_ALIVE_CHECK sequence
            // (emitCallOrBuiltin's own "Some" case) the old bare form
            // used to need this whole function's own fallback for;
            // falling through to that fallback here would emit a
            // second, duplicate GT_ALIVE_CHECK on top of it -- confirmed
            // directly this is exactly what happened before this case
            // existed.
            emitExpr(node);
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("fits")) {
            emitFits(node);
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("is")) {
            // "PUSH x / IS_BASE -- instead of push base and then IS,"
            // confirmed directly: only the left side is ever pushed
            // (there's no real value "base" is, so nothing is pushed
            // for it at all), collapsed into the one instruction. Fully
            // generic over whatever node.left is -- no hardcoded
            // reference to any particular parameter name -- so this
            // needed zero changes when 'is' was retargeted from
            // 'recurse' to 'range'.
            //
            // "IS_BASE leftType" -- not bare "IS_BASE" -- for the same
            // reason DEREF/CLONE both carry their own operand's type
            // directly rather than leaving a later stage to infer it
            // from whatever bytecode line happens to precede them: a
            // real, found bug, confirmed directly by actually compiling
            // "match (2..5) is base{...}" (an inline range *literal* as
            // the match subject -- legal; checkMatchCondition's "is"
            // case only requires `isRangeType(leftType)`, never that
            // `node.left` itself be a bare VARREF -- that restriction is
            // `isRequiredBaseCaseMatch`'s own, narrower, structural check
            // for a `@recursive` function's *mandatory* base case only,
            // not a general restriction on "is base" itself). A range
            // literal's own two bounds are pushed as two separate,
            // ordinary scalar "PUSH n u64" lines with no consolidating
            // instruction (`emitExpr`'s own ".." case) -- so the line
            // immediately before "IS_BASE" is, in that case, only ever
            // the upper bound's own bare scalar push, whose own trailing
            // type is "u64", never "range(...)" -- there is no line
            // anywhere in the emitted bytecode that ever carries the
            // true left-operand type when it's a composite literal like
            // this. `node.left.resolvedType` (set by `resolveExprType`
            // as an unconditional side effect of type-checking, still
            // holding the *real* operand type here regardless of how
            // many bytecode lines that operand took to emit) is the only
            // place that fact still exists once emission reaches this
            // point -- so it's written down directly, the same "emit the
            // distinguishing fact, don't leave it to be inferred later"
            // rule already applied elsewhere in this project's own
            // history, rather than trusting a later stage to reconstruct
            // it from shape alone.
            emitExpr(node.left);
            line("IS_BASE " + node.left.resolvedType);
            return;
        }
        if (node.type == TokenType.OPERATOR && (node.text.equals("!=") || node.text.equals(">"))
                && node.left.type == TokenType.VARREF && node.right.type == TokenType.INTEGER
                && node.right.text.equals("0")) {
            // The division-safety proof case (checkMatchCondition's own
            // identical shape check) -- an ordinary comparison, emitted
            // exactly the same way any other "!="/">" expression already
            // is; its own boolean *result* was never something a
            // GT_ALIVE_CHECK made any sense to run against (it isn't a
            // pointer), so this never falls through to the generic
            // bare-form path below at all, the same way "in"/
            // "instanceof"/"implements" already don't.
            emitExpr(node);
            return;
        }
        // Unreachable now that the bare form no longer exists at all --
        // checkMatchCondition itself rejects any condition shape that
        // isn't one of the cases already handled above, so type-
        // checking would already have failed before bytecode emission
        // was ever reached for anything landing here.
        throw new IllegalStateException(
                "internal error: unrecognized match condition shape reached bytecode emission: " + node.text);
    }

    private void emitLoop(Token loopTok) {
        String startLabel = newLabel("loop");
        String endLabel = newLabel("loop_end");
        loopEndLabels.add(endLabel);
        loopContinueTargets.add(new String[] { startLabel });
        emitDecorators("LOOP_DECORATE", loopTok.decorators);
        emitPreLoopInit(loopTok.preLoopInit);
        line(startLabel + ":");
        if (loopTok.isLockSpinLoop) {
            lockRetryLabels.add(startLabel);
        }
        emitBlock(loopTok.childs);
        if (loopTok.isLockSpinLoop) {
            lockRetryLabels.remove(lockRetryLabels.size() - 1);
        }
        emitDestructList(loopTok.destructOnExit, loopTok);
        line("JMP " + startLabel);
        line(endLabel + ":");
        loopEndLabels.remove(loopEndLabels.size() - 1);
        loopContinueTargets.remove(loopContinueTargets.size() - 1);
    }

    /**
     * "CLOSED:default"/"CLOSED:default(args)" -- see `Token.
     * preLoopInit`'s own doc comment: a "match @lock" statement (already
     * lowered, by this point, into an ordinary "loop" token) whose
     * CLOSED case invoked a registered "impl default match @lock" policy
     * carries its own pre-loop-init assigns here -- the shared
     * "$lm_tries" per-iteration counter (initialized once to 0) plus one
     * per real configuration parameter the policy declares. Each is
     * emitted exactly *once*, right before the loop's own start label --
     * deliberately *not* through `emitBlock`'s own statement loop,
     * since none of these are direct children of any block this loop's
     * own body ever walks (`emitBlock(loopTok.childs)` never sees them
     * at all) -- an ordinary loop with no default-policy usage has a
     * null list here and this is a complete no-op. Its own ALLOC is no
     * longer emitted here -- `collectHoistedAllocs`'s "loop" case
     * already collected it, ahead of this whole function's other
     * allocations, so only the runtime initialization (the ASSIGN)
     * still belongs at this position.
     */
    private void emitPreLoopInit(List<Token> preLoopInit) {
        if (preLoopInit == null) {
            return;
        }
        for (Token assign : preLoopInit) {
            emitAssign(assign);
        }
    }

    /**
     * "for i in 0..n{ ... }" desugars, in TypeChecker.checkForLoop, into
     * real 'let'/'in'/'++' AST nodes -- so this method's whole job is
     * just to emit each of those four pieces (stashed in forTok.sub)
     * through the *exact same* emission functions an ordinary
     * hand-written equivalent would go through: emitAssign for the two
     * declarations (so the hidden range-binding variable is an
     * ordinary ALLOC'd variable, not a bytecode-string trick), and
     * emitExpr on the 'in' condition dispatches straight through
     * emitOperator's ordinary "in" case -- confirmed directly, the
     * whole point being that the IN opcode is byte-for-byte identical
     * whether it came from a for-loop or an ordinary boolean
     * expression, so nothing downstream (bytecode-to-assembly
     * generation, eventually) needs to know or care which. Likewise
     * the increment reuses the ordinary postfix '++' emission.
     * The only thing genuinely new here is the loop-level control flow
     * itself (labels, the check-at-top CMP/JMP, the jump back) -- and
     * even that reuses the exact same CMP/JMP shape an 'if' branch's
     * own condition check already uses ("if true, fall through; if
     * false, jump away"), plus the same loopEndLabels mechanism an
     * ordinary 'loop' already uses for 'break'.
     * Neither hidden variable's own ALLOC is emitted here any more --
     * `collectHoistedAllocs`'s "for" case already collected both, ahead
     * of this whole function's other allocations -- only their runtime
     * initialization (each own ASSIGN, via `emitAssign`) still belongs
     * at this position.
     */
    private void emitForLoop(Token forTok) {
        Token rangeAssign = forTok.sub.get(0);
        Token loopVarAssign = forTok.sub.get(1);
        Token conditionIn = forTok.sub.get(2);
        Token increment = forTok.sub.get(3);

        emitAssign(rangeAssign);
        emitAssign(loopVarAssign);

        String startLabel = newLabel("for");
        String endLabel = newLabel("for_end");
        loopEndLabels.add(endLabel);
        String[] continueTarget = new String[] { null };
        loopContinueTargets.add(continueTarget);
        emitDecorators("FOR_DECORATE", forTok.decorators, true);
        line(startLabel + ":");
        emitExpr(conditionIn);
        line("CMP");
        line("JMP " + endLabel);
        emitBlock(forTok.childs);
        emitDestructList(forTok.destructOnExit, forTok);
        if (continueTarget[0] != null) {
            line(continueTarget[0] + ":");
        }
        emitExpr(increment);
        line("JMP " + startLabel);
        line(endLabel + ":");
        loopEndLabels.remove(loopEndLabels.size() - 1);
        loopContinueTargets.remove(loopContinueTargets.size() - 1);
    }

    private String rangeVarNameOf(Token rangeAssign) {
        return rangeAssign.left.childs.get(0).text;
    }

    /**
     * 'break' compiles to a jump straight to the enclosing loop's end
     * label. Only valid inside a loop -- already enforced by
     * TypeChecker before bytecode generation ever runs, so an empty
     * loopEndLabels stack here would mean that check was skipped or has
     * a bug, not a legitimate program state.
     */
    private void emitBreak(Token breakTok) {
        if (loopEndLabels.isEmpty()) {
            throw new IllegalStateException(
                    "internal error: 'break' reached bytecode gen outside any loop "
                            + "(should have been caught by TypeChecker)");
        }
        emitDestructList(breakTok.destructOnExit, breakTok);
        emitUnlockList(breakTok.unlockOnExit, breakTok);
        line("JMP " + loopEndLabels.get(loopEndLabels.size() - 1));
    }

    /**
     * 'continue' (only ever legal inside a "catch(e){ ... }" body -- see
     * `TypeChecker.checkStatement`'s own "continue" case) compiles to a
     * jump straight to the nearest enclosing try-block's own end label --
     * conceptually the exact `emitBreak` shape just above (a jump to a
     * boundary-scope's own end label), but resolved through
     * `continueTargetLabels` rather than a live push/pop stack like
     * `loopEndLabels`. A live stack can't work here: a real catch body
     * (where every 'continue' lives) is hoisted to the very top of its
     * function by `emitHoistedCatchBlocks`, emitted BEFORE this function's
     * own real body -- where the try-block itself lexically sits, and
     * where a live stack's own push would normally happen -- is ever
     * walked. So `continueTargetLabels` is precomputed instead, by
     * `collectTryBlockLabels`, once per function, before any of that
     * function's bytecode (hoisted catch bodies included) is emitted --
     * the identical precomputed-map pattern `tryCatchLabels`/
     * `collectTryCatchNodes` already established for the same "hoisted
     * catch body needs to reference a label from its future, not-yet-
     * reached lexical surroundings" problem.
     *
     * A missing entry here means TypeChecker's own "requires this 'catch'
     * to be inside an enclosing 'try { ... }' block" check was skipped or
     * has a bug, not a legitimate program state -- already enforced before
     * bytecode generation ever runs.
     */
    private void emitContinue(Token continueTok) {
        if (continueTok.isLoopContinue) {
            // 'continue' in a user 'for'/'loop': unwind like 'break', then jump to the loop's step / start.
            if (loopContinueTargets.isEmpty()) {
                throw new IllegalStateException(
                        "internal error: loop 'continue' reached bytecode gen outside any loop");
            }
            String[] target = loopContinueTargets.get(loopContinueTargets.size() - 1);
            if (target[0] == null) {
                target[0] = newLabel("for_cont");
            }
            emitDestructList(continueTok.destructOnExit, continueTok);
            emitUnlockList(continueTok.unlockOnExit, continueTok);
            line("JMP " + target[0]);
            return;
        }
        if (continueTok.isLockRetryContinue) {
            // 'continue' in the CLOSED case of a 'match @lock': retry the
            // acquire (the spin loop's start label, after any pre-loop
            // init, so retry counters are not reset). No lock is held.
            if (lockRetryLabels.isEmpty()) {
                throw new IllegalStateException(
                        "internal error: lock-retry 'continue' reached bytecode gen outside a 'match @lock' loop");
            }
            emitDestructList(continueTok.destructOnExit, continueTok);
            emitUnlockList(continueTok.unlockOnExit, continueTok);
            line("JMP " + lockRetryLabels.get(lockRetryLabels.size() - 1));
            return;
        }
        String targetLabel = continueTargetLabels.get(continueTok);
        if (targetLabel == null) {
            throw new IllegalStateException(
                    "internal error: 'continue' reached bytecode gen with no enclosing 'try { ... }' "
                            + "block (should have been caught by TypeChecker)");
        }
        emitDestructList(continueTok.destructOnExit, continueTok);
        emitUnlockList(continueTok.unlockOnExit, continueTok);
        line("JMP " + targetLabel);
    }

    private void emitReturn(Token returnTok) {
        if (returnTok.sub.isEmpty()) {
            emitDestructList(returnTok.destructOnExit, returnTok);
            emitUnlockList(returnTok.unlockOnExit, returnTok);
            line("RET " + returnTok.resolvedType);
            return;
        }
        Token expr = returnTok.sub.get(0);
        if (currentFuncInfo != null && isStructByValueReturn(currentFuncInfo.returnType)) {
            emitStructRvoReturn(expr, returnTok);
            return;
        }
        emitExpr(expr);
        // Evaluated (and, per its own resolvedType, already pushed)
        // before any destructing happens -- the returned value itself is
        // never in this list at all (TypeChecker already excludes it),
        // so nothing here can disturb what's about to be returned.
        emitDestructList(returnTok.destructOnExit, returnTok);
        emitUnlockList(returnTok.unlockOnExit, returnTok);
        line("RET " + returnTok.resolvedType);
    }

    /**
     * Return Value Optimization's callee-side half: writes the returned
     * struct value's fields directly into the caller-owned memory
     * "$ret_dest" points at, instead of leaving a value behind for the
     * caller to copy. Confirmed directly, via a hand-authored bytecode
     * file run through the real, unmodified optimizer/lowerorder/codegen
     * pipeline: "PUSH $ret_dest ptrType" then `emitExpr(expr)` -- entirely
     * unchanged, genuinely oblivious to the fact its result is headed
     * through a pointer rather than into an ordinary local slot, and
     * genuinely oblivious to whether `expr` is a fresh struct literal
     * (its own flat, one-push-per-field sequence) or a bare existing
     * variable (a single "PUSH varName Type" whole-value read) --
     * `TypeChecker.checkReturn` is the only thing restricting which
     * shapes `expr` may take, not this method -- then a single
     * whole-struct "ASSIGN" -- exactly `emitAssign`'s own shape, just
     * with "$ret_dest" standing in for a plain `emitAssignTarget`-computed
     * address. `ASSIGN` was confirmed completely agnostic to both how its
     * own destination address arrived on the stack (an "ADDR name" no
     * differently from a plain already-computed pointer value) AND how
     * many words its own source value arrived in (one combined push same
     * as several flat field pushes, as long as the total width matches),
     * which is the key fact that makes this correct either way.
     *
     * The callee still finishes with "RET raw_mut_StructName", returning
     * the same destination pointer in what real System V/win64 ABI code
     * would leave in RAX -- matching the genuine hidden-sret-pointer
     * convention this feature implements, even though this project's own
     * call sites (`emitStructRvoAssign`) never read it back. "RET"/
     * "PUSH_RET" were already fully generic for any pointer-sized return
     * type before this feature existed (an 'owns'/'raw' struct pointer
     * return was already legal), so this is a genuinely unmodified reuse,
     * not a new instruction shape.
     */
    private void emitStructRvoReturn(Token expr, Token returnTok) {
        String structName = currentFuncInfo.returnType.baseType;
        String ptrType = "raw_mut_" + structName;
        String structType = "mut_" + structName;
        Token unwrapped = unwrapMutWrappers(expr);
        if (unwrapped.isStructRvoCall) {
            // "return foo()" -- see `emitStructRvoForwardCall`'s own doc
            // comment for the full tail-forwarding account.
            emitStructRvoForwardCall(unwrapped, ptrType);
        } else {
            line("PUSH $ret_dest " + ptrType);
            emitExpr(expr);
            line("ASSIGN " + structType + " " + expr.resolvedType + " " + structType);
        }
        emitDestructList(returnTok.destructOnExit, returnTok);
        emitUnlockList(returnTok.unlockOnExit, returnTok);
        line("PUSH $ret_dest " + ptrType);
        line("RET " + ptrType);
    }

    /**
     * UPDATE (per-throw-site unwinding): "THROW string_id" used to carry
     * a second operand -- its own enclosing function's whole-function
     * `gt_routine` label -- and compiled to nothing but a single `jmp`
     * to it. That label is gone entirely now (see `emitGtRoutineBody`'s
     * own doc comment), and a `throw`'s own destination no longer needs
     * a jump *to* anything: since it's always resolved at compile time
     * (this exact lexical point, in this exact function), its own
     * precise destruct-and-unwind sequence is simply emitted inline,
     * right here, immediately after this marker -- mirroring an ordinary
     * `return`'s own shape (push/destruct/terminate, no separate label)
     * far more closely than the old jump-based design ever did.
     *
     * UPDATE (real message propagation): `THROW <operand>` itself
     * remains a single-operand marker line only -- a real backend can
     * still treat it as a no-op/comment, exactly as before -- but it is
     * no longer the only thing this method emits for its own operand.
     * Immediately after it, an ordinary `ADDR`/`PUSH`/`ASSIGN` sequence
     * now actually writes that same operand into this frame's own
     * reserved `gt_error_message` slot (see `emitGtRoutineAlloc`'s own
     * doc comment), which is what `catch(e){...}` ultimately reads --
     * so the thrown value is genuinely live at runtime now, not inert;
     * only `THROW` the marker line itself carries no independent
     * meaning. The actual control transfer is still entirely carried by
     * the ordinary `GT_DESTRUCT`/`GT_UNWIND`/`EXIT`/`EXIT_THREAD` lines
     * that follow, using machinery a real backend already has to
     * implement regardless (return-site and call-site-landing-pad
     * destructs use the identical instructions).
     */
    private void emitThrow(Token throwTok) {
        Token expr = throwTok.sub.get(0);
        String operand = expr.type == TokenType.VARREF ? expr.text : literalValueOf(expr);
        line("THROW " + operand);
        if (inGtSuppressedContext) {
            // "GT instructions removed" inside a gt-suppressed (dundered
            // ghost-table-reachable) function's own duplicate -- the
            // identical reasoning `emitDestructList` already applies
            // (calling 'gt_destruct' from within a path that must never
            // recurse into it). Nothing here is real, allocated frame
            // machinery either: `emitGtRoutineAlloc`/`emitGtRoutineBody`
            // both skip a gt-suppressed function outright, so there is
            // no `gt_routine_address` slot for a 'GT_UNWIND' here to even
            // read. `checkThrow` already forbids 'throw' inside the four
            // real ghost-table functions themselves; this guard covers
            // the (untested, likely never-hit in practice) remaining
            // edge case of a *reachable* function containing 'throw'
            // that also happens to need a dundered duplicate -- strictly
            // safer than the old design, which would have emitted a
            // 'jmp' to a 'gt_routine' label that was never emitted for
            // this exact function either.
            return;
        }
        // The real write: this frame's own `gt_error_message` slot (see
        // `emitGtRoutineAlloc`'s own doc comment) now actually holds the
        // thrown value, an entirely ordinary `ADDR`/`PUSH`/`ASSIGN`
        // sequence -- no new instruction needed, and no special-casing
        // between the two operand shapes `checkThrow` allows: `operand`
        // is already either a string literal's own `string_id` (pushed
        // exactly like any other string constant) or a bare variable's
        // name (pushed exactly like any other variable read -- including
        // a catch's own bound parameter, e.g. "catch(e){ throw e }",
        // whose own current value is simply re-pushed and re-written
        // back into the same slot, correctly re-propagating it further
        // up the chain unchanged). `expr.resolvedType` is already set,
        // by `TypeChecker.checkThrow`'s own `resolveExprType` call --
        // "static_some_imut_string" for a literal, "static_imut_string"
        // for a variable -- the identical type text an ordinary `PUSH`
        // of this same expression would already use anywhere else.
        line("ADDR gt_error_message static_imut_string");
        line("PUSH " + operand + " " + expr.resolvedType);
        line("ASSIGN static_imut_string " + expr.resolvedType + " static_imut_string");
        // Precise, per-throw-site destruct list -- see
        // `TypeChecker.checkThrow`'s own doc comment. `currentFuncInfo`/
        // `currentFuncMangledName` here are the same *emitted* (post-
        // dundering) identity `emitGtRoutineBody`'s own three-way
        // root/async/ordinary ending already keys off of, so a throw
        // inside a gt-decorated function's own dundered duplicate, or
        // inside the real, dundered "main"/"__caspien_main" pair, resolves
        // exactly the same way that ending already does.
        List<String> destructNames = throwTok.destructOnExit != null ? throwTok.destructOnExit
                : Collections.emptyList();
        if (!destructNames.isEmpty()) {
            requireGhostTableFunctionPresent("gt_destruct", throwTok);
        }
        for (String name : destructNames) {
            line("GT_DESTRUCT " + name);
        }
        emitUnlockList(throwTok.unlockOnExit, throwTok);
        if (currentFuncMangledName.equals("main")) {
            line("EXIT");
        } else if (currentFuncInfo != null && currentFuncInfo.isAsync) {
            line("EXIT_THREAD");
        } else {
            line(gtUnwindLine());
        }
    }

    private String newLabel(String prefix) {
        labelCounter++;
        return "@" + prefix + "_" + labelCounter;
    }

    // ---- expressions ------------------------------------------------------

    private void emitExpr(Token node) {
        switch (node.type) {
            case STRING:
            case CHAR:
            case INTEGER:
            case FLOAT:
            case BOOL:
            case NULL:
                line("PUSH " + literalValueOf(node) + " " + node.resolvedType);
                return;
            case VARREF:
                line((isAtomicCanonical(node.resolvedType) ? "ATOMIC_PUSH " : "PUSH ") + node.text + " "
                        + node.resolvedType);
                return;
            case KEYWORD:
                if (node.text.equals("let")) {
                    Token nameTok = node.childs.get(0);
                    line("PUSH " + nameTok.text + " " + node.resolvedType);
                    return;
                }
                if (node.text.equals("try")) {
                    // Expression-position form ("let x = try foo() catch
                    // { ... }", reached here as the "=" operator's own
                    // right-hand side -- see emitAssign, which already
                    // emitted "ADDR x" before calling emitExpr on us and
                    // will emit "ASSIGN ..." right after we return).
                    emitTryGuardedCall(node);
                    return;
                }
                break;
            case DELINEATOR:
                if (node.text.equals("(")) {
                    // transparent grouping parens reached as a plain operand
                    // (CALL's own '(' args holder is handled directly in
                    // emitCall, not through this generic dispatch).
                    if (!node.childs.isEmpty()) {
                        emitExpr(node.childs.get(0));
                    }
                    return;
                }
                if (node.text.equals("[")) {
                    // array literal -- same "value type, no wrapping
                    // instruction" shape as struct instantiation: just
                    // each element's value PUSHed in written order
                    // (unlike a struct literal, there's no member-name
                    // reordering concern here -- an array has no names,
                    // only position, and elements are written in the
                    // exact order they belong on the stack).
                    List<Token> elements = new ArrayList<>();
                    collectCommaArgs(node.childs.get(0), elements);
                    for (Token element : elements) {
                        if (dynLiteralTempNames != null && isOwnsTemporary(element)) {
                            emitOwnsTemporaryViaLocal(element, dynLiteralTempNames);
                        } else {
                            emitExpr(element);
                        }
                    }
                    return;
                }
                break;
            case OPERATOR:
                emitOperator(node);
                return;
            default:
                break;
        }
        throw new IllegalStateException("don't know how to emit " + node.type + "(" + node.text + ")");
    }

    private void emitOperator(Token op) {
        switch (op.text) {
            case "=":
                emitAssign(op);
                return;
            case "+=": case "-=": case "*=": case "/=": case "%=":
                emitCompoundAssign(op);
                return;
            case "+": case "-": case "*": case "/": case "%":
                if (op.unary) {
                    emitUnaryMinus(op);
                } else {
                    emitBinaryArith(op);
                }
                return;
            case "!":
                emitLogicalNot(op);
                return;
            case "++":
                emitIncrement(op);
                return;
            case "--":
                emitDecrement(op);
                return;
            case "&&":
                emitLogical(op, "AND");
                return;
            case "||":
                emitLogical(op, "OR");
                return;
            case "<": case ">": case "<=": case ">=": case "==": case "!=":
                emitComparison(op);
                return;
            case "in": case "into":
                // dedicated "IN" opcode, confirmed directly -- not
                // expressed in terms of any other comparison. "into"
                // reuses this exact case unmodified -- "it compiles to
                // the same bytecode as it would if it were an IN
                // operator," confirmed directly, zero new mnemonics; the
                // only difference between "in" and "into" is entirely a
                // TypeChecker-side, compile-time-only one (which proof
                // kind gets recorded/consulted), invisible here.
                if (op.isGuaranteedEnumMembershipCheck) {
                    // "u64 in GuaranteedEnumName" -- the right side is
                    // a bare enum *type* name, never a value
                    // expression, so it's pushed directly by its own
                    // name (checkGuaranteedEnumMembership already set
                    // op.right.resolvedType to it) rather than walked
                    // through the ordinary emitExpr an rvalue "in"'s
                    // right side gets -- the same "PUSH the name
                    // itself" shape checkInstanceof's own right operand
                    // already uses. Still reuses the plain, generic
                    // "IN" mnemonic, with zero new bytecode: a later
                    // backend derives the actual membership check from
                    // the enum's own already-emitted "ENUM" line, keyed
                    // by this same bare name.
                    emitExpr(op.left);
                    line("PUSH " + op.right.resolvedType);
                    line("IN " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
                    return;
                }
                if (op.isStringMembershipCheck) {
                    // "u64 in string" -- a string has no stored length
                    // (see the "len"/LEN_SCAN precedent for the
                    // identical "no stored length, must scan" shape an
                    // unsafe dynarray already has), so this can never be
                    // the ordinary bounds/length comparison plain "IN"
                    // already covers -- it's a real byte-by-byte runtime
                    // scan instead, kept under its own dedicated
                    // mnemonic ("IN_SCAN") rather than overloading "IN"
                    // with a fourth, differently-shaped runtime meaning,
                    // the identical "different runtime meaning gets its
                    // own mnemonic" precedent "WITHIN" (vs "IN") and
                    // "LEN_SCAN" (vs "LEN") already established.
                    emitExpr(op.left);
                    emitExpr(op.right);
                    line("IN_SCAN " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
                    return;
                }
                emitExpr(op.left);
                emitExpr(op.right);
                line("IN " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
                return;
            case "within":
                // Identical shape to "in" just above (same PUSH-both-
                // sides-then-one-instruction pattern, same trailing
                // operand-type annotations, same single plain mnemonic
                // regardless of pointerness on either side): always
                // "WITHIN", never a separate "PTR_WITHIN". Pointerness
                // used to be decided here at emission time (the retired
                // `op.isPtrWithin` field), but "in" never needed a
                // "PTR_IN" for the identical reason -- dereferencing is
                // handled downstream, in the optimizer's
                // MembershipLoweringPass, which already builds the exact
                // same materialize/dereference machinery for "WITHIN" as
                // it does for "IN". The trailing operand-type text still
                // carries each side's full storage/mutability/baseType
                // exactly as before, so that pass can always tell which
                // operand(s) need dereferencing from the type text alone.
                emitExpr(op.left);
                emitExpr(op.right);
                line("WITHIN " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
                return;
            case "CALL":
                emitCallOrBuiltin(op);
                return;
            case "LOOKUP":
                emitLookup(op);
                return;
            case "as":
                emitAs(op);
                return;
            case ".":
                emitDot(op);
                return;
            case "INSTANTIATE":
                emitInstantiate(op);
                return;
            case "owns": case "ref": case "auto": case "raw": case "static":
                emitAddressOf(op);
                return;
            case "mut": case "imut":
                // "let i = mut 0" -- purely a compile-time mutability
                // retag (see TypeChecker.checkMutabilityOf's coercion
                // lattice), never a runtime effect: the underlying value
                // doesn't change at all, only the type system's view of
                // it, the same "nothing to emit for the wrapper itself"
                // reasoning 'cast' already has for its own compile-time-
                // only scope narrowing.
                emitExpr(op.left);
                return;
            case "new":
                emitNew(op);
                return;
            case "await":
                // "await" itself has no bytecode presence at all -- the
                // wrapped CALL node already knows to emit AWAIT_CALL
                // (its own `asyncCallKind`, set by TypeChecker.checkAwait
                // before this stage ever runs), so this is purely a
                // pass-through.
                emitExpr(op.left);
                return;
            case "par":
                // Same reasoning as "await" just above -- the wrapped
                // CALL node already knows to emit PAR_CALL, and already
                // has its own `resolvedType` overwritten (by
                // TypeChecker.checkPar) to whatever "par" itself
                // resolves to (void, or the synthesized handle type),
                // so emitCallSequence's own PUSH_RET line already comes
                // out right with no special-casing needed here either.
                emitExpr(op.left);
                return;
            case "unsafe":
                // "unsafe dyn([0,1,2,3])" -- zero runtime representation
                // of its own, the identical "no bytecode presence at all"
                // pattern "await"/"par"/"mut"/"imut" already established
                // just above: the wrapped CALL node already carries its
                // own, already-corrected `resolvedType` (set by
                // TypeChecker.checkUnsafeDynWrapper to the unsafe
                // variant), so emitting it directly here needs no
                // special-casing at all -- the ordinary "dyn" builtin
                // emission (NEW <baseType> <count>) already reads that
                // resolvedType/baseType, whichever variant it is.
                emitExpr(op.left);
                return;
            case "atomic":
                // "this doesnt require new bytecode operations because
                // the type data is there in the instruction," confirmed
                // directly -- zero runtime representation of its own,
                // the identical "no bytecode presence at all" pattern
                // "mut"/"imut"/"unsafe" already established just above:
                // the wrapped expression's own resolvedType already
                // carries "atomic" (TypeChecker.checkAtomicWrapper), so
                // every later PUSH/ASSIGN of it already emits that type
                // text with no special-casing needed here at all.
                emitExpr(op.left);
                return;
            case "swap":
                emitSwap(op);
                return;
            case "..":
                // a range is just its two u64 bounds sitting on the
                // stack, back to back -- no instruction of its own,
                // confirmed directly ("the .. operator doesn't need to
                // do anything, they're stacked already").
                emitExpr(op.left);
                emitExpr(op.right);
                return;
            case ",":
                // no COMMA instruction, per spec -- just compile both sides
                emitExpr(op.left);
                emitExpr(op.right);
                return;
            case "instanceof": case "implements":
                // "our operators are just ending up as operators in our
                // bytecode" -- confirmed directly. This flat
                // representation defers *all* real computation detail
                // (sizes, addressing, the actual RTTI tag comparison) to
                // a later bytecode-to-assembly stage, exactly like every
                // other operator here -- it doesn't need the underlying
                // runtime mechanism to already exist, only to name the
                // operation, the same way "x+2" is just "PUSH x / PUSH 2
                // / ADD" long before addition is ever actually wired to
                // real hardware. The right operand is a bare type/
                // interface name, not a value -- pushed directly via its
                // own resolved name (set by checkInstanceof/
                // checkImplementsOperator), not evaluated as an
                // expression.
                //
                // "INSTANCEOF"/"IMPLEMENTS" now also carries the left
                // operand's own resolved type as a trailing operand --
                // the identical "IS_BASE leftType" fix just above, for
                // the identical reason: a real, found bug, confirmed
                // directly by actually compiling "Point{x=1} instanceof
                // Point" (a bare, non-`new` struct *literal* as the left
                // operand -- legal; checkInstanceof only requires
                // `leftType` be struct/interface-typed and, via
                // `requiresAliveProof`, explicitly allows "an inline
                // (no-storage)... struct value," which a bare literal
                // construction is). `emitInstantiate`'s own non-`new`
                // construction path pushes classId plus every member back
                // to back with no consolidating instruction, so the line
                // immediately before this one is, in that case, only ever
                // the *last member's own* scalar push -- its trailing
                // type is that member's own type, never the struct's.
                // `op.left.resolvedType` (an unconditional side effect of
                // `resolveExprType` at check time) is the only place the
                // real operand type still exists by the time emission
                // gets here, so, like `IS_BASE`, it's written down
                // directly rather than left for a later pass to guess at
                // from shape.
                emitExpr(op.left);
                line("PUSH " + op.right.resolvedType);
                line(op.text.toUpperCase() + " " + op.left.resolvedType);
                return;
            case "and":
                // Compiles to exactly the same bytecode as "&&", per the
                // match-condition-chaining round: "&&, but also, my left
                // and right operands are rules for the temporary type
                // system within a match block" -- that extra meaning is
                // entirely a TypeChecker.checkMatch concern (already
                // consumed by the time bytecode emission runs); here
                // it's just AND.
                emitLogical(op, "AND");
                return;
            case "FLOAT_CHECK":
                // "PUSH f / FLOAT_CHECK finite," confirmed directly --
                // a purely compiler-internal operator (never parsed
                // from real source; built only by
                // TypeChecker.lowerFloatMatchToIfChain), so op.right is
                // never a real expression to evaluate here -- its own
                // `.text` already holds the alphabetically-canonical
                // "finite|nan"-style chain string directly.
                emitExpr(op.left);
                // an f64 operand carries its width (the f32 form is unchanged: "FLOAT_CHECK cats")
                line("FLOAT_CHECK " + op.right.text + (op.left.resolvedType != null && (op.left.resolvedType.equals("f64") || op.left.resolvedType.endsWith("_f64")) ? " 8" : ""));
                return;
            default:
                throw new IllegalStateException("unhandled operator in bytecode gen: '" + op.text + "'");
        }
    }

    /**
     * "x = expr / x.y = expr / x[y] = expr... the root of the left hand
     * side of assignment must have the address of the variable,"
     * confirmed directly, refined across a full design conversation to:
     * a pure mnemonic swap at exactly the assignment target's own root
     * -- ADDR replacing PUSH, DOT_LHS replacing the ordinarily-collapsed
     * '.' chain (here, and only here, '.' is emitted as a real,
     * uncollapsed operator, with a new PUSH_FIELDNAME carrying the field
     * name the same way PUSH already carries a variable name), LOOKUP_
     * LHS replacing LOOKUP. Every type argument is identical to what the
     * ordinary read-form instruction would have shown -- "any change to
     * the normal type system can only effect the assignment opcode...
     * our type system should remain unaffected," confirmed directly, so
     * nothing here reads or writes anything TypeChecker produced beyond
     * the same resolvedType fields every other emit function already
     * reads. "It doesn't need to recurse down the chain... foo().x --
     * the foo() part needs to produce its actual value, not the
     * address," confirmed directly -- only this exact root swaps
     * treatment; whatever's on the left of a '.'/LOOKUP root (another
     * '.', a call, anything) is evaluated completely normally via the
     * ordinary emitExpr, collapsing exactly as it always has.
     *
     * A real, previously-unintentional ambiguity in the '.'/LOOKUP root
     * specifically (never the bare-VARREF root above, which was always
     * correct): "x is not simply going to be given a value type, we
     * need to update the frontend of the compiler where it swaps the
     * mnemonic used for x," confirmed directly. Before this fix, a bare
     * VARREF root of a '.'/LOOKUP chain was routed through the ordinary,
     * unmodified emitExpr -- which for a stack-resident (no-storage)
     * root emits an ordinary "PUSH x type," the exact same instruction
     * an unrelated genuine value-read of x already uses -- so the only
     * way anything downstream could tell "here's the base to compute a
     * field/element address from" apart from "read this value" was to
     * peek ahead at the instruction that followed (DOT_LHS/LOOKUP_LHS
     * vs. an ordinary consumer). `emitDotLhsRoot` closes this the same
     * way the doc comment above already describes the bare-assignment-
     * target case: a bare VARREF root whose own resolved type carries no
     * storage modifier gets the identical ADDR-replacing-PUSH mnemonic
     * swap the top-level "x = expr" case already gets, since a
     * stack-resident struct/array's own real, on-stack address is
     * exactly what DOT_LHS/LOOKUP_LHS both need as their base to add an
     * offset to. A pointer-typed root (raw/owns/ref/auto/static) is
     * left completely untouched, still routed through ordinary emitExpr
     * -- its own pushed value already IS the address to use, no swap
     * needed there, the identical "already correct, unambiguous" case
     * `pp.x = z` (`pp` a `raw mut Point`) always was. A root that isn't
     * a bare VARREF at all (a deeper chain, a call result, ...) is
     * likewise left untouched -- "whatever's on the left of a '.'/
     * LOOKUP root... is evaluated completely normally" already covers
     * it, and there is no bare slot for a mnemonic swap to even target.
     *
     * UPDATE (chained-lookup write fix, "grid[1][1] = x"): the "root
     * that isn't a bare VARREF at all" claim above turned out to be
     * incomplete for exactly one shape -- a root that is ITSELF another
     * '.'/LOOKUP node, e.g. the inner "grid[1]" of "grid[1][1] = x"'s
     * target ("LOOKUP" with left="LOOKUP(grid,1)", right="[1]"). Before
     * this fix, that inner root fell through to the plain "emitExpr(root)"
     * call below -- which, for a stack-resident (no-storage) array/struct
     * ELEMENT result, is exactly the ordinary *read* path: per
     * X86Backend's own "LOOKUP_ARRAY" (non-LHS) handling, a fixed-size
     * array/struct-typed read pushes the whole VALUE by copying its
     * bytes onto the stack, never its address ("the read side always
     * gets its base via a plain PUSH <totalArraySize> $offset -- the
     * whole array pushed by value... never an address"). The very next
     * instruction this method's own caller emits, though, is
     * "LOOKUP_LHS", which requires an ADDRESS as its base to add an
     * element offset to -- so the outer write ended up indexing into a
     * copied VALUE block as if it were a pointer, producing a garbage
     * address and corrupting the real stack (confirmed directly: a real
     * "grid[1][1] = 99" fixture segfaulted inside glibc's own
     * malloc/free machinery from the resulting stack corruption).
     *
     * The fix: a '.'/LOOKUP root, like the bare-VARREF root above, also
     * needs its own ADDRESS-producing form when it has no storage
     * modifier -- and that form already exists, recursively, as this
     * exact method's own caller, `emitAssignTarget` (its '.'/LOOKUP
     * branches already emit exactly "compute the base's address, then
     * DOT_LHS/LOOKUP_LHS" -- precisely what's needed here, one level
     * deeper). So a stack-resident '.'/LOOKUP root now recurses into
     * `emitAssignTarget(root)` instead of `emitExpr(root)`, giving a
     * chain of arbitrary depth ("cube[1][0][1] = x") a chain of nested
     * LOOKUP_LHS/DOT_LHS address computations all the way down to the
     * true base, exactly mirroring how the read side
     * (`emitLookup`/`emitDot`) already recurses via plain `emitExpr` on
     * their own left sides. A pointer-typed '.'/LOOKUP root (rare, but
     * possible e.g. through a `raw`-returning accessor) is left routed
     * through ordinary `emitExpr`, matching the pointer-typed-VARREF
     * case above: its own pushed value already IS the address, no
     * recursion needed.
     */
    private void emitDotLhsRoot(Token root) {
        if (root.type == TokenType.VARREF && !hasStorage(root.resolvedType)) {
            line("ADDR " + root.text + " " + root.resolvedType);
            return;
        }
        if (root.type == TokenType.OPERATOR && (root.text.equals(".") || root.text.equals("LOOKUP"))
                && !hasStorage(root.resolvedType)) {
            emitAssignTarget(root);
            return;
        }
        emitExpr(root);
    }

    /** "[storage_]_[mutability]_[basetype]" -- storage, when present, is always the string's own leading segment, immediately followed by '_' (TypeChecker.TypeInfo.canonical()'s own documented ordering). */
    private static boolean hasStorage(String canonicalType) {
        // Null-safe: a node that was never resolved to a real value type at
        // all (an EnumName reference used only as a '.'-chain root, e.g.
        // "Color.GREEN" -- confirmed directly via a real NullPointerException
        // compiling a plain "printf(...)" program that happens to reference
        // an enum-valued static constant indirectly through stdlib -- has no
        // resolvedType, and "no type" trivially carries no storage keyword.
        return canonicalType != null && (canonicalType.startsWith("raw_") || canonicalType.startsWith("owns_")
                || canonicalType.startsWith("ref_") || canonicalType.startsWith("auto_")
                || canonicalType.startsWith("static_"));
    }

    private void emitAssignTarget(Token target) {
        if (target.type == TokenType.OPERATOR && target.text.equals(".")) {
            emitDotLhsRoot(target.left);
            line("PUSH_FIELDNAME " + target.right.text + " " + target.resolvedType);
            line("DOT_LHS " + target.left.resolvedType + " " + target.resolvedType + " " + target.resolvedType);
            return;
        }
        if (target.type == TokenType.OPERATOR && target.text.equals("LOOKUP")) {
            emitDotLhsRoot(target.left);
            Token indexExpr = target.right.childs.get(0);
            emitExpr(indexExpr);
            line("LOOKUP_LHS " + target.left.resolvedType + " " + indexExpr.resolvedType + " "
                    + target.resolvedType);
            return;
        }
        if (target.type == TokenType.KEYWORD && target.text.equals("let")) {
            // A fresh 'let' declaration's own target -- "let" isn't the
            // variable name itself, it's a wrapper holding it as its
            // first child (see emitExpr's own identical handling of this
            // same shape for an ordinary read).
            Token nameTok = target.childs.get(0);
            line("ADDR " + nameTok.text + " " + target.resolvedType);
            return;
        }
        line("ADDR " + target.text + " " + target.resolvedType);
    }

    /**
     * "a=null... immediately after that move... The a=null isn't
     * subject to all the rule checking, it just becomes like: ASSIGN a
     * null," confirmed directly. Emits exactly the bytecode shape a
     * real, user-written "x = null" would (`emitAssignTarget` +
     * "PUSH null type" + "ASSIGN"), just synthesized directly here
     * rather than from real source -- reusing the existing, already-
     * correct machinery for both an ordinary variable and a struct
     * field (`emitAssignTarget` already handles both shapes). Called
     * only where `TypeChecker.markMovedIfOwned` actually set
     * `isOwnershipMoveSource` -- never unconditionally -- since a move
     * whose own source has no nameable slot at all (a fresh `new`/
     * call-result consumed directly) has nothing here to null out.
     *
     * NOTE FOR THE FUTURE BYTECODE-TO-ASSEMBLY GENERATOR: for a call
     * argument specifically (see the ARG-loop call site of this
     * method), this null-out is emitted *between* the argument's own
     * "POP ARGn" and the "CALL" that follows -- deliberately, not
     * after the call returns (a throw inside the callee would skip
     * right past anything placed after the call entirely, leaving the
     * caller's own slot stale when its own unwind routine later reads
     * it). This ordering creates a real constraint on register
     * allocation: this "ASSIGN ... null" sits between the ARGn-th
     * value being placed into its calling-convention register/slot and
     * the CALL that reads it, so whatever real registers this specific
     * ASSIGN's own generated code uses as scratch space MUST NOT be
     * (or must save/restore) any register already holding a still-
     * pending ARG value. Every ASSIGN emitted anywhere else in this
     * bytecode format is safe to treat as "the last operation of its
     * own statement" and free to clobber scratch registers however it
     * likes -- this is the one place that assumption is false, and the
     * assembler needs to know it explicitly rather than discover it as
     * a hard-to-reproduce bug in a throwing program.
     */
    /** A synthesized `base.member` access, enough for `emitAssignTarget`/`DOT_LHS`. */
    private Token syntheticMember(Token base, String member, TypeChecker.TypeInfo memberType) {
        Token dot = new Token(TokenType.OPERATOR, ".", base.line, base.file);
        Token name = new Token(TokenType.VARREF, member, base.line, base.file);
        name.resolvedType = memberType.canonical();
        dot.left = base;
        dot.right = name;
        dot.resolvedType = memberType.canonical();
        return dot;
    }

    /** A synthesized `base[i]` with a literal index, enough for `emitAssignTarget`/`LOOKUP_LHS`. */
    private Token syntheticElement(Token base, int index, String elemCanonical) {
        Token look = new Token(TokenType.OPERATOR, "LOOKUP", base.line, base.file);
        Token idx = new Token(TokenType.INTEGER, Integer.toString(index), base.line, base.file);
        idx.resolvedType = "imut_u64";
        Token holder = new Token(TokenType.DELINEATOR, "[", base.line, base.file);
        holder.childs.add(idx);
        look.left = base;
        look.right = holder;
        look.resolvedType = elemCanonical;
        return look;
    }

    /** Nulls every owned member of the inline struct at `base` (recursing into inline owning members): what a move out of it, or its own drop, leaves behind. */
    private void emitInlineOwnsNullOut(Token base, String structName) {
        if (checker.isFixedArrayTypeText(structName)) {
            // A fixed array of inline owning structs: null every element's owned leaves (unrolled, the length is fixed).
            String elemName = structName.substring(0, structName.lastIndexOf('['));
            int count = Integer.parseInt(structName.substring(structName.lastIndexOf('[') + 1, structName.length() - 1));
            String elemCanon = base.resolvedType.substring(0, base.resolvedType.lastIndexOf(structName)) + elemName;
            for (int i = 0; i < count; i++) {
                emitInlineOwnsNullOut(syntheticElement(base, i, elemCanon), elemName);
            }
            return;
        }
        TypeChecker.StructInfo si = checker.getStructs().get(structName);
        for (Map.Entry<String, TypeChecker.TypeInfo> m : si.members.entrySet()) {
            if (m.getKey().equals("___type")) {
                continue;
            }
            TypeChecker.TypeInfo mt = m.getValue();
            if ("owns".equals(mt.storage)) {
                Token target = syntheticMember(base, m.getKey(), mt);
                emitAssignTarget(target);
                line("PUSH null " + mt.canonical());
                line("ASSIGN " + mt.canonical() + " " + mt.canonical() + " " + mt.canonical());
            } else if (checker.isInlineOwningStruct(mt)) {
                emitInlineOwnsNullOut(syntheticMember(base, m.getKey(), mt), mt.baseType);
            }
        }
    }

    private void emitOwnershipMoveNullOut(Token valueExpr) {
        if (valueExpr.inlineOwnsStruct != null) {
            emitInlineOwnsNullOut(unwrapMutWrappers(valueExpr), valueExpr.inlineOwnsStruct);
            return;
        }
        Token source = unwrapMutWrappers(valueExpr); // `a = mut b`: the variable underneath the wrapper is what gets nulled
        emitAssignTarget(source);
        line("PUSH null " + source.resolvedType);
        line("ASSIGN " + source.resolvedType + " " + source.resolvedType + " " + source.resolvedType);
    }

    /**
     * "now i have a memory leak because the old b never has a
     * GT_DESTRUCT," confirmed directly -- a genuinely different gap
     * from the move-nulling above: that was about a move's own
     * *source* losing track of a value it no longer owns; this is
     * about an assignment's own *target* losing track of a value it
     * owned *before* being overwritten. Fixed by destructing whatever
     * the target currently holds first, before the new value is ever
     * written in -- reusing `gt_destruct`'s own already-established
     * null-safety (confirmed directly: "it just does nothing if you
     * pass it a null") to make this always safe to emit even when the
     * target happens to already be null.
     *
     * Deliberately scoped to *ordinary reassignment only* -- never a
     * fresh `let` (checked via `target.type != KEYWORD`, since a real
     * reassignment target is a VARREF or a '.' field access, never the
     * "let" wrapper token itself). A fresh `let`'s own hoisted `ALLOC`
     * slot only ever needs this if it could be re-executing with a
     * still-live prior value already in it -- the loop-reentry case --
     * and that's already made safe by nulling the slot after its own
     * natural-end-of-scope destruct (a separate, related fix), so a
     * fresh `let` is always either genuinely fresh (slot already null)
     * or a loop reentry (slot already nulled by that other fix) --
     * never a case this destruct would actually need to catch. Applying
     * it to `let` anyway was tried and reverted: it broke every
     * function whose only `owns` locals are immediately returned or
     * otherwise never need destructing at all, by forcing a
     * `@gt_destruct` function to exist even there (confirmed directly
     * by a real fixture regression, not assumed) -- a real, unwanted
     * cost for a case that doesn't need the protection in the first
     * place once the other fix is in place.
     *
     * Returns the target's own dotted name (matching `qualifiedDotName`
     * exactly, so a struct field like "h.inner" is destructed
     * correctly too, not just a bare variable) -- when a flat
     * compile-time name is even possible; see the "GT_DESTRUCT
     * dotted-operand gap" update below for when it isn't.
     *
     * UPDATE ("GT_DESTRUCT dotted-operand gap" fix): the original version
     * of this method built a flat, dot-joined name string
     * (`qualifiedDotName`) for ANY '.' target unconditionally, and simply
     * gave up (no destruct emitted at all -- a silent memory leak, not a
     * compile error) for a LOOKUP target. Both were real gaps with the
     * same root cause: `AddressLoweringPass.resolveAddress` (the
     * lowerorder-stage pass that turns a flat name like "a.b.c" into a
     * single compile-time byte offset) can only do that when EVERY
     * segment of the chain is an ordinary, stack-resident value -- the
     * moment a pointer (`raw`/`owns`/`ref`/`auto`/`static`) sits anywhere
     * in the middle, there is no single fixed offset to fold to at all
     * (confirmed directly in that pass's own code: "return null; //
     * pointer indirection"), and a LOOKUP's own index is a runtime value
     * to begin with, never flat-nameable in the first place. Before this
     * fix, either shape left `GT_DESTRUCT`'s own text operand unresolved,
     * and `X86Backend`'s `GT_DESTRUCT` case has no fallback for that --
     * it just emits a `TODO(codegen): ... not yet implemented` comment
     * and skips the destruct call entirely, silently leaking the old
     * owned value.
     *
     * `isQualifiedNameableDot` is the exact gate `emitDot` (ordinary
     * reads) and `emitAssignTarget` (assignment targets, via
     * `emitDotLhsRoot`) already check before deciding whether a '.'
     * chain is safe to flatten -- this method now checks the identical
     * gate instead of assuming every '.' chain qualifies. When it does
     * (no pointer anywhere in the chain), the original flat-name
     * behavior is unchanged. When it doesn't -- or the target is a
     * LOOKUP, which never qualifies -- there is no flat name to build at
     * all, so this falls back to the OTHER already-correct, already-
     * pointer-crossing mechanism this project has: the same runtime
     * address computation `emitAssignTarget` itself performs via
     * `DOT_LHS`/`LOOKUP_LHS` (proven correct through arbitrary pointer
     * indirection by the chained-array-lookup write fix -- see this
     * file's own CLAUDE.md, "Chained array indexing"). That address is
     * computed via `emitAssignTarget(target)` (pushing it once),
     * duplicated with the existing `DUP_TOP` mnemonic (added for the
     * win64 varargs-duplication fix, reused here for an unrelated
     * purpose -- any 8-byte stack word), and one copy consumed by a new
     * `GT_DESTRUCT_ADDR` bytecode line (an address-based sibling of the
     * existing, flat-offset-only `GT_DESTRUCT`; see `X86Backend`'s own
     * CLAUDE.md for its codegen). The other copy is left on the stack
     * for `emitAssign`'s own subsequent store to consume directly --
     * returning `true` from this method tells `emitAssign` the address
     * is already there, so it must NOT call `emitAssignTarget` again
     * (which would both waste work and, worse, double-evaluate any
     * side-effecting sub-expression inside the target, such as a LOOKUP
     * index expression containing a call).
     */
    private boolean isFlatOwnsDestructTarget(Token target) {
        if (target.type == TokenType.KEYWORD || target.resolvedType == null
                || !(target.resolvedType.startsWith("owns") || target.inlineOwnsStruct != null)) {
            return false;
        }
        return target.type == TokenType.VARREF
                || (target.type == TokenType.OPERATOR && target.text.equals(".") && isQualifiedNameableDot(target));
    }

    /** An `owns` assignment target with no flat name: a field reached through a pointer, or an indexed element. */
    private boolean isPointerCrossingOwnsTarget(Token target) {
        if (target.type == TokenType.KEYWORD || target.resolvedType == null
                || !(target.resolvedType.startsWith("owns") || target.inlineOwnsStruct != null)) {
            return false;
        }
        return target.type == TokenType.OPERATOR && (target.text.equals("LOOKUP")
                || (target.text.equals(".") && !isQualifiedNameableDot(target)));
    }

    private boolean emitDestructOldOwnedValue(Token target) {
        if (target.type == TokenType.KEYWORD || target.resolvedType == null
                || !(target.resolvedType.startsWith("owns") || target.inlineOwnsStruct != null)) {
            return false;
        }
        if (target.inlineOwnsStruct != null && !isFlatOwnsDestructTarget(target)) {
            // an inline owning struct reached through a pointer or an index: drop the members of the value at the computed address
            emitAssignTarget(target);
            line("DUP_TOP");
            requireGhostTableFunctionPresent("gt_destruct", target);
            line("GT_DROP_ADDR " + target.resolvedType);
            return true;
        }
        if (target.type == TokenType.VARREF) {
            requireGhostTableFunctionPresent("gt_destruct", target);
            line("GT_DESTRUCT " + target.text);
            return false;
        }
        if (target.type == TokenType.OPERATOR && target.text.equals(".") && isQualifiedNameableDot(target)) {
            requireGhostTableFunctionPresent("gt_destruct", target);
            line("GT_DESTRUCT " + qualifiedDotName(target));
            return false;
        }
        if (target.type == TokenType.OPERATOR && (target.text.equals(".") || target.text.equals("LOOKUP"))) {
            emitAssignTarget(target);
            line("DUP_TOP");
            requireGhostTableFunctionPresent("gt_destruct", target);
            line("GT_DESTRUCT_ADDR " + target.resolvedType);
            return true;
        }
        return false; // unreached today -- no other assignment-target shape exists
    }

    private void emitAssign(Token op) {
        if (unwrapMutWrappers(op.right).isStructRvoCall) {
            emitStructRvoAssign(op);
            return;
        }
        // A flat-named owns target (a variable, or a pointer-free '.' chain) is destructed
        // AFTER the right side is evaluated: "s = pass(s)" moves the old value into the
        // call, which nulls the variable, so destructing first freed an object the callee
        // was about to use. Destructing late sees the moved-out (null) slot and is a no-op.
        boolean lateDestruct = isFlatOwnsDestructTarget(op.left);
        // An owns target reached through a pointer or an index ("h.w = pass(h.w)", "a[i] = f(a[i])") has no flat name, so
        // its old value is destructed through the computed address. The right side is still evaluated FIRST (into a hidden
        // local): it may move the old value out (the move nulls the slot, so the destruct below is then a no-op), and if it
        // throws, the old value is left untouched instead of freed.
        boolean pointerCrossingOwns = !lateDestruct && lateAllocs != null && op.right.resolvedType != null
                && (op.right.resolvedType.startsWith("owns") || op.left.inlineOwnsStruct != null)
                && isPointerCrossingOwnsTarget(op.left);
        String spilledRight = null;
        if (pointerCrossingOwns) {
            String rightType = op.left.inlineOwnsStruct != null ? op.left.resolvedType : op.right.resolvedType;
            spilledRight = declareHiddenLocal(rightType);
            line("ADDR " + spilledRight + " " + rightType);
            emitExpr(op.right);
            line("ASSIGN " + rightType + " " + rightType + " " + rightType);
        }
        boolean addressAlreadyOnStack = false;
        if (!lateDestruct) {
            addressAlreadyOnStack = emitDestructOldOwnedValue(op.left);
        }
        if (!addressAlreadyOnStack) {
            emitAssignTarget(op.left);
        }
        if (spilledRight != null) {
            line("PUSH " + spilledRight + " " + (op.left.inlineOwnsStruct != null ? op.left.resolvedType : op.right.resolvedType));
        } else {
            emitExpr(op.right);
        }
        if (lateDestruct) {
            emitDestructOldOwnedValue(op.left);
        }
        String assignMnemonic = isAtomicCanonical(op.left.resolvedType) ? "ATOMIC_ASSIGN" : "ASSIGN";
        line(assignMnemonic + " " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
        if (op.right.isOwnershipMoveSource) {
            emitOwnershipMoveNullOut(op.right);
        }
    }

    /**
     * Return Value Optimization's caller-side half: `op.right` is a CALL
     * node `TypeChecker.checkCall` already confirmed targets a function
     * that returns a plain struct by value, sitting in one of the two
     * positions that's legal for it (a fresh 'let' binding's own RHS, or
     * a plain assignment's RHS with a bare-variable target -- enforced
     * entirely by TypeChecker; `op.left` is therefore always a bare,
     * addressable variable name here). No "PUSH_RET"/"ASSIGN" copy
     * happens at all -- instead, the callee is handed the destination's
     * own address directly, as a synthetic leading argument, and writes
     * the struct's fields into it directly (see `emitStructRvoReturn`).
     *
     * The hidden-argument push deliberately mirrors -- byte for byte --
     * the bytecode shape a real, ordinary "raw destVar" call argument
     * already produces ("PUSH destVar itsType" then "ADDR_OF RAW itsType
     * raw_indeterminate_StructName"), confirmed directly via probing
     * already-legal programs: this is the exact, already-proven-legal
     * way to pass a variable's own address as a call argument in this
     * bytecode format, and a "raw_indeterminate_..." argument was already
     * confirmed to satisfy a "raw_mut_..."-declared parameter (an
     * indeterminate-mutability pointer freely coerces to any declared
     * pointee mutability -- the same rule an ordinary "takesPtr(raw p)"
     * call already relied on before this feature existed).
     *
     * Every real, user-written argument is transferred exactly as
     * `emitCall`/`emitCallSequence` already would, just manually inlined
     * here (rather than reusing `emitCallSequence` itself) so the hidden
     * destination pointer can occupy register slot 0 ahead of them --
     * `ArgCounters` is a genuinely running, mutable per-call counter, so
     * simply transferring the hidden argument into it first, before the
     * real arguments' own loop, is all "offsetting by one register"
     * requires; nothing about `emitArgTransfer`/`emitArgWord` needs to
     * know this call is any different from an ordinary one.
     */
    /**
     * "mut x"/"imut x" are purely compile-time mutability retags with no
     * runtime effect at all (see `emitOperator`'s own "mut"/"imut" case) --
     * so a struct-RVO CALL node sitting underneath one (e.g. "let point =
     * mut newPoint()", needed here since a fresh struct literal always
     * has indeterminate mutability and a 'let' RHS requires a concrete
     * one) is still exactly the same call `TypeChecker.checkCall` marked
     * `isStructRvoCall` -- this just walks down through any number of
     * such transparent wrappers to find it, mirroring the "mut"/"imut"
     * transparency `TypeChecker.resolveOperatorType` itself now gives the
     * struct-RVO eligibility flag for the identical reason.
     */
    private Token unwrapMutWrappers(Token t) {
        while (true) {
            if (t.type == TokenType.OPERATOR && (t.text.equals("mut") || t.text.equals("imut"))) {
                t = t.left;
            } else if (t.type == TokenType.KEYWORD && t.text.equals("try") && !t.isTryBlock
                    && t.sub != null && !t.sub.isEmpty()) {
                // `let e = mut ? f()` / `try f() catch(..){..}`: the wrapped call is still the very call
                // TypeChecker flagged `isStructRvoCall` (the flag travels through `try`), so it must get its
                // hidden destination like any other RVO call; `emitStructRvoCallSequence` re-attaches the
                // try's catch label (see `ownerTryOf`).
                t = t.sub.get(0);
            } else {
                return t;
            }
        }
    }

    /** The non-block `try` token (of the function being emitted) whose directly wrapped call is `callNode`, or null. */
    private Token ownerTryOf(Token callNode) {
        for (Token tryTok : tryCatchLabels.keySet()) {
            if (!tryTok.isTryBlock && tryTok.sub != null && !tryTok.sub.isEmpty() && tryTok.sub.get(0) == callNode) {
                return tryTok;
            }
        }
        return null;
    }

    private void emitStructRvoAssign(Token op) {
        Token callOp = unwrapMutWrappers(op.right);
        String funcName = resolveCallTargetForEmission(callOp.resolvedCallTarget);
        TypeChecker.FuncInfo targetInfo = findFuncInfoByMangledName(funcName);
        String structName = targetInfo != null ? targetInfo.returnType.baseType
                : op.left.resolvedType.substring(op.left.resolvedType.lastIndexOf('_') + 1);
        String destVarName = destVarNameOf(op.left);
        String destType = op.left.resolvedType;
        String rawPtrType = "raw_indeterminate_" + structName;
        if (op.left.inlineOwnsStruct != null && lateAllocs != null) {
            // The destination already owns memory (an inline struct with owns members): the callee builds the new value in a
            // hidden local, then the old members are dropped and the value is copied over. A callee that throws leaves the
            // destination untouched, and an argument that moves a member out of it (`h = f(h.w)`) has already nulled it.
            String hidden = declareHiddenLocal(destType);
            emitStructRvoCallSequence(callOp, rawPtrType, () -> {
                line("PUSH " + hidden + " " + destType);
                line("ADDR_OF RAW " + destType + " " + rawPtrType);
            });
            requireGhostTableFunctionPresent("gt_destruct", op.left);
            line("GT_DESTRUCT " + destVarName);
            line("ADDR " + destVarName + " " + destType);
            line("PUSH " + hidden + " " + destType);
            line("ASSIGN " + destType + " " + destType + " " + destType);
            return;
        }
        emitStructRvoCallSequence(callOp, rawPtrType, () -> {
            line("PUSH " + destVarName + " " + destType);
            line("ADDR_OF RAW " + destType + " " + rawPtrType);
        });
        // Deliberately no "PUSH_RET"/"ASSIGN" here -- the destination's
        // own memory has already been populated directly by the callee.
    }

    /**
     * "return foo()" tail-forwarding: `callOp` is a struct-RVO call sitting
     * directly (through any number of "mut"/"imut" wrappers) as a
     * `return` statement's own expression, in a function whose own
     * return type matches. Rather than materialize a fresh destination
     * and then copy it into "$ret_dest" -- extra work this project's
     * flat struct handling doesn't otherwise need -- this function's OWN
     * "$ret_dest" is handed straight to `callOp` as *its* hidden
     * destination pointer: the callee then writes the struct directly
     * into the memory the ORIGINAL, outermost caller is waiting on, with
     * zero intermediate copies, however many "return foo()"-chained
     * frames deep this goes. `$ret_dest` is already a pointer (unlike a
     * plain struct-typed destination variable), so unlike
     * `emitStructRvoAssign`'s own "PUSH destVar; ADDR_OF RAW" (take the
     * address of a struct value), this is just "PUSH $ret_dest" --
     * forward the pointer value itself, no address-of needed.
     */
    private void emitStructRvoForwardCall(Token callOp, String ptrType) {
        emitStructRvoCallSequence(callOp, ptrType, () -> line("PUSH $ret_dest " + ptrType));
    }

    /**
     * The part `emitStructRvoAssign`/`emitStructRvoForwardCall` share:
     * everything about a struct-RVO call site except *how* the hidden
     * destination pointer's own value gets onto the stack in the first
     * place (`pushDestPointer` -- takes an address for a local
     * destination, or forwards an already-a-pointer "$ret_dest" for tail
     * forwarding). Every real, user-written argument is transferred
     * exactly as `emitCall`/`emitCallSequence` already would, just
     * manually inlined here (rather than reusing `emitCallSequence`
     * itself) so the hidden destination pointer can occupy register slot
     * 0 ahead of them.
     */
    private void emitStructRvoCallSequence(Token callOp, String rawPtrType, Runnable pushDestPointer) {
        String funcName = resolveCallTargetForEmission(callOp.resolvedCallTarget);
        List<Token> args = new ArrayList<>();
        Token argsDelineator = callOp.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        String conventionName = callOp.resolvedCallConvention;
        Token ownerTry = ownerTryOf(callOp);
        if (ownerTry != null) {
            pendingTryCatchLabel = tryCatchLabels.get(ownerTry); // consumed by the staging write just below
        }
        stageCallSiteForUnwind(callOp);
        pendingTryCatchLabel = null;
        ArgCounters counters = new ArgCounters(checker.getConfig().callingConventions.get(conventionName));
        line("CC_START " + conventionName);
        pushDestPointer.run();
        emitArgTransferTail(rawPtrType, counters, false);
        for (Token arg : args) {
            emitArgTransfer(arg, counters, false);
            if (arg.isOwnershipMoveSource) {
                emitOwnershipMoveNullOut(arg);
            }
        }
        String mnemonic = funcName.equals(currentFuncMangledName) ? "RECURSIVE_CALL" : "CALL";
        line(mnemonic + " " + funcName);
        line("CC_END " + conventionName);
    }

    /** The bare-variable target's own name -- `op.left` is always a plain VARREF here (a "let" binding's own fresh name, or an ordinary bare-variable assignment target; both are the only two positions `TypeChecker` ever allows a struct-RVO call in), never a "let"-wrapper node or a dotted/indexed target, so this is simpler than the general-purpose `emitAssignTarget`. */
    private String destVarNameOf(Token target) {
        if (target.type == TokenType.KEYWORD && target.text.equals("let")) {
            return target.childs.get(0).text;
        }
        return target.text;
    }

    /**
     * "right so we do need ATOMIC_ASSIGN and ATOMIC_PUSH" -- confirmed
     * directly: a single aligned load/store of an atomic-eligible
     * (<=8-byte) value can never tear, so ATOMIC_PUSH/ATOMIC_ASSIGN exist
     * for a different reason than ATOMIC_SWAP does. ATOMIC_SWAP is a
     * genuine read-modify-write primitive with no non-atomic equivalent;
     * ATOMIC_PUSH/ATOMIC_ASSIGN are ordinary reads/writes that would be
     * emitted identically to a plain PUSH/ASSIGN if not for one thing a
     * not-yet-built backend needs to know and would otherwise have no way
     * to recover once this compiler's own type text is gone: this
     * specific access must not be reordered across other memory
     * operations, hoisted out of a loop, or common-subexpression-
     * eliminated with another read of the same location -- the same
     * "volatile"-like guarantee C gives a volatile-qualified access,
     * needed here because another thread may change this value between
     * any two instructions with no fence or lock this compiler emits
     * that a real optimizing backend would otherwise respect. Emitted
     * directly at the one and only place each of these two mnemonics'
     * plain counterparts is emitted, rather than left to be reconstructed
     * later from a neighboring line -- this session's standing "emit the
     * distinguishing fact directly, once it's known, don't infer it back
     * from context afterward" rule (see DEREF/NEW_DYN/NEW_UDYN/RESIZE/
     * URESIZE's own identical reasoning).
     *
     * Checked via a plain substring test against the already-built
     * canonical type text, not a dedicated TypeInfo field -- this class
     * only ever sees the string TypeChecker already resolved onto each
     * Token, the same way `.startsWith("owns")` already tests for
     * storage a few lines up in `emitDestructOldOwnedValue`.
     * `TypeInfo.canonical()`'s own emission order (see its doc comment)
     * always writes the literal segment "atomic_" as its own token,
     * immediately preceded by either the very start of the string or an
     * underscore closing off a prior segment (storage/some/mutability)
     * -- never as a substring straddling an ordinary identifier, since no
     * real base type or struct name in this language is ever spelled
     * with a literal lowercase "atomic_" of its own. A plain `contains`
     * check is therefore exact here, not a heuristic.
     */
    private static boolean isAtomicCanonical(String canonical) {
        return canonical != null && canonical.contains("atomic_");
    }

    /**
     * The push of a raw-pointer arithmetic scale: `PUSH <bytes> <type>`, or, when the pointee is a struct whose
     * size is only known once the layout is final, the symbolic `SIZEOF <struct> <type>` (resolved by the
     * Optimizer's SizeofResolutionPass into that same PUSH).
     */
    private static String pointerScaleLine(Token op, String type) {
        return op.pointerScaleStruct != null
                ? "SIZEOF " + op.pointerScaleStruct + " " + type
                : "PUSH " + op.pointerScale + " " + type;
    }

    /** x += y desugars to the same bytecode as "x = x + y" -- no dedicated compound-assign opcode. */
    private void emitCompoundAssign(Token op) {
        emitAssignTarget(op.left); // assign target
        emitExpr(op.left);         // left operand of the arithmetic
        emitExpr(op.right);        // right operand
        if (op.pointerScale > 1) {
            // "p += n" on a raw pointer: n elements, i.e. n * sizeof(pointee) bytes.
            line(pointerScaleLine(op, "indeterminate_u64"));
            line("MUL " + op.right.resolvedType + " indeterminate_u64 " + op.right.resolvedType);
        }
        String arithOp = COMPOUND_TO_ARITH.get(op.text);
        line(ARITH_NAMES.get(arithOp) + " " + op.left.resolvedType + " " + op.right.resolvedType
                + " " + op.left.resolvedType);
        line("ASSIGN " + op.left.resolvedType + " " + op.left.resolvedType + " " + op.resolvedType);
    }

    private void emitBinaryArith(Token op) {
        if (op.pointerArithKind != 0) {
            emitPointerArith(op);
            return;
        }
        emitExpr(op.left);
        emitExpr(op.right);
        line(ARITH_NAMES.get(op.text) + " " + op.left.resolvedType + " " + op.right.resolvedType
                + " " + op.resolvedType);
    }

    /** C-style pointer arithmetic: p +/- n and n + p scale n by the pointee size; p - q is (p - q) / sizeof(pointee), signed. */
    private void emitPointerArith(Token op) {
        String l = op.left.resolvedType;
        String r = op.right.resolvedType;
        String arith = ARITH_NAMES.get(op.text);
        if (op.pointerArithKind == 3) {
            emitExpr(op.left);
            emitExpr(op.right);
            line(arith + " " + l + " " + r + " indeterminate_s64");
            if (op.pointerScale > 1) {
                line(pointerScaleLine(op, "indeterminate_s64"));
                line("DIV indeterminate_s64 indeterminate_s64 indeterminate_s64");
            }
            return;
        }
        emitExpr(op.left);
        if (op.pointerArithKind == 2 && op.pointerScale > 1) {
            line(pointerScaleLine(op, "indeterminate_u64"));
            line("MUL " + l + " indeterminate_u64 " + l);
        }
        emitExpr(op.right);
        if (op.pointerArithKind == 1 && op.pointerScale > 1) {
            line(pointerScaleLine(op, "indeterminate_u64"));
            line("MUL " + r + " indeterminate_u64 " + r);
        }
        line(arith + " " + l + " " + r + " " + op.resolvedType);
    }

    private void emitUnaryMinus(Token op) {
        // A negated integer literal is one constant: a single `PUSH -N` (so it also stays a plain PUSH inside a
        // packed array / struct construction run, which any other instruction between pushes would break).
        if (op.left.type == TokenType.INTEGER && op.left.forcedLiteralType == null && op.left.text.matches("[0-9]+")) {
            String digits = op.left.text.replaceFirst("^0+(?=.)", "");
            line("PUSH " + (digits.equals("0") ? "0" : "-" + digits) + " " + op.resolvedType);
            return;
        }
        emitExpr(op.left);
        line("NEG " + op.left.resolvedType + " " + op.resolvedType);
    }

    private void emitLogicalNot(Token op) {
        emitExpr(op.left);
        line("NOT " + op.left.resolvedType + " " + op.resolvedType);
    }

    /** x++ desugars to the same bytecode shape as "x = x .INC" -- push target, push value, INC, ASSIGN. */
    private void emitIncrement(Token op) {
        if (op.pointerScale > 1) {
            emitPointerStep(op, "ADD");
            return;
        }
        emitAssignTarget(op.left); // assign target
        emitExpr(op.left);         // value to increment
        line("INC " + op.left.resolvedType + " " + op.resolvedType);
        line("ASSIGN " + op.left.resolvedType + " " + op.resolvedType + " " + op.resolvedType);
    }

    /** "p++"/"p--" on a raw pointer whose pointee is wider than a byte: p = p +/- sizeof(pointee), as C does. */
    private void emitPointerStep(Token op, String arith) {
        String t = op.left.resolvedType;
        emitAssignTarget(op.left);
        emitExpr(op.left);
        line(pointerScaleLine(op, "indeterminate_u64"));
        line(arith + " " + t + " indeterminate_u64 " + t);
        line("ASSIGN " + t + " " + t + " " + t);
    }

    /** x-- -- the identical shape emitIncrement uses for x++, with "DEC" in place of "INC"; everything else (target/value order, the trailing ASSIGN) is exactly the same. */
    private void emitDecrement(Token op) {
        if (op.pointerScale > 1) {
            emitPointerStep(op, "SUB");
            return;
        }
        emitAssignTarget(op.left); // assign target
        emitExpr(op.left);         // value to decrement
        line("DEC " + op.left.resolvedType + " " + op.resolvedType);
        line("ASSIGN " + op.left.resolvedType + " " + op.resolvedType + " " + op.resolvedType);
    }

    private void emitLogical(Token op, String name) {
        emitExpr(op.left);
        emitExpr(op.right);
        line(name + " " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
    }

    /** True for a "&&then"/"||then" node specifically -- never a plain "&&"/"||". */
    private boolean isThenVariant(Token node) {
        return node.type == TokenType.OPERATOR && (node.text.equals("&&then") || node.text.equals("||then"));
    }

    /**
     * "&&then"/"||then" -- the lazy "then" pseudo-operator -- are "just
     * operators until they are parsed out to their instructions in the
     * hob," confirmed directly: everywhere else in the compiler
     * (RpnConverter, TreeBuilder, TypeChecker) they're ordinary binary
     * operator nodes, but they have no eager, single-pushed-value form at
     * all -- unlike plain "&&"/"||" (see emitLogical just above, which
     * always evaluates *both* sides before combining them with one
     * AND/OR instruction), a "&&then"/"||then" node only ever lowers to
     * this cascading CMP/JMP control-flow shape, entered here (from
     * emitIfChain) instead of the ordinary single `emitExpr; CMP; JMP`
     * pair an eager condition gets.
     *
     * TypeChecker.checkLogical already guarantees (see
     * `insideShortCircuitEligiblePosition`'s own doc comment) that a
     * "&&then"/"||then" node's own `.left`/`.right` are each *either*
     * another "&&then"/"||then" node (the chain continues), *or* some
     * completely ordinary expression containing no "&&then"/"||then" of
     * its own anywhere inside it (a plain "&&"/"||" subtree included --
     * that subtree's own "always evaluate both sides" guarantee is
     * preserved exactly, since it's emitted as one atomic leaf via the
     * ordinary `emitExpr`/`emitLogical` path below, never decomposed).
     * So this method never needs to search *inside* a non-then-variant
     * node for a further, illegally-nested "&&then"/"||then" -- one
     * simply cannot be there.
     *
     * Exactly one of `falseLabel`/`trueLabel` is ever non-null for any
     * one call -- the other says which outcome this call should just
     * *fall through* on, letting whatever code follows continue
     * naturally, rather than jump anywhere: `falseLabel` non-null means
     * "jump there if this node is false, fall through if true" (the
     * shape an ordinary `if`-condition or an "&&then" chain's own
     * operand needs); `trueLabel` non-null means the opposite (needed
     * for "||then"'s own left operand -- see the "||then" branch below,
     * matching the user's own sketch: "if A false, go evaluate B... A
     * was true -> skip straight into the body").
     *
     * Deliberately emits no "AND"/"OR" instruction anywhere in the
     * cascade (unlike the user's own rough sketch, which included one
     * for "&&then"): each operand independently determines whether to
     * jump away, so no combined boolean value is ever needed -- ANDing
     * with a value already known (from having fallen through) to be
     * "true" is a no-op, and omitting it avoids any question of whether
     * "CMP" pops or peeks its operand, staying entirely within the
     * already-proven "one push, one CMP, one JMP" instruction pairing
     * used everywhere else in this codebase.
     */
    private void emitShortCircuit(Token node, String falseLabel, String trueLabel) {
        if (!isThenVariant(node)) {
            // A leaf for cascading purposes -- any ordinary expression,
            // including a whole plain "&&"/"||" subtree emitted fully
            // eagerly (both sides always evaluated) via the normal
            // emitExpr/emitLogical path; only its single resulting bool
            // is tested here.
            emitExpr(node);
            line("CMP");
            if (falseLabel != null) {
                // "CMP; JMP label" already means "jump if false, fall
                // through if true" (emitIfChain's own established
                // reading) -- exactly what's needed here.
                line("JMP " + falseLabel);
            } else {
                // Need the opposite: jump to trueLabel only if true, fall
                // through if false. No dedicated "jump if true" opcode
                // exists, so this is built from the same CMP/JMP pairing
                // by inverting it with one extra unconditional jump: if
                // false, CMP/JMP's own "jump if false" lands past the
                // unconditional "JMP trueLabel" entirely (the natural
                // fall-through-on-false this branch needs); if true, it
                // falls through *into* the unconditional jump instead.
                String skipLabel = newLabel("short_circuit_leaf_skip");
                line("JMP " + skipLabel);
                line("JMP " + trueLabel);
                line(skipLabel + ":");
            }
            return;
        }
        boolean isAnd = node.text.equals("&&then");
        if (isAnd) {
            if (trueLabel == null) {
                // Ordinary "fall through if both true, jump falseLabel
                // the moment either fails" cascade -- the common case,
                // used directly by emitIfChain and by an outer
                // "&&then"'s own recursive call into this same shape.
                emitShortCircuit(node.left, falseLabel, null);
                emitShortCircuit(node.right, falseLabel, null);
            } else {
                // This "&&then" node is itself the left operand of an
                // enclosing "||then" (the only way trueLabel ends up
                // non-null here) -- needs "jump trueLabel only if BOTH
                // sides pass, otherwise fall through" instead. Built by
                // cascading both sides against one local fail label that
                // sits right before the unconditional jump to trueLabel,
                // so any failure skips past that jump and lands exactly
                // on the desired "fall through" outcome.
                String bothFailLabel = newLabel("andthen_fail");
                emitShortCircuit(node.left, bothFailLabel, null);
                emitShortCircuit(node.right, bothFailLabel, null);
                line("JMP " + trueLabel);
                line(bothFailLabel + ":");
            }
        } else { // "||then"
            if (falseLabel == null) {
                // This "||then" is itself the left operand of an
                // enclosing "||then" -- both sides just forward the same
                // trueLabel directly (either one being true jumps
                // straight there, matching plain "||then" semantics one
                // level up), falling through only if both are false.
                emitShortCircuit(node.left, null, trueLabel);
                emitShortCircuit(node.right, null, trueLabel);
            } else {
                // The ordinary case (used directly by emitIfChain, and
                // by an enclosing "&&then"'s own recursive call): if the
                // left operand is true, skip evaluating the right one
                // entirely and fall straight through as "true" (the
                // user's own sketch: "A was true -> skip straight into
                // the body, don't evaluate B at all"); otherwise fall
                // through to test the right operand, jumping to
                // falseLabel only if that one also fails.
                String passLabel = newLabel("orthen_pass");
                emitShortCircuit(node.left, null, passLabel);
                emitShortCircuit(node.right, falseLabel, null);
                line(passLabel + ":");
            }
        }
    }

    private void emitComparison(Token op) {
        emitExpr(op.left);
        emitExpr(op.right);
        line(COMPARISON_NAMES.get(op.text) + " " + op.left.resolvedType + " " + op.right.resolvedType
                + " " + op.resolvedType);
    }

    /**
     * "myAtomicChar swap '\0'" -- a genuine, dedicated read-modify-
     * write instruction, unlike an ordinary atomic read/write (which
     * needs no new bytecode at all). Same "PUSH both sides, one
     * instruction, trailing operand-type annotations" shape every
     * other binary operator here already has (see emitComparison/
     * emitLogical just above) -- ATOMIC_SWAP's own left operand is
     * always a bare atomic variable reference (checkSwapOperator
     * requires this), so it's simply pushed like any other value; the
     * result left on the stack is the value that was there a moment
     * before this instruction ran.
     */
    private void emitSwap(Token op) {
        // ATOMIC_SWAP consumes an ADDRESS (xchg through it), so push the target as an address (as an
        // assignment target does). It used to push the field's VALUE, which only worked for statics
        // (resolved by name downstream); a swap-lock struct held in a local segfaulted.
        emitAssignTarget(op.left);
        emitExpr(op.right);
        line("ATOMIC_SWAP " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
    }

    /**
     * "These look like functions and use the function call syntax, but
     * they get their own instruction in the bytecode output, unless
     * otherwise specified," confirmed directly. TypeChecker never sets
     * resolvedCallTarget for one of these (they bypass the ordinary
     * overload table entirely), so this must intercept before ever
     * reaching the ordinary emitCall path, dispatching on the reserved
     * name itself.
     */
    /** Per-function-body-unrelated, whole-program counter for the hidden local variable each "par"/"await" call site needs to hold its own async handle pointer (see emitAsyncCall's own doc comment) -- global rather than per-function, purely so no naming scheme has to track per-function uniqueness at all. */
    private int asyncHandleTempCounter;

    private void emitCallOrBuiltin(Token op) {
        if (op.isSleepCall) {
            emitSleepCall(op);
            return;
        }
        if ("AWAIT".equals(op.asyncCallKind) || "PAR".equals(op.asyncCallKind)) {
            emitAsyncCall(op);
            return;
        }
        if (op.left.type == TokenType.VARREF) {
            switch (op.left.text) {
                case "sizeof":
                    // "gets me the integer value, direct replacement in
                    // bytecode - no instruction," confirmed directly.
                    // A struct (or array of structs) stays symbolic: its real size (hidden ___type,
                    // padding, and whatever layout the Optimizer finally settles on) is only known
                    // there, and SizeofResolutionPass turns this into the same PUSH.
                    if (op.sizeofStruct != null) {
                        line("SIZEOF " + op.sizeofStruct + " " + op.resolvedType);
                    } else {
                        line("PUSH " + op.builtinConstantValue + " " + op.resolvedType);
                    }
                    return;
                case "len": {
                    List<Token> lenArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), lenArgs);
                    if (lenArgs.size() == 2) {
                        // "len(my_unsafe_char_array,'\0')" -- the
                        // unsafe, 2-arg terminator-scanning form.
                        // Never foldable (TypeChecker.checkLenBuiltin
                        // leaves builtinConstantValue unset for this
                        // case too), so both arguments are pushed and a
                        // new, dedicated "LEN_SCAN" instruction reads
                        // the real, scanned-for-a-terminator length at
                        // runtime -- kept as its own mnemonic, distinct
                        // from the safe variant's plain "LEN", since the
                        // two need genuinely different runtime
                        // implementations (a hidden-field read vs. a
                        // byte-by-byte scan).
                        Token targetArg = lenArgs.get(0);
                        Token termArg = lenArgs.get(1);
                        emitExpr(targetArg);
                        emitExpr(termArg);
                        line("LEN_SCAN " + targetArg.resolvedType + " " + termArg.resolvedType);
                    } else if (op.builtinConstantValue != null) {
                        // A fixed array or a direct string literal --
                        // same "no instruction, direct literal
                        // replacement" fold 'sizeof' always uses.
                        line("PUSH " + op.builtinConstantValue + " " + op.resolvedType);
                    } else {
                        // A "dynarray(T)" -- its length is a genuine
                        // runtime value (TypeChecker.checkLenBuiltin
                        // deliberately leaves builtinConstantValue unset
                        // for exactly this case), so it needs an actual
                        // instruction reading it, not a compile-time
                        // fold.
                        emitExpr(singleBuiltinArg(op));
                        line("LEN");
                    }
                    return;
                }
                case "range": {
                    // A range's own bounds are always two plain u64
                    // words (TypeChecker.checkRangeBuiltin's own doc
                    // comment) -- "start" is always a literal 0 in both
                    // shapes below, never itself a variable or a
                    // computed value.
                    if (op.builtinConstantValue != null) {
                        // A string literal or fixed array -- both
                        // bounds are compile-time constants; the exact
                        // same "no instruction, direct literal
                        // replacement" fold 'sizeof'/'len' already use,
                        // just written as two words instead of one.
                        // There used to be a real "RANGE" bytecode
                        // instruction here instead -- retired entirely,
                        // the same "compiled away, needs no instruction
                        // once the front end already knows the answer"
                        // treatment 'len'/'sizeof' already got, once it
                        // turned out this length was knowable at
                        // compile time all along and was just being
                        // thrown away before reaching codegen.
                        line("PUSH 0 imut_u64");
                        line("PUSH " + op.builtinConstantValue + " imut_u64");
                    } else {
                        // A safe dynarray -- its length is a genuine
                        // runtime value, read through the exact same
                        // hidden-length-header mechanism 'len's own
                        // dynarray form already reads (and the existing
                        // "LEN" instruction, unchanged, does the actual
                        // reading) -- no new codegen instruction needed
                        // at all.
                        line("PUSH 0 imut_u64");
                        emitExpr(singleBuiltinArg(op));
                        line("LEN");
                    }
                    return;
                }
                case "deref":
                    // "PUSH pointer / DEREF" used to carry no operand at
                    // all -- a real, found-and-fixed gap: a dereference is
                    // genuinely a copy (it reads the pointee's own bytes
                    // off the heap and replaces the pointer on the stack
                    // with the actual value), and on this bytecode's own
                    // byte-precise stack (PUSH/POP/ASSIGN all carry an
                    // explicit width), a copy needs to know how many bytes
                    // to move -- confirmed directly: "im against getting
                    // it from the previous line as much as possible" --
                    // the preceding "PUSH pointer" line's own erased width
                    // (AddressLoweringPass) is always exactly 8 (every
                    // pointer is one machine word, `SizeCalculator`'s own
                    // "storage-bearing types are always 8 bytes" rule),
                    // which describes the *pointer's* size, never the
                    // *pointee's* -- there was never a legitimate way to
                    // recover the real copy width from context, so `DEREF`
                    // now carries it directly: `checkDerefBuiltin` already
                    // resolves the pointee's own type onto this call's own
                    // `op.resolvedType` (via the ordinary `resolveExprType`
                    // wrapper every expression node gets, "storage
                    // stripped, same as any other inline value" -- that
                    // method's own doc comment) -- it was simply never
                    // read here before. `AddressLoweringPass` erases it to
                    // a byte count the identical way every other typed,
                    // single-operand mnemonic (`NEG`, ...) already does.
                    emitExpr(singleBuiltinArg(op));
                    line("DEREF " + op.resolvedType);
                    return;
                case "clone": {
                    // A call nested in this builtin's operands would consume the pending catch label (it is meant for the
                    // call-site staging of the first call), leaving the allocation check below without one: hold it back here.
                    final String cloneCatchLabel = pendingTryCatchLabel;
                    pendingTryCatchLabel = null;
                    // "the compiler should output the arguments for
                    // CLONE the same style as other standard operations,
                    // and then the optimizer should reduce it to CLONE
                    // size during the lowering," confirmed directly --
                    // CLONE used to be emitted completely bare, the only
                    // mnemonic here with a real, meaningful operand
                    // (checkCloneBuiltin's own pointee) but zero type
                    // annotation of any kind, unlike every other
                    // instruction in this format ("every instruction
                    // states the type of what it leaves on the stack").
                    // Now carries its argument's own pointer type and its
                    // own result type, the identical trailing-operand
                    // shape NEG/NOT/DEREF's siblings already use, so the
                    // sibling caspien-optimizer project's AddressLoweringPass
                    // can resolve the pointee's own byte size from the
                    // argument type alone (stripping the storage keyword
                    // first -- a pointer is always one word regardless of
                    // what it points to, but CLONE needs to know how much
                    // to actually copy).
                    Token arg = singleBuiltinArg(op);
                    emitExpr(arg);
                    requireGhostTableFunctionPresent("gt_register", op);
                    line("CLONE " + arg.resolvedType + " " + op.resolvedType);
                    // Same tail as `new`: a null result (failed malloc) jumps to the enclosing catch, otherwise
                    // the copy is registered with the ghost table so `match Some` and the scope-end destruct work.
                    pendingTryCatchLabel = cloneCatchLabel;
                    line("GT_REGISTER"); // before the check: a failed registration frees the block and leaves null
                    emitAllocFailureCheck(op);
                    return;
                }
                case "Some":
                    // "the bytecode is the same," confirmed directly --
                    // the exact GT_ALIVE_CHECK sequence the old bare-
                    // form match condition already emitted, now reached
                    // through a real, generally-available builtin call
                    // instead of match's own special-cased bare form.
                    // Both restrictions that guarded the old bare form
                    // move here with it, unconditionally -- "Some()" now
                    // needs them regardless of whether it's used inside
                    // a match condition or as an ordinary expression
                    // anywhere else, since the actual GT_ALIVE_CHECK
                    // emission (and everything that depends on it) is
                    // identical either way.
                    if (inGtSuppressedContext) {
                        throw new CompilerException("type", op.file, op.line,
                                "'Some(...)' (which needs '@gt_alive_check') can't be used here -- this "
                                        + "function is a ghost-table-decorated function, or is reachable "
                                        + "from one, and ghost-table instructions can never be generated "
                                        + "in that whole reachable set");
                    }
                    requireGhostTableFunctionPresent("gt_alive_check", op);
                    emitExpr(singleBuiltinArg(op));
                    line("GT_ALIVE_CHECK");
                    return;
                case "wrap":
                case "sat":
                    emitConvert(op);
                    return;
                case "insecure_rand":
                    // "have insecure_rand() be rand()," confirmed
                    // directly -- reuses the exact same shared call
                    // machinery every ordinary/extern call already goes
                    // through (emitCallSequence: CC_START / .. / CALL-
                    // shaped instruction / CC_END / PUSH_RET), just with
                    // zero arguments and no registered ExternInfo at
                    // all -- this builtin is never reachable as an
                    // ordinary user-declared "extern rand(...)" would
                    // be, so there's nothing to look up here, only a
                    // fixed, known call to emit. The default calling
                    // convention (compiler.config's own declared one) is
                    // used directly, the same way any other call site
                    // with no applicable "@call_convention" decorator
                    // already resolves to it.
                    emitCallSequence(null, new ArrayList<>(), () -> line("EXTERN_CALL rand 0"), "mut_u64",
                            checker.getConfig().defaultConvention);
                    return;
                case "bits_not": {
                    // Unary: one operand, one instruction "BITS_NOT argType resultType" (the shape NOT/NEG already have).
                    List<Token> notArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), notArgs);
                    emitExpr(notArgs.get(0));
                    line("BITS_NOT " + notArgs.get(0).resolvedType + " " + op.resolvedType);
                    return;
                }
                case "bits_left": case "bits_right": case "bits_rotl": case "bits_rotr": case "bits_or": case "bits_and": case "bits_xor": {
                    // "these compile to single instructions like other
                    // builtins," confirmed directly. Given the shape
                    // wasn't spelled out explicitly the way "call"'s
                    // was, this mirrors the structurally closest existing
                    // case -- a binary arithmetic operator (ADD/SUB/...),
                    // not a unary builtin like RANGE/DEREF/CLONE -- since
                    // these are themselves binary, two-operand operations
                    // with a result, the same shape ADD already has,
                    // including the operand-type annotations that shape
                    // always carries. (bits_not is the unary sibling just above.)
                    List<Token> bitsArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), bitsArgs);
                    Token bitsLeft = bitsArgs.get(0);
                    Token bitsRight = bitsArgs.get(1);
                    emitExpr(bitsLeft);
                    emitExpr(bitsRight);
                    String bitsOpName;
                    switch (op.left.text) {
                        case "bits_left": bitsOpName = "SHL"; break;
                        case "bits_right": bitsOpName = "SHR"; break;
                        case "bits_rotl": bitsOpName = "ROTL"; break;
                        case "bits_rotr": bitsOpName = "ROTR"; break;
                        case "bits_and": bitsOpName = "BITS_AND"; break;
                        case "bits_xor": bitsOpName = "BITS_XOR"; break;
                        default: bitsOpName = "BITS_OR"; break;
                    }
                    line(bitsOpName + " " + bitsLeft.resolvedType + " " + bitsRight.resolvedType + " "
                            + op.resolvedType);
                    return;
                }
                case "memcopy": {
                    // "this copies the second argument number of bytes
                    // from the second pointer to the first pointer,"
                    // confirmed directly -- no dedicated bytecode shape
                    // given explicitly, so this follows the same
                    // "PUSH every argument, then one instruction naming
                    // the operand types" shape 'call'/binary-operator
                    // emission already establishes, rather than
                    // inventing something new.
                    List<Token> memcopyArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), memcopyArgs);
                    for (Token arg : memcopyArgs) {
                        emitExpr(arg);
                    }
                    line("MEMCOPY " + memcopyArgs.get(0).resolvedType + " " + memcopyArgs.get(1).resolvedType
                            + " " + memcopyArgs.get(2).resolvedType + " " + op.resolvedType);
                    return;
                }
                case "dyn": {
                    // A call nested in this builtin's operands would consume the pending catch label (it is meant for the
                    // call-site staging of the first call), leaving the allocation check below without one: hold it back here.
                    final String dynCatchLabel = pendingTryCatchLabel;
                    pendingTryCatchLabel = null;
                    if (op.dynFromStringSource) {
                        // "dyn(text)" -- a static imut string argument,
                        // not an array literal. Pushes the string value
                        // (its own hoisted string_id, or a plain
                        // variable reference, via ordinary emitExpr),
                        // then one "NEW_FROM_STRING dynarray(T)
                        // sourceType" line -- a genuinely new runtime
                        // operation (character-by-character copying out
                        // of the string, not a fixed, already-known set
                        // of pushed elements the way an array literal
                        // is), so it needs its own mnemonic rather than
                        // reusing "NEW"'s own shape.
                        Token stringArg = singleBuiltinArg(op);
                        emitExpr(stringArg);
                        line("NEW_FROM_STRING " + op.resolvedType.substring("owns_".length()) + " "
                                + stringArg.resolvedType);
                        // UPDATE (throwing 'dyn'): the safe variant
                        // ("dyn(text)", not "unsafe dyn(text)") now
                        // requires 'try'/'catch' -- see
                        // TypeChecker.checkDynBuiltinCore's own doc
                        // comment -- so its own allocation failure now
                        // needs the identical runtime null-check-and-
                        // jump-to-catch `emitNew` already does for 'new'.
                        if (!op.resolvedType.substring("owns_".length()).startsWith("indeterminate_unsafe_dynarray(")) {
                            pendingTryCatchLabel = dynCatchLabel;
                            // The block start is registered with the ghost table (like `new`), so scope-end drop frees it;
                            // a failed registration frees the block and leaves null: the check below takes the OOM path.
                            requireGhostTableFunctionPresent("gt_register", op);
                            line("GT_REGISTER");
                            emitAllocFailureCheck(op);
                        }
                        return;
                    }
                    // "dyn([0,1,2,3])" -- reuses the exact same array-
                    // literal push shape "new [1,2,3]" already emits
                    // unchanged (each element PUSHed, in order, no
                    // instruction of its own for the literal itself),
                    // then one "NEW_DYN dynarray(T) count" /
                    // "NEW_UDYN unsafe_dynarray(T) count" line -- a
                    // trailing count appended in both cases, since a
                    // dynarray's own type string (unlike a fixed array's)
                    // never bakes in a length for the consumer to read
                    // the element count back out of otherwise.
                    //
                    // Split into two distinct mnemonics, rather than
                    // reusing plain "NEW" (as an earlier version of this
                    // did), for the identical reason `LOOKUP` was split
                    // into `LOOKUP_ARRAY`/`LOOKUP_DYN` in the optimizer's
                    // own `AddressLoweringPass.lookupMnemonicFor`: a safe
                    // `dynarray(T)`'s own allocation needs room for its
                    // 8-byte length header ahead of the elements, an
                    // unsafe one doesn't -- two genuinely different
                    // allocation shapes that a backend shouldn't have to
                    // tell apart by parsing a type string once this is
                    // fully lowered. `AddressLoweringPass` erases each
                    // one differently: "NEW_UDYN size" (a flat
                    // count*elementSize byte total -- an unsafe dynarray
                    // has no header to write, so nothing else is needed);
                    // "NEW_DYN size count" (elementSize count*elementSize
                    // total for the *elements* -- the header's own extra
                    // 8 bytes deliberately left for whatever allocates it
                    // to add, not folded in here -- plus `count` kept on
                    // the line, since the actual runtime code will need
                    // it again to write into the length header, and
                    // that's exactly the kind of thing this project
                    // doesn't recover by re-deriving it from a
                    // neighboring line).
                    Token arrayLiteral = singleBuiltinArg(op);
                    List<Token> literalElements = new ArrayList<>();
                    final List<String> literalTemps = new ArrayList<>();
                    // `dyn:<T>([])` -- nothing to push for an empty literal.
                    if (!arrayLiteral.childs.isEmpty()) {
                        final List<String> savedDynTemps = dynLiteralTempNames;
                        dynLiteralTempNames = literalTemps;
                        emitExpr(arrayLiteral);
                        dynLiteralTempNames = savedDynTemps;
                        if (arrayLiteral.type == TokenType.DELINEATOR && arrayLiteral.text.equals("[")) {
                            collectCommaArgs(arrayLiteral.childs.get(0), literalElements);
                        }
                    }
                    String typeText = op.resolvedType.substring("owns_".length());
                    boolean isUnsafeLiteral = typeText.startsWith("indeterminate_unsafe_dynarray(");
                    String mnemonic = isUnsafeLiteral ? "NEW_UDYN" : "NEW_DYN";
                    line(mnemonic + " " + typeText + " " + op.builtinConstantValue);
                    // UPDATE (throwing 'dyn'): "new throws, but dyn and
                    // resize dont, they need to use the same pattern as
                    // new," confirmed directly -- the safe variant
                    // ("dyn([...])", not "unsafe dyn([...])") now
                    // requires 'try'/'catch' (see
                    // TypeChecker.checkDynBuiltinCore's own doc comment),
                    // so its own allocation failure now needs the
                    // identical runtime null-check-and-jump-to-catch
                    // `emitNew` already does for 'new'. Left untouched
                    // for "NEW_UDYN": the unsafe variant is deliberately
                    // still not throw-capable at all.
                    if (!isUnsafeLiteral) {
                        pendingTryCatchLabel = dynCatchLabel;
                        requireGhostTableFunctionPresent("gt_register", op);
                        line("GT_REGISTER");
                        emitAllocFailureCheck(op, literalElements, literalTemps);
                    }
                    return;
                }
                case "resize": {
                    // A call nested in this builtin's operands would consume the pending catch label (it is meant for the
                    // call-site staging of the first call), leaving the allocation check below without one: hold it back here.
                    final String resizeCatchLabel = pendingTryCatchLabel;
                    pendingTryCatchLabel = null;
                    // "resize should take ownership (owns) of the
                    // dynamicarray and return a new owns" -- confirmed
                    // directly, modeled directly on a real realloc
                    // (which can hand back a new address). The first
                    // argument's own null-out (when
                    // TypeChecker.markMovedIfOwned actually marked it,
                    // via Token.isOwnershipMoveSource) is emitted right
                    // after that argument's own push and strictly
                    // before the "RESIZE"/"URESIZE" line runs -- the
                    // identical "null it out before, not after, the
                    // operation that consumes it" placement an ordinary
                    // call's own owns-typed argument already gets in
                    // emitCallSequence, needed here by hand since
                    // 'resize' bypasses the ordinary calling-convention
                    // machinery entirely. Three args (safe, with a fill
                    // value) or two (unsafe, a bare realloc, no fill
                    // value) -- TypeChecker.checkResizeBuiltin already
                    // validated which shape applies. The result is left
                    // implicitly on the stack by "RESIZE"/"URESIZE"
                    // itself for whatever ASSIGN/PUSH_RET follows to
                    // consume, mirroring "NEW TypeName"'s own
                    // "implicitly returns its constructed value" shape --
                    // no CC_START/CC_END/PUSH_RET wrapper is needed,
                    // since 'resize' was never routed through the
                    // ordinary calling-convention path to begin with.
                    //
                    // Split into "RESIZE" (safe) / "URESIZE" (unsafe)
                    // rather than one shared mnemonic, for the identical
                    // reason "dyn([...])"'s own allocation was just split
                    // into NEW_DYN/NEW_UDYN: the two shapes carry
                    // genuinely different information (a fill value's
                    // type vs. none at all) and a backend shouldn't have
                    // to branch on argument count to tell them apart.
                    // `checkResizeBuiltin` already forces the first
                    // argument's own mutability to "mut" unconditionally
                    // ("'resize' requires a 'mut' dynarray"), so its
                    // resolved type text always has the fixed shape
                    // "owns_mut_dynarray(...)" / "owns_mut_unsafe_dynarray(...)"
                    // -- checking that exact prefix is precise and safe
                    // here, unlike guessing at an arbitrary type string.
                    List<Token> resizeArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), resizeArgs);
                    Token dynArrArg = resizeArgs.get(0);
                    boolean isUnsafeResize = dynArrArg.resolvedType.startsWith("owns_mut_unsafe_dynarray(");
                    // A safe dynarray whose elements own memory: a shrink must drop the owns members of the elements it cuts
                    // off. The array pointer and the new count are evaluated once into hidden locals, the elements at index
                    // >= count are dropped (GT_DESTRUCT_TAIL, expanded by DropGlueGenerationPass), then the RESIZE runs on them.
                    String tailArr = null;
                    String tailCount = null;
                    String tailOld = null;
                    if (!isUnsafeResize && lateAllocs != null) {
                        String dynType = dynArrArg.resolvedType;
                        String elemText = dynType.substring(dynType.indexOf("dynarray(") + "dynarray(".length(), dynType.length() - 1);
                        if (checker.elementTextOwnsMemory(elemText)) {
                            Token countArg = resizeArgs.get(1);
                            tailArr = declareHiddenLocal(dynType);
                            line("ADDR " + tailArr + " " + dynType);
                            emitExpr(dynArrArg);
                            line("ASSIGN " + dynType + " " + dynType + " " + dynType);
                            tailCount = declareHiddenLocal("mut_u64");
                            line("ADDR " + tailCount + " mut_u64");
                            emitExpr(countArg);
                            line("ASSIGN mut_u64 " + countArg.resolvedType + " mut_u64");
                            line("GT_DESTRUCT_TAIL " + tailArr + " " + dynType + " " + tailCount);
                            tailOld = declareHiddenLocal("mut_u64");
                            line("ADDR " + tailOld + " mut_u64");
                            line("PUSH " + tailArr + " " + dynType);
                            line("LEN");
                            line("ASSIGN mut_u64 indeterminate_u64 mut_u64");
                            line("PUSH " + tailArr + " " + dynType);
                        }
                    }
                    // A source that is a temporary (a call result) has no name to free if the realloc fails: it goes through a
                    // hidden local (like a temporary moved into `new`), which the failure branch destructs.
                    final List<String> resizeTemps = new ArrayList<>();
                    if (tailArr != null && isOwnsTemporary(dynArrArg)) {
                        resizeTemps.add(tailArr);
                    }
                    if (tailArr == null) {
                        if (!isUnsafeResize && isOwnsTemporary(dynArrArg)) {
                            emitOwnsTemporaryViaLocal(dynArrArg, resizeTemps);
                        } else {
                            emitExpr(dynArrArg);
                        }
                    }
                    // Safe resize: the source variable is only nulled once the realloc succeeded. When it fails the old block is
                    // still allocated and registered (realloc leaves it intact) but nothing else would ever reach it, so the
                    // failure branch frees it (like a failed `new` frees what was moved into it). Unsafe resize cannot fail.
                    final boolean deferResizeNullOut = !isUnsafeResize && dynArrArg.isOwnershipMoveSource;
                    if (dynArrArg.isOwnershipMoveSource && !deferResizeNullOut) {
                        emitOwnershipMoveNullOut(dynArrArg);
                    }
                    StringBuilder resizeLine = new StringBuilder(isUnsafeResize ? "URESIZE " : "RESIZE ")
                            .append(dynArrArg.resolvedType);
                    for (int i = 1; i < resizeArgs.size(); i++) {
                        Token arg = resizeArgs.get(i);
                        if (i == 1 && tailCount != null) {
                            line("PUSH " + tailCount + " mut_u64");
                        } else {
                            emitExpr(arg);
                        }
                        resizeLine.append(' ').append(arg.resolvedType);
                    }
                    line(resizeLine.toString());
                    if (tailOld != null) {
                        // growing: the new slots are bitwise copies of the moved-in fill value; give each its own deep clone
                        String filled = declareHiddenLocal(dynArrArg.resolvedType);
                        line("POP " + filled + " " + dynArrArg.resolvedType);
                        line("CLONE_FILL " + filled + " " + dynArrArg.resolvedType + " " + tailOld);
                        line("PUSH " + filled + " " + dynArrArg.resolvedType);
                    }
                    // UPDATE (throwing 'resize'): "new throws, but dyn
                    // and resize dont, they need to use the same pattern
                    // as new," confirmed directly -- the safe variant
                    // ("RESIZE", a real realloc on a safe dynarray) now
                    // requires 'try'/'catch' (see
                    // TypeChecker.checkResizeBuiltin's own doc comment),
                    // so its own reallocation failure now needs the
                    // identical runtime null-check-and-jump-to-catch
                    // `emitNew` already does for 'new'. Left untouched
                    // for "URESIZE": the unsafe variant is deliberately
                    // still not throw-capable at all (its own "restricted
                    // to unsafe mode" latitude already covers a failed
                    // realloc the same way raw C code would).
                    if (!isUnsafeResize) {
                        pendingTryCatchLabel = resizeCatchLabel;
                        List<Token> resizeMoved = new ArrayList<>();
                        if (deferResizeNullOut) {
                            resizeMoved.add(dynArrArg);
                        }
                        emitAllocFailureCheck(op, resizeMoved, resizeTemps);
                        if (deferResizeNullOut) {
                            emitOwnershipMoveNullOut(dynArrArg);
                        }
                    }
                    return;
                }
                case "call": {
                    // "PUSH func_ptr / PUSH arg1 / PUSH arg2 / INVOKE n"
                    // -- confirmed directly, corrected mid-thread from a
                    // bare "INVOKE" to "INVOKE n" (n = the argument
                    // count, not counting the function pointer itself).
                    // Calling-convention wrapping applies to the real
                    // arguments only -- the function pointer itself is
                    // pushed first, inside CC_START/CC_END, but is never
                    // one of the "first n" register-transferred slots
                    // (it selects which callee to invoke; it isn't one
                    // of that callee's own parameters).
                    List<Token> callArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), callArgs);
                    Token funcPtrArg = callArgs.get(0);
                    List<Token> realArgs = callArgs.subList(1, callArgs.size());
                    int invokeArgCount = op.invokeArgCount;
                    emitCallSequence(() -> emitExpr(funcPtrArg), realArgs,
                            () -> line("INVOKE " + invokeArgCount), voidSafeReturnType(op), op.resolvedCallConvention);
                    return;
                }
                default:
                    break;
            }
        }
        if (op.left.type == TokenType.VARREF && checker.getExterns().containsKey(op.left.text)) {
            emitExternCall(op, checker.getExterns().get(op.left.text));
            return;
        }
        emitCall(op);
    }

    private Token singleBuiltinArg(Token op) {
        List<Token> args = new ArrayList<>();
        collectCommaArgs(op.right.childs.get(0), args);
        return args.get(0);
    }

    /**
     * Wraps the calling-convention bracket ("CC_START name" .. push
     * each argument, register-transferring only the first n of them
     * (n = that convention's own declared argumentRegisters.size(),
     * read from compiler.config) via "POP ARGn type" right after each
     * is pushed, anything beyond that left as a plain PUSH .. the
     * actual call instruction .. "CC_END name") and, for a non-void
     * return, a trailing "PUSH_RET type" *after* CC_END -- shared by
     * every call mechanism (CALL/RECURSIVE_CALL, EXTERN_CALL, INVOKE),
     * confirmed directly to apply to all three identically. Naturally
     * nests: "foo(bar())" -- bar()'s own argument is emitted via the
     * ordinary recursive emitExpr call inside this method's own arg-
     * emission loop, so bar()'s complete, self-contained CC_START/.../
     * CC_END/PUSH_RET sequence (using *its own* resolved convention,
     * independently of foo's) is already fully emitted (and its result
     * already sitting on the stack, via its own PUSH_RET) by the time
     * foo's outer POP ARG0 runs against it -- no special-casing needed
     * anywhere for this, including when the two calls resolve to
     * genuinely different conventions.
     *
     * pushFuncPtr: null for an ordinary/extern call; for `call()`,
     * pushes the function-pointer operand itself, inside the CC
     * bracket but *before* the register-transferred args and never
     * counted as one of them (it isn't one of the callee's own
     * parameters).
     * regArgs: the arguments actually subject to ARGn register
     * transfer.
     * emitCallInstruction: emits the one CALL/RECURSIVE_CALL/
     * EXTERN_CALL/INVOKE line itself.
     * returnTypeOrNull: the call's own canonical return type, or null
     * for a void return (see voidSafeReturnType) -- when non-null,
     * emits "PUSH_RET returnTypeOrNull" after CC_END.
     * conventionName: this call site's own resolved calling convention
     * (Token.resolvedCallConvention -- TypeChecker already resolved
     * this from the target func/extern's own "@call_convention", or,
     * for INVOKE, the function-pointer type's own; compiler.config's
     * declared default when neither was present. Never re-derived
     * here.
     */
    private void emitCallSequence(Runnable pushFuncPtr, List<Token> regArgs, Runnable emitCallInstruction,
            String returnTypeOrNull, String conventionName) {
        emitCallSequence(pushFuncPtr, regArgs, emitCallInstruction, returnTypeOrNull, conventionName, -1);
    }

    /**
     * `varargStartIndex` -- the index (into `regArgs`) of the first
     * *true* vararg, i.e. the first argument past a variadic extern's
     * own fixed, declared parameter list, or -1 for an ordinary,
     * wholly non-variadic call (every other call site in this
     * compiler). C's own "default argument promotion" rule applies
     * only to the true varargs -- never to a variadic function's own
     * fixed prefix parameters (printf's own format-string argument,
     * for instance) -- see `emitExternCall`, the only caller that ever
     * passes anything but -1 here, and `PROMOTE_F32_TO_F64`'s own doc
     * comment in `caspien-codegen`'s `X86Backend` for the full ABI
     * reasoning and the fix this was added for ("printf('%f', ...)
     * always prints 0.000000").
     */
    private void emitCallSequence(Runnable pushFuncPtr, List<Token> regArgs, Runnable emitCallInstruction,
            String returnTypeOrNull, String conventionName, int varargStartIndex) {
        ArgCounters counters = new ArgCounters(checker.getConfig().callingConventions.get(conventionName));
        line("CC_START " + conventionName);
        if (pushFuncPtr != null) {
            pushFuncPtr.run();
        }
        // "Normally one argument maps to one argument register, but
        // instead for a range it maps to two," confirmed directly --
        // so the register index is now a running counter, advanced by
        // however many words the argument just transferred actually
        // occupied, rather than the simple per-argument loop index this
        // used to be. `ArgCounters` itself (see its own doc comment)
        // additionally tracks which register *bank* each word draws
        // from -- int or float -- and the one real ABI difference in how
        // the two conventions this project knows about count them.
        for (int argIndex = 0; argIndex < regArgs.size(); argIndex++) {
            Token arg = regArgs.get(argIndex);
            boolean isTrueVararg = varargStartIndex >= 0 && argIndex >= varargStartIndex;
            boolean promoteToDouble = isTrueVararg; // "in the ... part of a variadic call": an f32 is widened, any float gets win64's register duplication
            emitArgTransfer(arg, counters, promoteToDouble);
            if (arg.isOwnershipMoveSource) {
                // "It seems I need to null it out before the function
                // is called, not after," confirmed directly -- right
                // here, immediately after this argument's own value is
                // captured (into its register(s), or left in place for a
                // stack-passed argument) and strictly before
                // `emitCallInstruction` runs, so the caller's own slot
                // is already null by the time control could possibly
                // reach a `throw` inside the callee. See
                // emitOwnershipMoveNullOut's own doc for the register-
                // clobbering hazard this specific placement creates.
                // (A by-value range is never itself an ownership-
                // move source -- moves apply only to 'owns' pointers,
                // which are always storage-bearing and so always take
                // the ordinary, unsplit single-word path below.)
                emitOwnershipMoveNullOut(arg);
            }
        }
        if (varargStartIndex >= 0) {
            // The SysV "%al must hold the number of vector (xmm)
            // registers used" varargs rule (win64 has no equivalent --
            // `X86Backend` only ever reads this for its own non-win64
            // call sites) needs the real, final count of float-bank
            // registers this call's own arguments actually consumed --
            // only known now, after every argument has been transferred.
            // Emitted only for a genuinely variadic call (never for an
            // ordinary fixed-arity one), immediately before the actual
            // call instruction, so `X86Backend` can consume it from
            // exactly the right call's own preamble -- see
            // `ArgCounters.floatRegistersUsed`'s own doc comment for why
            // this isn't simply `counters.floatIdx` (an unclamped running
            // total, not a real register count), and `X86Backend`'s own
            // `pendingVarargsXmmCount`/`case "VARARGS_XMM_COUNT"` for how
            // this value is actually consumed.
            line("VARARGS_XMM_COUNT " + counters.floatRegistersUsed());
        }
        emitCallInstruction.run();
        line("CC_END " + conventionName);
        if (returnTypeOrNull != null) {
            line("PUSH_RET " + returnTypeOrNull);
        }
    }

    /** Only 'imut'/'mut'/'indeterminate' -- no storage prefix -- immediately before a bare or literal-bounds 'range' base type: a genuine by-value range, never a pointer to one. */
    private static final java.util.regex.Pattern BY_VALUE_RANGE_ARG =
            java.util.regex.Pattern.compile("^(?:imut|mut|indeterminate)_range(?:\\(.*\\))?$");

    /**
     * Transfers one call argument into its register(s), returning the
     * next free register index for the argument after it.
     *
     * An ordinary argument -- including a storage-bearing ('ref
     * range(...)', etc.) range, which is just a single-word pointer
     * like any other -- occupies exactly one register, unchanged from
     * before.
     *
     * A by-value range argument is split into its two u64 bounds:
     * "foo(x) being: PUSH x.start / POP ARG0 / PUSH x.end / POP ARG1...
     * a range maps to two [registers]," confirmed directly.
     *
     * A '..'-literal range argument (e.g. "foo(1..2)" or "foo(a..b)")
     * already naturally emits its two bounds as two separate pushes via
     * the ordinary '..' bytecode (no instruction of its own -- see its
     * own emitOperator case) -- so it needs no synthesized names at
     * all, just the same per-word register bookkeeping any other
     * argument gets. Only a *named* range value (a variable, or a
     * dotted member chain like "self.field") needs its words
     * synthesized, as their own qualified-name pushes -- the exact
     * "PUSH qualifiedName type" shape an ordinary, parsed '.start'/
     * '.end' access (emitDot) already produces for the same names.
     *
     * (This used to also split a by-value slice argument into three
     * words -- its origin, then its range's own two bounds -- and a
     * slice-construction argument's own three-word transfer; the slice
     * type has been removed from the language entirely, so both of
     * those cases are gone along with it.)
     */
    private void emitArgTransfer(Token arg, ArgCounters counters, boolean promoteToDouble) {
        Token unwrapped = unwrapParens(arg);
        if (BY_VALUE_RANGE_ARG.matcher(arg.resolvedType).matches()) {
            // A range's own two words are always plain "indeterminate_u64"
            // bounds, never float -- promotion can never apply here
            // (the caller's own isFloatCanonical check already ensures
            // `promoteToDouble` is always false by the time a range
            // argument reaches this branch; not re-asserted here, just
            // noted).
            emitRangeArgWords(unwrapped, counters);
            return;
        }
        emitArgWord(arg, counters, promoteToDouble);
    }

    /** The range-only portion of `emitArgTransfer`, for a plain range argument. Both of a range's own two words are always "indeterminate_u64" (a bound, never a float), so these always draw from the int bank -- never float -- regardless of the range's own element type. */
    private void emitRangeArgWords(Token unwrapped, ArgCounters counters) {
        if (unwrapped.type == TokenType.OPERATOR && unwrapped.text.equals("..")) {
            emitArgWord(unwrapped.left, counters, false);
            emitArgWord(unwrapped.right, counters, false);
            return;
        }
        String base = qualifiedDotName(unwrapped);
        emitSyntheticArgWord(base + ".start", "indeterminate_u64", counters);
        emitSyntheticArgWord(base + ".end", "indeterminate_u64", counters);
    }

    /**
     * Pushes one ordinary, already-resolved sub-expression (one word)
     * via the generic emitExpr dispatch and transfers it into the next
     * register of its own type's bank, if any remain.
     *
     * `promoteToDouble` -- true only for a genuinely `f32`-typed
     * argument landing in the true, `...`-matched portion of a variadic
     * call (see `emitCallSequence`'s own `varargStartIndex` doc comment)
     * -- inserts a `PROMOTE_F32_TO_F64` line immediately after the value
     * is pushed and before it's transferred into its register/left on
     * the stack: C's own "default argument promotion" rule, widening
     * the real 4-byte value to an 8-byte `double`'s bit pattern in
     * place, occupying the exact same one stack word either way (see
     * that mnemonic's own doc comment in `caspien-codegen`'s
     * `X86Backend` for the full ABI reasoning). This changes nothing
     * about which bank/register the word still draws from -- a
     * promoted `f32` argument is still counted as exactly one
     * float-bank word by `emitArgTransferTail` below, unchanged.
     */
    private void emitArgWord(Token expr, ArgCounters counters, boolean promoteToDouble) {
        emitExpr(expr);
        if (promoteToDouble && isF32Canonical(expr.resolvedType)) {
            line("PROMOTE_F32_TO_F64");
        }
        // `promoteToDouble` is already exactly "a true vararg, and f32" --
        // precisely the same precondition win64's own "also duplicate a
        // variadic float register argument into its same-index integer
        // register" rule needs (see `emitArgTransferTail`'s own doc
        // comment) -- so it doubles as that flag too, rather than
        // threading a second, differently-named boolean through for an
        // identical condition.
        emitArgTransferTail(expr.resolvedType, counters, promoteToDouble);
    }

    /** Pushes a synthesized qualified-name word (e.g. "x.start") -- the same "PUSH qualifiedName type" shape emitDot already produces for an ordinary, parsed access -- and transfers it into the next register of its own type's bank, if any remain. A range's own two words are never float and never reach a true-vararg call site (see `emitArgTransfer`'s own doc comment), so this never needs the win64 varargs-duplication treatment. */
    private void emitSyntheticArgWord(String qualifiedName, String type, ArgCounters counters) {
        line("PUSH " + qualifiedName + " " + type);
        emitArgTransferTail(type, counters, false);
    }

    /**
     * Shared tail of `emitArgWord`/`emitSyntheticArgWord`: having already
     * pushed the word's value, decides which bank it draws from and
     * emits "POP ARGn"/"POP FARGn" if a register is still available in
     * that bank, leaving it as a plain stack-resident push (no POP line
     * at all) otherwise -- unchanged from the pre-float-split behavior,
     * just now type-aware.
     *
     * `isTrueVarargFloat` -- true only for a genuinely `f32`-typed
     * argument landing in the true, `...`-matched portion of a variadic
     * call (identically to `emitArgWord`'s own `promoteToDouble`, which
     * is where this flag actually comes from) -- additionally triggers
     * win64's own, separate ABI requirement for a *register-passed*
     * variadic floating-point argument: "for variadic functions,
     * floating-point arguments must be passed in both the XMM register
     * and the corresponding general-purpose register" (win64's own
     * varargs convention has no separate register-save-area mechanism
     * the way SysV's `%al`-count rule does -- the callee doesn't know
     * statically which of its own already-`shared`-position argument
     * registers were meant to be read as a float and which as an
     * integer, so the caller duplicates a float one into both). This is
     * a genuinely different rule from `PROMOTE_F32_TO_F64` (the SysV/C
     * *value*-widening rule) -- both happen to share the identical
     * "true vararg, and float" precondition, but this one is
     * win64-only, register-shape-only, and needs no separate f64
     * value-level meaning at all: the exact same 8-byte (already
     * promoted) bit pattern is simply read twice, once into each
     * register. Only fires when a real float register is actually
     * available for this word (`regIndex >= 0`) and the convention is
     * genuinely win64's `sharedArgumentPosition` one -- both `ArgCounters`
     * argument-register tables are always sized identically (4 and 4)
     * for a `shared` convention, so a valid float register index here
     * always has a real, same-index integer register to duplicate into
     * as well. A stack-passed (overflowing) variadic float, on either
     * convention, is untouched here -- win64 has no register-save-area
     * duplication concern for a word that was never in a register to
     * begin with.
     */
    private void emitArgTransferTail(String type, ArgCounters counters, boolean isTrueVarargFloat) {
        boolean isFloat = isFloatCanonical(type);
        int regIndex = counters.next(isFloat);
        boolean needsWin64VarargDup = isTrueVarargFloat && isFloat && regIndex >= 0 && counters.isShared();
        if (needsWin64VarargDup) {
            line("DUP_TOP");
        }
        if (regIndex >= 0) {
            line("POP " + (isFloat ? "FARG" : "ARG") + regIndex + " " + type);
            if (needsWin64VarargDup) {
                line("POP ARG" + regIndex + " " + type);
            }
        }
    }

    /**
     * True for a real float base type -- "f32" only, the sole
     * floating-point primitive `TypeChecker.PRIMITIVE_SIZE` defines.
     * `TypeInfo.canonical()` always writes a real base type as the very
     * last segment of the string (storage_mutability_atomic_
     * floatstates_baseType, confirmed directly against its own
     * construction order in `canonical()`), so checking the tail is
     * exact -- the identical "one literal segment can only ever mean
     * one thing" reasoning `isAtomicCanonical`'s own prefix check
     * already relies on, just anchored at the other end of the string.
     */
    private static boolean isFloatCanonical(String canonical) {
        return canonical != null && (canonical.equals("f32") || canonical.endsWith("_f32")
                || canonical.equals("f64") || canonical.endsWith("_f64"));
    }

    /** True only for a 4-byte f32 (the only float a C variadic call must widen to double; an f64 already is one). */
    private static boolean isF32Canonical(String canonical) {
        return canonical != null && (canonical.equals("f32") || canonical.endsWith("_f32"));
    }

    /**
     * Per-call-site running counters deciding which real register (if
     * any) each argument word draws from, and from which bank (int or
     * float) -- confirmed directly this genuinely needs to be
     * convention-aware, not just a bare count: SysV/arm64 count an
     * integer argument and a float argument on two fully independent
     * running counters (a float argument never "uses up" an integer
     * register slot, or vice versa), while win64 shares one running
     * position counter between the two banks (its own documented
     * behavior -- "f(int a, float b, int c)" passes b in XMM1, not
     * XMM0, because it's the *2nd* argument overall, not the 1st float
     * one). See `CompilerConfig.CallingConvention.sharedArgumentPosition`
     * for the full ABI reasoning; this class just applies whichever
     * rule that flag names. A stack-passed word (whichever bank
     * overflowed) still advances its own bank's counter here, exactly
     * as a register-transferred one does -- this class only decides
     * *this* call site's own register assignment, never a real stack
     * offset (an overflowing word is simply left as a plain stack push
     * with no "POP ARGn"/"POP FARGn" line at all, unchanged from
     * before this split; the not-yet-built stack-argument-marshalling
     * work this leaves for later is a separate, already-documented
     * gap).
     */
    private static final class ArgCounters {
        private final boolean shared;
        private final int intCount;
        private final int floatCount;
        private int sharedIdx;
        private int intIdx;
        private int floatIdx;

        ArgCounters(CompilerConfig.CallingConvention convention) {
            this.shared = convention.sharedArgumentPosition;
            this.intCount = convention.argumentRegisters.size();
            this.floatCount = convention.argumentRegistersFloat.size();
        }

        /** Advances this bank's own counter (or the shared one) and returns the real register-table index for the next word of the given type, or -1 if that bank's registers are already exhausted (stack-passed instead). */
        int next(boolean isFloat) {
            int idx;
            if (shared) {
                idx = sharedIdx;
                sharedIdx++;
            } else if (isFloat) {
                idx = floatIdx;
                floatIdx++;
            } else {
                idx = intIdx;
                intIdx++;
            }
            int cutoff = isFloat ? floatCount : intCount;
            return idx < cutoff ? idx : -1;
        }

        /**
         * The real number of float-bank (xmm) *registers* this call's
         * arguments actually consumed, for the SysV "%al must hold the
         * number of vector registers used" varargs rule -- deliberately
         * NOT the same as the raw `floatIdx` running counter, which
         * keeps advancing (unclamped) even once a call's floats have
         * overflowed onto the stack (`next`'s own -1-on-exhaustion
         * return doesn't stop `floatIdx` itself from incrementing, since
         * a stack-passed word still needs its own bank's counter
         * advanced -- see `next`'s own doc comment). Clamped to
         * `floatCount` (the real number of float argument registers
         * this convention has) so an overflowing call still reports the
         * true register count, not an inflated total including its own
         * stack-passed floats. Meaningless (and never consulted) for a
         * `shared`-position convention (win64) -- that ABI has no
         * equivalent varargs rule at all; only ever called for a real,
         * non-win64 variadic call.
         */
        int floatRegistersUsed() {
            return Math.min(floatIdx, floatCount);
        }

        /** True for win64's own "one running position counter shared between int and float argument banks" convention -- see this class's own doc comment. Used by `emitArgTransferTail` to gate win64's separate variadic-float-register-duplication rule, which has no SysV equivalent at all. */
        boolean isShared() {
            return shared;
        }
    }

    /** Unwraps transparent grouping parens (the same '(' shape emitExpr's own DELINEATOR case treats as transparent) down to the real expression underneath, so e.g. "foo((x))" still recognizes "x" as the named range value it is. */
    private Token unwrapParens(Token t) {
        while (t.type == TokenType.DELINEATOR && t.text.equals("(") && t.childs.size() == 1) {
            t = t.childs.get(0);
        }
        return t;
    }

    /** "Funcs that return void don't need this [PUSH_RET]," confirmed directly -- null here means "emit no PUSH_RET at all." Void's canonical form is always exactly "imut_void" or "mut_void" (void never takes a storage prefix), so an exact-equality check against those two spellings, rather than a substring/suffix check, can never be fooled by an unrelated base-type name that merely contains "void" as a substring. */
    private String voidSafeReturnType(Token op) {
        String t = op.resolvedType;
        if (t == null || t.equals("imut_void") || t.equals("mut_void")) {
            return null;
        }
        return t;
    }

    /**
     * A real varargs call site (possible only when the extern's own
     * declaration ended in '...') pushes a different number of
     * arguments at each call site, unlike an ordinary fixed-arity CALL
     * (whose arg count is already fully determined by its target's own
     * FUNC_START/ARG lines) -- so, unlike CALL, this always carries its
     * actual pushed-argument count explicitly, the same way INVOKE
     * already does for function-pointer calls of unknown fixed arity.
     * Never RECURSIVE_CALL -- an extern has no body of its own here to
     * recurse into.
     */
    /**
     * "SLEEP sleep_call duration" -- confirmed directly: a dedicated
     * bytecode mnemonic (not an ordinary "CALL"), carrying the real
     * "@sleep"-decorated function's own mangled name directly as an
     * operand (`op.resolvedCallTarget`, resolved by TypeChecker.
     * checkSleepCall -- never a hardcoded literal the codegen stage
     * would otherwise have to already know, avoiding the exact naming
     * fragility the "@gt_init"/etc. fix addressed), plus the pushed
     * duration argument's own resolved type as a trailing operand (the
     * identical "every instruction states the type of what it operates
     * on" convention NEG/DEREF/CLONE/LEN_SCAN already follow) -- "sleep
     * is used like sleep(duration) where duration is the duration type
     * C expects," confirmed directly. `requireSleepSignature` already
     * guarantees "@sleep" takes exactly one plain "u64" and returns one
     * plain "u64", so there is always exactly one argument to push here
     * and always exactly one "u64"-shaped result to leave behind.
     */
    private void emitSleepCall(Token op) {
        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        Token durationArg = argNodes.get(0);
        emitExpr(durationArg);
        line("SLEEP " + op.resolvedCallTarget + " " + durationArg.resolvedType);
    }

    private void emitExternCall(Token op, TypeChecker.ExternInfo info) {
        List<Token> args = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        int argCount = args.size();
        int varargStartIndex = info.hasVarargs ? info.paramTypes.size() : -1;
        emitCallSequence(null, args, () -> line("EXTERN_CALL " + info.linkName + " " + argCount),
                voidSafeReturnType(op), op.resolvedCallConvention, varargStartIndex);
    }

    private void emitCall(Token op) {
        String funcName = resolveCallTargetForEmission(op.resolvedCallTarget);
        List<Token> args = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        // "par"/"await" no longer reach here at all -- emitCallOrBuiltin
        // intercepts both up front and routes them through emitAsyncCall
        // instead, which desugars the whole call site into ordinary
        // NEW/CALL/ASSIGN-shaped bytecode (see that method's own doc
        // comment). What used to be a bare "PAR_CALL"/"AWAIT_CALL"
        // mnemonic substitution right here is gone along with that --
        // this method's only remaining job is the ordinary CALL/
        // RECURSIVE_CALL choice, including for the trampoline's own
        // direct call into the real "@async" function it wraps
        // (`isTrampolineOwnCall`), which is a perfectly ordinary CALL.
        //
        // "At every call site of a @recursive function that is within
        // that same function, I will be using RECURSIVE_CALL instead of
        // CALL," confirmed directly -- a self-call is only reachable at
        // all (TypeChecker.checkNoRecursion already having run) when the
        // target function is decorated '@recursive', so no further
        // condition needs checking here beyond "is this call's own
        // target the function it appears inside" -- compared *after*
        // dundered redirection, so a dundered duplicate's own self-call
        // correctly resolves to RECURSIVE_CALL against its own dundered
        // name too.
        String mnemonic = funcName.equals(currentFuncMangledName) ? "RECURSIVE_CALL" : "CALL";
        // Staged immediately before the call itself (ahead of
        // `emitCallSequence`'s own argument-pushing sequence too) --
        // see `stageCallSiteForUnwind`'s own doc comment. Argument
        // evaluation happening after the staging write is harmless: the
        // slot is only ever read by a deeper `GT_UNWIND`, which can't
        // happen until the real `CALL`/`RECURSIVE_CALL` instruction
        // itself actually runs.
        stageCallSiteForUnwind(op);
        emitCallSequence(null, args, () -> line(mnemonic + " " + funcName), voidSafeReturnType(op),
                op.resolvedCallConvention);
    }

    /**
     * Desugars a "par"/"await" call site entirely into ordinary
     * NEW/CALL/ASSIGN-shaped bytecode -- confirmed directly as the
     * intended net effect ("PAR_CALL"/"AWAIT_CALL" no longer emitted at
     * all anywhere in the front end). Shape, for both:
     *
     *   ALLOC __async_handle_N handleCanonicalType
     *   ADDR __async_handle_N handleCanonicalType
     *   [[ handle-literal member push sequence, in the handle struct's
     *      own padded layout order -- "value" gets the real argument (if
     *      any), "threadId" starts 0, "state" (non-void only) starts
     *      PENDING, "result" (non-void only) is left as an uninitialized
     *      STACK_LOCK-reserved gap, since nothing may read it before the
     *      trampoline writes it and "state" flips to READY ]]
     *   NEW <HandleStructName>
     *   GT_REGISTER
     *   ASSIGN handleCanonicalType handleCanonicalType handleCanonicalType
     *   CC_START <par_call/await_call's own convention>
     *   PUSH __trampoline_<realFuncName> <its own func-pointer type>
     *   POP ARG0 <that type>
     *   PUSH __async_handle_N handleCanonicalType
     *   POP ARG1 handleCanonicalType
     *   CALL par_call   (or await_call)
     *   CC_END <convention>
     *   -- PAR: PUSH __async_handle_N handleCanonicalType  (the handle
     *      itself is this whole expression's own value; ownership
     *      transfers out to whatever consumes it, exactly like any other
     *      freshly-`new`'d value flowing straight into an assignment)
     *   -- AWAIT: read __async_handle_N.result back out (the same
     *      PUSH+PUSH_FIELDNAME+DOT_LHS+DEREF shape emitDot's own
     *      storage-pointer branch already uses), then GT_DESTRUCT the
     *      now-unneeded handle -- it was never exposed to any caller,
     *      it's this expression's own, purely internal plumbing.
     *
     * The hidden local ("__async_handle_N") is deliberately allocated
     * inline, right here, rather than hoisted to this function's own
     * top the way every other local's `ALLOC` is (see BytecodeEmitter's
     * own "Whole-function ALLOC hoisting" convention) -- a real,
     * accepted, documented deviation: retrofitting arbitrary-depth
     * expression scanning (a "par"/"await" can appear anywhere inside
     * any expression, not just at statement position, unlike the
     * existing hoisting collector's own "let"/"for"/"loop" cases) into
     * the hoisting collector was out of scope for this round, and
     * nothing about correctness actually depends on hoisting position:
     * the low-order address-lowering stage assigns every named local's
     * own frame offset purely from the textual order its `ALLOC` lines
     * appear in, not from where in the function body they sit relative
     * to other statements -- hoisting is a style/optimization goal
     * ("eventually optimized to a single instruction manipulating the
     * stack pointers," per that convention's own doc comment), not a
     * correctness one. Flagged here, not silently done, and mentioned
     * again in this project's own CLAUDE.md.
     */
    private void emitAsyncCall(Token op) {
        boolean isAwait = "AWAIT".equals(op.asyncCallKind);
        String handleStructName = op.asyncHandleStructName;
        String handleCanonical = op.asyncHandleCanonicalType;
        TypeChecker.StructInfo handleStructInfo = checker.getStructs().get(handleStructName);
        List<Token> args = new ArrayList<>();
        if (!op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), args);
        }
        String tempName = "__async_handle_" + (++asyncHandleTempCounter);
        // The argument may contain calls that would consume the pending catch label: hold it back (as emitNew does).
        // Moved-in owns arguments are only let go once the thread really started; if the handle can not be made or the
        // thread can not be started they are destructed here instead (the failed 'par'/'await' never took them).
        final String asyncCatchLabel = pendingTryCatchLabel;
        pendingTryCatchLabel = null;
        final List<Token> movedSources = new ArrayList<>();
        final List<String> movedTemps = new ArrayList<>();
        final List<Token> savedDeferral = deferredMoveNullOuts;
        final List<String> savedTempNames = deferredTempNames;

        line("ALLOC " + tempName + " " + handleCanonical);
        line("ADDR " + tempName + " " + handleCanonical);
        if (handleStructInfo.classId != null) {
            line("PUSH " + handleStructInfo.classId + " imut_u64");
        }
        for (StructLayoutEntry entry : computeStructLayout(handleStructInfo).entries) {
            if (entry.memberName == null) {
                line("STACK_LOCK " + entry.paddingBytes);
                continue;
            }
            if (entry.memberName.equals("___type")) {
                continue;
            }
            switch (entry.memberName) {
                case "state":
                    line("PUSH AsyncState.PENDING " + entry.canonicalType);
                    break;
                case "result":
                    line("STACK_LOCK " + layoutSizeAndAlignOf(entry.canonicalType)[0]);
                    break;
                case "value":
                    if (!args.isEmpty()) {
                        Token argTok = args.get(0);
                        deferredMoveNullOuts = movedSources;
                        deferredTempNames = movedTemps;
                        emitExpr(argTok);
                        deferredMoveNullOuts = savedDeferral;
                        deferredTempNames = savedTempNames;
                        if (argTok.isOwnershipMoveSource && !movedSources.contains(argTok)) {
                            movedSources.add(argTok);
                        }
                    } else {
                        line("PUSH 0 " + entry.canonicalType);
                    }
                    break;
                case "threadId":
                    line("PUSH 0 " + entry.canonicalType);
                    break;
                default:
                    throw new IllegalStateException(
                            "unexpected AsyncHandle_T member '" + entry.memberName + "'");
            }
        }
        line("NEW " + handleStructName);
        requireGhostTableFunctionPresent("gt_register", op);
        line("GT_REGISTER");
        // Same null check as 'new': a failed malloc or a failed registration leaves null here.
        line("DUP_TOP");
        line("PUSH null " + handleCanonical);
        line("EQ " + handleCanonical + " " + handleCanonical + " imut_bool");
        line("CMP");
        String handleOkLabel = newLabel("async_handle_ok");
        line("JMP " + handleOkLabel);
        if (!inGtSuppressedContext && asyncCatchLabel != null) {
            emitDestructMovedSources(movedSources);
            emitDestructTempNames(movedTemps, op);
            emitAsyncFailureJump(asyncCatchLabel, "out of memory");
        }
        line(handleOkLabel + ":");
        line("ASSIGN " + handleCanonical + " " + handleCanonical + " " + handleCanonical);

        String glueDecorator = isAwait ? "await_call" : "par_call";
        TypeChecker.FuncInfo glueInfo = checker.getGhostTableFunction(glueDecorator);
        if (glueInfo == null) {
            throw new CompilerException("bytecode", op.file, op.line,
                    "emitting a '" + (isAwait ? "await" : "par") + "' call here requires a function "
                            + "decorated '@" + glueDecorator + "' to exist somewhere in the compilation unit "
                            + "(none was found)");
        }
        String trampolineName = op.asyncTrampolineName;
        TypeChecker.FuncInfo trampolineInfo = checker.getFunctions().get(trampolineName).get(0);
        String trampolineFuncPtrType = funcPointerCanonicalOf(trampolineInfo);

        line("CC_START " + glueInfo.callConvention);
        line("PUSH " + trampolineName + " " + trampolineFuncPtrType);
        line("POP ARG0 " + trampolineFuncPtrType);
        line("PUSH " + tempName + " " + handleCanonical);
        line("POP ARG1 " + handleCanonical);
        line("CALL " + glueInfo.mangledName);
        line("CC_END " + glueInfo.callConvention);
        // The glue returns false when pthread_create failed: nothing runs the trampoline, so nothing else will ever free
        // the handle (or use the moved-in argument).
        line("PUSH_RET imut_bool");
        line("CMP");
        String threadFailLabel = newLabel("async_thread_fail");
        String threadOkLabel = newLabel("async_thread_ok");
        line("JMP " + threadFailLabel);
        line("JMP " + threadOkLabel);
        line(threadFailLabel + ":");
        if (!inGtSuppressedContext && asyncCatchLabel != null) {
            requireGhostTableFunctionPresent("gt_destruct", op);
            line("GT_DESTRUCT " + tempName);
            emitDestructMovedSources(movedSources);
            emitDestructTempNames(movedTemps, op);
            emitAsyncFailureJump(asyncCatchLabel, "cannot start thread");
        }
        line(threadOkLabel + ":");
        for (Token moved : movedSources) {
            emitOwnershipMoveNullOut(moved); // the thread now owns the argument
        }

        boolean isVoidResult = isVoidCanonical(op.resolvedType);
        if (isAwait) {
            if (!isVoidResult) {
                String resultCanonical = op.resolvedType;
                line("PUSH " + tempName + " " + handleCanonical);
                line("PUSH_FIELDNAME result " + resultCanonical);
                line("DOT_LHS " + handleCanonical + " " + resultCanonical + " " + resultCanonical);
                line("DEREF " + resultCanonical);
            }
            // Safe unconditionally, void or not: "@await_call" already
            // blocked (pthread_join) before this point ever returns, so
            // the trampoline it started is guaranteed finished running by
            // now -- there is no other thread left that could still be
            // touching this handle, unlike the fire-and-forget "par" path
            // just below.
            requireGhostTableFunctionPresent("gt_destruct", op);
            line("GT_DESTRUCT " + tempName);
        } else if (!isVoidResult) {
            // Non-void "par": the handle is this whole expression's own
            // value, handed off to whatever consumes it (a "let", a
            // ".resolve()" call, etc.) -- exactly like any other freshly
            // -"new"'d value. Ownership (and eventually GT_DESTRUCT) is
            // the caller's problem from here on, the same as any other
            // "owns" value flowing out of an expression.
            line("PUSH " + tempName + " " + handleCanonical);
        } else {
            // Void "par": fire-and-forget -- this hidden handle is never
            // bound to anything a caller could ever see or destruct
            // (checkPar exposes "void" as this whole expression's type
            // for exactly this case, not the handle type), so nothing
            // pushed here for anyone to consume. It would otherwise leak
            // forever: the *caller* can never safely `GT_DESTRUCT` it
            // immediately after this non-blocking "par_call" returns
            // (the new OS thread the trampoline is running on may still
            // be reading "handle.value" well after "par_call" itself has
            // already returned -- a real use-after-free/race, not just a
            // leak, if the caller destructed it here). So instead the
            // trampoline itself is responsible for destructing this
            // hidden handle, from *inside* the new thread, only once it
            // is genuinely done reading from it -- see
            // "synthesizeAsyncTrampoline"'s own "selfDestructHidden"
            // parameter and doc comment for the mechanism (the same
            // hidden-handle-shaped trampoline is never reused between a
            // void "par" call site and a void "await" one -- each gets
            // its own trampoline instance precisely so only the "par"
            // one self-destructs; "await"'s own caller-side destruct
            // just above remains correct and non-racy for its own,
            // separate trampoline).
        }
    }

    /** Sets the shared `gt_error_message` and jumps to the catch of the `try`/`?` wrapping a 'par'/'await' (same shape as the 'new' out-of-memory exit). */
    private void emitAsyncFailureJump(String catchLabel, String message) {
        String messageId = hoistedStringId(message);
        line("ADDR gt_error_message static_imut_string");
        line("PUSH " + messageId + " static_imut_string");
        line("ASSIGN static_imut_string static_imut_string static_imut_string");
        line("JMP " + catchLabel);
    }

    /**
     * "Void's canonical form is always exactly 'imut_void' or 'mut_void'"
     * -- the same exact-equality check `voidSafeReturnType` already uses,
     * mirrored here since this call site needs the boolean itself (to
     * decide whether to emit a ".result" read / a final handle push) far
     * more than it needs the substitution `voidSafeReturnType` performs.
     */
    private boolean isVoidCanonical(String canonical) {
        return canonical == null || canonical.equals("imut_void") || canonical.equals("mut_void");
    }

    /**
     * The canonical text a bare reference to `info` (a plain top-level
     * function, never called -- "let foo_ptr = foo") resolves to, per
     * `TypeInfo.funcPointer` (mirrored here rather than reused directly,
     * since that's a `TypeChecker`-side type and this is purely a
     * bytecode-emission-side operand-text need): "imut_func(paramType1,
     * paramType2,...)returnType" -- the exact shape a real "let x = foo"
     * would resolve `x`'s own declared type to, which is all this
     * operand text needs to be for the low-order stage to treat this
     * "PUSH __trampoline_X ..." line as a function-address push, the
     * same mechanism `call()`'s own function-pointer argument already
     * relies on.
     */
    private String funcPointerCanonicalOf(TypeChecker.FuncInfo info) {
        // Mirrors TypeInfo.funcPointer's own canonical() shape exactly:
        // storage is always "static" for a function-pointer value, so
        // the full text is "static_<mutability>_func(params)returnType",
        // never a bare "func(...)" alone.
        StringBuilder sb = new StringBuilder("static_imut_func(");
        for (int i = 0; i < info.paramTypes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(info.paramTypes.get(i).canonical());
        }
        sb.append(')').append(info.returnType.canonical());
        return sb.toString();
    }

    /**
     * "Function calls within these functions need to be to dundered
     * duplicates of the actual functions," confirmed directly --
     * applies uniformly whenever gt-suppression is active (both a gt-
     * decorated function's own body, and every dundered duplicate
     * itself), redirecting any CALL/RECURSIVE_CALL target found in
     * gtReachableMangledNames to its own "__"-prefixed duplicate.
     * Outside gt-suppressed context, or for a target that isn't in the
     * reachable set at all (impossible in practice when suppressed,
     * given the set's own closure property, but checked defensively
     * anyway), the target is returned completely unchanged.
     */
    private String resolveCallTargetForEmission(String mangledName) {
        if (inGtSuppressedContext && gtReachableMangledNames.contains(mangledName)) {
            return "__" + mangledName;
        }
        // "dunder the main and have the @event_loop function be the
        // actual main," confirmed directly -- the real 'main' function
        // is emitted under "__caspien_main" (see emitFunc), so any call
        // actually targeting it (its own type-checked resolvedCallTarget
        // is still plainly "main" -- TypeChecker has no reason to know
        // about this purely-emission-time rename) must be redirected
        // here too, the exact same "resolve at the last possible
        // moment, right before the mnemonic line is written" shape the
        // gt-suppression case just above already uses.
        if (mangledName.equals("main") && checker.getEventLoopFunc() != null) {
            return "__caspien_main";
        }
        return mangledName;
    }

    private void collectCommaArgs(Token node, List<Token> out) {
        if (node.type == TokenType.OPERATOR && node.text.equals(",")) {
            collectCommaArgs(node.left, out);
            collectCommaArgs(node.right, out);
        } else {
            out.add(node);
        }
    }

    private void emitLookup(Token op) {
        String leftType = op.left.resolvedType;
        if (op.left.type == TokenType.VARREF && leftType != null && !hasStorage(leftType)
                && leftType.endsWith("]") && !leftType.contains("dynarray")
                && op.resolvedType != null && op.resolvedType.matches("(mut|imut|indeterminate)_(u8|u16|u32|u64|s8|s16|s32|s64|f32|f64|bool|char)")) {
            // A read of one element of a fixed array held in a variable: take the element's
            // ADDRESS (the same ADDR + LOOKUP_LHS a write uses) and load just that element,
            // instead of pushing the whole array onto the stack first and picking one element out
            // of the copy -- that copy cost O(array size) per read (a 20-byte array read pushed
            // 20 bytes, an n-body inner loop did it ~30 times per pair). Scalar elements only: an
            // aggregate element (a row of a 2D array, a struct) is a block whose size the following
            // LOOKUP/DOT tracks from the PUSH that produced it, and DEREF does not carry that.
            line("ADDR " + op.left.text + " " + leftType);
            Token idx = op.right.childs.get(0);
            emitExpr(idx);
            line("LOOKUP_LHS " + leftType + " " + idx.resolvedType + " " + op.resolvedType);
            line("DEREF " + op.resolvedType);
            return;
        }
        emitExpr(op.left);
        Token indexExpr = op.right.childs.get(0);
        emitExpr(indexExpr);
        line("LOOKUP " + op.left.resolvedType + " " + indexExpr.resolvedType + " " + op.resolvedType);
    }

    /**
     * "value as SomeAlias" (the `isAliasCast` shape -- both sides already
     * share the same underlying storage+baseType, only the nominal alias
     * name differs, and an alias never appears in bytecode text at all)
     * emits no instruction at all beyond compiling the left side -- there
     * is nothing for a runtime instruction to do to an already-identical
     * representation. Only the remaining shape -- same-signedness-family
     * integer widening -- genuinely changes the value's width at
     * runtime, so it alone gets a real instruction: "SEXT" (sign-extend)
     * for a signed family, "ZEXT" (zero-extend) for an unsigned one, in
     * place of the single, size-only "CAST" this used to always emit --
     * "cast just being instances where the compiler can unambiguously do
     * it," confirmed directly, so pointer/float casts stay out of scope
     * here entirely (planned as stdlib functions instead).
     *
     * UPDATE (dead-code cleanup): this used to also special-case
     * `op.right.type == TokenType.MODIFIER` for a third shape, "value as
     * mut/imut" -- a pure compile-time mutability reinterpretation, with
     * the identical zero-instruction treatment. Confirmed that shape no
     * longer reaches here at all: `mut`/`imut` no longer even parses as
     * the right-hand side of `as` (a parser-level removal, not just a
     * `TypeChecker.checkAs` rejection -- see that method's own doc
     * comment), so `op.right` is guaranteed to be a plain type-name
     * VARREF by the time bytecode emission runs, and that branch was
     * dead code. Removed rather than left as defensive dead code, per
     * this project's own "don't guess/handle what can't happen" standing
     * discipline.
     */
    /**
     * 'x fits T' as a runtime test, built only from unsigned 64-bit compares (no signed-compare
     * dependence). x is widened to 64 bits (SEXT/ZEXT by its signedness); then
     *   - x unsigned or T unsigned:   x64 <= Tmax            (a negative signed x is a huge u64, so it fails)
     *   - x signed and T signed:      (x64 + (-Tmin)) <= (Tmax - Tmin)
     * where Tmax for u64 is taken as s64's max when x is signed (negatives already excluded) and the
     * always-true test 'x >= 0' is used when the range covers everything.
     */
    private void emitFits(Token node) {
        String xb = node.fitsSourceBase;
        String tb = node.fitsTargetBase;
        boolean xSigned = xb.charAt(0) == 's';
        boolean tSigned = tb.charAt(0) == 's';
        int xw = Integer.parseInt(xb.substring(1));
        int tw = Integer.parseInt(tb.substring(1));
        String u64 = "indeterminate_u64";
        emitExpr(node.left);
        if (xw < 64) {
            line((xSigned ? "SEXT " : "ZEXT ") + node.left.resolvedType + " " + u64);
        }
        java.math.BigInteger one = java.math.BigInteger.ONE;
        java.math.BigInteger tmax = tSigned ? one.shiftLeft(tw - 1).subtract(one) : one.shiftLeft(tw).subtract(one);
        java.math.BigInteger tmin = tSigned ? one.shiftLeft(tw - 1).negate() : java.math.BigInteger.ZERO;
        boolean alwaysTrue;
        if (!xSigned || !tSigned) {
            // x64 <= Tmax (unsigned). u64 target: an unsigned x always fits; a signed x fits iff non-negative.
            if (tb.equals("u64")) {
                tmax = xSigned ? one.shiftLeft(63).subtract(one) : java.math.BigInteger.ZERO;
                alwaysTrue = !xSigned;
            } else {
                alwaysTrue = false;
            }
            if (alwaysTrue) {
                line("PUSH 0 " + u64);
                line("GT_EQ " + u64 + " " + u64 + " imut_bool");
            } else {
                line("PUSH " + tmax + " " + u64);
                line("LT_EQ " + u64 + " " + u64 + " imut_bool");
            }
            return;
        }
        // both signed
        if (tw == 64 || (xw <= tw)) {
            line("PUSH 0 " + u64);
            line("GT_EQ " + u64 + " " + u64 + " imut_bool");
            return;
        }
        line("PUSH " + tmin.negate() + " " + u64);
        line("ADD " + u64 + " " + u64 + " " + u64);
        line("PUSH " + tmax.subtract(tmin) + " " + u64);
        line("LT_EQ " + u64 + " " + u64 + " imut_bool");
    }


    private static String baseOfCanonical(String canonical) {
        return canonical.substring(canonical.lastIndexOf('_') + 1);
    }

    /**
     * `wrap:<T>(x)` / `sat:<T>(x)` -- total integer conversions.
     * wrap: widen (SEXT/ZEXT by the SOURCE's signedness), relabel (same width) or TRUNC.
     * sat: when every value of x always fits T this is exactly wrap's widening/relabel. Otherwise
     * x is widened to 64 bits and clamped with branch-free arithmetic that uses only unsigned
     * compares and literals below 2^63 (the backend's compares are unsigned), then TRUNC'd.
     */
    private void emitConvert(Token op) {
        final Token arg = singleBuiltinArg(op);
        if (op.isFloatConvert || (op.resolvedType != null && isFloatCanonical(op.resolvedType))) {
            // wrap:<f32>(x) / wrap:<f64>(x): a float conversion (a literal argument was already retagged in place).
            emitExpr(arg);
            if (op.isFloatConvert) {
                line("FCONV " + arg.resolvedType + " " + op.resolvedType);
            }
            return;
        }
        final boolean isSat = op.left.text.equals("sat");
        final String xb = baseOfCanonical(arg.resolvedType);
        final String tb = baseOfCanonical(op.resolvedType);
        final boolean xs = xb.charAt(0) == 's';
        final boolean ts = tb.charAt(0) == 's';
        final int xw = Integer.parseInt(xb.substring(1));
        final int tw = Integer.parseInt(tb.substring(1));
        final String u64 = "indeterminate_u64";
        final String ext = (xs ? "SEXT " : "ZEXT ") + arg.resolvedType + " " + u64;
        final java.math.BigInteger one = java.math.BigInteger.ONE;
        boolean alwaysFits = tw >= xw && (xs ? ts : (!ts || tw > xw));
        if (!isSat || alwaysFits) {
            emitExpr(arg);
            if (tw > xw) {
                line((xs ? "SEXT " : "ZEXT ") + arg.resolvedType + " " + op.resolvedType);
            } else if (tw < xw) {
                line("TRUNC " + arg.resolvedType + " " + op.resolvedType);
            }
            return;
        }
        final java.math.BigInteger tmax = ts ? one.shiftLeft(tw - 1).subtract(one) : one.shiftLeft(tw).subtract(one);
        final String i63 = one.shiftLeft(63).subtract(one).toString();
        final java.util.function.Consumer<String> bin = mn -> line(mn + " " + u64 + " " + u64 + " " + u64);
        final Runnable pushX = () -> {
            emitExpr(arg);
            if (xw < 64) {
                line(ext);
            }
        };
        // Xc: X for unsigned x; max(X,0) for signed x targeting an unsigned T
        final Runnable pushV;
        boolean needClampAbove;
        if (!xs) {
            pushV = pushX;
            needClampAbove = true;
        } else if (!ts) {
            pushV = () -> {                       // X * (X <=u 2^63-1)
                pushX.run();
                pushX.run();
                line("PUSH " + i63 + " " + u64);
                bin.accept("LT_EQ");
                bin.accept("MUL");
            };
            needClampAbove = tw < 64 && one.shiftLeft(xw - 1).subtract(one).compareTo(tmax) > 0;
        } else {
            pushV = null;                         // signed -> narrower signed: handled below
            needClampAbove = false;
        }
        if (pushV != null) {
            pushV.run();
            if (needClampAbove) {
                pushV.run();
                line("PUSH " + tmax + " " + u64);
                bin.accept("GT");
                line("PUSH " + tmax + " " + u64);
                pushV.run();
                bin.accept("SUB");
                bin.accept("MUL");
                bin.accept("ADD");
            }
        } else {
            java.math.BigInteger absMin = one.shiftLeft(tw - 1);
            java.math.BigInteger range = one.shiftLeft(tw).subtract(one);
            pushX.run();                                       // X
            pushX.run();
            line("PUSH " + absMin + " " + u64);
            bin.accept("ADD");
            line("PUSH " + range + " " + u64);
            bin.accept("GT");                                  // notInside
            line("PUSH 0 " + u64);
            line("PUSH " + absMin + " " + u64);
            bin.accept("SUB");                                 // Tmin (wrapped)
            pushX.run();
            line("PUSH " + i63 + " " + u64);
            bin.accept("LT_EQ");                               // X >= 0
            line("PUSH " + range + " " + u64);
            bin.accept("MUL");
            bin.accept("ADD");                                 // fallback
            pushX.run();
            bin.accept("SUB");                                 // fallback - X
            bin.accept("MUL");
            bin.accept("ADD");                                 // X + notInside*(fallback-X)
        }
        if (tw < 64) {
            line("TRUNC " + u64 + " " + op.resolvedType);
        }
    }

    private void emitAs(Token op) {
        emitExpr(op.left);
        if (op.isFloatConvert) {
            line("FCONV " + op.left.resolvedType + " " + op.resolvedType);
            return;
        }
        if (op.isTruncCast) {
            line("TRUNC " + op.left.resolvedType + " " + op.resolvedType);
            return;
        }
        if (op.isAliasCast) {
            return;
        }
        line((op.isSignedWideningCast ? "SEXT " : "ZEXT ") + op.left.resolvedType + " " + op.resolvedType);
    }

    /**
     * "EnumName.Variant" compiles to a single PUSH of the qualified
     * variant name, treated like any other compile-time-known literal
     * value -- there's no separate DOT instruction, and no runtime
     * computation happens (neither side is a value expression to
     * compile: the enum name and variant name are read directly off
     * their own tokens, not emitted through emitExpr).
     */
    /**
     * Both '.' forms -- EnumName.Variant and instance.member (including
     * arbitrarily deep chains like line.start.x) -- compile to the same
     * shape: a single PUSH of the fully-qualified dot-joined name. This
     * works for chains with no extra logic because the qualified name is
     * built recursively: op.left for a chained access is itself another
     * '.' node, not a plain token, so a naive "op.left.text" would only
     * ever capture the innermost segment -- qualifiedDotName walks back
     * down to the base token first.
     */
    private void emitDot(Token op) {
        if (isQualifiedNameableDot(op)) {
            line("PUSH " + qualifiedDotName(op) + " " + op.resolvedType);
            return;
        }
        // The chain's own base isn't a plain, nameable slot -- either
        // because it isn't a bare-VARREF-rooted chain at all (a LOOKUP --
        // "arr[i].x" -- or a call result), or because it IS one but some
        // segment along the way has storage (a pointer), which needs a
        // real runtime dereference to get past, not a constant-folded
        // name. Either way: recurse into the base with an ordinary,
        // fully-recursive emitExpr (which, for a further '.' node, comes
        // straight back through this same method -- so a multi-level
        // chain like "x.y.z" with x/y both pointers is decided per level,
        // never as a single whole-chain check: each hop only ever reads
        // its own field's own width off whatever value the previous hop
        // left on the stack, never bulk-copying an intervening pointee).
        //
        // Once the base's own value is on the stack, this level's own
        // field access branches on whether that base is itself a pointer
        // (has storage) or an inline value:
        //   - no storage: the base IS the containing value, pushed
        //     inline -- DOT's original model, offsetting straight into
        //     it (unchanged from before this comment).
        //   - storage: the base is a runtime address, not the value --
        //     DOT_LHS's exact address-computation logic (base + field
        //     offset) gets the field's own address, and DEREF's exact
        //     load logic then reads exactly this field's own width from
        //     it. Both mnemonics already exist and are already fully
        //     implemented (DOT_LHS for the write side, DEREF for
        //     pointer-dereference reads) -- this reuses them verbatim
        //     rather than inventing a new one.
        emitExpr(op.left);
        line("PUSH_FIELDNAME " + op.right.text + " " + op.resolvedType);
        if (hasStorage(op.left.resolvedType)) {
            line("DOT_LHS " + op.left.resolvedType + " " + op.resolvedType + " " + op.resolvedType);
            line("DEREF " + op.resolvedType);
        } else {
            line("DOT " + op.left.resolvedType + " " + op.resolvedType + " " + op.resolvedType);
        }
    }

    /**
     * True when this '.' chain's own base ultimately bottoms out at a
     * plain, bare-nameable token (a VARREF) -- the only case
     * `qualifiedDotName`'s own base case (returning `node.text`
     * directly) is actually meaningful for. A LOOKUP/CALL/any other
     * expression's own `.text` is an internal marker ("LOOKUP", "CALL"),
     * never a real name -- using it as one silently produced a
     * malformed "PUSH LOOKUP.x"-style operand before this check existed
     * (confirmed directly against real bytecode, not assumed).
     */
    private boolean isQualifiedNameableDot(Token node) {
        if (node.type == TokenType.OPERATOR && node.text.equals(".")) {
            // Per-level check, not just "does the whole chain bottom out
            // at a VARREF": node.left is the segment this level dots
            // into, so it also has to itself be storage-free -- a
            // storage-bearing (pointer) intermediate needs a genuine
            // runtime dereference to move past, which a flat, dot-joined
            // compile-time string can't express. This is the exact same
            // "bail on any storage-bearing segment before the final one"
            // rule AddressLoweringPass.resolveAddress already applies
            // when constant-folding a nameable chain's own address --
            // this check exists precisely so a chain that pass would
            // have to bail on is never handed to it as a flat name in
            // the first place.
            return isQualifiedNameableDot(node.left) && !hasStorage(node.left.resolvedType);
        }
        return node.type == TokenType.VARREF && !hasStorage(node.resolvedType);
    }

    private String qualifiedDotName(Token node) {
        if (node.type == TokenType.OPERATOR && node.text.equals(".")) {
            return qualifiedDotName(node.left) + "." + node.right.text;
        }
        return node.text;
    }

    /**
     * "StructName{ member= value, ... }" -- a struct is a plain value
     * type here, C-struct-style: no pointer, no wrapping instruction,
     * no separate allocation at the construction site. The instance
     * *is* its members' values, laid out inline -- so this compiles to
     * nothing more than each member's value PUSHed in the struct's
     * *declared* member order (read off StructInfo, via the `checker`
     * field set in emit()), regardless of what order the literal itself
     * listed them in. Declaration order matters here specifically
     * because nothing else disambiguates which pushed value is which
     * member anymore -- an earlier version of this emitted a NEW
     * instruction carrying member names precisely to allow written
     * order to differ from declared order, but that doesn't fit a
     * value-type model where the instance is only ever "however many
     * slots the type's size says it is, back to back" with no
     * per-member bookkeeping at runtime. The surrounding ASSIGN/PUSH/RET
     * that consumes this struct value doesn't change at all: it already
     * treats "the value" as a single opaque unit (see emitAssign), and
     * a later bytecode-to-assembly stage is expected to know a given
     * type's total size and copy/move that many slots as one block --
     * nothing here needs to size anything itself.
     */
    /**
     * "the Class ID of the struct is pushed first as a secret argument
     * on every OTC... its not for NEW, it is also when they are on the
     * stack... at the OTC in the bytecode," confirmed directly -- this
     * is the single choke point for every struct-literal construction
     * site in the whole compiler ('new X{...}' funnels through here via
     * emitNew's own "emitExpr(op.left)" call, and a bare, plain-stack
     * 'X{...}' literal reaches here identically, directly) -- so
     * pushing the hidden "___type" value first, right here, covers
     * both without needing two separate call sites to stay in sync.
     * "@untyped are excempt" -- `structInfo.classId` is null for one,
     * so no hidden push happens for it at all, matching emitStruct's
     * own identical exemption for the field's declaration.
     */
    private void emitInstantiate(Token op) {
        String structName = op.left.text;
        TypeChecker.StructInfo structInfo = checker.getStructs().get(structName);
        if (structInfo.classId != null) {
            line("PUSH " + structInfo.classId + " imut_u64");
        }

        if (op.isLockViolationConstruct) {
            // The one, deliberate OTC ("One True Construction")
            // violation this project's locked-struct feature allows
            // (see TypeChecker.checkOtcLockViolation) -- every member
            // except the lock/discriminant field was omitted, so this
            // is the *only* value ever pushed here, followed by a
            // "STACK_LOCK <structName>" line rather than the ordinary,
            // full per-declared-member push sequence below. "this will
            // manipulate the stack pointer directly," confirmed
            // directly -- the not-yet-built assembly stage is expected
            // to actually reserve/zero the omitted members' own stack
            // slots from this one instruction.
            Token lockAssignNode = op.right.childs.get(0).childs.get(0);
            Token lockValue = lockAssignNode.right;
            emitExpr(lockValue);
            if (lockValue.isOwnershipMoveSource) {
                emitOwnershipMoveNullOut(lockValue);
            }
            line("STACK_LOCK " + structName);
            return;
        }

        Map<String, Token> valueByMember = new HashMap<>();
        for (Token lineTok : op.right.childs) {
            Token assignNode = lineTok.childs.get(0);
            valueByMember.put(assignNode.left.text, assignNode.right);
        }
        // Walk the same padded layout emitStruct declared, not just the
        // real members, so the values actually pushed here land at the
        // exact byte offsets StructTable/AddressLoweringPass expect.
        // ___type was already pushed above (it's always the layout's
        // first entry when present), so skip it here to avoid pushing it
        // twice; every other real-member entry is pushed as before, and
        // every padding gap becomes a "move the stack pointer forward
        // this many bytes without pushing a value" STACK_LOCK, the same
        // instruction the OTC lock-violation branch above already uses
        // for exactly that purpose.
        for (StructLayoutEntry entry : computeStructLayout(structInfo).entries) {
            if (entry.memberName == null) {
                line("STACK_LOCK " + entry.paddingBytes);
                continue;
            }
            if (entry.memberName.equals("___type")) {
                continue;
            }
            Token value = valueByMember.get(entry.memberName);
            if (deferredTempNames != null && isOwnsTemporary(value)) {
                emitOwnsTemporaryViaLocal(value, deferredTempNames);
                continue;
            }
            emitExpr(value);
            if (value.isOwnershipMoveSource) {
                // Same reasoning, and same placement principle, as the
                // call-argument case: a *later* field's own value
                // expression can itself contain a throwing call (e.g.
                // "new Foo{a=x, b=someFunc()}"), so this field's own
                // move-source is nulled out immediately, before the
                // loop advances to the next field at all -- not once,
                // afterward, once every field has been pushed.
                if (deferredMoveNullOuts != null) {
                    deferredMoveNullOuts.add(value); // inside `new`: nulled only once the allocation has succeeded
                } else {
                    emitOwnershipMoveNullOut(value);
                }
            }
        }
    }

    /**
     * Moved-in `owns` sources whose null-out `emitNew` is holding back until its allocation succeeds (null outside `new`). If the
     * allocation fails, the field values are gone with the discarded stack image, so the failure branch destructs these sources
     * (which still hold their pointers) instead of leaking them.
     */
    private List<Token> deferredMoveNullOuts = null;

    /** Hidden locals holding moved-in temporaries (call results) for the `new`/`dyn([..])` being emitted; destructed if it fails. Null outside those. */
    private List<String> deferredTempNames = null;

    /** Same, for the elements of a `dyn([..])` literal. */
    private List<String> dynLiteralTempNames = null;

    private int lateAllocPos = -1;
    private List<String> lateAllocs = null;
    private int hiddenLocalCounter = 0;

    /** Declares a compiler-internal local of canonical type `type` in the function being emitted; its ALLOC is added to the function's alloc block when the function ends. */
    private String declareHiddenLocal(String type) {
        String name = "$mv" + (++hiddenLocalCounter);
        lateAllocs.add("ALLOC " + name + " " + type);
        return name;
    }

    /** An `owns` value that exists only as a computed result (a call), not in any variable or field. */
    private boolean isOwnsTemporary(Token value) {
        return value.resolvedType != null && value.resolvedType.startsWith("owns_") && !value.isOwnershipMoveSource
                && value.type == TokenType.OPERATOR && !value.text.equals(".") && lateAllocs != null;
    }

    /** Evaluates the temporary into a hidden local and pushes it from there, so a failing allocation can still free it. */
    private void emitOwnsTemporaryViaLocal(Token value, List<String> tempNames) {
        String type = value.resolvedType;
        String tmp = declareHiddenLocal(type);
        line("ADDR " + tmp + " " + type);
        emitExpr(value);
        line("ASSIGN " + type + " " + type + " " + type);
        line("PUSH " + tmp + " " + type);
        tempNames.add(tmp);
    }

    private void emitDestructTempNames(List<String> names, Token at) {
        for (String n : names) {
            requireGhostTableFunctionPresent("gt_destruct", at);
            line("GT_DESTRUCT " + n);
        }
    }

    /** `GT_DESTRUCT` for each moved-in source that is a plain `owns` variable or field path; temporaries are not addressable and are skipped. */
    private void emitDestructMovedSources(List<Token> sources) {
        for (Token t : sources) {
            if (t.resolvedType == null || !t.resolvedType.startsWith("owns")) {
                continue;
            }
            if (t.type == TokenType.VARREF) {
                requireGhostTableFunctionPresent("gt_destruct", t);
                line("GT_DESTRUCT " + t.text);
            } else if (t.type == TokenType.OPERATOR && t.text.equals(".") && isQualifiedNameableDot(t)) {
                requireGhostTableFunctionPresent("gt_destruct", t);
                line("GT_DESTRUCT " + qualifiedDotName(t));
            }
        }
    }

    /**
     * "owns x" never reaches here -- TypeChecker rejects it unconditionally
     * (checkAddressOf), since nothing is ever heap-resident yet. "static x"
     * applied to a literal is a pure compile-time reinterpretation (the
     * literal already inherently has static storage, or is being placed
     * there explicitly) -- no runtime representation change, so (like a
     * mutability-only 'as' cast) it emits nothing beyond compiling the
     * literal itself (see the early return in emitAddressOf, just below).
     * "auto x", "raw x" and "ref x" all do reach here, and all three are
     * genuine, well-defined address-of operations, confirmed directly by
     * compiling real examples -- not a placeholder, despite ADDR_OF being
     * a mnemonic this project invents rather than one the spec names.
     * `emitExpr(op.left)` always runs first, so the value ADDR_OF's own
     * operand computes to is already on the stack by the time ADDR_OF
     * itself runs; what ADDR_OF does with that value splits on whether
     * `op.left` was addressable (a bare VARREF or a dot chain rooted in
     * one -- `TypeChecker.isAddressableLvalue`, required today for both
     * "auto" and "raw"): when it was, `emitExpr` collapsed to a single
     * "PUSH qualifiedDotName type" line (emitDot's own shape for any
     * nameable chain), and that name's own resolved offset *is* the real
     * address -- see AddressLoweringPass's own "ADDR_OF opText leftType
     * returnType" -> "ADDR_OF opText $address" rewrite. When it wasn't
     * (a "ref(ownedVar)" operand, or "raw" on a string literal -- the one
     * literal kind still permitted, since it's already hoisted to real
     * static storage by TypeChecker's own STRING case), the pushed value
     * already *is* the correct final pointer, and ADDR_OF is a pure,
     * droppable no-op retag -- the same pass's "ADDR_OF ... -> dropped
     * entirely" case, right next to that rewrite.
     */
    /**
     * "new Point{...}" -- pushes whatever the constructed value's own
     * ordinary emission already produces (a struct literal's per-member
     * PUSHes via the existing, unmodified emitInstantiate; a primitive
     * literal's single PUSH; etc. -- 'new's operand is type-checked and
     * emitted completely normally, nothing new-specific about it), then
     * one "NEW TypeName" line naming the real type to allocate. That
     * name comes directly from `op.resolvedType` (checkNew always
     * produces exactly "owns_" + the real base type, a format this
     * method controls the shape of one call away, not a guess about
     * what's in the string) with the guaranteed "owns_" prefix stripped
     * -- deliberately *not* derived by inspecting any token's text or
     * shape.
     */
    /**
     * "new" allocates and copies, then hands the fresh pointer through
     * the ghost table before anything downstream sees it -- confirmed
     * directly: "malloc(size) amount of memory... copy the data off of
     * the stack into it... then I pass that malloced pointer thru the
     * provided gt_register function, before putting it on the stack,
     * for the next operation/instruction to work with." The
     * malloc+copy half was already right (`NEW`'s own doc comment in
     * X86Backend); this method was missing the registration half
     * entirely -- `NEW` left the fresh pointer on the stack with
     * nothing ever telling the ghost table it exists, so
     * `gt_alive_check`/`match Some(...)` could never find it alive (a
     * real, confirmed gap: a fresh "new Point{...}" was silently
     * unregistered, always reading as not-alive).
     *
     * `GT_REGISTER` mirrors `GT_INIT`/`GT_ALIVE_CHECK`/`GT_DESTRUCT`
     * exactly -- same `requireGhostTableFunctionPresent` gate, same
     * bare (operand-free) mnemonic, needing no lowering-pass rewrite
     * for the identical reason those three don't: nothing on the line
     * to reduce. Placed immediately after `NEW` so it's transparent to
     * every consumer downstream -- `NEW`'s own pointer is what's still
     * on the stack once `GT_REGISTER` returns, registration happening
     * as a pure side effect in between.
     */
    /**
     * UPDATE (throwing 'new'): "if it gets back NULL from the malloc,
     * it throws," confirmed directly -- checked here, right after `NEW`
     * hands back the fresh pointer and before `GT_REGISTER` ever sees
     * it (a null pointer has no business being registered into the
     * ghost table at all). `op.resolvedType` is now "owns_some_" + the
     * real base type (checkNew's own `.withSome(true)`) rather than the
     * old plain "owns_" -- both prefixes are stripped here for `NEW`'s
     * own operand, which only ever wants the bare constructed type,
     * unrelated to the pointer wrapper around it.
     *
     * No stack-peek/DUP-shaped primitive existed for this before
     * `DUP_TOP` was reused here -- "duplicates the top 8-byte stack
     * word in place (pop, then push it back twice)" is exactly what's
     * needed to inspect the just-allocated pointer against null without
     * disturbing the one copy every caller of `emitNew` already expects
     * to find on top of the stack when this method returns (confirmed
     * directly against a real compiled fixture: this method's whole
     * contract, both before and after this change, is "leave exactly
     * one value -- the pointer -- on the stack").
     *
     * The null branch is deliberately NOT a replica of `emitThrow`'s own
     * EXIT|EXIT_THREAD|GT_UNWIND ending -- that ending exists only for
     * a real `throw` (or a throwing CALL with no *local* catch to land
     * in), which genuinely crosses a function-call boundary and so
     * needs `GT_UNWIND`'s own frame-teardown-then-read-the-next-frame's-
     * slot dance to get back to whichever frame is now current. A
     * `new`'s own null check never crosses any such boundary -- it's
     * ordinary inline code, still in the exact same frame its own
     * `try new ... catch(e){...}` wrapper's hoisted catch block also
     * lives in (`checkNew` requires `op.insideTry` unconditionally, so
     * there's always exactly one, and it's always local -- unlike a
     * throwing CALL, 'new' can never be the "propagate via a fresh,
     * function-exiting landing pad" case `stageCallSiteForUnwindNames`
     * also has to handle). So the null branch here is nothing more than
     * an ordinary, local `JMP` straight to that catch label -- read off
     * `pendingTryCatchLabel` exactly the way `stageCallSiteForUnwindNames`
     * does for the identical "local catch" case, but consumed directly
     * here instead of being staged through `gt_routine_address` at all
     * (there's no deeper frame that will ever need to find it there).
     * The one real side effect that still has to happen first is
     * writing `gt_error_message` -- `emitHoistedCatchBlocks`'s own
     * catch-block prologue unconditionally snapshots it into `e` the
     * instant control reaches the label, exactly as it would for a real
     * `throw`. The message itself is a single shared "out of memory"
     * string literal -- `hoistedStringId` already dedupes by raw
     * content, so every 'new' null-check across the whole program
     * reuses the same one `STRING` entry.
     */
    /**
     * "new MyClass(...)" -- op.left is a struct-RVO call (a constructor,
     * or any other plain struct-by-value-returning function; see
     * TypeChecker.checkNew's own doc comment for why this is legal as a
     * fourth RVO destination position). Can't just fall through to the
     * ordinary "emitExpr(op.left)" below -- a struct-RVO call never
     * pushes its result the ordinary way (no PUSH_RET at all; the callee
     * writes through a hidden destination pointer it's handed directly)
     * -- so this constructs into a hidden, ordinary, whole-function-
     * ALLOC'd local first (deliberately emitted inline rather than
     * hoisted, the identical, already-documented, accepted deviation
     * `emitAsyncCall`'s own hidden handle local already established --
     * retrofitting arbitrary-depth expression scanning into the ALLOC-
     * hoisting collector for this one, rare shape is out of scope), via
     * the exact same `emitStructRvoCallSequence` an ordinary "let tmp =
     * mut ctor()" already uses. Once that temp is fully populated, its
     * value is pushed the ordinary, whole-value way (a plain "PUSH name
     * type" -- `emitDot`/every other whole-struct read already do
     * exactly this for an ordinary named local) -- from here on this
     * method is completely unmodified: `NEW`/`GT_REGISTER` neither know
     * nor care whether the value they just saw pushed came from an
     * ordinary literal or from this hidden temp.
     */
    private void emitNewFromStructRvoCall(Token callOp) {
        // Same "resolve the struct name from the callee's own return
        // type, falling back to parsing the call node's own resolvedType
        // text" precedent `emitStructRvoAssign` already established.
        String funcName = resolveCallTargetForEmission(callOp.resolvedCallTarget);
        TypeChecker.FuncInfo targetInfo = findFuncInfoByMangledName(funcName);
        String structName = targetInfo != null ? targetInfo.returnType.baseType
                : callOp.resolvedType.substring(callOp.resolvedType.lastIndexOf('_') + 1);
        String tempName = "$new_ctor_tmp" + (++labelCounter);
        String structType = "mut_" + structName;
        String rawPtrType = "raw_indeterminate_" + structName;
        line("ALLOC " + tempName + " " + structType);
        emitStructRvoCallSequence(callOp, rawPtrType, () -> {
            line("PUSH " + tempName + " " + structType);
            line("ADDR_OF RAW " + structType + " " + rawPtrType);
        });
        line("PUSH " + tempName + " " + structType);
    }

    private void emitNew(Token op) {
        Token unwrapped = unwrapMutWrappers(op.left);
        List<Token> savedDeferral = deferredMoveNullOuts;
        List<Token> movedSources = new ArrayList<>();
        deferredMoveNullOuts = movedSources;
        final List<String> savedTempNames = deferredTempNames;
        final List<String> movedTemps = new ArrayList<>();
        deferredTempNames = movedTemps;
        // A call nested in the field values would consume the pending catch label (meant for the call-site staging of the
        // first call), and the allocation check below would then have none -- an unchecked null: hold it back and restore it.
        final String newCatchLabel = pendingTryCatchLabel;
        pendingTryCatchLabel = null;
        if (unwrapped.isStructRvoCall) {
            // A '@throws' constructor is itself the call the try/'?' guards: it must stage the catch label (an unstaged
            // throw would unwind past this function's own catch into the caller). The label is restored for the allocation check below.
            TypeChecker.FuncInfo ctorInfo = findFuncInfoByMangledName(resolveCallTargetForEmission(unwrapped.resolvedCallTarget));
            if (ctorInfo != null && ctorInfo.isThrows) {
                pendingTryCatchLabel = newCatchLabel;
            }
            emitNewFromStructRvoCall(unwrapped);
        } else {
            emitExpr(op.left);
        }
        deferredMoveNullOuts = savedDeferral;
        deferredTempNames = savedTempNames;
        pendingTryCatchLabel = newCatchLabel;
        requireGhostTableFunctionPresent("gt_register", op);
        String constructedType = op.resolvedType.substring("owns_".length());
        if (constructedType.startsWith("some_")) {
            constructedType = constructedType.substring("some_".length());
        }
        line("NEW " + constructedType);
        // Registered right away: when the ghost table can not grow, GT_REGISTER frees the block and leaves null, so the
        // check below sees a failed registration exactly like a failed allocation (same catch, same freeing of moved sources).
        line("GT_REGISTER");
        line("DUP_TOP");
        line("PUSH null " + op.resolvedType);
        line("EQ " + op.resolvedType + " " + op.resolvedType + " imut_bool");
        line("CMP");
        String okLabel = newLabel("new_ok");
        line("JMP " + okLabel);
        if (!inGtSuppressedContext && pendingTryCatchLabel != null) {
            emitDestructMovedSources(movedSources); // the moved-in values are lost with the failed allocation: free them
            emitDestructTempNames(movedTemps, op);
            String oomStringId = hoistedStringId("out of memory");
            line("ADDR gt_error_message static_imut_string");
            line("PUSH " + oomStringId + " static_imut_string");
            line("ASSIGN static_imut_string static_imut_string static_imut_string");
            line("JMP " + pendingTryCatchLabel);
        }
        line(okLabel + ":");
        for (Token moved : movedSources) {
            emitOwnershipMoveNullOut(moved); // the allocation took ownership: now the sources let go
        }
    }

    /**
     * Runtime allocation-failure check for a throwing safe `dyn`/`resize`
     * -- the exact same "DUP_TOP / PUSH null / EQ / CMP / JMP okLabel /
     * (on null) set gt_error_message, JMP pendingTryCatchLabel / okLabel:"
     * dance `emitNew` already does for 'new', just above, factored out
     * here so the three call sites that now need it ("dyn([...])",
     * "dyn(text)", and "resize(...)"'s own safe variants -- see
     * TypeChecker.checkDynBuiltinCore/checkResizeBuiltin's own doc
     * comments, "new throws, but dyn and resize dont, they need to use
     * the same pattern as new," confirmed directly) don't each duplicate
     * it by hand. Expects the freshly-allocated/-reallocated value to
     * already be sitting on top of the stack (immediately after the
     * "NEW_DYN"/"NEW_FROM_STRING"/"RESIZE" line itself), exactly like
     * `emitNew` expects right after its own "NEW" line.
     *
     * Deliberately does NOT call `GT_REGISTER` the way `emitNew` does
     * right after its own equivalent check: a dynarray's own backing
     * store is never itself a gt-tracked destructor-chain member the way
     * a freshly-`new`'d struct is (nothing importing "gt_register" is
     * required for `dyn`/`resize` today, and this change doesn't start
     * requiring it) -- only the null-check-and-jump-to-catch half of
     * `emitNew`'s own sequence actually applies here.
     */
    private void emitAllocFailureCheck(Token op) {
        emitAllocFailureCheck(op, java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    /** `movedSources`: owns variables just moved into the allocation (e.g. the elements of `dyn([a, b])`); freed if it fails. */
    private void emitAllocFailureCheck(Token op, List<Token> movedSources, List<String> movedTemps) {
        line("DUP_TOP");
        line("PUSH null " + op.resolvedType);
        line("EQ " + op.resolvedType + " " + op.resolvedType + " imut_bool");
        line("CMP");
        String okLabel = newLabel("alloc_ok");
        line("JMP " + okLabel);
        if (!inGtSuppressedContext && pendingTryCatchLabel != null) {
            emitDestructMovedSources(movedSources);
            emitDestructTempNames(movedTemps, op);
            String oomStringId = hoistedStringId("out of memory");
            line("ADDR gt_error_message static_imut_string");
            line("PUSH " + oomStringId + " static_imut_string");
            line("ASSIGN static_imut_string static_imut_string static_imut_string");
            line("JMP " + pendingTryCatchLabel);
        }
        line(okLabel + ":");
    }

    private void emitAddressOf(Token op) {
        emitExpr(op.left);
        if (op.text.equals("static")) {
            return;
        }
        line("ADDR_OF " + op.text.toUpperCase() + " " + op.left.resolvedType + " " + op.resolvedType);
    }

    private String literalValueOf(Token node) {
        switch (node.type) {
            case STRING:
                // Hoisted -- every occurrence of this exact string
                // value, everywhere in the program, is replaced with
                // its shared id; the real quoted-and-escaped text only
                // ever appears once, in this id's own "STRING id
                // \"text\"" declaration (emitted at the very top of the
                // output -- see emit()).
                return hoistedStringId(node.literalValue);
            case CHAR:
                return "'" + escapeForBytecode(node.literalValue) + "'";
            case NULL:
                return "null";
            default:
                return node.text; // INTEGER, FLOAT, BOOL
        }
    }

    /**
     * "Every char/string literal token records its own decoded value
     * as `literalValue`" -- meaning by the time this runs, a literal
     * like "'\0'" has already been decoded down to one real NUL byte,
     * not the two source characters '\' and '0'. Re-escaping it back
     * into text form for the plain-text bytecode output is this
     * method's whole job -- but it only ever handled three of the
     * eight control-character escapes this language's own lexer
     * recognizes (`\\`/`\"`/`\n`), a real, separate bug found while
     * building a generic String class: "'\0'" (needed for a genuine
     * bounds-safe `charAt`) was leaking through as one literal raw NUL
     * byte, embedded directly in what's supposed to be a plain-text
     * file -- confirmed directly (not assumed) by inspecting the
     * actual emitted bytes. Every one of the lexer's own recognized
     * escapes is now handled explicitly here too.
     */
    private String escapeForBytecode(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                case '\r': sb.append("\\r"); break;
                case '\0': sb.append("\\0"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case 7: sb.append("\\a"); break; // bell -- no Java char-literal shorthand
                case 11: sb.append("\\v"); break; // vertical tab -- no Java char-literal shorthand
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}

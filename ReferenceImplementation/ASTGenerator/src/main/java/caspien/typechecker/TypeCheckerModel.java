package caspien.typechecker;
import caspien.config.CompilerConfig;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 6: type and logic checking over the import-resolved, tree-built program.
 * Type equality in comparisons is base-type only; see docs/TypeChecker.md, "Design decisions".
 */

/** TypeCheckerModel: fields, nested types and static helpers of TypeChecker (part 1 of 11; see README.md in this folder). */
public final class TypeCheckerModel {
    private final TypeChecker hub;

    TypeCheckerModel(TypeChecker hub) {
        this.hub = hub;
    }

    /** The loaded compiler.config (required): validates @call_convention names, supplies the default convention, and is handed to BytecodeEmitter via getConfig. */
    public CompilerConfig config;

    /** `unsafe` reasons that cannot free memory by themselves; a block with only these keeps the alive proofs (see the 'unsafe' statement case). */
    public static final Set<String> NON_FREEING_UNSAFE_TAGS = new HashSet<>(Arrays.asList(
            "memcopy", "raw", "deref", "clone", "global", "loop", "atomic", "assume"));

    public static final Set<String> NUMERIC_TYPES = new HashSet<>(Arrays.asList(
            "u64", "s64", "u32", "s32", "u16", "s16", "u8", "s8", "f32", "f64"));
    /** The two IEEE-754 float types: f32 (4 bytes) and f64 (8 bytes). */
    public static final Set<String> FLOAT_TYPES = new HashSet<>(Arrays.asList("f32", "f64"));
    public static boolean isFloatType(String baseType) {
        return baseType != null && FLOAT_TYPES.contains(baseType);
    }
    public static final Set<String> INTEGER_TYPES = new HashSet<>(Arrays.asList(
            "u64", "s64", "u32", "s32", "u16", "s16", "u8", "s8"));
    /** Reserved builtin names; a user function may not shadow them. */
    public static final Set<String> BUILTIN_NAMES = new HashSet<>(Arrays.asList(
            "sizeof", "len", "range", "deref", "clone", "call",
            "bits_left", "bits_right", "bits_rotl", "bits_rotr", "bits_or", "bits_and", "bits_xor", "bits_not", "memcopy", "dyn", "resize", "Some",
            "insecure_rand", "wrap", "sat"
    ));
    /** Float state modifiers: contextual words (type annotation before f32, match-on-f32 case labels), not lexer keywords. */
    public static final Set<String> FLOAT_STATE_NAMES = new HashSet<>(Arrays.asList("finite", "infinite", "nan"));
    /** Byte size per primitive, used only by 'sizeof'; bool and char are assumed 1 byte. See docs/TypeChecker.md, "Built-in names and sizes". */
    public static final Map<String, Long> PRIMITIVE_SIZE = new HashMap<>();
    static {
        PRIMITIVE_SIZE.put("u8", 1L); PRIMITIVE_SIZE.put("s8", 1L);
        PRIMITIVE_SIZE.put("u16", 2L); PRIMITIVE_SIZE.put("s16", 2L);
        PRIMITIVE_SIZE.put("u32", 4L); PRIMITIVE_SIZE.put("s32", 4L);
        PRIMITIVE_SIZE.put("u64", 8L); PRIMITIVE_SIZE.put("s64", 8L);
        PRIMITIVE_SIZE.put("f32", 4L);
        PRIMITIVE_SIZE.put("f64", 8L);
        PRIMITIVE_SIZE.put("bool", 1L);
        PRIMITIVE_SIZE.put("char", 1L);
    }
    /** Assumed 8 (64-bit), not stated explicitly anywhere -- every pointer, of any storage or pointee, is this size for 'sizeof' purposes. */
    public static final long POINTER_SIZE = 8L;
    /** Types that must carry a storage modifier (owns/ref/raw/auto/static) wherever explicitly annotated. */
    public final Set<String> pointerLikeBaseTypes = new HashSet<>(Arrays.asList("string"));
    /** Unary minus on an unsigned type promotes to the matching signed type -- u64 can't represent -2. */
    public static final Map<String, String> UNSIGNED_TO_SIGNED = new HashMap<>();
    static {
        UNSIGNED_TO_SIGNED.put("u64", "s64");
        UNSIGNED_TO_SIGNED.put("u32", "s32");
        UNSIGNED_TO_SIGNED.put("u16", "s16");
        UNSIGNED_TO_SIGNED.put("u8", "s8");
    }
    /** Bit width per integer type, used by 'as' to enforce widening-only casts within one signedness family. */
    public static final Map<String, Integer> INTEGER_WIDTH = new HashMap<>();
    static {
        INTEGER_WIDTH.put("u8", 8);
        INTEGER_WIDTH.put("u16", 16);
        INTEGER_WIDTH.put("u32", 32);
        INTEGER_WIDTH.put("u64", 64);
        INTEGER_WIDTH.put("s8", 8);
        INTEGER_WIDTH.put("s16", 16);
        INTEGER_WIDTH.put("s32", 32);
        INTEGER_WIDTH.put("s64", 64);
    }

























    /** The reasons a statement-level `unsafe` block must name (`unsafe deref extern{`); `unaudited` is the catch-all, written alone, and never allowed in the stdlib. See docs/TypeChecker.md, "Unsafe block tags". */
    public static final Set<String> UNSAFE_TAGS = new java.util.LinkedHashSet<>(Arrays.asList(
            "extern", "memcopy", "raw", "deref", "clone", "global", "loop", "udyn", "udyn:owns",
            "assume", "call", "asm", "async", "atomic", "file", "unaudited"));





    public final Map<String, UnsafeBlockRecord> unsafeBlockRecords = new java.util.LinkedHashMap<>();



    public final Map<String, StructInfo> structs = new HashMap<>();
    /** Struct names in declaration order (imports included), for generateClassHierarchyEnums; appended only by registerStructLike. */
    public final List<String> structDeclOrder = new ArrayList<>();
    public final Map<String, EnumInfo> enums = new HashMap<>();
    /** The list `check(...)` was given, shared with BytecodeEmitter, so root tokens appended later (par's AsyncHandle_T, see getOrCreateAsyncHandleType) reach emission. */
    public List<Token> rootLinesRef;
    public final Map<String, List<FuncInfo>> functions = new LinkedHashMap<>();
    public final Map<String, InterfaceInfo> interfaces = new HashMap<>();
    /** extern name -> signature; collectFunc/collectExtern cross-check so an extern and a function never share a name. */
    public final Map<String, ExternInfo> externs = new HashMap<>();
    /** Top-level func names already claimed by an 'export' (a second export of the name, or of overloads sharing it, is an error). */
    public final Set<String> exportedNames = new HashSet<>();
    /** Named root-level ASM declarations, in their own namespace. Visible from any function body; a root-level "asm_name;" invocation is processed in file order. See collectAsmDeclarationsAndInvocations. */
    public final Map<String, AsmInfo> rootAsms = new HashMap<>();
    /** Concrete type name -> every impl block declared for it (at most one bare impl, plus at most one per interface -- enforced at collection time). */
    public final Map<String, List<ImplInfo>> implsByConcreteType = new LinkedHashMap<>();
    /** Every generic constructor's FuncInfo, registered by collectImpl into `functions` (so check()'s two body loops miss it); check() runs a third loop over this list. See docs/TypeChecker.md, "Generic constructors". */
    public final List<FuncInfo> genericConstructorFuncInfos = new ArrayList<>();



    /** Methods/constructors dropped from a generic impl instantiation because their signature is illegal for the type argument, keyed "Concrete.method" or "__ctor__Concrete"; reasons are appended to a later "no match" error (unavailableNote). */
    public final Map<String, List<String>> unavailableGenericMethods = new HashMap<>();
    /** Library name -> LibraryInfo; shares its namespace with structs/enums/interfaces (registerLibraryName cross-checks). */
    public final Map<String, LibraryInfo> libraries = new LinkedHashMap<>();
    /** Root-level "let static NAME = ..." globals -- name -> resolved type. */
    public final Map<String, TypeInfo> globals = new HashMap<>();
    /** "type NAME ..." aliases: name -> raw target tokens, resolved lazily per use, so an unused alias is never validated. */
    public final Map<String, List<Token>> aliasRawTargets = new HashMap<>();    /** Root-list position each global was declared at, for "cannot be referenced before declaration." */
    public final Map<String, Integer> globalDeclOrder = new HashMap<>();
    /** Declaration order, so BytecodeEmitter emits every 'GLOBAL' line in a stable, deterministic order. */
    public final List<String> globalOrder = new ArrayList<>();
    /** Each global's own initializer expression, kept for BytecodeEmitter to fold into 'GLOBAL' lines later. */
    public final Map<String, Token> globalRhsByName = new HashMap<>();
    /** Public root-level globals (private by default; a local 'let static' needs no entry). The declaring file is in globalRhsByName. */
    public final Set<String> publicGlobals = new HashSet<>();
    /** "const NAME EXPR": pure compile-time substitution; name -> unevaluated expression root, evaluated lazily on first use (consts are forward-declarable). */
    public final Map<String, Token> constExprs = new HashMap<>();
    /** Memoized evaluateConstant result per const. */
    public final Map<String, Token> constResolvedValues = new HashMap<>();
    /** Consts mid-evaluation, for cycle detection. */
    public final Set<String> constsBeingEvaluated = new HashSet<>();
    /** Set per collectDeclarations(): func/impl KEYWORD token (impl methods share the impl's) -> root-list position. */
    public Map<Token, Integer> pendingDeclOrder = new HashMap<>();
    /** Generates unique names for the hidden range-binding variable a for-loop desugars to (see checkForLoop). */
    public int forVarCounter = 0;
    /** Identity of the sole expression of the 'return' being resolved, to tell a bare tail self-call (legal for @recursive) from one nested in a larger expression (requireRecursiveCallReturnSiteOnly). */
    public Token directReturnExprNode;
    /** Null unless 'main' is '@with_tick': the @event_loop function (set by validateTickEventLoop), which BytecodeEmitter makes the entry point instead of main. */
    public FuncInfo eventLoopFunc;

    /** At most one function per ghost-table decorator (@gt_init/@gt_alive_check/@gt_destruct/@gt_register...), filled by validateGhostTableDecoratorSignatures; BytecodeEmitter requires the function at each emission point. */
    public final Map<String, FuncInfo> ghostTableFunctions = new HashMap<>();

    /** Whole-program, syntactic flag: any owns/ref storage modifier, 'new' or 'dyn(...)' appears anywhere. Read by BytecodeEmitter to require GT_INIT in the entry point. See docs/TypeChecker.md, "Whole-program flags". */
    public boolean usesOwnsRefDynNew = false;

    /** Whole-program flag: any 'throw' was checked. OR'd with usesOwnsRefDynNew to decide whether each function gets the gt_routine/GT_UNWIND prologue. See docs/TypeChecker.md, "Whole-program flags". */
    public boolean usesThrow = false;

    /** True while checking anything under a "try new/dyn/resize ... catch" wrapper; nested allocations then share the one catch. See docs/TypeChecker.md, "Checker context flags". */
    public boolean insideThrowingAllocContext = false;

    /** True while checking a "catch(e){ }" body, the only place `continue` exits the try block. Save/restored by checkTry. */
    public boolean insideCatchBody = false;
    /** Innermost loop boundary when the catch body being checked started: a `continue` still targeting it belongs to the catch (skips the try block); one in a loop opened inside the catch belongs to that loop. */
    public Scope catchEnclosingLoopBoundary = null;
    /** Loop-body scopes of user-written `for`/`loop` statements (not the synthesized `match @lock` spin loop): the only scopes a loop `continue` may target. */
    public final Set<Scope> userLoopScopes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Spin-loop scope of the match @lock CLOSED case being checked (null elsewhere, and for OPEN): a `continue` directly inside retries the acquire. */
    public Scope lockClosedLoopScope = null;
    public int lockRetryContinuesSeen = 0;      // every `continue` that retries a `match @lock` acquire

    /** True only while `closedCaseTerminates` runs: makes `statementDefinitelyReturns` count a 'break' as a terminator. */
    public boolean breakCountsAsTerminator = false;

    /** Single-shot: true only for the next resolveExprType call, which may resolve a top-level `&&then`/`||then` (from an if/elseif condition root or through other then-nodes only). See docs/TypeChecker.md, "Checker context flags". */
    public boolean insideShortCircuitEligiblePosition = false;

    /** Single-shot, like insideShortCircuitEligiblePosition: true only where a struct-returning call (RVO) is legal, i.e. a plain let RHS or a bare-variable '=' RHS in checkAssign; not a return. See docs/TypeChecker.md, "Checker context flags". */
    public boolean insideStructRvoDestinationPosition = false;

    /** Name of the struct whose constructor body is being checked, else null. checkInstantiate's OTC gate: once a struct has a constructor, its bare literal is legal only inside it. */
    public String insideConstructorBodyForStruct = null;



    // ---- decorators ("@name(args)") -------------------------------------

    /** Valid on any func-shaped token (top-level func, interface default method, or impl method) regardless of context; @default/@overrides/@realizes are validated separately, per-context, since which of those (if any) is *required* depends on where the func sits. */
    public static final Set<String> FUNC_BASE_DECORATORS = new HashSet<>(Arrays.asList(
            "pure", "recursive", "inline", "call_convention", "reads", "writes",
            "gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve", "par_call", "await_call", "sleep",
            "lock", "lifetime", "async", "pub", "with_tick", "tick", "event_loop", "make_safe_args", "throws", "drop", "fs_root", "fs_unsafe", "non"));
    public final Map<String, String> dropHookOwners = new HashMap<>();

    /** True for a file inside the compiler's own stdlib folder (the project's stdlib, see ProjectRoot). */
    public static boolean isStdlibFile(String file) {
        try {
            java.nio.file.Path stdlib = caspien.config.ProjectRoot.stdlib();
            return java.nio.file.Paths.get(file).toAbsolutePath().normalize().startsWith(stdlib);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public final List<String> fsReportLines = new ArrayList<>();

    public static final Set<String> STRUCT_DECORATORS = new HashSet<>(Arrays.asList("unpadded", "untyped", "pub", "lock"));
    public static final Set<String> ENUM_DECORATORS = new HashSet<>(Arrays.asList("non_exhaustive"));
    public static final Set<String> INTERFACE_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    /** Only "pub" is allowed on an impl. */
    public static final Set<String> IMPL_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    public static final Set<String> LIBRARY_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    /** A top-level "let static" has its own decorator allowlist. */
    public static final Set<String> GLOBAL_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    public static final Set<String> LOOP_DECORATORS = new HashSet<>(Arrays.asList("par", "unroll"));
    public static final Set<String> READS_WRITES_ARGS = new HashSet<>(Arrays.asList("all", "self", "others", "globals"));

    public static String pureKind(boolean rt) {
        return rt ? "@pure(rt)" : "@pure";
    }

    /** Decorators an extern accepts (no other decorator has C-linkage meaning). */
    public static final Set<String> EXTERN_DECORATORS = new HashSet<>(Arrays.asList("call_convention", "link_name"));



    public static final Set<String> FUNC_ALL_DECORATORS;
    static {
        FUNC_ALL_DECORATORS = new HashSet<>(FUNC_BASE_DECORATORS);
        FUNC_ALL_DECORATORS.addAll(Arrays.asList("default", "overrides", "realizes"));
    }

    /** @async functions take at most one parameter, and it must fit one machine register (no ranges, no structs). See docs/TypeChecker.md, "Decorator signature checks". */
    public static final Set<String> ASYNC_PARAM_ALLOWED_BASE_TYPES = new HashSet<>(Arrays.asList(
            "u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "bool", "char"));

    // ---- name:type parsing (shared by struct members and func params) ---



    // ---- pass 2: check every function body ---------------------------



    public final List<CallEdge> callEdges = new ArrayList<>();

    public final List<ThrowSite> throwSites = new ArrayList<>();
    /** Functions calling `resize` directly: the seed of checkDynArrayForLoopResizeSafety's reachability walk. */
    public final Set<FuncInfo> funcsCallingResizeDirectly = new HashSet<>();

    /** Per function being checked: `par` handle locals (name -> declaring token), names a `match @lock` was taken on, and owns slots ever moved (must-match rule for locked `par` handles). */
    public Map<String, Token> fnHandleDecls = new LinkedHashMap<>();
    public Set<String> fnLockMatched = new HashSet<>();
    public Set<String> fnEverMoved = new HashSet<>();

    /** Slots moved on only SOME paths into a merge point: still "moved" for use-after-move, but their scope-exit destruct is kept (null-safe). See docs/TypeChecker.md, "Branch merge and partly-moved slots". */
    public final Set<String> partlyMovedSlots = new HashSet<>();

    /** True while the condition of an 'assume match' is being checked: a proof there is an assertion to the checker that never runs, so it may name an arbitrary expression, not only a variable. */
    public boolean assumeKeyMode = false;

    /** > 0 while the operands of a `new` / `dyn` are being checked: an owner moved in there is freed if the allocation fails, so it ends alive proofs. */
    public int newDynDepth = 0;

    /** `v.f`, `v.f[i].g` ...: the variable v when `t` is a member/index path rooted at a bare variable (at least one step), else null. */
    public static String memberPathRoot(Token t) {
        Token cur = t;
        int steps = 0;
        while (cur != null && cur.type == TokenType.OPERATOR && (".".equals(cur.text) || "LOOKUP".equals(cur.text)) && cur.left != null) {
            cur = cur.left;
            steps++;
        }
        return steps > 0 && cur != null && cur.type == TokenType.VARREF ? cur.text : null;
    }

    /** The bare variable on the left of the assignment being checked: assigning to it is not a read of it. */
    public Token assignTargetVar = null;



    /** The value of an integer literal token (underscores, 0x/0b allowed), or -1 when it is not a literal or does not fit. */
    public static long literalLimit(Token t) {
        if (t == null || t.type != TokenType.INTEGER) {
            return -1;
        }
        try {
            String s = t.text.replace("_", "").toLowerCase();
            long v = s.startsWith("0b") ? Long.parseLong(s.substring(2), 2) : Long.decode(s);
            return v < 0 ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The attempt limit used when a registered `impl default match @lock` policy has no u64 `limit` parameter. */
    public static final long LOCK_SPIN_LIMIT = 1_000_000_000L;

    /**
     * Non-fatal diagnostic: records "[warning] file:line - message" in {@link #WARNINGS} (the caller reports them) and checking continues.
     */
    public static final java.util.List<String> WARNINGS = new java.util.ArrayList<>();

    public static void warn(String file, int line, String message) {
        StringBuilder sb = new StringBuilder("[warning] ");
        if (file != null) {
            sb.append(file);
            if (line > 0) {
                sb.append(":").append(line);
            }
            sb.append(" - ");
        }
        sb.append(message);
        WARNINGS.add(sb.toString());
    }

    /** Names the integer types that can hold `v`, for "no overload matches" messages about a literal argument. */
    public static String fittingTypes(java.math.BigInteger v) {
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"u8", "s8", "u16", "s16", "u32", "s32", "u64", "s64"}) {
            if (literalFits(v, name)) {
                sb.append(sb.length() == 0 ? "" : ", ").append(name);
            }
        }
        return sb.length() == 0 ? "no integer type" : sb.toString();
    }

    /** Catch parameters of the catch bodies being checked (name -> the try token that binds it). */
    public final Map<String, Token> activeCatchTries = new HashMap<>();
    /** The operand of the `throw` being checked (a bare catch parameter there is a re-throw, not a use of the message). */
    public Token rethrowOperand = null;

    /** Base types backed by a real atomic CPU instruction: integers, bool, char. */
    public static final Set<String> ATOMIC_ELIGIBLE_BASE_TYPES = new HashSet<>(Arrays.asList(
            "u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "bool", "char"));

    /** Memoization guard for ensureAsyncStateEnum. */
    public boolean asyncStateEnumEnsured;

    public boolean asyncGateEnumEnsured;

    /** One AsyncHandle struct/impl per distinct (result, param, locked) key. */
    public final Map<String, TypeInfo> asyncHandleTypesByResultCanonical = new HashMap<>();

    /** True while the body of a compiler-written par/await glue function (trampoline, drop hook) is being checked: such code is trusted, so it may touch a locked handle's members outside `match @lock` and retry the writer's lock without a bound. */
    public boolean synthesizingGlueBody;

    /** Memoizes which "@async" functions already have a synthesized trampoline (one per function, never per call site or per handle type -- see synthesizeAsyncTrampoline's own doc comment). */
    public final Set<String> asyncTrampolinesSynthesized = new HashSet<>();

    public final Set<String> asyncDropHooksSynthesized = new HashSet<>();

    /** Smallest and largest value an integer base type ("u8".."s64") holds, or null for a non-integer type. */
    public static java.math.BigInteger[] integerBounds(String baseType) {
        Integer bits = INTEGER_WIDTH.get(baseType);
        if (bits == null) {
            return null;
        }
        java.math.BigInteger one = java.math.BigInteger.ONE;
        if (baseType.charAt(0) == 'u') {
            return new java.math.BigInteger[]{java.math.BigInteger.ZERO, one.shiftLeft(bits).subtract(one)};
        }
        return new java.math.BigInteger[]{one.shiftLeft(bits - 1).negate(), one.shiftLeft(bits - 1).subtract(one)};
    }

    /** True when an integer literal of value `v` fits `baseType` exactly. */
    public static boolean literalFits(java.math.BigInteger v, String baseType) {
        java.math.BigInteger[] b = integerBounds(baseType);
        return b != null && v.compareTo(b[0]) >= 0 && v.compareTo(b[1]) <= 0;
    }

    public static String unsignedCounterpart(String signedBase) {
        for (Map.Entry<String, String> e : UNSIGNED_TO_SIGNED.entrySet()) {
            if (e.getValue().equals(signedBase)) {
                return e.getKey();
            }
        }
        return signedBase;
    }

    /** True for a 'raw' pointer value -- the only type C-style pointer arithmetic (scaled by the pointee size) applies to. */
    public static boolean isRawPointerType(TypeInfo t) {
        return t != null && "raw".equals(t.storage) && t.funcPtrParamTypes == null;
    }

    /** For ProofKills.check: every function the program defines, and the link names of its externs. */
    public void checkProofKills() {
        List<FuncInfo> all = new ArrayList<>();
        for (List<FuncInfo> l : functions.values()) {
            all.addAll(l);
        }
        for (List<ImplInfo> l : implsByConcreteType.values()) {
            for (ImplInfo impl : l) {
                all.addAll(impl.methods.values());
                all.addAll(impl.staticMethods.values());
                all.addAll(impl.genericMethodInstances.values());
            }
        }
        for (LibraryInfo lib : libraries.values()) {
            for (List<FuncInfo> l : lib.ownFunctions.values()) {
                all.addAll(l);
            }
        }
        Set<String> ext = new HashSet<>();
        for (ExternInfo e : externs.values()) {
            ext.add(e.linkName);
            ext.add(e.name);
        }
        ProofKills.check(hub, all, ext);
    }

    /** For BytecodeEmitter: the loaded compiler.config this whole compilation is using. */
    public CompilerConfig getConfig() { return config; }

    public boolean isFloatStateName(String text) {
        return FLOAT_STATE_NAMES.contains(text);
    }

    public List<SplitInfo> unsafeSplitInfo() {
        List<SplitInfo> out = new ArrayList<>();
        for (UnsafeBlockRecord rec : unsafeBlockRecords.values()) {
            out.add(new SplitInfo(rec.tok.file, rec.tok.line, rec.declared, rec.childLine, rec.childAssume, rec.childTags, rec.unattributed));
        }
        return out;
    }

    /** For `--audit`: what every checked statement-level `unsafe` block really needed, keyed "absolute file:line" (unioned over generic instantiations). */
    public Map<String, java.util.Set<String>> unsafeTagsNeeded() {
        Map<String, java.util.Set<String>> out = new java.util.HashMap<>();
        for (UnsafeBlockRecord rec : unsafeBlockRecords.values()) {
            String k = java.nio.file.Paths.get(rec.tok.file).toAbsolutePath().normalize() + ":" + rec.tok.line;
            java.util.Set<String> tags = out.get(k);
            if (tags == null) {
                tags = new java.util.TreeSet<>();
                out.put(k, tags);
            }
            tags.addAll(rec.used);
        }
        return out;
    }

    /** True when a value of type `t` holds memory it must free: an `owns` pointer, a dynarray, or an inline struct with such a member. */
    public boolean typeOwnsMemory(TypeInfo root, Set<String> visiting) {
        java.util.Deque<TypeInfo> work = new java.util.LinkedList<>();
        work.push(root);
        while (!work.isEmpty()) {
            TypeInfo t = work.pop();
            if (t == null) {
                continue;
            }
            if ("owns".equals(t.storage)) {
                return true;
            }
            String elemBase = t.storage == null ? hub.declarations.stripArrayDims(t.baseType) : t.baseType;
            StructInfo si = structs.get(elemBase);
            if (si == null || !visiting.add(elemBase)) {
                continue;
            }
            List<TypeInfo> members = new ArrayList<>(si.members.values());
            for (int i = members.size() - 1; i >= 0; i--) {
                work.push(members.get(i));
            }
        }
        return false;
    }

    /** The overload list for a function name, created (in insertion order) on first use. */
    public List<FuncInfo> functionList(String name) {
        List<FuncInfo> list = functions.get(name);
        if (list == null) {
            list = new ArrayList<>();
            functions.put(name, list);
        }
        return list;
    }

    /** The impl list for a concrete type name, created (in insertion order) on first use. */
    public List<ImplInfo> implList(String typeName) {
        List<ImplInfo> list = implsByConcreteType.get(typeName);
        if (list == null) {
            list = new ArrayList<>();
            implsByConcreteType.put(typeName, list);
        }
        return list;
    }
}

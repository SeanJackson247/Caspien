package caspien.bytecodeemitter;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.config.CompilerConfig;
import caspien.config.CompilerException;
import caspien.config.DocComments;
import caspien.token.Token;
import caspien.token.TokenType;
import caspien.config.TraceConfig;
import caspien.typechecker.LockNestingCheck;
import caspien.typechecker.TypeChecker;
import caspien.typechecker.TypeCheckerModel;


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
import caspien.typechecker.EnumInfo;
import caspien.typechecker.ExternInfo;
import caspien.typechecker.FuncInfo;
import caspien.typechecker.ImplInfo;
import caspien.typechecker.LibraryInfo;
import caspien.typechecker.StructInfo;
import caspien.typechecker.TypeInfo;
import caspien.token.Decorator;
import caspien.config.CallingConvention;
import caspien.config.DocComment;

/**
 * Final front-end stage: walks the type-checked tree and builds the stack-based higher-order bytecode (HOB) as a BytecodeProgram (text only when the caller serializes it).
 * Instruction shapes, if/elseif chaining and the ++ / compound-assignment desugaring: see docs/BytecodeEmitter.md, "Instruction shapes".
 */

/** BytecodeEmitterCore: fields, nested types and static helpers of BytecodeEmitter (part 1 of 4; see README.md in this folder). */
final class BytecodeEmitterCore {
    private final BytecodeEmitter hub;

    BytecodeEmitterCore(BytecodeEmitter hub) {
        this.hub = hub;
    }

    /** The mangled name of the function currently being emitted -- set/cleared around each emitFunc call, read by emitCall to detect a self-call (needed for RECURSIVE_CALL vs CALL). */
    public String currentFuncMangledName;
    /** Same lifetime as `currentFuncMangledName`, set/cleared alongside it -- needed by `emitDestructList` to look up an `owns` local's own type (`TypeChecker.FuncInfo.ownsLocalTypes`) when nulling a slot out after its own natural-end-of-scope destruct. */
    public FuncInfo currentFuncInfo;

    /** Landing pads staged for call sites of the current function, drained before its FUNC_END; reset per emitFuncUnderName. See docs/BytecodeEmitter.md, "Call-site unwinding". */
    public List<PendingUnwindLandingPad> pendingCallSiteUnwindLandingPads = new ArrayList<>();

    /** "try" token -> catch label assigned by emitHoistedCatchBlocks before the body is emitted; reset per function. */
    public Map<Token, String> tryCatchLabels = new HashMap<>();
    /** try-block token -> its "after" label, and continue token -> the try-block label it targets; precomputed per function by collectTryBlockLabels. */
    public Map<Token, String> tryBlockLabels = new HashMap<>();
    public Map<Token, String> continueTargetLabels = new HashMap<>();

    /** One-shot: a "try" points the next call-site staging write at its catch label instead of a fresh landing pad. See docs/BytecodeEmitter.md, "Call-site unwinding". */
    public String pendingTryCatchLabel;



    /** Names of the gt-decorated functions, and of everything they transitively call (each of those is emitted twice, the second as a "__" duplicate). See docs/BytecodeEmitter.md, "Ghost-table suppression". */
    public Set<String> gtDecoratedFuncMangledNames = new HashSet<>();
    public Set<String> gtReachableMangledNames = new HashSet<>();
    /** True while emitting a gt-decorated function or any "__" duplicate: GT_DESTRUCT is skipped, a bare 'match' needing GT_ALIVE_CHECK is an error, calls into gtReachableMangledNames go to the "__" copy. */
    public boolean inGtSuppressedContext = false;

    public static final Map<String, String> ARITH_NAMES = new HashMap<>();
    static {
        ARITH_NAMES.put("+", "ADD");
        ARITH_NAMES.put("-", "SUB");
        ARITH_NAMES.put("*", "MUL");
        ARITH_NAMES.put("/", "DIV");
        ARITH_NAMES.put("%", "MOD");
    }
    public static final Map<String, String> COMPOUND_TO_ARITH = new HashMap<>();
    static {
        COMPOUND_TO_ARITH.put("+=", "+");
        COMPOUND_TO_ARITH.put("-=", "-");
        COMPOUND_TO_ARITH.put("*=", "*");
        COMPOUND_TO_ARITH.put("/=", "/");
        COMPOUND_TO_ARITH.put("%=", "%");
    }
    public static final Map<String, String> COMPARISON_NAMES = new HashMap<>();
    static {
        COMPARISON_NAMES.put("<", "LT");
        COMPARISON_NAMES.put(">", "GT");
        COMPARISON_NAMES.put("<=", "LT_EQ");
        COMPARISON_NAMES.put(">=", "GT_EQ");
        COMPARISON_NAMES.put("==", "EQ");
        COMPARISON_NAMES.put("!=", "NEQ");
    }

    /** The program being built: one BytecodeStatement per instruction. Turned into text only by the caller of emit(), once this is complete. */
    public final BytecodeProgram out = new BytecodeProgram();
    static final String ORIGIN = "ASTGenerator";
    public int labelCounter = 0;
    /** Distinct string literal -> hoisted id ("string_id1", ...), first-occurrence order; char literals are excluded. Filled by literalValueOf, emitted as STRING lines at the very front of the output. */
    public final Map<String, String> hoistedStrings = new LinkedHashMap<>();

    /** Set at the start of emit(); used by emitInstantiate to read a struct's declared member order. */
    public TypeChecker checker;
    /** Stack of enclosing loops' end labels, innermost last; 'break' jumps to the top of this. */
    public final List<String> loopEndLabels = new ArrayList<>();
    /**
     * One entry per enclosing loop, parallel to `loopEndLabels`: where a loop-`continue` jumps. For a `loop` it is
     * its start label; for a `for` it is a label right before the step, created on the first `continue` that
     * needs it (so a program without one gets exactly the same labels and numbering as before).
     */
    public final List<String[]> loopContinueTargets = new ArrayList<>();
    /** Start labels of the enclosing 'match @lock' spin loops, innermost last: the target of a lock-retry 'continue'. */
    public final List<String> lockRetryLabels = new ArrayList<>();

    /** Marker string the stdlib `funcname` returns; replaced at the end of emission by the generated id -> name chain. */
    public static final String FUNCNAME_MARKER = "@@funcname@@";

    /** Source-level name for a trace: the emitted name without its signature suffix, plus where it is defined. */
    public static String traceDisplayName(FuncInfo info, String emittedName) {
        int sig = emittedName.indexOf("__sig_");
        String name = sig >= 0 ? emittedName.substring(0, sig) : emittedName;
        String file = info.funcToken != null && info.funcToken.file != null ? info.funcToken.file : "?";
        file = file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
        return name + " (" + file + ":" + (info.funcToken != null ? info.funcToken.line : 0) + ")";
    }

    // ---- struct layout / alignment ---- see docs/BytecodeEmitter.md, "Struct layout and padding".
    // Keeps its own size/alignment table (not TypeChecker.PRIMITIVE_SIZE, which serves 'sizeof' under different rules).

    public static final Map<String, Long> LAYOUT_PRIMITIVE_SIZE = new HashMap<>();
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
    public static final long LAYOUT_POINTER_SIZE = 8L;
    public static final Set<String> LAYOUT_STORAGE_KEYWORDS =
            new HashSet<>(Arrays.asList("owns", "ref", "raw", "auto", "static"));





    public final Map<String, StructLayout> structLayoutCache = new HashMap<>();

    /** Type of the trace pointer: `auto` to the `DEPTH + 1` ids of a trace (a pointer into a frame, as the root's buffer is). */
    public static String traceArrayPointerType() {
        return "auto_mut_u64[" + (TraceConfig.depth + 1) + "]";
    }

    /** A frame whose caller is not a traced Caspien frame: the real `main` (C entry point or event loop), a thread trampoline, an exported function. */
    public static boolean isTraceRoot(FuncInfo info, String emittedName) {
        return emittedName.equals("main") || emittedName.startsWith("__trampoline_") || info.isExported;
    }

    /** Catch parameter names of the function being emitted: `throw e` of one of them re-throws and keeps the trace captured at the original throw. */
    public Set<String> functionCatchParams = new HashSet<>();

    /** Next function id for stack traces: ids start at 1, id 0 ends a trace. Assigned in emission order, one per emitted function (a generic instantiation is its own function). */
    public int nextTraceId = 1;
    /** id - 1 -> the emitted function name, for the name table (`funcname`). */
    public final List<String> traceFuncNames = new ArrayList<>();

    /** `/*! termination: .. *&#47;` labels found on emitted functions (HOB function name) and loops (HOB function name + "|" + loop label); checked by GasReport.checkLabels. */
    public final Map<String, DocComment> funcTermLabels = new java.util.LinkedHashMap<>();
    public final Map<String, DocComment> loopTermLabels = new java.util.LinkedHashMap<>();
    public String curHobFn;
    /** "function|loop label" -> most retries of a `match @lock` spin (> 0 literal, -1 run-time value); GasReport uses it as the loop's bound. */
    public final Map<String, Long> spinBounds = new java.util.LinkedHashMap<>();
    /** Every emitted function and loop as {hobFunction, loopLabel or null, file, line, kind}: what `--fix termination` labels. */
    public final List<String[]> termSites = new ArrayList<>();
    /** Where every emitted function and loop was written ("function" and "function|label" -> "file:line"), for the causes `--audit` prints. */
    public final Map<String, String> sourcePos = new java.util.LinkedHashMap<>();

    public static String shownPos(Token t) {
        java.nio.file.Path abs = java.nio.file.Paths.get(t.file).toAbsolutePath().normalize();
        java.nio.file.Path base = caspien.config.ProjectRoot.root();
        return (abs.startsWith(base) ? base.relativize(abs).toString() : abs.toString()) + ":" + t.line;
    }

    /** Proof key -> hidden local holding the address a `match Some(<nullable ref>)` resolved (valid for the function being emitted). */
    public Map<String, String> refAddrSlots = new HashMap<>();

    /** "[storage_]_[mutability]_[basetype]" -- storage, when present, is always the string's own leading segment, immediately followed by '_' (TypeChecker.TypeInfo.canonical()'s own documented ordering). */
    public static boolean hasStorage(String canonicalType) {
        // null-safe: an enum name used only as a '.'-chain root has no resolvedType
        return canonicalType != null && (canonicalType.startsWith("raw_") || canonicalType.startsWith("owns_")
                || canonicalType.startsWith("ref_") || canonicalType.startsWith("auto_")
                || canonicalType.startsWith("static_"));
    }

    /**
     * True if the canonical type text contains "atomic_" (exact: canonical() writes it as its own segment). ATOMIC_PUSH/ATOMIC_ASSIGN
     * carry the volatile-like guarantee (no reordering, hoisting or CSE). See docs/BytecodeEmitter.md, "Atomics".
     */
    public static boolean isAtomicCanonical(String canonical) {
        return canonical != null && canonical.contains("atomic_");
    }

    /**
     * The push of a raw-pointer arithmetic scale: `PUSH <bytes> <type>`, or, when the pointee is a struct whose
     * size is only known once the layout is final, the symbolic `SIZEOF <struct> <type>` (resolved by the
     * Optimizer's SizeofResolutionPass into that same PUSH).
     */
    public static String pointerScaleLine(Token op, String type) {
        return op.chk.pointerScaleStruct != null
                ? "SIZEOF " + op.chk.pointerScaleStruct + " " + type
                : "PUSH " + op.chk.pointerScale + " " + type;
    }

    /** Builtin calls: they bypass the overload table (no resolvedCallTarget), so they are dispatched here by reserved name before emitCall; see docs/BytecodeEmitter.md, "Builtin call emission". */
    /** Whole-program counter naming the hidden async-handle local of each par/await site. */
    public int asyncHandleTempCounter;

    /** A genuine by-value range type (no storage prefix). */
    public static final java.util.regex.Pattern BY_VALUE_RANGE_ARG =
            java.util.regex.Pattern.compile("^(?:imut|mut|indeterminate)_range(?:\\(.*\\))?$");

    /** True for an f32/f64 canonical type (the base type is the last segment). */
    public static boolean isFloatCanonical(String canonical) {
        return canonical != null && (canonical.equals("f32") || canonical.endsWith("_f32")
                || canonical.equals("f64") || canonical.endsWith("_f64"));
    }

    /** True only for a 4-byte f32 (the only float a C variadic call must widen to double; an f64 already is one). */
    public static boolean isF32Canonical(String canonical) {
        return canonical != null && (canonical.equals("f32") || canonical.endsWith("_f32"));
    }



    public static String baseOfCanonical(String canonical) {
        return canonical.substring(canonical.lastIndexOf('_') + 1);
    }

    /**
     * Moved-in `owns` sources whose null-out `emitNew` is holding back until its allocation succeeds (null outside `new`). If the
     * allocation fails, the field values are gone with the discarded stack image, so the failure branch destructs these sources
     * (which still hold their pointers) instead of leaking them.
     */
    public List<Token> deferredMoveNullOuts = null;

    /** Hidden locals holding moved-in temporaries (call results) for the `new`/`dyn([..])` being emitted; destructed if it fails. Null outside those. */
    public List<String> deferredTempNames = null;

    /** Same, for the elements of a `dyn([..])` literal. */
    public List<String> dynLiteralTempNames = null;

    public int lateAllocPos = -1;
    public List<BytecodeStatement> lateAllocs = null;
    public int hiddenLocalCounter = 0;

    /** Identical string literals anywhere in the program share one id. */
    public String hoistedStringId(String rawValue) {
        String id = hoistedStrings.get(rawValue);
        if (id == null) {
            id = "string_id" + (hoistedStrings.size() + 1);
            hoistedStrings.put(rawValue, id);
        }
        return id;
    }

    /** Expands root-level unsafe/safe wrappers into their contents (they leave no bytecode); mirrors TypeChecker's flattenRootLines without the validation. */
    public List<Token> flattenRootLinesForEmit(List<Token> lines) {
        List<Token> out = new ArrayList<>();
        Deque<Token> work = new ArrayDeque<>();
        for (int i = lines.size() - 1; i >= 0; i--) {
            work.push(lines.get(i));
        }
        while (!work.isEmpty()) {
            Token lineTok = work.pop();
            Token t = lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && (t.text.equals("unsafe") || t.text.equals("safe"))) {
                for (int i = t.childs.size() - 1; i >= 0; i--) {
                    work.push(t.childs.get(i));
                }
            } else {
                out.add(lineTok);
            }
        }
        return out;
    }

    public BytecodeProgram emit(List<Token> rootLines, TypeChecker checker) {
        this.checker = checker;
        Map<String, StructInfo> structs = checker.declarations.getStructs();
        Map<String, EnumInfo> enums = checker.declarations.getEnums();
        computeGtReachableFunctions();
        Set<String> lockExempt = new HashSet<>(gtReachableMangledNames);
        lockExempt.addAll(gtDecoratedFuncMangledNames);
        LockNestingCheck.run(checker, lockExempt);
        checker.model.checkProofKills();

        // Globals first, wherever they were declared (every GLOBAL line precedes any FUNC_START); same folding as local statics.
        for (String name : checker.declarations.getGlobalOrder()) {
            hub.statements.emitStaticAlloc("GLOBAL", name, checker.declarations.getGlobals().get(name).canonical(),
                    checker.declarations.getGlobalRhs().get(name));
        }

        for (Token lineTok : flattenRootLinesForEmit(rootLines)) {
            Token t = lineTok.childs.get(0);
            if (t.type == TokenType.OPERATOR && t.text.equals("=")) {
                continue; // a global -- already emitted above
            }
            if (t.type == TokenType.VARREF) {
                // "asm_name;" at the root: already resolved and validated by TypeChecker.
                emitAsm(t.chk.resolvedAsmContent);
                continue;
            }
            switch (t.text) {
                case "struct":
                    emitStruct(structs.get(t.declName().text));
                    break;
                case "type":
                    break; // no bytecode: a type alias is erased, TypeChecker resolved every use
                case "const":
                    break; // no bytecode: a const is erased, TypeChecker substituted every use
                case "enum":
                    emitEnum(enums.get(t.declName().text));
                    break;
                case "func":
                    emitFunc(findFuncInfo(checker, t));
                    break;
                case "interface":
                    break; // no bytecode
                case "impl":
                    for (Token methodTok : t.childs) {
                        // Generic constructors live in the shared `functions` overload map, not in the impl's method tables.
                        if (methodTok.isConstructorDecl) {
                            emitFunc(findFuncInfo(checker, methodTok));
                        } else {
                            emitFunc(findImplMethodInfo(checker, t, methodTok));
                        }
                    }
                    break;
                case "library":
                    // A library has no instances: its functions emit like plain top-level funcs. An inherited function is emitted only under its declaring library.
                    for (Token funcTok : t.childs) {
                        emitFunc(findLibraryFuncInfo(checker, t, funcTok));
                    }
                    break;
                case "extern":
                    emitExtern(checker.declarations.getExterns().get(t.declName().text));
                    break;
                case "export":
                    break; // folded into the target func's FUNC_START block as an EXPORT line (see emitFunc)
                case "impl_default_lock_match":
                    break; // template only: a cloned copy is emitted at each use site inside the "match @lock" spin loop
                case "ASM":
                    // Named: emitted at each "asm_name;" invocation (see the VARREF branch). Unnamed: emitted only here.
                    if (t.sub.isEmpty()) {
                        emitAsm(t.chk.resolvedAsmContent);
                    }
                    break;
                default:
                    throw new IllegalStateException("unexpected top-level token: " + t.text);
            }
        }
        fillFuncNameTable();
        out.addAll(0, hoistedStringStatements());
        return out;
    }

    /**
     * Stack traces (stack_trace.md, 3.7): `stdlib/funcname.caspien` is `return "@@funcname@@"`. Once every function has its id, the push of that
     * marker is replaced by a chain `if id == k { return "name k" }` ending in `"?"`. Without a call to `funcname` the table stays out.
     */
    public void fillFuncNameTable() {
        String markerId = hoistedStrings.get(FUNCNAME_MARKER);
        if (markerId == null) {
            return;
        }
        String markerPush = "PUSH " + markerId + " static_some_imut_string";
        int at = indexOfStatement(markerPush);
        if (at < 0) {
            return;
        }
        boolean referenced = indexOfStatement("CALL funcname") >= 0;
        StringBuilder chain = new StringBuilder();
        if (referenced) {
            for (int i = 0; i < traceFuncNames.size(); i++) {
                String label = "@funcname_next_" + (i + 1);
                chain.append("PUSH id imut_u64\nPUSH ").append(i + 1).append(" indeterminate_u64\n")
                        .append("EQ imut_u64 indeterminate_u64 indeterminate_bool\nCMP\nJMP ").append(label).append('\n')
                        .append("PUSH ").append(hoistedStringId(traceFuncNames.get(i)))
                        .append(" static_some_imut_string\nRET static_imut_string\n").append(label).append(":\n");
            }
        }
        String unknownId = hoistedStringId("?");
        chain.append("PUSH ").append(unknownId).append(" static_some_imut_string\n");
        out.remove(at);
        out.addAll(at, statementsOf(chain.toString()));
    }

    /** Index of the first statement equal to `text` (tokens joined by one space), or -1. */
    private int indexOfStatement(String text) {
        for (int i = 0; i < out.size(); i++) {
            BytecodeStatement st = out.get(i);
            if (st.size() == 0) {
                continue;
            }
            StringBuilder sb = new StringBuilder(st.get(0).text);
            for (int k = 1; k < st.size(); k++) {
                sb.append(' ').append(st.get(k).text);
            }
            if (sb.toString().equals(text)) {
                return i;
            }
        }
        return -1;
    }

    /** Text with one instruction per line -> statements. */
    static List<BytecodeStatement> statementsOf(String lines) {
        List<BytecodeStatement> result = new ArrayList<>();
        for (String l : lines.split("\n", -1)) {
            result.add(BytecodeStatement.fromText(l, ORIGIN));
        }
        result.remove(result.size() - 1); // text ends with '\n': the last piece is empty
        return result;
    }

    /** One STRING line per hoisted string literal, prepended to the output (ahead of the GLOBAL lines). */
    public List<BytecodeStatement> hoistedStringStatements() {
        List<BytecodeStatement> prefix = new ArrayList<>();
        for (Map.Entry<String, String> entry : hoistedStrings.entrySet()) {
            prefix.add(BytecodeStatement.fromText("STRING " + entry.getValue() + " \""
                    + hub.escapeForBytecode(entry.getKey()) + "\"", ORIGIN));
        }
        return prefix;
    }

    /**
     * Builds gtDecoratedFuncMangledNames and gtReachableMangledNames (BFS over Token.resolvedCallTarget) and bans call() (INVOKE) anywhere in the
     * reachable set. See docs/BytecodeEmitter.md, "Ghost-table suppression".
     */
    public void computeGtReachableFunctions() {
        Deque<String> queue = new ArrayDeque<>();
        for (String decoratorName : Arrays.asList("gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve")) {
            FuncInfo info = checker.declarations.getGhostTableFunction(decoratorName);
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
            FuncInfo info = findFuncInfoByMangledName(name);
            if (info != null) {
                collectCallTargetsAndBanInvoke(info.funcToken, queue);
            }
            // info == null: an extern, which has no body to duplicate and never consults gtReachableMangledNames.
        }
    }

    /** The FuncInfo (plain function, impl method or library function) with this mangled name; Token.resolvedCallTarget only carries the name. */
    public FuncInfo findFuncInfoByMangledName(String mangledName) {
        for (List<FuncInfo> overloads : checker.declarations.getFunctions().values()) {
            for (FuncInfo info : overloads) {
                if (info.mangledName.equals(mangledName)) {
                    return info;
                }
            }
        }
        for (List<ImplInfo> impls : checker.declarations.getImpls().values()) {
            for (ImplInfo impl : impls) {
                for (FuncInfo info : impl.methods.values()) {
                    if (info.mangledName.equals(mangledName)) {
                        return info;
                    }
                }
                for (FuncInfo info : impl.staticMethods.values()) {
                    if (info.mangledName.equals(mangledName)) {
                        return info;
                    }
                }
            }
        }
        for (LibraryInfo lib : checker.declarations.getLibraries().values()) {
            for (List<FuncInfo> overloads : lib.ownFunctions.values()) {
                for (FuncInfo info : overloads) {
                    if (info.mangledName.equals(mangledName)) {
                        return info;
                    }
                }
            }
        }
        return null;
    }

    /** Generic walk (left/right/childs/sub -- every field a Token tree can branch through) over one function body, collecting every Token.resolvedCallTarget it finds (a CALL/RECURSIVE_CALL/method-call/extern-call target -- resolvedCallTarget is never set on anything else) into `queue`, and throwing immediately on any "call()" (INVOKE) use found anywhere within it. */
    public void collectCallTargetsAndBanInvoke(Token root, Deque<String> queue) {
        // Pre-order on an explicit stack: left, right, next, childs, sub (children pushed in reverse).
        Deque<Token> work = new ArrayDeque<>();
        if (root != null) {
            work.push(root);
        }
        while (!work.isEmpty()) {
            Token node = work.pop();
            if (node.chk.resolvedCallTarget != null) {
                queue.add(node.chk.resolvedCallTarget);
            }
            if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left != null
                    && node.left.type == TokenType.VARREF && node.left.text.equals("call")) {
                throw new CompilerException("type", node.file, node.line,
                        "'call' (a function-pointer invocation) cannot be used anywhere reachable from a "
                                + "ghost-table-decorated function -- the target isn't known at compile time, so "
                                + "there's no way to guarantee it only reaches dundered duplicates too");
            }
            if (node.sub != null) {
                for (int i = node.sub.size() - 1; i >= 0; i--) {
                    pushIfNotNull(work, node.sub.get(i));
                }
            }
            if (node.childs != null) {
                for (int i = node.childs.size() - 1; i >= 0; i--) {
                    pushIfNotNull(work, node.childs.get(i));
                }
            }
            pushIfNotNull(work, node.next);
            pushIfNotNull(work, node.right);
            pushIfNotNull(work, node.left);
        }
    }

    private static void pushIfNotNull(Deque<Token> work, Token t) {
        if (t != null) {
            work.push(t);
        }
    }

    /** ASM_START / raw content / ASM_END, shared by every asm emission site. */
    public void emitAsm(String rawContent) {
        line("ASM_START");
        if (!rawContent.isEmpty()) {
            for (String l : (rawContent.endsWith("\n") ? rawContent : rawContent + "\n").split("\n", -1)) {
                out.add(BytecodeStatement.fromText(l, ORIGIN));
            }
            out.remove(out.size() - 1); // the piece after the final '\n'
        } else {
            out.add(BytecodeStatement.fromText("", ORIGIN));
        }
        line("ASM_END");
    }

    public FuncInfo findImplMethodInfo(TypeChecker checker, Token implTok, Token methodTok) {
        String concreteName = implTok.sub.get(implTok.sub.size() - 1).text;
        for (ImplInfo implInfo : checker.declarations.getImpls().get(concreteName)) {
            for (FuncInfo info : implInfo.methods.values()) {
                if (info.funcToken == methodTok) {
                    return info;
                }
            }
            for (FuncInfo info : implInfo.staticMethods.values()) {
                if (info.funcToken == methodTok) {
                    return info;
                }
            }
            // Call-site-instantiated residual-generic methods live only in genericMethodInstances.
            for (FuncInfo info : implInfo.genericMethodInstances.values()) {
                if (info.funcToken == methodTok) {
                    return info;
                }
            }
        }
        throw new IllegalStateException("no FuncInfo for impl method '" + methodTok.declName().text + "'");
    }

    public FuncInfo findLibraryFuncInfo(TypeChecker checker, Token libraryTok, Token funcTok) {
        String name = libraryTok.declName().text;
        for (List<FuncInfo> overloads : checker.declarations.getLibraries().get(name).ownFunctions.values()) {
            for (FuncInfo info : overloads) {
                if (info.funcToken == funcTok) {
                    return info;
                }
            }
        }
        throw new IllegalStateException("no FuncInfo for library function '" + funcTok.declName().text + "'");
    }

    public FuncInfo findFuncInfo(TypeChecker checker, Token funcTok) {
        String name = funcTok.declName().text;
        for (FuncInfo info : checker.declarations.getFunctions().get(name)) {
            if (info.funcToken == funcTok) {
                return info;
            }
        }
        throw new IllegalStateException("no FuncInfo for '" + name + "'");
    }

    /**
     * A name may be redeclared in a sibling scope with another type (the slot is shared, sized for the widest use; tests/same_name_locals_test), EXCEPT
     * when a type needs generated drop glue: the glue pass resolves `GT_DESTRUCT name` through one name -> type map, so two owning types under one
     * name dropped through the wrong layout (a catch parameter `e` plus an owning local `e` leaked). Rename one of them.
     */
    public void requireOneOwnsTypePerSlotName(FuncInfo info, BytecodeProgram allocLines) {
        Map<String, String> seen = new HashMap<>();
        for (BytecodeStatement st : allocLines) {
            if (!st.opcode().equals("ALLOC") || st.size() < 3) {
                continue;
            }
            String[] parts = {"ALLOC", st.text(1), st.text(2)};
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
    public boolean dropNeedsGlue(String type) {
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
            return checker.declarations.elementTextOwnsMemory(t.substring("dynarray(".length(), t.length() - 1));
        }
        if (t.startsWith("unsafe_dynarray(")) {
            return false;
        }
        return checker.declarations.elementTextOwnsMemory(t) && !t.startsWith("dynarray(");
    }

    public void line(String s) {
        out.add(BytecodeStatement.fromText(s, ORIGIN));
    }

    // ---- top-level declarations ------------------------------------------

    public void emitStruct(StructInfo info) {
        line("STRUCT_START " + info.name);
        emitDecorators("STRUCT_DECORATE", info.declTok.decorators);
        // The hidden ___type class id comes first (absent for @untyped): computeStructLayout assigns offsets in this order, so it must precede the
        // user members. See docs/BytecodeEmitter.md, "Struct layout and padding".
        for (StructLayoutEntry entry : computeStructLayout(info).entries) {
            line(entry.paddingBytes > 0
                    ? "STRUCT_PADDING " + entry.paddingBytes
                    : "STRUCT_MEMBER " + entry.memberName + " " + entry.canonicalType);
        }
        line("STRUCT_END");
    }

    /** `info`'s own real STRUCT_MEMBER/STRUCT_PADDING sequence plus its own total (size, alignment) in bytes -- memoized per struct name. Nested structs are laid out first on an explicit stack (struct types form a DAG). */
    public StructLayout computeStructLayout(StructInfo info) {
        StructLayout cached = structLayoutCache.get(info.name);
        if (cached != null) {
            return cached;
        }
        Deque<StructInfo> pendingStructs = new ArrayDeque<>();
        pendingStructs.push(info);
        while (!pendingStructs.isEmpty()) {
            StructInfo cur = pendingStructs.peek();
            if (structLayoutCache.containsKey(cur.name)) {
                pendingStructs.pop();
                continue;
            }
            StructInfo missing = layoutStructOrMissing(cur);
            if (missing == null) {
                pendingStructs.pop();
                continue;
            }
            if (pendingStructs.contains(missing)) {
                throw new IllegalStateException("struct '" + missing.name + "' contains itself");
            }
            pendingStructs.push(missing);
        }
        return structLayoutCache.get(info.name);
    }

    /** Lays `info` out and caches it, or returns the first nested struct whose layout is not cached yet (nothing is cached then). */
    private StructInfo layoutStructOrMissing(StructInfo info) {
        List<StructLayoutEntry> entries = new ArrayList<>();
        long offset = 0;
        long maxAlign = 1;
        if (info.classId != null) {
            entries.add(StructLayoutEntry.member("___type", "imut_u64"));
            offset += 8;
            maxAlign = 8;
        }
        for (Map.Entry<String, TypeInfo> e : info.members.entrySet()) {
            String canonical = e.getValue().canonical();
            long[] sizeAlign = layoutSizeAndAlignOrMissing(canonical);
            if (sizeAlign == null) {
                return layoutMissingStruct;
            }
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
        structLayoutCache.put(info.name, new StructLayout(entries, offset, maxAlign));
        return null;
    }

    /** The nested struct layoutSizeAndAlignOrMissing could not size because its layout is not cached. */
    private StructInfo layoutMissingStruct;

    /** [size, align] in bytes for a member's canonical type text: any storage-bearing type is one 8-byte pointer word, otherwise strips to the base type (layoutSizeAndAlignOfBaseType). */
    public long[] layoutSizeAndAlignOf(String canonical) {
        while (true) {
            long[] r = layoutSizeAndAlignOrMissing(canonical);
            if (r != null) {
                return r;
            }
            computeStructLayout(layoutMissingStruct);
        }
    }

    /** layoutSizeAndAlignOf without computing nested layouts: null (and layoutMissingStruct set) when a nested struct is not cached yet. */
    private long[] layoutSizeAndAlignOrMissing(String canonical) {
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

    /** [size, align] for a bare base type: range 16/8; fixed array = element align, count * element size (dimensions peeled outermost first, applied innermost first); dynarray = one 8-byte handle; nested struct = its cached layout; enum or unknown = one 8-byte word. Null when a nested struct is not cached. */
    private long[] layoutSizeAndAlignOfBaseType(String baseType) {
        List<Long> lengths = new ArrayList<>();
        String t = baseType;
        long[] base;
        while (true) {
            if (t.equals("range") || t.startsWith("range(")) {
                base = new long[]{16, 8};
                break;
            }
            if (!t.isEmpty() && t.charAt(t.length() - 1) == ']') {
                int open = t.lastIndexOf('[');
                if (open >= 0) {
                    String digits = t.substring(open + 1, t.length() - 1);
                    boolean allDigits = !digits.isEmpty();
                    for (int i = 0; allDigits && i < digits.length(); i++) {
                        allDigits = Character.isDigit(digits.charAt(i));
                    }
                    lengths.add(allDigits ? Long.parseLong(digits) : -1L);
                    t = t.substring(0, open);
                    continue;
                }
            }
            if (t.startsWith("dynarray(") || t.startsWith("unsafe_dynarray(")) {
                base = new long[]{LAYOUT_POINTER_SIZE, LAYOUT_POINTER_SIZE};
                break;
            }
            StructInfo nested = checker.declarations.getStructs().get(t);
            if (nested != null) {
                StructLayout nestedLayout = structLayoutCache.get(nested.name);
                if (nestedLayout == null) {
                    layoutMissingStruct = nested;
                    return null;
                }
                base = new long[]{nestedLayout.size, nestedLayout.align};
                break;
            }
            Long prim = LAYOUT_PRIMITIVE_SIZE.get(t);
            base = prim != null ? new long[]{prim, prim} : new long[]{LAYOUT_POINTER_SIZE, LAYOUT_POINTER_SIZE};
            break;
        }
        long size = base[0];
        for (int k = lengths.size() - 1; k >= 0; k--) {
            long length = lengths.get(k);
            size = length < 0 ? LAYOUT_POINTER_SIZE : length * size;
        }
        return new long[]{size, base[1]};
    }

    public void emitEnum(EnumInfo info) {
        // Plain enum: "ENUM name V1 V2"; explicit values: "V1 0 V2 1"; range-valued: "V1 a..b" (exactly one kind per enum).
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

    /** Synthesizes the real C-ABI `main(argc, argv)`: builds the safe args value with make_safe_args and calls "__caspien_main" with it. See docs/BytecodeEmitter.md, "Entry points". */
    public void emitSafeArgsMainWrapper(FuncInfo userMain) {
        FuncInfo makeSafeArgs = checker.declarations.getFunctions().get("make_safe_args").get(0);
        TypeInfo argcType = new TypeInfo(null, "imut", "s32");
        TypeInfo argvType = new TypeInfo("raw", "mut",
                new TypeInfo("raw", "mut", "char").canonical());
        TypeInfo argsType = checker.declarations.safeArgsType();

        line("FUNC_START main");
        line("RETURNS " + userMain.returnType.canonical());

        FuncInfo wrapperInfo = new FuncInfo();
        wrapperInfo.ownsLocalTypes.put("args", argsType);
        currentFuncMangledName = "main";
        currentFuncInfo = wrapperInfo;

        // Same order as emitFuncUnderName: RETURNS, ARG lines (argc/argv, signature facts only), then the gt_routine slot and the one local "args".
        // No @call_convention (nothing to write it on): ArgToAllocLoweringPass uses the config default.
        List<String> osArgNames = Arrays.asList("argc", "argv");
        List<String> osArgTypes = Arrays.asList(argcType.canonical(), argvType.canonical());
        emitParamDecls(osArgNames, osArgTypes);
        emitGtRoutineAlloc(wrapperInfo, "main", false);
        line("ALLOC args " + argsType.canonical());
        emitGtRoutineBody(wrapperInfo, "main", false);

        // GT_INIT placement of a true main: after the ALLOCs, before the first expression.
        if (checker.declarations.usesOwnsRefDynNew()) {
            requireGhostTableFunctionPresent("gt_init", userMain.funcToken);
            requireGhostTableFunctionPresent("gt_moved", userMain.funcToken);
            line("GT_INIT");
        }
        List<PendingUnwindLandingPad> previousPendingLandingPads = pendingCallSiteUnwindLandingPads;
        pendingCallSiteUnwindLandingPads = new ArrayList<>();
        line("ADDR args " + argsType.canonical());
        Token argcTok = syntheticVarref("argc", argcType.canonical());
        Token argvTok = syntheticVarref("argv", argvType.canonical());
        // Empty destruct list: "args" does not exist yet.
        stageCallSiteForUnwindNames(Collections.emptyList(), Collections.emptyList(), userMain.funcToken);
        hub.operations.emitCallSequenceTop(userMain.funcToken, Arrays.asList(argcTok, argvTok), "CALL make_safe_args",
                argsType.canonical(), makeSafeArgs.callConvention);
        line("ASSIGN " + argsType.canonical() + " " + argsType.canonical() + " " + argsType.canonical());

        Token argsTok = syntheticVarref("args", argsType.canonical());
        argsTok.chk.isOwnershipMoveSource = true;
        boolean isVoid = userMain.returnType.canonical().equals("imut_void");
        // Empty destruct list: "args" is moved into this call.
        stageCallSiteForUnwindNames(Collections.emptyList(), Collections.emptyList(), userMain.funcToken);
        hub.operations.emitCallSequenceTop(userMain.funcToken, Arrays.asList(argsTok), "CALL __caspien_main",
                isVoid ? null : userMain.returnType.canonical(), userMain.callConvention);
        if (isVoid) {
            line("RET imut_void");
        } else {
            line("RET " + userMain.returnType.canonical());
        }
        // Always emitted under "main" and never gt-suppressed.
        emitCallSiteUnwindLandingPads(wrapperInfo, "main", false);
        line("FUNC_END");
        currentFuncMangledName = null;
        currentFuncInfo = null;
        pendingCallSiteUnwindLandingPads = previousPendingLandingPads;
    }

    /** A minimal VARREF token so synthesized names can go through the ordinary emitExpr / emitAssignTarget machinery; never type-checked. */
    public Token syntheticVarref(String name, String resolvedType) {
        Token t = new Token(TokenType.VARREF, name, 0, "<synthesized>");
        t.chk.resolvedType = resolvedType;
        return t;
    }

    public void emitFunc(FuncInfo info) {
        // An @event_loop function is emitted as "main" and the user's main becomes "__caspien_main". See docs/BytecodeEmitter.md, "Entry points".
        FuncInfo eventLoop = checker.declarations.getEventLoopFunc();
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
            // No @event_loop: synthesize the C-ABI main wrapper (emitSafeArgsMainWrapper).
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
     * Reserved frame slots, emitted ahead of every local so GT_UNWIND finds them at the same rbp offset in every frame: gt_routine_address first, then
     * gt_error_message (only when the program throws), then the trace slots. Skipped when gt-suppressed or when the program uses neither
     * owns/ref/dyn/new nor throw. The label/destruct/ending half is emitGtRoutineBody. See docs/BytecodeEmitter.md, "gt_routine prologue".
     */
    public void emitGtRoutineAlloc(FuncInfo info, String emittedName, boolean gtSuppressed) {
        if (gtSuppressed) {
            return;
        }
        if (!checker.declarations.usesOwnsRefDynNew() && !checker.declarations.usesThrow()) {
            return;
        }
        line("ALLOC gt_routine_address code_addr");
        // gt_error_message: meaningful only with `throw`, hence the narrower gate. Typed static_imut_string like a thrown message. Written by emitThrow,
        // read by emitHoistedCatchBlocks; GT_UNWIND MSG (X86Backend) copies it into the caller's frame. See docs/BytecodeEmitter.md, "gt_routine prologue".
        if (checker.declarations.usesThrow()) {
            line("ALLOC gt_error_message static_imut_string");
        }
        // Stack traces (stack_trace.md, 3.2): two more fixed slots right behind the first two, at rbp-24 (this function's id, written once at
        // entry by `emitTraceIdStore`) and rbp-32 (the trace pointer carried by the unwind). Present in every frame when tracing is on.
        if (TraceConfig.usesTrace) {
            line("ALLOC gt_trace_id mut_u64");
            line("ALLOC gt_trace_ptr " + traceArrayPointerType());
            if (isTraceRoot(info, emittedName)) {
                line("ALLOC gt_trace_buf mut_u64[" + (TraceConfig.depth + 1) + "]");   // the root frame's staging array (3.3)
            }
        }
    }

    /** With tracing on: copies the ids of the frames from here up to the root into the root's staging array (before the unwind starts). */
    public void emitTraceCapture() {
        if (TraceConfig.usesTrace && !inGtSuppressedContext) {
            line("TRACE_CAPTURE " + TraceConfig.depth);
        }
    }

    /**
     * With tracing on, stores `(id << 1) | isRoot` into this frame's `gt_trace_id` slot. A root is a frame whose caller is not a traced Caspien frame:
     * the real `main` (the C entry point, or the event loop), a thread trampoline, an exported function.
     */
    public void emitTraceIdStore(FuncInfo info, String emittedName) {
        if (!TraceConfig.usesTrace) {
            return;
        }
        boolean root = isTraceRoot(info, emittedName);
        int id = nextTraceId++;
        traceFuncNames.add(traceDisplayName(info, emittedName));
        line("ADDR gt_trace_id mut_u64");
        line("PUSH " + (((long) id << 1) | (root ? 1 : 0)) + " indeterminate_u64");
        line("ASSIGN mut_u64 indeterminate_u64 mut_u64");
        if (root) {
            // a root's trace pointer starts at its own staging array; unwinding then carries the same pointer up every frame
            String arr = "mut_u64[" + (TraceConfig.depth + 1) + "]";
            line("ADDR gt_trace_ptr " + traceArrayPointerType());
            line("PUSH gt_trace_buf " + arr);
            line("ADDR_OF AUTO " + arr + " auto_indeterminate_u64[" + (TraceConfig.depth + 1) + "]");
            line("ASSIGN " + traceArrayPointerType() + " " + traceArrayPointerType() + " " + traceArrayPointerType());
        }
    }

    /**
     * "GT_UNWIND" or, in a program that uses throw (every frame then has the fixed `gt_error_message` slot at rbp-16, right behind
     * `gt_routine_address` at rbp-8), "GT_UNWIND MSG": the backend copies this frame's message slot into the caller's before leaving, so
     * a `catch` in any frame up the chain sees the message that was thrown, not its own never-written slot.
     */
    public String gtUnwindLine() {
        return checker.declarations.usesThrow() ? (TraceConfig.usesTrace ? "GT_UNWIND MSG TRACE" : "GT_UNWIND MSG") : "GT_UNWIND";
    }

    /** The rest of the `gt_routine` prologue -- see `emitGtRoutineAlloc`'s doc comment for why this is split out and why it must run only after every ALLOC/ALLOC_STATIC in the function has already been emitted. */
    public void emitGtRoutineBody(FuncInfo info, String emittedName, boolean gtSuppressed) {
        if (gtSuppressed) {
            return;
        }
        // Gate identical to emitGtRoutineAlloc's: the prologue is needed only if the program uses owns/ref/dyn/new (the GT_INIT trigger) or throw.
        if (!checker.declarations.usesOwnsRefDynNew() && !checker.declarations.usesThrow()) {
            return;
        }
        // With `throw` anywhere in the program this whole-function label is superseded by per-call-site landing pads (stageCallSiteForUnwind,
        // emitCallSiteUnwindLandingPads) and nothing below is emitted. Without throw the old whole-function shape is kept. See docs/BytecodeEmitter.md, "gt_routine prologue".
        emitTraceIdStore(info, emittedName);
        if (checker.declarations.usesThrow()) {
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
     * Stages the reserved `gt_routine_address` slot, just before an ordinary CALL/RECURSIVE_CALL, to point at a landing pad for exactly this call
     * site (it destructs what `op.destructOnExit` lists, then continues the unwind). No-op when the program never throws or in gt-suppressed context.
     * See docs/BytecodeEmitter.md, "Call-site unwinding".
     */
    public void stageCallSiteForUnwind(Token op) {
        List<String> destructNames = op.chk.destructOnExit != null ? op.chk.destructOnExit : Collections.emptyList();
        List<Token> unlocks = op.chk.unlockOnExit != null ? op.chk.unlockOnExit : Collections.emptyList();
        stageCallSiteForUnwindNames(destructNames, unlocks, op);
    }

    /** The staging work of stageCallSiteForUnwind, also used by emitSafeArgsMainWrapper's two hand-built calls with an explicit (empty) destruct list. */
    public void stageCallSiteForUnwindNames(List<String> destructNames, List<Token> unlockCalls, Token at) {
        if (!checker.declarations.usesThrow() || inGtSuppressedContext) {
            return;
        }
        String label;
        if (pendingTryCatchLabel != null) {
            // A "try" redirects this staging write straight at its hoisted catch label; no landing pad entry is added.
            label = pendingTryCatchLabel;
            pendingTryCatchLabel = null;
        } else {
            label = hub.statements.newLabel("gt_callsite__" + currentFuncMangledName);
            pendingCallSiteUnwindLandingPads.add(new PendingUnwindLandingPad(label, destructNames, unlockCalls, at));
        }
        line("ADDR gt_routine_address code_addr");
        line("PUSH_LABEL " + label + " code_addr");
        line("ASSIGN code_addr code_addr code_addr");
    }

    /**
     * Emits the call node a "try" directly wraps with the one-shot `pendingTryCatchLabel` set, so its staging write lands in the catch block. The
     * clear afterwards is only a safety net. No resume label is needed: checkTry requires every catch body to terminate. See docs/BytecodeEmitter.md, "Call-site unwinding".
     */
    public EmitFrame tryGuardedCallFrame(Token tryTok) {
        return new EmitFrame(EmitFrame.TRY_GUARDED, tryTok);
    }

    public int stepTryGuardedCall(EmitFrame f) {
        if (f.phase == 0) {
            pendingTryCatchLabel = tryCatchLabels.get(f.node);
            f.phase = 1;
            return f.callExpr(f.node.operand());
        }
        pendingTryCatchLabel = null;
        return EmitFrame.DONE;
    }

    /**
     * Emits the landing pads staged while the body was emitted, after the natural-end destruct list and ahead of FUNC_END, behind a leading JMP
     * (dead unless an unwind lands there). Each pad: its GT_DESTRUCT list, then EXIT (true main), EXIT_THREAD (@async) or GT_UNWIND.
     */
    public void emitCallSiteUnwindLandingPads(FuncInfo info, String emittedName, boolean gtSuppressed) {
        if (gtSuppressed || pendingCallSiteUnwindLandingPads.isEmpty()) {
            return;
        }
        String skipLabel = hub.statements.newLabel("end_of_gt_callsites__" + emittedName);
        line("JMP " + skipLabel);
        for (PendingUnwindLandingPad pad : pendingCallSiteUnwindLandingPads) {
            line(pad.label + ":");
            if (!pad.destructNames.isEmpty()) {
                requireGhostTableFunctionPresent("gt_destruct", pad.at);
            }
            for (String name : pad.destructNames) {
                line("GT_DESTRUCT " + name);
            }
            runUnlockList(pad.unlockCalls, pad.at);
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

    public void emitFuncUnderName(FuncInfo info, String emittedName, boolean gtSuppressed,
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
        // Try-block "after" labels and continue targets are precomputed here (see collectTryBlockLabels).
        hub.statements.collectTryBlockLabels(info.funcToken.childs, new ArrayDeque<>());
        functionCatchParams = new HashSet<>();
        line("FUNC_START " + emittedName);
        curHobFn = emittedName;
        if (info.funcToken.file != null) {
            sourcePos.put(emittedName, shownPos(info.funcToken));
            termSites.add(new String[]{emittedName, null, info.funcToken.file, String.valueOf(info.funcToken.line), "func"});
        }
        {
            DocComment td = DocComments.find(info.funcToken.file, info.funcToken.line, "termination", "func");
            if (td != null) {
                funcTermLabels.put(emittedName, td);
            }
        }
        if (allowExport && info.isExported) {
            // Always the bare name (isExported is only set on plain top-level funcs).
            line("EXPORT");
        }
        emitDecorators("FUNC_DECORATE", info.funcToken.decorators, true);
        if (TraceConfig.usesTrace && !gtSuppressed) {
            line("FUNC_DECORATE @dont(inline)");   // an inlined function leaves no frame, and so no id, on the stack (stack_trace.md, 3.3)
        }
        line("RETURNS " + info.returnType.canonical());
        // "ARG name type": signature facts only. ARG-to-ALLOC lowering is done by the optimizer's ArgToAllocLoweringPass.
        if (isStructByValueReturn(info.returnType)) {
            // RVO: a hidden leading "$ret_dest" parameter ('$' cannot occur in a user identifier) receives the result address. It comes first so it lands in
            // the sret register slot (System V RDI / win64 RCX); ArgToAllocLoweringPass needs no special case for it.
            line("ARG $ret_dest raw_mut_" + info.returnType.baseType);
        }
        emitParamDecls(info.paramNames, canonicalParamTypes(info));
        // Every ALLOC/ALLOC_STATIC of the whole function (any nesting depth) is hoisted here: statics, then gt_routine_address, then ordinary locals.
        // emitBlock and friends emit only the initializing ASSIGNs. Parameters stay ARG lines. See docs/BytecodeEmitter.md, "ALLOC hoisting".
        List<HoistedAlloc> staticAllocs = new ArrayList<>();
        List<HoistedAlloc> ordinaryAllocs = new ArrayList<>();
        hub.statements.collectHoistedAllocs(info.funcToken.childs, staticAllocs, ordinaryAllocs);
        for (HoistedAlloc alloc : staticAllocs) {
            hub.statements.emitHoistedAlloc(alloc);
        }
        emitGtRoutineAlloc(info, emittedName, gtSuppressed);
        final int allocScanStart = out.size();
        for (HoistedAlloc alloc : ordinaryAllocs) {
            hub.statements.emitHoistedAlloc(alloc);
        }
        requireOneOwnsTypePerSlotName(info, out.slice(allocScanStart, out.size()));
        // Locals discovered while emitting the body (see `declareHiddenLocal`) get their ALLOC lines spliced in right here once the
        // body is done: a variable cannot be used before it is declared, so no pre-pass is needed to find them.
        final int savedLateAllocPos = lateAllocPos;
        final List<BytecodeStatement> savedLateAllocs = lateAllocs;
        final Map<String, String> savedRefAddrSlots = refAddrSlots;
        refAddrSlots = new HashMap<>();
        lateAllocPos = out.size();
        lateAllocs = new ArrayList<>();
        emitGtRoutineBody(info, emittedName, gtSuppressed);
        hub.statements.emitHoistedCatchBlocks(info);
        // isEntryPointBody is true for the function emitted as "main" (the post-dundering name), i.e. also for an @event_loop function.
        runBlock(info.funcToken.childs, emittedName.equals("main"));
        runDestructList(info.funcToken.chk.destructOnExit, info.funcToken);
        // Every call site is known now: emit the landing pads after the natural-end destruct list, before FUNC_END.
        emitCallSiteUnwindLandingPads(info, emittedName, gtSuppressed);
        line("FUNC_END");
        if (!lateAllocs.isEmpty()) {
            out.addAll(lateAllocPos, lateAllocs);
        }
        lateAllocPos = savedLateAllocPos;
        lateAllocs = savedLateAllocs;
        refAddrSlots = savedRefAddrSlots;
        currentFuncMangledName = null;
        currentFuncInfo = null;
        inGtSuppressedContext = previousGtContext;
        pendingCallSiteUnwindLandingPads = previousPendingLandingPads;
        tryCatchLabels = previousTryCatchLabels;
        tryBlockLabels = previousTryBlockLabels;
        continueTargetLabels = previousContinueTargetLabels;
    }

    /** True for a plain (no storage modifier) struct type, i.e. TypeChecker.isStructByValueReturn's condition: such a return uses RVO (hidden "$ret_dest", emitReturn, emitStructRvoAssign). */
    public boolean isStructByValueReturn(TypeInfo t) {
        return t.storage == null && checker.declarations.getStructs().containsKey(t.baseType);
    }

    /** `info.paramTypes`, each already reduced to its own `canonical()` text -- the shape `emitParamDecls` needs, computed once per call rather than repeated inline at every use site. */
    public List<String> canonicalParamTypes(FuncInfo info) {
        List<String> out = new ArrayList<>();
        for (TypeInfo t : info.paramTypes) {
            out.add(t.canonical());
        }
        return out;
    }

    /** One "ARG name type" line per parameter; see the ARG comment in emitFuncUnderName. */
    public void emitParamDecls(List<String> paramNames, List<String> paramCanonicalTypes) {
        for (int i = 0; i < paramNames.size(); i++) {
            line("ARG " + paramNames.get(i) + " " + paramCanonicalTypes.get(i));
        }
    }

    /** One line: "EXTERN linkName returnType paramType... [VARARGS]". Signature only; the linker provides the body. */
    public void emitExtern(ExternInfo info) {
        // linkName, not name: it matches the EXTERN_CALL operand, so raw bytecode never shows two names for one extern.
        StringBuilder sb = new StringBuilder("EXTERN ").append(info.linkName)
                .append(' ').append(info.returnType.canonical());
        for (TypeInfo paramType : info.paramTypes) {
            sb.append(' ').append(paramType.canonical());
        }
        if (info.hasVarargs) {
            sb.append(" VARARGS");
        }
        line(sb.toString());
    }

    /** One line per decorator, in declaration order: mnemonic + "@name(args)"; string arguments keep their quotes. */
    public void emitDecorators(String mnemonic, List<Decorator> decorators) {
        emitDecorators(mnemonic, decorators, false);
    }

    /** withPos: append the decorator's source position as a quoted "file:line" operand (the optimizer reports on it). */
    public void emitDecorators(String mnemonic, List<Decorator> decorators, boolean withPos) {
        if (decorators == null) {
            return;
        }
        for (Decorator d : decorators) {
            StringBuilder sb = new StringBuilder("@").append(d.name);
            if (!d.args.isEmpty()) {
                sb.append('(');
                for (int i = 0; i < d.args.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    String a = d.args.get(i);
                    sb.append(d.argIsString.get(i) ? "\"" + hub.escapeForBytecode(a) + "\"" : a);
                }
                sb.append(')');
            }
            boolean wantsPos = !mnemonic.equals("FUNC_DECORATE")
                    || d.name.equals("inline") || (d.name.equals("dont") && d.args.size() == 1 && d.args.get(0).equals("inline"));
            if (withPos && wantsPos && d.file != null && d.line > 0) {
                sb.append(" \"").append(hub.escapeForBytecode(d.file + ":" + d.line)).append('"');
            }
            line(mnemonic + " " + sb);
        }
    }

    // ---- blocks: hoisted ALLOCs, then each statement in order -------------

    public EmitFrame blockFrame(List<Token> lines, boolean isEntryPointBody) {
        EmitFrame f = new EmitFrame(EmitFrame.BLOCK, null);
        f.toks = lines;
        f.b1 = isEntryPointBody;
        return f;
    }

    /** Runs a block outside the emitter machine (function bodies, hoisted catch bodies). */
    public void runBlock(List<Token> lines, boolean isEntryPointBody) {
        hub.driver.run(blockFrame(lines, isEntryPointBody));
    }

    /**
     * isEntryPointBody (b1) is true for the single block of the function emitted as "main": it gets GT_INIT (when owns/ref/dyn/new is used anywhere),
     * after the hoisted ALLOCs and before any expression. A `let` emits only its ASSIGN here; the ALLOC was hoisted. Frame: toks = the lines, i = next one.
     */
    public int stepBlock(EmitFrame f) {
        List<Token> lines = f.toks;
        if (f.phase == 0) {
            if (f.b1 && checker.declarations.usesOwnsRefDynNew()) {
                Token at = currentFuncInfo != null && currentFuncInfo.funcToken != null ? currentFuncInfo.funcToken
                        : (lines.isEmpty() ? null : lines.get(0));
                requireGhostTableFunctionPresent("gt_init", at);
                requireGhostTableFunctionPresent("gt_moved", at);
                line("GT_INIT");
            }
            f.i = 0;
            f.phase = 1;
        }
        while (f.i < lines.size()) {
            Token stmt = lines.get(f.i++).childs.get(0);
            Token letTok = hub.statements.letDeclarationOf(stmt);
            if (letTok != null && letTok.isStatic) {
                continue; // no runtime init to emit -- the value is already baked into ALLOC_STATIC, hoisted to the top of the function
            }
            EmitFrame statement = hub.statements.statementFrame(stmt);
            if (statement != null) {
                return f.call(statement);
            }
        }
        return EmitFrame.DONE;
    }

    /**
     * One GT_DESTRUCT per name of TypeChecker's precomputed list (reverse declaration order, moved and return-preserved names already excluded).
     * Requires a function decorated @gt_destruct only when something is actually emitted. Null when there is nothing to emit.
     */
    public EmitFrame destructListFrame(List<String> names, Token at) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        if (inGtSuppressedContext) {
            // Silent skip: implicit compiler-inserted behavior (unlike a bare 'match' condition the programmer wrote, which errors).
            return null;
        }
        requireGhostTableFunctionPresent("gt_destruct", at);
        EmitFrame f = new EmitFrame(EmitFrame.DESTRUCT_LIST, at);
        f.strs = names;
        return f;
    }

    public void runDestructList(List<String> names, Token at) {
        EmitFrame f = destructListFrame(names, at);
        if (f != null) {
            hub.driver.run(f);
        }
    }

    /** Frame: strs = the names, i = next name. */
    public int stepDestructList(EmitFrame f) {
        while (f.i < f.strs.size()) {
            String name = f.strs.get(f.i++);
            line("GT_DESTRUCT " + name);
            // Null the slot after its destruct: a loop-body owns local shares one hoisted slot across iterations, and a stale freed pointer could be
            // destructed again after the allocator reused the address.
            TypeInfo type = currentFuncInfo != null ? currentFuncInfo.ownsLocalTypes.get(name) : null;
            if (type != null && checker.declarations.isInlineOwningStruct(type)) {
                // an inline owning struct: its members were dropped by the GT_DESTRUCT above; leave them null
                Token local = new Token(TokenType.VARREF, name, 0, "");
                local.chk.resolvedType = type.canonical();
                return f.call(hub.statements.inlineNullOutFrame(local, type.baseType));
            } else if (type != null) {
                line("ADDR " + name + " " + type.canonical());
                line("PUSH null " + type.canonical());
                line("ASSIGN " + type.canonical() + " " + type.canonical() + " " + type.canonical());
            }
        }
        return EmitFrame.DONE;
    }

    /** Lock-release counterpart of the destruct list: TypeChecker's synthesized unlock() calls (reverse acquisition order) as ordinary expressions. Null when there are none. */
    public EmitFrame unlockListFrame(List<Token> unlockCalls, Token at) {
        if (unlockCalls == null || unlockCalls.isEmpty()) {
            return null;
        }
        EmitFrame f = new EmitFrame(EmitFrame.UNLOCK_LIST, at);
        f.toks = unlockCalls;
        return f;
    }

    public void runUnlockList(List<Token> unlockCalls, Token at) {
        EmitFrame f = unlockListFrame(unlockCalls, at);
        if (f != null) {
            hub.driver.run(f);
        }
    }

    /** Frame: toks = the calls, i = next call. */
    public int stepUnlockList(EmitFrame f) {
        if (f.i < f.toks.size()) {
            return f.callExpr(f.toks.get(f.i++));
        }
        return EmitFrame.DONE;
    }

    /** Shared by the destruct list and the match conditions -- see destructListFrame's doc comment for the rule this enforces. */
    public void requireGhostTableFunctionPresent(String decoratorName, Token at) {
        if (checker.declarations.getGhostTableFunction(decoratorName) == null) {
            throw new CompilerException("bytecode", at.file, at.line,
                    "emitting a '@" + decoratorName + "' ghost-table interaction here requires a function "
                            + "decorated '@" + decoratorName + "' to exist somewhere in the compilation unit "
                            + "(none was found)");
        }
    }
}

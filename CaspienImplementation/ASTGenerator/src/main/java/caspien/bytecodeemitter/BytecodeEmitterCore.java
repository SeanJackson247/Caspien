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
import caspien.bytecode.Text;

/**
 * Final front-end stage: walks the type-checked tree and builds the stack-based higher-order bytecode (HOB) as a BytecodeProgram (text only when the caller serializes it).
 * Instruction shapes, if/elseif chaining and the ++ / compound-assignment desugaring: see docs/BytecodeEmitter.md, "Instruction shapes".
 */

/** BytecodeEmitterCore: fields, nested types and static helpers of BytecodeEmitter (part 1 of 4; see README.md in this folder). */
final class BytecodeEmitterCore {
    private final BytecodeEmitter hub;

    BytecodeEmitterCore(BytecodeEmitter hub) { }

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
    static { }
    public static final Map<String, String> COMPOUND_TO_ARITH = new HashMap<>();
    static { }
    public static final Map<String, String> COMPARISON_NAMES = new HashMap<>();
    static { }

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
    public static String traceDisplayName(FuncInfo info, String emittedName) { return null; }

    // ---- struct layout / alignment ---- see docs/BytecodeEmitter.md, "Struct layout and padding".
    // Keeps its own size/alignment table (not TypeChecker.PRIMITIVE_SIZE, which serves 'sizeof' under different rules).

    public static final Map<String, Long> LAYOUT_PRIMITIVE_SIZE = new HashMap<>();
    static { }
    public static final long LAYOUT_POINTER_SIZE = 8L;
    public static final Set<String> LAYOUT_STORAGE_KEYWORDS =
            new HashSet<>(Arrays.asList("owns", "ref", "raw", "auto", "static"));





    public final Map<String, StructLayout> structLayoutCache = new HashMap<>();

    /** Type of the trace pointer: `auto` to the `DEPTH + 1` ids of a trace (a pointer into a frame, as the root's buffer is). */
    public static String traceArrayPointerType() { return null; }

    /** A frame whose caller is not a traced Caspien frame: the real `main` (C entry point or event loop), a thread trampoline, an exported function. */
    public static boolean isTraceRoot(FuncInfo info, String emittedName) { return false; }

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

    public static String shownPos(Token t) { return null; }

    /** Proof key -> hidden local holding the address a `match Some(<nullable ref>)` resolved (valid for the function being emitted). */
    public Map<String, String> refAddrSlots = new HashMap<>();

    /** "[storage_]_[mutability]_[basetype]" -- storage, when present, is always the string's own leading segment, immediately followed by '_' (TypeChecker.TypeInfo.canonical()'s own documented ordering). */
    public static boolean hasStorage(String canonicalType) { return false; }

    /**
     * True if the canonical type text contains "atomic_" (exact: canonical() writes it as its own segment). ATOMIC_PUSH/ATOMIC_ASSIGN
     * carry the volatile-like guarantee (no reordering, hoisting or CSE). See docs/BytecodeEmitter.md, "Atomics".
     */
    public static boolean isAtomicCanonical(String canonical) { return false; }

    /**
     * The push of a raw-pointer arithmetic scale: `PUSH <bytes> <type>`, or, when the pointee is a struct whose
     * size is only known once the layout is final, the symbolic `SIZEOF <struct> <type>` (resolved by the
     * Optimizer's SizeofResolutionPass into that same PUSH).
     */
    public static String pointerScaleLine(Token op, String type) { return null; }

    /** Builtin calls: they bypass the overload table (no resolvedCallTarget), so they are dispatched here by reserved name before emitCall; see docs/BytecodeEmitter.md, "Builtin call emission". */
    /** Whole-program counter naming the hidden async-handle local of each par/await site. */
    public int asyncHandleTempCounter;

    /** True for an f32/f64 canonical type (the base type is the last segment). */
    public static boolean isFloatCanonical(String canonical) { return false; }

    /** True only for a 4-byte f32 (the only float a C variadic call must widen to double; an f64 already is one). */
    public static boolean isF32Canonical(String canonical) { return false; }



    public static String baseOfCanonical(String canonical) { return null; }

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
    public String hoistedStringId(String rawValue) { return null; }

    /** Expands root-level unsafe/safe wrappers into their contents (they leave no bytecode); mirrors TypeChecker's flattenRootLines without the validation. */
    public List<Token> flattenRootLinesForEmit(List<Token> lines) { return null; }

    public BytecodeProgram emit(List<Token> rootLines, TypeChecker checker) { return null; }

    /**
     * Stack traces (stack_trace.md, 3.7): `stdlib/funcname.caspien` is `return "@@funcname@@"`. Once every function has its id, the push of that
     * marker is replaced by a chain `if id == k { return "name k" }` ending in `"?"`. Without a call to `funcname` the table stays out.
     */
    public void fillFuncNameTable() { }

    /** Index of the first statement equal to `text` (tokens joined by one space), or -1. */
    private int indexOfStatement(String text) { return 0; }

    /** Text with one instruction per line -> statements. */
    static List<BytecodeStatement> statementsOf(String lines) { return null; }

    /** One STRING line per hoisted string literal, prepended to the output (ahead of the GLOBAL lines). */
    public List<BytecodeStatement> hoistedStringStatements() { return null; }

    /**
     * Builds gtDecoratedFuncMangledNames and gtReachableMangledNames (BFS over Token.resolvedCallTarget) and bans call() (INVOKE) anywhere in the
     * reachable set. See docs/BytecodeEmitter.md, "Ghost-table suppression".
     */
    public void computeGtReachableFunctions() { }

    /** The FuncInfo (plain function, impl method or library function) with this mangled name; Token.resolvedCallTarget only carries the name. */
    public FuncInfo findFuncInfoByMangledName(String mangledName) { return null; }

    /** Generic walk (left/right/childs/sub -- every field a Token tree can branch through) over one function body, collecting every Token.resolvedCallTarget it finds (a CALL/RECURSIVE_CALL/method-call/extern-call target -- resolvedCallTarget is never set on anything else) into `queue`, and throwing immediately on any "call()" (INVOKE) use found anywhere within it. */
    public void collectCallTargetsAndBanInvoke(Token root, Deque<String> queue) { }

    private static void pushIfNotNull(Deque<Token> work, Token t) { }

    /** ASM_START / raw content / ASM_END, shared by every asm emission site. */
    public void emitAsm(String rawContent) { }

    public FuncInfo findImplMethodInfo(TypeChecker checker, Token implTok, Token methodTok) { return null; }

    public FuncInfo findLibraryFuncInfo(TypeChecker checker, Token libraryTok, Token funcTok) { return null; }

    public FuncInfo findFuncInfo(TypeChecker checker, Token funcTok) { return null; }

    /**
     * A name may be redeclared in a sibling scope with another type (the slot is shared, sized for the widest use; tests/same_name_locals_test), EXCEPT
     * when a type needs generated drop glue: the glue pass resolves `GT_DESTRUCT name` through one name -> type map, so two owning types under one
     * name dropped through the wrong layout (a catch parameter `e` plus an owning local `e` leaked). Rename one of them.
     */
    public void requireOneOwnsTypePerSlotName(FuncInfo info, BytecodeProgram allocLines) { }

    /** True when destructing a local of this canonical type needs generated drop glue: an owned dynarray whose elements own memory, or an (owned or inline) struct with an owning member. */
    public boolean dropNeedsGlue(String type) { return false; }

    public void line(String s) { }

    // ---- top-level declarations ------------------------------------------

    public void emitStruct(StructInfo info) { }

    /** `info`'s own real STRUCT_MEMBER/STRUCT_PADDING sequence plus its own total (size, alignment) in bytes -- memoized per struct name. Nested structs are laid out first on an explicit stack (struct types form a DAG). */
    public StructLayout computeStructLayout(StructInfo info) { return null; }

    /** Lays `info` out and caches it, or returns the first nested struct whose layout is not cached yet (nothing is cached then). */
    private StructInfo layoutStructOrMissing(StructInfo info) { return null; }

    /** The nested struct layoutSizeAndAlignOrMissing could not size because its layout is not cached. */
    private StructInfo layoutMissingStruct;

    /** [size, align] in bytes for a member's canonical type text: any storage-bearing type is one 8-byte pointer word, otherwise strips to the base type (layoutSizeAndAlignOfBaseType). */
    public long[] layoutSizeAndAlignOf(String canonical) { return null; }

    /** layoutSizeAndAlignOf without computing nested layouts: null (and layoutMissingStruct set) when a nested struct is not cached yet. */
    private long[] layoutSizeAndAlignOrMissing(String canonical) { return null; }

    /** [size, align] for a bare base type: range 16/8; fixed array = element align, count * element size (dimensions peeled outermost first, applied innermost first); dynarray = one 8-byte handle; nested struct = its cached layout; enum or unknown = one 8-byte word. Null when a nested struct is not cached. */
    private long[] layoutSizeAndAlignOfBaseType(String baseType) { return null; }

    public void emitEnum(EnumInfo info) { }

    /** Synthesizes the real C-ABI `main(argc, argv)`: builds the safe args value with make_safe_args and calls "__caspien_main" with it. See docs/BytecodeEmitter.md, "Entry points". */
    public void emitSafeArgsMainWrapper(FuncInfo userMain) { }

    /** A minimal VARREF token so synthesized names can go through the ordinary emitExpr / emitAssignTarget machinery; never type-checked. */
    public Token syntheticVarref(String name, String resolvedType) { return null; }

    public void emitFunc(FuncInfo info) { }

    /**
     * Reserved frame slots, emitted ahead of every local so GT_UNWIND finds them at the same rbp offset in every frame: gt_routine_address first, then
     * gt_error_message (only when the program throws), then the trace slots. Skipped when gt-suppressed or when the program uses neither
     * owns/ref/dyn/new nor throw. The label/destruct/ending half is emitGtRoutineBody. See docs/BytecodeEmitter.md, "gt_routine prologue".
     */
    public void emitGtRoutineAlloc(FuncInfo info, String emittedName, boolean gtSuppressed) { }

    /** With tracing on: copies the ids of the frames from here up to the root into the root's staging array (before the unwind starts). */
    public void emitTraceCapture() { }

    /**
     * With tracing on, stores `(id << 1) | isRoot` into this frame's `gt_trace_id` slot. A root is a frame whose caller is not a traced Caspien frame:
     * the real `main` (the C entry point, or the event loop), a thread trampoline, an exported function.
     */
    public void emitTraceIdStore(FuncInfo info, String emittedName) { }

    /**
     * "GT_UNWIND" or, in a program that uses throw (every frame then has the fixed `gt_error_message` slot at rbp-16, right behind
     * `gt_routine_address` at rbp-8), "GT_UNWIND MSG": the backend copies this frame's message slot into the caller's before leaving, so
     * a `catch` in any frame up the chain sees the message that was thrown, not its own never-written slot.
     */
    public String gtUnwindLine() { return null; }

    /** The rest of the `gt_routine` prologue -- see `emitGtRoutineAlloc`'s doc comment for why this is split out and why it must run only after every ALLOC/ALLOC_STATIC in the function has already been emitted. */
    public void emitGtRoutineBody(FuncInfo info, String emittedName, boolean gtSuppressed) { }

    /**
     * Stages the reserved `gt_routine_address` slot, just before an ordinary CALL/RECURSIVE_CALL, to point at a landing pad for exactly this call
     * site (it destructs what `op.destructOnExit` lists, then continues the unwind). No-op when the program never throws or in gt-suppressed context.
     * See docs/BytecodeEmitter.md, "Call-site unwinding".
     */
    public void stageCallSiteForUnwind(Token op) { }

    /** The staging work of stageCallSiteForUnwind, also used by emitSafeArgsMainWrapper's two hand-built calls with an explicit (empty) destruct list. */
    public void stageCallSiteForUnwindNames(List<String> destructNames, List<Token> unlockCalls, Token at) { }

    /**
     * Emits the call node a "try" directly wraps with the one-shot `pendingTryCatchLabel` set, so its staging write lands in the catch block. The
     * clear afterwards is only a safety net. No resume label is needed: checkTry requires every catch body to terminate. See docs/BytecodeEmitter.md, "Call-site unwinding".
     */
    public EmitFrame tryGuardedCallFrame(Token tryTok) { return null; }

    public int stepTryGuardedCall(EmitFrame f) { return 0; }

    /**
     * Emits the landing pads staged while the body was emitted, after the natural-end destruct list and ahead of FUNC_END, behind a leading JMP
     * (dead unless an unwind lands there). Each pad: its GT_DESTRUCT list, then EXIT (true main), EXIT_THREAD (@async) or GT_UNWIND.
     */
    public void emitCallSiteUnwindLandingPads(FuncInfo info, String emittedName, boolean gtSuppressed) { }

    public void emitFuncUnderName(FuncInfo info, String emittedName, boolean gtSuppressed,
            boolean allowExport) { }

    /** True for a plain (no storage modifier) struct type, i.e. TypeChecker.isStructByValueReturn's condition: such a return uses RVO (hidden "$ret_dest", emitReturn, emitStructRvoAssign). */
    public boolean isStructByValueReturn(TypeInfo t) { return false; }

    /** `info.paramTypes`, each already reduced to its own `canonical()` text -- the shape `emitParamDecls` needs, computed once per call rather than repeated inline at every use site. */
    public List<String> canonicalParamTypes(FuncInfo info) { return null; }

    /** One "ARG name type" line per parameter; see the ARG comment in emitFuncUnderName. */
    public void emitParamDecls(List<String> paramNames, List<String> paramCanonicalTypes) { }

    /** One line: "EXTERN linkName returnType paramType... [VARARGS]". Signature only; the linker provides the body. */
    public void emitExtern(ExternInfo info) { }

    /** One line per decorator, in declaration order: mnemonic + "@name(args)"; string arguments keep their quotes. */
    public void emitDecorators(String mnemonic, List<Decorator> decorators) { }

    /** withPos: append the decorator's source position as a quoted "file:line" operand (the optimizer reports on it). */
    public void emitDecorators(String mnemonic, List<Decorator> decorators, boolean withPos) { }

    // ---- blocks: hoisted ALLOCs, then each statement in order -------------

    public EmitFrame blockFrame(List<Token> lines, boolean isEntryPointBody) { return null; }

    /** Runs a block outside the emitter machine (function bodies, hoisted catch bodies). */
    public void runBlock(List<Token> lines, boolean isEntryPointBody) { }

    /**
     * isEntryPointBody (b1) is true for the single block of the function emitted as "main": it gets GT_INIT (when owns/ref/dyn/new is used anywhere),
     * after the hoisted ALLOCs and before any expression. A `let` emits only its ASSIGN here; the ALLOC was hoisted. Frame: toks = the lines, i = next one.
     */
    public int stepBlock(EmitFrame f) { return 0; }

    /**
     * One GT_DESTRUCT per name of TypeChecker's precomputed list (reverse declaration order, moved and return-preserved names already excluded).
     * Requires a function decorated @gt_destruct only when something is actually emitted. Null when there is nothing to emit.
     */
    public EmitFrame destructListFrame(List<String> names, Token at) { return null; }

    public void runDestructList(List<String> names, Token at) { }

    /** Frame: strs = the names, i = next name. */
    public int stepDestructList(EmitFrame f) { return 0; }

    /** Lock-release counterpart of the destruct list: TypeChecker's synthesized unlock() calls (reverse acquisition order) as ordinary expressions. Null when there are none. */
    public EmitFrame unlockListFrame(List<Token> unlockCalls, Token at) { return null; }

    public void runUnlockList(List<Token> unlockCalls, Token at) { }

    /** Frame: toks = the calls, i = next call. */
    public int stepUnlockList(EmitFrame f) { return 0; }

    /** Shared by the destruct list and the match conditions -- see destructListFrame's doc comment for the rule this enforces. */
    public void requireGhostTableFunctionPresent(String decoratorName, Token at) { }
}

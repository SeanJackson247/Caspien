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
 * Final front-end stage: walks the type-checked tree and emits the stack-based higher-order bytecode (HOB) as text.
 * Instruction shapes, if/elseif chaining and the ++ / compound-assignment desugaring: see docs/BytecodeEmitter.md, "Instruction shapes".
 */
public class BytecodeEmitter {

    /** The mangled name of the function currently being emitted -- set/cleared around each emitFunc call, read by emitCall to detect a self-call (needed for RECURSIVE_CALL vs CALL). */
    private String currentFuncMangledName;
    /** Same lifetime as `currentFuncMangledName`, set/cleared alongside it -- needed by `emitDestructList` to look up an `owns` local's own type (`TypeChecker.FuncInfo.ownsLocalTypes`) when nulling a slot out after its own natural-end-of-scope destruct. */
    private TypeChecker.FuncInfo currentFuncInfo;

    /** Landing pads staged for call sites of the current function, drained before its FUNC_END; reset per emitFuncUnderName. See docs/BytecodeEmitter.md, "Call-site unwinding". */
    private List<PendingUnwindLandingPad> pendingCallSiteUnwindLandingPads = new ArrayList<>();

    /** "try" token -> catch label assigned by emitHoistedCatchBlocks before the body is emitted; reset per function. */
    private Map<Token, String> tryCatchLabels = new HashMap<>();
    /** try-block token -> its "after" label, and continue token -> the try-block label it targets; precomputed per function by collectTryBlockLabels. */
    private Map<Token, String> tryBlockLabels = new HashMap<>();
    private Map<Token, String> continueTargetLabels = new HashMap<>();

    /** One-shot: a "try" points the next call-site staging write at its catch label instead of a fresh landing pad. See docs/BytecodeEmitter.md, "Call-site unwinding". */
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

    /** Names of the gt-decorated functions, and of everything they transitively call (each of those is emitted twice, the second as a "__" duplicate). See docs/BytecodeEmitter.md, "Ghost-table suppression". */
    private Set<String> gtDecoratedFuncMangledNames = new HashSet<>();
    private Set<String> gtReachableMangledNames = new HashSet<>();
    /** True while emitting a gt-decorated function or any "__" duplicate: GT_DESTRUCT is skipped, a bare 'match' needing GT_ALIVE_CHECK is an error, calls into gtReachableMangledNames go to the "__" copy. */
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
    /** Distinct string literal -> hoisted id ("string_id1", ...), first-occurrence order; char literals are excluded. Filled by literalValueOf, emitted as STRING lines at the very front of the output. */
    private final Map<String, String> hoistedStrings = new LinkedHashMap<>();

    /** Identical string literals anywhere in the program share one id. */
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

    /** Expands root-level unsafe/safe wrappers into their contents (they leave no bytecode); mirrors TypeChecker's flattenRootLines without the validation. */
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
        checker.checkProofKills();

        // Globals first, wherever they were declared (every GLOBAL line precedes any FUNC_START); same folding as local statics.
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
                // "asm_name;" at the root: already resolved and validated by TypeChecker.
                emitAsm(t.resolvedAsmContent);
                continue;
            }
            switch (t.text) {
                case "struct":
                    emitStruct(structs.get(t.sub.get(0).text));
                    break;
                case "type":
                    break; // no bytecode: a type alias is erased, TypeChecker resolved every use
                case "const":
                    break; // no bytecode: a const is erased, TypeChecker substituted every use
                case "enum":
                    emitEnum(enums.get(t.sub.get(0).text));
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
                    emitExtern(checker.getExterns().get(t.sub.get(0).text));
                    break;
                case "export":
                    break; // folded into the target func's FUNC_START block as an EXPORT line (see emitFunc)
                case "impl_default_lock_match":
                    break; // template only: a cloned copy is emitted at each use site inside the "match @lock" spin loop
                case "ASM":
                    // Named: emitted at each "asm_name;" invocation (see the VARREF branch). Unnamed: emitted only here.
                    if (t.sub.isEmpty()) {
                        emitAsm(t.resolvedAsmContent);
                    }
                    break;
                default:
                    throw new IllegalStateException("unexpected top-level token: " + t.text);
            }
        }
        fillFuncNameTable();
        return emitHoistedStringsPrefix() + out.toString();
    }

    /** Marker string the stdlib `funcname` returns; replaced at the end of emission by the generated id -> name chain. */
    private static final String FUNCNAME_MARKER = "@@funcname@@";

    /**
     * Stack traces (stack_trace.md, 3.7): `stdlib/funcname.caspien` is `return "@@funcname@@"`. Once every function has its id, the push of that
     * marker is replaced by a chain `if id == k { return "name k" }` ending in `"?"`. Without a call to `funcname` the table stays out.
     */
    private void fillFuncNameTable() {
        String markerId = hoistedStrings.get(FUNCNAME_MARKER);
        if (markerId == null) {
            return;
        }
        String markerPush = "PUSH " + markerId + " static_some_imut_string\n";
        int at = out.indexOf(markerPush);
        if (at < 0) {
            return;
        }
        boolean referenced = out.indexOf("CALL funcname\n") >= 0;
        StringBuilder chain = new StringBuilder();
        if (referenced) {
            for (int i = 0; i < traceFuncNames.size(); i++) {
                String label = "@funcname_next_" + (i + 1);
                chain.append("PUSH id imut_u64\nPUSH ").append(i + 1).append(" indeterminate_u64\n")
                        .append("EQ imut_u64 indeterminate_u64 indeterminate_bool\nCMP\nJMP ").append(label).append('\n')
                        .append("PUSH ").append(hoistedStrings.computeIfAbsent(traceFuncNames.get(i), v -> "string_id" + (hoistedStrings.size() + 1)))
                        .append(" static_some_imut_string\nRET static_imut_string\n").append(label).append(":\n");
            }
        }
        String unknownId = hoistedStrings.computeIfAbsent("?", v -> "string_id" + (hoistedStrings.size() + 1));
        chain.append("PUSH ").append(unknownId).append(" static_some_imut_string\n");
        out.replace(at, at + markerPush.length(), chain.toString());
    }

    /** Source-level name for a trace: the emitted name without its signature suffix, plus where it is defined. */
    private static String traceDisplayName(TypeChecker.FuncInfo info, String emittedName) {
        int sig = emittedName.indexOf("__sig_");
        String name = sig >= 0 ? emittedName.substring(0, sig) : emittedName;
        String file = info.funcToken != null && info.funcToken.file != null ? info.funcToken.file : "?";
        file = file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
        return name + " (" + file + ":" + (info.funcToken != null ? info.funcToken.line : 0) + ")";
    }

    /** One STRING line per hoisted string literal, prepended to the output (ahead of the GLOBAL lines). */
    private String emitHoistedStringsPrefix() {
        StringBuilder prefix = new StringBuilder();
        for (Map.Entry<String, String> entry : hoistedStrings.entrySet()) {
            prefix.append("STRING ").append(entry.getValue()).append(" \"")
                    .append(escapeForBytecode(entry.getKey())).append("\"\n");
        }
        return prefix.toString();
    }

    /**
     * Builds gtDecoratedFuncMangledNames and gtReachableMangledNames (BFS over Token.resolvedCallTarget) and bans call() (INVOKE) anywhere in the
     * reachable set. See docs/BytecodeEmitter.md, "Ghost-table suppression".
     */
    private void computeGtReachableFunctions() {
        Deque<String> queue = new ArrayDeque<>();
        for (String decoratorName : Arrays.asList("gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve")) {
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
            // info == null: an extern, which has no body to duplicate and never consults gtReachableMangledNames.
        }
    }

    /** The FuncInfo (plain function, impl method or library function) with this mangled name; Token.resolvedCallTarget only carries the name. */
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

    /** ASM_START / raw content / ASM_END, shared by every asm emission site. */
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
            // Call-site-instantiated residual-generic methods live only in genericMethodInstances.
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
     * A name may be redeclared in a sibling scope with another type (the slot is shared, sized for the widest use; tests/same_name_locals_test), EXCEPT
     * when a type needs generated drop glue: the glue pass resolves `GT_DESTRUCT name` through one name -> type map, so two owning types under one
     * name dropped through the wrong layout (a catch parameter `e` plus an owning local `e` leaked). Rename one of them.
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
        // The hidden ___type class id comes first (absent for @untyped): computeStructLayout assigns offsets in this order, so it must precede the
        // user members. See docs/BytecodeEmitter.md, "Struct layout and padding".
        for (StructLayoutEntry entry : computeStructLayout(info).entries) {
            line(entry.paddingBytes > 0
                    ? "STRUCT_PADDING " + entry.paddingBytes
                    : "STRUCT_MEMBER " + entry.memberName + " " + entry.canonicalType);
        }
        line("STRUCT_END");
    }

    // ---- struct layout / alignment ---- see docs/BytecodeEmitter.md, "Struct layout and padding".
    // Keeps its own size/alignment table (not TypeChecker.PRIMITIVE_SIZE, which serves 'sizeof' under different rules).

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

    /** [size, align] in bytes for a member's canonical type text: any storage-bearing type is one 8-byte pointer word, otherwise strips to the base type (layoutSizeAndAlignOfBaseType). */
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

    /** [size, align] for a bare base type: range 16/8; fixed array = element align, count * element size; dynarray = one 8-byte handle; nested struct recurses; enum or unknown = one 8-byte word. */
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

        // Same order as emitFuncUnderName: RETURNS, ARG lines (argc/argv, signature facts only), then the gt_routine slot and the one local "args".
        // No @call_convention (nothing to write it on): ArgToAllocLoweringPass uses the config default.
        List<String> osArgNames = Arrays.asList("argc", "argv");
        List<String> osArgTypes = Arrays.asList(argcType.canonical(), argvType.canonical());
        emitParamDecls(osArgNames, osArgTypes);
        emitGtRoutineAlloc(wrapperInfo, "main", false);
        line("ALLOC args " + argsType.canonical());
        emitGtRoutineBody(wrapperInfo, "main", false);

        // GT_INIT placement of a true main: after the ALLOCs, before the first expression.
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
        // Empty destruct list: "args" does not exist yet.
        stageCallSiteForUnwindNames(Collections.emptyList(), Collections.emptyList(), userMain.funcToken);
        emitCallSequence(null, Arrays.asList(argcTok, argvTok), () -> line("CALL make_safe_args"),
                argsType.canonical(), makeSafeArgs.callConvention);
        line("ASSIGN " + argsType.canonical() + " " + argsType.canonical() + " " + argsType.canonical());

        Token argsTok = syntheticVarref("args", argsType.canonical());
        argsTok.isOwnershipMoveSource = true;
        boolean isVoid = userMain.returnType.canonical().equals("imut_void");
        // Empty destruct list: "args" is moved into this call.
        stageCallSiteForUnwindNames(Collections.emptyList(), Collections.emptyList(), userMain.funcToken);
        emitCallSequence(null, Arrays.asList(argsTok), () -> line("CALL __caspien_main"),
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
    private Token syntheticVarref(String name, String resolvedType) {
        Token t = new Token(TokenType.VARREF, name, 0, "<synthesized>");
        t.resolvedType = resolvedType;
        return t;
    }

    private void emitFunc(TypeChecker.FuncInfo info) {
        // An @event_loop function is emitted as "main" and the user's main becomes "__caspien_main". See docs/BytecodeEmitter.md, "Entry points".
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
    private void emitGtRoutineAlloc(TypeChecker.FuncInfo info, String emittedName, boolean gtSuppressed) {
        if (gtSuppressed) {
            return;
        }
        if (!checker.usesOwnsRefDynNew() && !checker.usesThrow()) {
            return;
        }
        line("ALLOC gt_routine_address code_addr");
        // gt_error_message: meaningful only with `throw`, hence the narrower gate. Typed static_imut_string like a thrown message. Written by emitThrow,
        // read by emitHoistedCatchBlocks; GT_UNWIND MSG (X86Backend) copies it into the caller's frame. See docs/BytecodeEmitter.md, "gt_routine prologue".
        if (checker.usesThrow()) {
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

    /** Type of the trace pointer: `auto` to the `DEPTH + 1` ids of a trace (a pointer into a frame, as the root's buffer is). */
    private static String traceArrayPointerType() {
        return "auto_mut_u64[" + (TraceConfig.depth + 1) + "]";
    }

    /** A frame whose caller is not a traced Caspien frame: the real `main` (C entry point or event loop), a thread trampoline, an exported function. */
    private static boolean isTraceRoot(TypeChecker.FuncInfo info, String emittedName) {
        return emittedName.equals("main") || emittedName.startsWith("__trampoline_") || info.isExported;
    }

    /** Catch parameter names of the function being emitted: `throw e` of one of them re-throws and keeps the trace captured at the original throw. */
    private Set<String> functionCatchParams = new HashSet<>();

    /** With tracing on: copies the ids of the frames from here up to the root into the root's staging array (before the unwind starts). */
    private void emitTraceCapture() {
        if (TraceConfig.usesTrace && !inGtSuppressedContext) {
            line("TRACE_CAPTURE " + TraceConfig.depth);
        }
    }

    /** Next function id for stack traces: ids start at 1, id 0 ends a trace. Assigned in emission order, one per emitted function (a generic instantiation is its own function). */
    private int nextTraceId = 1;
    /** id - 1 -> the emitted function name, for the name table (`funcname`). */
    private final List<String> traceFuncNames = new ArrayList<>();

    /**
     * With tracing on, stores `(id << 1) | isRoot` into this frame's `gt_trace_id` slot. A root is a frame whose caller is not a traced Caspien frame:
     * the real `main` (the C entry point, or the event loop), a thread trampoline, an exported function.
     */
    private void emitTraceIdStore(TypeChecker.FuncInfo info, String emittedName) {
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
    private String gtUnwindLine() {
        return checker.usesThrow() ? (TraceConfig.usesTrace ? "GT_UNWIND MSG TRACE" : "GT_UNWIND MSG") : "GT_UNWIND";
    }

    /** The rest of the `gt_routine` prologue -- see `emitGtRoutineAlloc`'s doc comment for why this is split out and why it must run only after every ALLOC/ALLOC_STATIC in the function has already been emitted. */
    private void emitGtRoutineBody(TypeChecker.FuncInfo info, String emittedName, boolean gtSuppressed) {
        if (gtSuppressed) {
            return;
        }
        // Gate identical to emitGtRoutineAlloc's: the prologue is needed only if the program uses owns/ref/dyn/new (the GT_INIT trigger) or throw.
        if (!checker.usesOwnsRefDynNew() && !checker.usesThrow()) {
            return;
        }
        // With `throw` anywhere in the program this whole-function label is superseded by per-call-site landing pads (stageCallSiteForUnwind,
        // emitCallSiteUnwindLandingPads) and nothing below is emitted. Without throw the old whole-function shape is kept. See docs/BytecodeEmitter.md, "gt_routine prologue".
        emitTraceIdStore(info, emittedName);
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
     * Stages the reserved `gt_routine_address` slot, just before an ordinary CALL/RECURSIVE_CALL, to point at a landing pad for exactly this call
     * site (it destructs what `op.destructOnExit` lists, then continues the unwind). No-op when the program never throws or in gt-suppressed context.
     * See docs/BytecodeEmitter.md, "Call-site unwinding".
     */
    private void stageCallSiteForUnwind(Token op) {
        List<String> destructNames = op.destructOnExit != null ? op.destructOnExit : Collections.emptyList();
        List<Token> unlocks = op.unlockOnExit != null ? op.unlockOnExit : Collections.emptyList();
        stageCallSiteForUnwindNames(destructNames, unlocks, op);
    }

    /** The staging work of stageCallSiteForUnwind, also used by emitSafeArgsMainWrapper's two hand-built calls with an explicit (empty) destruct list. */
    private void stageCallSiteForUnwindNames(List<String> destructNames, List<Token> unlockCalls, Token at) {
        if (!checker.usesThrow() || inGtSuppressedContext) {
            return;
        }
        String label;
        if (pendingTryCatchLabel != null) {
            // A "try" redirects this staging write straight at its hoisted catch label; no landing pad entry is added.
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
     * Emits the call node a "try" directly wraps with the one-shot `pendingTryCatchLabel` set, so its staging write lands in the catch block. The
     * clear afterwards is only a safety net. No resume label is needed: checkTry requires every catch body to terminate. See docs/BytecodeEmitter.md, "Call-site unwinding".
     */
    private void emitTryGuardedCall(Token tryTok) {
        pendingTryCatchLabel = tryCatchLabels.get(tryTok);
        emitExpr(tryTok.sub.get(0));
        pendingTryCatchLabel = null;
    }

    /**
     * Emits the landing pads staged while the body was emitted, after the natural-end destruct list and ahead of FUNC_END, behind a leading JMP
     * (dead unless an unwind lands there). Each pad: its GT_DESTRUCT list, then EXIT (true main), EXIT_THREAD (@async) or GT_UNWIND.
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
        // Try-block "after" labels and continue targets are precomputed here (see collectTryBlockLabels).
        collectTryBlockLabels(info.funcToken.childs, new ArrayDeque<>());
        functionCatchParams = new HashSet<>();
        line("FUNC_START " + emittedName);
        curHobFn = emittedName;
        if (info.funcToken.file != null) {
            sourcePos.put(emittedName, shownPos(info.funcToken));
            termSites.add(new String[]{emittedName, null, info.funcToken.file, String.valueOf(info.funcToken.line), "func"});
        }
        {
            DocComments.Doc td = DocComments.find(info.funcToken.file, info.funcToken.line, "termination", "func");
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
        final Map<String, String> savedRefAddrSlots = refAddrSlots;
        refAddrSlots = new HashMap<>();
        lateAllocPos = out.length();
        lateAllocs = new ArrayList<>();
        emitGtRoutineBody(info, emittedName, gtSuppressed);
        emitHoistedCatchBlocks(info);
        // isEntryPointBody is true for the function emitted as "main" (the post-dundering name), i.e. also for an @event_loop function.
        emitBlock(info.funcToken.childs, emittedName.equals("main"));
        emitDestructList(info.funcToken.destructOnExit, info.funcToken);
        // Every call site is known now: emit the landing pads after the natural-end destruct list, before FUNC_END.
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

    /** One "ARG name type" line per parameter; see the ARG comment in emitFuncUnderName. */
    private void emitParamDecls(List<String> paramNames, List<String> paramCanonicalTypes) {
        for (int i = 0; i < paramNames.size(); i++) {
            line("ARG " + paramNames.get(i) + " " + paramCanonicalTypes.get(i));
        }
    }

    /** One line: "EXTERN linkName returnType paramType... [VARARGS]". Signature only; the linker provides the body. */
    private void emitExtern(TypeChecker.ExternInfo info) {
        // linkName, not name: it matches the EXTERN_CALL operand, so raw bytecode never shows two names for one extern.
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

    /** One line per decorator, in declaration order: mnemonic + "@name(args)"; string arguments keep their quotes. */
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
     * isEntryPointBody is true for the single block of the function emitted as "main": it gets GT_INIT (when owns/ref/dyn/new is used anywhere),
     * after the hoisted ALLOCs and before any expression. A `let` emits only its ASSIGN here; the ALLOC was hoisted.
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
     * One GT_DESTRUCT per name of TypeChecker's precomputed list (reverse declaration order, moved and return-preserved names already excluded).
     * Requires a function decorated @gt_destruct only when something is actually emitted.
     */
    private void emitDestructList(List<String> names, Token at) {
        if (names == null || names.isEmpty()) {
            return;
        }
        if (inGtSuppressedContext) {
            // Silent skip: implicit compiler-inserted behavior (unlike a bare 'match' condition the programmer wrote, which errors).
            return;
        }
        requireGhostTableFunctionPresent("gt_destruct", at);
        for (String name : names) {
            line("GT_DESTRUCT " + name);
            // Null the slot after its destruct: a loop-body owns local shares one hoisted slot across iterations, and a stale freed pointer could be
            // destructed again after the allocator reused the address.
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

    /** Lock-release counterpart of emitDestructList: emits TypeChecker's synthesized unlock() calls (reverse acquisition order) as ordinary expressions. */
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
     * A compile-time-constant `let static` / global: the folded value is part of the allocation line. Scalar: "MNEMONIC name type value". Struct: a
     * container line "MNEMONIC name imut_Struct" then one "MNEMONIC name.member type value" per member (nested: "name.outer.inner"). Array:
     * container then "name.0", "name.1"... Range: container, "name.start", "name.end". MNEMONIC is ALLOC_STATIC (local) or GLOBAL.
     */
    private void emitStaticAlloc(String mnemonic, String name, String resolvedType, Token rhs) {
        Token unwrapped = unwrapStaticExpr(rhs);
        String structName = staticStructNameOf(unwrapped);
        if (structName != null) {
            line(mnemonic + " " + name + " imut_" + structName);
            TypeChecker.StructInfo structInfo = checker.getStructs().get(structName);
            // Hidden ___type (classId) first, as emitInstantiate pushes it; absent for @untyped. Without it a classId-bearing static was left uninitialised.
            Map<String, Token> valueByMember = new HashMap<>();
            for (Token lineTok : unwrapped.right.childs) {
                Token assignNode = lineTok.childs.get(0);
                valueByMember.put(assignNode.left.text, assignNode.right);
            }
            // Walk the real layout (___type, members, padding): the backend places each "name.member" line at the running sum of the sizes before it,
            // so padding gaps must be lines too ("name.$padN").
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
            // Fixed-array literal: a no-value container line, then "name.0", "name.1", ... per element.
            line(mnemonic + " " + name + " " + resolvedType);
            String elemType = staticArrayElementType(resolvedType, unwrapped);
            for (int i = 0; i < arrayElements.size(); i++) {
                emitStaticAlloc(mnemonic, name + "." + i, elemType, arrayElements.get(i));
            }
            return;
        }
        if (isRangeType(resolvedType)) {
            // Range: a no-value container line, then "name.start" and "name.end".
            long[] bounds = rangeBoundValues(unwrapped);
            line(mnemonic + " " + name + " " + resolvedType);
            line(mnemonic + " " + name + ".start imut_u64 " + bounds[0]);
            line(mnemonic + " " + name + ".end imut_u64 " + bounds[1]);
            return;
        }
        line(mnemonic + " " + name + " " + resolvedType + " " + foldStaticConstant(rhs));
    }

    /** Strips (mut|imut) wrappers, 'as' casts and grouping parens from a static initializer down to the node that has a runtime shape. */
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

    /** The element expressions if `rhs` is an array literal ("[" node), else null. */
    private List<Token> staticArrayElements(Token rhs) {
        if (rhs.type != TokenType.DELINEATOR || !rhs.text.equals("[")) {
            return null;
        }
        List<Token> elements = new ArrayList<>();
        collectCommaArgs(rhs.childs.get(0), elements);
        return elements;
    }

    /** Strips the trailing "[N]" of a static array type: "mut_u64[3]" -> "mut_u64". */
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

    /** True when a static/global's canonical type is a range (after one optional storage and one mutability prefix, "range(" follows). */
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

    /** The two bounds of a range static: a literal "a..b" (each side folded) or a range-valued enum variant, whose bounds are embedded in its type text ("imut_range(0,10)"). */
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
     * Folds a compile-time-constant expression to the bytecode text of one literal: literals pass through (literalValueOf), 'as'/mut/imut/static/atomic
     * wrappers are transparent, integer + - * / % and unary - are evaluated (the restricted shape 'dup' counts use, see DupExpander), a plain enum
     * variant folds to its dotted name, a global may reference an earlier global. Enum-variant defaults beyond that are not handled.
     */
    private String foldStaticConstant(Token node) {
        if (node.sizeofStruct != null && node.left != null && node.left.type == TokenType.VARREF
                && node.left.text.equals("sizeof")) {
            // "let static n = sizeof(Struct)": the initializer is a data line, so the Optimizer never resolves it; fold the laid-out size here and emit
            // STRUCT_PIN so StructMemberReorderingPass cannot change that size (the pass always strips the line).
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
            // The RHS is virtually always wrapped in mut/imut (a bare literal is indeterminate); the wrapper has no runtime representation.
            return foldStaticConstant(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("static")) {
            // 'static' on a string/char literal has no runtime representation either (emitAddressOf emits just the operand).
            return foldStaticConstant(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("atomic")) {
            // 'atomic' is a pure type modifier; without this case the operator text "atomic" itself became the folded initial value of every atomic static/global.
            return foldStaticConstant(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && isQualifiedNameableDot(node)) {
            // Plain enum-variant reference ("let static c = Color.GREEN"; range-valued ones are routed by emitStaticAlloc's isRangeType check): folds to the
            // same dotted name an ordinary PUSH uses (qualifiedDotName).
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
                // Float constant (negative literal or array element): negate the text; parseLong would throw.
                return folded.startsWith("-") ? folded.substring(1) : "-" + folded;
            }
            return Long.toString(-Long.parseLong(folded));
        }
        if (node.type == TokenType.VARREF && checker.getGlobals().containsKey(node.text)) {
            // A global may reference an earlier global: fold through to its value.
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
     * Walks the whole function body (every nested block, any depth) collecting one Runnable per ALLOC/ALLOC_STATIC, in program order, without
     * emitting. emitFuncUnderName runs the static ones, then the gt_routine_address slot, then the ordinary ones; the initializing ASSIGN stays at
     * the declaration's position. See docs/BytecodeEmitter.md, "ALLOC hoisting".
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
                // A 'let' right-hand side can itself be a "try...catch(e){...}": hoist its catch parameter and body locals as well.
                Token tryNodeUnderLet = tryNodeOf(stmt);
                if (tryNodeUnderLet != null) {
                    collectTryHoistedAllocs(tryNodeUnderLet, staticAllocs, ordinaryAllocs);
                }
                continue;
            }
            // "let x = try..." (above), "x = try..." and a bare "try...catch(e){...}" were once never hoisted (the switch below only sees KEYWORD
            // statements); tryNodeOf recognises all three shapes, so checking it here closes the gap.
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
        functionCatchParams.add(tryNode.catchParamName);
        ordinaryAllocs.add(() -> line("ALLOC " + tryNode.catchParamName + " static_imut_string"));
        if (tryNode.catchUsesStackTrace) {
            ordinaryAllocs.add(() -> line("ALLOC " + tryNode.catchParamName + "$trace mut_u64[" + (TraceConfig.depth + 1) + "]"));
        }
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
                // Only the plain "try { ... }" block form arrives here (a throwing try...catch is intercepted by tryNodeOf); it emits nothing of its own.
                collectHoistedAllocs(stmt.childs, staticAllocs, ordinaryAllocs);
                return;
            default:
                return;
        }
    }

    // ---- whole-function catch-block hoisting -------------------------------

    /**
     * Emits each real try/catch's catch body once, at the top of the function behind a JMP over them (a catch body is plain
     * code that must end in return/throw, so no resume label exists). Labels are assigned before any statement is emitted
     * so call sites can redirect unwinds into them. See docs/BytecodeEmitter.md, "Hoisted catch blocks".
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
            // copy of this frame's gt_error_message into the catch parameter (an independent local, never an alias)
            line("ADDR " + tryNode.catchParamName + " static_imut_string");
            line("PUSH gt_error_message static_imut_string");
            line("ASSIGN static_imut_string static_imut_string static_imut_string");
            if (tryNode.catchUsesStackTrace) {
                // handler-local copy of the trace: the staging array belongs to the root and a later throw would overwrite it
                String arr = "mut_u64[" + (TraceConfig.depth + 1) + "]";
                line("ADDR " + tryNode.catchParamName + "$trace " + arr);
                line("PUSH gt_trace_ptr " + traceArrayPointerType());
                line("DEREF " + arr);
                line("ASSIGN " + arr + " " + arr + " " + arr);
            }
            emitBlock(tryNode.childs);
            // no trailing JMP/destruct list: the body ends in return/throw, which emit their own
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
                // a catch body can itself contain a nested try
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
                    // plain "try { }" block form only (the throwing form was taken by tryNodeOf); recurse so nested try/catch is found
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
     * Walks the function body once, giving every plain "try { }" block a label (tryBlockLabels) and resolving every 'continue' to
     * its nearest enclosing try-block label (continueTargetLabels) before any code is emitted: a 'continue' inside a hoisted
     * catch body is emitted before its try block is reached. See docs/BytecodeEmitter.md, "Hoisted catch blocks".
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
                    // plain "try { }" only; label assigned before recursing so a nested catch's 'continue' can resolve it
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
                    // empty stack: already rejected by TypeChecker
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * The throwing "try" token a statement carries (bare statement, "let/x = [mut|imut] try ..", or "return [mut|imut] try ..");
     * null if none. See docs/BytecodeEmitter.md, "Hoisted catch blocks".
     */
    private Token tryNodeOf(Token stmt) {
        // a plain "try { }" block shares the KEYWORD text but is a different construct; excluded
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
                case "unsafe":
                case "safe":
                    // compile-time safety markers only; emit the statements, no wrapper
                    emitBlock(stmt.childs);
                    emitDestructList(stmt.destructOnExit, stmt);
                    return;
                case "assume":
                    // no runtime presence: the block form emits its statements; the no-block form has no children (later siblings belong to the enclosing block)
                    if (stmt.hasBlock) {
                        emitBlock(stmt.childs);
                        emitDestructList(stmt.destructOnExit, stmt);
                    } else if (stmt.assumeFieldVariants == null && stmt.sub != null && !stmt.sub.isEmpty()) {
                        emitAssumeResolves(stmt.sub.get(0));   // a nullable ref proven by `assume match Some(..)`: resolve it once
                    }
                    return;
                case "return":
                    emitReturn(stmt);
                    return;
                case "throw":
                    emitThrow(stmt);
                    return;
                case "try":
                    // Two constructs share this KEYWORD (Token.isTryBlock). A plain "try { }" block emits its statements plus a label
                    // precomputed by collectTryBlockLabels (a hoisted catch's 'continue' jumps to it). Otherwise it is the bare
                    // "try call() catch(e){..}" statement; the call's result, if any, stays on the stack.
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
                    // bare mnemonic, no operands
                    line("YIELD");
                    return;
                case "ASM":
                    // named: emitted at each invocation site (see the "ASM" case in emit()); unnamed: only here
                    if (stmt.sub.isEmpty()) {
                        emitAsm(stmt.resolvedAsmContent);
                    }
                    return;
                default:
                    throw new IllegalStateException("unexpected statement keyword: " + stmt.text);
            }
        }
        if (stmt.type == TokenType.VARREF && stmt.resolvedAsmContent != null) {
            // "asm_name;" invocation (resolvedAsmContent is set only by TypeChecker, only for genuine ones)
            emitAsm(stmt.resolvedAsmContent);
            return;
        }
        // plain expression statement: the result, if any, is left on the stack (no POP)
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
                    // "&&then"/"||then" root: cascading jumps (see emitShortCircuit)
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

    /** A match is an if-chain: one CMP/JMP per branch (conditions via emitMatchCondition), shared end label; a bare 'else' branch falls into its block. */
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
            // "match Some(i) in/into x": one combined bool (bounds IN, then element alive) gating the single block.
            // node.left is the Some(..) call; only its index argument is pushed for the IN.
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
            if (node.someIndexElementExpr.someOnRef) {
                emitSomeOnRef(node.someIndexElementExpr, node.someIndexElementExpr, node.someIndexElementExpr.someProofKey);
            } else {
                requireGhostTableFunctionPresent("gt_alive_check", node);
                emitExpr(node.someIndexElementExpr);
                line("GT_ALIVE_CHECK");
            }
            line("AND " + node.resolvedType + " indeterminate_bool " + node.resolvedType);
            return;
        }
        if (node.type == TokenType.OPERATOR
                && (node.text.equals("instanceof") || node.text.equals("implements") || node.text.equals("in")
                        || node.text.equals("within") || node.text.equals("into"))) {
            // "within" as a match condition emits exactly like "within"
            // within/into emit exactly like in as expressions; the proof is recorded separately by the type checker
            emitExpr(node);
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left.type == TokenType.VARREF
                && node.left.text.equals("Some")) {
            // emitExpr already emits GT_ALIVE_CHECK for Some(..); the generic fallback below would duplicate it
            emitExpr(node);
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("fits")) {
            emitFits(node);
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("is")) {
            // "PUSH x / IS_BASE <leftType>": only the left side is pushed. The type is written out because for a composite
            // left operand (e.g. a range literal = two scalar pushes) the preceding line does not carry it.
            // See docs/BytecodeEmitter.md, "Operator emission".
            emitExpr(node.left);
            line("IS_BASE " + node.left.resolvedType);
            return;
        }
        if (node.type == TokenType.OPERATOR && (node.text.equals("!=") || node.text.equals(">"))
                && node.left.type == TokenType.VARREF && node.right.type == TokenType.INTEGER
                && node.right.text.equals("0")) {
            // division-safety proof shape: an ordinary comparison, never a GT_ALIVE_CHECK
            emitExpr(node);
            return;
        }
        // unreachable: checkMatchCondition rejects every other shape
        throw new IllegalStateException(
                "internal error: unrecognized match condition shape reached bytecode emission: " + node.text);
    }

    /** `/*! termination: .. *&#47;` labels found on emitted functions (HOB function name) and loops (HOB function name + "|" + loop label); checked by GasReport.checkLabels. */
    final Map<String, DocComments.Doc> funcTermLabels = new java.util.LinkedHashMap<>();
    final Map<String, DocComments.Doc> loopTermLabels = new java.util.LinkedHashMap<>();
    private String curHobFn;
    /** "function|loop label" -> most retries of a `match @lock` spin (> 0 literal, -1 run-time value); GasReport uses it as the loop's bound. */
    final Map<String, Long> spinBounds = new java.util.LinkedHashMap<>();
    /** Every emitted function and loop as {hobFunction, loopLabel or null, file, line, kind}: what `--fix termination` labels. */
    final List<String[]> termSites = new ArrayList<>();
    /** Where every emitted function and loop was written ("function" and "function|label" -> "file:line"), for the causes `--audit` prints. */
    final Map<String, String> sourcePos = new java.util.LinkedHashMap<>();

    private static String shownPos(Token t) {
        java.nio.file.Path abs = java.nio.file.Paths.get(t.file).toAbsolutePath().normalize();
        java.nio.file.Path base = java.nio.file.Paths.get("..").toAbsolutePath().normalize();
        return (abs.startsWith(base) ? base.relativize(abs).toString() : abs.toString()) + ":" + t.line;
    }

    private void noteLoopLabel(Token loopTok, String kind, String startLabel) {
        if (curHobFn != null && loopTok.file != null) {
            sourcePos.put(curHobFn + "|" + startLabel, shownPos(loopTok));
            termSites.add(new String[]{curHobFn, startLabel, loopTok.file, String.valueOf(loopTok.line), kind});
        }
        DocComments.Doc td = DocComments.find(loopTok.file, loopTok.line, "termination", kind);
        if (td != null && curHobFn != null) {
            loopTermLabels.put(curHobFn + "|" + startLabel, td);
        }
    }

    private void emitLoop(Token loopTok) {
        String startLabel = newLabel("loop");
        noteLoopLabel(loopTok, "loop", startLabel);
        if (loopTok.lockSpinBound != 0 && curHobFn != null) {
            spinBounds.put(curHobFn + "|" + startLabel, loopTok.lockSpinBound);
        }
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
     * Pre-loop assigns of a lowered "match @lock" loop that uses a "CLOSED:default" policy ($lm_tries = 0 and the policy's
     * parameters), run once before the start label and not through emitBlock. Their ALLOCs are hoisted by collectHoistedAllocs.
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
     * Emits a 'for' that TypeChecker.checkForLoop desugared into four nodes (range let, loop-variable let, 'in' condition, '++')
     * through the ordinary emission paths, plus the loop control flow (check at top, continue label before the step, jump back).
     * The hidden variables' ALLOCs are hoisted by collectHoistedAllocs.
     */
    private void emitForLoop(Token forTok) {
        Token rangeAssign = forTok.sub.get(0);
        Token loopVarAssign = forTok.sub.get(1);
        Token conditionIn = forTok.sub.get(2);
        Token increment = forTok.sub.get(3);

        emitAssign(rangeAssign);
        emitAssign(loopVarAssign);

        String startLabel = newLabel("for");
        noteLoopLabel(forTok, "for", startLabel);
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

    /** 'break': destruct/unlock lists, then a jump to the enclosing loop's end label. An empty stack means TypeChecker failed. */
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
     * 'continue' in three forms: a user for/loop (jump to the step/start), the CLOSED case of a 'match @lock' (retry), or a
     * catch-body continue (jump to the label from continueTargetLabels, precomputed because the catch body is hoisted).
     * See docs/BytecodeEmitter.md, "Hoisted catch blocks".
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
            // CLOSED case of a 'match @lock': retry at the spin loop's start (after the pre-loop init, so counters are kept); no lock is held
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
        // the value is evaluated before destructing; the returned value is never in the destruct list
        emitDestructList(returnTok.destructOnExit, returnTok);
        emitUnlockList(returnTok.unlockOnExit, returnTok);
        line("RET " + returnTok.resolvedType);
    }

    /** Callee half of struct RVO: writes the returned struct through "$ret_dest" and returns that pointer. See docs/BytecodeEmitter.md, "Return and struct return (RVO)". */
    private void emitStructRvoReturn(Token expr, Token returnTok) {
        String structName = currentFuncInfo.returnType.baseType;
        String ptrType = "raw_mut_" + structName;
        String structType = "mut_" + structName;
        Token unwrapped = unwrapMutWrappers(expr);
        if (unwrapped.isStructRvoCall) {
            // "return foo()": forward $ret_dest (emitStructRvoForwardCall)
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
     * THROW marker line, then the write of the operand into this frame's gt_error_message slot, the per-throw-site destruct list
     * and the unwind/exit. See docs/BytecodeEmitter.md, "Throw".
     */
    private void emitThrow(Token throwTok) {
        Token expr = throwTok.sub.get(0);
        String operand = expr.type == TokenType.VARREF ? expr.text : literalValueOf(expr);
        line("THROW " + operand);
        if (inGtSuppressedContext) {
            // gt-suppressed function: no ghost-table instructions and no gt_routine slot exist here (checkThrow bans throw in the ghost-table functions)
            return;
        }
        // operand is a string literal id or a variable name (a catch parameter is re-propagated unchanged)
        if (!(expr.type == TokenType.VARREF && functionCatchParams.contains(expr.text))) {
            emitTraceCapture();   // a fresh throw starts a new trace; `throw e` of a catch parameter keeps the one already captured
        }
        line("ADDR gt_error_message static_imut_string");
        line("PUSH " + operand + " " + expr.resolvedType);
        line("ASSIGN static_imut_string " + expr.resolvedType + " static_imut_string");
        // per-throw-site destruct list; currentFuncInfo/currentFuncMangledName are the emitted (post-dundering) identity
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

    /**
     * Nullable `ref` values are 64-bit ids, `ref some` values and proven bindings are addresses (see Token.refToId). This wrapper
     * applies the three marks the TypeChecker left on an expression: a nullable `ref` that is the subject of an alive proof is read from
     * the proof's hidden address slot; an address stored where a nullable `ref` is expected becomes an id (GT_REF_ID); a nullable `ref`
     * handed to a `raw` slot is resolved to its current address (GT_REF_RESOLVE, null when dead).
     */
    private void emitExpr(Token node) {
        if (node.refProofIsId && node.refProofKey != null) {
            String slot = refAddrSlots.get(node.refProofKey);
            if (slot != null) {
                line("PUSH " + slot + " " + node.resolvedType);
                return;
            }
            // proven by `assume match` (nothing ran, no address slot): resolve the id here
            emitExprCore(node);
            requireRefHook("gt_ref_resolve", node);
            line("GT_REF_RESOLVE " + node.resolvedType);   // like every instruction, it names the type it leaves (a following PUSH_FIELDNAME reads the struct from it)
            return;
        }
        emitExprCore(node);
        if (node.refToId) {
            requireRefHook("gt_ref_id", node);
            line("GT_REF_ID");
        } else if (node.refToRaw) {
            requireRefHook("gt_ref_resolve", node);
            line("GT_REF_RESOLVE " + node.resolvedType);
        }
    }

    /** Proof key -> hidden local holding the address a `match Some(<nullable ref>)` resolved (valid for the function being emitted). */
    private Map<String, String> refAddrSlots = new HashMap<>();

    private void requireRefHook(String decorator, Token at) {
        if (inGtSuppressedContext) {
            throw new CompilerException("type", at.file, at.line,
                    "a nullable 'ref' (which needs '@" + decorator + "') can't be used here -- this function is a ghost-table-decorated "
                            + "function, or is reachable from one, and ghost-table instructions can never be generated in that whole reachable set");
        }
        requireGhostTableFunctionPresent(decorator, at);
    }

    /**
     * `Some(x)` on a nullable `ref` (an id): resolve it into the proof's hidden address slot (one slot per proof key and function), then
     * test that address against null. The match body reads the slot instead of the id (emitExpr, Token.refProofKey).
     */
    private void emitSomeOnRef(Token someCall, Token subject, String proofKey) {
        emitSomeOnRef(someCall, subject, proofKey, true);
    }

    /**
     * `assume match Some(r)` / `assume match <index> in v and Some(v[i].r)` on a nullable `ref`: the same one-time resolve into the proof's
     * address slot as a real `match Some`, but no null test and no branch (the programmer vouches), so the uses after it read the slot
     * instead of resolving the id again at every use.
     */
    private void emitAssumeResolves(Token c) {
        if (c == null) {
            return;
        }
        if (c.someOnRef && c.someProofKey != null) {
            emitSomeOnRef(c, singleBuiltinArg(c), c.someProofKey, false);
        } else if (c.someIndexElementExpr != null && c.someIndexElementExpr.someOnRef) {
            emitSomeOnRef(c.someIndexElementExpr, c.someIndexElementExpr, c.someIndexElementExpr.someProofKey, false);
        }
        emitAssumeResolves(c.left);
        emitAssumeResolves(c.right);
        if (c.sub != null) {
            for (Token t : c.sub) {
                emitAssumeResolves(t);
            }
        }
    }

    private void emitSomeOnRef(Token someCall, Token subject, String proofKey, boolean test) {
        requireRefHook("gt_ref_resolve", someCall);
        String type = subject.resolvedType;
        String slot = proofKey == null ? null : refAddrSlots.get(proofKey);
        if (slot == null) {
            slot = declareHiddenLocal(type);
            if (proofKey != null) {
                refAddrSlots.put(proofKey, slot);
            }
        }
        line("ADDR " + slot + " " + type);
        emitExpr(subject);
        line("GT_REF_RESOLVE " + type);
        line("ASSIGN " + type + " " + type + " " + type);
        if (test) {
            line("PUSH " + slot + " " + type);
            line("PUSH null null");
            line("NEQ " + type + " null indeterminate_bool");
        }
    }

    private void emitExprCore(Token node) {
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
                    // expression-position "let x = try .. catch .."; emitAssign already emitted ADDR x
                    emitTryGuardedCall(node);
                    return;
                }
                break;
            case DELINEATOR:
                if (node.text.equals("(")) {
                    // transparent grouping parens
                    if (!node.childs.isEmpty()) {
                        emitExpr(node.childs.get(0));
                    }
                    return;
                }
                if (node.text.equals("[")) {
                    // array literal: each element pushed in written order
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
                // dedicated IN opcode; "into" emits identically (the difference is only a type-checker proof kind)
                if (op.isGuaranteedEnumMembershipCheck) {
                    // right side is a bare enum type name, pushed by name; the backend derives the check from the ENUM line
                    emitExpr(op.left);
                    line("PUSH " + op.right.resolvedType);
                    line("IN " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
                    return;
                }
                if (op.isStringMembershipCheck) {
                    // a string has no stored length: runtime byte scan under its own mnemonic IN_SCAN
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
                // same shape as "in"; always WITHIN (pointerness is handled by MembershipLoweringPass from the operand types)
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
                // compile-time mutability retag: nothing to emit
                emitExpr(op.left);
                return;
            case "new":
                emitNew(op);
                return;
            case "await":
                // no bytecode of its own; the wrapped CALL emits AWAIT_CALL
                emitExpr(op.left);
                return;
            case "par":
                // no bytecode of its own; the wrapped CALL emits PAR_CALL (its resolvedType was overwritten by checkPar)
                emitExpr(op.left);
                return;
            case "unsafe":
                // no bytecode of its own ("unsafe dyn(..)"); the wrapped call carries the corrected resolvedType
                emitExpr(op.left);
                return;
            case "atomic":
                // no bytecode of its own; the wrapped expression's resolvedType carries "atomic"
                emitExpr(op.left);
                return;
            case "swap":
                emitSwap(op);
                return;
            case "..":
                // a range is its two u64 bounds pushed back to back
                emitExpr(op.left);
                emitExpr(op.right);
                return;
            case ",":
                // no COMMA instruction, per spec -- just compile both sides
                emitExpr(op.left);
                emitExpr(op.right);
                return;
            case "instanceof": case "implements":
                // right operand is a bare type/interface name pushed by name. The left operand's type is a trailing operand
                // because a bare struct literal on the left leaves only its last member's push before this line.
                emitExpr(op.left);
                line("PUSH " + op.right.resolvedType);
                line(op.text.toUpperCase() + " " + op.left.resolvedType);
                return;
            case "and":
                // same bytecode as "&&"; the extra meaning is type-checker only
                emitLogical(op, "AND");
                return;
            case "FLOAT_CHECK":
                // compiler-internal operator (TypeChecker.lowerFloatMatchToIfChain); op.right.text holds the "finite|nan" chain string
                emitExpr(op.left);
                // an f64 operand carries its width (the f32 form is unchanged: "FLOAT_CHECK cats")
                line("FLOAT_CHECK " + op.right.text + (op.left.resolvedType != null && (op.left.resolvedType.equals("f64") || op.left.resolvedType.endsWith("_f64")) ? " 8" : ""));
                return;
            default:
                throw new IllegalStateException("unhandled operator in bytecode gen: '" + op.text + "'");
        }
    }

    /**
    /**
     * Pushes the base of an assignment target's '.'/LOOKUP chain: a no-storage bare variable gets ADDR instead of PUSH, a
     * no-storage '.'/LOOKUP root recurses into emitAssignTarget (so "grid[1][1] = x" computes nested addresses), and a
     * pointer-typed root or anything else goes through emitExpr. See docs/BytecodeEmitter.md, "Assignment targets".
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
        // null-safe: an enum name used only as a '.'-chain root has no resolvedType
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
            // fresh 'let': the name is the wrapper's first child
            Token nameTok = target.childs.get(0);
            line("ADDR " + nameTok.text + " " + target.resolvedType);
            return;
        }
        line("ADDR " + target.text + " " + target.resolvedType);
    }

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

    /**
     * Nulls a moved-from owner right after the move (synthesized "x = null", only where isOwnershipMoveSource is set). For a call
     * argument it sits between "POP ARGn" and the CALL, so a backend must keep its scratch registers off pending ARG registers.
     * See docs/BytecodeEmitter.md, "Moves and owning assignment".
     */
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

    /**
     * Before an owning assignment overwrites its target, destructs the old value (gt_destruct is null-safe); never for a fresh 'let'.
     * Returns true when the target has no flat name: its address is then on the stack (DUP_TOP + GT_DESTRUCT_ADDR) and emitAssign
     * must not compute it again. See docs/BytecodeEmitter.md, "Moves and owning assignment".
     */
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
        // Flat-named owns target: destruct AFTER the right side ("s = pass(s)" moves the old value into the call, nulling the variable).
        boolean lateDestruct = isFlatOwnsDestructTarget(op.left);
        // Pointer/index-reached owns target (no flat name): the right side is evaluated FIRST into a hidden local, so a move out of
        // the old value nulls it and a throw leaves it untouched.
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

    /** Strips transparent mut/imut wrappers (and a non-block try) to find the underlying node, e.g. the struct-RVO CALL. */
    private Token unwrapMutWrappers(Token t) {
        while (true) {
            if (t.type == TokenType.OPERATOR && (t.text.equals("mut") || t.text.equals("imut"))) {
                t = t.left;
            } else if (t.type == TokenType.KEYWORD && t.text.equals("try") && !t.isTryBlock
                    && t.sub != null && !t.sub.isEmpty()) {
                // the isStructRvoCall flag travels through try; emitStructRvoCallSequence re-attaches the try's catch label (ownerTryOf)
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

    /** Caller half of struct RVO: the callee gets the destination's address as a hidden first argument; no PUSH_RET/ASSIGN copy. See docs/BytecodeEmitter.md, "Return and struct return (RVO)". */
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
        // no PUSH_RET/ASSIGN: the callee already wrote the destination
    }

    /** "return foo()" tail forwarding: this function's own $ret_dest pointer is the callee's hidden destination (no address-of, no copy). */
    private void emitStructRvoForwardCall(Token callOp, String ptrType) {
        emitStructRvoCallSequence(callOp, ptrType, () -> line("PUSH $ret_dest " + ptrType));
    }

    /** Shared RVO call site: arguments are transferred as emitCallSequence would, with the hidden destination pointer (pushDestPointer) in register slot 0. */
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

    /** Name of the bare-variable destination (a 'let' binding or a plain assignment target). */
    private String destVarNameOf(Token target) {
        if (target.type == TokenType.KEYWORD && target.text.equals("let")) {
            return target.childs.get(0).text;
        }
        return target.text;
    }

    /**
     * True if the canonical type text contains "atomic_" (exact: canonical() writes it as its own segment). ATOMIC_PUSH/ATOMIC_ASSIGN
     * carry the volatile-like guarantee (no reordering, hoisting or CSE). See docs/BytecodeEmitter.md, "Atomics".
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

    /** x-- : emitIncrement's shape with DEC. */
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
     * Cascading CMP/JMP lowering of "&&then"/"||then". Exactly one of falseLabel/trueLabel is non-null; the other outcome falls
     * through. No AND/OR is emitted. See docs/BytecodeEmitter.md, "Short-circuit conditions".
     */
    private void emitShortCircuit(Token node, String falseLabel, String trueLabel) {
        if (!isThenVariant(node)) {
            // leaf: any ordinary expression (a plain &&/|| subtree stays eager); only its bool is tested
            // is tested here.
            emitExpr(node);
            line("CMP");
            if (falseLabel != null) {
                // "CMP; JMP label" = jump if false, fall through if true
                line("JMP " + falseLabel);
            } else {
                // jump to trueLabel only if true: there is no jump-if-true opcode, so invert with an extra unconditional JMP
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
                // common case: fall through if both are true, jump to falseLabel as soon as either fails
                emitShortCircuit(node.left, falseLabel, null);
                emitShortCircuit(node.right, falseLabel, null);
            } else {
                // left operand of an enclosing "||then": jump to trueLabel only if both pass; a failure lands past that jump
                String bothFailLabel = newLabel("andthen_fail");
                emitShortCircuit(node.left, bothFailLabel, null);
                emitShortCircuit(node.right, bothFailLabel, null);
                line("JMP " + trueLabel);
                line(bothFailLabel + ":");
            }
        } else { // "||then"
            if (falseLabel == null) {
                // This "||then" is itself the left operand of an
                // left operand of an enclosing "||then": forward trueLabel to both sides
                // level up), falling through only if both are false.
                emitShortCircuit(node.left, null, trueLabel);
                emitShortCircuit(node.right, null, trueLabel);
            } else {
                // left true: skip the right side and fall through as true; otherwise test the right side against falseLabel
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

    /** "a swap b": ATOMIC_SWAP, a read-modify-write; the left operand is pushed as an address, the result is the previous value. */
    private void emitSwap(Token op) {
        // ATOMIC_SWAP consumes an ADDRESS (xchg through it); pushing the field's value worked only for statics and segfaulted for a swap-lock struct in a local
        emitAssignTarget(op.left);
        emitExpr(op.right);
        line("ATOMIC_SWAP " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
    }

    /** Builtin calls: they bypass the overload table (no resolvedCallTarget), so they are dispatched here by reserved name before emitCall; see docs/BytecodeEmitter.md, "Builtin call emission". */
    /** Whole-program counter naming the hidden async-handle local of each par/await site. */
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
                    // Direct PUSH; a struct size stays symbolic (SIZEOF) until SizeofResolutionPass.
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
                        // Unsafe 2-arg terminator scan: never foldable, own LEN_SCAN mnemonic.
                        Token targetArg = lenArgs.get(0);
                        Token termArg = lenArgs.get(1);
                        emitExpr(targetArg);
                        emitExpr(termArg);
                        line("LEN_SCAN " + targetArg.resolvedType + " " + termArg.resolvedType);
                    } else if (op.builtinConstantValue != null) {
                        // Fixed array / string literal: folded to a PUSH.
                        line("PUSH " + op.builtinConstantValue + " " + op.resolvedType);
                    } else {
                        // Safe dynarray: run-time length, read by LEN.
                        emitExpr(singleBuiltinArg(op));
                        line("LEN");
                    }
                    return;
                }
                case "range": {
                    // A range is two u64 words; start is always literal 0.
                    if (op.builtinConstantValue != null) {
                        // String literal / fixed array: constant bounds, no RANGE instruction.
                        line("PUSH 0 imut_u64");
                        line("PUSH " + op.builtinConstantValue + " imut_u64");
                    } else {
                        // Safe dynarray: length read by LEN.
                        line("PUSH 0 imut_u64");
                        emitExpr(singleBuiltinArg(op));
                        line("LEN");
                    }
                    return;
                }
                case "deref":
                    // DEREF carries the pointee type because it is the copy width (the pushed pointer is always 8 bytes).
                    emitExpr(singleBuiltinArg(op));
                    line("DEREF " + op.resolvedType);
                    return;
                case "clone": {
                    // Hold back the pending catch label: a nested call would consume it (see "Allocation failure check").
                    final String cloneCatchLabel = pendingTryCatchLabel;
                    pendingTryCatchLabel = null;
                    // CLONE carries argument and result types so lowering can size the pointee.
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
                    // Same GT_ALIVE_CHECK sequence as a match condition; both restrictions apply in any expression position.
                    if (inGtSuppressedContext) {
                        throw new CompilerException("type", op.file, op.line,
                                "'Some(...)' (which needs '@gt_alive_check') can't be used here -- this "
                                        + "function is a ghost-table-decorated function, or is reachable "
                                        + "from one, and ghost-table instructions can never be generated "
                                        + "in that whole reachable set");
                    }
                    if (op.someOnRef) {
                        emitSomeOnRef(op, singleBuiltinArg(op), op.someProofKey);
                        return;
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
                    // rand() through the shared call sequence: no ExternInfo, default convention.
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
                    // Binary shape like ADD: both operands, then "OP leftType rightType resultType".
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
                    // Push all arguments, then MEMCOPY with the operand types.
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
                    // Hold back the pending catch label (see "Allocation failure check").
                    final String dynCatchLabel = pendingTryCatchLabel;
                    pendingTryCatchLabel = null;
                    if (op.dynFromStringSource) {
                        // dyn(text): NEW_FROM_STRING copies the characters at run time.
                        Token stringArg = singleBuiltinArg(op);
                        emitExpr(stringArg);
                        line("NEW_FROM_STRING " + op.resolvedType.substring("owns_".length()) + " "
                                + stringArg.resolvedType);
                        // Safe dyn(text) throws: same allocation failure check as new.
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
                    // dyn([..]): push the elements, then NEW_DYN (safe, keeps count) or NEW_UDYN (unsafe, flat size); see docs/BytecodeEmitter.md, "dyn literals".
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
                    // Safe dyn([..]) throws like new; NEW_UDYN stays non-throwing.
                    if (!isUnsafeLiteral) {
                        pendingTryCatchLabel = dynCatchLabel;
                        requireGhostTableFunctionPresent("gt_register", op);
                        line("GT_REGISTER");
                        emitAllocFailureCheck(op, literalElements, literalTemps);
                    }
                    return;
                }
                case "resize": {
                    // Hold back the pending catch label (see "Allocation failure check").
                    final String resizeCatchLabel = pendingTryCatchLabel;
                    pendingTryCatchLabel = null;
                    // resize takes ownership of the array and returns a new owns; RESIZE (safe) / URESIZE (unsafe). No CC bracket. see docs/BytecodeEmitter.md, "resize emission".
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
                    // Safe resize throws like new; URESIZE stays non-throwing.
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
                    // PUSH fnptr, args, INVOKE n (n excludes the pointer); the pointer is not an ARGn word.
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
     * Wraps one call in CC_START .. CC_END (first n arguments moved to ARGn registers) and adds PUSH_RET for a non-void result.
     * pushFuncPtr is non-null only for call(); regArgs are the register-transferred arguments; returnTypeOrNull null = void.
     * The convention is the one TypeChecker resolved, never re-derived. see docs/BytecodeEmitter.md, "Call sequence".
     */
    private void emitCallSequence(Runnable pushFuncPtr, List<Token> regArgs, Runnable emitCallInstruction,
            String returnTypeOrNull, String conventionName) {
        emitCallSequence(pushFuncPtr, regArgs, emitCallInstruction, returnTypeOrNull, conventionName, -1);
    }

    /** varargStartIndex: first true vararg of a variadic extern (-1 = none); only emitExternCall passes one. see docs/BytecodeEmitter.md, "Variadic calls". */
    private void emitCallSequence(Runnable pushFuncPtr, List<Token> regArgs, Runnable emitCallInstruction,
            String returnTypeOrNull, String conventionName, int varargStartIndex) {
        ArgCounters counters = new ArgCounters(checker.getConfig().callingConventions.get(conventionName));
        line("CC_START " + conventionName);
        if (pushFuncPtr != null) {
            pushFuncPtr.run();
        }
        // The register index is a running counter advanced by the words each argument occupied (a range takes two); ArgCounters tracks the banks.
        for (int argIndex = 0; argIndex < regArgs.size(); argIndex++) {
            Token arg = regArgs.get(argIndex);
            boolean isTrueVararg = varargStartIndex >= 0 && argIndex >= varargStartIndex;
            boolean promoteToDouble = isTrueVararg; // "in the ... part of a variadic call": an f32 is widened, any float gets win64's register duplication
            emitArgTransfer(arg, counters, promoteToDouble);
            if (arg.isOwnershipMoveSource) {
                // Null the moved owns slot right after its value is captured and before the call, so a throw inside the callee sees it null.
                // A by-value range is never a move source. Register hazard: see emitOwnershipMoveNullOut.
                emitOwnershipMoveNullOut(arg);
            }
        }
        if (varargStartIndex >= 0) {
            // SysV varargs: %al = xmm registers used. Only for a variadic call, just before the call instruction; see docs/BytecodeEmitter.md, "Variadic calls".
            line("VARARGS_XMM_COUNT " + counters.floatRegistersUsed());
        }
        emitCallInstruction.run();
        line("CC_END " + conventionName);
        if (returnTypeOrNull != null) {
            line("PUSH_RET " + returnTypeOrNull);
        }
    }

    /** A genuine by-value range type (no storage prefix). */
    private static final java.util.regex.Pattern BY_VALUE_RANGE_ARG =
            java.util.regex.Pattern.compile("^(?:imut|mut|indeterminate)_range(?:\\(.*\\))?$");

    /** Transfers one argument into its register(s); a by-value range becomes its two u64 bounds. see docs/BytecodeEmitter.md, "Call sequence". */
    private void emitArgTransfer(Token arg, ArgCounters counters, boolean promoteToDouble) {
        Token unwrapped = unwrapParens(arg);
        if (BY_VALUE_RANGE_ARG.matcher(arg.resolvedType).matches()) {
            // A range's words are u64 bounds, never float.
            emitRangeArgWords(unwrapped, counters);
            return;
        }
        emitArgWord(arg, counters, promoteToDouble);
    }

    /** Range portion of emitArgTransfer; both words are int-bank u64. */
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

    /** Pushes one word and moves it to the next register of its bank; promoteToDouble inserts PROMOTE_F32_TO_F64 for a true f32 vararg. */
    private void emitArgWord(Token expr, ArgCounters counters, boolean promoteToDouble) {
        emitExpr(expr);
        if (promoteToDouble && isF32Canonical(expr.resolvedType)) {
            line("PROMOTE_F32_TO_F64");
        }
        // promoteToDouble doubles as the win64 variadic float duplication flag (same precondition).
        emitArgTransferTail(expr.resolvedType, counters, promoteToDouble);
    }

    /** Pushes a synthesized qualified-name word (e.g. "x.start") and transfers it; never float. */
    private void emitSyntheticArgWord(String qualifiedName, String type, ArgCounters counters) {
        line("PUSH " + qualifiedName + " " + type);
        emitArgTransferTail(type, counters, false);
    }

    /** Emits POP ARGn / POP FARGn if the word's bank has a register left, else leaves it on the stack. isTrueVarargFloat additionally duplicates the word into the same-index int register on a shared-position (win64) convention. see docs/BytecodeEmitter.md, "Variadic calls". */
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

    /** True for an f32/f64 canonical type (the base type is the last segment). */
    private static boolean isFloatCanonical(String canonical) {
        return canonical != null && (canonical.equals("f32") || canonical.endsWith("_f32")
                || canonical.equals("f64") || canonical.endsWith("_f64"));
    }

    /** True only for a 4-byte f32 (the only float a C variadic call must widen to double; an f64 already is one). */
    private static boolean isF32Canonical(String canonical) {
        return canonical != null && (canonical.equals("f32") || canonical.endsWith("_f32"));
    }

    /** Per-call-site counters choosing the register and bank (int/float) of each argument word: SysV counts the banks independently, win64 shares one position counter. A stack-passed word still advances its counter. see docs/BytecodeEmitter.md, "Call sequence". */
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

        /** Float-bank registers really used (clamped to floatCount) for the SysV %al rule; not the raw floatIdx, which keeps counting stack-passed floats. */
        int floatRegistersUsed() {
            return Math.min(floatIdx, floatCount);
        }

        /** True for win64's shared position counter. */
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

    /** Null for void ("imut_void"/"mut_void", exact match) so no PUSH_RET is emitted. */
    private String voidSafeReturnType(Token op) {
        String t = op.resolvedType;
        if (t == null || t.equals("imut_void") || t.equals("mut_void")) {
            return null;
        }
        return t;
    }

    /** SLEEP sleep_call duration: dedicated mnemonic naming the @sleep function (resolved by TypeChecker) with the duration type. see docs/BytecodeEmitter.md, "Sleep call". */
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
    /** Extern call; a variadic one carries its pushed-argument count in EXTERN_CALL. see docs/BytecodeEmitter.md, "Variadic calls". */
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
        // par/await are intercepted earlier (emitAsyncCall). A self-call (compared after dunder redirection) is RECURSIVE_CALL.
        String mnemonic = funcName.equals(currentFuncMangledName) ? "RECURSIVE_CALL" : "CALL";
        // Staged before the argument sequence: the slot is only read by a deeper GT_UNWIND, after the real call runs.
        stageCallSiteForUnwind(op);
        emitCallSequence(null, args, () -> line(mnemonic + " " + funcName), voidSafeReturnType(op),
                op.resolvedCallConvention);
    }

    /**
     * Desugars a par/await site into ordinary ALLOC/NEW/GT_REGISTER/CALL bytecode with a hidden local __async_handle_N (emitted inline, not hoisted).
     * see docs/BytecodeEmitter.md, "par / await desugaring".
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
                case "gate":
                    line("PUSH AsyncGate.OPEN " + entry.canonicalType);
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
        if (!isAwait) {
            // par_call also receives where to store the thread id: the handle's own threadId (so the handle's drop hook can join
            // the thread), or null for the hidden fire-and-forget handle (its thread is detached).
            if (handleStructInfo.members.containsKey("gate")) {
                line("PUSH " + tempName + " " + handleCanonical);
                line("PUSH_FIELDNAME threadId mut_u64");
                line("DOT_LHS " + handleCanonical + " mut_u64 mut_u64");
            } else {
                line("PUSH null raw_mut_u64");
            }
            line("POP ARG2 raw_mut_u64");
        }
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
            // Safe for void too: @await_call has already joined, no other thread touches the handle.
            requireGhostTableFunctionPresent("gt_destruct", op);
            line("GT_DESTRUCT " + tempName);
        } else if (!isVoidResult) {
            // Non-void par: the handle is the expression value; the consumer owns it.
            line("PUSH " + tempName + " " + handleCanonical);
        } else {
            // Void par: the hidden handle is destructed by the trampoline itself (selfDestructHidden), never here: the thread may still be reading it.
        }
    }

    /** Sets the shared `gt_error_message` and jumps to the catch of the `try`/`?` wrapping a 'par'/'await' (same shape as the 'new' out-of-memory exit). */
    private void emitAsyncFailureJump(String catchLabel, String message) {
        String messageId = hoistedStringId(message);
        emitTraceCapture();
        line("ADDR gt_error_message static_imut_string");
        line("PUSH " + messageId + " static_imut_string");
        line("ASSIGN static_imut_string static_imut_string static_imut_string");
        line("JMP " + catchLabel);
    }

    /** Exact-equality void test, as voidSafeReturnType. */
    private boolean isVoidCanonical(String canonical) {
        return canonical == null || canonical.equals("imut_void") || canonical.equals("mut_void");
    }

    /** Canonical text of a bare function reference ("static_imut_func(params)ret"), mirroring TypeInfo.funcPointer; used for the trampoline address push. */
    private String funcPointerCanonicalOf(TypeChecker.FuncInfo info) {
        // Function-pointer values always have static storage.
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

    /** Under gt-suppression a CALL to a gt-reachable function is redirected to its "__" duplicate. see docs/BytecodeEmitter.md, "Call target redirection". */
    private String resolveCallTargetForEmission(String mangledName) {
        if (inGtSuppressedContext && gtReachableMangledNames.contains(mangledName)) {
            return "__" + mangledName;
        }
        // main is emitted as __caspien_main when an event loop is the real entry (see emitFunc); redirect calls to it.
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
            // Scalar element of a fixed array variable: take the element address (ADDR + LOOKUP_LHS) and load only it, instead of pushing the whole array. Aggregate elements keep the old path (DEREF carries no size).
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

    /** 'x fits T' as unsigned 64-bit compares only; see docs/BytecodeEmitter.md, "fits". */
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

    /** wrap:<T>(x) / sat:<T>(x), total integer conversions; see docs/BytecodeEmitter.md, "wrap and sat". */
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
    /** "as": no instruction for an alias cast, FCONV / TRUNC / SEXT / ZEXT otherwise. see docs/BytecodeEmitter.md, "as casts". */
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

    /** Both EnumName.Variant and instance.member chains: one PUSH of the dot-joined name when the chain is nameable, else base + DOT / DOT_LHS+DEREF. see docs/BytecodeEmitter.md, "Dot access". */
    private void emitDot(Token op) {
        if (op.catchMember != null) {
            String param = op.catchTry.catchParamName;
            if (op.catchMember.equals("msg")) {
                line("PUSH " + param + " static_imut_string");
            } else {
                String arr = "mut_u64[" + (TraceConfig.depth + 1) + "]";
                line("PUSH " + param + "$trace " + arr);
                line("ADDR_OF AUTO " + arr + " auto_indeterminate_u64[" + (TraceConfig.depth + 1) + "]");
            }
            return;
        }
        if (isQualifiedNameableDot(op)) {
            line("PUSH " + qualifiedDotName(op) + " " + op.resolvedType);
            return;
        }
        // Not nameable (base is a LOOKUP/call, or a segment is a pointer): emit the base, then DOT (inline base) or DOT_LHS+DEREF (pointer base), decided per level.
        emitExpr(op.left);
        line("PUSH_FIELDNAME " + op.right.text + " " + op.resolvedType);
        if (hasStorage(op.left.resolvedType)) {
            line("DOT_LHS " + op.left.resolvedType + " " + op.resolvedType + " " + op.resolvedType);
            line("DEREF " + op.resolvedType);
        } else {
            line("DOT " + op.left.resolvedType + " " + op.resolvedType + " " + op.resolvedType);
        }
    }

    /** True when the whole chain is storage-free and rooted at a VARREF (a LOOKUP/CALL .text is a marker, not a name). */
    private boolean isQualifiedNameableDot(Token node) {
        if (node.catchMember != null) {
            return false;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".")) {
            // Per level: the segment dotted into must be storage-free too, the same bail-out AddressLoweringPass.resolveAddress applies.
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

    /** Struct literal: optional hidden ___type push, then members in padded layout order (declaration order, not literal order). Single choke point for `new X{..}` and bare `X{..}`. see docs/BytecodeEmitter.md, "Struct literals". */
    private void emitInstantiate(Token op) {
        String structName = op.left.text;
        TypeChecker.StructInfo structInfo = checker.getStructs().get(structName);
        if (structInfo.classId != null) {
            line("PUSH " + structInfo.classId + " imut_u64");
        }

        if (op.isLockViolationConstruct) {
            // OTC lock violation: only the lock/discriminant value is pushed, then STACK_LOCK <structName>.
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
        // Walk the padded layout: ___type already pushed, each padding gap is a STACK_LOCK.
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
                // Null the move source right away: a later field value may contain a throwing call.
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

    /** `new ctor(..)`: construct into a hidden inline local through emitStructRvoCallSequence, then push it whole. see docs/BytecodeEmitter.md, "new". */
    private void emitNewFromStructRvoCall(Token callOp) {
        // Struct name from the callee return type, else from the call node's resolvedType.
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
    /** new: operand, NEW type, GT_REGISTER, null check jumping to the local catch; moved-in sources are nulled only after success. see docs/BytecodeEmitter.md, "new". */
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
            emitTraceCapture();
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

    /** Null check after NEW_DYN / NEW_FROM_STRING / RESIZE, the same sequence emitNew uses; expects the value on top of the stack. see docs/BytecodeEmitter.md, "Allocation failure check". */
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
            emitTraceCapture();
            line("ADDR gt_error_message static_imut_string");
            line("PUSH " + oomStringId + " static_imut_string");
            line("ASSIGN static_imut_string static_imut_string static_imut_string");
            line("JMP " + pendingTryCatchLabel);
        }
        line(okLabel + ":");
    }
    /** ADDR_OF auto/raw/ref (nothing for `static`); see docs/BytecodeEmitter.md, "Address-of". */
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
                // Hoisted: the quoted text appears once in a STRING declaration at the top of the output.
                return hoistedStringId(node.literalValue);
            case CHAR:
                return "'" + escapeForBytecode(node.literalValue) + "'";
            case NULL:
                return "null";
            default:
                return node.text; // INTEGER, FLOAT, BOOL
        }
    }

    /** Re-escapes a decoded char/string value for the text bytecode: every lexer escape (\\ \" \n \t \r \0 \b \f \a \v). */
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

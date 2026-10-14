package caspien.typechecker;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;
import caspien.config.TraceConfig;
import caspien.parser.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import caspien.token.Decorator;

/**
 * Stage 6: type and logic checking over the import-resolved, tree-built program.
 * Type equality in comparisons is base-type only; see docs/TypeChecker.md, "Design decisions".
 */

/** TypeCheckerDeclarations: entry point, root declaration collection, struct/enum/interface flattening (part 2 of 11; see README.md in this folder). */
public final class TypeCheckerDeclarations {
    private final TypeChecker hub;

    TypeCheckerDeclarations(TypeChecker hub) {
        this.hub = hub;
    }

    /** Struct name a `ref` points at, for ProofKills (null when it is not a plain struct: then any destroying event ends the proof). */
    public String pointeeOf(TypeInfo t) {
        if (t == null || t.baseType == null) {
            return null;
        }
        String b = stripArrayDims(t.baseType);
        return hub.model.structs.containsKey(b) ? b : null;
    }

    /** Names of the types an object of type t can contain by ownership (itself, owned members, inline members, transitively); null = unknown. */
    public Set<String> destroyedTypes(TypeInfo t) {
        if (t == null || t.baseType == null) {
            return null;
        }
        Set<String> out = new HashSet<>();
        collectDestroyed(t, out);
        return out;
    }

    public void collectDestroyed(TypeInfo root, Set<String> out) {
        Deque<TypeInfo> work = new java.util.LinkedList<>();
        work.push(root);
        while (!work.isEmpty()) {
            TypeInfo t = work.pop();
            if (t == null || t.baseType == null || (t.storage != null && !"owns".equals(t.storage))) {
                continue;
            }
            String b = stripArrayDims(t.baseType);
            if (!out.add(b)) {
                continue;
            }
            StructInfo si = hub.model.structs.get(b);
            if (si != null) {
                si.members.values().forEach(work::push);
            }
        }
    }

    /** An inline (no storage keyword) struct value that owns memory through some member: it has to be dropped at scope end and moved, not copied. */
    public boolean isInlineOwningStruct(TypeInfo t) {
        if (t == null || t.storage != null || t.baseType == null) {
            return false;
        }
        String elem = stripArrayDims(t.baseType);
        return hub.model.structs.containsKey(elem) && elementTextOwnsMemory(elem);
    }

    /** `Holder[2][3]` -> `Holder`: a fixed array of inline owning structs is an owned slot too (dropped element by element, moved as a whole). */
    public String stripArrayDims(String baseType) {
        while (hub.isArrayType(baseType)) {
            baseType = hub.arrayElementType(baseType);
        }
        return baseType;
    }

    /** True for a fixed array type (`Holder[2]`). */
    public boolean isFixedArrayTypeText(String baseType) {
        return baseType != null && hub.isArrayType(baseType);
    }

    /** True when a dynarray element, given as the canonical text in a dynarray type (`Holder`, `owns_some_mut_World`), owns memory. */
    public boolean elementTextOwnsMemory(String elemText) {
        if (elemText.startsWith("owns_") || elemText.startsWith("dynarray(") || elemText.startsWith("unsafe_dynarray(")) {
            return true;
        }
        StructInfo si = hub.model.structs.get(elemText);
        if (si == null) {
            return false;
        }
        Set<String> visiting = new HashSet<>();
        visiting.add(elemText);
        for (TypeInfo m : si.members.values()) {
            if (hub.model.typeOwnsMemory(m, visiting)) {
                return true;
            }
        }
        return false;
    }

    /** The unsafe tag an operation on this unsafe dynarray needs: `udyn:owns` when its elements own memory (the programmer destructs them by hand), else `udyn`. */
    public String udynTag(TypeInfo unsafeDynArrayType) {
        TypeInfo el = unsafeDynArrayType == null ? null : unsafeDynArrayType.unsafeDynArrayElementType;
        return hub.model.typeOwnsMemory(el, new HashSet<>()) ? "udyn:owns" : "udyn";
    }

    /** Called where `unsafe` bypasses a rule: true in unsafe context, and records `tag` on the enclosing tagged block. Call only when the bypass is actually exercised. */
    public boolean unsafeBypass(Scope scope, String tag) {
        if (!"unsafe".equals(scope.currentSafety)) {
            return false;
        }
        for (Scope sc = scope; sc != null; sc = sc.parent) {
            if (sc.unsafeBlock != null) {
                sc.unsafeBlock.use(tag);
                break;
            }
        }
        return true;
    }

    /** Local ASM names (scope chain) first, then root-level; null if unknown. */
    public AsmInfo lookupAsmName(String name, Scope scope) {
        for (Scope s = scope; s != null; s = s.parent) {
            AsmInfo info = s.localAsmNames.get(name);
            if (info != null) {
                return info;
            }
        }
        return hub.model.rootAsms.get(name);
    }

    public void recordUnavailable(String key, String shownName, String concreteName, boolean ctor, DeferredSignatureException e) {
        String msg = (ctor ? "constructor of '" : "method '" + shownName + "' of '") + concreteName
                + "' is unavailable for this type argument: "
                + e.raw;
        hub.model.unavailableGenericMethods.computeIfAbsent(key, k -> new ArrayList<>()).add(msg);
    }

    public String unavailableNote(String key) {
        List<String> l = hub.model.unavailableGenericMethods.get(key);
        if (l == null) {
            return "";
        }
        return " (" + String.join("; ", l) + ")";
    }

    /** For BytecodeEmitter: the resolved declarations this checker collected, valid after check() runs. */
    public Map<String, StructInfo> getStructs() { return hub.model.structs; }
    public Map<String, EnumInfo> getEnums() { return hub.model.enums; }
    public Map<String, List<FuncInfo>> getFunctions() { return hub.model.functions; }
    public Map<String, InterfaceInfo> getInterfaces() { return hub.model.interfaces; }
    public Map<String, List<ImplInfo>> getImpls() { return hub.model.implsByConcreteType; }
    public Map<String, LibraryInfo> getLibraries() { return hub.model.libraries; }
    public Map<String, ExternInfo> getExterns() { return hub.model.externs; }
    public List<String> getGlobalOrder() { return hub.model.globalOrder; }
    public Map<String, TypeInfo> getGlobals() { return hub.model.globals; }
    public FuncInfo getEventLoopFunc() { return hub.model.eventLoopFunc; }
    public Map<String, Token> getGlobalRhs() { return hub.model.globalRhsByName; }

    /** For BytecodeEmitter: the (at most one) function decorated with the given ghost-table decorator name, or null if none exists in this compilation unit. */
    public FuncInfo getGhostTableFunction(String decoratorName) { return hub.model.ghostTableFunctions.get(decoratorName); }
    public boolean usesOwnsRefDynNew() { return hub.model.usesOwnsRefDynNew; }
    /** True when the program throws, or when stack tracing is on (it needs the throw-style frame layout in every function). */
    public boolean usesThrow() { return hub.model.usesThrow || TraceConfig.usesTrace; }

    /** Does every path through a 'match @lock' CLOSED body end in continue / return / throw / break? */
    public boolean closedCaseTerminates(List<Token> lines) {
        boolean saved = hub.model.breakCountsAsTerminator;
        hub.model.breakCountsAsTerminator = true;
        try {
            return hub.statements.definitelyReturns(lines);
        } finally {
            hub.model.breakCountsAsTerminator = saved;
        }
    }

    /** True once "structName" has at least one declared constructor. */
    public boolean hasConstructors(String structName) {
        return hub.model.functions.containsKey(Parser.CONSTRUCTOR_NAME_PREFIX + structName);
    }

    /**
     * Flattens root lines through unsafe/safe wrappers into declarations tagged with their safety context;
     * an 'unsafe' nested in 'safe' is an error (the reverse is allowed).
     */
    public List<FlatDecl> flattenRootLines(List<Token> lines, String currentSafety) {
        List<FlatDecl> out = new ArrayList<>();
        Deque<Iterator<Token>> iterators = new ArrayDeque<>();
        Deque<String> safeties = new ArrayDeque<>();
        iterators.push(lines.iterator());
        safeties.push(currentSafety);
        while (!iterators.isEmpty()) {
            if (!iterators.peek().hasNext()) {
                iterators.pop();
                safeties.pop();
                continue;
            }
            Token lineTok = iterators.peek().next();
            String safety = safeties.peek();
            Token t = lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && (t.text.equals("unsafe") || t.text.equals("safe"))) {
                if (t.text.equals("unsafe") && safety.equals("safe")) {
                    throw new CompilerException("type", t.file, t.line,
                            "'unsafe' cannot be nested inside 'safe'");
                }
                iterators.push(t.childs.iterator());
                safeties.push(t.text);
            } else {
                out.add(new FlatDecl(lineTok, safety));
            }
        }
        return out;
    }

    /** Entry point: type/logic-checks a fully resolved program's top-level statements. */
    public void check(List<Token> rootLines) {
        hub.model.rootLinesRef = rootLines;
        collectDeclarations(rootLines);
        hub.signatures.forceGhostTableFunctionNames();
        hub.controlFlow.validateGenericBounds(rootLines);
        for (FlatDecl fd : flattenRootLines(rootLines, "indeterminate")) {
            Token t = fd.lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && t.text.equals("func")) {
                hub.statements.checkFunctionBody(hub.signatures.functionInfoFor(t));
            }
        }
        for (List<ImplInfo> implList : hub.model.implsByConcreteType.values()) {
            for (ImplInfo implInfo : implList) {
                for (FuncInfo methodInfo : implInfo.methods.values()) {
                    hub.statements.checkFunctionBody(methodInfo);
                }
                for (FuncInfo methodInfo : implInfo.staticMethods.values()) {
                    hub.statements.checkFunctionBody(methodInfo);
                }
            }
        }
        for (LibraryInfo libInfo : hub.model.libraries.values()) {
            for (List<FuncInfo> overloads : libInfo.ownFunctions.values()) {
                for (FuncInfo fn : overloads) {
                    hub.statements.checkFunctionBody(fn);
                }
            }
        }
        // Generic constructors are in `functions` only, so neither loop above reaches them.
        for (FuncInfo ctorInfo : hub.model.genericConstructorFuncInfos) {
            hub.statements.checkFunctionBody(ctorInfo);
        }
        hub.signatures.checkSafetyCallGraph();
        hub.signatures.checkDynArrayForLoopResizeSafety();
        hub.signatures.checkNoRecursion();
        hub.signatures.validateNoThrowReachableFromGhostTableFunctions();
        hub.signatures.validateNoAutoReturns();
        hub.signatures.validateNoRefSomeStructMembers();
        hub.signatures.validateNoAutoStructMembers();
        hub.signatures.validateNoRecursiveStructs();
        hub.signatures.validateNoPointersInLockStructs();
        validateMainFunction();
        hub.statements.validateUnsafeBlockTags();
    }

    /** All top-level funcs (any overload) with the given decorator; @tick/@event_loop/@make_safe_args are found by decorator, not by name. */
    public List<FuncInfo> findFuncsWithDecorator(String decoratorName) {
        List<FuncInfo> found = new ArrayList<>();
        for (List<FuncInfo> overloads : hub.model.functions.values()) {
            for (FuncInfo f : overloads) {
                if (hub.impls.hasDecorator(f.funcToken.decorators, decoratorName)) {
                    found.add(f);
                }
            }
        }
        return found;
    }

    /** Requires "owns some mut" of a concrete struct; returns the struct name so callers can compare "same T". */
    public String requireOwnsSomeMutStruct(TypeInfo t, String context, Token at) {
        boolean shapeOk = "owns".equals(t.storage) && t.isSome && "mut".equals(t.mutability)
                && hub.model.structs.containsKey(t.baseType);
        if (!shapeOk) {
            throw new CompilerException("type", at.file, at.line,
                    context + " must be 'owns some mut' a concrete struct type -- got '" + t.canonical()
                            + "'");
        }
        return t.baseType;
    }

    /** Validates @with_tick, @tick and @event_loop together with main. See docs/TypeChecker.md, "main, @tick, @event_loop, @make_safe_args". */
    public void validateTickEventLoop(FuncInfo main, boolean withTick) {
        List<FuncInfo> tickFuncs = findFuncsWithDecorator("tick");
        List<FuncInfo> eventLoopFuncs = findFuncsWithDecorator("event_loop");
        if (!withTick) {
            // Without @with_tick, neither decorator may appear.
            if (!tickFuncs.isEmpty()) {
                Token at = tickFuncs.get(0).funcToken;
                throw new CompilerException("type", at.file, at.line,
                        "'@tick' can only be used when 'main' is '@with_tick'");
            }
            if (!eventLoopFuncs.isEmpty()) {
                Token at = eventLoopFuncs.get(0).funcToken;
                throw new CompilerException("type", at.file, at.line,
                        "'@event_loop' can only be used when 'main' is '@with_tick'");
            }
            return;
        }
        // With @with_tick, @tick and @event_loop are each required exactly once.
        if (tickFuncs.isEmpty()) {
            throw new CompilerException("type", main.funcToken.file, main.funcToken.line,
                    "'main' is '@with_tick', so a '@tick' function is required");
        }
        if (tickFuncs.size() > 1) {
            Token at = tickFuncs.get(1).funcToken;
            throw new CompilerException("type", at.file, at.line,
                    "'@tick' may only be used once in the final compilation unit");
        }
        FuncInfo tick = tickFuncs.get(0);
        Token tickAt = tick.funcToken;
        if (tick.paramNames.size() != 1) {
            throw new CompilerException("type", tickAt.file, tickAt.line,
                    "'@tick' must take exactly one parameter, 'owns some mut' a concrete struct type");
        }
        String tickParamStruct = requireOwnsSomeMutStruct(tick.paramTypes.get(0), "'@tick's own parameter",
                tickAt);
        String tickReturnStruct = requireOwnsSomeMutStruct(tick.returnType, "'@tick's own return type",
                tickAt);
        if (!tickParamStruct.equals(tickReturnStruct)) {
            throw new CompilerException("type", tickAt.file, tickAt.line,
                    "'@tick's own parameter ('" + tickParamStruct + "') and return type ('"
                            + tickReturnStruct + "') must be the same struct");
        }
        // main must return the same struct as @tick.
        String mainReturnStruct = requireOwnsSomeMutStruct(main.returnType, "'main'", main.funcToken);
        if (!mainReturnStruct.equals(tickReturnStruct)) {
            throw new CompilerException("type", main.funcToken.file, main.funcToken.line,
                    "'main' returns '" + mainReturnStruct + "', but '@tick' is for '" + tickReturnStruct
                            + "' -- both must be the same struct");
        }
        // @event_loop is required exactly once.
        if (eventLoopFuncs.isEmpty()) {
            throw new CompilerException("type", main.funcToken.file, main.funcToken.line,
                    "'main' is '@with_tick', so an '@event_loop' function is required");
        }
        if (eventLoopFuncs.size() > 1) {
            Token at = eventLoopFuncs.get(1).funcToken;
            throw new CompilerException("type", at.file, at.line,
                    "'@event_loop' may only be used once in the final compilation unit");
        }
        FuncInfo eventLoop = eventLoopFuncs.get(0);
        Token elAt = eventLoop.funcToken;
        // @event_loop is the real entry point, so its signature follows main's argument shape; its body is not validated.
        if (main.mainArgShape == null) {
            if (!eventLoop.paramNames.isEmpty()) {
                throw new CompilerException("type", elAt.file, elAt.line,
                        "'@event_loop' must take no parameters ('main' itself takes none)");
            }
        } else {
            boolean elArgsOk = eventLoop.paramNames.size() == 2
                    && eventLoop.paramTypes.get(0).canonical().equals("imut_s32")
                    && eventLoop.paramTypes.get(1).canonical().equals("raw_mut_raw_mut_char");
            if (!elArgsOk) {
                throw new CompilerException("type", elAt.file, elAt.line,
                        "'@event_loop' must take '(imut s32, raw mut raw mut char)' -- 'main' itself "
                                + "takes arguments, so the real entry point needs to receive the raw "
                                + "C-ABI arguments to pass (or wrap) through to it");
            }
        }
        String elReturn = eventLoop.returnType.canonical();
        boolean elReturnOk = elReturn.equals("imut_s32") || elReturn.equals("imut_bool")
                || elReturn.equals("imut_void");
        if (!elReturnOk) {
            throw new CompilerException("type", elAt.file, elAt.line,
                    "'@event_loop' must return 'imut s32', 'imut bool', or 'void' (one of the 'main' "
                            + "signatures that doesn't return a pointer) -- got '" + elReturn + "'");
        }
        hub.model.eventLoopFunc = eventLoop;
    }

    /** Exactly one 'main' with a valid signature, over the final import-flattened `functions` map. See docs/TypeChecker.md, "main, @tick, @event_loop, @make_safe_args". */
    public void validateMainFunction() {
        List<FuncInfo> mains = hub.model.functions.get("main");
        if (mains == null || mains.isEmpty()) {
            Token fallback = hub.model.rootLinesRef.isEmpty() ? null : hub.model.rootLinesRef.get(0);
            String file = fallback != null ? fallback.file : "<unknown>";
            int line = fallback != null ? fallback.line : 0;
            throw new CompilerException("type", file, line,
                    "no 'main' function found -- every program needs exactly one, with signature "
                            + "'func main() imut s32', 'func main() imut bool', or 'func main() void'");
        }
        if (mains.size() > 1) {
            FuncInfo second = mains.get(1);
            throw new CompilerException("type", second.funcToken.file, second.funcToken.line,
                    "more than one 'main' function found -- a program can only have exactly one");
        }
        FuncInfo main = mains.get(0);
        Token at = main.funcToken;
        main.mainArgShape = validateAndResolveMainArgShape(main, at);
        validateMakeSafeArgs(main);
        boolean withTick = hub.impls.getDecorator(at.decorators, "with_tick") != null;
        if (!withTick) {
            String canonicalReturn = main.returnType.canonical();
            boolean validReturn = canonicalReturn.equals("imut_s32") || canonicalReturn.equals("imut_bool")
                    || canonicalReturn.equals("imut_void");
            if (!validReturn) {
                throw new CompilerException("type", at.file, at.line,
                        "'main' must return 'imut s32', 'imut bool', or 'void' -- got '" + canonicalReturn
                                + "' (or be decorated '@with_tick' and return 'owns some mut' a "
                                + "concrete struct type matching '@tick')");
            }
        }
        validateTickEventLoop(main, withTick);
    }

    /** Resolves main's argument shape: none (null), C-ABI "c_args", or the wrapped "safe_args". */
    public String validateAndResolveMainArgShape(FuncInfo main, Token at) {
        if (main.paramNames.isEmpty()) {
            return null;
        }
        String cArgsDesc = "'(imut s32, raw mut raw mut char)' (the C-ABI argc/argv shape)";
        String safeArgsDesc = "'(" + safeArgsType().canonical() + ")' (the safe, wrapped shape)";
        if (main.paramNames.size() == 2) {
            if (!main.paramTypes.get(0).canonical().equals("imut_s32")
                    || !main.paramTypes.get(1).canonical().equals("raw_mut_raw_mut_char")) {
                throw new CompilerException("type", at.file, at.line,
                        "'main' with two parameters must be exactly " + cArgsDesc + " -- got '"
                                + main.paramTypes.get(0).canonical() + "', '"
                                + main.paramTypes.get(1).canonical() + "'");
            }
            return "c_args";
        }
        if (main.paramNames.size() == 1) {
            if (!main.paramTypes.get(0).canonical().equals(safeArgsType().canonical())) {
                throw new CompilerException("type", at.file, at.line,
                        "'main' with one parameter must be exactly " + safeArgsDesc + " -- got '"
                                + main.paramTypes.get(0).canonical() + "'");
            }
            return "safe_args";
        }
        throw new CompilerException("type", at.file, at.line,
                "'main' must take no parameters, " + cArgsDesc + ", or " + safeArgsDesc + " -- got "
                        + main.paramNames.size() + " parameters");
    }

    /** Type of the safe main argument: owns some mut dynarray of imut dynarrays of imut char. */
    public TypeInfo safeArgsType() {
        TypeInfo charType = new TypeInfo(null, "imut", "char");
        TypeInfo innerDynArray = TypeInfo.dynArray("imut", charType);
        return TypeInfo.dynArray("mut", innerDynArray).withSome(true);
    }

    /** @make_safe_args must exist exactly once iff main uses safe_args, with signature (imut s32, raw mut raw mut char) returning safeArgsType(). */
    public void validateMakeSafeArgs(FuncInfo main) {
        List<FuncInfo> candidates = findFuncsWithDecorator("make_safe_args");
        if (candidates.size() > 1) {
            FuncInfo second = candidates.get(1);
            throw new CompilerException("type", second.funcToken.file, second.funcToken.line,
                    "'@make_safe_args' may only be used once in the final compilation unit");
        }
        boolean needed = "safe_args".equals(main.mainArgShape);
        if (needed && candidates.isEmpty()) {
            throw new CompilerException("type", main.funcToken.file, main.funcToken.line,
                    "'main' uses the safe, wrapped argument shape, which requires exactly one "
                            + "'@make_safe_args' function to exist somewhere in the compilation unit -- "
                            + "none was found");
        }
        if (candidates.isEmpty()) {
            return;
        }
        FuncInfo msa = candidates.get(0);
        Token at = msa.funcToken;
        boolean sigOk = msa.paramNames.size() == 2
                && msa.paramTypes.get(0).canonical().equals("imut_s32")
                && msa.paramTypes.get(1).canonical().equals("raw_mut_raw_mut_char")
                && msa.returnType.canonical().equals(safeArgsType().canonical());
        if (!sigOk) {
            throw new CompilerException("type", at.file, at.line,
                    "'@make_safe_args' must be exactly 'func(argc: imut s32, argv: raw mut raw mut char) "
                            + safeArgsType().canonical() + "'");
        }
    }

    // ---- pass 1: collect and validate top-level declarations ------------

    public void collectDeclarations(List<Token> rootLines) {
        List<FlatDecl> flatDecls = flattenRootLines(rootLines, "indeterminate");
        // Pass 1a: register structs, interfaces, enums, libraries, aliases and consts raw (see docs/TypeChecker.md, "collectDeclarations passes").
        for (FlatDecl fd : flatDecls) {
            if (globalLetAssignOf(fd.lineTok) != null) {
                continue;
            }
            Token t = requireTopLevelDecl(fd.lineTok);
            switch (t.text) {
                case "struct": registerStructLike(t); break;
                                case "enum": collectEnum(t); break;
                case "interface": registerInterface(t); break;
                case "library": registerLibraryName(t); break;
                case "type": registerAlias(t); break;
                case "const": registerConst(t); break;
                default: break; // func/impl handled below
            }
        }
        // Pass 1a.5: flatten struct members and interface methods (before impls and globals are processed).
        for (String name : new ArrayList<>(hub.model.structs.keySet())) {
            flattenStruct(name, new HashSet<>());
        }
        for (String name : new ArrayList<>(hub.model.interfaces.keySet())) {
            flattenInterface(name, new HashSet<>());
        }
        // Pass 1a.6: record each func/impl's root-list position (FuncInfo.declOrder).
        hub.model.pendingDeclOrder = new HashMap<>();
        for (int i = 0; i < flatDecls.size(); i++) {
            Token t = flatDecls.get(i).lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD
                    && (t.text.equals("func") || t.text.equals("impl") || t.text.equals("library"))) {
                hub.model.pendingDeclOrder.put(t, i);
                if (t.text.equals("impl") || t.text.equals("library")) {
                    for (Token method : t.childs) {
                        hub.model.pendingDeclOrder.put(method, i);
                    }
                }
            }
        }
        // Pass 1a.7: externs, before func/impl collection so a colliding function name is caught.
        for (FlatDecl fd : flatDecls) {
            Token t = fd.lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && t.text.equals("extern")) {
                hub.impls.collectExtern(t);
            }
        }
        // Pass 1a.8: globals, in file order.
        for (int i = 0; i < flatDecls.size(); i++) {
            Token assignNode = globalLetAssignOf(flatDecls.get(i).lineTok);
            if (assignNode != null) {
                collectGlobal(assignNode, i);
            }
        }
        // Pass 1b: func and impl (impl validates against what pass 1a collected)
        for (FlatDecl fd : flatDecls) {
            Token t = fd.lineTok.childs.get(0);
            switch (t.text) {
                case "func": hub.impls.collectFunc(t, fd.safety); break;
                case "impl": hub.impls.collectImpl(t, fd.safety); break;
                case "impl_default_lock_match": collectDefaultLockMatchPolicy(t); break;
                case "library": hub.impls.collectLibrary(t, fd.safety); break;
                default: break; // already handled above
            }
        }
        // Pass 1b.5: flatten library 'extends' chains (needs every library's function set).
        for (String name : new ArrayList<>(hub.model.libraries.keySet())) {
            hub.impls.flattenLibrary(name, new HashSet<>());
        }
        // Pass 1c: check every struct's "implements" contract.
        for (StructInfo info : hub.model.structs.values()) {
            checkImplementsContract(info);
        }
        // Pass 1c-bis: synthesize the Class enum and the "enum for X" membership enums (order matters).
        hub.accessAndAsync.generateClassHierarchyEnums();
        hub.accessAndAsync.generateEnumForEnums();
        // Pass 1c-ter: synthesize interface-method dispatchers (after enum-for and impl validation).
        hub.accessAndAsync.generateInterfaceDispatchers();
        // Pass 1d: export statements.
        for (FlatDecl fd : flatDecls) {
            Token t = fd.lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && t.text.equals("export")) {
                hub.impls.collectExport(t);
            }
        }
        // Pass 1e: root-level ASM declarations and invocations, in strict file order.
        for (FlatDecl fd : flatDecls) {
            Token t = fd.lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && t.text.equals("ASM")) {
                collectRootAsmDeclaration(t, fd.safety);
            } else if (t.type == TokenType.VARREF) {
                collectRootAsmInvocation(t, fd.safety);
            }
        }
        // Pass 1f: mangle overloaded top-level funcs (only a name with more than one overload).
        for (List<FuncInfo> overloads : hub.model.functions.values()) {
            if (overloads.size() <= 1) {
                continue;
            }
            for (FuncInfo fi : overloads) {
                fi.mangledName = fi.name + "__sig_" + hub.impls.signatureSuffix(fi.paramTypes);
            }
        }
    }

    /** "ASM name {...}"/"ASM name \"path\"" at the root -- see collectDeclarations' Pass 1e. */
    public void collectRootAsmDeclaration(Token t, String safety) {
        if (!"unsafe".equals(safety)) {
            throw new CompilerException("type", t.file, t.line,
                    "declaring 'ASM' requires 'unsafe' code");
        }
        String rawText = hub.statements.resolveAsmRawText(t);
        t.chk.resolvedAsmContent = rawText; // read by BytecodeEmitter directly when unnamed (self-emits here); harmlessly unused otherwise
        if (t.sub.isEmpty()) {
            return; // unnamed -- never registered under any name at all
        }
        String name = t.declName().text;
        if (hub.model.rootAsms.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'ASM " + name + "' is already declared");
        }
        AsmInfo info = new AsmInfo();
        info.name = name;
        info.rawText = rawText;
        info.declTok = t;
        hub.model.rootAsms.put(name, info);
    }

    /** A bare "asm_name;" line at the root -- see collectDeclarations' Pass 1e. */
    public void collectRootAsmInvocation(Token t, String safety) {
        if (!"unsafe".equals(safety)) {
            throw new CompilerException("type", t.file, t.line,
                    "invoking '" + t.text + "' requires 'unsafe' code");
        }
        AsmInfo info = hub.model.rootAsms.get(t.text);
        if (info == null) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + t.text + "' is not a declared 'ASM' name (or is used before its declaration)");
        }
        t.chk.resolvedAsmContent = info.rawText;
    }

    /** Returns the "=" assign node if `lineTok` is a root-level "let static NAME = ..." global, else null. */
    public Token globalLetAssignOf(Token lineTok) {
        Token t = lineTok.childs.get(0);
        if (t.type == TokenType.OPERATOR && t.text.equals("=")
                && t.left.type == TokenType.KEYWORD && t.left.text.equals("let")) {
            return t;
        }
        return null;
    }

    /**
     * "let static NAME = ..." at the root: the RHS follows the local-static rules but may name an earlier global.
     * See docs/TypeChecker.md, "Top-level declaration collection".
     */
    public void collectGlobal(Token assignNode, int declOrder) {
        Token letTok = assignNode.left;
        Token nameTok = letTok.childs.get(0);
        String name = nameTok.text;
        if (!letTok.isStatic) {
            throw new CompilerException("type", letTok.file, letTok.line,
                    "only 'let static' is allowed at the top level -- a plain 'let' has no meaning "
                            + "outside a function body");
        }
        hub.impls.validateDecorators(letTok.decorators, TypeCheckerModel.GLOBAL_DECORATORS, "a top-level 'let static'");
        if (hub.impls.getDecorator(letTok.decorators, "pub") != null) {
            hub.model.publicGlobals.add(name);
        }
        if (hub.model.globals.containsKey(name) || hub.model.structs.containsKey(name) || hub.model.enums.containsKey(name)
                || hub.model.interfaces.containsKey(name) || hub.model.aliasRawTargets.containsKey(name)
                || hub.model.constExprs.containsKey(name)) {
            throw new CompilerException("type", letTok.file, letTok.line,
                    "'" + name + "' is already declared");
        }
        hub.expressions.checkNoRuntimeConstructs(assignNode.right);
        FuncInfo globalCheckContext = new FuncInfo();
        globalCheckContext.declOrder = declOrder;
        TypeInfo rhsType = hub.controlFlow.resolveExprType(assignNode.right, new Scope(null), globalCheckContext);
        // `let static:<mut f64> g = mut 0.5` -- an explicit type bound on a global (checked and literal-adapted like a local's)
        TypeInfo globalBound = hub.expressions.resolveAndValidateTypeBound(letTok, rhsType, assignNode.right);
        if (globalBound != null) {
            rhsType = globalBound;
        }
        hub.expressions.requireStaticMutable(rhsType, assignNode.right);
        // Direct atomic declaration vs. merely reading an atomic value: see isDirectAtomicDeclaration.
        if (rhsType.isAtomic && !hub.impls.isDirectAtomicDeclaration(assignNode.right)) {
            rhsType = rhsType.withAtomic(false);
        }
        letTok.chk.resolvedType = rhsType.canonical();

        hub.model.globals.put(name, rhsType);
        hub.model.globalDeclOrder.put(name, declOrder);
        hub.model.globalOrder.add(name);
        hub.model.globalRhsByName.put(name, assignNode.right);
    }

    public void checkImplementsContract(StructInfo info) {
        if (info.implementsNames.isEmpty()) {
            return;
        }
        List<ImplInfo> impls = hub.model.implsByConcreteType.get(info.name);
        for (String interfaceName : info.implementsNames) {
            boolean satisfied = false;
            if (impls != null) {
                for (ImplInfo impl : impls) {
                    if (interfaceName.equals(impl.interfaceName)) {
                        satisfied = true;
                        break;
                    }
                }
            }
            if (!satisfied) {
                throw new CompilerException("type", info.declTok.file, info.declTok.line,
                        "'" + info.name + "' declares 'implements " + interfaceName + "' but no matching "
                                + "'impl " + interfaceName + " for " + info.name + "{...}' was found "
                                + "anywhere in this compilation unit");
            }
        }
    }

    public Token requireTopLevelDecl(Token lineTok) {
        if (lineTok.childs.size() != 1) {
            throw new CompilerException("type", lineTok.file, lineTok.line,
                    "only 'struct', 'enum', 'interface', 'impl', 'library', 'func', 'type', "
                            + "'extern', 'export', and 'ASM' declarations (or a bare 'asm_name;' "
                            + "invocation) are allowed at the top level");
        }
        Token t = lineTok.childs.get(0);
        // A bare VARREF line is allowed: it can only be an "asm_name;" invocation (validated in
        // collectAsmInvocations).
        if (t.type == TokenType.VARREF) {
            return t;
        }
        if (t.type != TokenType.KEYWORD
                || !(t.text.equals("struct") || t.text.equals("enum")
                        || t.text.equals("func") || t.text.equals("interface") || t.text.equals("impl")
                        || t.text.equals("impl_default_lock_match")
                        || t.text.equals("library")
                        || t.text.equals("type") || t.text.equals("extern") || t.text.equals("export")
                        || t.text.equals("ASM") || t.text.equals("const"))) {
            throw new CompilerException("type", t.file, t.line,
                    "only 'struct', 'enum', 'interface', 'impl', 'library', 'func', 'type', "
                            + "'const', 'extern', 'export', and 'ASM' declarations (or a bare 'asm_name;' "
                            + "invocation) are allowed at the top level, found '" + t.text + "'");
        }
        return t;
    }

    /**
     * "type NAME <target>": stores the raw target tokens. Collision with globals is checked from collectGlobal's
     * side.
     */
    public void registerAlias(Token t) {
        String name = t.declName().text;
        if (hub.model.aliasRawTargets.containsKey(name) || hub.model.structs.containsKey(name) || hub.model.enums.containsKey(name)
                || hub.model.interfaces.containsKey(name) || hub.model.libraries.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        hub.model.aliasRawTargets.put(name, t.sub.subList(1, t.sub.size()));
    }

    /**
     * "const NAME EXPR": registered raw and evaluated lazily on first reference (consts are forward-declarable,
     * see resolveConstValue). See docs/TypeChecker.md, "Top-level declaration collection".
     */
    public void registerConst(Token t) {
        String name = t.declName().text;
        if (hub.model.constExprs.containsKey(name) || hub.model.structs.containsKey(name) || hub.model.enums.containsKey(name)
                || hub.model.interfaces.containsKey(name) || hub.model.aliasRawTargets.containsKey(name)
                || hub.model.libraries.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        hub.model.constExprs.put(name, t.childs.get(0));
    }

    /**
     * Raw registration of a library name (shares the struct/enum/interface namespace); its functions are built
     * later in collectLibrary.
     */
    public void registerLibraryName(Token t) {
        String name = t.declName().text;
        if (hub.model.structs.containsKey(name) || hub.model.enums.containsKey(name) || hub.model.interfaces.containsKey(name)
                || hub.model.libraries.containsKey(name) || hub.model.aliasRawTargets.containsKey(name)
                || hub.model.constExprs.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        hub.impls.validateDecorators(t.decorators, TypeCheckerModel.LIBRARY_DECORATORS, "a 'library'");
        LibraryInfo info = new LibraryInfo();
        info.name = name;
        info.declTok = t;
        info.isPublic = hub.impls.getDecorator(t.decorators, "pub") != null;
        info.extendsNames = t.extendsNames != null ? t.extendsNames : new ArrayList<>();
        hub.model.libraries.put(name, info);
    }

    public void registerStructLike(Token t) {
        String name = t.declName().text;
        if (hub.model.structs.containsKey(name) || hub.model.enums.containsKey(name) || hub.model.interfaces.containsKey(name)
                || hub.model.libraries.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        hub.impls.validateDecorators(t.decorators, TypeCheckerModel.STRUCT_DECORATORS, "a struct");
        // @unpadded is recognised but rejected: every struct is compiler-padded (see emitStruct). Kept in
        // STRUCT_DECORATORS to give this specific error.
        if (hub.impls.getDecorator(t.decorators, "unpadded") != null) {
            throw new CompilerException("type", t.file, t.line,
                    "'@unpadded' is not supported yet -- every struct is compiler-padded to its own "
                            + "natural alignment");
        }
        StructInfo info = new StructInfo(name);
        info.isPublic = hub.impls.getDecorator(t.decorators, "pub") != null;
        info.implementsNames = t.implementsNames != null ? t.implementsNames : new ArrayList<>();
        info.rawMemberLines = t.childs;
        info.declTok = t;
        hub.model.structs.put(name, info);
        hub.model.structDeclOrder.add(name);
    }

    /**
     * Resolves `name`'s final member set (a struct has only its own
     * members -- struct inheritance does not exist) and validates its
     * `swap` mutex field and struct-level `@lock` clause.
     */
    public void flattenStruct(String name, Set<String> visiting) {
        StructInfo info = hub.model.structs.get(name);
        if (info == null || info.flattened) {
            return;
        }

        String swapFieldName = null;
        Token swapFieldDeclTok = null;
        for (Token memberLine : info.rawMemberLines) {
            boolean linePublic = !memberLine.childs.isEmpty() && memberLine.childs.get(0).isPubBlockMember;
            for (NameType nt : hub.signatures.parseNameTypeGroup(memberLine.childs)) {
                if (info.members.containsKey(nt.name)) {
                    throw new CompilerException("type", memberLine.file, memberLine.line,
                            "'" + name + "' already has a member named '" + nt.name + "'");
                }
                info.members.put(nt.name, nt.type);
                if (linePublic) {
                    info.publicMembers.add(nt.name);
                }
                info.memberDeclFile.put(nt.name, memberLine.file);
                if (nt.isSwap) {
                    if (swapFieldName != null) {
                        throw new CompilerException("type", memberLine.file, memberLine.line,
                                "'" + name + "' already has a 'swap'-tagged member named '" + swapFieldName
                                        + "' -- a struct can have at most one");
                    }
                    swapFieldName = nt.name;
                    swapFieldDeclTok = memberLine;
                }
            }
        }
        if (swapFieldName != null) {
            validateSwapMutexField(info, name, swapFieldName, swapFieldDeclTok);
            info.swapFieldName = swapFieldName;
        }

        Decorator lockDecorator = null;
        for (Decorator d : (info.declTok.decorators == null ? List.<Decorator>of() : info.declTok.decorators)) {
            if (d.name.equals("lock") && d.lockFieldName != null) {
                lockDecorator = d;
                break;
            }
        }
        if (lockDecorator != null) {
            // Validated at the end of flattenStruct, once member types are known.
            validateLockEnumClause(lockDecorator, info, name);
            // The first user-declared member must be the lock field (the hidden ___type is not in info.members).
            if (!info.members.isEmpty()) {
                String firstMember = info.members.keySet().iterator().next();
                if (!firstMember.equals(lockDecorator.lockFieldName)) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "'" + name + "' is decorated '@lock(match self." + lockDecorator.lockFieldName
                                    + " : ...)', so '" + lockDecorator.lockFieldName + "' must be its first member, "
                                    + "but its first member is '" + firstMember + "'");
                }
            }
            info.lockFieldName = lockDecorator.lockFieldName;
            info.lockVariants = lockDecorator.lockVariants;
            if (info.swapFieldName != null && !info.swapFieldName.equals(info.lockFieldName)) {
                // A swap member plus a struct-level @lock(...) must name the same field. See docs/TypeChecker.md,
                // "Struct flattening and @lock clauses".
                throw new CompilerException("type", info.declTok.file, info.declTok.line,
                        "'" + name + "'s '@lock(match self." + info.lockFieldName + " : ...)' decorator must "
                                + "name the same field as its 'swap'-tagged member ('" + info.swapFieldName
                                + "'), not a different one");
            }
        }

        info.flattened = true;
    }

    /**
     * The swap member of a "match @lock" target must be a two-variant enum named exactly OPEN and CLOSED (codegen
     * builds ATOMIC_SWAP calls against those names).
     */
    public void validateSwapMutexField(StructInfo structInfo, String structName, String fieldName, Token at) {
        TypeInfo fieldType = structInfo.members.get(fieldName);
        EnumInfo enumInfo = hub.model.enums.get(fieldType.baseType);
        if (enumInfo == null) {
            throw new CompilerException("type", at.file, at.line,
                    "'swap " + fieldName + "' must be an enum-typed atomic member, got '"
                            + fieldType.canonical() + "'");
        }
        if (enumInfo.variants.size() != 2 || !enumInfo.variants.contains("OPEN")
                || !enumInfo.variants.contains("CLOSED")) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + enumInfo.name + "' is used here as a swap-mutex state -- it must be a plain, "
                            + "exactly-two-variant enum named 'OPEN' and 'CLOSED' (found: "
                            + enumInfo.variants + ")");
        }
    }

    /**
     * Validates "@lock(match self.x : X)": the field exists, is enum-typed, and every variant belongs to that
     * enum. Shared by struct-level and method-level use.
     */
    public void validateLockEnumClause(Decorator d, StructInfo structInfo, String structName) {
        String fieldName = d.lockFieldName;
        TypeInfo fieldType = structInfo.members.get(fieldName);
        if (fieldType == null) {
            throw new CompilerException("type", d.file, d.line,
                    "'@lock(match self." + fieldName + " : ...)' -- '" + structName + "' has no member "
                            + "named '" + fieldName + "'");
        }
        EnumInfo lockEnumInfo = hub.model.enums.get(fieldType.baseType);
        if (lockEnumInfo == null) {
            throw new CompilerException("type", d.file, d.line,
                    "'@lock(match self." + fieldName + " : ...)' -- '" + fieldName + "' must be an "
                            + "enum-typed member, got '" + fieldType.canonical() + "'");
        }
        for (String variant : d.lockVariants) {
            if (!lockEnumInfo.variants.contains(variant)) {
                throw new CompilerException("type", d.file, d.line,
                        "'" + lockEnumInfo.name + "' has no variant named '" + variant + "'");
            }
        }
    }

    /**
     * Validates every "@lock(match ...)" decorator on one method (impl method or interface signature); struct
     * receivers only. See docs/TypeChecker.md, "Method-level @lock".
     */
    public void validateMethodLocks(List<Decorator> decorators, List<String> paramNames,
            List<TypeInfo> paramTypes, String concreteName) {
        if (decorators == null || !hub.model.structs.containsKey(concreteName)) {
            return;
        }
        flattenStruct(concreteName, new HashSet<>());
        StructInfo si = hub.model.structs.get(concreteName);
        for (Decorator d : decorators) {
            if (!d.name.equals("lock")) {
                continue;
            }
            if (d.lockFieldName != null) {
                validateLockEnumClause(d, si, concreteName);
            } else if (d.lockInParams != null) {
                validateLockInClause(d, si, concreteName, paramNames, paramTypes);
            }
            // else: no qualified @lock clause to validate.
        }
    }

    /**
     * Validates "@lock(match x in|within self.f)" against the typing rules of checkIn / checkWithin (chosen per
     * clause by d.lockInOperators).
     */
    public void validateLockInClause(Decorator d, StructInfo si, String concreteName,
            List<String> paramNames, List<TypeInfo> paramTypes) {
        for (int i = 0; i < d.lockInParams.size(); i++) {
            String paramName = d.lockInParams.get(i);
            String fieldName = d.lockInFields.get(i);
            String operatorText = d.lockInOperators.get(i);
            int paramIdx = paramNames.indexOf(paramName);
            if (paramIdx < 0) {
                throw new CompilerException("type", d.file, d.line,
                        "'@lock(match " + paramName + " " + operatorText + " ...)' -- no parameter named '"
                                + paramName + "'");
            }
            TypeInfo paramType = paramTypes.get(paramIdx);
            if (!"imut".equals(paramType.mutability)) {
                throw new CompilerException("type", d.file, d.line,
                        "'@lock(match " + paramName + " " + operatorText + " ...)' -- '" + paramName
                                + "' must be an 'imut' parameter, got '" + paramType.canonical() + "'");
            }
            TypeInfo fieldType = si.members.get(fieldName);
            if (fieldType == null) {
                throw new CompilerException("type", d.file, d.line,
                        "'@lock(match " + paramName + " " + operatorText + " self." + fieldName + ")' -- '"
                                + concreteName + "' has no member named '" + fieldName + "'");
            }
            boolean isWithin = operatorText.equals("within");
            boolean leftOk = isWithin ? hub.isRangeType(paramType)
                    : (hub.isPlainU64(paramType) || hub.isRangeType(paramType));
            if (!leftOk) {
                throw new CompilerException("type", d.file, d.line,
                        "'@lock(match " + paramName + " " + operatorText + " ...)' -- '" + operatorText
                                + "' requires a " + (isWithin ? "range" : "plain 'u64' or a range")
                                + " on the left, got '" + paramType.canonical() + "'");
            }
            boolean rightOk = isWithin ? hub.isRangeType(fieldType)
                    : (hub.isRangeType(fieldType) || hub.isDynArrayType(fieldType));
            if (!rightOk) {
                throw new CompilerException("type", d.file, d.line,
                        "'@lock(match ... " + operatorText + " self." + fieldName + ")' -- '" + operatorText
                                + "' requires a range" + (isWithin ? "" : " or a dynarray")
                                + " on the right, got '" + fieldType.canonical() + "'");
            }
            // "Some(i) in self.x" needs self.x to be a dynarray of pointer elements (as checkSomeIndexBoundsCondition
            // requires).
            if (d.lockInSomeWrapped != null && Boolean.TRUE.equals(d.lockInSomeWrapped.get(i))) {
                if (!hub.isDynArrayType(fieldType) || fieldType.dynArrayElementType == null
                        || fieldType.dynArrayElementType.storage == null) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@lock(match Some(" + paramName + ") " + operatorText + " self." + fieldName
                                    + ")' -- 'Some(...)' requires 'self." + fieldName + "' to be a dynarray "
                                    + "of pointer-typed elements, got '" + fieldType.canonical() + "'");
                }
            }
        }
    }

    public void collectEnum(Token t) {
        String name = t.declName().text;
        if (hub.model.structs.containsKey(name) || hub.model.enums.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        hub.impls.validateDecorators(t.decorators, TypeCheckerModel.ENUM_DECORATORS, "an enum");
        EnumInfo info = new EnumInfo(name);
        info.isNonExhaustive = hub.impls.getDecorator(t.decorators, "non_exhaustive") != null;
        int explicitValueCount = 0;
        int explicitRangeCount = 0;
        for (Token variant : t.childs) {
            if (info.variants.contains(variant.text)) {
                throw new CompilerException("type", variant.file, variant.line,
                        "enum '" + name + "' already has a variant named '" + variant.text + "'");
            }
            info.variants.add(variant.text);
            if (variant.explicitEnumValue != null) {
                explicitValueCount++;
            }
            if (variant.explicitRangeStart != null) {
                explicitRangeCount++;
            }
        }
        if (explicitValueCount > 0 && explicitRangeCount > 0) {
            // An enum is plain, u64-valued, or range-valued -- never a
            // mix of the last two within one declaration.
            throw new CompilerException("type", t.file, t.line,
                    "enum '" + name + "' mixes u64-valued and range-valued variants -- an enum is either "
                            + "u64-valued or range-valued, never both");
        }
        if (explicitValueCount != 0 && explicitValueCount != t.childs.size()) {
            // "no partial mixing," confirmed directly.
            throw new CompilerException("type", t.file, t.line,
                    "enum '" + name + "' mixes explicit and default variant values -- either every variant "
                            + "has one, or none do");
        }
        if (explicitRangeCount != 0 && explicitRangeCount != t.childs.size()) {
            // Same "no partial mixing" rule, for the range-valued form.
            throw new CompilerException("type", t.file, t.line,
                    "enum '" + name + "' mixes explicit and default variant ranges -- either every variant "
                            + "has one, or none do");
        }
        if (explicitValueCount > 0) {
            info.explicitValues = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (Token variant : t.childs) {
                if (!seen.add(variant.explicitEnumValue)) {
                    throw new CompilerException("type", variant.file, variant.line,
                            "enum '" + name + "' has two variants both explicitly valued '"
                                    + variant.explicitEnumValue + "'");
                }
                info.explicitValues.add(variant.explicitEnumValue);
            }
        }
        if (explicitRangeCount > 0) {
            info.explicitRangeStarts = new ArrayList<>();
            info.explicitRangeEnds = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Token variant : t.childs) {
                if (variant.explicitRangeStart > variant.explicitRangeEnd) {
                    throw new CompilerException("type", variant.file, variant.line,
                            "enum '" + name + "' variant '" + variant.text + "' has a range whose start ("
                                    + variant.explicitRangeStart + ") is after its end ("
                                    + variant.explicitRangeEnd + ")");
                }
                String key = variant.explicitRangeStart + ".." + variant.explicitRangeEnd;
                if (!seen.add(key)) {
                    throw new CompilerException("type", variant.file, variant.line,
                            "enum '" + name + "' has two variants both explicitly valued the identical "
                                    + "range '" + key + "'");
                }
                info.explicitRangeStarts.add(variant.explicitRangeStart);
                info.explicitRangeEnds.add(variant.explicitRangeEnd);
            }
        }
        hub.model.enums.put(name, info);
    }

    /**
     * "interface Name{...}": signatures stay raw (self is not concrete yet) and are only checked structurally;
     * resolved per impl in collectImpl.
     */
    public void registerInterface(Token t) {
        String name = t.declName().text;
        if (hub.model.structs.containsKey(name) || hub.model.enums.containsKey(name) || hub.model.interfaces.containsKey(name)
                || hub.model.libraries.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        hub.impls.validateDecorators(t.decorators, TypeCheckerModel.INTERFACE_DECORATORS, "an interface");
        InterfaceInfo info = new InterfaceInfo();
        info.name = name;
        info.declTok = t;
        info.isPublic = hub.impls.getDecorator(t.decorators, "pub") != null;
        info.extendsNames = t.extendsNames != null ? t.extendsNames : new ArrayList<>();
        info.ownMethods = new LinkedHashMap<>();
        for (Token methodTok : t.childs) {
            boolean isDefault = methodTok.text.equals("func");
            validateInterfaceMethodDecorators(methodTok, isDefault, name);
            if (methodTok.isStaticMethod) {
                // A static signature never gets a "self": reject any use outright. See docs/TypeChecker.md,
                // "Interfaces".
                forbidSelfReference(methodTok, "a static method");
            } else if (isDefault) {
                forbidSelfReference(methodTok, "a '@default' method");
            }
            String methodName = methodTok.declName().text;
            if (info.ownMethods.containsKey(methodName)) {
                throw new CompilerException("type", methodTok.file, methodTok.line,
                        "interface '" + name + "' already declares a method named '" + methodName + "'");
            }
            RawMethodSig sig = new RawMethodSig();
            sig.nameTok = methodTok.declName();
            sig.paramsTok = methodTok.paramsTok();
            sig.returnTypeTokens = methodTok.returnTypeTokens();
            sig.isDefault = isDefault;
            sig.decorators = methodTok.decorators;
            sig.isStatic = methodTok.isStaticMethod;
            sig.defaultBodyTok = isDefault ? methodTok : null;
            if (!methodTok.isStaticMethod) {
                validateSelfIsPointer(sig, name);
            }
            info.ownMethods.put(methodName, sig);
        }
        hub.model.interfaces.put(name, info);
    }

    /**
     * Body-bearing interface methods require @default, signature-only ones forbid it; @overrides/@realizes belong
     * to impl methods only.
     */
    public void validateInterfaceMethodDecorators(Token methodTok, boolean isDefault, String interfaceName) {
        if (isDefault) {
            buildFuncInfoDecoratorsOnly(methodTok); // reuses the generic func-decorator arg-shape validation
            if (!hub.impls.hasDecorator(methodTok.decorators, "default")) {
                throw new CompilerException("type", methodTok.file, methodTok.line,
                        "a method with a body inside interface '" + interfaceName
                                + "' must be labelled '@default'");
            }
        } else if (hub.impls.hasDecorator(methodTok.decorators, "default")) {
            throw new CompilerException("type", methodTok.file, methodTok.line,
                    "'@default' requires a method body -- '" + interfaceName + "' declares this one as a "
                            + "signature only");
        }
        for (String forbidden : new String[]{"overrides", "realizes"}) {
            Decorator bad = hub.impls.getDecorator(methodTok.decorators, forbidden);
            if (bad != null) {
                throw new CompilerException("type", bad.file, bad.line,
                        "'@" + forbidden + "' decorates an impl's method, not an interface's own "
                                + "declaration");
            }
        }
    }

    /** Validates just the decorator arg-shapes on a func-shaped token, without building a real FuncInfo (used for interface default methods, which aren't registered as callable functions). */
    public void buildFuncInfoDecoratorsOnly(Token t) {
        hub.impls.validateDecorators(t.decorators, TypeCheckerModel.FUNC_ALL_DECORATORS, "a function");
    }

    /**
     * Rejects 'self' anywhere in a signature or body: used for @default methods and static methods (`context`
     * names which, for the message).
     */
    public void forbidSelfReference(Token methodTok, String context) {
        for (Token s : methodTok.sub) {
            forbidSelfReferenceIn(s, context);
        }
        if (methodTok.childs != null) {
            for (Token c : methodTok.childs) {
                forbidSelfReferenceIn(c, context);
            }
        }
    }

    public void forbidSelfReferenceIn(Token root, String context) {
        Deque<Token> work = new java.util.LinkedList<>();   // may hold null children
        work.push(root);
        while (!work.isEmpty()) {
            Token node = work.pop();
            if (node == null) {
                continue;
            }
            if (node.type == TokenType.VARREF && node.text.equals("self")) {
                throw new CompilerException("type", node.file, node.line,
                        context + " cannot reference 'self' anywhere in its signature or body");
            }
            // pushed in reverse so the pop order is left, right, next, childs, sub
            if (node.sub != null) {
                for (int i = node.sub.size() - 1; i >= 0; i--) {
                    work.push(node.sub.get(i));
                }
            }
            if (node.childs != null) {
                for (int i = node.childs.size() - 1; i >= 0; i--) {
                    work.push(node.childs.get(i));
                }
            }
            work.push(node.next);
            work.push(node.right);
            work.push(node.left);
        }
    }

    /** Resolves an interface's flattened method set (any number of extends; name collisions are errors). */
    public void flattenInterface(String rootName, Set<String> visiting) {
        // frames: {interface name, iterator over its extended interfaces (null before first entry), parent just flattened and not yet merged}
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{rootName, null, null});
        while (!stack.isEmpty()) {
            Object[] f = stack.peek();
            String name = (String) f[0];
            InterfaceInfo info = hub.model.interfaces.get(name);
            if (f[1] == null) {
                if (info == null || info.flattened) {
                    stack.pop();
                    continue;
                }
                if (visiting.contains(name)) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "circular inheritance involving interface '" + name + "'");
                }
                visiting.add(name);
                f[1] = info.extendsNames.iterator();
            }
            if (f[2] != null) {
                InterfaceInfo parentInfo = hub.model.interfaces.get((String) f[2]);
                f[2] = null;
                for (Map.Entry<String, RawMethodSig> e : parentInfo.methods.entrySet()) {
                    if (info.methods.containsKey(e.getKey())) {
                        throw new CompilerException("type", info.declTok.file, info.declTok.line,
                                "interface '" + name + "' inherits a method named '" + e.getKey()
                                        + "' from more than one extended interface");
                    }
                    info.methods.put(e.getKey(), e.getValue());
                }
            }
            @SuppressWarnings("unchecked")
            Iterator<String> parents = (Iterator<String>) f[1];
            if (parents.hasNext()) {
                String parentName = parents.next();
                if (hub.model.interfaces.get(parentName) == null) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "'" + parentName + "' is not a known interface");
                }
                f[2] = parentName;
                stack.push(new Object[]{parentName, null, null});
                continue;
            }
            for (Map.Entry<String, RawMethodSig> e : info.ownMethods.entrySet()) {
                if (info.methods.containsKey(e.getKey())) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "interface '" + name + "' already has a method named '" + e.getKey()
                                    + "' via an extended interface");
                }
                info.methods.put(e.getKey(), e.getValue());
            }
            info.flattened = true;
            visiting.remove(name);
            stack.pop();
        }
    }

    public void validateSelfIsPointer(RawMethodSig sig, String interfaceName) {
        for (List<Token> group : hub.signatures.splitIntoRawGroups(sig.paramsTok.childs)) {
            if (group.size() < 3 || group.get(1).type != TokenType.OPERATOR || !group.get(1).text.equals(":")) {
                continue; // malformed; the later real parse (at impl-matching time) reports it properly
            }
            checkSelfStorageInTypeTokens(group.subList(2, group.size()), interfaceName);
        }
        checkSelfStorageInTypeTokens(sig.returnTypeTokens, interfaceName);
    }

    public void checkSelfStorageInTypeTokens(List<Token> typeTokens, String interfaceName) {
        boolean hasStorage = false;
        Token selfTok = null;
        for (Token t : typeTokens) {
            if (t.type == TokenType.VARREF && t.text.equals("self")) {
                selfTok = t;
            }
            if (t.type == TokenType.MODIFIER && !t.text.equals("mut") && !t.text.equals("imut")) {
                hasStorage = true;
            }
        }
        if (selfTok != null && !hasStorage) {
            throw new CompilerException("type", selfTok.file, selfTok.line,
                    "'self' must be a pointer in interface '" + interfaceName + "' (owns/ref/raw/auto/static)");
        }
    }

    /**
     * "impl default match @lock S{ CLOSED:(tries:imut u64,...)=>{...} }": registers a retry policy, validated
     * eagerly at its declaration (only the parameter shape; the body is checked at each use site). See
     * docs/TypeChecker.md, "Impl collection".
     */
    public void collectDefaultLockMatchPolicy(Token t) {
        String structName = t.declName().text;
        StructInfo structInfo = hub.model.structs.get(structName);
        if (structInfo == null) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + structName + "' is not a known struct");
        }
        if (structInfo.swapFieldName == null) {
            throw new CompilerException("type", t.file, t.line,
                    "'impl default match @lock " + structName + "' requires '" + structName
                            + "' to declare a 'swap'-tagged atomic mutex-state field");
        }
        if (structInfo.defaultLockMatchPolicy != null) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + structName + "' already has a registered 'default match @lock' policy -- at most one "
                            + "is allowed per struct");
        }
        Token paramsTok = t.paramsTok();
        List<NameType> params = hub.signatures.parseParamList(paramsTok.childs);
        if (params.isEmpty()) {
            throw new CompilerException("type", t.file, t.line,
                    "'impl default match @lock' requires at least one parameter -- its own first parameter is "
                            + "always the automatic, per-iteration 'tries' counter, never supplied by a "
                            + "'CLOSED:default(...)' call site");
        }
        TypeInfo triesType = params.get(0).type;
        if (triesType.storage != null || !"imut".equals(triesType.mutability) || !"u64".equals(triesType.baseType)) {
            throw new CompilerException("type", t.file, t.line,
                    "'impl default match @lock's own first parameter ('" + params.get(0).name + "') must be "
                            + "exactly 'imut u64' -- the automatic, per-iteration 'tries' counter -- got '"
                            + triesType.canonical() + "'");
        }
        structInfo.defaultLockMatchPolicy = new DefaultLockMatchPolicy(structName, paramsTok, t.childs, t);
    }
}

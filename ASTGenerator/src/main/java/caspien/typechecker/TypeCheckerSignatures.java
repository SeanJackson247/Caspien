package caspien.typechecker;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;

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

/**
 * Stage 6: type and logic checking over the import-resolved, tree-built program.
 * Type equality in comparisons is base-type only; see docs/TypeChecker.md, "Design decisions".
 */

/** TypeCheckerSignatures: function signatures, type annotations, whole-program checks (part 4 of 11; see README.md in this folder). */
public final class TypeCheckerSignatures {
    private final TypeChecker hub;

    TypeCheckerSignatures(TypeChecker hub) {
        this.hub = hub;
    }

    public void buildFuncInfo(Token t, FuncInfo info) {
        hub.impls.validateDecorators(t.decorators, TypeCheckerModel.FUNC_ALL_DECORATORS, "a function");
        Token nameTok = t.declName();
        Token paramsTok = t.paramsTok();
        List<Token> returnTypeTokens = t.returnTypeTokens();

        info.name = nameTok.text;
        info.mangledName = info.name; // overridden for impl methods, see collectImpl
        info.callConvention = hub.impls.resolveCallConvention(t.decorators);
        info.isAsync = hub.impls.getDecorator(t.decorators, "async") != null;
        info.isThrows = hub.impls.getDecorator(t.decorators, "throws") != null;
        info.isPublic = hub.impls.getDecorator(t.decorators, "pub") != null;
        for (NameType nt : parseParamList(paramsTok.childs)) {
            info.paramNames.add(nt.name);
            info.paramTypes.add(nt.type);
        }
        if (hub.impls.getDecorator(t.decorators, "drop") != null) {
            hub.impls.requireDropHookShape(t, info, returnTypeTokens);
        }
        if (info.isAsync) {
            requireAsyncParamShape(t, info);
        }
        info.returnType = returnTypeTokens.isEmpty()
                ? new TypeInfo(null, "imut", "void")
                : resolveTypeAnnotation(returnTypeTokens, t, true);

        // Enum/array returns need a storage modifier (struct returns are legal: RVO, see isStructByValueReturn).
        if ((hub.model.enums.containsKey(info.returnType.baseType) || hub.isArrayType(info.returnType.baseType))
                && info.returnType.storage == null) {
            throw new CompilerException("type", t.file, t.line,
                    "function '" + info.name + "' returns '" + info.returnType.baseType
                            + "' without a storage modifier -- an enum/array cannot be "
                            + "returned by value, only by pointer (owns/ref/raw/auto/static)");
        }

        // Array parameters also need a storage modifier.
        for (int i = 0; i < info.paramTypes.size(); i++) {
            TypeInfo paramType = info.paramTypes.get(i);
            if (hub.isArrayType(paramType.baseType) && paramType.storage == null) {
                throw new CompilerException("type", t.file, t.line,
                        "parameter '" + info.paramNames.get(i) + "' of function '" + info.name
                                + "' is an array without a storage modifier -- arrays can only be "
                                + "passed as parameters by pointer (owns/ref/raw/auto/static)");
            }
            // A by-value struct parameter is illegal at any size. See docs/TypeChecker.md, "Function signature rules".
            StructInfo paramStructInfo = hub.model.structs.get(paramType.baseType);
            if (paramStructInfo != null && paramType.storage == null) {
                if (t.fromGenericInstance) {
                    // Monomorphized generic: the method is only unavailable for this T, an error only if called.
                    throw new DeferredSignatureException(t.file, t.line,
                            "parameter '" + info.paramNames.get(i) + "' of '" + info.name
                                    + "' is a plain (by-value) '" + paramType.baseType + "' (the generic "
                                    + "type argument is a struct) -- passing a struct on the stack, not by "
                                    + "pointer, is illegal; use a pointer-taking variant of this method "
                                    + "(auto/ref/raw parameter) if the library offers one");
                }
                throw new CompilerException("type", t.file, t.line,
                        "parameter '" + info.paramNames.get(i) + "' of function '" + info.name
                                + "' is a plain (by-value) '" + paramType.baseType + "' -- passing a "
                                + "struct on the stack, not by pointer, is illegal; pass a pointer "
                                + "instead (owns/ref/raw/auto/static)");
            }
        }
        validateGhostTableDecoratorSignatures(t, info);
    }

    /** Checks the fixed signature of each ghost-table / async / sleep hook decorator and registers it (behavior is never validated). See docs/TypeChecker.md, "Decorator signature checks". */
    public void validateGhostTableDecoratorSignatures(Token t, FuncInfo info) {
        if (hub.impls.getDecorator(t.decorators, "gt_init") != null) {
            requireGhostTableSignature(t, info, "gt_init", 0, "void");
            registerGhostTableFunction(t, info, "gt_init");
        }
        if (hub.impls.getDecorator(t.decorators, "gt_alive_check") != null) {
            requireGhostTableSignature(t, info, "gt_alive_check", 1, "bool");
            registerGhostTableFunction(t, info, "gt_alive_check");
        }
        if (hub.impls.getDecorator(t.decorators, "gt_destruct") != null) {
            requireGhostTableSignature(t, info, "gt_destruct", 1, "void");
            registerGhostTableFunction(t, info, "gt_destruct");
        }
        if (hub.impls.getDecorator(t.decorators, "gt_register") != null) {
            requireGhostTableSignature(t, info, "gt_register", 1, "bool");
            registerGhostTableFunction(t, info, "gt_register");
        }
        if (hub.impls.getDecorator(t.decorators, "gt_moved") != null) {
            requireGhostTableSignature(t, info, "gt_moved", 1, "void");
            registerGhostTableFunction(t, info, "gt_moved");
        }
        // @gt_ref_id / @gt_ref_resolve: nullable-ref hooks. @par_call / @await_call: async glue; all share the one-function-per-decorator map.
        if (hub.impls.getDecorator(t.decorators, "gt_ref_id") != null) {
            requireGtRefSignature(t, info, "gt_ref_id", true);
            registerGhostTableFunction(t, info, "gt_ref_id");
        }
        if (hub.impls.getDecorator(t.decorators, "gt_ref_resolve") != null) {
            requireGtRefSignature(t, info, "gt_ref_resolve", false);
            registerGhostTableFunction(t, info, "gt_ref_resolve");
        }
        if (hub.impls.getDecorator(t.decorators, "par_call") != null) {
            requireAsyncGlueSignature(t, info, "par_call");
            registerGhostTableFunction(t, info, "par_call");
        }
        if (hub.impls.getDecorator(t.decorators, "await_call") != null) {
            requireAsyncGlueSignature(t, info, "await_call");
            registerGhostTableFunction(t, info, "await_call");
        }
        // @sleep: call sites resolve it by mangled name (checkSleepCall), so no name forcing is needed.
        if (hub.impls.getDecorator(t.decorators, "sleep") != null) {
            requireSleepSignature(t, info);
            registerGhostTableFunction(t, info, "sleep");
        }
    }

    /** @sleep takes one plain u64 and returns a plain u64, like C sleep(unsigned int). */
    public void requireSleepSignature(Token t, FuncInfo info) {
        if (info.paramTypes.size() != 1) {
            throw new CompilerException("type", t.file, t.line,
                    "'@sleep' requires exactly 1 parameter (matching the real C 'sleep(unsigned int)'), got "
                            + info.paramTypes.size());
        }
        TypeInfo paramType = info.paramTypes.get(0);
        if (paramType.storage != null || !paramType.baseType.equals("u64")) {
            throw new CompilerException("type", t.file, t.line,
                    "'@sleep's parameter must be a plain 'u64' (matching the real C 'sleep(unsigned int)'), "
                            + "got '" + paramType.canonical() + "'");
        }
        if (info.returnType.storage != null || !info.returnType.baseType.equals("u64")) {
            throw new CompilerException("type", t.file, t.line,
                    "'@sleep' requires a plain 'u64' return type (matching the real C 'sleep(unsigned int)'), "
                            + "got '" + info.returnType.canonical() + "'");
        }
    }

    /** Fixed shape of @par_call / @await_call. See docs/TypeChecker.md, "Decorator signature checks". */
    public void requireAsyncGlueSignature(Token t, FuncInfo info, String decoratorName) {
        boolean isPar = decoratorName.equals("par_call");
        if (info.paramTypes.size() != (isPar ? 3 : 2)) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "' requires exactly " + (isPar ? "3 parameters (a trampoline function pointer, "
                            + "a handle pointer and a 'raw mut u64' that receives the thread id, or null)"
                            : "2 parameters (a trampoline function pointer and a handle pointer)")
                            + ", got " + info.paramTypes.size());
        }
        if (isPar) {
            TypeInfo tidParam = info.paramTypes.get(2);
            if (!"raw".equals(tidParam.storage) || !tidParam.baseType.equals("u64")) {
                throw new CompilerException("type", t.file, t.line,
                        "'@par_call's third parameter (where the started thread's id is stored, or null) must be "
                                + "'raw mut u64', got '" + tidParam.canonical() + "'");
            }
        }
        TypeInfo fnParam = info.paramTypes.get(0);
        if (!"raw".equals(fnParam.storage) || !fnParam.baseType.equals("u8")) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "'s first parameter (the trampoline function pointer) must be "
                            + "'raw u8', got '" + fnParam.canonical() + "'");
        }
        TypeInfo handleParam = info.paramTypes.get(1);
        if (!"raw".equals(handleParam.storage) || !handleParam.baseType.equals("u8")) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "'s second parameter (the async handle pointer) must be "
                            + "'raw u8', got '" + handleParam.canonical() + "'");
        }
        if (!info.returnType.baseType.equals("bool")) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "' requires a return type of 'bool' (true = the thread was started, "
                            + "false = it could not be), got '" + info.returnType.canonical() + "'");
        }
    }

    public void requireAsyncParamShape(Token t, FuncInfo info) {
        if (info.paramTypes.size() > 1) {
            throw new CompilerException("type", t.file, t.line,
                    "'@async' function '" + info.name + "' takes " + info.paramTypes.size()
                            + " parameters -- an '@async' function may take at most one");
        }
        if (info.paramTypes.isEmpty()) {
            return;
        }
        TypeInfo paramType = info.paramTypes.get(0);
        boolean fitsInOneRegister = paramType.storage != null
                || TypeCheckerModel.ASYNC_PARAM_ALLOWED_BASE_TYPES.contains(paramType.baseType);
        if (!fitsInOneRegister) {
            throw new CompilerException("type", t.file, t.line,
                    "'@async' function '" + info.name + "'s parameter must fit in a single register (<=8 "
                            + "bytes) -- 'u8/16/32/64', 's8/16/32/64', 'bool', 'char', or a pointer/'raw' "
                            + "type -- got '" + paramType.canonical() + "'");
        }
    }

    /**
     * Forces each gt_* decorated function's mangledName to the literal decorator name, because X86Backend calls those
     * symbols by literal name. Runs after all functions are collected so the collision check is complete.
     * See docs/TypeChecker.md, "Decorator signature checks".
     */
    public void forceGhostTableFunctionNames() {
        for (String decoratorName : Arrays.asList("gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve")) {
            FuncInfo decorated = hub.model.ghostTableFunctions.get(decoratorName);
            if (decorated == null) {
                continue;
            }
            if (!decorated.name.equals(decoratorName)) {
                List<FuncInfo> collision = hub.model.functions.get(decoratorName);
                if (collision != null && !collision.isEmpty()) {
                    throw new CompilerException("type", decorated.funcToken.file, decorated.funcToken.line,
                            "'" + decorated.name + "' is decorated '@" + decoratorName + "' but a separate "
                                    + "function literally named '" + decoratorName + "' also exists in this "
                                    + "compilation unit -- the decorated function must be emitted under the "
                                    + "literal name '" + decoratorName + "', so the two can't coexist; rename "
                                    + "one of them");
                }
            }
            decorated.mangledName = decoratorName;
        }
    }

    /** At most one function per ghost-table decorator in the whole compilation unit (imports included). */
    public void registerGhostTableFunction(Token t, FuncInfo info, String decoratorName) {
        FuncInfo existing = hub.model.ghostTableFunctions.get(decoratorName);
        if (existing != null && existing != info) {
            throw new CompilerException("type", t.file, t.line,
                    "only one function may be decorated '@" + decoratorName + "' in the whole compilation "
                            + "unit -- '" + existing.name + "' already carries it");
        }
        hub.model.ghostTableFunctions.put(decoratorName, info);
    }

    /** Parameter count is 0 (gt_init) or 1 (a `raw u8`, either mutability). */
    public void requireGhostTableSignature(Token t, FuncInfo info, String decoratorName, int expectedParamCount,
            String expectedReturnBaseType) {
        if (info.paramTypes.size() != expectedParamCount) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "' requires exactly " + expectedParamCount + " parameter(s), got "
                            + info.paramTypes.size());
        }
        if (expectedParamCount == 1) {
            TypeInfo paramType = info.paramTypes.get(0);
            if (!"raw".equals(paramType.storage) || !paramType.baseType.equals("u8")) {
                throw new CompilerException("type", t.file, t.line,
                        "'@" + decoratorName + "' requires its parameter to be 'raw u8', got '"
                                + paramType.canonical() + "'");
            }
        }
        if (!info.returnType.baseType.equals(expectedReturnBaseType)) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "' requires a return type of '" + expectedReturnBaseType + "', got '"
                            + info.returnType.canonical() + "'");
        }
    }

    /** `@gt_ref_id`: `func(ptr: raw u8) u64`; `@gt_ref_resolve`: `func(id: u64) raw u8`. */
    public void requireGtRefSignature(Token t, FuncInfo info, String decoratorName, boolean idOfPointer) {
        boolean ok = info.paramTypes.size() == 1;
        if (ok) {
            TypeInfo p = info.paramTypes.get(0);
            TypeInfo r = info.returnType;
            if (idOfPointer) {
                ok = "raw".equals(p.storage) && p.baseType.equals("u8") && r.storage == null && r.baseType.equals("u64");
            } else {
                ok = p.storage == null && p.baseType.equals("u64") && "raw".equals(r.storage) && r.baseType.equals("u8");
            }
        }
        if (!ok) {
            throw new CompilerException("type", t.file, t.line, idOfPointer
                    ? "'@gt_ref_id' must have the shape 'func f(ptr: raw u8) u64'"
                    : "'@gt_ref_resolve' must have the shape 'func f(id: u64) raw u8'");
        }
    }

    public boolean sameSignature(FuncInfo a, FuncInfo b) {
        if (a.paramTypes.size() != b.paramTypes.size()) {
            return false;
        }
        for (int i = 0; i < a.paramTypes.size(); i++) {
            TypeInfo pa = a.paramTypes.get(i);
            TypeInfo pb = b.paramTypes.get(i);
            if (!pa.baseType.equals(pb.baseType)) {
                return false;
            }
            // Differently aliased params are nominally distinct overloads.
            if (!java.util.Objects.equals(pa.aliasName, pb.aliasName)) {
                return false;
            }
        }
        return true;
    }

    public FuncInfo functionInfoFor(Token funcTok) {
        String name = funcTok.declName().text;
        for (FuncInfo info : hub.model.functions.get(name)) {
            if (info.funcToken == funcTok) {
                return info;
            }
        }
        throw new CompilerException("type", funcTok.file, funcTok.line,
                "internal error: no collected FuncInfo for '" + name + "'");
    }

    /** Splits a flat token list on ',' / stray TERMINATORs into "name : type" groups (param lists may span lines). */
    public List<NameType> parseParamList(List<Token> flat) {
        return parseNameTypeGroupsFrom(flat, false);
    }

    /** Struct members only: the one place `swap` is recognized. */
    public List<NameType> parseNameTypeGroup(List<Token> flat) {
        return parseNameTypeGroupsFrom(flat, true);
    }

    public List<NameType> parseNameTypeGroupsFrom(List<Token> flat, boolean allowSwap) {
        List<NameType> result = new ArrayList<>();
        for (List<Token> group : splitIntoRawGroups(flat)) {
            result.add(parseOneNameType(group, allowSwap));
        }
        return result;
    }

    /** Splits into raw "name : type..." groups without resolving types (interface signature checks need the raw tokens, since `self` is not a type yet). */
    public List<List<Token>> splitIntoRawGroups(List<Token> flat) {
        List<List<Token>> result = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        for (Token t : flat) {
            boolean isSeparator = (t.type == TokenType.OPERATOR && t.text.equals(","))
                    || t.type == TokenType.TERMINATOR;
            if (isSeparator) {
                if (!current.isEmpty()) {
                    result.add(current);
                    current = new ArrayList<>();
                }
                continue;
            }
            current.add(t);
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    public NameType parseOneNameType(List<Token> group, boolean allowSwap) {
        boolean isSwap = false;
        Token swapTok = null;
        if (allowSwap && !group.isEmpty() && group.get(0).type == TokenType.OPERATOR
                && group.get(0).text.equals("swap")) {
            // `swap` always lexes as an OPERATOR, so it cannot collide with a member name.
            isSwap = true;
            swapTok = group.get(0);
            group = group.subList(1, group.size());
        }
        if (group.size() < 3 || group.get(0).type != TokenType.VARREF
                || group.get(1).type != TokenType.OPERATOR || !group.get(1).text.equals(":")) {
            Token at = group.isEmpty() ? swapTok : group.get(0);
            throw new CompilerException("type", at.file, at.line,
                    "expected 'name : type', found '" + describeGroup(group) + "'");
        }
        String name = group.get(0).text;
        List<Token> typeTokens = group.subList(2, group.size());
        TypeInfo type = isSwap ? resolveSwapMemberType(typeTokens, group.get(0))
                : resolveTypeAnnotation(typeTokens, group.get(0));
        return new NameType(name, type, isSwap);
    }

    /** A `swap` member's type must start with `atomic` (accepted only here), be concretely mut and carry no storage. See docs/TypeChecker.md, "Name:type groups and swap members". */
    public TypeInfo resolveSwapMemberType(List<Token> typeTokens, Token at) {
        if (typeTokens.isEmpty() || typeTokens.get(0).type != TokenType.KEYWORD
                || !typeTokens.get(0).text.equals("atomic")) {
            throw new CompilerException("type", at.file, at.line,
                    "a 'swap'-tagged struct member's type must begin with 'atomic', e.g. "
                            + "'swap flag: atomic mut MutexState'");
        }
        List<Token> rest = typeTokens.subList(1, typeTokens.size());
        TypeInfo resolved = resolveTypeAnnotation(rest, at);
        if (!"mut".equals(resolved.mutability)) {
            throw new CompilerException("type", at.file, at.line,
                    "a 'swap'-tagged member's atomic type must be concretely 'mut' -- found '"
                            + resolved.mutability + "'");
        }
        if (resolved.storage != null) {
            throw new CompilerException("type", at.file, at.line,
                    "a 'swap'-tagged member's atomic type cannot carry a storage modifier");
        }
        return resolved.withAtomic(true);
    }

    public String describeGroup(List<Token> group) {
        StringBuilder sb = new StringBuilder();
        for (Token t : group) {
            sb.append(t.text).append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * Resolves a type annotation, splicing in `type` aliases first (textual substitution, cycle-checked); the outermost
     * alias name is kept as TypeInfo.aliasName (nominal tag only). See docs/TypeChecker.md, "Type annotation resolution".
     */
    public TypeInfo resolveTypeAnnotation(List<Token> typeTokens, Token at) {
        return resolveTypeAnnotation(typeTokens, at, false);
    }

    /**
     * `allowIndeterminateMutability` is true only when resolving a function's own return type (func/impl method, extern,
     * interface method signature); nested resolutions always pass false. See docs/TypeChecker.md, "Type annotation resolution".
     */
    public TypeInfo resolveTypeAnnotation(List<Token> typeTokens, Token at, boolean allowIndeterminateMutability) {
        String[] aliasNameHolder = new String[1];
        List<Token> expanded = expandAliases(typeTokens, new HashSet<>(), aliasNameHolder, at);
        TypeInfo resolved = resolveTypeAnnotationRaw(expanded, at, allowIndeterminateMutability);
        if (aliasNameHolder[0] == null) {
            return resolved;
        }
        return new TypeInfo(resolved.storage, resolved.mutability, resolved.baseType, aliasNameHolder[0]);
    }

    /** Splices alias base types in place; only the first VARREF counts as the base type, the rest (array suffixes etc.) passes through. */
    public List<Token> expandAliases(List<Token> tokens, Set<String> expanding, String[] aliasNameHolder, Token at) {
        List<Token> result = new ArrayList<>();
        // frames: {token iterator, sawBaseType, alias name to release from `expanding` when the frame ends (or null)}
        Deque<Object[]> frames = new ArrayDeque<>();
        frames.push(new Object[]{tokens.iterator(), false, null});
        while (!frames.isEmpty()) {
            Object[] f = frames.peek();
            @SuppressWarnings("unchecked")
            Iterator<Token> it = (Iterator<Token>) f[0];
            if (!it.hasNext()) {
                frames.pop();
                if (f[2] != null) {
                    expanding.remove((String) f[2]);
                }
                continue;
            }
            Token t = it.next();
            if (t.type == TokenType.VARREF && !(Boolean) f[1]) {
                f[1] = true;
                if (hub.model.aliasRawTargets.containsKey(t.text)) {
                    if (expanding.contains(t.text)) {
                        throw new CompilerException("type", t.file, t.line,
                                "circular type alias involving '" + t.text + "'");
                    }
                    if (aliasNameHolder[0] == null) {
                        aliasNameHolder[0] = t.text;
                    }
                    expanding.add(t.text);
                    frames.push(new Object[]{hub.model.aliasRawTargets.get(t.text).iterator(), false, t.text});
                    continue;
                }
            }
            result.add(t);
        }
        return result;
    }

    /** A struct/interface named in a type annotation must be @pub or declared in the same file; other names are ignored. */
    public void checkTypeNameVisibility(String baseType, Token at) {
        StructInfo si = hub.model.structs.get(baseType);
        if (si != null) {
            if (!si.isPublic && !si.declTok.file.equals(at.file)) {
                throw new CompilerException("type", at.file, at.line,
                        "'" + baseType + "' is private -- not accessible outside the file it's "
                                + "declared in");
            }
            return;
        }
        InterfaceInfo ii = hub.model.interfaces.get(baseType);
        if (ii != null && !ii.isPublic && !ii.declTok.file.equals(at.file)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + baseType + "' is private -- not accessible outside the file it's declared "
                            + "in");
        }
    }

    /** Modifiers must come in the order `const`, storage, `some`, mutability, base type; compares ranks of those present. */
    public void requireModifierOrder(int seenRank, int thisRank, String thisKind, Token at) {
        if (thisRank < seenRank) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + at.text + "' (" + thisKind + ") is out of order -- this language requires "
                            + "'const owns some imut T' order (constancy, then storage, then presence, "
                            + "then mutability, then the base type)");
        }
    }

    public TypeInfo resolveTypeAnnotationRaw(List<Token> typeTokens, Token at) {
        return resolveTypeAnnotationRaw(typeTokens, at, false);
    }

    public TypeInfo resolveTypeAnnotationRaw(List<Token> typeTokens, Token at,
            boolean allowIndeterminateMutability) {
        // One frame per (nested) annotation; a nested one (func params/return, raw pointee, dynarray element) is resolved on top, then delivered back.
        Deque<AnnotationFrame> stack = new ArrayDeque<>();
        stack.push(newAnnotationFrame(typeTokens, allowIndeterminateMutability));
        TypeInfo delivered = null;
        while (true) {
            AnnotationFrame f = stack.peek();
            if (delivered != null) {
                AnnotationFrame next = deliverAnnotation(f, delivered, at);
                delivered = null;
                if (next != null) {
                    stack.push(next);
                    continue;
                }
            }
            AnnotationFrame child = stepAnnotation(f, at);
            if (child != null) {
                stack.push(child);
                continue;
            }
            TypeInfo result = finishAnnotation(f, at);
            stack.pop();
            if (stack.isEmpty()) {
                return result;
            }
            delivered = result;
        }
    }

    private AnnotationFrame newAnnotationFrame(List<Token> typeTokens, boolean allowIndeterminateMutability) {
        AnnotationFrame f = new AnnotationFrame(typeTokens, allowIndeterminateMutability);
        // `const` must be leftmost; stripped before the modifier loop (a let's const is Token.isConst, set earlier).
        if (!typeTokens.isEmpty() && typeTokens.get(0).type == TokenType.KEYWORD && typeTokens.get(0).text.equals("const")) {
            f.isConst = true;
            f.typeTokens = typeTokens.subList(1, typeTokens.size());
        }
        return f;
    }

    /** Next parameter span of a `func(..) R` annotation, else its return span; null never (the return type always follows). */
    private AnnotationFrame nextFuncChild(AnnotationFrame f, Token at) {
        if (f.paramNext < f.paramSpans.size()) {
            f.wait = AnnotationFrame.WAIT_FUNC_PARAM;
            return new AnnotationFrame(f.paramSpans.get(f.paramNext++), false);
        }
        if (f.returnSpan.isEmpty()) {
            throw new CompilerException("type", f.waitTok.file, f.waitTok.line, "'func(...)' requires a return type");
        }
        f.wait = AnnotationFrame.WAIT_FUNC_RETURN;
        return new AnnotationFrame(new ArrayList<>(f.returnSpan), false);
    }

    /** Takes the result of the nested annotation the frame waited for; returns another nested frame to resolve, or null to go on with the token loop. */
    private AnnotationFrame deliverAnnotation(AnnotationFrame f, TypeInfo r, Token at) {
        int wait = f.wait;
        f.wait = AnnotationFrame.WAIT_NONE;
        switch (wait) {
            case AnnotationFrame.WAIT_FUNC_PARAM:
                f.funcPtrParams.add(r);
                return nextFuncChild(f, at);
            case AnnotationFrame.WAIT_FUNC_RETURN:
                f.funcPtrReturn = r;
                f.baseType = "func"; // placeholder -- TypeInfo.funcPointer builds the real baseType
                f.i = f.typeTokens.size() + 1; // consumed everything remaining
                return null;
            case AnnotationFrame.WAIT_RAW:
                f.rawPointeeType = r;
                f.baseType = r.canonical();
                f.i = f.typeTokens.size() + 1;
                return null;
            case AnnotationFrame.WAIT_UNSAFE_DYN:
                f.unsafeDynArrayElementType = r;
                checkNotRefSomeElement(r, f.waitTok, "an 'unsafe dynarray' element type");
                f.baseType = "unsafe_dynarray(" + TypeInfo.dynArrayElementText(r) + ")";
                f.i += 3; // 'unsafe', 'dynarray' and the '(' span
                return null;
            case AnnotationFrame.WAIT_DYN:
                f.dynArrayElementType = r;
                checkNotRefSomeElement(r, f.waitTok, "a 'dynarray' element type");
                f.baseType = "dynarray(" + TypeInfo.dynArrayElementText(r) + ")";
                f.i += 2; // 'dynarray' and the '(' span
                return null;
            default:
                throw new IllegalStateException("annotation frame delivered with nothing awaited");
        }
    }

    /** Runs the token loop from f.i; returns a nested annotation to resolve first (the frame then waits), or null when the tokens are used up. */
    private AnnotationFrame stepAnnotation(AnnotationFrame f, Token at) {
        for (; f.i < f.typeTokens.size(); f.i++) {

            Token t = f.typeTokens.get(f.i);
            if (t.type == TokenType.OPERATOR && t.text.equals("@")) {
                // Inline `@name` inside a function-pointer annotation (normal decorators attach to a statement line).
                if (f.i + 1 >= f.typeTokens.size() || f.typeTokens.get(f.i + 1).type != TokenType.VARREF) {
                    throw new CompilerException("type", t.file, t.line,
                            "'@' must be followed by a decorator name");
                }
                String decoratorName = f.typeTokens.get(f.i + 1).text;
                if (f.funcPtrDecorators == null) {
                    f.funcPtrDecorators = new HashSet<>();
                }
                f.funcPtrDecorators.add(decoratorName);
                f.i++; // consumed the decorator name token too
                // Optional "(args)": only @call_convention carries a value.
                if (f.i + 1 < f.typeTokens.size() && f.typeTokens.get(f.i + 1).type == TokenType.DELINEATOR
                        && f.typeTokens.get(f.i + 1).text.equals("(")) {
                    Token parenTok = f.typeTokens.get(f.i + 1);
                    f.i++;
                    if (decoratorName.equals("call_convention")) {
                        f.funcPtrCallConventionName = parseInlineCallConventionArg(parenTok);
                    }
                }
            } else if (t.type == TokenType.KEYWORD && t.text.equals("func") && f.baseType == null) {
                // "func(P1,P2,...) R": the return type is everything after the parameter list.
                if (f.i + 1 >= f.typeTokens.size() || f.typeTokens.get(f.i + 1).type != TokenType.DELINEATOR
                        || !f.typeTokens.get(f.i + 1).text.equals("(")) {
                    throw new CompilerException("type", t.file, t.line,
                            "'func' requires a parenthesized parameter list");
                }
                Token paramsParen = f.typeTokens.get(f.i + 1);
                f.funcPtrParams = new ArrayList<>();
                f.paramSpans = paramsParen.childs.isEmpty() ? new ArrayList<>() : splitTopLevelArgGroups(paramsParen.childs);
                f.paramNext = 0;
                f.returnSpan = f.typeTokens.subList(f.i + 2, f.typeTokens.size());
                f.waitTok = t;
                return nextFuncChild(f, at);
            } else if (t.type == TokenType.MODIFIER) {
                if (t.text.equals("mut") || t.text.equals("imut")) {
                    if (f.mutability != null) {
                        throw new CompilerException("type", t.file, t.line,
                                "duplicate mutability modifier in type annotation");
                    }
                    requireModifierOrder(f.modifierRank, 3, "mutability", t);
                    f.modifierRank = 3;
                    f.mutability = t.text;
                } else if (t.text.equals("some")) {
                    // `some` is a presence flag, not a storage; rank 2.
                    if (f.isSome) {
                        throw new CompilerException("type", t.file, t.line,
                                "duplicate 'some' modifier in type annotation");
                    }
                    requireModifierOrder(f.modifierRank, 2, "presence", t);
                    f.modifierRank = 2;
                    f.isSome = true;
                } else {
                    if (f.storage != null) {
                        if (f.storage.equals("raw") && t.text.equals("raw")) {
                            // A second `raw` starts a nested annotation ("raw mut raw mut char"), resolved recursively.
                            List<Token> pointeeSpan = new ArrayList<>(f.typeTokens.subList(f.i, f.typeTokens.size()));
                            f.wait = AnnotationFrame.WAIT_RAW;
                            return new AnnotationFrame(pointeeSpan, false);
                        }
                        throw new CompilerException("type", t.file, t.line,
                                "duplicate storage modifier in type annotation");
                    }
                    requireModifierOrder(f.modifierRank, 1, "storage", t);
                    f.modifierRank = 1;
                    f.storage = t.text;
                    if (f.storage.equals("owns") || f.storage.equals("ref")) {
                        // Any annotation that writes owns/ref counts (syntactic presence).
                        hub.model.usesOwnsRefDynNew = true;
                    }
                }
            } else if (t.type == TokenType.VARREF && f.baseType == null && f.i + 1 < f.typeTokens.size()
                    && hub.model.isFloatStateName(t.text)) {
                // Float-state modifier (finite|infinite|nan, chainable with '|'): contextual identifiers, not keywords.
                // A VARREF that is the last token is the base type instead.
                if (f.floatStates != null) {
                    throw new CompilerException("type", t.file, t.line,
                            "duplicate float-state modifier in type annotation");
                }
                f.floatStates = new java.util.TreeSet<>();
                f.floatStates.add(t.text);
                while (f.i + 2 < f.typeTokens.size() && f.typeTokens.get(f.i + 1).type == TokenType.OPERATOR
                        && f.typeTokens.get(f.i + 1).text.equals("|") && hub.model.isFloatStateName(f.typeTokens.get(f.i + 2).text)) {
                    String next = f.typeTokens.get(f.i + 2).text;
                    if (!f.floatStates.add(next)) {
                        throw new CompilerException("type", t.file, t.line,
                                "'" + next + "' repeated in a float-state modifier chain");
                    }
                    f.i += 2;
                }
                if (f.floatStates.size() == TypeCheckerModel.FLOAT_STATE_NAMES.size()) {
                    throw new CompilerException("type", t.file, t.line,
                            "'" + String.join("|", f.floatStates) + "' names every possible float state -- "
                                    + "write the bare type instead (with no state modifier at all), which "
                                    + "already means exactly that");
                }
            } else if (t.type == TokenType.KEYWORD && t.text.equals("unsafe") && f.baseType == null
                    && f.i + 1 < f.typeTokens.size() && f.typeTokens.get(f.i + 1).type == TokenType.VARREF
                    && f.typeTokens.get(f.i + 1).text.equals("dynarray")) {
                // "unsafe dynarray(T)": the element type must be spelled out.
                Token dynTok = f.typeTokens.get(f.i + 1);
                Token parenTok = (f.i + 2 < f.typeTokens.size()) ? f.typeTokens.get(f.i + 2) : null;
                if (parenTok == null || parenTok.type != TokenType.DELINEATOR || !parenTok.text.equals("(")) {
                    throw new CompilerException("type", dynTok.file, dynTok.line,
                            "'unsafe dynarray' requires its element type spelled out, e.g. "
                                    + "'unsafe dynarray(u64)' -- there's no bare/incomplete form of this type");
                }
                List<List<Token>> args = splitTopLevelArgGroups(parenTok.childs);
                if (args.size() != 1) {
                    throw new CompilerException("type", parenTok.file, parenTok.line,
                            "'unsafe dynarray(...)' requires exactly one argument: the element type");
                }
                f.wait = AnnotationFrame.WAIT_UNSAFE_DYN;
                f.waitTok = parenTok;
                return new AnnotationFrame(args.get(0), false);
            } else if (t.type == TokenType.VARREF && t.text.equals("dynarray") && f.baseType == null) {
                // "dynarray(T)": always complete, no bare form.
                Token next = (f.i + 1 < f.typeTokens.size()) ? f.typeTokens.get(f.i + 1) : null;
                if (next == null || next.type != TokenType.DELINEATOR || !next.text.equals("(")) {
                    throw new CompilerException("type", t.file, t.line,
                            "'dynarray' requires its element type spelled out, e.g. 'dynarray(u64)' -- "
                                    + "there's no bare/incomplete form of this type");
                }
                List<List<Token>> args = splitTopLevelArgGroups(next.childs);
                if (args.size() != 1) {
                    throw new CompilerException("type", next.file, next.line,
                            "'dynarray(...)' requires exactly one argument: the element type");
                }
                f.wait = AnnotationFrame.WAIT_DYN;
                f.waitTok = next;
                return new AnnotationFrame(args.get(0), false);
            } else if (t.type == TokenType.VARREF && t.text.equals("slice") && f.baseType == null) {
                // `slice` was removed from the language; rejected with a dedicated message.
                throw new CompilerException("type", t.file, t.line,
                        "'slice' is no longer a valid type -- Caspien has removed the slice type; use a "
                                + "scalar index or a range/pointer pair explicitly");
            } else if (t.type == TokenType.VARREF && t.text.equals("range") && f.baseType == null) {
                Token next = (f.i + 1 < f.typeTokens.size()) ? f.typeTokens.get(f.i + 1) : null;
                if (next != null && next.type == TokenType.DELINEATOR && next.text.equals("(")) {
                    // "range(a,b)": bounds as in rangeBoundDescriptor.
                    f.baseType = parseRangeAnnotationArgs(next, at);
                    f.i++; // consumed the '(' token's whole span as one unit
                } else {
                    // Bare "range": legal by value without storage (two u64 bounds, unknown at compile time).
                    f.baseType = t.text;
                    f.baseIsIncompleteRange = true;
                }
            } else if (t.type == TokenType.VARREF) {
                if (f.baseType != null) {
                    throw new CompilerException("type", t.file, t.line,
                            "unexpected extra token '" + t.text + "' in type annotation");
                }
                f.baseType = t.text;
                f.baseTypeTok = t;
            } else if (t.type == TokenType.DELINEATOR && t.text.equals("[")) {
                // "T[N]": N is one integer literal; repeatable for multi-dimensional arrays.
                if (f.baseType == null) {
                    throw new CompilerException("type", t.file, t.line,
                            "array length annotation '[...]' with no base type before it");
                }
                if (t.childs.size() != 1 || t.childs.get(0).type != TokenType.INTEGER) {
                    throw new CompilerException("type", t.file, t.line,
                            "array length must be a single integer literal");
                }
                f.arraySuffix.append('[').append(t.childs.get(0).text).append(']');
            } else {
                throw new CompilerException("type", t.file, t.line,
                        "unexpected token '" + t.text + "' in type annotation");
            }
        }
        return null;
    }

    private TypeInfo finishAnnotation(AnnotationFrame f, Token at) {
        String mutability = f.mutability;
        String storage = f.storage;
        boolean isSome = f.isSome;
        java.util.TreeSet<String> floatStates = f.floatStates;
        String baseType = f.baseType;
        Token baseTypeTok = f.baseTypeTok;
        StringBuilder arraySuffix = f.arraySuffix;
        boolean baseIsIncompleteRange = f.baseIsIncompleteRange;
        TypeInfo dynArrayElementType = f.dynArrayElementType;
        TypeInfo unsafeDynArrayElementType = f.unsafeDynArrayElementType;
        TypeInfo rawPointeeType = f.rawPointeeType;
        List<TypeInfo> funcPtrParams = f.funcPtrParams;
        TypeInfo funcPtrReturn = f.funcPtrReturn;
        Set<String> funcPtrDecorators = f.funcPtrDecorators;
        String funcPtrCallConventionName = f.funcPtrCallConventionName;
        boolean isConst = f.isConst;
        boolean allowIndeterminateMutability = f.allowIndeterminateMutability;
        if (baseType == null) {
            throw new CompilerException("type", at.file, at.line, "type annotation is missing a base type");
        }
        if (isSome) {
            // `some` needs owns/ref/raw/static storage ('auto' is already non-null).
            if (storage == null || storage.equals("auto")) {
                throw new CompilerException("type", at.file, at.line,
                        "'some' requires 'owns'/'ref'/'raw'/'static' storage, got '"
                                + (storage == null ? "none" : storage) + "'");
            }
            // No fixed-array-specific `ref some` restriction here. See docs/TypeChecker.md, "Type annotation resolution".
        }
        if (floatStates != null && !TypeCheckerModel.isFloatType(baseType)) {
            throw new CompilerException("type", at.file, at.line,
                    "a float-state modifier ('" + String.join("|", floatStates) + "') requires 'f32' or 'f64', got '"
                            + baseType + "'");
        }
        if (baseIsIncompleteRange) {
            // Bare `range` is a plain by-value type: nothing to check.
        } else if (funcPtrParams != null) {
            // Function-pointer types need 'static' storage.
            if (!"static".equals(storage)) {
                throw new CompilerException("type", at.file, at.line,
                        "a function-pointer type's storage must be 'static', got '"
                                + (storage == null ? "none" : storage) + "'");
            }
            if (mutability == null) {
                throw new CompilerException("type", at.file, at.line,
                        "type annotations require an explicit 'mut' or 'imut' -- mutability is never "
                                + "inferred or defaulted here");
            }
            return TypeInfo.funcPointer(mutability, funcPtrParams, funcPtrReturn,
                    funcPtrDecorators != null ? funcPtrDecorators : new HashSet<>(),
                    funcPtrCallConventionName != null ? funcPtrCallConventionName : hub.model.config.defaultConvention)
                    .withSome(isSome).withFloatStates(floatStates).withConst(isConst);
        } else if (!baseType.startsWith("range(")
                && !baseType.startsWith("dynarray(") && !baseType.startsWith("unsafe_dynarray(")
                && rawPointeeType == null && !isKnownType(baseType)) {
            throw new CompilerException("type", at.file, at.line, "unknown type '" + baseType + "'");
        }
        if (mutability == null && !baseType.equals("void") && !allowIndeterminateMutability) {
            // Mutability is mandatory in annotations except on a function's own return type (allowIndeterminateMutability).
            throw new CompilerException("type", at.file, at.line,
                    "type annotations require an explicit 'mut' or 'imut' -- mutability is never "
                            + "inferred or defaulted here");
        }
        if (mutability == null && allowIndeterminateMutability && !baseType.equals("void")) {
            // Return types may omit mutability; recorded as "indeterminate".
            mutability = "indeterminate";
        }
        if (mutability == null) {
            // `void` is exempt; "imut" so it equals an omitted return type.
            mutability = "imut";
        }
        // string and interface types require storage; structs/enums may be inline values. Array rules are enforced by the caller.
        // The visibility check is anchored to baseTypeTok (see above).
        checkTypeNameVisibility(baseType, baseTypeTok != null ? baseTypeTok : at);
        if ((hub.model.pointerLikeBaseTypes.contains(baseType) || hub.model.interfaces.containsKey(baseType)) && storage == null) {
            throw new CompilerException("type", at.file, at.line,
                    "type '" + baseType + "' is a pointer type and requires a storage modifier "
                            + "(owns/ref/raw/auto/static)");
        }
        if (dynArrayElementType != null && arraySuffix.length() == 0) {
            // The generic constructor below drops dynArrayElementType, so build the TypeInfo here.
            // Only for a bare "dynarray(T)", not "dynarray(T)[3]"; storage is forced to owns.
            // See docs/TypeChecker.md, "Type annotation resolution".
            if (storage != null && !storage.equals("owns")) {
                throw new CompilerException("type", at.file, at.line,
                        "a dynarray is always 'owns' storage -- it's inherently a heap-allocated, "
                                + "owned value, with no other storage it could have -- '" + storage
                                + "' can't be written before it");
            }
            return new TypeInfo("owns", mutability, baseType + arraySuffix, null,
                    null, null, null, null, dynArrayElementType, null)
                            .withSome(isSome).withFloatStates(floatStates).withConst(isConst);
        }
        if (unsafeDynArrayElementType != null) {
            // Same for "unsafe dynarray(T)": keep the element type, force owns.
            if (storage != null && !storage.equals("owns")) {
                throw new CompilerException("type", at.file, at.line,
                        "an unsafe dynarray is always 'owns' storage -- it's inherently a "
                                + "heap-allocated, owned value, with no other storage it could have -- '"
                                + storage + "' can't be written before it");
            }
            return new TypeInfo("owns", mutability, baseType + arraySuffix, null,
                    null, null, null, null, null, unsafeDynArrayElementType, null, null,
                    isSome, floatStates, isConst, false);
        }
        if (rawPointeeType != null) {
            // Explicit nested raw: keep rawPointeeType.
            return new TypeInfo(storage, mutability, baseType + arraySuffix, null,
                    null, null, null, null, null, rawPointeeType)
                            .withSome(isSome).withFloatStates(floatStates).withConst(isConst);
        }
        return new TypeInfo(storage, mutability, baseType + arraySuffix, null)
                .withSome(isSome).withFloatStates(floatStates).withConst(isConst);
    }

    /** `ref some` is rejected as a dynarray / unsafe-dynarray element type (struct members: validateNoRefSomeStructMembers). */
    public void checkNotRefSomeElement(TypeInfo elementType, Token at, String contextDesc) {
        if ("ref".equals(elementType.storage) && elementType.isSome) {
            throw new CompilerException("type", at.file, at.line,
                    "'ref some' is not allowed as " + contextDesc);
        }
    }

    /** Parses `@call_convention(name)` in a function-pointer annotation: one bare name declared in compiler.config. */
    public String parseInlineCallConventionArg(Token parenTok) {
        List<String> args = new ArrayList<>();
        for (Token argTok : parenTok.childs) {
            if (argTok.type == TokenType.OPERATOR && argTok.text.equals(",")) {
                continue;
            }
            if (argTok.type != TokenType.VARREF) {
                throw new CompilerException("type", argTok.file, argTok.line,
                        "'@call_convention' takes a bare name, not '" + argTok.text + "'");
            }
            args.add(argTok.text);
        }
        if (args.size() != 1) {
            throw new CompilerException("type", parenTok.file, parenTok.line,
                    "'@call_convention' takes exactly one argument, e.g. '@call_convention(win64)'");
        }
        if (!hub.model.config.callingConventions.containsKey(args.get(0))) {
            throw new CompilerException("type", parenTok.file, parenTok.line,
                    "'@call_convention(" + args.get(0) + ")' is not a calling convention declared in "
                            + "compiler.config (known: " + hub.model.config.callingConventions.keySet() + ")");
        }
        return args.get(0);
    }

    /** "range(0,5)" or "range(0,x)" -- the composite range annotation body (inside the parens), reusing rangeBoundDescriptor's exact bare-literal-or-bare-identifier shape. */
    public String parseRangeAnnotationArgs(Token parenTok, Token at) {
        List<List<Token>> args = splitTopLevelArgGroups(parenTok.childs);
        if (args.size() != 2) {
            throw new CompilerException("type", parenTok.file, parenTok.line,
                    "'range(...)' requires exactly two bounds");
        }
        return "range(" + rangeAnnotationBoundDescriptor(args.get(0))
                + "," + rangeAnnotationBoundDescriptor(args.get(1)) + ")";
    }

    /** A range bound in a type annotation: an integer literal or a bare identifier (assumed imut). */
    public String rangeAnnotationBoundDescriptor(List<Token> boundTokens) {
        if (boundTokens.size() != 1) {
            throw new CompilerException("type", boundTokens.get(0).file, boundTokens.get(0).line,
                    "a range bound must be a single integer literal or a bare identifier");
        }
        Token t = boundTokens.get(0);
        if (t.type == TokenType.INTEGER) {
            return t.text;
        }
        if (t.type == TokenType.VARREF) {
            return "imut_" + t.text;
        }
        throw new CompilerException("type", t.file, t.line,
                "a range bound must be a single integer literal or a bare identifier, found '" + t.text + "'");
    }

    public List<List<Token>> splitTopLevelArgGroups(List<Token> tokens) {
        List<List<Token>> groups = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        for (Token t : tokens) {
            if (t.type == TokenType.WHITESPACE) {
                continue;
            }
            if (t.type == TokenType.OPERATOR && t.text.equals(",")) {
                groups.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(t);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    public boolean isKnownType(String name) {
        if (TypeCheckerModel.NUMERIC_TYPES.contains(name)) {
            return true;
        }
        switch (name) {
            case "bool": case "char": case "string": case "void": case "null":
                return true;
            default:
                return hub.model.structs.containsKey(name) || hub.model.enums.containsKey(name) || hub.model.interfaces.containsKey(name);
        }
    }

    /** A call made in a "safe" context may not reach "unsafe" code, transitively. Runs after all bodies are checked. See docs/TypeChecker.md, "Whole-program call-graph checks". */
    public void checkSafetyCallGraph() {
        Map<FuncInfo, List<FuncInfo>> callees = new HashMap<>();
        for (CallEdge e : hub.model.callEdges) {
            List<FuncInfo> calleeList = callees.get(e.caller);
            if (calleeList == null) {
                calleeList = new ArrayList<>();
                callees.put(e.caller, calleeList);
            }
            calleeList.add(e.callee);
        }
        Map<FuncInfo, Boolean> reachesUnsafeCache = new HashMap<>();
        for (CallEdge e : hub.model.callEdges) {
            if (!e.safety.equals("safe")) {
                continue;
            }
            if (e.callee.safetyTag.equals("unsafe") || reachesUnsafe(e.callee, callees, reachesUnsafeCache)) {
                throw new CompilerException("type", e.at.file, e.at.line,
                        "'" + e.caller.name + "' calls '" + e.callee.name + "' from within a 'safe' "
                                + "context, but '" + e.callee.name + "' is (or transitively calls) "
                                + "'unsafe' code");
            }
        }
    }

    /** Direct recursion needs @recursive; any indirect cycle is an error. `call()` targets are not CallEdges, so they are never seen. See docs/TypeChecker.md, "Whole-program call-graph checks". */
    public void checkNoRecursion() {
        Map<FuncInfo, List<CallEdge>> outEdges = new HashMap<>();
        for (CallEdge e : hub.model.callEdges) {
            outEdges.computeIfAbsent(e.caller, k -> new ArrayList<>()).add(e);
        }
        for (CallEdge e : hub.model.callEdges) {
            if (e.caller == e.callee && hub.impls.getDecorator(e.caller.funcToken.decorators, "recursive") == null) {
                throw new CompilerException("type", e.at.file, e.at.line,
                        "'" + e.caller.name + "' calls itself -- recursion is not allowed unless the "
                                + "function is decorated '@recursive'");
            }
        }
        Set<FuncInfo> visited = new HashSet<>();
        Set<FuncInfo> onStack = new HashSet<>();
        for (FuncInfo start : outEdges.keySet()) {
            if (!visited.contains(start)) {
                detectIndirectCycle(start, outEdges, visited, onStack);
            }
        }
    }

    public void detectIndirectCycle(FuncInfo start, Map<FuncInfo, List<CallEdge>> outEdges, Set<FuncInfo> visited,
            Set<FuncInfo> onStack) {
        // frames: {function, iterator over its call edges}
        Deque<Object[]> frames = new ArrayDeque<>();
        visited.add(start);
        onStack.add(start);
        frames.push(new Object[]{start, outEdges.getOrDefault(start, new ArrayList<>()).iterator()});
        while (!frames.isEmpty()) {
            Object[] fr = frames.peek();
            FuncInfo f = (FuncInfo) fr[0];
            @SuppressWarnings("unchecked")
            Iterator<CallEdge> edges = (Iterator<CallEdge>) fr[1];
            if (!edges.hasNext()) {
                onStack.remove(f);
                frames.pop();
                continue;
            }
            CallEdge e = edges.next();
            if (e.caller == e.callee) {
                continue; // direct self-recursion already checked separately, with its own rule
            }
            if (onStack.contains(e.callee)) {
                throw new CompilerException("type", e.at.file, e.at.line,
                        "indirect recursion detected: '" + e.caller.name + "' calls '" + e.callee.name
                                + "', which (directly or transitively) calls back to '" + e.caller.name
                                + "' -- recursion is never allowed indirectly, even for a '@recursive' "
                                + "function");
            }
            if (!visited.contains(e.callee)) {
                visited.add(e.callee);
                onStack.add(e.callee);
                frames.push(new Object[]{e.callee, outEdges.getOrDefault(e.callee, new ArrayList<>()).iterator()});
            }
        }
    }

    /** No `throw` may be reachable from a gt_* decorated function (a local try/catch is fine). See docs/TypeChecker.md, "Whole-program call-graph checks". */
    public void validateNoThrowReachableFromGhostTableFunctions() {
        List<FuncInfo> gtFuncs = new ArrayList<>();
        for (String decoratorName : Arrays.asList("gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve")) {
            FuncInfo f = hub.model.ghostTableFunctions.get(decoratorName);
            if (f != null) {
                gtFuncs.add(f);
            }
        }
        if (gtFuncs.isEmpty() || hub.model.throwSites.isEmpty()) {
            return;
        }
        Map<FuncInfo, List<FuncInfo>> callees = new HashMap<>();
        for (CallEdge e : hub.model.callEdges) {
            List<FuncInfo> calleeList = callees.get(e.caller);
            if (calleeList == null) {
                calleeList = new ArrayList<>();
                callees.put(e.caller, calleeList);
            }
            calleeList.add(e.callee);
        }
        Set<FuncInfo> gtFuncSet = new HashSet<>(gtFuncs);
        Set<FuncInfo> reachable = new HashSet<>();
        List<FuncInfo> stack = new ArrayList<>(gtFuncs);
        reachable.addAll(gtFuncs);
        while (!stack.isEmpty()) {
            FuncInfo current = stack.remove(stack.size() - 1);
            for (FuncInfo callee : callees.getOrDefault(current, Collections.emptyList())) {
                if (reachable.add(callee)) {
                    stack.add(callee);
                }
            }
        }
        for (ThrowSite site : hub.model.throwSites) {
            if (reachable.contains(site.func) && !gtFuncSet.contains(site.func)) {
                throw new CompilerException("type", site.at.file, site.at.line,
                        "'throw' is not allowed here -- '" + site.func.name + "' is (transitively) "
                                + "reachable from a '@gt_init'/'@gt_alive_check'/'@gt_destruct'/"
                                + "'@gt_register' function, and no 'throw' may ever escape a ghost-table "
                                + "function's own call graph (calling an '@throws' function and catching "
                                + "it locally is still fine -- only an unhandled 'throw' reachable from "
                                + "here is banned)");
            }
        }
    }

    /** True when `t` is an `auto`, or an array / dynarray of them (a pointer to a stack slot that cannot outlive its frame). */
    public boolean typeHoldsAuto(TypeInfo root) {
        Deque<TypeInfo> work = new java.util.LinkedList<>();
        work.push(root);
        while (!work.isEmpty()) {
            TypeInfo t = work.pop();
            if (t == null) {
                continue;
            }
            if ("auto".equals(t.storage)) {
                return true;
            }
            work.push(t.arrayElementType);
            work.push(t.dynArrayElementType);
            String base = t.baseType;
            if (base == null) {
                continue;
            }
            base = hub.declarations.stripArrayDims(base);
            if (base.startsWith("dynarray(") || base.startsWith("unsafe_dynarray(")) {
                int open = base.indexOf('(');
                int close = base.lastIndexOf(')');
                if (close > open) {
                    work.push(parseDynArrayElementTextForCheck(base.substring(open + 1, close)));
                }
            }
        }
        return false;
    }

    public TypeInfo parseDynArrayElementTextForCheck(String text) {
        return text.startsWith("auto_") ? new TypeInfo("auto", "indeterminate", text.substring(5)) : new TypeInfo(null, "indeterminate", text);
    }

    /**
     * `auto` points into a stack frame, so it must not be stored anywhere that can outlive that frame: a struct member (the struct can be
     * returned, moved to the heap, or handed to another function that keeps it) is refused, and a function cannot return one
     * (see `requireNoAutoReturn`). An `auto` parameter or local is fine.
     */
    public void validateNoAutoStructMembers() {
        for (StructInfo si : hub.model.structs.values()) {
            for (Map.Entry<String, TypeInfo> member : si.members.entrySet()) {
                if (typeHoldsAuto(member.getValue())) {
                    throw new CompilerException("type", si.declTok.file, si.declTok.line,
                            "'" + si.name + "'s member '" + member.getKey() + "' is an 'auto' pointer (" + member.getValue().canonical()
                                    + ") -- an 'auto' points into a stack frame and cannot be stored in a struct, which could outlive it. "
                                    + "Use 'ref' to an owned value instead");
                }
            }
        }
    }

    /** A container (array / dynarray) of `auto`: a callee could push its own local's address into a longer-lived container. */
    public void requireNoAutoContainer(TypeInfo t, Token where, String what) {
        if (t != null && !"auto".equals(t.storage) && typeHoldsAuto(t)) {
            throw new CompilerException("type", where.file, where.line,
                    what + " has the type '" + t.canonical() + "', a container of 'auto' pointers: an 'auto' points into a stack frame, "
                            + "and a callee could store its own local's address into a container that outlives it");
        }
    }

    public void requireNoAutoReturn(FuncInfo f) {
        for (int pi = 0; pi < f.paramTypes.size(); pi++) {
            requireNoAutoContainer(f.paramTypes.get(pi), f.funcToken, "parameter '" + f.paramNames.get(pi) + "' of '" + f.name + "'");
        }
        if (typeHoldsAuto(f.returnType)) {
            throw new CompilerException("type", f.funcToken.file, f.funcToken.line,
                    "'" + f.name + "' returns an 'auto' pointer (" + f.returnType.canonical() + ") -- it would point into the stack frame "
                            + "of the function that is returning. Return a 'ref' to an owned value, or the value itself");
        }
    }

    /** `ref some` is rejected as a struct member type; members bypass checkNotRefSomeElement, hence this post-collection pass. */
    public void validateNoRefSomeStructMembers() {
        for (StructInfo si : hub.model.structs.values()) {
            for (Map.Entry<String, TypeInfo> member : si.members.entrySet()) {
                TypeInfo mt = member.getValue();
                if ("ref".equals(mt.storage) && mt.isSome) {
                    throw new CompilerException("type", si.declTok.file, si.declTok.line,
                            "'" + si.name + "'s member '" + member.getKey() + "' is 'ref some' -- "
                                    + "'ref some' is not allowed as a struct member type");
                }
            }
        }
    }

    public TypeInfo declaredTypeIncludingGlobals(Token node, Scope scope) {
        // walk down the `.member` spine, resolve its root, then resolve the members back up
        Deque<Token> spine = new ArrayDeque<>();
        while (!(node.type == TokenType.VARREF && scope.lookup(node.text) == null)
                && node.type == TokenType.OPERATOR && ".".equals(node.text) && node.right != null && node.right.type == TokenType.VARREF) {
            spine.push(node);
            node = node.left;
        }
        TypeInfo cur;
        if (node.type == TokenType.VARREF && scope.lookup(node.text) == null) {
            cur = hub.model.globals.get(node.text);
        } else {
            cur = hub.statements.lookupDeclaredTypeStructurally(node, scope);
        }
        while (!spine.isEmpty()) {
            Token dot = spine.pop();
            StructInfo si = cur == null ? null : hub.model.structs.get(cur.baseType);
            cur = si == null ? null : si.members.get(dot.right.text);
        }
        return cur;
    }

    /** Whether the assignment target `a.b.c` / `a.items[i]` lies inside a swap-lock struct. */
    public boolean assignsInsideSwapLockStruct(Token target, Scope scope) {
        Token cur = target;
        while (cur != null && cur.type == TokenType.OPERATOR && (".".equals(cur.text) || "LOOKUP".equals(cur.text)) && cur.left != null) {
            if (".".equals(cur.text)) {
                TypeInfo holder = declaredTypeIncludingGlobals(cur.left, scope);
                if (holder != null && holder.baseType != null) {
                    StructInfo si = hub.model.structs.get(holder.baseType);
                    if (si != null && si.swapFieldName != null) {
                        return true;
                    }
                }
            }
            cur = cur.left;
        }
        return false;
    }

    /**
     * A value entering a swap-lock struct must be a fresh allocation (`null`, `new`, `dyn`, `clone`), never a moved variable: an
     * earlier `ref` would reach the data without the lock. See docs/TypeChecker.md, "Lock struct rules".
     */
    public void requireFreshIntoSwapLock(Token rhs, String verb, String targetText) {
        Token v = rhs;
        while (true) {
            if (v.type == TokenType.DELINEATOR && "(".equals(v.text) && !v.childs.isEmpty()) {
                v = v.childs.get(0);
            } else if (v.type == TokenType.OPERATOR && v.unary && ("mut".equals(v.text) || "imut".equals(v.text)) && v.left != null) {
                v = v.left;
            } else if (v.type == TokenType.KEYWORD && "try".equals(v.text) && !v.isTryBlock && v.sub != null && !v.sub.isEmpty()) {
                v = v.operand();
            } else {
                break;
            }
        }
        boolean fresh = v.type == TokenType.NULL
                || (v.type == TokenType.OPERATOR && v.unary && "new".equals(v.text))
                || (v.type == TokenType.OPERATOR && "CALL".equals(v.text) && v.left != null && v.left.type == TokenType.VARREF
                        && ("dyn".equals(v.left.text) || "clone".equals(v.left.text)));
        if (!fresh || containsMoveSource(v)) {
            throw new CompilerException("type", rhs.file, rhs.line,
                    "a value " + verb + " the lock struct ('" + targetText + "') must be a fresh allocation: 'null', 'new ...', 'dyn(...)' or "
                            + "'clone(...)', with no owned variable moved into it. A 'ref' taken before the move would still reach the data "
                            + "without the lock. Copy an existing value in with 'clone(x)'");
        }
    }

    public boolean containsMoveSource(Token root) {
        Deque<Token> work = new java.util.LinkedList<>();
        work.push(root);
        while (!work.isEmpty()) {
            Token t = work.pop();
            if (t == null) {
                continue;
            }
            if (t.chk.isOwnershipMoveSource) {
                return true;
            }
            work.push(t.left);
            work.push(t.right);
            work.push(t.next);
            if (t.childs != null) {
                for (Token child : t.childs) {
                    work.push(child);
                }
            }
            if (t.sub != null) {
                for (Token child : t.sub) {
                    work.push(child);
                }
            }
        }
        return false;
    }

    /** No `ref`/`auto` anywhere inside a `@lock` struct (nested structs, arrays, owns pointees, interface implementers). See docs/TypeChecker.md, "Lock struct rules". */
    public void validateNoPointersInLockStructs() {
        for (Map.Entry<String, StructInfo> entry : hub.model.structs.entrySet()) {
            StructInfo info = entry.getValue();
            if (info.lockFieldName == null) {
                continue;
            }
            Set<String> visiting = new HashSet<>();
            visiting.add(entry.getKey());
            for (Map.Entry<String, TypeInfo> member : info.members.entrySet()) {
                String found = findLockForbiddenPointer(member.getValue(), entry.getKey() + "." + member.getKey(), visiting);
                if (found != null) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "'" + entry.getKey() + "' is decorated '@lock(...)', so it cannot contain a 'ref' or 'auto' anywhere inside it, "
                                    + "but '" + found + "' is one: a reference taken out of the lock would reach data the lock does not protect. "
                                    + "Make the struct own its data ('owns') and refer to items by index inside the lock");
                }
            }
        }
    }

    /** The path of the first `ref`/`auto` reachable from `t` (through nested structs, fixed arrays, dynarrays and `owns` pointees), or null. */
    public String findLockForbiddenPointer(TypeInfo root, String rootPath, Set<String> visiting) {
        // frames: {iterator over {TypeInfo, path} children, struct name to release from `visiting` when the frame ends (or null)}
        Deque<Object[]> frames = new ArrayDeque<>();
        frames.push(new Object[]{Collections.singletonList(new Object[]{root, rootPath}).iterator(), null});
        while (!frames.isEmpty()) {
            Object[] fr = frames.peek();
            @SuppressWarnings("unchecked")
            Iterator<Object[]> children = (Iterator<Object[]>) fr[0];
            if (!children.hasNext()) {
                frames.pop();
                if (fr[1] != null) {
                    visiting.remove((String) fr[1]);
                }
                continue;
            }
            Object[] child = children.next();
            TypeInfo t = (TypeInfo) child[0];
            String path = (String) child[1];
            if (t == null) {
                continue;
            }
            if ("ref".equals(t.storage) || "auto".equals(t.storage)) {
                for (Object[] open : frames) {
                    if (open[1] != null) {
                        visiting.remove((String) open[1]);
                    }
                }
                return path + " (" + t.canonical() + ")";
            }
            String base = t.baseType;
            if (base == null) {
                continue;
            }
            base = hub.declarations.stripArrayDims(base);
            if (base.startsWith("dynarray(") || base.startsWith("unsafe_dynarray(")) {
                int open = base.indexOf('(');
                int close = base.lastIndexOf(')');
                if (close > open) {
                    frames.push(new Object[]{Collections.singletonList(new Object[]{TypeInfo.parseDynArrayElementText(base.substring(open + 1, close)), path + "[]"}).iterator(), null});
                }
                continue;
            }
            if (hub.model.interfaces.containsKey(base)) {
                // an interface-typed value can hold any implementer, so every struct that implements it counts (whole program)
                List<Object[]> implementers = new ArrayList<>();
                for (List<ImplInfo> implList : hub.model.implsByConcreteType.values()) {
                    for (ImplInfo impl : implList) {
                        if (base.equals(impl.interfaceName)) {
                            implementers.add(new Object[]{new TypeInfo(null, "imut", impl.concreteName), path + "<" + impl.concreteName + ">"});
                        }
                    }
                }
                frames.push(new Object[]{implementers.iterator(), null});
                continue;
            }
            StructInfo inner = hub.model.structs.get(base);
            if (inner == null || !visiting.add(base)) {
                continue;
            }
            List<Object[]> members = new ArrayList<>();
            for (Map.Entry<String, TypeInfo> member : inner.members.entrySet()) {
                members.add(new Object[]{member.getValue(), path + "." + member.getKey()});
            }
            frames.push(new Object[]{members.iterator(), base});
        }
        return null;
    }

    /** Rejects recursive structs (cycle of any length, any member storage), after monomorphization. A plain nullable `ref` member is not an edge. See docs/TypeChecker.md, "Recursive structs". */
    public void validateNoRecursiveStructs() {
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        for (String structName : hub.model.structs.keySet()) {
            if (!visited.contains(structName)) {
                detectStructCycle(structName, visited, onStack);
            }
        }
    }

    public void detectStructCycle(String rootName, Set<String> visited, Set<String> onStack) {
        // frames: {struct name, its info, iterator over its members}
        Deque<Object[]> frames = new ArrayDeque<>();
        visited.add(rootName);
        onStack.add(rootName);
        StructInfo rootInfo = hub.model.structs.get(rootName);
        frames.push(new Object[]{rootName, rootInfo, rootInfo.members.entrySet().iterator()});
        while (!frames.isEmpty()) {
            Object[] fr = frames.peek();
            String structName = (String) fr[0];
            StructInfo info = (StructInfo) fr[1];
            @SuppressWarnings("unchecked")
            Iterator<Map.Entry<String, TypeInfo>> members = (Iterator<Map.Entry<String, TypeInfo>>) fr[2];
            if (!members.hasNext()) {
                onStack.remove(structName);
                frames.pop();
                continue;
            }
            Map.Entry<String, TypeInfo> member = members.next();
            TypeInfo memberType = member.getValue();
            if ("ref".equals(memberType.storage) && !memberType.isSome) {
                continue; // a plain (nullable) `ref` member may point back at its own struct: a borrow never owns, drops or clones what it points at
            }
            String memberBaseType = member.getValue().baseType;
            String referencedStruct = hub.isArrayType(memberBaseType) ? hub.arrayElementType(memberBaseType) : memberBaseType;
            if (!hub.model.structs.containsKey(referencedStruct)) {
                continue;
            }
            if (onStack.contains(referencedStruct)) {
                throw new CompilerException("type", info.declTok.file, info.declTok.line,
                        "recursive struct detected: '" + structName + "'s member '" + member.getKey()
                                + "' (directly or transitively) refers back to '" + structName
                                + "' -- recursive structs are not allowed, including through pointers "
                                + "and indirectly through other structs");
            }
            if (!visited.contains(referencedStruct)) {
                visited.add(referencedStruct);
                onStack.add(referencedStruct);
                StructInfo next = hub.model.structs.get(referencedStruct);
                frames.push(new Object[]{referencedStruct, next, next.members.entrySet().iterator()});
            }
        }
    }

    /** Runs `requireNoAutoReturn` over every function and impl method, once all declarations are collected. */
    public void validateNoAutoReturns() {
        for (List<FuncInfo> overloads : hub.model.functions.values()) {
            for (FuncInfo f : overloads) {
                requireNoAutoReturn(f);
            }
        }
        for (List<ImplInfo> impls : hub.model.implsByConcreteType.values()) {
            for (ImplInfo impl : impls) {
                for (FuncInfo f : impl.methods.values()) {
                    requireNoAutoReturn(f);
                }
                for (FuncInfo f : impl.staticMethods.values()) {
                    requireNoAutoReturn(f);
                }
            }
        }
    }

    public boolean reachesUnsafe(FuncInfo f, Map<FuncInfo, List<FuncInfo>> callees, Map<FuncInfo, Boolean> cache) {
        return reachesWhere(f, callees, cache, true);
    }

    private boolean isReachBase(FuncInfo fn, boolean unsafeBase) {
        return unsafeBase ? fn.safetyTag.equals("unsafe") : hub.model.funcsCallingResizeDirectly.contains(fn);
    }

    /**
     * Memoised "can f reach a function satisfying the base test (`unsafeBase`: safety tag unsafe, else calls resize directly)": a function still being explored counts as false (a cycle with no such function in it
     * never reaches one), exactly as the recursive form did, so results do not depend on anything but the visiting order.
     */
    public boolean reachesWhere(FuncInfo f, Map<FuncInfo, List<FuncInfo>> callees, Map<FuncInfo, Boolean> cache,
            boolean unsafeBase) {
        Boolean cachedRoot = cache.get(f);
        if (cachedRoot != null) {
            return cachedRoot;
        }
        // frames: {function, callee iterator, result so far (Boolean[1])}
        Deque<Object[]> frames = new ArrayDeque<>();
        cache.put(f, false);
        boolean rootResult = isReachBase(f, unsafeBase);
        frames.push(new Object[]{f, callees.getOrDefault(f, Collections.emptyList()).iterator(), new boolean[]{rootResult}});
        Boolean childResult = null;
        boolean last = false;
        while (!frames.isEmpty()) {
            Object[] fr = frames.peek();
            FuncInfo cur = (FuncInfo) fr[0];
            @SuppressWarnings("unchecked")
            Iterator<FuncInfo> it = (Iterator<FuncInfo>) fr[1];
            boolean[] result = (boolean[]) fr[2];
            if (childResult != null) {
                if (childResult) {
                    result[0] = true;
                }
                childResult = null;
            }
            if (!result[0] && it.hasNext()) {
                FuncInfo callee = it.next();
                Boolean cached = cache.get(callee);
                if (cached != null) {
                    childResult = cached;
                } else {
                    cache.put(callee, false);
                    frames.push(new Object[]{callee, callees.getOrDefault(callee, Collections.emptyList()).iterator(), new boolean[]{isReachBase(callee, unsafeBase)}});
                }
                continue;
            }
            cache.put(cur, result[0]);
            frames.pop();
            childResult = result[0];
            last = result[0];
        }
        return last;
    }

    /** Calling, from a `for i in dynarrayExpr{}` body, any function that may `resize` is barred outright (conservative: no alias analysis). See docs/TypeChecker.md, "Whole-program call-graph checks". */
    public void checkDynArrayForLoopResizeSafety() {
        Map<FuncInfo, List<FuncInfo>> callees = new HashMap<>();
        for (CallEdge e : hub.model.callEdges) {
            List<FuncInfo> calleeList = callees.get(e.caller);
            if (calleeList == null) {
                calleeList = new ArrayList<>();
                callees.put(e.caller, calleeList);
            }
            calleeList.add(e.callee);
        }
        Map<FuncInfo, Boolean> reachesResizeCache = new HashMap<>();
        for (CallEdge e : hub.model.callEdges) {
            if (!e.insideDynArrayForLoop) {
                continue;
            }
            if (hub.model.funcsCallingResizeDirectly.contains(e.callee)
                    || reachesResize(e.callee, callees, reachesResizeCache)) {
                throw new CompilerException("type", e.at.file, e.at.line,
                        "'" + e.caller.name + "' calls '" + e.callee.name + "' from inside a "
                                + "\"for i in dynarrayExpr{...}\" loop's own body, but '" + e.callee.name
                                + "' calls (or transitively calls) 'resize' -- resizing a dynarray "
                                + "(even a different one, conservatively) while iterating one is not allowed");
            }
        }
    }

    public boolean reachesResize(FuncInfo f, Map<FuncInfo, List<FuncInfo>> callees, Map<FuncInfo, Boolean> cache) {
        return reachesWhere(f, callees, cache, false);
    }
}

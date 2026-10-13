package caspien.typechecker;
import caspien.token.TokenCloner;
import caspien.config.CompilerException;
import caspien.config.FsPolicy;
import caspien.token.Token;
import caspien.token.TokenType;
import caspien.rpnconverter.RpnConverter;
import caspien.treebuilder.TreeBuilder;

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

/** TypeCheckerImpls: impls, libraries, decorator and purity rules, externs and exports (part 3 of 11; see README.md in this folder). */
public final class TypeCheckerImpls {
    private final TypeChecker hub;

    TypeCheckerImpls(TypeChecker hub) {
        this.hub = hub;
    }

    /** Registers "impl [I for] C{...}". See docs/TypeChecker.md, "Impl collection" for the rules. */
    public void collectImpl(Token t, String safetyTag) {
        String interfaceName = t.sub.size() == 2 ? t.sub.get(0).text : null;
        String concreteName = t.sub.size() == 2 ? t.sub.get(1).text : t.sub.get(0).text;

        boolean concreteIsStruct = hub.model.structs.containsKey(concreteName);
        boolean concreteIsEnum = hub.model.enums.containsKey(concreteName);
        if (!concreteIsStruct && !concreteIsEnum) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + concreteName + "' is not a known struct or enum");
        }
        TypeCheckerModel.InterfaceInfo interfaceInfo = null;
        if (interfaceName != null) {
            interfaceInfo = hub.model.interfaces.get(interfaceName);
            if (interfaceInfo == null) {
                throw new CompilerException("type", t.file, t.line,
                        "'" + interfaceName + "' is not a known interface");
            }
            if (concreteIsEnum) {
                throw new CompilerException("type", t.file, t.line,
                        "cannot implement an interface for an enum ('" + concreteName + "')");
            }
        }

        TypeCheckerModel.ImplInfo implInfo = new TypeCheckerModel.ImplInfo();
        implInfo.interfaceName = interfaceName;
        implInfo.concreteName = concreteName;
        implInfo.declTok = t;
        implInfo.safetyTag = safetyTag;
        validateDecorators(t.decorators, TypeCheckerModel.IMPL_DECORATORS, "an 'impl'");
        implInfo.isPublic = getDecorator(t.decorators, "pub") != null;

        // Residual-generic templates are held back in genericMethodTemplates; their tokens are removed from t.childs
        // after the loop (BytecodeEmitter walks t.childs).
        List<Token> genericTemplateTokensToRemove = new ArrayList<>();
        boolean droppedUnavailable = false;
        for (Token methodTok : t.childs) {
            // methodTok is a gathered 'func' token: sub = [nameTok, paramsTok, ...returnTypeTokens].
            if (methodTok.isConstructorDecl) {
                // Generic constructor wrapped by Parser.gatherConstructorImpl: registered as an ordinary overload in
                // `functions` (like collectFunc) but left in t.childs for BytecodeEmitter. See docs/TypeChecker.md,
                // "Impl collection".
                TypeCheckerModel.FuncInfo ctorInfo = new TypeCheckerModel.FuncInfo();
                ctorInfo.funcToken = methodTok;
                try {
                    hub.signatures.buildFuncInfo(methodTok, ctorInfo);
                } catch (TypeCheckerModel.DeferredSignatureException e) {
                    hub.declarations.recordUnavailable(methodTok.declName().text, "constructor", concreteName, true, e);
                    genericTemplateTokensToRemove.add(methodTok);
                    droppedUnavailable = true;
                    continue;
                }
                registerConstructorFuncInfo(methodTok, ctorInfo);
                ctorInfo.declOrder = hub.model.pendingDeclOrder.getOrDefault(t, Integer.MAX_VALUE);
                ctorInfo.safetyTag = safetyTag;
                if (hub.model.externs.containsKey(ctorInfo.name)) {
                    throw new CompilerException("type", methodTok.file, methodTok.line,
                            "'" + ctorInfo.name + "' collides with an already-declared 'extern "
                                    + ctorInfo.name + "'");
                }
                if (hub.model.constExprs.containsKey(ctorInfo.name)) {
                    throw new CompilerException("type", methodTok.file, methodTok.line,
                            "'" + ctorInfo.name + "' collides with an already-declared 'const "
                                    + ctorInfo.name + "'");
                }
                List<TypeCheckerModel.FuncInfo> ctorOverloads = hub.model.functions.computeIfAbsent(ctorInfo.name, k -> new ArrayList<>());
                for (TypeCheckerModel.FuncInfo existing : ctorOverloads) {
                    if (hub.signatures.sameSignature(existing, ctorInfo)) {
                        throw new CompilerException("type", methodTok.file, methodTok.line,
                                "constructor '" + t.sub.get(t.sub.size() - 1).text
                                        + "' with this parameter signature is already declared");
                    }
                }
                ctorOverloads.add(ctorInfo);
                hub.model.genericConstructorFuncInfos.add(ctorInfo);
                continue;
            }
            if (methodTok.typeParams != null) {
                // Residual-generic method template (own type params): held back, instantiated per call site by an
                // explicit ":<U>".
                if (methodTok.isStaticMethod) {
                    // Only instance methods are supported; a static one would need handling in checkStaticMethodCall.
                    throw new CompilerException("type", methodTok.file, methodTok.line,
                            "a 'static' method cannot declare its own residual type parameter(s) yet -- "
                                    + "only an instance method's own residual generics are supported");
                }
                String genericMethodName = methodTok.declName().text;
                if (implInfo.genericMethodTemplates.containsKey(genericMethodName)) {
                    throw new CompilerException("type", methodTok.file, methodTok.line,
                            "'" + concreteName + "' already has a method named '"
                                    + genericMethodName + "' in this impl block");
                }
                if (interfaceInfo != null && interfaceInfo.methods.containsKey(genericMethodName)) {
                    throw new CompilerException("type", methodTok.file, methodTok.line,
                            "'" + genericMethodName + "' is a residual-generic method of this impl's own "
                                    + "(uses an impl-only type parameter), and cannot also be the name of "
                                    + "an interface method '" + interfaceName + "' requires");
                }
                implInfo.genericMethodTemplates.put(genericMethodName, methodTok);
                genericTemplateTokensToRemove.add(methodTok);
                continue;
            }
            if (methodTok.isStaticMethod) {
                // A static method cannot mention 'self' (signature or body).
                hub.declarations.forbidSelfReference(methodTok, "a static method");
            } else {
                // 'self' is replaced by the concrete type name before buildFuncInfo.
                substituteSelfInPlace(methodTok.paramsTok().childs, concreteName);
                substituteSelfInPlace(methodTok.returnTypeTokens(), concreteName);
            }

            if (interfaceInfo == null) {
                // A bare impl has no interface: @default/@overrides/@realizes do not apply.
                for (String forbidden : new String[]{"default", "overrides", "realizes"}) {
                    Token.Decorator bad = getDecorator(methodTok.decorators, forbidden);
                    if (bad != null) {
                        throw new CompilerException("type", bad.file, bad.line,
                                "'@" + forbidden + "' only applies to a method implementing an "
                                        + "interface -- '" + concreteName + "' has no 'for InterfaceName' "
                                        + "clause on this impl");
                    }
                }
            }

            TypeCheckerModel.FuncInfo info = new TypeCheckerModel.FuncInfo();
            info.funcToken = methodTok;
            try {
                hub.signatures.buildFuncInfo(methodTok, info);
            } catch (TypeCheckerModel.DeferredSignatureException e) {
                hub.declarations.recordUnavailable(concreteName + "." + methodTok.declName().text, methodTok.declName().text,
                        concreteName, false, e);
                genericTemplateTokensToRemove.add(methodTok);
                droppedUnavailable = true;
                continue;
            }
            info.enclosingImpl = implInfo;
            info.declOrder = hub.model.pendingDeclOrder.getOrDefault(t, Integer.MAX_VALUE);
            info.safetyTag = safetyTag;
            // Static methods get "static_" in the mangled name so they cannot collide with an instance method of the
            // same name.
            String methodNamePart = methodTok.isStaticMethod ? "static_" + info.name : info.name;
            info.mangledName = interfaceName != null
                    ? concreteName + "__" + interfaceName + "_" + methodNamePart
                    : concreteName + "_" + methodNamePart;
            hub.declarations.validateMethodLocks(methodTok.decorators, info.paramNames, info.paramTypes, concreteName);

            registerImplMethod(implInfo, info, methodTok, concreteName, interfaceInfo != null);
        }
        t.childs.removeAll(genericTemplateTokensToRemove);

        if (interfaceInfo != null) {
            for (Map.Entry<String, TypeCheckerModel.RawMethodSig> entry : interfaceInfo.methods.entrySet()) {
                String methodName = entry.getKey();
                TypeCheckerModel.RawMethodSig ifaceSig = entry.getValue();
                boolean ifaceIsStatic = ifaceSig.isStatic;
                LinkedHashMap<String, TypeCheckerModel.FuncInfo> implMap = ifaceIsStatic ? implInfo.staticMethods : implInfo.methods;
                if (!implMap.containsKey(methodName)) {
                    // An omitted method is an error unless the interface gave it a @default body (materialized below).
                    if (ifaceSig.isDefault) {
                        continue;
                    }
                    throw new CompilerException("type", t.file, t.line,
                            "impl of '" + interfaceName + "' for '" + concreteName + "' is missing "
                                    + (ifaceIsStatic ? "static " : "") + "method '" + methodName + "'");
                }
            }
            for (Map.Entry<String, TypeCheckerModel.FuncInfo> entry : implInfo.methods.entrySet()) {
                String methodName = entry.getKey();
                TypeCheckerModel.RawMethodSig ifaceSig = interfaceInfo.methods.get(methodName);
                if (ifaceSig == null || ifaceSig.isStatic) {
                    throw new CompilerException("type", t.file, t.line,
                            "impl of '" + interfaceName + "' for '" + concreteName + "' declares '"
                                    + methodName + "', which is not part of the interface (an impl "
                                    + "must match the interface's method set exactly)");
                }
                validateExactSignatureMatch(ifaceSig, entry.getValue(), concreteName, interfaceName);
                validateOverridesRealizes(entry.getValue(), ifaceSig, concreteName, interfaceName);
                requireIdenticalLocks(entry.getValue(), ifaceSig, concreteName, interfaceName);
                requirePublicInterfaceImpl(entry.getValue(), methodName, concreteName, interfaceName);
            }
            for (Map.Entry<String, TypeCheckerModel.FuncInfo> entry : implInfo.staticMethods.entrySet()) {
                String methodName = entry.getKey();
                TypeCheckerModel.RawMethodSig ifaceSig = interfaceInfo.methods.get(methodName);
                if (ifaceSig == null || !ifaceSig.isStatic) {
                    throw new CompilerException("type", t.file, t.line,
                            "impl of '" + interfaceName + "' for '" + concreteName + "' declares static '"
                                    + methodName + "', which is not part of the interface's own static "
                                    + "method set (an impl must match the interface's method set exactly)");
                }
                validateExactSignatureMatch(ifaceSig, entry.getValue(), concreteName, interfaceName);
                validateOverridesRealizes(entry.getValue(), ifaceSig, concreteName, interfaceName);
                requireIdenticalLocks(entry.getValue(), ifaceSig, concreteName, interfaceName);
                requirePublicInterfaceImpl(entry.getValue(), methodName, concreteName, interfaceName);
            }

            // Materialize omitted @default methods last, after every written method has been validated.
            for (Map.Entry<String, TypeCheckerModel.RawMethodSig> entry : interfaceInfo.methods.entrySet()) {
                String methodName = entry.getKey();
                TypeCheckerModel.RawMethodSig ifaceSig = entry.getValue();
                LinkedHashMap<String, TypeCheckerModel.FuncInfo> implMap = ifaceSig.isStatic ? implInfo.staticMethods : implInfo.methods;
                if (!implMap.containsKey(methodName) && ifaceSig.isDefault) {
                    materializeDefaultMethod(implInfo, concreteName, interfaceName, methodName, ifaceSig,
                            safetyTag, t);
                }
            }
        }

        // An impl made only of constructors is not a "bare impl block" for the one-bare-impl rule.
        if (droppedUnavailable && t.childs.isEmpty()) {
            return; // every method of this impl instance was unavailable for this type argument
        }
        boolean constructorsOnlyImpl = interfaceName == null && !t.childs.isEmpty();
        for (Token ctorCandidate : t.childs) {
            if (!ctorCandidate.isConstructorDecl) {
                constructorsOnlyImpl = false;
                break;
            }
        }
        if (constructorsOnlyImpl) {
            return;
        }
        List<TypeCheckerModel.ImplInfo> existingImpls = hub.model.implsByConcreteType.computeIfAbsent(concreteName, k -> new ArrayList<>());
        for (TypeCheckerModel.ImplInfo existing : existingImpls) {
            if (interfaceName == null && existing.interfaceName == null) {
                throw new CompilerException("type", t.file, t.line,
                        "'" + concreteName + "' already has a bare 'impl' block");
            }
            if (interfaceName != null && interfaceName.equals(existing.interfaceName)) {
                throw new CompilerException("type", t.file, t.line,
                        "'" + concreteName + "' already implements '" + interfaceName + "'");
            }
            for (Map.Entry<String, TypeCheckerModel.FuncInfo> entry : implInfo.methods.entrySet()) {
                TypeCheckerModel.FuncInfo mine = entry.getValue();
                for (TypeCheckerModel.FuncInfo existingMethod : existing.candidates(mine.name, false)) {
                    if (hub.signatures.sameSignature(existingMethod, mine)) {
                        throw new CompilerException("type", t.file, t.line,
                                "method '" + mine.name + "' for '" + concreteName + "' is ambiguous: "
                                        + "both '" + describeImplSource(existing) + "' and '"
                                        + describeImplSource(implInfo) + "' declare a method with the same "
                                        + "name and parameter signature, which cannot be resolved at the "
                                        + "call site");
                    }
                }
            }
            for (Map.Entry<String, TypeCheckerModel.FuncInfo> entry : implInfo.staticMethods.entrySet()) {
                TypeCheckerModel.FuncInfo mine = entry.getValue();
                for (TypeCheckerModel.FuncInfo existingMethod : existing.candidates(mine.name, true)) {
                    if (hub.signatures.sameSignature(existingMethod, mine)) {
                        throw new CompilerException("type", t.file, t.line,
                                "static method '" + mine.name + "' for '" + concreteName + "' is ambiguous: "
                                        + "both '" + describeImplSource(existing) + "' and '"
                                        + describeImplSource(implInfo) + "' declare a static method with the "
                                        + "same name and parameter signature, which cannot be resolved at the "
                                        + "call site");
                    }
                }
            }
        }
        existingImpls.add(implInfo);
    }

    /**
     * Materializes a callable entry for an interface @default method that this impl omits: the default body is
     * deep-cloned per impl and none of the user-impl validations apply. See docs/TypeChecker.md, "Default
     * interface methods".
     */
    public void materializeDefaultMethod(TypeCheckerModel.ImplInfo implInfo, String concreteName, String interfaceName,
            String methodName, TypeCheckerModel.RawMethodSig ifaceSig, String safetyTag, Token implDeclTok) {
        // The default body was never RPN-converted or tree-built (RpnConverter/TreeBuilder skip "interface"), so wrap
        // the clone and convert it here.
        Token methodTok = deepCloneCheckedTree(ifaceSig.defaultBodyTok);
        Token wrapLine = new Token(TokenType.LINE, "line", methodTok.line, methodTok.file);
        wrapLine.childs.add(methodTok);
        List<Token> wrapList = new ArrayList<>();
        wrapList.add(wrapLine);
        new RpnConverter().convertLines(wrapList);
        new TreeBuilder().buildLines(wrapList);
        if (!methodTok.isStaticMethod) {
            substituteSelfInPlace(methodTok.paramsTok().childs, concreteName);
            substituteSelfInPlace(methodTok.returnTypeTokens(), concreteName);
        }
        TypeCheckerModel.FuncInfo info = new TypeCheckerModel.FuncInfo();
        info.funcToken = methodTok;
        hub.signatures.buildFuncInfo(methodTok, info);
        // Must be public to satisfy the interface; forced because the @default source need not carry @pub.
        info.isPublic = true;
        info.enclosingImpl = implInfo;
        info.declOrder = hub.model.pendingDeclOrder.getOrDefault(implDeclTok, Integer.MAX_VALUE);
        info.safetyTag = safetyTag;
        String methodNamePart = methodTok.isStaticMethod ? "static_" + info.name : info.name;
        info.mangledName = concreteName + "__" + interfaceName + "_" + methodNamePart;
        hub.declarations.validateMethodLocks(methodTok.decorators, info.paramNames, info.paramTypes, concreteName);
        LinkedHashMap<String, TypeCheckerModel.FuncInfo> targetMap = methodTok.isStaticMethod
                ? implInfo.staticMethods : implInfo.methods;
        targetMap.put(info.name, info);
        // BytecodeEmitter emits impl methods by walking the impl token's childs (matching FuncInfo by Token identity),
        // so the cloned token must be appended there or the method is never emitted.
        implDeclTok.childs.add(methodTok);
    }

    /**
     * Deep-clones a gathered, tree-built, not yet type-checked Token tree (see TokenCloner.syntax; Token.deepCloneRaw has a narrower field set).
     */
    public Token deepCloneCheckedTree(Token src) {
        return TokenCloner.syntax(src);
    }

    /**
     * Builds a library's own function set (self-less; overloaded by full signature, unlike functions and impl
     * methods). See docs/TypeChecker.md, "Libraries".
     */
    public void collectLibrary(Token t, String safetyTag) {
        String name = t.declName().text;
        TypeCheckerModel.LibraryInfo info = hub.model.libraries.get(name);
        info.ownFunctions = new LinkedHashMap<>();
        for (Token funcTok : t.childs) {
            hub.declarations.forbidSelfReference(funcTok, "a library function");
            for (String forbidden : new String[]{"default", "overrides", "realizes"}) {
                Token.Decorator bad = getDecorator(funcTok.decorators, forbidden);
                if (bad != null) {
                    throw new CompilerException("type", bad.file, bad.line,
                            "'@" + forbidden + "' is only valid on an interface method or an impl method, "
                                    + "not a library function");
                }
            }
            TypeCheckerModel.FuncInfo fi = new TypeCheckerModel.FuncInfo();
            fi.funcToken = funcTok;
            hub.signatures.buildFuncInfo(funcTok, fi);
            fi.enclosingLibrary = info;
            fi.declOrder = hub.model.pendingDeclOrder.getOrDefault(t, Integer.MAX_VALUE);
            fi.safetyTag = safetyTag;
            fi.mangledName = name + "_" + fi.name + "__sig_" + signatureSuffix(fi.paramTypes);

            List<TypeCheckerModel.FuncInfo> overloads = info.ownFunctions.computeIfAbsent(fi.name, k -> new ArrayList<>());
            for (TypeCheckerModel.FuncInfo existing : overloads) {
                if (hub.signatures.sameSignature(existing, fi)) {
                    throw new CompilerException("type", funcTok.file, funcTok.line,
                            "library '" + name + "' already declares a function named '" + fi.name
                                    + "' with this parameter signature");
                }
            }
            overloads.add(fi);
        }
    }

    /**
     * Registers a method into implInfo. A bare impl allows overloads by signature (unique mangled name
     * "<first>__sig_<signature>"; the first keeps its name). An interface impl cannot overload. See
     * docs/TypeChecker.md, "Impl method overloading".
     */
    public void registerImplMethod(TypeCheckerModel.ImplInfo implInfo, TypeCheckerModel.FuncInfo info, Token methodTok, String concreteName,
            boolean hasInterface) {
        boolean isStatic = methodTok.isStaticMethod;
        LinkedHashMap<String, TypeCheckerModel.FuncInfo> targetMap = isStatic ? implInfo.staticMethods : implInfo.methods;
        LinkedHashMap<String, List<TypeCheckerModel.FuncInfo>> overloadMap =
                isStatic ? implInfo.staticMethodOverloads : implInfo.methodOverloads;
        String kind = isStatic ? "static " : "";
        List<TypeCheckerModel.FuncInfo> existing = overloadMap.get(info.name);
        if (existing == null && targetMap.containsKey(info.name)) {
            existing = new ArrayList<>();
            existing.add(targetMap.get(info.name));
        }
        if (existing == null) {
            targetMap.put(info.name, info);
            List<TypeCheckerModel.FuncInfo> fresh = new ArrayList<>();
            fresh.add(info);
            overloadMap.put(info.name, fresh);
            return;
        }
        if (hasInterface) {
            throw new CompilerException("type", methodTok.file, methodTok.line,
                    "'" + concreteName + "' already has a " + kind + "method named '" + info.name
                            + "' in this impl block");
        }
        for (TypeCheckerModel.FuncInfo other : existing) {
            if (hub.signatures.sameSignature(other, info)) {
                throw new CompilerException("type", methodTok.file, methodTok.line,
                        "'" + concreteName + "' already has a " + kind + "method named '" + info.name
                                + "' with this parameter signature in this impl block");
            }
        }
        info.mangledName = info.mangledName + "__sig_" + signatureSuffix(info.paramTypes);
        targetMap.put(info.name + "#" + signatureSuffix(info.paramTypes), info);
        if (!overloadMap.containsKey(info.name)) {
            overloadMap.put(info.name, existing);
        }
        existing.add(info);
    }

    /**
     * Parameter-type suffix for overload mangled names; always appended for library functions so same-named
     * overloads never collide at FUNC_START/CALL.
     */
    public String signatureSuffix(List<TypeCheckerModel.TypeInfo> paramTypes) {
        if (paramTypes.isEmpty()) {
            return "void";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) {
                sb.append('_');
            }
            sb.append(paramTypes.get(i).canonical());
        }
        // The suffix ends up in an assembly label; a parameter type such
        // as owns_mut_dynarray(u64) contains characters no assembler
        // accepts in a symbol, so anything outside [A-Za-z0-9_] becomes '_'.
        return sb.toString().replaceAll("[^A-Za-z0-9_]", "_");
    }

    /**
     * Flattens a library's function set: multiple extends, only a full-signature collision is an error, functions
     * inherited by reference. See docs/TypeChecker.md, "Libraries".
     */
    public void flattenLibrary(String rootName, Set<String> visiting) {
        // frames: {library name, iterator over its extended libraries (null before first entry), parent just flattened and not yet merged}
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{rootName, null, null});
        while (!stack.isEmpty()) {
            Object[] f = stack.peek();
            String name = (String) f[0];
            TypeCheckerModel.LibraryInfo info = hub.model.libraries.get(name);
            if (f[1] == null) {
                if (info == null || info.flattened) {
                    stack.pop();
                    continue;
                }
                if (visiting.contains(name)) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "circular inheritance involving library '" + name + "'");
                }
                visiting.add(name);
                f[1] = info.extendsNames.iterator();
            }
            if (f[2] != null) {
                TypeCheckerModel.LibraryInfo parentInfo = hub.model.libraries.get((String) f[2]);
                f[2] = null;
                for (Map.Entry<String, List<TypeCheckerModel.FuncInfo>> e : parentInfo.functions.entrySet()) {
                    mergeLibraryOverloads(info, name, e.getKey(), e.getValue());
                }
            }
            @SuppressWarnings("unchecked")
            Iterator<String> parents = (Iterator<String>) f[1];
            if (parents.hasNext()) {
                String parentName = parents.next();
                if (hub.model.libraries.get(parentName) == null) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "'" + parentName + "' is not a known library");
                }
                f[2] = parentName;
                stack.push(new Object[]{parentName, null, null});
                continue;
            }
            for (Map.Entry<String, List<TypeCheckerModel.FuncInfo>> e : info.ownFunctions.entrySet()) {
                mergeLibraryOverloads(info, name, e.getKey(), e.getValue());
            }
            info.flattened = true;
            visiting.remove(name);
            stack.pop();
        }
    }

    /**
     * Merges one function name's overloads into `info.functions`, rejecting only a full-signature collision (see
     * flattenLibrary).
     */
    public void mergeLibraryOverloads(TypeCheckerModel.LibraryInfo info, String name, String funcName, List<TypeCheckerModel.FuncInfo> incoming) {
        List<TypeCheckerModel.FuncInfo> existing = info.functions.computeIfAbsent(funcName, k -> new ArrayList<>());
        for (TypeCheckerModel.FuncInfo candidate : incoming) {
            for (TypeCheckerModel.FuncInfo already : existing) {
                if (hub.signatures.sameSignature(already, candidate)) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "library '" + name + "' inherits a function named '" + funcName
                                    + "' with this exact parameter signature from more than one extended "
                                    + "library -- rename one of them or drop the conflicting 'extends'");
                }
            }
            existing.add(candidate);
        }
    }

    /**
     * Library visibility, mirroring checkImplMethodVisibility: the declaring library must be @pub (or in the same
     * file), then the function must be public or called from the same library.
     */
    public void requireLibraryVisibility(TypeCheckerModel.FuncInfo candidate, String funcName, Token at, TypeCheckerModel.FuncInfo callerFunc) {
        TypeCheckerModel.LibraryInfo declLib = candidate.enclosingLibrary;
        String declFile = candidate.funcToken.file;
        boolean sameFile = declFile.equals(at.file);
        if (!declLib.isPublic && !sameFile) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + declLib.name + "' is not '@pub' -- not accessible outside the file it's declared "
                            + "in");
        }
        if (candidate.isPublic) {
            return;
        }
        if (callerFunc == null || callerFunc.enclosingLibrary != declLib) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + funcName + "' is private -- it can only be referenced by another function within "
                            + "that same library");
        }
    }

    public TypeCheckerModel.TypeInfo checkLibraryCall(Token op, String libName, String funcName, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        CheckFrame f = new CheckFrame(CheckFrame.LIBRARY_CALL, op, scope, func);
        f.s1 = libName;
        f.s2 = funcName;
        return hub.driver.run(f);
    }

    public int stepLibraryCall(CheckFrame f) {
        Token op = f.node;
        TypeCheckerModel.Scope scope = f.scope;
        TypeCheckerModel.FuncInfo func = f.func;
        String libName = f.s1;
        String funcName = f.s2;
        if (f.phase == 0) {

            TypeCheckerModel.LibraryInfo lib = hub.model.libraries.get(libName);
            List<TypeCheckerModel.FuncInfo> overloads = lib.functions.get(funcName);
            if (overloads == null) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + libName + "' has no function named '" + funcName + "'");
            }

            f.data = overloads;
            f.toks = new ArrayList<>();
            Token argsDelineator = op.right;
            if (!argsDelineator.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(argsDelineator.childs.get(0), f.toks);
            }
            f.phase = 1;
            return f.callAll(f.toks);
        }
        @SuppressWarnings("unchecked")
        List<TypeCheckerModel.FuncInfo> overloads = (List<TypeCheckerModel.FuncInfo>) f.data;
        return f.done(libraryCallTail(op, scope, func, libName, funcName, overloads, f.toks, f.gotFrame.list));
    }

    private TypeCheckerModel.TypeInfo libraryCallTail(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, String libName, String funcName,
            List<TypeCheckerModel.FuncInfo> overloads, List<Token> argNodes, List<TypeCheckerModel.TypeInfo> argTypes) {

        for (TypeCheckerModel.FuncInfo candidate : hub.orderByLiteralExactness(overloads, argTypes)) {
            if (candidate.paramTypes.size() != argTypes.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < argTypes.size(); i++) {
                if (!hub.argSatisfiesParam(candidate.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                hub.retagLiteralArgs(candidate.paramTypes, argTypes, argNodes);
                requireLibraryVisibility(candidate, funcName, op, func);
                op.chk.resolvedCallTarget = candidate.mangledName;
                op.chk.resolvedCallConvention = candidate.callConvention;
                hub.controlFlow.requireThrowsWrapping(candidate, op);
                for (int i = 0; i < argNodes.size(); i++) {
                    hub.expressions.markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
                }
                // Per-call-site destruct list, as in checkThrow (BytecodeEmitter uses it only when the program uses
                // throw).
                op.chk.destructOnExit = hub.statements.collectOwnsToDestruct(scope, scope.functionRootScope, null);
                op.chk.unlockOnExit = hub.statements.collectLockReleasesToBoundary(scope, scope.functionRootScope);
                if (candidate == func && scope.suspendsSelfRecursion) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot call '" + func.name + "' from within its own base-case match "
                                    + "block -- the func loses its recursive privileges there");
                }
                if (candidate == func && getDecorator(func.funcToken.decorators, "recursive") != null) {
                    hub.statements.requireRecursiveCallReturnSiteOnly(func, op);
                    hub.statements.requireRecursiveCallWithinProof(func, op, argNodes, argTypes, scope);
                }
                requirePureCalleeIfPure(func, candidate, op);
                hub.model.callEdges.add(new TypeCheckerModel.CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
                return candidate.returnType;
            }
        }
        throw new CompilerException("type", op.file, op.line,
                "'" + libName + "' has no function '" + funcName + "' matching argument types ("
                        + hub.locksAndProofs.describeTypes(argTypes) + ")");
        }


    public String describeImplSource(TypeCheckerModel.ImplInfo info) {
        return info.interfaceName != null
                ? "impl " + info.interfaceName + " for " + info.concreteName
                : "impl " + info.concreteName;
    }

    public void substituteSelfInPlace(List<Token> tokens, String concreteName) {
        for (Token t : tokens) {
            if (t.type == TokenType.VARREF && t.text.equals("self")) {
                t.text = concreteName;
            }
        }
    }

    /** self-substituted copy, used only for re-resolving the interface's own raw signature at impl-validation time -- doesn't mutate the interface's own tokens. */
    public List<Token> substituteSelfCopy(List<Token> tokens, String concreteName) {
        List<Token> result = new ArrayList<>();
        for (Token t : tokens) {
            if (t.type == TokenType.VARREF && t.text.equals("self")) {
                result.add(new Token(TokenType.VARREF, concreteName, t.line, t.file));
            } else {
                result.add(t);
            }
        }
        return result;
    }

    /** Every method satisfying an interface must be @pub (interface members are public). */
    public void requirePublicInterfaceImpl(TypeCheckerModel.FuncInfo implMethod, String methodName, String concreteName,
            String interfaceName) {
        if (!implMethod.isPublic) {
            throw new CompilerException("type", implMethod.funcToken.file, implMethod.funcToken.line,
                    "'" + concreteName + "'s '" + methodName + "' implements interface '" + interfaceName
                            + "', so it must be '@pub'");
        }
    }

    /**
     * Each interface-impl method must carry exactly one of @overrides (the interface method is @default) or
     * @realizes (it is a plain signature).
     */
    public void validateOverridesRealizes(TypeCheckerModel.FuncInfo implMethod, TypeCheckerModel.RawMethodSig interfaceMethod,
            String concreteName, String interfaceName) {
        List<Token.Decorator> decorators = implMethod.funcToken.decorators;
        boolean hasOverrides = hasDecorator(decorators, "overrides");
        boolean hasRealizes = hasDecorator(decorators, "realizes");
        Token at = implMethod.funcToken;
        if (hasOverrides && hasRealizes) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + concreteName + "'s '" + implMethod.name + "' cannot be labelled both "
                            + "'@overrides' and '@realizes'");
        }
        if (interfaceMethod.isDefault) {
            if (hasRealizes) {
                throw new CompilerException("type", at.file, at.line,
                        "'@realizes' is for fulfilling a plain interface method -- '" + interfaceName
                                + "'s '" + implMethod.name + "' is '@default'; use '@overrides' instead");
            }
            if (!hasOverrides) {
                throw new CompilerException("type", at.file, at.line,
                        "'" + concreteName + "'s '" + implMethod.name + "' overrides a '@default' method "
                                + "of '" + interfaceName + "' and must be labelled '@overrides'");
            }
        } else {
            if (hasOverrides) {
                throw new CompilerException("type", at.file, at.line,
                        "'@overrides' is for overriding a '@default' method -- '" + interfaceName + "'s '"
                                + implMethod.name + "' is a plain signature; use '@realizes' instead");
            }
            if (!hasRealizes) {
                throw new CompilerException("type", at.file, at.line,
                        "'" + concreteName + "'s '" + implMethod.name + "' realizes '" + interfaceName
                                + "'s '" + implMethod.name + "' and must be labelled '@realizes'");
            }
        }
    }

    /**
     * An interface method's "@lock(match ...)" qualifiers must be repeated exactly, in order, by each implementing
     * method; "in"-style clauses match by parameter position, not name.
     */
    public void requireIdenticalLocks(TypeCheckerModel.FuncInfo actual, TypeCheckerModel.RawMethodSig ifaceSig, String concreteName,
            String interfaceName) {
        List<Token.Decorator> ifaceLocks = filterQualifiedLockDecorators(ifaceSig.decorators);
        List<Token.Decorator> implLocks = filterQualifiedLockDecorators(actual.funcToken.decorators);
        Token at = actual.funcToken;
        if (ifaceLocks.size() != implLocks.size()) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + concreteName + "'s '" + actual.name + "' must carry exactly " + ifaceLocks.size()
                            + " '@lock(match ...)' qualifier(s) to match '" + interfaceName + "'s own "
                            + "declaration -- found " + implLocks.size());
        }
        List<String> ifaceParamNames = new ArrayList<>();
        for (List<Token> group : hub.signatures.splitIntoRawGroups(ifaceSig.paramsTok.childs)) {
            ifaceParamNames.add(group.get(0).text);
        }
        for (int idx = 0; idx < ifaceLocks.size(); idx++) {
            Token.Decorator ifaceLock = ifaceLocks.get(idx);
            Token.Decorator implLock = implLocks.get(idx);
            String mismatch = describeLockMismatch(ifaceLock, implLock, ifaceParamNames, actual.paramNames);
            if (mismatch != null) {
                throw new CompilerException("type", at.file, at.line,
                        "'" + concreteName + "'s '" + actual.name + "' own '@lock(...)' #" + (idx + 1)
                                + " doesn't match '" + interfaceName + "'s declaration: " + mismatch);
            }
        }
    }

    /** Every "@lock(match ...)" decorator (either qualified shape) on `decorators`, in source order -- excludes the plain, argument-less "@lock" (the @guard method decorator), which `requireIdenticalLocks` has nothing to say about. */
    public List<Token.Decorator> filterQualifiedLockDecorators(List<Token.Decorator> decorators) {
        List<Token.Decorator> result = new ArrayList<>();
        if (decorators == null) {
            return result;
        }
        for (Token.Decorator d : decorators) {
            if (d.name.equals("lock") && (d.lockFieldName != null || d.lockInParams != null)) {
                result.add(d);
            }
        }
        return result;
    }

    /** Null if `ifaceLock` and `implLock` describe the identical requirement; otherwise a short, human-readable reason they don't. */
    public String describeLockMismatch(Token.Decorator ifaceLock, Token.Decorator implLock,
            List<String> ifaceParamNames, List<String> implParamNames) {
        if (ifaceLock.lockFieldName != null) {
            if (implLock.lockFieldName == null) {
                return "the interface's own qualifier is enum-style ('match self.x : X'), the "
                        + "implementation's own isn't";
            }
            if (!ifaceLock.lockFieldName.equals(implLock.lockFieldName)
                    || !ifaceLock.lockVariants.equals(implLock.lockVariants)) {
                return "expected 'match self." + ifaceLock.lockFieldName + " : "
                        + String.join("|", ifaceLock.lockVariants) + "'";
            }
            return null;
        }
        if (implLock.lockInParams == null) {
            return "the interface's own qualifier is 'in'-style, the implementation's own isn't";
        }
        if (ifaceLock.lockInParams.size() != implLock.lockInParams.size()) {
            return "expected " + ifaceLock.lockInParams.size() + " 'in'-clause(s), got "
                    + implLock.lockInParams.size();
        }
        for (int j = 0; j < ifaceLock.lockInParams.size(); j++) {
            int ifacePos = ifaceParamNames.indexOf(ifaceLock.lockInParams.get(j));
            int implPos = implParamNames.indexOf(implLock.lockInParams.get(j));
            // The operator must match too ("in" vs "within").
            if (ifacePos != implPos || !ifaceLock.lockInFields.get(j).equals(implLock.lockInFields.get(j))
                    || !ifaceLock.lockInOperators.get(j).equals(implLock.lockInOperators.get(j))) {
                return "clause " + (j + 1) + " must be on the same parameter (by position), the same "
                        + "field, and the same operator as the interface's own '"
                        + ifaceLock.lockInOperators.get(j) + " self." + ifaceLock.lockInFields.get(j) + "'";
            }
            // Some(...)-wrapping must match exactly (no superset/subset relation).
            boolean ifaceSome = ifaceLock.lockInSomeWrapped != null
                    && Boolean.TRUE.equals(ifaceLock.lockInSomeWrapped.get(j));
            boolean implSome = implLock.lockInSomeWrapped != null
                    && Boolean.TRUE.equals(implLock.lockInSomeWrapped.get(j));
            if (ifaceSome != implSome) {
                return "clause " + (j + 1) + " must match the interface's own use of 'Some(...)' exactly -- "
                        + "the interface's own clause is "
                        + (ifaceSome ? "'Some(...)'-wrapped" : "not 'Some(...)'-wrapped")
                        + ", the implementation's own isn't the same";
            }
        }
        return null;
    }

    /**
     * True when `t` is a struct with a swap-tagged mutex field: the only kind of global/static safe code may touch
     * directly.
     */
    public boolean isSwapLockStructType(TypeCheckerModel.TypeInfo t) {
        TypeCheckerModel.StructInfo structInfo = hub.model.structs.get(t.baseType);
        return structInfo != null && structInfo.swapFieldName != null;
    }

    /**
     * True for a direct (optionally mut/imut-wrapped) "atomic" operator, i.e. a new atomic declaration, as opposed
     * to an expression that merely resolves to an atomic type (reading one yields an ordinary non-atomic local).
     */
    public boolean isDirectAtomicDeclaration(Token expr) {
        Token t = expr;
        while (t.type == TokenType.OPERATOR && t.unary && (t.text.equals("mut") || t.text.equals("imut"))) {
            t = t.left;
        }
        return t.type == TokenType.OPERATOR && t.text.equals("atomic");
    }

    /**
     * A global or static local can only be touched (read or written) outside `unsafe` code when its own type is an atomic primitive
     * (`isAtomic`, and then only from `unsafe atomic{}`) or a struct declaring a 'swap'-tagged atomic mutex-state field
     * (`isSwapLockStructType`, touched through its own `match @lock`).
     */
    public void requireSharedStateOrUnsafe(TypeCheckerModel.TypeInfo t, String name, TypeCheckerModel.Scope scope, Token at) {
        if (t.isAtomic) {
            // An atomic primitive: each access is one atomic step but program correctness cannot be checked, so every
            // access needs an audited `unsafe atomic{}`.
            if (!hub.declarations.unsafeBypass(scope, "atomic")) {
                throw new CompilerException("type", at.file, at.line,
                        "'" + name + "' is an atomic -- outside a lock it can only be read, written or swapped from 'unsafe atomic{...}' code; "
                                + "shared state in safe code belongs in a struct with a 'swap'-tagged mutex-state field, touched through 'match @lock'");
            }
            return;
        }
        if (!isSwapLockStructType(t) && !hub.declarations.unsafeBypass(scope, "global")) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + name + "' is a global/static variable -- in safe code, only a struct with a 'swap'-tagged atomic mutex-state "
                            + "field (touched through its own 'match @lock') can be accessed without 'unsafe' code");
        }
    }

    /**
     * A @pure function may not read mutable globals/statics (imut ones are fine); @pure(rt) may read none. Unsafe
     * code is not exempt. See docs/TypeChecker.md, "Purity".
     */
    public void requirePureDoesNotReadMutableStatic(TypeCheckerModel.TypeInfo t, String name, TypeCheckerModel.FuncInfo func, Token at) {
        if (isPureRtFunc(func)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '@pure(rt)' and cannot read '" + name
                            + "' -- a global/static variable is memory the arguments do not determine");
        }
        if (isPureFunc(func) && "mut".equals(t.mutability)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '@pure' and cannot read '" + name
                            + "' -- it is a mutable global/static variable");
        }
    }

    public void validateExactSignatureMatch(TypeCheckerModel.RawMethodSig rawSig, TypeCheckerModel.FuncInfo actual, String concreteName,
            String interfaceName) {
        List<List<Token>> rawParamGroups = hub.signatures.splitIntoRawGroups(rawSig.paramsTok.childs);
        if (rawParamGroups.size() != actual.paramTypes.size()) {
            throw new CompilerException("type", actual.funcToken.file, actual.funcToken.line,
                    "method '" + actual.name + "' in impl of '" + interfaceName + "' for '" + concreteName
                            + "' has " + actual.paramTypes.size() + " parameter(s), interface declares "
                            + rawParamGroups.size());
        }
        for (int i = 0; i < rawParamGroups.size(); i++) {
            List<Token> group = rawParamGroups.get(i);
            List<Token> typeTokens = substituteSelfCopy(group.subList(2, group.size()), concreteName);
            TypeCheckerModel.TypeInfo expected = hub.signatures.resolveTypeAnnotation(typeTokens, group.get(0));
            TypeCheckerModel.TypeInfo got = actual.paramTypes.get(i);
            if (!expected.canonical().equals(got.canonical())) {
                throw new CompilerException("type", actual.funcToken.file, actual.funcToken.line,
                        "method '" + actual.name + "' parameter " + (i + 1) + " must be exactly '"
                                + expected.canonical() + "' to match interface '" + interfaceName
                                + "', got '" + got.canonical() + "'");
            }
        }
        List<Token> returnTypeTokens = substituteSelfCopy(rawSig.returnTypeTokens, concreteName);
        TypeCheckerModel.TypeInfo expectedReturn = returnTypeTokens.isEmpty()
                ? new TypeCheckerModel.TypeInfo(null, "imut", "void")
                : hub.signatures.resolveTypeAnnotation(returnTypeTokens, rawSig.nameTok, true);
        if (!expectedReturn.canonical().equals(actual.returnType.canonical())) {
            throw new CompilerException("type", actual.funcToken.file, actual.funcToken.line,
                    "method '" + actual.name + "' must return exactly '" + expectedReturn.canonical()
                            + "' to match interface '" + interfaceName + "', got '"
                            + actual.returnType.canonical() + "'");
        }
    }
    /** `@drop` marks a struct's cleanup function: the drop glue calls it with the struct's address just before the struct (an `owns` value) is freed, so a handle type can close what it holds. It takes exactly one parameter, `ref some mut S` for a struct S, returns void and does not throw. */
    public void requireDropHookShape(Token t, TypeCheckerModel.FuncInfo info, List<Token> returnTypeTokens) {
        TypeCheckerModel.TypeInfo p = info.paramTypes.size() == 1 ? info.paramTypes.get(0) : null;
        boolean shapeOk = p != null && "ref".equals(p.storage) && p.isSome && hub.model.structs.containsKey(p.baseType);
        if (!shapeOk || info.isThrows || info.isAsync) {
            throw new CompilerException("type", t.file, t.line,
                    "'@drop' function '" + info.name + "' must take exactly one parameter of type 'ref some mut <struct>', must not be '@throws' or '@async', and returns void");
        }
        String prior = hub.model.dropHookOwners.put(p.baseType, info.name);
        if (prior != null && !prior.equals(info.name)) {
            throw new CompilerException("type", t.file, t.line,
                    "struct '" + p.baseType + "' already has a '@drop' function ('" + prior + "')");
        }
    }

    /** `--fs-report`: what the program may do to the file system, as seen by the compiler. */
    public void printFsReport(java.io.PrintStream out) {
        FsPolicy p = FsPolicy.current;
        out.println("[fs-report] roots: " + (p.rootsEnforced ? (p.roots.isEmpty() ? "(none: every path open is refused)" : p.runtimeRootsText()) : "(no fs-roots in fs.config: unrestricted)"));
        out.println("[fs-report] denied externs: " + (p.denyExterns.isEmpty() ? "(none)" : String.join(", ", p.denyExterns)));
        for (String l : hub.model.fsReportLines) {
            out.println("[fs-report] " + l);
        }
        out.println("[fs-report] not covered: 'unsafe asm' (raw syscalls), 'call(...)' function pointers, extern names not in the deny list, and the C library functions the stdlib itself links.");
    }

    /** Checks the file-system rules of a call to a static method carrying '@fs_root' (literal path, checked against fs-roots) or '@fs_unsafe' (needs 'unsafe file'). */
    public void checkFsPolicyAtCall(TypeCheckerModel.FuncInfo callee, Token op, List<Token> argNodes, TypeCheckerModel.Scope scope) {
        Token.Decorator root = getDecorator(callee.funcToken.decorators, "fs_root");
        if (root != null) {
            boolean rw = !root.args.isEmpty() && root.args.get(0).equals("rw");
            Token a0 = argNodes.isEmpty() ? null : argNodes.get(0);
            if (a0 != null && a0.type == TokenType.STRING) {
                String why = FsPolicy.current.checkLiteralRoot(a0.literalValue, rw);
                if (why != null) {
                    throw new CompilerException("type", op.file, op.line,
                            "'" + callee.name + "' cannot open this path: " + why);
                }
                hub.model.fsReportLines.add(op.file + ":" + op.line + " opens \"" + a0.literalValue + "\" " + (rw ? "read-write" : "read-only"));
            } else {
                throw new CompilerException("type", op.file, op.line,
                        "'" + callee.name + "' needs a string literal path inside an fs-roots entry; for a computed path use Root.check(path) "
                                + "to get a checked RootPath, or 'unsafe file{ Dir.rootAny(path) }'");
            }
        }
        if (getDecorator(callee.funcToken.decorators, "fs_unsafe") != null) {
            if (!hub.declarations.unsafeBypass(scope, "file")) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + callee.name + "' opens an unchecked path and needs 'unsafe file{ ... }'");
            }
            hub.model.fsReportLines.add(op.file + ":" + op.line + " UNSAFE unchecked path via " + callee.name);
        }
    }
    /** True when the type text names (directly, through struct members, or as a dynarray element) a struct that has a `@drop` hook. */
    public boolean typeReachesDropHook(String baseType, Set<String> seen) {
        if (baseType == null) {
            return false;
        }
        Deque<Iterator<String>> its = new ArrayDeque<>();
        its.push(Arrays.asList(baseType.split("[^A-Za-z0-9_]+")).iterator());
        while (!its.isEmpty()) {
            Iterator<String> it = its.peek();
            if (!it.hasNext()) {
                its.pop();
                continue;
            }
            String id = it.next();
            if (id.isEmpty() || !seen.add(id)) {
                continue;
            }
            if (hub.model.dropHookOwners.containsKey(id)) {
                return true;
            }
            TypeCheckerModel.StructInfo si = hub.model.structs.get(id);
            if (si != null) {
                List<String> memberIds = new ArrayList<>();
                for (TypeCheckerModel.TypeInfo m : si.members.values()) {
                    if (m.baseType != null) {
                        memberIds.addAll(Arrays.asList(m.baseType.split("[^A-Za-z0-9_]+")));
                    }
                }
                its.push(memberIds.iterator());
            }
        }
        return false;
    }

    public boolean hasDecorator(List<Token.Decorator> decorators, String name) {
        return getDecorator(decorators, name) != null;
    }

    public Token.Decorator getDecorator(List<Token.Decorator> decorators, String name) {
        if (decorators == null) {
            return null;
        }
        for (Token.Decorator d : decorators) {
            if (d.name.equals(name)) {
                return d;
            }
        }
        return null;
    }

    /**
     * The "@call_convention(name)" argument if present (already validated), else compiler.config's default. Shared
     * by func and extern collection.
     */
    public String resolveCallConvention(List<Token.Decorator> decorators) {
        Token.Decorator d = getDecorator(decorators, "call_convention");
        return d != null ? d.args.get(0) : hub.model.config.defaultConvention;
    }

    public boolean isPureFunc(TypeCheckerModel.FuncInfo f) {
        return f != null && f.funcToken != null && getDecorator(f.funcToken.decorators, "pure") != null;
    }

    /** `@pure(rt)`: referentially transparent (the argument was validated to be exactly `rt`). */
    public boolean isPureRtFunc(TypeCheckerModel.FuncInfo f) {
        if (f == null || f.funcToken == null) {
            return false;
        }
        Token.Decorator d = getDecorator(f.funcToken.decorators, "pure");
        return d != null && !d.args.isEmpty();
    }

    /** Things a plain `@pure` function (and therefore a `@pure(rt)` one) may not do: unsafe code, the `match @lock` spin lock, threads. Compiler-synthesized unsafe blocks never reach this. */
    public void requireNotPure(TypeCheckerModel.FuncInfo func, Token at, String what, String why) {
        if (isPureFunc(func)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '" + TypeCheckerModel.pureKind(isPureRtFunc(func)) + "' and cannot use " + what + " -- " + why);
        }
    }

    /** Things only a `@pure(rt)` function may not do: allocation, randomness, reading through a pointer. A plain `@pure` function may (they are non-deterministic, not side effects). */
    public void requireNotPureRt(TypeCheckerModel.FuncInfo func, Token at, String what, String why) {
        if (isPureRtFunc(func)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '@pure(rt)' (referentially transparent) and cannot use " + what + " -- " + why);
        }
    }

    /** A `@pure(rt)` function never holds a pointer: no `ref`/`raw`/`owns`/`auto`/`static` typed value and no dynarray (reading through one depends on memory the arguments do not determine). */
    public void requirePureRtNoPointer(TypeCheckerModel.TypeInfo t, Token at, TypeCheckerModel.FuncInfo func, String where) {
        if (t != null && (t.storage != null || t.dynArrayElementType != null)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '@pure(rt)' (referentially transparent) and cannot have a pointer or dynamic array ('"
                            + t.canonical() + "') " + where + " -- it may not read memory through pointers");
        }
    }

    /**
     * Per-edge rule (not transitive): a @pure caller may only call @pure callees, a @pure(rt) caller only
     * @pure(rt) ones. Checked even in unsafe code. See docs/TypeChecker.md, "Purity".
     */
    public void requirePureCalleeIfPure(TypeCheckerModel.FuncInfo caller, TypeCheckerModel.FuncInfo callee, Token at) {
        if (isPureFunc(caller) && !isPureFunc(callee)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + caller.name + "' is '" + TypeCheckerModel.pureKind(isPureRtFunc(caller)) + "' and can only call other '@pure' functions -- '"
                            + callee.name + "' is not");
        }
        if (isPureRtFunc(caller) && !isPureRtFunc(callee)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + caller.name + "' is '@pure(rt)' and can only call other '@pure(rt)' functions -- '"
                            + callee.name + "' is only '@pure'" + (callee.funcToken != null && getDecorator(callee.funcToken.decorators, "non") != null ? " and '@non(deterministic)'" : ""));
        }
    }

    /**
     * Checks each decorator is a real one, allowed in this context, with the right argument shape. "@dont(X)" is
     * valid exactly where X is.
     */
    public void validateDecorators(List<Token.Decorator> decorators, Set<String> allowedNames, String contextDesc) {
        if (decorators == null) {
            return;
        }
        for (Token.Decorator d : decorators) {
            if (d.name.equals("dont")) {
                if (d.args.size() != 1) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@dont' takes exactly one argument (par/unroll/inline)");
                }
                String target = d.args.get(0);
                if (!allowedNames.contains(target)) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@dont(" + target + ")' is not valid on " + contextDesc);
                }
                continue;
            }
            if (!allowedNames.contains(d.name)) {
                throw new CompilerException("type", d.file, d.line,
                        "'@" + d.name + "' is not a valid decorator on " + contextDesc);
            }
            if (d.name.equals("lock") && d.lockFieldName == null && d.lockInParams == null) {
                throw new CompilerException("type", d.file, d.line,
                        "a bare '@lock' method decorator has been removed (it belonged to '@guard'); write '@lock(match self.field : VARIANT)' "
                                + "or '@lock(match x in self.field)'");
            }
            validateDecoratorArgShape(d);
        }
        Token.Decorator pureDec = getDecorator(decorators, "pure");
        Token.Decorator nonDec = getDecorator(decorators, "non");
        if (pureDec != null && !pureDec.args.isEmpty() && nonDec != null) {
            throw new CompilerException("type", nonDec.file, nonDec.line,
                    "'@pure(rt)' (referentially transparent) cannot also be '@non(deterministic)'");
        }
    }

    public void validateDecoratorArgShape(Token.Decorator d) {
        switch (d.name) {
            case "call_convention":
                if (d.args.size() != 1) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@call_convention' takes exactly one argument, e.g. '@call_convention(win64)'");
                }
                if (d.argIsString.get(0)) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@call_convention' takes a bare name, not a string -- '@call_convention("
                                    + d.args.get(0) + ")', not '@call_convention(\"" + d.args.get(0) + "\")'");
                }
                // Accepted names are read live from compiler.config.
                if (!hub.model.config.callingConventions.containsKey(d.args.get(0))) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@call_convention(" + d.args.get(0) + ")' is not a calling convention declared in "
                                    + "compiler.config (known: " + hub.model.config.callingConventions.keySet() + ")");
                }
                break;
            case "link_name":
                // "@link_name(realCName)": extern escape hatch for a C symbol that is not a valid Caspien identifier
                // (e.g. "sleep", now a keyword). A bare word, not a string. See docs/TypeChecker.md, "Decorators".
                if (d.args.size() != 1) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@link_name' takes exactly one argument, e.g. '@link_name(sleep)'");
                }
                if (d.argIsString.get(0)) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@link_name' takes a bare name, not a string -- '@link_name(sleep)', not "
                                    + "'@link_name(\"sleep\")'");
                }
                break;
            case "pure":
                // "@pure" (side-effect free) or "@pure(rt)" (referentially transparent: also no pointer reads, no statics, no allocation, no non-determinism)
                if (d.args.size() > 1 || (d.args.size() == 1 && (d.argIsString.get(0) || !d.args.get(0).equals("rt")))) {
                    throw new CompilerException("type", d.file, d.line, "'@pure' takes nothing or 'rt' ('@pure(rt)', referentially transparent)");
                }
                break;
            case "non":
                if (d.args.size() != 1 || d.argIsString.get(0) || !d.args.get(0).equals("deterministic")) {
                    throw new CompilerException("type", d.file, d.line, "'@non' takes exactly 'deterministic': '@non(deterministic)'");
                }
                break;
            case "fs_root":
                if (d.args.size() > 1 || (d.args.size() == 1 && !d.args.get(0).equals("rw"))) {
                    throw new CompilerException("type", d.file, d.line, "'@fs_root' takes nothing (read-only) or 'rw'");
                }
                break;
            case "unroll":
                // "@unroll" (unroll fully) or "@unroll(N)" (partial unroll by factor N, 2..64)
                if (d.args.size() > 1) {
                    throw new CompilerException("type", d.file, d.line, "'@unroll' takes nothing or one factor, e.g. '@unroll(4)'");
                }
                if (d.args.size() == 1) {
                    String a = d.args.get(0);
                    boolean digits = !d.argIsString.get(0) && !a.isEmpty() && a.length() <= 3 && a.chars().allMatch(Character::isDigit);
                    if (!digits || Integer.parseInt(a) < 2 || Integer.parseInt(a) > 64) {
                        throw new CompilerException("type", d.file, d.line,
                                "'@unroll(N)' needs a whole-number factor from 2 to 64, found '" + a + "'");
                    }
                }
                break;
            case "reads":
            case "writes":
                if (d.args.isEmpty()) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@" + d.name + "' requires at least one argument (all/self/others/globals)");
                }
                for (String a : d.args) {
                    if (!TypeCheckerModel.READS_WRITES_ARGS.contains(a)) {
                        throw new CompilerException("type", d.file, d.line,
                                "'@" + d.name + "' argument must be one of all/self/others/globals, got '"
                                        + a + "'");
                    }
                }
                break;
            default:
                if (!d.args.isEmpty()) {
                    throw new CompilerException("type", d.file, d.line, "'@" + d.name + "' takes no arguments");
                }
        }
    }

    /**
     * Constructor bookkeeping shared by collectFunc (plain constructor) and collectImpl (monomorphized generic
     * constructor).
     */
    public void registerConstructorFuncInfo(Token t, TypeCheckerModel.FuncInfo info) {
        if (!hub.model.structs.containsKey(t.constructorConcreteName)) {
            throw new CompilerException("type", t.file, t.line,
                    "'impl constructor for " + t.constructorConcreteName + "' -- '"
                            + t.constructorConcreteName + "' is not a declared struct");
        }
        if (!hub.isStructByValueReturn(info.returnType) || !info.returnType.baseType.equals(t.constructorConcreteName)) {
            throw new CompilerException("type", t.file, t.line,
                    "internal error: constructor for '" + t.constructorConcreteName
                            + "' resolved an unexpected return type '" + info.returnType.canonical() + "'");
        }
        info.isConstructor = true;
        info.constructorConcreteName = t.constructorConcreteName;
        info.isPublic = true; // constructors are always callable wherever the struct itself is visible -- no separate '@pub' concept for v1
    }

    public void collectFunc(Token t, String safetyTag) {
        for (String contextOnly : new String[]{"default", "overrides", "realizes"}) {
            Token.Decorator bad = getDecorator(t.decorators, contextOnly);
            if (bad != null) {
                throw new CompilerException("type", bad.file, bad.line,
                        "'@" + contextOnly + "' is only valid on an interface method or an impl method, "
                                + "not a plain top-level function");
            }
        }
        TypeCheckerModel.FuncInfo info = new TypeCheckerModel.FuncInfo();
        info.funcToken = t;
        hub.signatures.buildFuncInfo(t, info);
        if (t.isConstructorDecl) {
            // Constructor: registered as an ordinary overload (name and return type were fixed by
            // Parser.gatherConstructorImpl); shared with collectImpl via registerConstructorFuncInfo.
            registerConstructorFuncInfo(t, info);
        } else if (TypeCheckerModel.BUILTIN_NAMES.contains(info.name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + info.name + "' is a reserved built-in name (sizeof/len/range/deref/clone) "
                            + "and can't be used as a function name");
        }
        info.declOrder = hub.model.pendingDeclOrder.getOrDefault(t, Integer.MAX_VALUE);
        info.safetyTag = safetyTag;

        if (hub.model.externs.containsKey(info.name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + info.name + "' collides with an already-declared 'extern " + info.name
                            + "' -- an extern and an ordinary function can't share a name");
        }
        if (hub.model.constExprs.containsKey(info.name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + info.name + "' collides with an already-declared 'const " + info.name
                            + "' -- a const and a function can't share a name");
        }
        List<TypeCheckerModel.FuncInfo> overloads = hub.model.functions.computeIfAbsent(info.name, k -> new ArrayList<>());
        for (TypeCheckerModel.FuncInfo existing : overloads) {
            if (hub.signatures.sameSignature(existing, info)) {
                throw new CompilerException("type", t.file, t.line,
                        "function '" + info.name + "' with this parameter signature is already declared");
            }
        }
        overloads.add(info);
    }

    /**
     * "extern name(types) ret": signature-only declaration with its own namespace, cross-checked against
     * `functions` both ways (no overloading). See docs/TypeChecker.md, "Externs and exports".
     */
    public void collectExtern(Token t) {
        validateDecorators(t.decorators, TypeCheckerModel.EXTERN_DECORATORS, "an extern");
        Token nameTok = t.declName();
        Token paramsTok = t.paramsTok();
        List<Token> returnTypeTokens = t.returnTypeTokens();
        String name = nameTok.text;

        if (TypeCheckerModel.BUILTIN_NAMES.contains(name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + name + "' is a reserved built-in name (sizeof/len/range/deref/clone) "
                            + "and can't be used as an extern name");
        }
        if (hub.model.externs.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'extern " + name + "' is already declared");
        }
        if (hub.model.functions.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'extern " + name + "' collides with an already-declared function '" + name
                            + "' -- an extern and an ordinary function can't share a name");
        }

        TypeCheckerModel.ExternInfo info = new TypeCheckerModel.ExternInfo();
        info.name = name;
        Token.Decorator linkNameDecorator = getDecorator(t.decorators, "link_name");
        info.linkName = linkNameDecorator != null ? linkNameDecorator.args.get(0) : name;
        info.declTok = t;
        info.callConvention = resolveCallConvention(t.decorators);
        if (FsPolicy.current.denies(name, info.linkName) && !TypeCheckerModel.isStdlibFile(t.file)) {
            throw new CompilerException("type", t.file, t.line,
                    "extern '" + name + "' (links to '" + info.linkName + "') is forbidden by fs-deny-externs in the compiler's fs.config -- "
                            + "use the stdlib's fs API (Dir/File) instead");
        }
        TypeCheckerModel.ExternParamParseResult params = parseExternParamList(paramsTok.childs, t);
        info.paramTypes.addAll(params.fixedTypes);
        info.hasVarargs = params.hasVarargs;
        info.returnType = returnTypeTokens.isEmpty()
                ? new TypeCheckerModel.TypeInfo(null, "imut", "void")
                : hub.signatures.resolveTypeAnnotation(returnTypeTokens, t, true);

        // Same by-pointer-only return rule as ordinary functions.
        if ((hub.model.structs.containsKey(info.returnType.baseType) || hub.model.enums.containsKey(info.returnType.baseType)
                || hub.isArrayType(info.returnType.baseType)) && info.returnType.storage == null) {
            throw new CompilerException("type", t.file, t.line,
                    "'extern " + name + "' returns '" + info.returnType.baseType
                            + "' without a storage modifier -- a struct/enum/array cannot be "
                            + "returned by value, only by pointer (owns/ref/raw/auto/static)");
        }
        hub.model.externs.put(name, info);
    }

    /**
     * Splits an extern's params into bare types (no "name :"); a final '...' marks varargs and is rejected
     * anywhere else.
     */
    public TypeCheckerModel.ExternParamParseResult parseExternParamList(List<Token> flat, Token at) {
        List<List<Token>> groups = hub.signatures.splitIntoRawGroups(flat);
        List<TypeCheckerModel.TypeInfo> types = new ArrayList<>();
        boolean hasVarargs = false;
        for (int i = 0; i < groups.size(); i++) {
            List<Token> group = groups.get(i);
            boolean isVarargsMarker = group.size() == 1 && group.get(0).type == TokenType.OPERATOR
                    && group.get(0).text.equals("...");
            if (isVarargsMarker) {
                if (i != groups.size() - 1) {
                    throw new CompilerException("type", group.get(0).file, group.get(0).line,
                            "'...' must be the last parameter in an 'extern' declaration");
                }
                hasVarargs = true;
                continue;
            }
            types.add(hub.signatures.resolveTypeAnnotation(group, at));
        }
        return new TypeCheckerModel.ExternParamParseResult(types, hasVarargs);
    }

    /** "export foo": foo must be a declared, overload-free, non-generic top-level func. */
    public void collectExport(Token t) {
        Token nameTok = t.declName();
        String name = nameTok.text;
        List<TypeCheckerModel.FuncInfo> overloads = hub.model.functions.get(name);
        if (overloads == null || overloads.isEmpty()) {
            throw new CompilerException("type", t.file, t.line,
                    "'export " + name + "' does not name a declared function");
        }
        if (overloads.size() > 1) {
            throw new CompilerException("type", t.file, t.line,
                    "'export " + name + "' is ambiguous -- '" + name + "' has " + overloads.size()
                            + " overloads, and C has no overloading; only an overload-free function "
                            + "can be exported");
        }
        TypeCheckerModel.FuncInfo info = overloads.get(0);
        if (info.funcToken.typeParams != null && !info.funcToken.typeParams.isEmpty()) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + name + "' is generic and can't be exported -- C has no equivalent of a "
                            + "monomorphized function family");
        }
        if (hub.model.exportedNames.contains(name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + name + "' is already exported");
        }
        hub.model.exportedNames.add(name);
        info.isExported = true;
    }
}

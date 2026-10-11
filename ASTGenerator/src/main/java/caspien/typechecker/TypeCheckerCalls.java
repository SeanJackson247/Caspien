package caspien.typechecker;
import caspien.bytecodeemitter.BytecodeEmitter;
import caspien.parser.Parser;
import caspien.*;

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

/** TypeCheckerCalls: calls, builtins, methods and generic methods (part 8 of 11; see README.md in this folder). */
abstract class TypeCheckerCalls extends TypeCheckerExpressions {
    // methods defined in later parts of the chain
    abstract public String interfaceDispatcherName(String interfaceName, String methodName);
    abstract public TypeInfo checkStaticMethodCall(Token op, String concreteName, String methodName, Scope scope, FuncInfo func, boolean eligibleForRvoHere);
    abstract public boolean requiresAliveProof(TypeInfo t, Token targetExpr, Scope scope);
    abstract public int arrayLength(String baseType);
    abstract public void requireMethodLockProofs(FuncInfo candidate, Token receiverNode, List<Token> argNodes, Scope scope, Token at);



    public TypeInfo checkCall(Token op, Scope scope, FuncInfo func, boolean eligibleForRvoHere) {
        if (op.left.type == TokenType.KEYWORD && op.left.text.equals("sleep")) {
            // 'sleep' is a Lexer keyword, so it resolves to whichever function carries '@sleep'.
            return checkSleepCall(op, scope, func);
        }
        if (op.left.type == TokenType.OPERATOR && op.left.text.equals(".")) {
            return checkMethodCall(op, scope, func, eligibleForRvoHere);
        }
        if (op.left.type != TokenType.VARREF) {
            throw new CompilerException("type", op.file, op.line,
                    "calling through a function-pointer expression is not yet supported by the type checker");
        }
        String funcName = op.left.text;
        boolean wasEventLoopRedirect = false;
        if (funcName.equals("tick") && func != null && hasDecorator(func.funcToken.decorators, "event_loop")) {
            // Inside '@event_loop', a literal `tick(..)` redirects to the function decorated '@tick' (neither has a fixed name).
            List<FuncInfo> tickFuncs = findFuncsWithDecorator("tick");
            if (!tickFuncs.isEmpty()) {
                funcName = tickFuncs.get(0).name;
                wasEventLoopRedirect = true;
                // The stdlib loop writes `? tick(state)`; the wrap is kept only if tick is '@throws', so the two-way rule still holds.
                if (!tickFuncs.get(0).isThrows) {
                    op.insideTry = false;
                }
            }
        }
        if (BUILTIN_NAMES.contains(funcName)) {
            return checkBuiltinCall(funcName, op, scope, func);
        }
        ExternInfo externInfo = externs.get(funcName);
        if (externInfo != null) {
            return checkExternCall(externInfo, op, scope, func);
        }
        List<FuncInfo> overloads = functions.get(funcName);
        boolean isConstructorCallShape = false;
        if (overloads == null && structs.containsKey(funcName)) {
            // `MyClass(..)` resolves against the struct's constructors (CONSTRUCTOR_NAME_PREFIX + name) via the normal overload loop.
            overloads = functions.get(Parser.CONSTRUCTOR_NAME_PREFIX + funcName);
            isConstructorCallShape = true;
        }
        if (overloads == null) {
            throw new CompilerException("type", op.file, op.line,
                    isConstructorCallShape
                            ? "'" + funcName + "' declares no constructor at all"
                            : "call to undeclared function '" + funcName + "'");
        }

        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        List<TypeInfo> argTypes = new ArrayList<>();
        for (Token argNode : argNodes) {
            argTypes.add(resolveExprType(argNode, scope, func));
        }

        for (FuncInfo candidate : orderByLiteralExactness(overloads, argTypes)) {
            if (candidate.paramTypes.size() != argTypes.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < argTypes.size(); i++) {
                if (!argSatisfiesParam(candidate.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                retagLiteralArgs(candidate.paramTypes, argTypes, argNodes);
                if (hasDecorator(candidate.funcToken.decorators, "event_loop")) {
                    // '@event_loop' cannot be called or referenced.
                    throw new CompilerException("type", op.file, op.line,
                            "'" + funcName + "' is '@event_loop' -- it cannot be called or referenced "
                                    + "anywhere in the compilation unit");
                }
                boolean callerIsEventLoop = func != null && hasDecorator(func.funcToken.decorators, "event_loop");
                boolean mainIsWithTick = functions.containsKey("main")
                        && !functions.get("main").isEmpty()
                        && hasDecorator(functions.get("main").get(0).funcToken.decorators, "with_tick");
                boolean isMainOrTick = candidate.name.equals("main")
                        || hasDecorator(candidate.funcToken.decorators, "tick");
                if (mainIsWithTick && isMainOrTick && !callerIsEventLoop) {
                    // With a '@with_tick' main, only the '@event_loop' function may call main or the '@tick' function. Read from `functions`,
                    // because eventLoopFunc is not set until validateTickEventLoop runs at the end of check().
                    throw new CompilerException("type", op.file, op.line,
                            "'" + funcName + "' can only be called from within the '@event_loop' "
                                    + "function");
                }
                boolean privilegedEventLoopCall = wasEventLoopRedirect
                        || (funcName.equals("main") && callerIsEventLoop);
                if (!candidate.isPublic && !candidate.funcToken.file.equals(op.file)
                        && !privilegedEventLoopCall) {
                    // Private function: same file only; main and the '@tick' function are exempt when called from the '@event_loop' body.
                    throw new CompilerException("type", op.file, op.line,
                            "'" + funcName + "' is private -- not accessible outside the file it's "
                                    + "declared in");
                }
                if (candidate.isAsync && op.asyncCallKind == null && !op.isTrampolineOwnCall) {
                    throw new CompilerException("type", op.file, op.line,
                            "'" + funcName + "' is '@async' and can only be called through 'par' or "
                                    + "'await' -- never plainly");
                }
                if (!candidate.isAsync && op.asyncCallKind != null) {
                    throw new CompilerException("type", op.file, op.line,
                            "'" + op.asyncCallKind.toLowerCase() + "' can only be used on an '@async' "
                                    + "function -- '" + funcName + "' isn't one");
                }
                if (op.asyncCallKind != null) {
                    // Pointers (even nested in structs) cannot cross par/await without 'unsafe async'.
                    for (int i = 0; i < argNodes.size(); i++) {
                        if (containsPointerAnywhere(candidate.paramTypes.get(i)) && !unsafeBypass(scope, "async")) {
                            throw new CompilerException("type", argNodes.get(i).file, argNodes.get(i).line,
                                    "passing '" + candidate.paramTypes.get(i).canonical() + "' (a pointer, "
                                            + "or a struct with one nested somewhere inside it) across "
                                            + "'" + op.asyncCallKind.toLowerCase() + "' requires 'unsafe' "
                                            + "code");
                        }
                    }
                }
                op.resolvedCallTarget = candidate.mangledName;
                op.resolvedCallConvention = candidate.callConvention;
                requireThrowsWrapping(candidate, op);
                for (int i = 0; i < argNodes.size(); i++) {
                    markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
                }
                // Per-call-site destruct list, used if the callee throws and unwinds back here (emitted only when the program uses throw).
                op.destructOnExit = collectOwnsToDestruct(scope, scope.functionRootScope, null);
                op.unlockOnExit = collectLockReleasesToBoundary(scope, scope.functionRootScope);
                if (candidate == func && scope.suspendsSelfRecursion) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot call '" + func.name + "' from within its own base-case match "
                                    + "block -- the func loses its recursive privileges there");
                }
                if (candidate == func && getDecorator(func.funcToken.decorators, "recursive") != null) {
                    requireRecursiveCallReturnSiteOnly(func, op);
                    requireRecursiveCallWithinProof(func, op, argNodes, argTypes, scope);
                }
                requirePureCalleeIfPure(func, candidate, op);
                callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
                // Struct-by-value return: legal only at a 'let' RHS or a bare-variable assignment RHS (insideStructRvoDestinationPosition).
                // See docs/TypeChecker.md, "Struct return values (RVO)".
                if (isStructByValueReturn(candidate.returnType)) {
                    if (!eligibleForRvoHere) {
                        throw new CompilerException("type", op.file, op.line,
                                "a call to '" + funcName + "' (which returns '"
                                        + candidate.returnType.baseType + "' by value) may only be used as a "
                                        + "'let' binding's own right-hand side, or as a plain assignment's "
                                        + "right-hand side with a bare-variable target -- not as a function "
                                        + "argument, inside a larger expression, as a 'return' statement's own "
                                        + "expression, or anywhere else a value is otherwise expected; bind it "
                                        + "to a 'let' first");
                    }
                    op.isStructRvoCall = true;
                }
                return candidate.returnType;
            }
        }
        throw new CompilerException("type", op.file, op.line,
                "no overload of '" + funcName + "' matches argument types (" + describeTypes(argTypes) + ")"
                        + unavailableNote(isConstructorCallShape ? Parser.CONSTRUCTOR_NAME_PREFIX + funcName : funcName));
    }

    /** Calling an extern needs `unsafe extern` and is banned in '@pure'. Fixed parameters are checked like an ordinary call; varargs only need a concrete (non-indeterminate) type. */
    public TypeInfo checkExternCall(ExternInfo info, Token op, Scope scope, FuncInfo func) {
        if (!unsafeBypass(scope, "extern")) {
            throw new CompilerException("type", op.file, op.line,
                    "calling extern '" + info.name + "' requires 'unsafe' code");
        }
        if (isPureFunc(func)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + func.name + "' is '@pure' and cannot call extern '" + info.name
                            + "' -- an extern's behavior can never be verified at compile time");
        }

        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        int fixedCount = info.paramTypes.size();
        if (info.hasVarargs ? argNodes.size() < fixedCount : argNodes.size() != fixedCount) {
            throw new CompilerException("type", op.file, op.line,
                    "call to extern '" + info.name + "' requires " + (info.hasVarargs ? "at least " : "exactly ")
                            + fixedCount + " argument(s), got " + argNodes.size());
        }

        for (int i = 0; i < argNodes.size(); i++) {
            Token argNode = argNodes.get(i);
            TypeInfo argType = resolveExprType(argNode, scope, func);
            if (i < fixedCount) {
                TypeInfo expected = info.paramTypes.get(i);
                if (!acceptsAdaptingLiteral(expected, argType, argNode)) {
                    throw new CompilerException("type", argNode.file, argNode.line,
                            "argument " + (i + 1) + " to extern '" + info.name + "' expects '"
                                    + expected.canonical() + "', got '" + argType.canonical() + "'");
                }
                markMovedIfOwned(expected, argNode, scope);
            }
            // Varargs past the fixed parameters: C ignores mutability, so an
            // indeterminate-mutability value (literal, expression) is accepted as is.
        }
        op.resolvedCallTarget = info.linkName; // the real linked symbol -- info.name unless "@link_name" overrides it (see ExternInfo.linkName's own doc comment)
        op.resolvedCallConvention = info.callConvention;
        return info.returnType;
    }

    /** `sleep(...)`: always resolves to the function carrying `@sleep` (ghost-table function registry); fixed arity and types, no overloads. See docs/TypeChecker.md, "Builtin calls". */
    public TypeInfo checkSleepCall(Token op, Scope scope, FuncInfo func) {
        FuncInfo sleepFunc = getGhostTableFunction("sleep");
        if (sleepFunc == null) {
            throw new CompilerException("type", op.file, op.line,
                    "using 'sleep' requires a function decorated '@sleep' to exist somewhere in the "
                            + "compilation unit (none was found)");
        }
        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        int expectedCount = sleepFunc.paramTypes.size();
        if (argNodes.size() != expectedCount) {
            throw new CompilerException("type", op.file, op.line,
                    "'sleep' requires exactly " + expectedCount + " argument(s) (matching the '@sleep'"
                            + "-decorated function's own signature), got " + argNodes.size());
        }
        for (int i = 0; i < argNodes.size(); i++) {
            Token argNode = argNodes.get(i);
            TypeInfo argType = resolveExprType(argNode, scope, func);
            TypeInfo expected = sleepFunc.paramTypes.get(i);
            if (!acceptsAdaptingLiteral(expected, argType, argNode)) {
                throw new CompilerException("type", argNode.file, argNode.line,
                        "argument " + (i + 1) + " to 'sleep' expects '" + expected.canonical() + "', got '"
                                + argType.canonical() + "'");
            }
            markMovedIfOwned(expected, argNode, scope);
        }
        op.resolvedCallTarget = sleepFunc.mangledName;
        op.resolvedCallConvention = sleepFunc.callConvention;
        // Emitted as SLEEP, not CALL; BytecodeEmitter.emitCallOrBuiltin reads the flag.
        op.isSleepCall = true;
        return sleepFunc.returnType;
    }

    /** Dispatches a reserved builtin name to its checker; builtins never use the user-function overload table. */
    public TypeInfo checkBuiltinCall(String name, Token op, Scope scope, FuncInfo func) {
        switch (name) {
            case "sizeof": return checkSizeofBuiltin(op, scope, func);
            case "len": return checkLenBuiltin(op, scope, func);
            case "range": return checkRangeBuiltin(op, scope, func);
            case "deref": return checkDerefBuiltin(op, scope, func);
            case "clone": return checkCloneBuiltin(op, scope, func);
            case "call": return checkCallBuiltin(op, scope, func);
            case "bits_left": case "bits_right": case "bits_rotl": case "bits_rotr": case "bits_or": case "bits_and": case "bits_xor": case "bits_not":
                return checkBitsBuiltin(name, op, scope, func);
            case "memcopy": return checkMemcopyBuiltin(op, scope, func);
            case "dyn": return checkDynBuiltin(op, scope, func);
            case "resize": return checkResizeBuiltin(op, scope, func);
            case "Some": return checkSomeBuiltin(op, scope, func);
            case "insecure_rand": requireNotPureRt(func, op, "'insecure_rand'", "it is non-deterministic"); return checkInsecureRandBuiltin(op);
            case "wrap": case "sat": return checkConvertBuiltin(name, op, scope, func);
            default: throw new IllegalStateException("unreachable: '" + name + "' not in BUILTIN_NAMES");
        }
    }

    /** `Some(x)`: an ordinary bool builtin; checkMatchCondition adds the alive proof when it is a match condition. */
    public TypeInfo checkSomeBuiltin(Token op, Scope scope, FuncInfo func) {
        Token argExpr = collectSingleBuiltinArg(op, "Some", scope, func).get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (argType.storage == null) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'Some' requires a pointer, got '" + argType.canonical() + "'");
        }
        op.someOnRef = "ref".equals(argType.storage) && !argType.isSome;
        return new TypeInfo(null, "indeterminate", "bool");
    }


    /**
     * `wrap:<T>(x)` and `sat:<T>(x)` -- the two TOTAL integer conversions (no `unsafe`, they cannot
     * fail): `wrap` keeps the low bits (modular, like a C cast); `sat` clamps to T's range. Both
     * accept an integer of any width/signedness and yield T (indeterminate mutability).
     * `sat` evaluates its argument more than once in the emitted code, so the argument must be
     * side-effect free (a variable, field chain, or literal, optionally negated).
     */
    public TypeInfo checkConvertBuiltin(String name, Token op, Scope scope, FuncInfo func) {
        List<List<Token>> targs = op.left == null ? null : op.left.genericArgs;
        if (name.equals("wrap") && targs != null && targs.size() == 1 && targs.get(0).size() == 1
                && isFloatType(targs.get(0).get(0).text)) {
            // wrap:<f32>(x) / wrap:<f64>(x): float conversion (f64 -> f32 rounds to nearest; f32 -> f64 is exact).
            String floatTarget = targs.get(0).get(0).text;
            Token floatArg = collectSingleBuiltinArg(op, name, scope, func).get(0);
            TypeInfo floatArgType = resolveExprType(floatArg, scope, func);
            if (floatArgType.storage != null || !isFloatType(floatArgType.baseType)) {
                throw new CompilerException("type", floatArg.file, floatArg.line,
                        "'wrap:<" + floatTarget + ">' converts a float, got '" + floatArgType.canonical() + "'");
            }
            if (floatArgType.floatLiteral) {
                adaptLiteral(floatArg, new TypeInfo(null, floatArgType.mutability, floatTarget));
            } else {
                op.isFloatConvert = true;
            }
            return new TypeInfo(null, "indeterminate", floatTarget);
        }
        if (targs == null || targs.size() != 1 || targs.get(0).size() != 1
                || !INTEGER_TYPES.contains(targs.get(0).get(0).text)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + name + "' needs an integer target type, e.g. '" + name + ":<u8>(x)'");
        }
        String target = targs.get(0).get(0).text;
        Token argExpr = collectSingleBuiltinArg(op, name, scope, func).get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (argType.storage != null || !INTEGER_TYPES.contains(argType.baseType)) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'" + name + "' converts an integer, got '" + argType.canonical() + "'");
        }
        if (name.equals("sat") && !isPureSimpleOperand(argExpr)) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'sat' evaluates its argument several times, so it must be a plain variable, field "
                            + "or literal -- bind a computed value to a 'let' first");
        }
        return new TypeInfo(null, "indeterminate", target);
    }

    public boolean isPureSimpleOperand(Token t) {
        if (t.type == TokenType.VARREF || t.type == TokenType.INTEGER) {
            return true;
        }
        if (t.type == TokenType.OPERATOR && t.text.equals(".") && t.left != null && t.right != null) {
            return isPureSimpleOperand(t.left) && t.right.type == TokenType.VARREF;
        }
        if (t.type == TokenType.OPERATOR && t.text.equals("-") && t.unary && t.left != null) {
            return isPureSimpleOperand(t.left);
        }
        if (t.type == TokenType.OPERATOR && (t.text.equals("mut") || t.text.equals("imut")) && t.left != null) {
            return isPureSimpleOperand(t.left);
        }
        return false;
    }

    public List<Token> collectSingleBuiltinArg(Token op, String name, Scope scope, FuncInfo func) {
        List<Token> args = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        if (args.size() != 1) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + name + "' takes exactly one argument, got " + args.size());
        }
        return args;
    }

    /** `insecure_rand()`: non-crypto u64 (C `rand()`). A compiler builtin, so it needs no `unsafe` and is allowed in `@pure`. See docs/TypeChecker.md, "Builtin calls". */
    public TypeInfo checkInsecureRandBuiltin(Token op) {
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            List<Token> args = new ArrayList<>();
            collectCommaArgs(argsDelineator.childs.get(0), args);
            if (!args.isEmpty()) {
                throw new CompilerException("type", op.file, op.line,
                        "'insecure_rand' takes no arguments, got " + args.size());
            }
        }
        return new TypeInfo(null, "indeterminate", "u64");
    }

    /** `sizeof(T)` or `sizeof(expr)`: for an array, the base element type's size; the result is an indeterminate u64. */
    public TypeInfo checkSizeofBuiltin(Token op, Scope scope, FuncInfo func) {
        Token argExpr = collectSingleBuiltinArg(op, "sizeof", scope, func).get(0);
        TypeInfo argType = tryResolveAsTypeDescriptor(argExpr);
        if (argType == null) {
            argType = resolveExprType(argExpr, scope, func);
        }
        long size = computeSizeOf(argType, op);
        op.builtinConstantValue = size;
        op.sizeofStruct = sizedStructName(argType);
        return new TypeInfo(null, "indeterminate", "u64");
    }

    /**
     * The struct whose laid-out size a `sizeof` of this type means, or null when the size is fully known
     * here (a primitive, a pointer, a range). An array gives its element's struct (sizeof an array is its
     * element's size). Such a size is left symbolic (`SIZEOF name`) for the Optimizer to resolve.
     */
    public String sizedStructName(TypeInfo t) {
        if (t == null || t.storage != null || isRangeType(t)) {
            return null;
        }
        String base = t.baseType;
        while (isArrayType(base)) {
            base = arrayElementType(base);
        }
        return structs.containsKey(base) ? base : null;
    }

    /** Reads a (storage/mutability-prefixed) bare type name as a TypeInfo; null if it is not a known type, so the caller treats it as an expression. */
    public TypeInfo tryResolveAsTypeDescriptor(Token node) {
        String storage = null;
        Token cur = node;
        while (cur.type == TokenType.OPERATOR
                && (cur.text.equals("raw") || cur.text.equals("owns") || cur.text.equals("ref")
                        || cur.text.equals("auto") || cur.text.equals("static")
                        || cur.text.equals("mut") || cur.text.equals("imut"))) {
            if (!cur.text.equals("mut") && !cur.text.equals("imut")) {
                if (storage != null) {
                    return null;
                }
                storage = cur.text;
            }
            cur = cur.left;
        }
        if (cur.type != TokenType.VARREF || !isKnownType(cur.text)) {
            return null;
        }
        return new TypeInfo(storage, "imut", cur.text);
    }

    /** Size of a type: any pointer = POINTER_SIZE, range = 16, array = its base element. See docs/TypeChecker.md, "Builtin calls". */
    public long computeSizeOf(TypeInfo t, Token at) {
        if (t.storage != null) {
            return POINTER_SIZE;
        }
        if (isRangeType(t)) {
            return 16;
        }
        if (isArrayType(t.baseType)) {
            String elem = t.baseType;
            while (isArrayType(elem)) {
                elem = arrayElementType(elem);
            }
            return computeSizeOf(new TypeInfo(null, "imut", elem), at);
        }
        StructInfo si = structs.get(t.baseType);
        if (si != null) {
            // A rough estimate only (the real size, with padding and the layout the Optimizer finally
            // settles on, is resolved by SizeofResolutionPass from the symbolic SIZEOF): the hidden
            // ___type word plus the members, so it is never below 8 and never claims a one-byte stride.
            long sum = 8;
            for (TypeInfo memberType : si.members.values()) {
                sum += computeSizeOf(memberType, at);
            }
            return sum;
        }
        Long primitiveSize = PRIMITIVE_SIZE.get(t.baseType);
        if (primitiveSize != null) {
            return primitiveSize;
        }
        throw new CompilerException("type", at.file, at.line,
                "'sizeof' doesn't know the size of '" + t.canonical() + "'");
    }

    /** `len(x)`: constant for arrays and direct string literals, run-time for dynarrays; the 2-arg form scans an unsafe dynarray. See docs/TypeChecker.md, "len and range". */
    public TypeInfo checkLenBuiltin(Token op, Scope scope, FuncInfo func) {
        List<Token> args = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        if (args.size() == 2) {
            // `len(unsafe_dynarray, terminator)`: unsafe, scans for the terminator; its type must equal the element type (as resize's fill).
            if (!"unsafe".equals(scope.currentSafety)) {
                throw new CompilerException("type", op.file, op.line,
                        "'len' with a terminator argument can only be used from within 'unsafe' code");
            }
            Token targetExpr = args.get(0);
            Token termExpr = args.get(1);
            TypeInfo targetType = resolveExprType(targetExpr, scope, func);
            if (!isUnsafeDynArrayType(targetType)) {
                throw new CompilerException("type", targetExpr.file, targetExpr.line,
                        "'len' with a terminator argument requires an 'unsafe dynarray', got '"
                                + targetType.canonical() + "'");
            }
            unsafeBypass(scope, udynTag(targetType));
            TypeInfo termType = resolveExprType(termExpr, scope, func);
            if (!termType.baseType.equals(targetType.unsafeDynArrayElementType.baseType)) {
                throw new CompilerException("type", termExpr.file, termExpr.line,
                        "'len' terminator must match the unsafe dynarray's own element type ('"
                                + targetType.unsafeDynArrayElementType.baseType + "'), got '"
                                + termType.baseType + "'");
            }
            // builtinConstantValue stays unset: BytecodeEmitter then emits a real scan.
            return new TypeInfo(null, "indeterminate", "u64");
        }
        if (args.size() != 1) {
            throw new CompilerException("type", op.file, op.line,
                    "'len' takes one argument, or two (a terminator, for an 'unsafe dynarray'), got "
                            + args.size());
        }
        Token argExpr = args.get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (isUnsafeDynArrayType(argType)) {
            // A bare 1-arg `len` on an unsafe dynarray has no length to read; a specific error beats the generic one below.
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'len' on an 'unsafe dynarray' requires a terminator argument, e.g. "
                            + "'len(arr, '\\0')' -- it has no hidden length to read otherwise");
        }
        if (isDynArrayType(argType)) {
            // Run-time length: builtinConstantValue stays unset so BytecodeEmitter emits LEN.
            return new TypeInfo(null, "indeterminate", "u64");
        }
        long length;
        if (isArrayType(argType.baseType)) {
            length = arrayLength(argType.baseType);
        } else if (argType.baseType.equals("string")) {
            if (argExpr.type != TokenType.STRING) {
                throw new CompilerException("type", argExpr.file, argExpr.line,
                        "'len' on a string requires a direct string literal -- its length can't be "
                                + "confirmed at compile time otherwise");
            }
            length = argExpr.literalValue.length();
        } else {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'len' requires a string, an array, or a dynarray, got '" + argType.canonical() + "'");
        }
        op.builtinConstantValue = length;
        return new TypeInfo(null, "indeterminate", "u64");
    }

    /** `range(x)`: 0..len for a string literal / fixed array (bounds baked into the type), plain `range` for a safe dynarray, error for an unsafe one. See docs/TypeChecker.md, "len and range". */
    public TypeInfo checkRangeBuiltin(Token op, Scope scope, FuncInfo func) {
        Token argExpr = collectSingleBuiltinArg(op, "range", scope, func).get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (isUnsafeDynArrayType(argType)) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'range' on an 'unsafe dynarray' has no hidden length to read -- it has no "
                            + "terminator argument to scan for one either, unlike 'len'");
        }
        if (isDynArrayType(argType)) {
            // Run-time length: no builtinConstantValue, no baked-in bounds.
            return new TypeInfo(null, "imut", "range");
        }
        long length;
        if (isArrayType(argType.baseType)) {
            length = arrayLength(argType.baseType);
        } else if (argType.baseType.equals("string")) {
            if (argExpr.type != TokenType.STRING) {
                throw new CompilerException("type", argExpr.file, argExpr.line,
                        "'range' on a string requires a direct string literal -- its length can't be "
                                + "confirmed at compile time otherwise");
            }
            length = argExpr.literalValue.length();
        } else {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'range' requires a string, an array, or a dynarray, got '" + argType.canonical() + "'");
        }
        op.builtinConstantValue = length;
        return new TypeInfo(null, "imut", "range(0," + length + ")");
    }

    /** `deref(p)`: the pointee value with storage stripped (usable with `.` directly). */
    public TypeInfo checkDerefBuiltin(Token op, Scope scope, FuncInfo func) {
        requireNotPureRt(func, op, "'deref'", "it reads memory through a pointer");
        Token argExpr = collectSingleBuiltinArg(op, "deref", scope, func).get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (argType.storage == null) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'deref' requires a pointer, got '" + argType.canonical() + "'");
        }
        // Safe code may dereference only a pointer proven non-null and alive:
        // an 'auto' pointer, a 'some'-tagged pointer, or one inside a
        // 'match Some(p)' (the same rule '.' member access uses).
        if (requiresAliveProof(argType, argExpr, scope)) {
            throw new CompilerException("type", op.file, op.line,
                    "'deref' of a pointer requires the pointer to be proven alive -- "
                            + "'auto', 'some'-tagged, inside 'match Some(...)', or vouched for with "
                            + "'assume match Some(...)' in an 'unsafe assume' block [needs: assume match Some(" + exprHint(argExpr) + ")]");
        }
        if ("raw".equals(argType.storage) && !unsafeBypass(scope, "deref")) {
            // A 'raw' pointer may point anywhere (C memory, the stack, the middle of a block), so even a proven one
            // is only as good as the programmer's word: reading through it is 'unsafe' on top of the alive proof.
            throw new CompilerException("type", op.file, op.line,
                    "'deref' of a 'raw' pointer is inherently unsafe -- it needs an 'unsafe deref' block (and the "
                            + "alive proof as well: 'match Some(...)', or 'assume match Some(...)' in an 'unsafe assume' block)");
        }
        return new TypeInfo(null, argType.mutability, argType.baseType, argType.aliasName);
    }

    /** `clone(p)`: any storage accepted; the result is `owns some`, pointee type and mutability kept. */
    public TypeInfo checkCloneBuiltin(Token op, Scope scope, FuncInfo func) {
        requireNotPureRt(func, op, "'clone'", "allocation depends on heap state (it can fail), so the result is not a function of the arguments");
        // Allocates and registers like `new`: same try/catch discipline and same program-wide flags.
        usesOwnsRefDynNew = true;
        usesThrow = true;
        if (!op.insideTry && !insideThrowingAllocContext) {
            throw new CompilerException("type", op.file, op.line,
                    "'clone' can fail (it throws when the underlying allocation fails) and must be "
                            + "wrapped in 'try ... catch { ... }'");
        }
        Token argExpr = collectSingleBuiltinArg(op, "clone", scope, func).get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (argType.storage == null) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'clone' requires a pointer, got '" + argType.canonical() + "'");
        }
        // The source is read, so it is held to the same rule as `deref`: safe code may clone only a pointer proven
        // non-null and alive ('auto', 'some'-tagged, or inside 'match Some'). `clone` itself does no null check
        // on its source.
        if (requiresAliveProof(argType, argExpr, scope)) {
            throw new CompilerException("type", op.file, op.line,
                    "'clone' of a pointer requires the pointer to be proven alive -- "
                            + "'auto', 'some'-tagged, inside 'match Some(...)', or vouched for with "
                            + "'assume match Some(...)' in an 'unsafe assume' block [needs: assume match Some(" + exprHint(argExpr) + ")]");
        }
        if ("raw".equals(argType.storage) && !unsafeBypass(scope, "clone")) {
            // Same as 'deref': a 'raw' pointer's pointee is only as good as the programmer's word, so cloning from
            // one is 'unsafe' on top of the alive proof.
            throw new CompilerException("type", op.file, op.line,
                    "'clone' of a 'raw' pointer is inherently unsafe -- it needs an 'unsafe clone' block (and the "
                            + "alive proof as well: 'match Some(...)', or 'assume match Some(...)' in an 'unsafe assume' block)");
        }
        if (typeReachesDropHook(argType.baseType, new HashSet<>())) {
            throw new CompilerException("type", op.file, op.line,
                    "'clone' of '" + argType.baseType + "' is rejected: it holds a '@drop' resource "
                            + "(a handle cannot be duplicated)");
        }
        // A failed allocation throws, so what comes back is never null: `owns some`, like `new`.
        return argType.withStorage("owns").withSome(true); // keeps dynarray/array shape, mutability and alias
    }

    /** `call(fp, args...)`: variable arity, unsafe only; arity, argument types and result type come from the pointer's recorded signature. */
    public TypeInfo checkCallBuiltin(Token op, Scope scope, FuncInfo func) {
        if (!unsafeBypass(scope, "call")) {
            throw new CompilerException("type", op.file, op.line,
                    "'call' can only be used from within 'unsafe' code");
        }
        // Banned in @pure: the callee's purity cannot be verified at compile time.
        if (isPureFunc(func)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + func.name + "' is '@pure' and cannot use 'call' -- the target's purity can "
                            + "never be verified at compile time");
        }
        List<Token> args = new ArrayList<>();
        if (!op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), args);
        }
        if (args.isEmpty()) {
            throw new CompilerException("type", op.file, op.line,
                    "'call' requires at least a function-pointer argument");
        }
        Token funcPtrExpr = args.get(0);
        TypeInfo funcPtrType = resolveExprType(funcPtrExpr, scope, func);
        if (funcPtrType.funcPtrParamTypes == null) {
            throw new CompilerException("type", funcPtrExpr.file, funcPtrExpr.line,
                    "'call''s first argument must be a function pointer, got '"
                            + funcPtrType.canonical() + "'");
        }
        List<Token> callArgs = args.subList(1, args.size());
        if (callArgs.size() != funcPtrType.funcPtrParamTypes.size()) {
            throw new CompilerException("type", op.file, op.line,
                    "'call' passes " + callArgs.size() + " argument(s), but the function pointer's "
                            + "signature declares " + funcPtrType.funcPtrParamTypes.size());
        }
        for (int i = 0; i < callArgs.size(); i++) {
            TypeInfo argType = resolveExprType(callArgs.get(i), scope, func);
            TypeInfo paramType = funcPtrType.funcPtrParamTypes.get(i);
            if (!acceptsAdaptingLiteral(paramType, argType, callArgs.get(i))) {
                throw new CompilerException("type", callArgs.get(i).file, callArgs.get(i).line,
                        "'call' argument " + (i + 1) + " expects '" + paramType.canonical() + "', got '"
                                + argType.canonical() + "'");
            }
            markMovedIfOwned(paramType, callArgs.get(i), scope);
        }
        op.invokeArgCount = callArgs.size();
        op.resolvedCallConvention = funcPtrType.funcPtrCallConvention;
        return funcPtrType.funcPtrReturnType;
    }

    /** `bits_*` builtins: integer operands only, both sides one type (literals adapt), result that type; `bits_not` is unary. */
    public TypeInfo checkBitsBuiltin(String name, Token op, Scope scope, FuncInfo func) {
        List<Token> args = new ArrayList<>();
        if (!op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), args);
        }
        if (name.equals("bits_not")) {
            // bits_not(a): the complement of `a`, truncated to a's own width (u8: bits_not(0) == 255; signed: two's complement).
            if (args.size() != 1) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + name + "' takes exactly one argument, got " + args.size());
            }
            TypeInfo only = resolveExprType(args.get(0), scope, func);
            if (!INTEGER_TYPES.contains(only.baseType)) {
                throw new CompilerException("type", args.get(0).file, args.get(0).line,
                        "'" + name + "' requires an integer type, got '" + only.canonical() + "'");
            }
            if (only.literalValue != null && !literalFits(only.literalValue, only.baseType)) {
                throw new CompilerException("type", args.get(0).file, args.get(0).line,
                        "'" + name + "': literal " + only.literalValue + " does not fit in '" + only.baseType + "'");
            }
            return new TypeInfo(null, "indeterminate", only.baseType);
        }
        if (args.size() != 2) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + name + "' takes exactly two arguments, got " + args.size());
        }
        TypeInfo leftType = resolveExprType(args.get(0), scope, func);
        TypeInfo rightType = resolveExprType(args.get(1), scope, func);
        if (!INTEGER_TYPES.contains(leftType.baseType)) {
            throw new CompilerException("type", args.get(0).file, args.get(0).line,
                    "'" + name + "' requires an integer type, got '" + leftType.canonical() + "'");
        }
        if (!INTEGER_TYPES.contains(rightType.baseType)) {
            throw new CompilerException("type", args.get(1).file, args.get(1).line,
                    "'" + name + "' requires an integer type, got '" + rightType.canonical() + "'");
        }
        // Literal adaptation, exactly as in arithmetic: an integer literal on one side takes the other side's integer type
        // when its value fits (`bits_and(x_u8, 255)`, `bits_left(x_u8, 1)`, `bits_or(1, x_u8)`). Two literals of different
        // default types (`bits_and(5, -1)`: u64 and s64) unify the same way, whichever one fits adapting to the other.
        // Two typed operands must still be the same type.
        if (!leftType.sameBaseType(rightType)) {
            boolean leftLit = leftType.literalValue != null;
            boolean rightLit = rightType.literalValue != null;
            if (rightLit && literalFits(rightType.literalValue, leftType.baseType)) {
                adaptLiteral(args.get(1), leftType);
                rightType = leftType;
            } else if (leftLit && literalFits(leftType.literalValue, rightType.baseType)) {
                adaptLiteral(args.get(0), rightType);
                leftType = rightType;
            } else if (rightLit) {
                throw new CompilerException("type", args.get(1).file, args.get(1).line,
                        "'" + name + "': literal " + rightType.literalValue + " does not fit in '" + leftType.baseType + "'");
            } else if (leftLit) {
                throw new CompilerException("type", args.get(0).file, args.get(0).line,
                        "'" + name + "': literal " + leftType.literalValue + " does not fit in '" + rightType.baseType + "'");
            }
        }
        if (!leftType.sameBaseType(rightType)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + name + "' requires both sides to be the same integer type, got '"
                            + leftType.canonical() + "' and '" + rightType.canonical() + "'");
        }
        return new TypeInfo(null, "indeterminate", leftType.baseType);
    }

    /** `memcopy(raw dest, u64 bytes, src)`: unsafe only; returns the destination's type. Constructing a `raw` is what is gated, not holding one. */
    public TypeInfo checkMemcopyBuiltin(Token op, Scope scope, FuncInfo func) {
        if (!unsafeBypass(scope, "memcopy")) {
            throw new CompilerException("type", op.file, op.line,
                    "'memcopy' can only be used from within 'unsafe' code");
        }
        List<Token> args = new ArrayList<>();
        if (!op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), args);
        }
        if (args.size() != 3) {
            throw new CompilerException("type", op.file, op.line,
                    "'memcopy' takes exactly three arguments (destination, byte count, source), got "
                            + args.size());
        }
        TypeInfo destType = resolveExprType(args.get(0), scope, func);
        if (!"raw".equals(destType.storage)) {
            throw new CompilerException("type", args.get(0).file, args.get(0).line,
                    "'memcopy''s destination must be a 'raw' pointer, got '" + destType.canonical() + "'");
        }
        TypeInfo countType = resolveExprType(args.get(1), scope, func);
        if (!isPlainU64(countType)) {
            throw new CompilerException("type", args.get(1).file, args.get(1).line,
                    "'memcopy''s byte count must be a plain 'u64', got '" + countType.canonical() + "'");
        }
        TypeInfo sourceType = resolveExprType(args.get(2), scope, func);
        if (sourceType.storage == null) {
            throw new CompilerException("type", args.get(2).file, args.get(2).line,
                    "'memcopy''s source must be a pointer (any storage), got '" + sourceType.canonical() + "'");
        }
        return destType;
    }

    /** `dyn([..])`: a fresh owns dynarray from an array literal (checkArrayLiteral validates it); builtinConstantValue carries the element count to BytecodeEmitter. */
    public TypeInfo checkDynBuiltin(Token op, Scope scope, FuncInfo func) {
        requireNotPureRt(func, op, "'dyn'", "allocation depends on heap state (it can fail), so the result is not a function of the arguments");
        return checkDynBuiltinCore(op, scope, func, false);
    }

    /**
     * Shared by `dyn([..])` and `unsafe dyn([..])` (checkUnsafeDynWrapper cannot delegate to the ordinary dispatch). The safe form allocates, so it needs
     * try/catch like `new` (op.insideTry or insideThrowingAllocContext) and sets usesThrow; the unsafe form is exempt. See docs/TypeChecker.md, "dyn and resize".
     */
    public TypeInfo checkDynBuiltinCore(Token op, Scope scope, FuncInfo func, boolean isUnsafe) {
        newDynDepth++;
        try {
            return checkDynBuiltinCoreInner(op, scope, func, isUnsafe);
        } finally {
            newDynDepth--;
        }
    }

    public TypeInfo checkDynBuiltinCoreInner(Token op, Scope scope, FuncInfo func, boolean isUnsafe) {
        usesOwnsRefDynNew = true;
        if (!isUnsafe) {
            usesThrow = true;
            if (!op.insideTry && !insideThrowingAllocContext) {
                throw new CompilerException("type", op.file, op.line,
                        "'dyn' can fail (it throws when the underlying allocation fails) and must be "
                                + "wrapped in 'try ... catch { ... }'");
            }
        }
        Token argExpr = collectSingleBuiltinArg(op, "dyn", scope, func).get(0);
        boolean argIsArrayLiteral = argExpr.type == TokenType.DELINEATOR && argExpr.text.equals("[");
        // `dyn:<T>([])`: the only way to write an empty dynarray (an empty literal has no element type); the element is `mut T`.
        List<List<Token>> dynTypeArgs = op.left == null ? null : op.left.genericArgs;
        if (dynTypeArgs != null) {
            if (dynTypeArgs.size() != 1 || dynTypeArgs.get(0).size() != 1) {
                throw new CompilerException("type", op.file, op.line,
                        "'dyn:<T>' takes exactly one element type, e.g. 'dyn:<char>([])'");
            }
            if (!argIsArrayLiteral || !argExpr.childs.isEmpty()) {
                throw new CompilerException("type", op.file, op.line,
                        "a type argument on 'dyn' is only for the empty array literal, 'dyn:<T>([])' -- a "
                                + "non-empty literal or a string already determines its own element type");
            }
            Token elemTok = dynTypeArgs.get(0).get(0);
            if (!isKnownType(elemTok.text)) {
                throw new CompilerException("type", elemTok.file, elemTok.line,
                        "'dyn:<" + elemTok.text + ">': '" + elemTok.text + "' is not a known type");
            }
            TypeInfo emptyElementType = new TypeInfo(null, "mut", elemTok.text);
            op.builtinConstantValue = 0L;
            return isUnsafe ? TypeInfo.unsafeDynArray("indeterminate", emptyElementType)
                    : TypeInfo.dynArray("indeterminate", emptyElementType).withSome(true);
        }
        if (argIsArrayLiteral && argExpr.childs.isEmpty()) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "an empty array literal has no element type -- write 'dyn:<T>([])' (e.g. "
                            + "'dyn:<char>([])') to construct an empty dynarray");
        }
        if (!argIsArrayLiteral) {
            // `dyn(text)`: a dynarray of chars; the element is `imut char`, like string indexing.
            TypeInfo argType = resolveExprType(argExpr, scope, func);
            if (argType.baseType.equals("string")) {
                op.dynFromStringSource = true;
                TypeInfo elementType = new TypeInfo(null, "imut", "char");
                return isUnsafe ? TypeInfo.unsafeDynArray("indeterminate", elementType)
                        : TypeInfo.dynArray("indeterminate", elementType).withSome(true);
            }
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'dyn' requires an array literal or a string argument, e.g. 'dyn([0,1,2,3])' or 'dyn(text)'");
        }
        TypeInfo fixedArrayType = checkArrayLiteral(argExpr, scope, func);
        int count = arrayLength(fixedArrayType.baseType);
        // Reuse the element type checkArrayLiteral already computed: resolving the elements again re-ran the move check and wrongly reported `dyn([a, b])`.
        TypeInfo elementType = fixedArrayType.arrayElementType;
        op.builtinConstantValue = (long) count;
        return isUnsafe ? TypeInfo.unsafeDynArray("indeterminate", elementType)
                : TypeInfo.dynArray("indeterminate", elementType).withSome(true);
    }

    /** `unsafe dyn(..)`: calls checkDynBuiltinCore directly (the inner CALL must resolve to the unsafe type) and sets op.left.resolvedType by hand. */
    public TypeInfo checkUnsafeDynWrapper(Token op, Scope scope, FuncInfo func) {
        requireNotPure(func, op, "'unsafe dyn(...)'", "unsafe code can do anything the compiler cannot check, so purity could not be guaranteed");
        if (!"unsafe".equals(scope.currentSafety)) {
            throw new CompilerException("type", op.file, op.line,
                    "'unsafe dyn(...)' can only be used from within 'unsafe' code");
        }
        if (op.left.type != TokenType.OPERATOR || !op.left.text.equals("CALL")
                || op.left.left.type != TokenType.VARREF || !op.left.left.text.equals("dyn")) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'unsafe' must directly wrap a 'dyn(...)' call, e.g. 'unsafe dyn([0,1,2,3])'");
        }
        TypeInfo result = checkDynBuiltinCore(op.left, scope, func, true);
        unsafeBypass(scope, udynTag(result));
        op.left.resolvedType = result.canonical();
        return result;
    }

    /** `resize(d, n, fill)`: needs a `mut` dynarray, allowed in safe code; growing needs an explicit fill. Moves the source. See docs/TypeChecker.md, "dyn and resize". */
    public TypeInfo checkResizeBuiltin(Token op, Scope scope, FuncInfo func) {
        TypeInfo result;
        result = checkResizeBuiltinInner(op, scope, func);
        List<Token> resizeArgs = new ArrayList<>();
        if (op.right != null && op.right.childs != null && !op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), resizeArgs);
        }
        ProofKills.kill(scope.activeMatchPatterns, op, "'resize' (the block may move or shrink, freeing what was in it)",
                resizeArgs.isEmpty() ? null : memberPathRoot(resizeArgs.get(0)), destroyedTypes(result));
        return result;
    }

    public TypeInfo checkResizeBuiltinInner(Token op, Scope scope, FuncInfo func) {
        requireNotPureRt(func, op, "'resize'", "allocation depends on heap state (it can fail), so the result is not a function of the arguments");
        // A direct `resize` in a dynarray `for` body is rejected here; indirect ones by checkDynArrayForLoopResizeSafety.
        if (scope.insideDynArrayForLoopBody) {
            throw new CompilerException("type", op.file, op.line,
                    "'resize' cannot be called inside a \"for i in dynarrayExpr{...}\" loop's own body -- "
                            + "resizing the dynarray being iterated (even a different one, conservatively) "
                            + "while iterating it is not allowed");
        }
        funcsCallingResizeDirectly.add(func);
        List<Token> args = new ArrayList<>();
        if (!op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), args);
        }
        if (args.size() != 2 && args.size() != 3) {
            throw new CompilerException("type", op.file, op.line,
                    "'resize' takes two arguments (an unsafe dynarray, the new count) or three (a safe "
                            + "dynarray, the new count, a fill value for any newly-created slots), got "
                            + args.size());
        }
        TypeInfo dynArrType = resolveExprType(args.get(0), scope, func);
        boolean isUnsafeTarget = isUnsafeDynArrayType(dynArrType);
        if (!isUnsafeTarget && !isDynArrayType(dynArrType)) {
            throw new CompilerException("type", args.get(0).file, args.get(0).line,
                    "'resize''s first argument must be a dynarray, got '" + dynArrType.canonical() + "'");
        }
        if (!"mut".equals(dynArrType.mutability)) {
            throw new CompilerException("type", args.get(0).file, args.get(0).line,
                    "'resize' requires a 'mut' dynarray, got '" + dynArrType.canonical() + "'");
        }
        // Unsafe dynarray: a bare realloc with no fill (no hidden length, no initialisation guarantee).
        if (isUnsafeTarget) {
            if (!unsafeBypass(scope, udynTag(dynArrType))) {
                throw new CompilerException("type", op.file, op.line,
                        "'resize' on an 'unsafe dynarray' can only be used from within 'unsafe' code");
            }
            if (args.size() != 2) {
                throw new CompilerException("type", op.file, op.line,
                        "'resize' on an 'unsafe dynarray' takes exactly two arguments (the dynarray, "
                                + "the new count) -- no fill value; growing may leave newly-created "
                                + "memory uninitialized, permitted here only because the code is "
                                + "'unsafe', got " + args.size());
            }
            // An unsafe `resize` cannot throw, so `try` around it is rejected (same two-way contract as requireThrowsWrapping).
            if (op.insideTry) {
                throw new CompilerException("type", op.file, op.line,
                        "'try' wraps a call to 'resize' on an 'unsafe dynarray', which cannot throw -- "
                                + "'try'/'catch' is only needed (and only allowed) around 'resize' on a "
                                + "safe dynarray");
            }
        } else if (args.size() != 3) {
            throw new CompilerException("type", op.file, op.line,
                    "'resize' on a dynarray takes exactly three arguments (the dynarray, the new count, "
                            + "a fill value for any newly-created slots), got " + args.size());
        }
        // A safe `resize` can fail like `new`/`dyn`: it needs try/catch (op.insideTry or insideThrowingAllocContext) and sets usesThrow.
        if (!isUnsafeTarget) {
            usesThrow = true;
            if (!op.insideTry && !insideThrowingAllocContext) {
                throw new CompilerException("type", op.file, op.line,
                        "'resize' can fail (it throws when the underlying reallocation fails) and must be "
                                + "wrapped in 'try ... catch { ... }'");
            }
        }
        TypeInfo countType = resolveExprType(args.get(1), scope, func);
        if (!isPlainU64(countType)) {
            throw new CompilerException("type", args.get(1).file, args.get(1).line,
                    "'resize''s new count must be a plain 'u64', got '" + countType.canonical() + "'");
        }
        TypeInfo elementType;
        if (isUnsafeTarget) {
            elementType = dynArrType.unsafeDynArrayElementType;
        } else {
            elementType = dynArrType.dynArrayElementType;
            TypeInfo fillType = resolveExprType(args.get(2), scope, func);
            if (!fillType.baseType.equals(elementType.baseType)) {
                throw new CompilerException("type", args.get(2).file, args.get(2).line,
                        "'resize''s fill value must match the dynarray's own element type ('"
                                + elementType.baseType + "'), got '" + fillType.baseType + "'");
            }
        }
        // `resize` takes ownership and returns a new owns (a realloc may move): the source is moved by hand via markMovedIfOwned, both variants.
        markMovedIfOwned(dynArrType, args.get(0), scope);
        return isUnsafeTarget ? TypeInfo.unsafeDynArray("indeterminate", elementType)
                : TypeInfo.dynArray("indeterminate", elementType).withSome(true);
    }

    /** Visibility: the impl-level gate (impl must be `@pub` outside its file) comes first; a private method is callable only from the same impl block (identity, since a struct can have several). */
    public void checkImplMethodVisibility(FuncInfo candidate, ImplInfo impl, String methodName, Token at,
            FuncInfo callerFunc) {
        String declFile = candidate.funcToken.file;
        boolean sameFile = declFile.equals(at.file);
        if (!impl.isPublic && !sameFile) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + impl.concreteName + "'s impl (containing '" + methodName + "') is not '@pub' -- "
                            + "not accessible outside the file it's declared in");
        }
        if (candidate.isPublic) {
            return;
        }
        if (callerFunc == null || callerFunc.enclosingImpl != impl) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + methodName + "' is private -- it can only be referenced by another func "
                            + "within that same impl");
        }
    }

    /** RVO for a method call returning a struct: legal only as a `let` RHS or bare-variable assignment RHS (same rule and message as `checkCall`). */
    public void markStructRvoMethodCall(Token op, FuncInfo candidate, boolean eligibleForRvoHere) {
        if (!isStructByValueReturn(candidate.returnType)) {
            return;
        }
        if (!eligibleForRvoHere) {
            throw new CompilerException("type", op.file, op.line,
                    "a call to '" + candidate.name + "' (which returns '" + candidate.returnType.baseType
                            + "' by value) may only be used as a 'let' binding's own right-hand side, or as a plain "
                            + "assignment's right-hand side with a bare-variable target -- not as a function argument, "
                            + "inside a larger expression, as a 'return' statement's own expression, or anywhere else a "
                            + "value is otherwise expected; bind it to a 'let' first");
        }
        op.isStructRvoCall = true;
    }

    /** `receiver.method(args)`: the receiver only selects the impls to search and is not injected as an argument; resolved at compile time, no run-time dispatch. See docs/TypeChecker.md, "Method calls". */
    public TypeInfo checkMethodCall(Token op, Scope scope, FuncInfo func, boolean eligibleForRvoHere) {
        Token dotNode = op.left;
        Token methodNameTok = dotNode.right;
        if (methodNameTok.type != TokenType.VARREF) {
            throw new CompilerException("type", dotNode.file, dotNode.line,
                    "expected a method name after '.'");
        }
        String methodName = methodNameTok.text;

        // `TypeName.method(..)` (static call): a bare non-variable name that is a struct/enum goes to checkStaticMethodCall; generic receivers arrive already monomorphized.
        if (dotNode.left.type == TokenType.VARREF && scope.lookup(dotNode.left.text) == null
                && (structs.containsKey(dotNode.left.text) || enums.containsKey(dotNode.left.text))) {
            return checkStaticMethodCall(op, dotNode.left.text, methodName, scope, func, eligibleForRvoHere);
        }

        // `Lib.fn(..)`: a bare library name.
        // the library counterpart of the static-method case just above.
        if (dotNode.left.type == TokenType.VARREF && scope.lookup(dotNode.left.text) == null
                && libraries.containsKey(dotNode.left.text)) {
            return checkLibraryCall(op, dotNode.left.text, methodName, scope, func);
        }

        // Resolved as an expression, not via checkDot (a method is not a member).
        TypeInfo receiverType = resolveExprType(dotNode.left, scope, func);

        if (interfaces.containsKey(receiverType.baseType)) {
            return checkInterfaceMethodDispatch(op, dotNode, receiverType.baseType, methodName, scope, func);
        }

        List<ImplInfo> impls = implsByConcreteType.get(receiverType.baseType);
        if (impls == null) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + receiverType.baseType + "' has no method named '" + methodName + "'");
        }

        // `recv.method:<U>(..)`: explicit generic-method type args survive GenericsExpander and are dispatched separately (never in impl.methods).
        if (methodNameTok.genericArgs != null) {
            return checkGenericMethodCall(op, dotNode, receiverType, impls, methodName, methodNameTok, scope, func, eligibleForRvoHere);
        }

        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        List<TypeInfo> argTypes = new ArrayList<>();
        for (Token argNode : argNodes) {
            argTypes.add(resolveExprType(argNode, scope, func));
        }

        boolean nameExistsAsStatic = false;
        for (ImplInfo impl : impls) {
            List<FuncInfo> overloadCandidates = impl.candidates(methodName, false);
            if (overloadCandidates.isEmpty()) {
                if (impl.staticMethods.containsKey(methodName)) {
                    nameExistsAsStatic = true;
                }
                continue;
            }
            for (FuncInfo candidate : orderByLiteralExactness(overloadCandidates, argTypes)) {
            if (candidate.paramTypes.size() != argTypes.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < argTypes.size(); i++) {
                if (!argSatisfiesParam(candidate.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                retagLiteralArgs(candidate.paramTypes, argTypes, argNodes);
                checkImplMethodVisibility(candidate, impl, methodName, op, func);
                op.resolvedCallTarget = candidate.mangledName;
                op.resolvedCallConvention = candidate.callConvention;
                requireThrowsWrapping(candidate, op);
                for (int i = 0; i < argNodes.size(); i++) {
                    markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
                }
                // Per-call-site unwind lists; see docs/TypeChecker.md, "Method calls".
                op.destructOnExit = collectOwnsToDestruct(scope, scope.functionRootScope, null);
                op.unlockOnExit = collectLockReleasesToBoundary(scope, scope.functionRootScope);
                if (candidate == func && scope.suspendsSelfRecursion) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot call '" + func.name + "' from within its own base-case match "
                                    + "block -- the func loses its recursive privileges there");
                }
                if (candidate == func && getDecorator(func.funcToken.decorators, "recursive") != null) {
                    requireRecursiveCallReturnSiteOnly(func, op);
                    requireRecursiveCallWithinProof(func, op, argNodes, argTypes, scope);
                }
                requireMethodLockProofs(candidate, dotNode.left, argNodes, scope, op);
                requirePureCalleeIfPure(func, candidate, op);
                callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
                markStructRvoMethodCall(op, candidate, eligibleForRvoHere);
                return candidate.returnType;
            }
            }
        }
        // A specific error when the name exists only as a static method.
        if (nameExistsAsStatic) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + methodName + "' is a static method on '" + receiverType.baseType + "', not an "
                            + "instance method -- call it as '" + receiverType.baseType + "." + methodName
                            + "(...)', not on an instance");
        }
        throw new CompilerException("type", op.file, op.line,
                "'" + receiverType.baseType + "' has no method '" + methodName
                        + "' matching argument types (" + describeTypes(argTypes) + ")"
                        + unavailableNote(receiverType.baseType + "." + methodName));
    }

    /**
    /** `recv.method:<U>(..)`: instantiates (memoized) the residual-generic method and matches the arguments against that single candidate. */
    public TypeInfo checkGenericMethodCall(Token op, Token dotNode, TypeInfo receiverType, List<ImplInfo> impls,
            String methodName, Token methodNameTok, Scope scope, FuncInfo func, boolean eligibleForRvoHere) {
        ImplInfo owningImpl = null;
        Token template = null;
        for (ImplInfo impl : impls) {
            Token t = impl.genericMethodTemplates.get(methodName);
            if (t != null) {
                owningImpl = impl;
                template = t;
                break;
            }
        }
        if (template == null) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + receiverType.baseType + "' has no generic method named '" + methodName + "'");
        }

        List<String> argNames = new ArrayList<>();
        for (List<Token> group : methodNameTok.genericArgs) {
            if (group.size() != 1) {
                throw new CompilerException("type", methodNameTok.file, methodNameTok.line,
                        "generic method type argument must be a single type name, found a compound "
                                + "expression");
            }
            argNames.add(group.get(0).text);
        }
        if (template.typeParams.size() != argNames.size()) {
            throw new CompilerException("type", methodNameTok.file, methodNameTok.line,
                    "'" + methodName + "' expects " + template.typeParams.size() + " type argument(s), got "
                            + argNames.size());
        }

        FuncInfo candidate = instantiateGenericMethod(owningImpl, template, argNames, methodNameTok);
        methodNameTok.genericArgs = null;

        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        List<TypeInfo> argTypes = new ArrayList<>();
        for (Token argNode : argNodes) {
            argTypes.add(resolveExprType(argNode, scope, func));
        }
        if (candidate.paramTypes.size() != argTypes.size()) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + methodName + "' expects " + candidate.paramTypes.size() + " argument(s), got "
                            + argTypes.size());
        }
        for (int i = 0; i < argTypes.size(); i++) {
            if (!argSatisfiesParam(candidate.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + methodName + "' argument " + (i + 1) + " type mismatch: expected '"
                                + candidate.paramTypes.get(i).canonical() + "', got '"
                                + argTypes.get(i).canonical() + "'");
            }
        }
        retagLiteralArgs(candidate.paramTypes, argTypes, argNodes);

        checkImplMethodVisibility(candidate, owningImpl, methodName, op, func);
        op.resolvedCallTarget = candidate.mangledName;
        op.resolvedCallConvention = candidate.callConvention;
        requireThrowsWrapping(candidate, op);
        for (int i = 0; i < argNodes.size(); i++) {
            markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
        }
        // Per-call-site unwind lists; see docs/TypeChecker.md, "Method calls".
        op.destructOnExit = collectOwnsToDestruct(scope, scope.functionRootScope, null);
        op.unlockOnExit = collectLockReleasesToBoundary(scope, scope.functionRootScope);
        requireMethodLockProofs(candidate, dotNode.left, argNodes, scope, op);
        requirePureCalleeIfPure(func, candidate, op);
        callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
        markStructRvoMethodCall(op, candidate, eligibleForRvoHere);
        return candidate.returnType;
    }

    /** Materializes (memoized per type-argument list) a callable FuncInfo for a residual-generic method template; the template is already tree-built, so deepCloneCheckedTree is used. */
    public FuncInfo instantiateGenericMethod(ImplInfo impl, Token template, List<String> argNames, Token usageTok) {
        String key = template.sub.get(0).text + "<" + String.join(",", argNames) + ">";
        FuncInfo existing = impl.genericMethodInstances.get(key);
        if (existing != null) {
            return existing;
        }

        if (template.typeParamBounds != null) {
            for (int i = 0; i < template.typeParamBounds.size(); i++) {
                String bound = template.typeParamBounds.get(i);
                if (bound != null && !structImplementsInterface(argNames.get(i), bound)) {
                    throw new CompilerException("type", usageTok.file, usageTok.line,
                            "'" + argNames.get(i) + "' does not implement '" + bound
                                    + "' -- required by the bound on type parameter '"
                                    + template.typeParams.get(i) + "'");
                }
            }
        }

        Map<String, String> subMap = new HashMap<>();
        for (int i = 0; i < template.typeParams.size(); i++) {
            subMap.put(template.typeParams.get(i), argNames.get(i));
        }

        Token clone = deepCloneCheckedTree(template);
        // `self` is substituted here for residual-generic templates (collectImpl skips them).
        substituteSelfInPlace(clone.sub.get(1).childs, impl.concreteName);
        substituteSelfInPlace(clone.sub.subList(2, clone.sub.size()), impl.concreteName);
        substituteGenericMethodParams(clone, subMap);
        clone.typeParams = null;
        clone.typeParamBounds = null;

        FuncInfo info = new FuncInfo();
        info.funcToken = clone;
        buildFuncInfo(clone, info);
        info.enclosingImpl = impl;
        info.declOrder = pendingDeclOrder.getOrDefault(impl.declTok, Integer.MAX_VALUE);
        info.safetyTag = impl.safetyTag;
        // Forced public: reached only through an instance call, whose impl-level @pub check follows.
        info.isPublic = true;
        String methodNamePart = info.name;
        info.mangledName = (impl.interfaceName != null
                ? impl.concreteName + "__" + impl.interfaceName + "_" + methodNamePart
                : impl.concreteName + "_" + methodNamePart) + "__" + String.join("_", argNames);
        validateMethodLocks(clone.decorators, info.paramNames, info.paramTypes, impl.concreteName);

        // Memoized before the body is checked: guards recursive self-instantiation and avoids mutating implInfo.methods while it is iterated.
        impl.genericMethodInstances.put(key, info);
        // BytecodeEmitter walks the impl root's childs, not genericMethodInstances.
        impl.declTok.childs.add(clone);

        checkFunctionBody(info);

        return info;
    }

    /** Blanket VARREF-text substitution of a residual-generic method's type parameters (post-GenericsExpander counterpart of its substituteParams). */
    public void substituteGenericMethodParams(Token node, Map<String, String> subMap) {
        if (node == null) {
            return;
        }
        if (node.type == TokenType.VARREF && subMap.containsKey(node.text)) {
            node.text = subMap.get(node.text);
        }
        substituteGenericMethodParams(node.left, subMap);
        substituteGenericMethodParams(node.right, subMap);
        if (node.childs != null) {
            for (Token c : node.childs) {
                substituteGenericMethodParams(c, subMap);
            }
        }
        if (node.sub != null) {
            for (Token s : node.sub) {
                substituteGenericMethodParams(s, subMap);
            }
        }
        if (node.genericArgs != null) {
            for (List<Token> group : node.genericArgs) {
                for (Token g : group) {
                    substituteGenericMethodParams(g, subMap);
                }
            }
        }
        if (node.typeBound != null) {
            for (Token t : node.typeBound) {
                substituteGenericMethodParams(t, subMap);
            }
        }
    }

    /** `c.foo(args)` on a bare interface type: uses the synthesized dispatcher for (interface, method); one candidate, no overloads, no impl bookkeeping. See docs/TypeChecker.md, "Method calls". */
    public TypeInfo checkInterfaceMethodDispatch(Token op, Token dotNode, String interfaceName, String methodName,
            Scope scope, FuncInfo func) {
        List<FuncInfo> overloads = functions.get(interfaceDispatcherName(interfaceName, methodName));
        FuncInfo dispatcher = (overloads == null || overloads.isEmpty()) ? null : overloads.get(0);
        if (dispatcher == null) {
            throw new CompilerException("type", op.file, op.line,
                    "could not resolve implementation: the receiver is only known by its interface "
                            + "type ('" + interfaceName + "'), and no dispatcher could be generated for '"
                            + methodName + "' -- either it has no explicit 'self'-typed parameter "
                            + "(nothing in its own signature identifies which concrete implementer to "
                            + "call), it's '@lock'/'@unlock'-decorated (a synthesized call site can't yet "
                            + "establish the required lock proof), or no '@gt_alive_check' function is "
                            + "in scope (import the ghost-table stdlib to enable interface dispatch)");
        }
        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        List<TypeInfo> argTypes = new ArrayList<>();
        for (Token argNode : argNodes) {
            argTypes.add(resolveExprType(argNode, scope, func));
        }
        if (dispatcher.paramTypes.size() != argTypes.size()) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + methodName + "' on interface '" + interfaceName + "' expects "
                            + dispatcher.paramTypes.size() + " argument(s), got " + argTypes.size());
        }
        for (int i = 0; i < argTypes.size(); i++) {
            if (!argSatisfiesParam(dispatcher.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + methodName + "' on interface '" + interfaceName + "' argument " + (i + 1)
                                + ": expected '" + dispatcher.paramTypes.get(i).canonical() + "', got '"
                                + argTypes.get(i).canonical() + "'");
            }
        }
        retagLiteralArgs(dispatcher.paramTypes, argTypes, argNodes);
        op.resolvedCallTarget = dispatcher.mangledName;
        op.resolvedCallConvention = dispatcher.callConvention;
        for (int i = 0; i < argNodes.size(); i++) {
            markMovedIfOwned(dispatcher.paramTypes.get(i), argNodes.get(i), scope);
        }
        requirePureCalleeIfPure(func, dispatcher, op);
        callEdges.add(new CallEdge(func, dispatcher, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
        return dispatcher.returnType;
    }
}

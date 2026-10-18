package caspien.typechecker;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;
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
public final class TypeCheckerCalls {
    private final TypeChecker hub;

    TypeCheckerCalls(TypeChecker hub) {
        this.hub = hub;
    }

    public TypeInfo checkCall(Token op, Scope scope, FuncInfo func, boolean eligibleForRvoHere) {
        CheckFrame f = new CheckFrame(CheckFrame.CALL_EXPR, op, scope, func);
        f.p = eligibleForRvoHere;
        return hub.driver.run(f);
    }

    /** Hands the call to another frame kind (its result is this call's result). */
    private int delegate(CheckFrame f, int kind, String name) {
        CheckFrame c = new CheckFrame(kind, f.node, f.scope, f.func);
        c.p = f.p;
        c.s1 = name;
        c.data = f.data;
        f.phase = 9;
        return f.call(c);
    }

    /** f.p = eligibleForRvoHere; head in phase 0, argument types in phase 1 (f.s1 = callee name, f.q = event-loop redirect). */
    public int stepCall(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        if (f.phase == 9) {
            return f.done(f.got);
        }
        if (f.phase == 0) {
            if (op.left.type == TokenType.KEYWORD && op.left.text.equals("sleep")) {
                // 'sleep' is a Lexer keyword, so it resolves to whichever function carries '@sleep'.
                return delegate(f, CheckFrame.SLEEP_CALL, null);
            }
            if (op.left.type == TokenType.OPERATOR && op.left.text.equals(".")) {
                return delegate(f, CheckFrame.METHOD_CALL, null);
            }
            if (op.left.type != TokenType.VARREF) {
                throw new CompilerException("type", op.file, op.line,
                        "calling through a function-pointer expression is not yet supported by the type checker");
            }
            String funcName = op.left.text;
            boolean wasEventLoopRedirect = false;
            if (funcName.equals("tick") && func != null && hub.impls.hasDecorator(func.funcToken.decorators, "event_loop")) {
                // Inside '@event_loop', a literal `tick(..)` redirects to the function decorated '@tick' (neither has a fixed name).
                List<FuncInfo> tickFuncs = hub.declarations.findFuncsWithDecorator("tick");
                if (!tickFuncs.isEmpty()) {
                    funcName = tickFuncs.get(0).name;
                    wasEventLoopRedirect = true;
                    // The stdlib loop writes `? tick(state)`; the wrap is kept only if tick is '@throws', so the two-way rule still holds.
                    if (!tickFuncs.get(0).isThrows) {
                        op.chk.insideTry = false;
                    }
                }
            }
            if (TypeCheckerModel.BUILTIN_NAMES.contains(funcName)) {
                return delegate(f, CheckFrame.BUILTIN_CALL, funcName);
            }
            ExternInfo externInfo = hub.model.externs.get(funcName);
            if (externInfo != null) {
                f.data = externInfo;
                return delegate(f, CheckFrame.EXTERN_CALL, null);
            }
            List<FuncInfo> overloads = hub.model.functions.get(funcName);
            boolean isConstructorCallShape = false;
            if (overloads == null && hub.model.structs.containsKey(funcName)) {
                // `MyClass(..)` resolves against the struct's constructors (CONSTRUCTOR_NAME_PREFIX + name) via the normal overload loop.
                overloads = hub.model.functions.get(Parser.CONSTRUCTOR_NAME_PREFIX + funcName);
                isConstructorCallShape = true;
            }
            if (overloads == null) {
                throw new CompilerException("type", op.file, op.line,
                        isConstructorCallShape
                                ? "'" + funcName + "' declares no constructor at all"
                                : "call to undeclared function '" + funcName + "'");
            }

            f.s1 = funcName;
            f.q = wasEventLoopRedirect;
            f.data = new Object[]{overloads, isConstructorCallShape};
            List<Token> argNodes = new ArrayList<>();
            Token argsDelineator = op.right;
            if (!argsDelineator.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(argsDelineator.childs.get(0), argNodes);
            }
            f.toks = argNodes;
            f.phase = 1;
            return f.callAll(argNodes);
        }
        Object[] saved = (Object[]) f.data;
        @SuppressWarnings("unchecked")
        List<FuncInfo> overloads = (List<FuncInfo>) saved[0];
        return f.done(callTail(op, scope, func, f.p, f.s1, f.q, overloads, (Boolean) saved[1], f.toks, f.gotFrame.list));
    }

    private TypeInfo callTail(Token op, Scope scope, FuncInfo func, boolean eligibleForRvoHere, String funcName, boolean wasEventLoopRedirect,
            List<FuncInfo> overloads, boolean isConstructorCallShape, List<Token> argNodes, List<TypeInfo> argTypes) {
        for (FuncInfo candidate : hub.orderByLiteralExactness(overloads, argTypes)) {
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
                if (hub.impls.hasDecorator(candidate.funcToken.decorators, "event_loop")) {
                    // '@event_loop' cannot be called or referenced.
                    throw new CompilerException("type", op.file, op.line,
                            "'" + funcName + "' is '@event_loop' -- it cannot be called or referenced "
                                    + "anywhere in the compilation unit");
                }
                boolean callerIsEventLoop = func != null && hub.impls.hasDecorator(func.funcToken.decorators, "event_loop");
                boolean mainIsWithTick = hub.model.functions.containsKey("main")
                        && !hub.model.functions.get("main").isEmpty()
                        && hub.impls.hasDecorator(hub.model.functions.get("main").get(0).funcToken.decorators, "with_tick");
                boolean isMainOrTick = candidate.name.equals("main")
                        || hub.impls.hasDecorator(candidate.funcToken.decorators, "tick");
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
                if (candidate.isAsync && op.chk.asyncCallKind == null && !op.chk.isTrampolineOwnCall) {
                    throw new CompilerException("type", op.file, op.line,
                            "'" + funcName + "' is '@async' and can only be called through 'par' or "
                                    + "'await' -- never plainly");
                }
                if (!candidate.isAsync && op.chk.asyncCallKind != null) {
                    throw new CompilerException("type", op.file, op.line,
                            "'" + op.chk.asyncCallKind.toLowerCase() + "' can only be used on an '@async' "
                                    + "function -- '" + funcName + "' isn't one");
                }
                if (op.chk.asyncCallKind != null) {
                    // Pointers (even nested in structs) cannot cross par/await without 'unsafe async'.
                    for (int i = 0; i < argNodes.size(); i++) {
                        if (hub.locksAndProofs.containsPointerAnywhere(candidate.paramTypes.get(i)) && !hub.declarations.unsafeBypass(scope, "async")) {
                            throw new CompilerException("type", argNodes.get(i).file, argNodes.get(i).line,
                                    "passing '" + candidate.paramTypes.get(i).canonical() + "' (a pointer, "
                                            + "or a struct with one nested somewhere inside it) across "
                                            + "'" + op.chk.asyncCallKind.toLowerCase() + "' requires 'unsafe' "
                                            + "code");
                        }
                    }
                }
                op.chk.resolvedCallTarget = candidate.mangledName;
                op.chk.resolvedCallConvention = candidate.callConvention;
                hub.controlFlow.requireThrowsWrapping(candidate, op);
                hub.lifetimes.onCall(candidate, argNodes, scope, op);
                for (int i = 0; i < argNodes.size(); i++) {
                    hub.expressions.markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
                }
                // Per-call-site destruct list, used if the callee throws and unwinds back here (emitted only when the program uses throw).
                op.chk.destructOnExit = hub.statements.collectOwnsToDestruct(scope, scope.functionRootScope, null);
                op.chk.unlockOnExit = hub.statements.collectLockReleasesToBoundary(scope, scope.functionRootScope);
                if (candidate == func && scope.suspendsSelfRecursion) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot call '" + func.name + "' from within its own base-case match "
                                    + "block -- the func loses its recursive privileges there");
                }
                if (candidate == func && hub.impls.getDecorator(func.funcToken.decorators, "recursive") != null) {
                    hub.statements.requireRecursiveCallReturnSiteOnly(func, op);
                    hub.statements.requireRecursiveCallWithinProof(func, op, argNodes, argTypes, scope);
                }
                hub.impls.requirePureCalleeIfPure(func, candidate, op);
                hub.model.callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
                // Struct-by-value return: legal only at a 'let' RHS or a bare-variable assignment RHS (insideStructRvoDestinationPosition).
                // See docs/TypeChecker.md, "Struct return values (RVO)".
                if (hub.isStructByValueReturn(candidate.returnType)) {
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
                    op.chk.isStructRvoCall = true;
                }
                return candidate.returnType;
            }
        }
        throw new CompilerException("type", op.file, op.line,
                "no overload of '" + funcName + "' matches argument types (" + hub.locksAndProofs.describeTypes(argTypes) + ")"
                        + hub.declarations.unavailableNote(isConstructorCallShape ? Parser.CONSTRUCTOR_NAME_PREFIX + funcName : funcName));
        }


    public TypeInfo checkExternCall(ExternInfo info, Token op, Scope scope, FuncInfo func) {
        CheckFrame f = new CheckFrame(CheckFrame.EXTERN_CALL, op, scope, func);
        f.data = info;
        return hub.driver.run(f);
    }

    /** Calling an extern needs `unsafe extern` and is banned in '@pure'. Fixed parameters are checked like an ordinary call; varargs only need a concrete (non-indeterminate) type. */
    public int stepExternCall(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        ExternInfo info = (ExternInfo) f.data;
        if (f.phase == 0) {
            if (!hub.declarations.unsafeBypass(scope, "extern")) {
                throw new CompilerException("type", op.file, op.line,
                        "calling extern '" + info.name + "' requires 'unsafe' code");
            }
            if (hub.impls.isPureFunc(func)) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + func.name + "' is '@pure' and cannot call extern '" + info.name
                                + "' -- an extern's behavior can never be verified at compile time");
            }

            List<Token> argNodes = new ArrayList<>();
            Token argsDelineator = op.right;
            if (!argsDelineator.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(argsDelineator.childs.get(0), argNodes);
            }
            int fixedCount = info.paramTypes.size();
            if (info.hasVarargs ? argNodes.size() < fixedCount : argNodes.size() != fixedCount) {
                throw new CompilerException("type", op.file, op.line,
                        "call to extern '" + info.name + "' requires " + (info.hasVarargs ? "at least " : "exactly ")
                                + fixedCount + " argument(s), got " + argNodes.size());
            }
            f.toks = argNodes;
            f.i = 0;
        } else {
            int i = f.i++;
            Token argNode = f.toks.get(i);
            TypeInfo argType = f.got;
            if (i < info.paramTypes.size()) {
                TypeInfo expected = info.paramTypes.get(i);
                if (!hub.acceptsAdaptingLiteral(expected, argType, argNode)) {
                    throw new CompilerException("type", argNode.file, argNode.line,
                            "argument " + (i + 1) + " to extern '" + info.name + "' expects '"
                                    + expected.canonical() + "', got '" + argType.canonical() + "'");
                }
                hub.expressions.markMovedIfOwned(expected, argNode, scope);
            }
            // Varargs past the fixed parameters: C ignores mutability, so an
            // indeterminate-mutability value (literal, expression) is accepted as is.
        }
        if (f.i < f.toks.size()) {
            f.phase = 1;
            return f.call(f.toks.get(f.i));
        }
        op.chk.resolvedCallTarget = info.linkName; // the real linked symbol -- info.name unless "@link_name" overrides it (see ExternInfo.linkName's own doc comment)
        op.chk.resolvedCallConvention = info.callConvention;
        return f.done(info.returnType);
    }


    public TypeInfo checkSleepCall(Token op, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.SLEEP_CALL, op, scope, func));
    }

    /** `sleep(...)`: always resolves to the function carrying `@sleep` (ghost-table function registry); fixed arity and types, no overloads. See docs/TypeChecker.md, "Builtin calls". */
    public int stepSleepCall(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        FuncInfo sleepFunc = hub.declarations.getGhostTableFunction("sleep");
        if (f.phase == 0) {
            if (sleepFunc == null) {
                throw new CompilerException("type", op.file, op.line,
                        "using 'sleep' requires a function decorated '@sleep' to exist somewhere in the "
                                + "compilation unit (none was found)");
            }
            List<Token> argNodes = new ArrayList<>();
            Token argsDelineator = op.right;
            if (!argsDelineator.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(argsDelineator.childs.get(0), argNodes);
            }
            int expectedCount = sleepFunc.paramTypes.size();
            if (argNodes.size() != expectedCount) {
                throw new CompilerException("type", op.file, op.line,
                        "'sleep' requires exactly " + expectedCount + " argument(s) (matching the '@sleep'"
                                + "-decorated function's own signature), got " + argNodes.size());
            }
            f.toks = argNodes;
            f.i = 0;
        } else {
            int i = f.i++;
            Token argNode = f.toks.get(i);
            TypeInfo argType = f.got;
            TypeInfo expected = sleepFunc.paramTypes.get(i);
            if (!hub.acceptsAdaptingLiteral(expected, argType, argNode)) {
                throw new CompilerException("type", argNode.file, argNode.line,
                        "argument " + (i + 1) + " to 'sleep' expects '" + expected.canonical() + "', got '"
                                + argType.canonical() + "'");
            }
            hub.expressions.markMovedIfOwned(expected, argNode, scope);
        }
        if (f.i < f.toks.size()) {
            f.phase = 1;
            return f.call(f.toks.get(f.i));
        }
        op.chk.resolvedCallTarget = sleepFunc.mangledName;
        op.chk.resolvedCallConvention = sleepFunc.callConvention;
        // Emitted as SLEEP, not CALL; BytecodeEmitter.emitCallOrBuiltin reads the flag.
        op.chk.isSleepCall = true;
        return f.done(sleepFunc.returnType);
    }


    public TypeInfo checkBuiltinCall(String name, Token op, Scope scope, FuncInfo func) {
        CheckFrame f = new CheckFrame(CheckFrame.BUILTIN_CALL, op, scope, func);
        f.s1 = name;
        return hub.driver.run(f);
    }

    /** Dispatches a reserved builtin name to its checker; builtins never use the user-function overload table. */
    public int stepBuiltinCall(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        String name = f.s1;
        if (f.phase == 1) {
            return f.done(f.got);
        }
        int kind;
        switch (name) {
            case "sizeof": kind = CheckFrame.SIZEOF_BUILTIN; break;
            case "len": kind = CheckFrame.LEN_BUILTIN; break;
            case "range": kind = CheckFrame.RANGE_BUILTIN; break;
            case "deref": kind = CheckFrame.DEREF_BUILTIN; break;
            case "clone": kind = CheckFrame.CLONE_BUILTIN; break;
            case "call": kind = CheckFrame.CALL_BUILTIN; break;
            case "bits_left": case "bits_right": case "bits_rotl": case "bits_rotr": case "bits_or": case "bits_and": case "bits_xor": case "bits_not":
                kind = CheckFrame.BITS_BUILTIN; break;
            case "memcopy": kind = CheckFrame.MEMCOPY_BUILTIN; break;
            case "dyn":
                hub.impls.requireNotPureRt(func, op, "'dyn'", "allocation depends on heap state (it can fail), so the result is not a function of the arguments");
                kind = CheckFrame.DYN_BUILTIN; break;
            case "resize": kind = CheckFrame.RESIZE_BUILTIN; break;
            case "Some": kind = CheckFrame.SOME_BUILTIN; break;
            case "insecure_rand":
                hub.impls.requireNotPureRt(func, op, "'insecure_rand'", "it is non-deterministic");
                return f.done(checkInsecureRandBuiltin(op));
            case "wrap": case "sat": kind = CheckFrame.CONVERT_BUILTIN; break;
            default: throw new IllegalStateException("unreachable: '" + name + "' not in BUILTIN_NAMES");
        }
        CheckFrame c = new CheckFrame(kind, op, scope, func);
        c.s1 = name;
        f.phase = 1;
        return f.call(c);
    }


    /** `Some(x)`: an ordinary bool builtin; checkMatchCondition adds the alive proof when it is a match condition. */
    public TypeInfo checkSomeBuiltin(Token op, Scope scope, FuncInfo func) {
        CheckFrame f = new CheckFrame(CheckFrame.SOME_BUILTIN, op, scope, func);
        return hub.driver.run(f);
    }

    public int stepSomeBuiltin(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        if (f.phase == 0) {
            f.x = someHead(op, scope, func);
            f.phase = 1;
            return f.call(f.x);
        }
        return f.done(someTail(op, scope, func, f.x, f.got));
    }

    private Token someHead(Token op, Scope scope, FuncInfo func) {
        Token argExpr = collectSingleBuiltinArg(op, "Some", scope, func).get(0);
        return argExpr;
    }

    private TypeInfo someTail(Token op, Scope scope, FuncInfo func, Token argExpr, TypeInfo argType) {        if (argType.storage == null) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'Some' requires a pointer, got '" + argType.canonical() + "'");
        }
        op.chk.someOnRef = "ref".equals(argType.storage) && !argType.isSome;
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
        CheckFrame f = new CheckFrame(CheckFrame.CONVERT_BUILTIN, op, scope, func);
        f.s1 = name;
        return hub.driver.run(f);
    }

    /** f.p = the float form (`wrap:<f32>`), f.s2 = the target type name, f.x = the argument. */
    public int stepConvertBuiltin(CheckFrame f) {
        Token op = f.node;
        String name = f.s1;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        if (f.phase == 0) {
            List<List<Token>> targs = op.left == null ? null : op.left.genericArgs;
            if (name.equals("wrap") && targs != null && targs.size() == 1 && targs.get(0).size() == 1
                    && TypeCheckerModel.isFloatType(targs.get(0).get(0).text)) {
                // wrap:<f32>(x) / wrap:<f64>(x): float conversion (f64 -> f32 rounds to nearest; f32 -> f64 is exact).
                f.s2 = targs.get(0).get(0).text;
                f.x = collectSingleBuiltinArg(op, name, scope, func).get(0);
                f.p = true;
                f.phase = 1;
                return f.call(f.x);
            }
            if (targs == null || targs.size() != 1 || targs.get(0).size() != 1
                    || !TypeCheckerModel.INTEGER_TYPES.contains(targs.get(0).get(0).text)) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + name + "' needs an integer target type, e.g. '" + name + ":<u8>(x)'");
            }
            String target = targs.get(0).get(0).text;
            f.s2 = targs.get(0).get(0).text;
            f.x = collectSingleBuiltinArg(op, name, scope, func).get(0);
            f.phase = 1;
            return f.call(f.x);
        }
        if (f.p) {
            return f.done(convertFloatType(op, f.s2, f.x, f.got));
        }
        return f.done(convertIntType(op, name, f.s2, f.x, f.got));
    }

    private TypeInfo convertFloatType(Token op, String floatTarget, Token floatArg, TypeInfo floatArgType) {
        if (floatArgType.storage != null || !TypeCheckerModel.isFloatType(floatArgType.baseType)) {
            throw new CompilerException("type", floatArg.file, floatArg.line,
                    "'wrap:<" + floatTarget + ">' converts a float, got '" + floatArgType.canonical() + "'");
        }
        if (floatArgType.floatLiteral) {
            hub.adaptLiteral(floatArg, new TypeInfo(null, floatArgType.mutability, floatTarget));
        } else {
            op.chk.isFloatConvert = true;
        }
        return new TypeInfo(null, "indeterminate", floatTarget);
    }

    private TypeInfo convertIntType(Token op, String name, String target, Token argExpr, TypeInfo argType) {
        if (argType.storage != null || !TypeCheckerModel.INTEGER_TYPES.contains(argType.baseType)) {
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
        while (true) {
            if (t.type == TokenType.VARREF || t.type == TokenType.INTEGER) {
                return true;
            }
            if (t.type == TokenType.OPERATOR && t.text.equals(".") && t.left != null && t.right != null) {
                if (t.right.type != TokenType.VARREF) {
                    return false;
                }
                t = t.left;
            } else if (t.type == TokenType.OPERATOR && (t.text.equals("mut") || t.text.equals("imut") || (t.text.equals("-") && t.unary)) && t.left != null) {
                t = t.left;
            } else {
                return false;
            }
        }
    }

    public List<Token> collectSingleBuiltinArg(Token op, String name, Scope scope, FuncInfo func) {
        List<Token> args = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            hub.locksAndProofs.collectCommaArgs(argsDelineator.childs.get(0), args);
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
            hub.locksAndProofs.collectCommaArgs(argsDelineator.childs.get(0), args);
            if (!args.isEmpty()) {
                throw new CompilerException("type", op.file, op.line,
                        "'insecure_rand' takes no arguments, got " + args.size());
            }
        }
        return new TypeInfo(null, "indeterminate", "u64");
    }

    /** `sizeof(T)` or `sizeof(expr)`: for an array, the base element type's size; the result is an indeterminate u64. */
    public TypeInfo checkSizeofBuiltin(Token op, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.SIZEOF_BUILTIN, op, scope, func));
    }

    public int stepSizeofBuiltin(CheckFrame f) {
        Token op = f.node;
        if (f.phase == 0) {
            Token argExpr = collectSingleBuiltinArg(op, "sizeof", f.scope, f.func).get(0);
            TypeInfo argType = tryResolveAsTypeDescriptor(argExpr);
            if (argType != null) {
                return f.done(sizeofType(op, argType));
            }
            f.phase = 1;
            return f.call(argExpr);
        }
        return f.done(sizeofType(op, f.got));
    }

    private TypeInfo sizeofType(Token op, TypeInfo argType) {
        long size = computeSizeOf(argType, op);
        op.chk.builtinConstantValue = size;
        op.chk.sizeofStruct = sizedStructName(argType);
        return new TypeInfo(null, "indeterminate", "u64");
    }


    /**
     * The struct whose laid-out size a `sizeof` of this type means, or null when the size is fully known
     * here (a primitive, a pointer, a range). An array gives its element's struct (sizeof an array is its
     * element's size). Such a size is left symbolic (`SIZEOF name`) for the Optimizer to resolve.
     */
    public String sizedStructName(TypeInfo t) {
        if (t == null || t.storage != null || hub.isRangeType(t)) {
            return null;
        }
        String base = t.baseType;
        while (hub.isArrayType(base)) {
            base = hub.arrayElementType(base);
        }
        return hub.model.structs.containsKey(base) ? base : null;
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
        if (cur.type != TokenType.VARREF || !hub.signatures.isKnownType(cur.text)) {
            return null;
        }
        return new TypeInfo(storage, "imut", cur.text);
    }

    /** Size of a type: any pointer = POINTER_SIZE, range = 16, array = its base element. See docs/TypeChecker.md, "Builtin calls". */
    public long computeSizeOf(TypeInfo root, Token at) {
        long total = 0;
        Deque<TypeInfo> work = new java.util.LinkedList<>();
        work.push(root);
        while (!work.isEmpty()) {
            TypeInfo t = work.pop();
            if (t.storage != null) {
                total += TypeCheckerModel.POINTER_SIZE;
                continue;
            }
            if (hub.isRangeType(t)) {
                total += 16;
                continue;
            }
            if (hub.isArrayType(t.baseType)) {
                String elem = t.baseType;
                while (hub.isArrayType(elem)) {
                    elem = hub.arrayElementType(elem);
                }
                work.push(new TypeInfo(null, "imut", elem));
                continue;
            }
            StructInfo si = hub.model.structs.get(t.baseType);
            if (si != null) {
                // A rough estimate only (the real size, with padding and the layout the Optimizer finally
                // settles on, is resolved by SizeofResolutionPass from the symbolic SIZEOF): the hidden
                // ___type word plus the members, so it is never below 8 and never claims a one-byte stride.
                total += 8;
                List<TypeInfo> members = new ArrayList<>(si.members.values());
                for (int i = members.size() - 1; i >= 0; i--) {
                    work.push(members.get(i));
                }
                continue;
            }
            Long primitiveSize = TypeCheckerModel.PRIMITIVE_SIZE.get(t.baseType);
            if (primitiveSize != null) {
                total += primitiveSize;
                continue;
            }
            throw new CompilerException("type", at.file, at.line,
                    "'sizeof' doesn't know the size of '" + t.canonical() + "'");
        }
        return total;
    }

    public TypeInfo checkLenBuiltin(Token op, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.LEN_BUILTIN, op, scope, func));
    }

    /** `len(x)`: constant for arrays and direct string literals, run-time for dynarrays; the 2-arg form scans an unsafe dynarray. See docs/TypeChecker.md, "len and range". */
    public int stepLenBuiltin(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        if (f.phase == 0) {
            List<Token> args = new ArrayList<>();
            Token argsDelineator = op.right;
            if (!argsDelineator.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(argsDelineator.childs.get(0), args);
            }
            f.toks = args;
            if (args.size() == 2) {
                // `len(unsafe_dynarray, terminator)`: unsafe, scans for the terminator; its type must equal the element type (as resize's fill).
                if (!"unsafe".equals(scope.currentSafety)) {
                    throw new CompilerException("type", op.file, op.line,
                            "'len' with a terminator argument can only be used from within 'unsafe' code");
                }
                f.phase = 1;
                return f.call(args.get(0));
            }
            if (args.size() != 1) {
                throw new CompilerException("type", op.file, op.line,
                        "'len' takes one argument, or two (a terminator, for an 'unsafe dynarray'), got "
                                + args.size());
            }
            f.phase = 3;
            return f.call(args.get(0));
        }
        if (f.phase == 1) {
            Token targetExpr = f.toks.get(0);
            TypeInfo targetType = f.got;
            if (!hub.isUnsafeDynArrayType(targetType)) {
                throw new CompilerException("type", targetExpr.file, targetExpr.line,
                        "'len' with a terminator argument requires an 'unsafe dynarray', got '"
                                + targetType.canonical() + "'");
            }
            hub.declarations.unsafeBypass(scope, hub.declarations.udynTag(targetType));
            f.a = targetType;
            f.phase = 2;
            return f.call(f.toks.get(1));
        }
        if (f.phase == 2) {
            Token termExpr = f.toks.get(1);
            TypeInfo targetType = f.a;
            TypeInfo termType = f.got;
            if (!termType.baseType.equals(targetType.unsafeDynArrayElementType.baseType)) {
                throw new CompilerException("type", termExpr.file, termExpr.line,
                        "'len' terminator must match the unsafe dynarray's own element type ('"
                                + targetType.unsafeDynArrayElementType.baseType + "'), got '"
                                + termType.baseType + "'");
            }
            // builtinConstantValue stays unset: BytecodeEmitter then emits a real scan.
            return f.done(new TypeInfo(null, "indeterminate", "u64"));
        }
        Token argExpr = f.toks.get(0);
        TypeInfo argType = f.got;
        if (hub.isUnsafeDynArrayType(argType)) {
            // A bare 1-arg `len` on an unsafe dynarray has no length to read; a specific error beats the generic one below.
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'len' on an 'unsafe dynarray' requires a terminator argument, e.g. "
                            + "'len(arr, '\\0')' -- it has no hidden length to read otherwise");
        }
        if (hub.isDynArrayType(argType)) {
            // Run-time length: builtinConstantValue stays unset so BytecodeEmitter emits LEN.
            return f.done(new TypeInfo(null, "indeterminate", "u64"));
        }
        long length;
        if (hub.isArrayType(argType.baseType)) {
            length = hub.arrayLength(argType.baseType);
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
        op.chk.builtinConstantValue = length;
        return f.done(new TypeInfo(null, "indeterminate", "u64"));
    }


    /** `range(x)`: 0..len for a string literal / fixed array (bounds baked into the type), plain `range` for a safe dynarray, error for an unsafe one. See docs/TypeChecker.md, "len and range". */
    public TypeInfo checkRangeBuiltin(Token op, Scope scope, FuncInfo func) {
        CheckFrame f = new CheckFrame(CheckFrame.RANGE_BUILTIN, op, scope, func);
        return hub.driver.run(f);
    }

    public int stepRangeBuiltin(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        if (f.phase == 0) {
            f.x = rangeBuiltinHead(op, scope, func);
            f.phase = 1;
            return f.call(f.x);
        }
        return f.done(rangeBuiltinTail(op, scope, func, f.x, f.got));
    }

    private Token rangeBuiltinHead(Token op, Scope scope, FuncInfo func) {
        Token argExpr = collectSingleBuiltinArg(op, "range", scope, func).get(0);
        return argExpr;
    }

    private TypeInfo rangeBuiltinTail(Token op, Scope scope, FuncInfo func, Token argExpr, TypeInfo argType) {        if (hub.isUnsafeDynArrayType(argType)) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'range' on an 'unsafe dynarray' has no hidden length to read -- it has no "
                            + "terminator argument to scan for one either, unlike 'len'");
        }
        if (hub.isDynArrayType(argType)) {
            // Run-time length: no builtinConstantValue, no baked-in bounds.
            return new TypeInfo(null, "imut", "range");
        }
        long length;
        if (hub.isArrayType(argType.baseType)) {
            length = hub.arrayLength(argType.baseType);
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
        op.chk.builtinConstantValue = length;
        return new TypeInfo(null, "imut", "range(0," + length + ")");
    }


    /** `deref(p)`: the pointee value with storage stripped (usable with `.` directly). */
    public TypeInfo checkDerefBuiltin(Token op, Scope scope, FuncInfo func) {
        CheckFrame f = new CheckFrame(CheckFrame.DEREF_BUILTIN, op, scope, func);
        return hub.driver.run(f);
    }

    public int stepDerefBuiltin(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        if (f.phase == 0) {
            f.x = derefHead(op, scope, func);
            f.phase = 1;
            return f.call(f.x);
        }
        return f.done(derefTail(op, scope, func, f.x, f.got));
    }

    private Token derefHead(Token op, Scope scope, FuncInfo func) {
        hub.impls.requireNotPureRt(func, op, "'deref'", "it reads memory through a pointer");
        Token argExpr = collectSingleBuiltinArg(op, "deref", scope, func).get(0);
        return argExpr;
    }

    private TypeInfo derefTail(Token op, Scope scope, FuncInfo func, Token argExpr, TypeInfo argType) {        if (argType.storage == null) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'deref' requires a pointer, got '" + argType.canonical() + "'");
        }
        // Safe code may dereference only a pointer proven non-null and alive:
        // an 'auto' pointer, a 'some'-tagged pointer, or one inside a
        // 'match Some(p)' (the same rule '.' member access uses).
        if (hub.locksAndProofs.requiresAliveProof(argType, argExpr, scope)) {
            throw new CompilerException("type", op.file, op.line,
                    "'deref' of a pointer requires the pointer to be proven alive -- "
                            + "'auto', 'some'-tagged, inside 'match Some(...)', or vouched for with "
                            + "'assume match Some(...)' in an 'unsafe assume' block [needs: assume match Some(" + hub.expressions.exprHint(argExpr) + ")]");
        }
        if ("raw".equals(argType.storage) && !hub.declarations.unsafeBypass(scope, "deref")) {
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
        CheckFrame f = new CheckFrame(CheckFrame.CLONE_BUILTIN, op, scope, func);
        return hub.driver.run(f);
    }

    public int stepCloneBuiltin(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        if (f.phase == 0) {
            f.x = cloneHead(op, scope, func);
            f.phase = 1;
            return f.call(f.x);
        }
        return f.done(cloneTail(op, scope, func, f.x, f.got));
    }

    private Token cloneHead(Token op, Scope scope, FuncInfo func) {
        hub.impls.requireNotPureRt(func, op, "'clone'", "allocation depends on heap state (it can fail), so the result is not a function of the arguments");
        // Allocates and registers like `new`: same try/catch discipline and same program-wide flags.
        hub.model.usesOwnsRefDynNew = true;
        hub.model.usesThrow = true;
        if (!op.chk.insideTry && !hub.model.insideThrowingAllocContext) {
            throw new CompilerException("type", op.file, op.line,
                    "'clone' can fail (it throws when the underlying allocation fails) and must be "
                            + "wrapped in 'try ... catch { ... }'");
        }
        Token argExpr = collectSingleBuiltinArg(op, "clone", scope, func).get(0);
        return argExpr;
    }

    private TypeInfo cloneTail(Token op, Scope scope, FuncInfo func, Token argExpr, TypeInfo argType) {        if (argType.storage == null) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'clone' requires a pointer, got '" + argType.canonical() + "'");
        }
        // The source is read, so it is held to the same rule as `deref`: safe code may clone only a pointer proven
        // non-null and alive ('auto', 'some'-tagged, or inside 'match Some'). `clone` itself does no null check
        // on its source.
        if (hub.locksAndProofs.requiresAliveProof(argType, argExpr, scope)) {
            throw new CompilerException("type", op.file, op.line,
                    "'clone' of a pointer requires the pointer to be proven alive -- "
                            + "'auto', 'some'-tagged, inside 'match Some(...)', or vouched for with "
                            + "'assume match Some(...)' in an 'unsafe assume' block [needs: assume match Some(" + hub.expressions.exprHint(argExpr) + ")]");
        }
        if (hub.lifetimes.isScoped(argType)) {
            throw new CompilerException("type", op.file, op.line,
                    "'clone' of '" + argType.canonical() + "': a scoped type (it holds a 'ref some') cannot be cloned, the copy would need its own lifetime contract");
        }
        if ("raw".equals(argType.storage) && !hub.declarations.unsafeBypass(scope, "clone")) {
            // Same as 'deref': a 'raw' pointer's pointee is only as good as the programmer's word, so cloning from
            // one is 'unsafe' on top of the alive proof.
            throw new CompilerException("type", op.file, op.line,
                    "'clone' of a 'raw' pointer is inherently unsafe -- it needs an 'unsafe clone' block (and the "
                            + "alive proof as well: 'match Some(...)', or 'assume match Some(...)' in an 'unsafe assume' block)");
        }
        if (hub.impls.typeReachesDropHook(argType.baseType, new HashSet<>())) {
            throw new CompilerException("type", op.file, op.line,
                    "'clone' of '" + argType.baseType + "' is rejected: it holds a '@drop' resource "
                            + "(a handle cannot be duplicated)");
        }
        // A failed allocation throws, so what comes back is never null: `owns some`, like `new`.
        return argType.withStorage("owns").withSome(true); // keeps dynarray/array shape, mutability and alias
    }


    public TypeInfo checkCallBuiltin(Token op, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.CALL_BUILTIN, op, scope, func));
    }

    /** `call(fp, args...)`: variable arity, unsafe only; arity, argument types and result type come from the pointer's recorded signature. */
    public int stepCallBuiltin(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        if (f.phase == 0) {
            if (!hub.declarations.unsafeBypass(scope, "call")) {
                throw new CompilerException("type", op.file, op.line,
                        "'call' can only be used from within 'unsafe' code");
            }
            // Banned in @pure: the callee's purity cannot be verified at compile time.
            if (hub.impls.isPureFunc(func)) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + func.name + "' is '@pure' and cannot use 'call' -- the target's purity can "
                                + "never be verified at compile time");
            }
            List<Token> args = new ArrayList<>();
            if (!op.right.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(op.right.childs.get(0), args);
            }
            if (args.isEmpty()) {
                throw new CompilerException("type", op.file, op.line,
                        "'call' requires at least a function-pointer argument");
            }
            f.toks = args;
            f.phase = 1;
            return f.call(args.get(0));
        }
        if (f.phase == 1) {
            Token funcPtrExpr = f.toks.get(0);
            TypeInfo funcPtrType = f.got;
            if (funcPtrType.funcPtrParamTypes == null) {
                throw new CompilerException("type", funcPtrExpr.file, funcPtrExpr.line,
                        "'call''s first argument must be a function pointer, got '"
                                + funcPtrType.canonical() + "'");
            }
            List<Token> callArgs = f.toks.subList(1, f.toks.size());
            if (callArgs.size() != funcPtrType.funcPtrParamTypes.size()) {
                throw new CompilerException("type", op.file, op.line,
                        "'call' passes " + callArgs.size() + " argument(s), but the function pointer's "
                                + "signature declares " + funcPtrType.funcPtrParamTypes.size());
            }
            f.a = funcPtrType;
            f.i = 0;
        } else {
            List<Token> callArgs = f.toks.subList(1, f.toks.size());
            int i = f.i++;
            TypeInfo funcPtrType = f.a;
            TypeInfo argType = f.got;
            TypeInfo paramType = funcPtrType.funcPtrParamTypes.get(i);
            if (!hub.acceptsAdaptingLiteral(paramType, argType, callArgs.get(i))) {
                throw new CompilerException("type", callArgs.get(i).file, callArgs.get(i).line,
                        "'call' argument " + (i + 1) + " expects '" + paramType.canonical() + "', got '"
                                + argType.canonical() + "'");
            }
            hub.expressions.markMovedIfOwned(paramType, callArgs.get(i), scope);
        }
        if (f.i < f.toks.size() - 1) {
            f.phase = 2;
            return f.call(f.toks.get(f.i + 1));
        }
        TypeInfo funcPtrType = f.a;
        op.chk.invokeArgCount = f.toks.size() - 1;
        op.chk.resolvedCallConvention = funcPtrType.funcPtrCallConvention;
        return f.done(funcPtrType.funcPtrReturnType);
    }


    public TypeInfo checkBitsBuiltin(String name, Token op, Scope scope, FuncInfo func) {
        CheckFrame f = new CheckFrame(CheckFrame.BITS_BUILTIN, op, scope, func);
        f.s1 = name;
        return hub.driver.run(f);
    }

    /** `bits_*` builtins: integer operands only, both sides one type (literals adapt), result that type; `bits_not` is unary. */
    public int stepBitsBuiltin(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        String name = f.s1;
        if (f.phase == 0) {
            List<Token> args = new ArrayList<>();
            if (!op.right.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(op.right.childs.get(0), args);
            }
            f.toks = args;
            if (name.equals("bits_not")) {
                // bits_not(a): the complement of `a`, truncated to a's own width (u8: bits_not(0) == 255; signed: two's complement).
                if (args.size() != 1) {
                    throw new CompilerException("type", op.file, op.line,
                            "'" + name + "' takes exactly one argument, got " + args.size());
                }
                f.phase = 1;
                return f.call(args.get(0));
            }
            if (args.size() != 2) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + name + "' takes exactly two arguments, got " + args.size());
            }
            f.phase = 2;
            return f.call(args.get(0));
        }
        List<Token> args = f.toks;
        if (f.phase == 1) {
            TypeInfo only = f.got;
            if (!TypeCheckerModel.INTEGER_TYPES.contains(only.baseType)) {
                throw new CompilerException("type", args.get(0).file, args.get(0).line,
                        "'" + name + "' requires an integer type, got '" + only.canonical() + "'");
            }
            if (only.literalValue != null && !TypeCheckerModel.literalFits(only.literalValue, only.baseType)) {
                throw new CompilerException("type", args.get(0).file, args.get(0).line,
                        "'" + name + "': literal " + only.literalValue + " does not fit in '" + only.baseType + "'");
            }
            return f.done(new TypeInfo(null, "indeterminate", only.baseType));
        }
        if (f.phase == 2) {
            f.a = f.got;
            f.phase = 3;
            return f.call(args.get(1));
        }
        TypeInfo leftType = f.a;
        TypeInfo rightType = f.got;
        return f.done(bitsTail(name, op, args, leftType, rightType));
    }

    private TypeInfo bitsTail(String name, Token op, List<Token> args, TypeInfo leftType, TypeInfo rightType) {
        if (!TypeCheckerModel.INTEGER_TYPES.contains(leftType.baseType)) {
            throw new CompilerException("type", args.get(0).file, args.get(0).line,
                    "'" + name + "' requires an integer type, got '" + leftType.canonical() + "'");
        }
        if (!TypeCheckerModel.INTEGER_TYPES.contains(rightType.baseType)) {
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
            if (rightLit && TypeCheckerModel.literalFits(rightType.literalValue, leftType.baseType)) {
                hub.adaptLiteral(args.get(1), leftType);
                rightType = leftType;
            } else if (leftLit && TypeCheckerModel.literalFits(leftType.literalValue, rightType.baseType)) {
                hub.adaptLiteral(args.get(0), rightType);
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


    public TypeInfo checkMemcopyBuiltin(Token op, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.MEMCOPY_BUILTIN, op, scope, func));
    }

    /** `memcopy(raw dest, u64 bytes, src)`: unsafe only; returns the destination's type. Constructing a `raw` is what is gated, not holding one. */
    public int stepMemcopyBuiltin(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        if (f.phase == 0) {
            if (!hub.declarations.unsafeBypass(scope, "memcopy")) {
                throw new CompilerException("type", op.file, op.line,
                        "'memcopy' can only be used from within 'unsafe' code");
            }
            List<Token> args = new ArrayList<>();
            if (!op.right.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(op.right.childs.get(0), args);
            }
            if (args.size() != 3) {
                throw new CompilerException("type", op.file, op.line,
                        "'memcopy' takes exactly three arguments (destination, byte count, source), got "
                                + args.size());
            }
            f.toks = args;
            f.phase = 1;
            return f.call(args.get(0));
        }
        List<Token> args = f.toks;
        if (f.phase == 1) {
            TypeInfo destType = f.got;
            if (!"raw".equals(destType.storage)) {
                throw new CompilerException("type", args.get(0).file, args.get(0).line,
                        "'memcopy''s destination must be a 'raw' pointer, got '" + destType.canonical() + "'");
            }
            f.a = destType;
            f.phase = 2;
            return f.call(args.get(1));
        }
        if (f.phase == 2) {
            TypeInfo countType = f.got;
            if (!hub.isPlainU64(countType)) {
                throw new CompilerException("type", args.get(1).file, args.get(1).line,
                        "'memcopy''s byte count must be a plain 'u64', got '" + countType.canonical() + "'");
            }
            f.phase = 3;
            return f.call(args.get(2));
        }
        TypeInfo sourceType = f.got;
        if (sourceType.storage == null) {
            throw new CompilerException("type", args.get(2).file, args.get(2).line,
                    "'memcopy''s source must be a pointer (any storage), got '" + sourceType.canonical() + "'");
        }
        return f.done(f.a);
    }








    /** `dyn([..])`: a fresh owns dynarray from an array literal (checkArrayLiteral validates it); builtinConstantValue carries the element count to BytecodeEmitter. */
    public TypeInfo checkDynBuiltin(Token op, Scope scope, FuncInfo func) {
        hub.impls.requireNotPureRt(func, op, "'dyn'", "allocation depends on heap state (it can fail), so the result is not a function of the arguments");
        return checkDynBuiltinCore(op, scope, func, false);
    }

    public TypeInfo checkDynBuiltinCore(Token op, Scope scope, FuncInfo func, boolean isUnsafe) {
        CheckFrame f = new CheckFrame(CheckFrame.DYN_BUILTIN, op, scope, func);
        f.p = isUnsafe;
        return hub.driver.run(f);
    }

    /**
     * Shared by `dyn([..])` and `unsafe dyn([..])` (checkUnsafeDynWrapper cannot delegate to the ordinary dispatch). The safe form allocates, so it needs
     * try/catch like `new` (op.insideTry or insideThrowingAllocContext) and sets usesThrow; the unsafe form is exempt. See docs/TypeChecker.md, "dyn and resize".
     */
    public int stepDynBuiltin(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        boolean isUnsafe = f.p;
        if (f.phase == 0) {
            hub.model.newDynDepth++;
            hub.model.usesOwnsRefDynNew = true;
            if (!isUnsafe) {
                hub.model.usesThrow = true;
                if (!op.chk.insideTry && !hub.model.insideThrowingAllocContext) {
                    throw new CompilerException("type", op.file, op.line,
                            "'dyn' can fail (it throws when the underlying allocation fails) and must be "
                                    + "wrapped in 'try ... catch { ... }'");
                }
            }
            Token argExpr = collectSingleBuiltinArg(op, "dyn", scope, func).get(0);
            f.x = argExpr;
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
                if (!hub.signatures.isKnownType(elemTok.text)) {
                    throw new CompilerException("type", elemTok.file, elemTok.line,
                            "'dyn:<" + elemTok.text + ">': '" + elemTok.text + "' is not a known type");
                }
                TypeInfo emptyElementType = new TypeInfo(null, "mut", elemTok.text);
                op.chk.builtinConstantValue = 0L;
                hub.model.newDynDepth--;
                return f.done(isUnsafe ? TypeInfo.unsafeDynArray("indeterminate", emptyElementType)
                        : TypeInfo.dynArray("indeterminate", emptyElementType).withSome(true));
            }
            if (argIsArrayLiteral && argExpr.childs.isEmpty()) {
                throw new CompilerException("type", argExpr.file, argExpr.line,
                        "an empty array literal has no element type -- write 'dyn:<T>([])' (e.g. "
                                + "'dyn:<char>([])') to construct an empty dynarray");
            }
            if (!argIsArrayLiteral) {
                // `dyn(text)`: a dynarray of chars; the element is `imut char`, like string indexing.
                f.phase = 1;
                return f.call(argExpr);
            }
            f.phase = 2;
            return f.call(new CheckFrame(CheckFrame.ARRAY_LITERAL, argExpr, scope, func));
        }
        Token argExpr = f.x;
        if (f.phase == 1) {
            TypeInfo argType = f.got;
            if (argType.baseType.equals("string")) {
                op.chk.dynFromStringSource = true;
                TypeInfo elementType = new TypeInfo(null, "imut", "char");
                hub.model.newDynDepth--;
                return f.done(isUnsafe ? TypeInfo.unsafeDynArray("indeterminate", elementType)
                        : TypeInfo.dynArray("indeterminate", elementType).withSome(true));
            }
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'dyn' requires an array literal or a string argument, e.g. 'dyn([0,1,2,3])' or 'dyn(text)'");
        }
        TypeInfo fixedArrayType = f.got;
        int count = hub.arrayLength(fixedArrayType.baseType);
        // Reuse the element type checkArrayLiteral already computed: resolving the elements again re-ran the move check and wrongly reported `dyn([a, b])`.
        TypeInfo elementType = fixedArrayType.arrayElementType;
        op.chk.builtinConstantValue = (long) count;
        hub.model.newDynDepth--;
        return f.done(isUnsafe ? TypeInfo.unsafeDynArray("indeterminate", elementType)
                : TypeInfo.dynArray("indeterminate", elementType).withSome(true));
    }

    public TypeInfo checkUnsafeDynWrapper(Token op, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.UNSAFE_DYN_WRAPPER, op, scope, func));
    }

    /** `unsafe dyn(..)`: runs the dyn core directly (the inner CALL must resolve to the unsafe type) and sets op.left.resolvedType by hand. */
    public int stepUnsafeDynWrapper(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        if (f.phase == 0) {
            hub.impls.requireNotPure(func, op, "'unsafe dyn(...)'", "unsafe code can do anything the compiler cannot check, so purity could not be guaranteed");
            if (!"unsafe".equals(scope.currentSafety)) {
                throw new CompilerException("type", op.file, op.line,
                        "'unsafe dyn(...)' can only be used from within 'unsafe' code");
            }
            if (op.left.type != TokenType.OPERATOR || !op.left.text.equals("CALL")
                    || op.left.left.type != TokenType.VARREF || !op.left.left.text.equals("dyn")) {
                throw new CompilerException("type", op.left.file, op.left.line,
                        "'unsafe' must directly wrap a 'dyn(...)' call, e.g. 'unsafe dyn([0,1,2,3])'");
            }
            CheckFrame core = new CheckFrame(CheckFrame.DYN_BUILTIN, op.left, scope, func);
            core.p = true;
            f.phase = 1;
            return f.call(core);
        }
        TypeInfo result = f.got;
        hub.declarations.unsafeBypass(scope, hub.declarations.udynTag(result));
        op.left.chk.resolvedType = result.canonical();
        return f.done(result);
    }


    public TypeInfo checkResizeBuiltin(Token op, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.RESIZE_BUILTIN, op, scope, func));
    }

    /** `resize(d, n, fill)`: needs a `mut` dynarray, allowed in safe code; growing needs an explicit fill. Moves the source. See docs/TypeChecker.md, "dyn and resize". */
    public int stepResizeBuiltin(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        if (f.phase == 0) {
            hub.impls.requireNotPureRt(func, op, "'resize'", "allocation depends on heap state (it can fail), so the result is not a function of the arguments");
            // A direct `resize` in a dynarray `for` body is rejected here; indirect ones by checkDynArrayForLoopResizeSafety.
            if (scope.insideDynArrayForLoopBody) {
                throw new CompilerException("type", op.file, op.line,
                        "'resize' cannot be called inside a \"for i in dynarrayExpr{...}\" loop's own body -- "
                                + "resizing the dynarray being iterated (even a different one, conservatively) "
                                + "while iterating it is not allowed");
            }
            hub.model.funcsCallingResizeDirectly.add(func);
            List<Token> args = new ArrayList<>();
            if (!op.right.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(op.right.childs.get(0), args);
            }
            if (args.size() != 2 && args.size() != 3) {
                throw new CompilerException("type", op.file, op.line,
                        "'resize' takes two arguments (an unsafe dynarray, the new count) or three (a safe "
                                + "dynarray, the new count, a fill value for any newly-created slots), got "
                                + args.size());
            }
            f.toks = args;
            f.phase = 1;
            return f.call(args.get(0));
        }
        List<Token> args = f.toks;
        if (f.phase == 1) {
            TypeInfo dynArrType = f.got;
            boolean isUnsafeTarget = hub.isUnsafeDynArrayType(dynArrType);
            if (!isUnsafeTarget && !hub.isDynArrayType(dynArrType)) {
                throw new CompilerException("type", args.get(0).file, args.get(0).line,
                        "'resize''s first argument must be a dynarray, got '" + dynArrType.canonical() + "'");
            }
            if (!"mut".equals(dynArrType.mutability)) {
                throw new CompilerException("type", args.get(0).file, args.get(0).line,
                        "'resize' requires a 'mut' dynarray, got '" + dynArrType.canonical() + "'");
            }
            // Unsafe dynarray: a bare realloc with no fill (no hidden length, no initialisation guarantee).
            if (isUnsafeTarget) {
                if (!hub.declarations.unsafeBypass(scope, hub.declarations.udynTag(dynArrType))) {
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
                if (op.chk.insideTry) {
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
                hub.model.usesThrow = true;
                if (!op.chk.insideTry && !hub.model.insideThrowingAllocContext) {
                    throw new CompilerException("type", op.file, op.line,
                            "'resize' can fail (it throws when the underlying reallocation fails) and must be "
                                    + "wrapped in 'try ... catch { ... }'");
                }
            }
            f.a = dynArrType;
            f.p = isUnsafeTarget;
            f.phase = 2;
            return f.call(args.get(1));
        }
        TypeInfo dynArrType = f.a;
        boolean isUnsafeTarget = f.p;
        TypeInfo elementType;
        if (f.phase == 2) {
            TypeInfo countType = f.got;
            if (!hub.isPlainU64(countType)) {
                throw new CompilerException("type", args.get(1).file, args.get(1).line,
                        "'resize''s new count must be a plain 'u64', got '" + countType.canonical() + "'");
            }
            if (!isUnsafeTarget) {
                f.phase = 3;
                return f.call(args.get(2));
            }
            elementType = dynArrType.unsafeDynArrayElementType;
        } else {
            elementType = dynArrType.dynArrayElementType;
            TypeInfo fillType = f.got;
            if (!fillType.baseType.equals(elementType.baseType)) {
                throw new CompilerException("type", args.get(2).file, args.get(2).line,
                        "'resize''s fill value must match the dynarray's own element type ('"
                                + elementType.baseType + "'), got '" + fillType.baseType + "'");
            }
        }
        // `resize` takes ownership and returns a new owns (a realloc may move): the source is moved by hand via markMovedIfOwned, both variants.
        hub.expressions.markMovedIfOwned(dynArrType, args.get(0), scope);
        TypeInfo result = isUnsafeTarget ? TypeInfo.unsafeDynArray("indeterminate", elementType)
                : TypeInfo.dynArray("indeterminate", elementType).withSome(true);
        ProofKills.kill(scope.activeMatchPatterns, op, "'resize' (the block may move or shrink, freeing what was in it)",
                args.isEmpty() ? null : TypeCheckerModel.memberPathRoot(args.get(0)), hub.declarations.destroyedTypes(result));
        return f.done(result);
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
        if (!hub.isStructByValueReturn(candidate.returnType)) {
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
        op.chk.isStructRvoCall = true;
    }

    public TypeInfo checkMethodCall(Token op, Scope scope, FuncInfo func, boolean eligibleForRvoHere) {
        CheckFrame f = new CheckFrame(CheckFrame.METHOD_CALL, op, scope, func);
        f.p = eligibleForRvoHere;
        return hub.driver.run(f);
    }

    /** `receiver.method(args)`: the receiver only selects the impls to search and is not injected as an argument; resolved at compile time, no run-time dispatch. See docs/TypeChecker.md, "Method calls". */
    public int stepMethodCall(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        boolean eligibleForRvoHere = f.p;
        Token dotNode = op.left;
        if (f.phase == 9) {
            return f.done(f.got);
        }
        if (f.phase == 0) {
            Token methodNameTok = dotNode.right;
            if (methodNameTok.type != TokenType.VARREF) {
                throw new CompilerException("type", dotNode.file, dotNode.line,
                        "expected a method name after '.'");
            }
            String methodName = methodNameTok.text;

            // `TypeName.method(..)` (static call): a bare non-variable name that is a struct/enum goes to checkStaticMethodCall; generic receivers arrive already monomorphized.
            if (dotNode.left.type == TokenType.VARREF && scope.lookup(dotNode.left.text) == null
                    && (hub.model.structs.containsKey(dotNode.left.text) || hub.model.enums.containsKey(dotNode.left.text))) {
                f.phase = 9;
                return f.call(hub.locksAndProofs.staticMethodCallFrame(op, dotNode.left.text, methodName, scope, func, eligibleForRvoHere));
            }

            // `Lib.fn(..)`: a bare library name.
            // the library counterpart of the static-method case just above.
            if (dotNode.left.type == TokenType.VARREF && scope.lookup(dotNode.left.text) == null
                    && hub.model.libraries.containsKey(dotNode.left.text)) {
                CheckFrame lib = new CheckFrame(CheckFrame.LIBRARY_CALL, op, scope, func);
                lib.s1 = dotNode.left.text;
                lib.s2 = methodName;
                f.phase = 9;
                return f.call(lib);
            }

            // Resolved as an expression, not via checkDot (a method is not a member).
            f.phase = 1;
            return f.call(dotNode.left);
        }
        Token methodNameTok = dotNode.right;
        String methodName = methodNameTok.text;
        if (f.phase == 1) {
            TypeInfo receiverType = f.got;

            if (hub.model.interfaces.containsKey(receiverType.baseType)) {
                CheckFrame disp = new CheckFrame(CheckFrame.INTERFACE_DISPATCH, op, scope, func);
                disp.s1 = receiverType.baseType;
                disp.s2 = methodName;
                f.phase = 9;
                return f.call(disp);
            }

            List<ImplInfo> impls = hub.model.implsByConcreteType.get(receiverType.baseType);
            if (impls == null) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + receiverType.baseType + "' has no method named '" + methodName + "'");
            }

            // `recv.method:<U>(..)`: explicit generic-method type args survive GenericsExpander and are dispatched separately (never in impl.methods).
            if (methodNameTok.genericArgs != null) {
                CheckFrame gen = new CheckFrame(CheckFrame.GENERIC_METHOD_CALL, op, scope, func);
                gen.p = eligibleForRvoHere;
                gen.s1 = methodName;
                gen.data = new Object[]{receiverType, impls};
                f.phase = 9;
                return f.call(gen);
            }

            f.a = receiverType;
            f.data = impls;
            f.toks = new ArrayList<>();
            Token argsDelineator = op.right;
            if (!argsDelineator.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(argsDelineator.childs.get(0), f.toks);
            }
            f.phase = 2;
            return f.callAll(f.toks);
        }
        @SuppressWarnings("unchecked")
        List<ImplInfo> impls = (List<ImplInfo>) f.data;
        return f.done(methodCallTail(op, dotNode, scope, func, eligibleForRvoHere, methodName, f.a, impls, f.toks, f.gotFrame.list));
    }

    private TypeInfo methodCallTail(Token op, Token dotNode, Scope scope, FuncInfo func, boolean eligibleForRvoHere,
            String methodName, TypeInfo receiverType, List<ImplInfo> impls, List<Token> argNodes, List<TypeInfo> argTypes) {

        boolean nameExistsAsStatic = false;
        for (ImplInfo impl : impls) {
            List<FuncInfo> overloadCandidates = impl.candidates(methodName, false);
            if (overloadCandidates.isEmpty()) {
                if (impl.staticMethods.containsKey(methodName)) {
                    nameExistsAsStatic = true;
                }
                continue;
            }
            for (FuncInfo candidate : hub.orderByLiteralExactness(overloadCandidates, argTypes)) {
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
                checkImplMethodVisibility(candidate, impl, methodName, op, func);
                op.chk.resolvedCallTarget = candidate.mangledName;
                op.chk.resolvedCallConvention = candidate.callConvention;
                hub.controlFlow.requireThrowsWrapping(candidate, op);
                hub.lifetimes.onCall(candidate, argNodes, scope, op);
                for (int i = 0; i < argNodes.size(); i++) {
                    hub.expressions.markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
                }
                // Per-call-site unwind lists; see docs/TypeChecker.md, "Method calls".
                op.chk.destructOnExit = hub.statements.collectOwnsToDestruct(scope, scope.functionRootScope, null);
                op.chk.unlockOnExit = hub.statements.collectLockReleasesToBoundary(scope, scope.functionRootScope);
                if (candidate == func && scope.suspendsSelfRecursion) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot call '" + func.name + "' from within its own base-case match "
                                    + "block -- the func loses its recursive privileges there");
                }
                if (candidate == func && hub.impls.getDecorator(func.funcToken.decorators, "recursive") != null) {
                    hub.statements.requireRecursiveCallReturnSiteOnly(func, op);
                    hub.statements.requireRecursiveCallWithinProof(func, op, argNodes, argTypes, scope);
                }
                hub.locksAndProofs.requireMethodLockProofs(candidate, dotNode.left, argNodes, scope, op);
                hub.impls.requirePureCalleeIfPure(func, candidate, op);
                hub.model.callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
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
                        + "' matching argument types (" + hub.locksAndProofs.describeTypes(argTypes) + ")"
                        + hub.declarations.unavailableNote(receiverType.baseType + "." + methodName));
        }


    public TypeInfo checkGenericMethodCall(Token op, Token dotNode, TypeInfo receiverType, List<ImplInfo> impls,
            String methodName, Token methodNameTok, Scope scope, FuncInfo func, boolean eligibleForRvoHere) {
        CheckFrame f = new CheckFrame(CheckFrame.GENERIC_METHOD_CALL, op, scope, func);
        f.p = eligibleForRvoHere;
        f.s1 = methodName;
        f.data = new Object[]{receiverType, impls};
        return hub.driver.run(f);
    }

    /** `recv.method:<U>(..)`: instantiates (memoized) the residual-generic method and matches the arguments against that single candidate. */
    public int stepGenericMethodCall(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        boolean eligibleForRvoHere = f.p;
        String methodName = f.s1;
        Token dotNode = op.left;
        Token methodNameTok = dotNode.right;
        if (f.phase == 0) {
            Object[] in = (Object[]) f.data;
            TypeInfo receiverType = (TypeInfo) in[0];
            @SuppressWarnings("unchecked")
            List<ImplInfo> impls = (List<ImplInfo>) in[1];

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

            List<FuncInfo> toCheck = new ArrayList<>();
            FuncInfo candidate = instantiateGenericMethod(owningImpl, template, argNames, methodNameTok, toCheck);
            f.data = new Object[]{candidate, owningImpl};
            if (!toCheck.isEmpty()) {
                f.phase = 2;
                return f.call(hub.statements.functionBodyFrame(toCheck.get(0)));
            }
        }
        if (f.phase == 0 || f.phase == 2) {
            methodNameTok.genericArgs = null;
            f.toks = new ArrayList<>();
            Token argsDelineator = op.right;
            if (!argsDelineator.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(argsDelineator.childs.get(0), f.toks);
            }
            f.phase = 1;
            return f.callAll(f.toks);
        }
        Object[] st = (Object[]) f.data;
        return f.done(genericMethodTail(op, dotNode, scope, func, eligibleForRvoHere, methodName, (FuncInfo) st[0], (ImplInfo) st[1], f.toks, f.gotFrame.list));
    }

    private TypeInfo genericMethodTail(Token op, Token dotNode, Scope scope, FuncInfo func, boolean eligibleForRvoHere,
            String methodName, FuncInfo candidate, ImplInfo owningImpl, List<Token> argNodes, List<TypeInfo> argTypes) {
        if (candidate.paramTypes.size() != argTypes.size()) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + methodName + "' expects " + candidate.paramTypes.size() + " argument(s), got "
                            + argTypes.size());
        }
        for (int i = 0; i < argTypes.size(); i++) {
            if (!hub.argSatisfiesParam(candidate.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + methodName + "' argument " + (i + 1) + " type mismatch: expected '"
                                + candidate.paramTypes.get(i).canonical() + "', got '"
                                + argTypes.get(i).canonical() + "'");
            }
        }
        hub.retagLiteralArgs(candidate.paramTypes, argTypes, argNodes);

        checkImplMethodVisibility(candidate, owningImpl, methodName, op, func);
        op.chk.resolvedCallTarget = candidate.mangledName;
        op.chk.resolvedCallConvention = candidate.callConvention;
        hub.controlFlow.requireThrowsWrapping(candidate, op);
        hub.lifetimes.onCall(candidate, argNodes, scope, op);
        for (int i = 0; i < argNodes.size(); i++) {
            hub.expressions.markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
        }
        // Per-call-site unwind lists; see docs/TypeChecker.md, "Method calls".
        op.chk.destructOnExit = hub.statements.collectOwnsToDestruct(scope, scope.functionRootScope, null);
        op.chk.unlockOnExit = hub.statements.collectLockReleasesToBoundary(scope, scope.functionRootScope);
        hub.locksAndProofs.requireMethodLockProofs(candidate, dotNode.left, argNodes, scope, op);
        hub.impls.requirePureCalleeIfPure(func, candidate, op);
        hub.model.callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
        markStructRvoMethodCall(op, candidate, eligibleForRvoHere);
        return candidate.returnType;
        }


    /** Materializes (memoized per type-argument list) a callable FuncInfo for a residual-generic method template; the template is already tree-built, so deepCloneCheckedTree is used. */
    public FuncInfo instantiateGenericMethod(ImplInfo impl, Token template, List<String> argNames, Token usageTok,
            List<FuncInfo> toCheck) {
        String key = template.declName().text + "<" + String.join(",", argNames) + ">";
        FuncInfo existing = impl.genericMethodInstances.get(key);
        if (existing != null) {
            return existing;
        }

        if (template.typeParamBounds != null) {
            for (int i = 0; i < template.typeParamBounds.size(); i++) {
                String bound = template.typeParamBounds.get(i);
                if (bound != null && !hub.controlFlow.structImplementsInterface(argNames.get(i), bound)) {
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

        Token clone = hub.impls.deepCloneCheckedTree(template);
        // `self` is substituted here for residual-generic templates (collectImpl skips them).
        hub.impls.substituteSelfInPlace(clone.paramsTok().childs, impl.concreteName);
        hub.impls.substituteSelfInPlace(clone.returnTypeTokens(), impl.concreteName);
        substituteGenericMethodParams(clone, subMap);
        clone.typeParams = null;
        clone.typeParamBounds = null;

        FuncInfo info = new FuncInfo();
        info.funcToken = clone;
        hub.signatures.buildFuncInfo(clone, info);
        info.enclosingImpl = impl;
        info.declOrder = hub.model.pendingDeclOrder.getOrDefault(impl.declTok, Integer.MAX_VALUE);
        info.safetyTag = impl.safetyTag;
        // Forced public: reached only through an instance call, whose impl-level @pub check follows.
        info.isPublic = true;
        String methodNamePart = info.name;
        info.mangledName = (impl.interfaceName != null
                ? impl.concreteName + "__" + impl.interfaceName + "_" + methodNamePart
                : impl.concreteName + "_" + methodNamePart) + "__" + String.join("_", argNames);
        hub.declarations.validateMethodLocks(clone.decorators, info.paramNames, info.paramTypes, impl.concreteName);

        // Memoized before the body is checked: guards recursive self-instantiation and avoids mutating implInfo.methods while it is iterated.
        impl.genericMethodInstances.put(key, info);
        // BytecodeEmitter walks the impl root's childs, not genericMethodInstances.
        impl.declTok.childs.add(clone);

        toCheck.add(info);   // the caller checks the body as a nested frame

        return info;
    }

    /** Blanket VARREF-text substitution of a residual-generic method's type parameters (post-GenericsExpander counterpart of its substituteParams). */
    public void substituteGenericMethodParams(Token root, Map<String, String> subMap) {
        Deque<Token> work = new java.util.LinkedList<>();   // may hold null children
        work.push(root);
        while (!work.isEmpty()) {
            Token node = work.pop();
            if (node == null) {
                continue;
            }
            if (node.type == TokenType.VARREF && subMap.containsKey(node.text)) {
                node.text = subMap.get(node.text);
            }
            work.push(node.left);
            work.push(node.right);
            work.push(node.next);
            if (node.childs != null) {
                for (Token child : node.childs) {
                    work.push(child);
                }
            }
            if (node.sub != null) {
                for (Token child : node.sub) {
                    work.push(child);
                }
            }
            if (node.genericArgs != null) {
                for (List<Token> group : node.genericArgs) {
                    for (Token child : group) {
                        work.push(child);
                    }
                }
            }
            if (node.typeBound != null) {
                for (Token child : node.typeBound) {
                    work.push(child);
                }
            }
        }
    }

    public TypeInfo checkInterfaceMethodDispatch(Token op, Token dotNode, String interfaceName, String methodName,
            Scope scope, FuncInfo func) {
        CheckFrame f = new CheckFrame(CheckFrame.INTERFACE_DISPATCH, op, scope, func);
        f.s1 = interfaceName;
        f.s2 = methodName;
        return hub.driver.run(f);
    }

    /** `c.foo(args)` on a bare interface type: uses the synthesized dispatcher for (interface, method); one candidate, no overloads, no impl bookkeeping. See docs/TypeChecker.md, "Method calls". */
    public int stepInterfaceDispatch(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        String interfaceName = f.s1;
        String methodName = f.s2;
        if (f.phase == 0) {

            List<FuncInfo> overloads = hub.model.functions.get(hub.accessAndAsync.interfaceDispatcherName(interfaceName, methodName));
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
            f.data = dispatcher;
            f.toks = new ArrayList<>();
            Token argsDelineator = op.right;
            if (!argsDelineator.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(argsDelineator.childs.get(0), f.toks);
            }
            f.phase = 1;
            return f.callAll(f.toks);
        }
        return f.done(interfaceDispatchTail(op, scope, func, interfaceName, methodName, (FuncInfo) f.data, f.toks, f.gotFrame.list));
    }

    private TypeInfo interfaceDispatchTail(Token op, Scope scope, FuncInfo func, String interfaceName, String methodName,
            FuncInfo dispatcher, List<Token> argNodes, List<TypeInfo> argTypes) {
        if (dispatcher.paramTypes.size() != argTypes.size()) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + methodName + "' on interface '" + interfaceName + "' expects "
                            + dispatcher.paramTypes.size() + " argument(s), got " + argTypes.size());
        }
        for (int i = 0; i < argTypes.size(); i++) {
            if (!hub.argSatisfiesParam(dispatcher.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + methodName + "' on interface '" + interfaceName + "' argument " + (i + 1)
                                + ": expected '" + dispatcher.paramTypes.get(i).canonical() + "', got '"
                                + argTypes.get(i).canonical() + "'");
            }
        }
        hub.retagLiteralArgs(dispatcher.paramTypes, argTypes, argNodes);
        op.chk.resolvedCallTarget = dispatcher.mangledName;
        op.chk.resolvedCallConvention = dispatcher.callConvention;
        for (int i = 0; i < argNodes.size(); i++) {
            hub.expressions.markMovedIfOwned(dispatcher.paramTypes.get(i), argNodes.get(i), scope);
        }
        hub.impls.requirePureCalleeIfPure(func, dispatcher, op);
        hub.model.callEdges.add(new CallEdge(func, dispatcher, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
        return dispatcher.returnType;
        }

}

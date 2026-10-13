package caspien.typechecker;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;
import caspien.parser.Parser;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 6: type and logic checking over the import-resolved, tree-built program.
 * Type equality in comparisons is base-type only; see docs/TypeChecker.md, "Design decisions".
 */

/** TypeCheckerLocksAndProofs: match @lock, lookups, casts, alive and bounds proofs (part 9 of 11; see README.md in this folder). */
public final class TypeCheckerLocksAndProofs {
    private final TypeChecker hub;

    TypeCheckerLocksAndProofs(TypeChecker hub) {
        this.hub = hub;
    }

    /** `TypeName.method(..)`: static twin of checkMethodCall; the concrete name comes straight from the type name. */
    public TypeInfo checkStaticMethodCall(Token op, String concreteName, String methodName, Scope scope,
            FuncInfo func, boolean eligibleForRvoHere) {
        return hub.driver.run(staticMethodCallFrame(op, concreteName, methodName, scope, func, eligibleForRvoHere));
    }

    public CheckFrame staticMethodCallFrame(Token op, String concreteName, String methodName, Scope scope,
            FuncInfo func, boolean eligibleForRvoHere) {
        CheckFrame f = new CheckFrame(CheckFrame.STATIC_METHOD_CALL, op, scope, func);
        f.s1 = concreteName;
        f.s2 = methodName;
        f.p = eligibleForRvoHere;
        return f;
    }

    public int stepStaticMethodCall(CheckFrame f) {
        Token op = f.node;
        String concreteName = f.s1;
        String methodName = f.s2;
        if (f.phase == 0) {
            List<ImplInfo> impls = hub.model.implsByConcreteType.get(concreteName);
            if (impls == null) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + concreteName + "' has no static method named '" + methodName + "'");
            }

            List<Token> argNodes = new ArrayList<>();
            Token argsDelineator = op.right;
            if (!argsDelineator.childs.isEmpty()) {
                collectCommaArgs(argsDelineator.childs.get(0), argNodes);
            }
            f.toks = argNodes;
            f.phase = 1;
            return f.callAll(argNodes);
        }
        return f.done(staticMethodCallType(op, concreteName, methodName, f.scope, f.func, f.p, f.toks, f.gotFrame.list));
    }

    private TypeInfo staticMethodCallType(Token op, String concreteName, String methodName, Scope scope,
            FuncInfo func, boolean eligibleForRvoHere, List<Token> argNodes, List<TypeInfo> argTypes) {
        List<ImplInfo> impls = hub.model.implsByConcreteType.get(concreteName);
        boolean nameExistsAsInstance = false;
        for (ImplInfo impl : impls) {
            List<FuncInfo> overloadCandidates = impl.candidates(methodName, true);
            if (overloadCandidates.isEmpty()) {
                if (impl.methods.containsKey(methodName)) {
                    nameExistsAsInstance = true;
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
                hub.calls.checkImplMethodVisibility(candidate, impl, methodName, op, func);
                op.chk.resolvedCallTarget = candidate.mangledName;
                op.chk.resolvedCallConvention = candidate.callConvention;
                hub.controlFlow.requireThrowsWrapping(candidate, op);
                hub.impls.checkFsPolicyAtCall(candidate, op, argNodes, scope);
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
                hub.impls.requirePureCalleeIfPure(func, candidate, op);
                hub.model.callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
                hub.calls.markStructRvoMethodCall(op, candidate, eligibleForRvoHere);
                return candidate.returnType;
            }
            }
        }
        // A specific error when the name exists only as an instance method.
        if (nameExistsAsInstance) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + methodName + "' is an instance method on '" + concreteName + "', not a static "
                            + "method -- call it on an instance instead");
        }
        throw new CompilerException("type", op.file, op.line,
                "'" + concreteName + "' has no static method '" + methodName
                        + "' matching argument types (" + describeTypes(argTypes) + ")"
                        + hub.declarations.unavailableNote(concreteName + "." + methodName));
        }


    /** Builds a raw `receiver.field` node for checkLockMatchStatement; marked compiler-synthesized so checkDot's swap-field restriction exempts it. */
    public Token buildFieldDot(Token receiver, String fieldName, Token at) {
        Token dot = new Token(TokenType.OPERATOR, ".", at.line, at.file);
        dot.left = receiver;
        dot.right = new Token(TokenType.VARREF, fieldName, at.line, at.file);
        dot.chk.isCompilerSynthesizedSwapAccess = true;
        return dot;
    }

    /** Builds an ordinary "EnumName.Variant" '.' node -- raw, unresolved, recognized by checkDot's own enum-variant branch exactly as if it had been written by hand. */
    public Token buildEnumVariantRef(String enumName, String variantName, Token at) {
        Token dot = new Token(TokenType.OPERATOR, ".", at.line, at.file);
        dot.left = new Token(TokenType.VARREF, enumName, at.line, at.file);
        dot.right = new Token(TokenType.VARREF, variantName, at.line, at.file);
        return dot;
    }

    /**
     * `match @lock EXPR{ OPEN:{..} CLOSED:{..} }`: validates real Token trees (swap, ==, release assign) through the ordinary machinery, then rewrites
     * `stmt` in place into a `loop` (spin on ATOMIC_SWAP). See docs/TypeChecker.md, "match @lock lowering".
     */
    public int stepLockMatch(CheckFrame f) {
        Token stmt = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        LockState st;
        if (f.phase == 0) {
            hub.impls.requireNotPure(func, stmt, "'match @lock'", "acquiring the lock writes the mutex and blocks (a retry loop on shared state)");
            st = new LockState();
            f.data = st;
            st.receiver = stmt.condition();
            f.phase = 1;
            return f.call(st.receiver);
        }
        st = (LockState) f.data;
        Token receiver = st.receiver;
        if (f.phase == 1) {
            TypeInfo receiverType = f.got;
            String matchedSlotKey = hub.expressions.slotKeyOf(receiver);
            if (matchedSlotKey != null) {
                hub.model.fnLockMatched.add(matchedSlotKey);
            }
            st.structInfo = hub.model.structs.get(receiverType.baseType);
            if (st.structInfo == null || st.structInfo.swapFieldName == null) {
                throw new CompilerException("type", stmt.file, stmt.line,
                        "'match @lock' requires a struct value with a 'swap'-tagged atomic mutex-state field, "
                                + "found '" + receiverType.canonical() + "'");
            }
            st.swapFieldName = st.structInfo.swapFieldName;
            TypeInfo swapFieldType = st.structInfo.members.get(st.swapFieldName);
            st.stateEnumName = swapFieldType.baseType;

            for (Token caseTok : stmt.childs) {
                List<Token> labels = caseTok.sub;
                if (labels.size() != 1) {
                    throw new CompilerException("type", caseTok.file, caseTok.line,
                            "'match @lock' case labels can't be '|'-chained -- 'OPEN' and 'CLOSED' each need "
                                    + "their own, separate case");
                }
                String label = labels.get(0).text;
                if (label.equals("OPEN")) {
                    if (st.openCase != null) {
                        throw new CompilerException("type", caseTok.file, caseTok.line,
                                "'OPEN' is already covered by an earlier case");
                    }
                    st.openCase = caseTok;
                } else if (label.equals("CLOSED")) {
                    if (st.closedCase != null) {
                        throw new CompilerException("type", caseTok.file, caseTok.line,
                                "'CLOSED' is already covered by an earlier case");
                    }
                    st.closedCase = caseTok;
                } else {
                    throw new CompilerException("type", caseTok.file, caseTok.line,
                            "'match @lock' case labels must be exactly 'OPEN' and 'CLOSED', found '" + label + "'");
                }
            }
            if (st.openCase == null || st.closedCase == null) {
                throw new CompilerException("type", stmt.file, stmt.line,
                        "'match @lock' must be exhaustive -- both 'OPEN' and 'CLOSED' cases are required");
            }

            // Acquire attempt: `recv.field swap Enum.CLOSED`.
            st.swapOp = new Token(TokenType.OPERATOR, "swap", stmt.line, stmt.file);
            st.swapOp.left = buildFieldDot(receiver, st.swapFieldName, stmt);
            st.swapOp.right = buildEnumVariantRef(st.stateEnumName, "CLOSED", stmt);
            f.phase = 2;
            return f.call(st.swapOp);
        }
        if (f.phase == 2) {
            // Was it OPEN: `== Enum.OPEN` on the swap result.
            st.condOp = new Token(TokenType.OPERATOR, "==", stmt.line, stmt.file);
            st.condOp.left = st.swapOp;
            st.condOp.right = buildEnumVariantRef(st.stateEnumName, "OPEN", stmt);
            f.phase = 3;
            return f.call(st.condOp);
        }
        if (f.phase == 3) {
            // Release: `recv.field = Enum.OPEN`, checked once and replayed at every OPEN-case exit. Not wrapped in `mut`: an enum-variant reference is always imut and checkAssign has the matching exception.
            Token releaseValue = buildEnumVariantRef(st.stateEnumName, "OPEN", stmt);
            st.releaseAssign = new Token(TokenType.OPERATOR, "=", stmt.line, stmt.file);
            st.releaseAssign.left = buildFieldDot(receiver, st.swapFieldName, stmt);
            st.releaseAssign.right = releaseValue;
            f.phase = 4;
            return f.call(st.releaseAssign);
        }
        if (f.phase == 4) {
            // The OPEN body owes `releaseAssign` on every exit (Scope.activeLockReleases). Both case bodies sit under one extra loop-boundary Scope so a user `break` targets this statement's own spin loop.
            st.loopBodyScope = new Scope(scope, true);

            st.preState = scope.movedSlots;
            st.openBranchMoved = new HashSet<>(st.preState);
            // Inside the OPEN case this thread holds the lock, so the struct's
            // own "@lock(match self.<swap field> : OPEN)" proof is satisfied
            // for this receiver -- the same proof an ordinary
            // "match recv.<field>{ OPEN:{...} }" plants (hasEnumFieldProof).
            List<Token.MatchPattern> openProofPatterns = new ArrayList<>(st.loopBodyScope.activeMatchPatterns);
            String openReceiverSlot = hub.expressions.slotKeyOf(receiver);
            if (openReceiverSlot != null) {
                openProofPatterns.add(new Token.MatchPattern(openReceiverSlot, "enum_field_variants", st.swapFieldName,
                        new ArrayList<>(List.of("OPEN"))));
            }
            Scope openProofScope = new Scope(st.loopBodyScope, new HashSet<>(st.preState), openProofPatterns, false);
            st.openBodyScope = new Scope(openProofScope, st.openBranchMoved, st.releaseAssign);
            st.savedLockClosedScope = hub.model.lockClosedLoopScope;
            hub.model.lockClosedLoopScope = null; // 'continue' (retry) is meaningless while holding the lock
            f.phase = 5;
            return f.call(hub.statements.linesInScopeFrame(st.openCase.childs, st.openBodyScope, func, true));
        }
        if (f.phase == 5) {
            hub.model.lockClosedLoopScope = st.savedLockClosedScope;
            st.openCase.chk.destructOnExit = hub.statements.collectNaturalEndDestruct(st.openCase.childs, st.openBodyScope, st.openBodyScope);

            // `CLOSED:default` / `CLOSED:default(args)`: detected on the tree-built CLOSED body and substituted before use; an ordinary body is untouched.
            CheckFrame sub = new CheckFrame(CheckFrame.SUBST_POLICY, st.closedCase, scope, func);
            sub.sc1 = st.loopBodyScope;
            sub.data = st.structInfo;
            f.phase = 6;
            return f.call(sub);
        }
        if (f.phase == 6) {
            DefaultPolicySubstitution substitution = (DefaultPolicySubstitution) f.gotFrame.out;
            st.substitution = substitution;
            Scope closedParentScope = st.loopBodyScope;
            if (substitution != null) {
                closedParentScope = substitution.preLoopScope;
                stmt.chk.preLoopInit = substitution.preLoopInit;
                stmt.chk.lockSpinBound = substitution.spinBound;
            }
            st.closedBranchMoved = new HashSet<>(st.preState);
            st.closedBodyScope = new Scope(closedParentScope, st.closedBranchMoved, scope.activeMatchPatterns, false);
            hub.model.lockClosedLoopScope = st.loopBodyScope;
            st.retriesBefore = hub.model.lockRetryContinuesSeen;
            f.phase = 7;
            return f.call(hub.statements.linesInScopeFrame(st.closedCase.childs, st.closedBodyScope, func, true));
        }
        hub.model.lockClosedLoopScope = st.savedLockClosedScope;
        Token closedCase = st.closedCase;
        Token openCase = st.openCase;
        DefaultPolicySubstitution substitution = st.substitution;
        if (substitution == null && hub.model.lockRetryContinuesSeen == st.retriesBefore) {
            stmt.chk.lockSpinBound = 1;         // a try-lock: no CLOSED path retries, so the loop body runs once
        }
        if (substitution == null && hub.model.lockRetryContinuesSeen > st.retriesBefore && !isGhostTableFunction(func) && !hub.model.synthesizingGlueBody) {
            throw new CompilerException("type", closedCase.file, closedCase.line,
                    "a 'match @lock' that retries must say how many times: a bare 'CLOSED:{ ... continue }' would spin without a limit, which safe code does not allow -- "
                            + "write 'CLOSED:default(n)' (retry up to n times, then give up with a 'break'), or give the type an 'impl default match @lock' policy, or give up at once "
                            + "with 'break'/'return'/'throw'");
        }
        if (!hub.declarations.closedCaseTerminates(closedCase.childs)) {
            throw new CompilerException("type", closedCase.file, closedCase.line,
                    "the 'CLOSED' case of 'match @lock' must end, on every path, in 'continue' (retry the "
                            + "acquire), 'break' (give up without the lock), 'return' or 'throw' -- it "
                            + "cannot fall off its end");
        }
        closedCase.chk.destructOnExit = hub.statements.collectNaturalEndDestruct(closedCase.childs, st.closedBodyScope, st.closedBodyScope);

        st.preState.clear();
        st.preState.addAll(st.openBranchMoved);
        st.preState.addAll(st.closedBranchMoved);

        // Synthesized "got it, stop spinning" exit after the OPEN body (dead code if the body already ends in return/break); its unlockOnExit includes `releaseAssign`.
        Token breakTok = new Token(TokenType.KEYWORD, "break", stmt.line, stmt.file);
        // Only the owns locals declared INSIDE the OPEN body die at this exit (stop at the spin loop's own boundary scope, like a
        // user-written `break` does). Walking on to `scope` also destroyed the enclosing function's owns locals (e.g. the matched
        // handle itself) before the lock release below wrote through them.
        breakTok.chk.destructOnExit = hub.statements.collectOwnsToDestruct(st.openBodyScope, st.loopBodyScope, null);
        breakTok.chk.unlockOnExit = hub.statements.collectLockReleasesToBoundary(st.openBodyScope, scope);
        List<Token> openBranchChilds = new ArrayList<>(openCase.childs);
        openBranchChilds.add(hub.statements.wrapAsCheckedLine(breakTok));

        Token ifTok = new Token(TokenType.KEYWORD, "if", stmt.line, stmt.file);
        ifTok.sub = new ArrayList<>();
        ifTok.sub.add(st.condOp);
        ifTok.childs = openBranchChilds;
        ifTok.chk.destructOnExit = openCase.chk.destructOnExit;

        Token elseTok = new Token(TokenType.KEYWORD, "else", stmt.line, stmt.file);
        elseTok.sub = new ArrayList<>();
        elseTok.childs = closedCase.childs;
        elseTok.chk.destructOnExit = closedCase.chk.destructOnExit;
        ifTok.next = elseTok;

        // Mutate `stmt` in place into a real `loop`; BytecodeEmitter's ordinary loop/if/break/=/swap emission does the rest.
        stmt.text = "loop";
        stmt.chk.isLockSpinLoop = true;
        stmt.isCaseMatch = false;
        stmt.sub = new ArrayList<>();
        stmt.childs = new ArrayList<>();
        stmt.childs.add(hub.statements.wrapAsCheckedLine(ifTok));
        stmt.chk.destructOnExit = new ArrayList<>();
        return f.done(null);
    }




    /** Returns the lone expression of a `CLOSED:default` / `CLOSED:default(args)` body (bare VARREF `default` or a CALL on it), or null for an ordinary body. */
    public Token detectDefaultPolicyRef(Token closedCase) {
        if (closedCase.childs.size() != 1) {
            return null;
        }
        Token lineTok = closedCase.childs.get(0);
        if (lineTok.type != TokenType.LINE || lineTok.childs.size() != 1) {
            return null;
        }
        Token expr = lineTok.childs.get(0);
        if (expr.type == TokenType.VARREF && expr.text.equals("default")) {
            return expr;
        }
        if (expr.type == TokenType.OPERATOR && expr.text.equals("CALL") && expr.left != null
                && expr.left.type == TokenType.VARREF && expr.left.text.equals("default")) {
            return expr;
        }
        return null;
    }

    /**
     * Replaces a `CLOSED:default[(args)]` body with a real one built from the struct's registered `impl default match @lock` policy (or the built-in
     * attempt-limit form when none is registered); returns the pre-loop-init pieces, or null if the body is ordinary.
     * The new body is left unchecked: the caller's checkLinesInScope checks it. See docs/TypeChecker.md, "CLOSED:default substitution".
     */
    public int stepSubstPolicy(CheckFrame f) {
        Token closedCase = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        SubstState st;
        if (f.phase == 0) {
            return substHead(f, new SubstState());
        }
        st = (SubstState) f.data;
        StructInfo structInfo = st.structInfo;
        Token defaultRef = st.defaultRef;
        if (f.phase == 1) {
            TypeInfo limitType = f.got;
            if (!hub.typesCompatible(new TypeInfo(null, "imut", "u64"), limitType)) {
                throw new CompilerException("type", st.builtinArgs.get(0).file, st.builtinArgs.get(0).line,
                        "the attempt limit of 'CLOSED:default(...)' must be a u64, got '" + limitType.canonical() + "'");
            }
            st.preLoopScope = new Scope(f.sc1);
            st.preLoopInit = new ArrayList<>();
            f.phase = 2;
            return f.call(hub.statements.letAssignFrame("$lm_tries",
                    wrapWithMutability(new Token(TokenType.INTEGER, "0", defaultRef.line, defaultRef.file), "mut"), st.preLoopScope, func));
        }
        if (f.phase == 2) {
            st.preLoopInit.add((Token) f.gotFrame.out);
            f.phase = 3;
            return f.call(hub.statements.letAssignFrame("$lm_limit", wrapWithMutability(st.builtinArgs.get(0), "imut"), st.preLoopScope, func));
        }
        if (f.phase == 3) {
            st.preLoopInit.add((Token) f.gotFrame.out);
            List<Token> builtinBody = new ArrayList<>();
            builtinBody.add(hub.statements.wrapAsCheckedLine(buildTriesIncrement(defaultRef)));
            builtinBody.add(hub.statements.wrapAsCheckedLine(buildGiveUpCheck(defaultRef, new Token(TokenType.VARREF, "$lm_limit", defaultRef.line, defaultRef.file))));
            builtinBody.add(hub.statements.wrapAsCheckedLine(new Token(TokenType.KEYWORD, "continue", defaultRef.line, defaultRef.file)));
            closedCase.childs = builtinBody;
            f.out = new DefaultPolicySubstitution(st.preLoopInit, st.preLoopScope).withBound(TypeCheckerModel.literalLimit(st.builtinArgs.get(0)));
            return f.done(null);
        }
        DefaultLockMatchPolicy policy = structInfo.defaultLockMatchPolicy;
        if (f.phase == 4) {
            st.preLoopInit.add((Token) f.gotFrame.out);
            f.i = 1;
            return substArgLoop(f, st);
        }
        if (f.phase == 6) {
            NameType param = st.policyParams.get(f.i);
            Token argExpr = st.callArgNodes.get(f.i - 1);
            TypeInfo argType = f.got;
            if (!hub.typesCompatible(param.type, argType)) {
                throw new CompilerException("type", argExpr.file, argExpr.line,
                        "'CLOSED:default(...)' argument " + f.i + " for '" + policy.structName + "' must be '"
                                + param.type.canonical() + "', got '" + argType.canonical() + "'");
            }
            // Bound once before the loop under its declared name; the cloned policy body refers to it by that name.
            Token wrappedArg = wrapWithMutability(argExpr, param.type.mutability);
            f.phase = 7;
            return f.call(hub.statements.letAssignFrame(param.name, wrappedArg, st.preLoopScope, func));
        }
        // phase 7: a policy argument is bound
        st.preLoopInit.add((Token) f.gotFrame.out);
        f.i++;
        return substArgLoop(f, st);
    }

    /** First step of SUBST_POLICY: detects the form and starts the first nested check. f.sc1 = the spin loop's body scope, f.data = the struct on entry. */
    private int substHead(CheckFrame f, SubstState st) {
        Token closedCase = f.node;
        StructInfo structInfo = (StructInfo) f.data;
        st.structInfo = structInfo;
        f.data = st;
        Token defaultRef = detectDefaultPolicyRef(closedCase);
        if (defaultRef == null) {
            f.out = null;
            return f.done(null);
        }
        st.defaultRef = defaultRef;
        DefaultLockMatchPolicy policy = structInfo.defaultLockMatchPolicy;
        if (policy == null) {
            // No policy registered: the built-in one. `CLOSED:default(n)` retries up to n times (attempt numbers 1..n), then gives up with a `break`.
            if (defaultRef.type != TokenType.OPERATOR || !defaultRef.text.equals("CALL")) {
                throw new CompilerException("type", defaultRef.file, defaultRef.line,
                        "'" + structInfo.name + "' has no registered 'impl default match @lock' policy, so 'CLOSED:default' needs its attempt limit: "
                                + "write 'CLOSED:default(n)' (retry up to n times, then give up)");
            }
            st.builtinArgs = new ArrayList<>();
            Token builtinArgsDelineator = defaultRef.right;
            if (!builtinArgsDelineator.childs.isEmpty()) {
                collectCommaArgs(builtinArgsDelineator.childs.get(0), st.builtinArgs);
            }
            if (st.builtinArgs.size() != 1) {
                throw new CompilerException("type", defaultRef.file, defaultRef.line,
                        "'CLOSED:default(...)' with no registered 'impl default match @lock' policy takes exactly one argument, the attempt limit -- got " + st.builtinArgs.size());
            }
            f.phase = 1;
            return f.call(st.builtinArgs.get(0));
        }
        st.callArgNodes = new ArrayList<>();
        if (defaultRef.type == TokenType.OPERATOR && defaultRef.text.equals("CALL")) {
            Token argsDelineator = defaultRef.right;
            if (!argsDelineator.childs.isEmpty()) {
                collectCommaArgs(argsDelineator.childs.get(0), st.callArgNodes);
            }
        }
        st.policyParams = hub.signatures.parseParamList(policy.rawParamsTok.childs);
        int requiredArgCount = st.policyParams.size() - 1;
        if (st.callArgNodes.size() != requiredArgCount) {
            throw new CompilerException("type", defaultRef.file, defaultRef.line,
                    "'CLOSED:default(...)' for '" + policy.structName + "' requires exactly " + requiredArgCount
                            + " argument(s) -- its own first parameter ('" + st.policyParams.get(0).name
                            + "') is always the automatic, per-iteration counter, never supplied here -- got "
                            + st.callArgNodes.size());
        }
        st.preLoopScope = new Scope(f.sc1);
        st.preLoopInit = new ArrayList<>();
        f.phase = 4;
        return f.call(hub.statements.letAssignFrame("$lm_tries",
                wrapWithMutability(new Token(TokenType.INTEGER, "0", defaultRef.line, defaultRef.file), "mut"), st.preLoopScope, f.func));
    }

    /** Next policy argument (f.i) or, when all are bound, the substituted CLOSED body. */
    private int substArgLoop(CheckFrame f, SubstState st) {
        Token closedCase = f.node;
        Token defaultRef = st.defaultRef;
        DefaultLockMatchPolicy policy = st.structInfo.defaultLockMatchPolicy;
        List<NameType> policyParams = st.policyParams;
        if (f.i < policyParams.size()) {
            f.phase = 6;
            return f.call(st.callArgNodes.get(f.i - 1));
        }
        List<Token> clonedBodyLines = new ArrayList<>();
        for (Token lineTok : policy.rawBodyLines) {
            clonedBodyLines.add(lineTok.deepCloneRaw());
        }
        clonedBodyLines = new Parser().gatherStatementLines(clonedBodyLines);
        new RpnConverter().convertLines(clonedBodyLines);
        new TreeBuilder().buildLines(clonedBodyLines);

        String triesLocalName = "$lm_tries";
        List<Token> substitutedChilds = new ArrayList<>();
        Token triesIncrement = new Token(TokenType.OPERATOR, "++", defaultRef.line, defaultRef.file);
        triesIncrement.unary = true;
        triesIncrement.left = new Token(TokenType.VARREF, triesLocalName, defaultRef.line, defaultRef.file);
        substitutedChilds.add(hub.statements.wrapAsCheckedLine(triesIncrement));

        Token triesRebindLet = new Token(TokenType.KEYWORD, "let", defaultRef.line, defaultRef.file);
        triesRebindLet.childs = new ArrayList<>();
        triesRebindLet.childs.add(new Token(TokenType.VARREF, policyParams.get(0).name, defaultRef.line,
                defaultRef.file));
        Token triesRebindAssign = new Token(TokenType.OPERATOR, "=", defaultRef.line, defaultRef.file);
        triesRebindAssign.left = triesRebindLet;
        triesRebindAssign.right = wrapWithMutability(
                new Token(TokenType.VARREF, triesLocalName, defaultRef.line, defaultRef.file), "imut");
        substitutedChilds.add(hub.statements.wrapAsCheckedLine(triesRebindAssign));
        // The compiler, not the policy, enforces the bound: after the counter is bumped, `if $lm_tries > <limit>{ break }` gives up. The limit is the
        // policy's second parameter when that is a u64 ("tries, limit"), else LOCK_SPIN_LIMIT. A policy that gives up earlier (`if tries >= limit{ break }`)
        // still does; the body runs for attempts 1..limit.
        Token limitExpr = policyParams.size() >= 2 && "u64".equals(policyParams.get(1).type.baseType)
                ? new Token(TokenType.VARREF, policyParams.get(1).name, defaultRef.line, defaultRef.file)
                : new Token(TokenType.INTEGER, String.valueOf(TypeCheckerModel.LOCK_SPIN_LIMIT), defaultRef.line, defaultRef.file);
        substitutedChilds.add(hub.statements.wrapAsCheckedLine(buildGiveUpCheck(defaultRef, limitExpr)));

        substitutedChilds.addAll(clonedBodyLines);
        // The policy body keeps its old meaning "falling off the end
        // retries": add the explicit 'continue' the CLOSED case now needs
        // unless the policy already ends every path itself.
        if (!hub.declarations.closedCaseTerminates(substitutedChilds)) {
            Token retry = new Token(TokenType.KEYWORD, "continue", defaultRef.line, defaultRef.file);
            substitutedChilds.add(hub.statements.wrapAsCheckedLine(retry));
        }
        closedCase.childs = substitutedChilds;

        long bound = TypeCheckerModel.LOCK_SPIN_LIMIT;       // a policy without a u64 `limit` parameter is stopped at LOCK_SPIN_LIMIT
        if (policyParams.size() >= 2 && "u64".equals(policyParams.get(1).type.baseType)) {
            bound = TypeCheckerModel.literalLimit(st.callArgNodes.get(0));
        }
        f.out = new DefaultPolicySubstitution(st.preLoopInit, st.preLoopScope).withBound(bound);
        return f.done(null);
    }




    /** True for the `@gt_*` functions (the ghost table's own lock code), which keep an unbounded spin. */
    public boolean isGhostTableFunction(FuncInfo func) {
        if (func == null || func.funcToken == null || func.funcToken.decorators == null) {
            return false;
        }
        for (String d : new String[] { "gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve" }) {
            if (hub.impls.getDecorator(func.funcToken.decorators, d) != null) {
                return true;
            }
        }
        return false;
    }

    /** `$lm_tries++` */
    public Token buildTriesIncrement(Token at) {
        Token inc = new Token(TokenType.OPERATOR, "++", at.line, at.file);
        inc.unary = true;
        inc.left = new Token(TokenType.VARREF, "$lm_tries", at.line, at.file);
        return inc;
    }

    /** `if $lm_tries > limit{ break }` -- raw, checked later with the rest of the CLOSED body. */
    public Token buildGiveUpCheck(Token at, Token limit) {
        Token cmp = new Token(TokenType.OPERATOR, ">", at.line, at.file);
        cmp.left = new Token(TokenType.VARREF, "$lm_tries", at.line, at.file);
        cmp.right = limit;
        Token ifTok = new Token(TokenType.KEYWORD, "if", at.line, at.file);
        ifTok.sub = new ArrayList<>();
        ifTok.sub.add(cmp);
        ifTok.childs = new ArrayList<>();
        ifTok.childs.add(hub.statements.wrapAsCheckedLine(new Token(TokenType.KEYWORD, "break", at.line, at.file)));
        return ifTok;
    }

    /** Wraps `expr` in a raw `mut`/`imut` prefix operator to make a synthesized value's mutability concrete before a `let`. */
    public Token wrapWithMutability(Token expr, String mutability) {
        Token wrapper = new Token(TokenType.OPERATOR, mutability, expr.line, expr.file);
        wrapper.left = expr;
        return wrapper;
    }

    /**
     * The proofs a `@lock(..)` method may assume inside its own body (so it needs no `match` of its own). Only for non-static impl methods: the first
     * parameter is the receiver, whatever it is called. See docs/TypeChecker.md, "Method lock proofs".
     */
    public List<Token.MatchPattern> assumedLockPatterns(FuncInfo func) {
        List<Token.MatchPattern> result = new ArrayList<>();
        if (func.funcToken.decorators == null || func.enclosingImpl == null || func.funcToken.isStaticMethod
                || func.paramNames.isEmpty()) {
            return result;
        }
        String selfParamName = func.paramNames.get(0);
        for (Token.Decorator d : func.funcToken.decorators) {
            if (!d.name.equals("lock")) {
                continue;
            }
            if (d.lockFieldName != null) {
                result.add(new Token.MatchPattern(selfParamName, "enum_field_variants", d.lockFieldName,
                        d.lockVariants));
            } else if (d.lockInParams != null) {
                for (int j = 0; j < d.lockInParams.size(); j++) {
                    // `in`/`within` grant the read-only "in" kind, `into` the write-capable "into" kind (see checkMatchCondition).
                    String kind = d.lockInOperators.get(j).equals("into") ? "into" : "in";
                    String paramName = d.lockInParams.get(j);
                    String fieldName = d.lockInFields.get(j);
                    String targetSlot = selfParamName + "." + fieldName;
                    result.add(new Token.MatchPattern(paramName, kind, null, targetSlot));
                    // `Some(..)`-wrapped: also grant the element-alive proof for `self.field[param]` (as checkSomeIndexBoundsCondition does).
                    if (d.lockInSomeWrapped != null && Boolean.TRUE.equals(d.lockInSomeWrapped.get(j))) {
                        result.add(new Token.MatchPattern(targetSlot + "[" + paramName + "]", "alive", null));
                    }
                }
            }
        }
        return result;
    }

    /** Runs a pure proof check; in unsafe context a failure is not an error but records that the enclosing block needed `tag`. */
    public void checkOrBypass(Scope scope, String tag, Runnable check) {
        try {
            check.run();
        } catch (CompilerException e) {
            if (hub.declarations.unsafeBypass(scope, tag)) {
                return;
            }
            throw e;
        }
    }

    /** At a call to a `@lock(..)` method: requires the matching proofs at the call site (skipped in unsafe). `argNodes` includes the receiver as its first element, like candidate.paramTypes. See docs/TypeChecker.md, "Method lock proofs". */
    public void requireMethodLockProofs(FuncInfo candidate, Token receiverNode, List<Token> argNodes, Scope scope,
            Token at) {
        if (candidate.funcToken.decorators == null) {
            return;
        }
        String receiverSlot = hub.expressions.slotKeyOf(receiverNode);
        for (Token.Decorator d : candidate.funcToken.decorators) {
            if (!d.name.equals("lock")) {
                continue;
            }
            if (d.lockFieldName != null) {
                if (!hasEnumFieldProof(scope, receiverSlot, d.lockFieldName, d.lockVariants)) {
                    throw new CompilerException("type", at.file, at.line,
                            "calling '" + candidate.name + "' requires a 'match "
                                    + (receiverSlot != null ? receiverSlot : "...") + "." + d.lockFieldName
                                    + "{...}' proof first (it's decorated '@lock(match self."
                                    + d.lockFieldName + " : ...)')");
                }
            } else if (d.lockInParams != null) {
                for (int j = 0; j < d.lockInParams.size(); j++) {
                    String paramName = d.lockInParams.get(j);
                    String fieldName = d.lockInFields.get(j);
                    // The written operator only changes the message. An `in`/`within` clause accepts an "in" or "into" proof; an `into` clause needs an "into" proof (exact match).
                    String operatorText = d.lockInOperators.get(j);
                    String requiredKind = operatorText.equals("into") ? "into" : "in";
                    int paramIdx = candidate.paramNames.indexOf(paramName);
                    Token argExpr = paramIdx >= 0 && paramIdx < argNodes.size() ? argNodes.get(paramIdx) : null;
                    // boundsSlotKeyOf, not slotKeyOf: a literal index ("match 0 into thr.backing{ thr.set(thr, 0, v) }") is provable too
                    String argSlot = argExpr != null ? hub.accessAndAsync.boundsSlotKeyOf(argExpr) : null;
                    String targetSlot = receiverSlot != null ? receiverSlot + "." + fieldName : null;
                    boolean proven = argSlot != null && targetSlot != null && scope.activeMatchPatterns.stream()
                            .anyMatch(p -> !p.invalidated && (p.kind.equals(requiredKind)
                                    || (requiredKind.equals("in") && p.kind.equals("into")))
                                    && argSlot.equals(p.slotKey) && targetSlot.equals(p.rightSlotKey));
                    if (!proven) {
                        throw new CompilerException("type", at.file, at.line,
                                "calling '" + candidate.name + "' requires a 'match "
                                        + (argSlot != null ? argSlot : "...") + " " + operatorText + " "
                                        + (targetSlot != null ? targetSlot : "...") + "{...}' proof first "
                                        + "(it's decorated '@lock(match " + paramName + " " + operatorText
                                        + " self." + fieldName + ")')");
                    }
                    // A `Some(..)`-wrapped clause also needs the element-alive proof for `self.field[param]`.
                    if (d.lockInSomeWrapped != null && Boolean.TRUE.equals(d.lockInSomeWrapped.get(j))) {
                        String elemSlot = targetSlot != null && argSlot != null
                                ? targetSlot + "[" + argSlot + "]" : null;
                        boolean elemProven = elemSlot != null && scope.activeMatchPatterns.stream()
                                .anyMatch(p -> !p.invalidated && p.kind.equals("alive") && elemSlot.equals(p.slotKey));
                        if (!elemProven) {
                            throw new CompilerException("type", at.file, at.line,
                                    "calling '" + candidate.name + "' requires a 'match Some("
                                            + (argSlot != null ? argSlot : "...") + ") " + operatorText + " "
                                            + (targetSlot != null ? targetSlot : "...") + "{...}' proof first "
                                            + "(it's decorated '@lock(match Some(" + paramName + ") "
                                            + operatorText + " self." + fieldName + ")')");
                        }
                    }
                }
            }
        }
    }

    /** True if `scope` has an active enum_field_variants proof for `slotKey.fieldName` whose proven variants are all within `requiredVariants`. */
    public boolean hasEnumFieldProof(Scope scope, String slotKey, String fieldName, List<String> requiredVariants) {
        if (slotKey == null) {
            return false;
        }
        return scope.activeMatchPatterns.stream()
                .anyMatch(p -> !p.invalidated && p.kind.equals("enum_field_variants") && p.slotKey.equals(slotKey)
                        && p.targetName.equals(fieldName) && requiredVariants.containsAll(p.provenVariants));
    }

    /** A member other than the lock discriminant field needs an active enum-field proof for this receiver (planted by checkEnumMatch); the discriminant is freely readable; unsafe bypasses. */
    public void requireStructLockProof(StructInfo structInfo, String memberName, Token receiverNode, Scope scope,
            Token at) {
        if (structInfo.lockFieldName == null || memberName.equals(structInfo.lockFieldName) || hub.model.synthesizingGlueBody) {
            return;
        }
        String slotKey = hub.expressions.slotKeyOf(receiverNode);
        boolean proven = hasEnumFieldProof(scope, slotKey, structInfo.lockFieldName, structInfo.lockVariants);
        if (!proven) {
            String slotDisplay = slotKey != null ? slotKey : "...";
            throw new CompilerException("type", at.file, at.line,
                    "'" + memberName + "' requires a 'match " + slotDisplay + "." + structInfo.lockFieldName
                            + "{...}' proof first (this struct is decorated '@lock(match self."
                            + structInfo.lockFieldName + " : ...)')"
                            + (structInfo.swapFieldName != null && structInfo.swapFieldName.equals(structInfo.lockFieldName)
                                    ? " -- or take the lock: 'match @lock " + slotDisplay + "{ OPEN:{...} CLOSED:{...} }'" : ""));
        }
    }

    public void collectCommaArgs(Token root, List<Token> out) {
        Deque<Token> work = new ArrayDeque<>();
        work.push(root);
        while (!work.isEmpty()) {
            Token node = work.pop();
            if (node.type == TokenType.OPERATOR && node.text.equals(",")) {
                work.push(node.right);
                work.push(node.left);
            } else {
                out.add(node);
            }
        }
    }

    /** `[a, b, ..]`: flattens the comma chain and requires one element base type; the type string embeds the length (`T[n]`). `dup` is already expanded by DupExpander. */
    public TypeInfo checkArrayLiteral(Token node, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.ARRAY_LITERAL, node, scope, func));
    }

    public int stepArrayLiteral(CheckFrame f) {
        Token node = f.node;
        if (f.phase == 0) {

                if (node.childs.isEmpty()) {
                throw new CompilerException("type", node.file, node.line,
                        "array literal must have at least one element");
            }
            List<Token> elements = new ArrayList<>();
            collectCommaArgs(node.childs.get(0), elements);
            if (elements.isEmpty()) {
                throw new CompilerException("type", node.file, node.line,
                        "array literal must have at least one element");
            }

            f.toks = elements;
            f.phase = 1;
            return f.callAll(elements);
        }
        return f.done(arrayLiteralType(node, f.scope, f.func, f.toks, f.gotFrame.list));
    }

    private TypeInfo arrayLiteralType(Token node, Scope scope, FuncInfo func,
            List<Token> elements, List<TypeInfo> elementTypes) {
        // null alone gives `null[n]`; next to a non-null element the type comes from that element and null is accepted wherever a pointer is.
        TypeInfo establishedType = null;
        for (TypeInfo t : elementTypes) {
            if (establishedType == null && !t.baseType.equals("null")) {
                establishedType = t;
            }
        }
        // A literal alongside a typed (non-literal) integer element takes that element's type when its value fits: `[c, c + 1, 7]`
        // with `c: u16` is a `u16[3]`, exactly as `c + 7` would be. (Two typed elements of different types still mismatch below.)
        TypeInfo typedInt = null;
        for (TypeInfo t : elementTypes) {
            if (t.literalValue == null && t.storage == null && TypeCheckerModel.INTEGER_WIDTH.containsKey(t.baseType)) {
                typedInt = t;
                break;
            }
        }
        if (typedInt != null) {
            boolean adaptable = true;
            for (TypeInfo t : elementTypes) {
                if (t.literalValue != null && (t.storage != null || !TypeCheckerModel.literalFits(t.literalValue, typedInt.baseType))) {
                    adaptable = false;
                }
            }
            if (adaptable) {
                TypeInfo slot = new TypeInfo(null, "indeterminate", typedInt.baseType);
                for (int i = 0; i < elements.size(); i++) {
                    if (elementTypes.get(i).literalValue != null) {
                        hub.adaptLiteral(elements.get(i), slot);
                        elementTypes.set(i, new TypeInfo(null, elementTypes.get(i).mutability, typedInt.baseType));
                    }
                }
                establishedType = typedInt;
            }
        }
        // All-integer-literal arrays may mix `u64` and negated (`s64`) literals ([-5, 300]): they settle on
        // s64 when any element is negative, and each element node is retagged to match.
        boolean allIntLiterals = !elementTypes.isEmpty();
        boolean anyNegative = false;
        for (TypeInfo t : elementTypes) {
            if (t.literalValue == null || t.storage != null) {
                allIntLiterals = false;
                break;
            }
            anyNegative |= t.literalValue.signum() < 0;
        }
        if (allIntLiterals && anyNegative) {
            TypeInfo unified = new TypeInfo(null, "indeterminate", "s64");
            for (int i = 0; i < elements.size(); i++) {
                if (!elementTypes.get(i).baseType.equals("s64") && TypeCheckerModel.literalFits(elementTypes.get(i).literalValue, "s64")) {
                    hub.adaptLiteral(elements.get(i), unified);
                    TypeInfo retyped = new TypeInfo(null, elementTypes.get(i).mutability, "s64");
                    retyped.literalValue = elementTypes.get(i).literalValue;
                    elementTypes.set(i, retyped);
                }
            }
            establishedType = elementTypes.get(0);
        }
        if (establishedType == null) {
            // every element is null: the poisoned case, valid.
            establishedType = elementTypes.get(0);
        } else if (!hub.isPointerType(establishedType)) {
            // A non-pointer element type needs every element to match; null cannot stand in.
            for (int i = 0; i < elementTypes.size(); i++) {
                TypeInfo t = elementTypes.get(i);
                if (t.baseType.equals("null")) {
                    throw new CompilerException("type", elements.get(i).file, elements.get(i).line,
                            "'null' cannot be used as an array element alongside a non-pointer element "
                                    + "of type '" + establishedType.canonical() + "'");
                }
                if (!t.baseType.equals(establishedType.baseType)) {
                    throw new CompilerException("type", elements.get(i).file, elements.get(i).line,
                            "array elements must all be the same type, got '" + establishedType.baseType
                                    + "' and '" + t.baseType + "'");
                }
            }
        } else {
            for (int i = 0; i < elementTypes.size(); i++) {
                TypeInfo t = elementTypes.get(i);
                if (t.baseType.equals("null")) {
                    if (establishedType.isSome) {
                        // A null literal cannot satisfy a `some` element type (third site of this rule, besides typesCompatible and the bound-type `let` path).
                        throw new CompilerException("type", elements.get(i).file, elements.get(i).line,
                                "'null' cannot satisfy a 'some'-tagged array element type ('"
                                        + establishedType.canonical() + "')");
                    }
                    continue; // null satisfies any (non-'some') pointer-typed slot
                }
                if (!t.baseType.equals(establishedType.baseType)) {
                    throw new CompilerException("type", elements.get(i).file, elements.get(i).line,
                            "array elements must all be the same type, got '" + establishedType.baseType
                                    + "' and '" + t.baseType + "'");
                }
            }
        }

        if ("owns".equals(establishedType.storage) || hub.declarations.isInlineOwningStruct(establishedType)) {
            for (int i = 0; i < elements.size(); i++) {
                hub.expressions.markMovedIfOwned(establishedType, elements.get(i), scope);
            }
        }

        // TypeInfo.array keeps the real element type (storage included) in arrayElementType so a later `[]` can hand back a pointer element.
        TypeInfo arrayType = TypeInfo.array(establishedType, elements.size());
        boolean allLiteral = establishedType.storage == null && TypeCheckerModel.INTEGER_WIDTH.containsKey(establishedType.baseType);
        List<java.math.BigInteger> values = new ArrayList<>();
        for (TypeInfo t : elementTypes) {
            if (t.literalValue == null) {
                allLiteral = false;
                break;
            }
            values.add(t.literalValue);
        }
        if (allLiteral) {
            arrayType.literalElements = values;
        }
        // A nested all-integer-literal array ([[1,2,3],[4,5,6]]): the rows' values, flattened, so the whole literal adapts to a `T[n][m]` slot.
        if (!allLiteral && !elementTypes.isEmpty() && establishedType.storage == null && establishedType.baseType.indexOf('[') > 0) {
            boolean nested = true;
            List<java.math.BigInteger> flat = new ArrayList<>();
            for (TypeInfo t : elementTypes) {
                if (t.literalElements == null) {
                    nested = false;
                    break;
                }
                flat.addAll(t.literalElements);
            }
            if (nested) {
                arrayType.literalElements = flat;
            }
        }
        if (!elementTypes.isEmpty() && establishedType.storage == null && establishedType.baseType.indexOf('[') > 0) {
            boolean nestedF = true;
            for (TypeInfo t : elementTypes) {
                if (!t.floatArrayLiteral) {
                    nestedF = false;
                    break;
                }
            }
            if (nestedF) {
                arrayType.floatArrayLiteral = true;
            }
        }
        boolean allFloatLits = !elementTypes.isEmpty() && establishedType.storage == null && TypeCheckerModel.isFloatType(establishedType.baseType);
        for (TypeInfo t : elementTypes) {
            if (!t.floatLiteral) {
                allFloatLits = false;
                break;
            }
        }
        if (allFloatLits) {
            arrayType.floatArrayLiteral = true;
        }
        return arrayType;
        }


    /** True if `structName` is decorated `@untyped` (no hidden class id, so no runtime type information). */
    public boolean isUntyped(String structName) {
        StructInfo si = hub.model.structs.get(structName);
        return si != null && hub.impls.getDecorator(si.declTok.decorators, "untyped") != null;
    }

    /**
     * "ifaceValue instanceof StructName": runtime class-id (`___type`) compare. Left side must be
     * interface-typed, right side a struct name. See docs/TypeChecker.md, "instanceof and implements".
     */
    public TypeInfo checkInstanceof(Token op, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.INSTANCEOF, op, scope, func));
    }

    public int stepInstanceof(CheckFrame f) {
        if (f.phase == 0) {
            f.phase = 1;
            return f.call(f.node.left);
        }
        return f.done(instanceofType(f.node, f.scope, f.func, f.got));
    }

    private TypeInfo instanceofType(Token op, Scope scope, FuncInfo func, TypeInfo leftType) {        // Interface-typed left side only: narrows to one of the interface's implementers. @untyped is
        // skipped safely (interfaces are absent from 'structs'); the alive-proof gate below treats
        // interface-typed values as pointer-like already.
        if (!hub.model.interfaces.containsKey(leftType.baseType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'instanceof' can only be used on an interface-typed value ('x instanceof SomeStruct'), got '"
                            + leftType.canonical() + "'");
        }
        // Same alive-proof gate as '.' access (checkDot); applies whether this is an expression or a
        // match condition. 'unsafe' does not bypass it (known quirk, left as is).
        if (requiresAliveProof(leftType, op.left, scope)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'instanceof' on a pointer-typed struct value is not permitted -- the type system "
                            + "can't yet confirm it isn't null ('match Some(...)' will be able to prove "
                            + "this) -- only an inline (no-storage), 'auto', or 'some'-tagged struct value "
                            + "supports 'instanceof' without one");
        }
        if (isUntyped(leftType.baseType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'" + leftType.baseType + "' is '@untyped' -- it carries no runtime type "
                            + "information, so it can never be the left side of 'instanceof'");
        }
        if (op.right.type != TokenType.VARREF) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "expected a struct name on the right of 'instanceof'");
        }
        String targetName = op.right.text;
        if (!hub.model.structs.containsKey(targetName)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'" + targetName + "' is not a known struct");
        }
        if (isUntyped(targetName)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'" + targetName + "' is '@untyped' -- it carries no runtime type information, so "
                            + "nothing can ever be proven to be an instance of it");
        }
        if (!structImplements(targetName, leftType.baseType)) {
            // Only a struct that implements the left side's interface can ever be behind that interface-typed value.
            TypeCheckerModel.warn(op.right.file, op.right.line,
                    "'instanceof " + targetName + "' can never be true -- '" + targetName + "' does not implement '"
                            + leftType.baseType + "'");
        }
        op.right.chk.resolvedType = targetName;
        return new TypeInfo(null, "imut", "bool");
    }


    /**
     * "structValue implements InterfaceName" -- the left side must be struct-typed,
     * the right side must name an interface (a statically decidable question).
     */
    public TypeInfo checkImplementsOperator(Token op, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.IMPLEMENTS, op, scope, func));
    }

    public int stepImplements(CheckFrame f) {
        if (f.phase == 0) {
            f.phase = 1;
            return f.call(f.node.left);
        }
        return f.done(implementsType(f.node, f.scope, f.func, f.got));
    }

    private TypeInfo implementsType(Token op, Scope scope, FuncInfo func, TypeInfo leftType) {        // Interface-typed left operands are legal too (mirror of checkInstanceof).
        if (!hub.model.structs.containsKey(leftType.baseType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'implements' can only be used on a struct-typed value ('x implements SomeInterface'), got '"
                            + leftType.canonical() + "'");
        }
        // Same alive-proof gate as checkInstanceof.
        if (requiresAliveProof(leftType, op.left, scope)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'implements' on a pointer-typed struct value is not permitted -- the type system "
                            + "can't yet confirm it isn't null ('match Some(...)' will be able to prove "
                            + "this) -- only an inline (no-storage), 'auto', or 'some'-tagged struct value "
                            + "supports 'implements' without one");
        }
        if (isUntyped(leftType.baseType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'" + leftType.baseType + "' is '@untyped' -- it carries no runtime type "
                            + "information, so it can never be the left side of 'implements'");
        }
        if (op.right.type != TokenType.VARREF) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "expected an interface name on the right of 'implements'");
        }
        String targetName = op.right.text;
        if (!hub.model.interfaces.containsKey(targetName)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'" + targetName + "' is not a known interface");
        }
        // Warning, not error: an interface nothing implements. `implsByConcreteType` is complete before
        // bodies are checked, so this is a whole-program fact. See docs/TypeChecker.md, "instanceof and implements".
        if (!hasAnyImplementer(targetName)) {
            TypeCheckerModel.warn(op.right.file, op.right.line,
                    "'implements " + targetName + "' can never be true -- no struct anywhere in this "
                            + "program implements '" + targetName + "'");
        } else if (!structImplements(leftType.baseType, targetName)) {
            // The left side is a struct-typed value, so its type is known: some other struct implements the interface, this one does not.
            TypeCheckerModel.warn(op.right.file, op.right.line,
                    "'implements " + targetName + "' can never be true -- '" + leftType.baseType + "' does not implement '"
                            + targetName + "'");
        }
        op.right.chk.resolvedType = targetName;
        return new TypeInfo(null, "imut", "bool");
    }


    /** Whether `structName` has an `impl interfaceName for structName` block or declares `implements interfaceName`. */
    public boolean structImplements(String structName, String interfaceName) {
        List<ImplInfo> impls = hub.model.implsByConcreteType.get(structName);
        if (impls != null) {
            for (ImplInfo impl : impls) {
                if (interfaceName.equals(impl.interfaceName)) {
                    return true;
                }
            }
        }
        StructInfo si = hub.model.structs.get(structName);
        return si != null && si.implementsNames.contains(interfaceName);
    }

    /** Whether any `impl interfaceName for X` exists in the program (same scan as `generateEnumForEnums`). */
    public boolean hasAnyImplementer(String interfaceName) {
        for (List<ImplInfo> implList : hub.model.implsByConcreteType.values()) {
            for (ImplInfo impl : implList) {
                if (interfaceName.equals(impl.interfaceName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String describeTypes(List<TypeInfo> types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            TypeInfo t = types.get(i);
            if (t.literalValue != null && t.storage == null) {
                sb.append("integer literal ").append(t.literalValue).append(" (fits only ")
                        .append(TypeCheckerModel.fittingTypes(t.literalValue)).append(")");
            } else {
                sb.append(t.baseType);
            }
        }
        return sb.toString();
    }

    /**
     * "[]" default rules: a lookup needs a compile-time-known target length and a literal in-range index,
     * or a live `match i in` proof. See docs/TypeChecker.md, "Lookup".
     */
    public TypeInfo checkLookup(Token op, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.LOOKUP, op, scope, func));
    }

    /** f.a = target type; f.p = the target is an unsafe dynarray; f.x = the index expression; f.q/f.data = provenInBounds / knownLength of the safe path. */
    public int stepLookup(CheckFrame f) {
        Token op = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        switch (f.phase) {
            case 0:
                f.phase = 1;
                return f.call(op.left);
            case 1: {
                TypeInfo targetType = f.got;
                f.a = targetType;
                if (hub.isUnsafeDynArrayType(targetType)) {
                    // Unsafe dynarray: needs 'unsafe', unchecked (no hidden length). Never sets
                    // `op.isLookupProofReliant`, so writes need no "into" proof. See docs/TypeChecker.md, "Lookup".
                    if (!hub.declarations.unsafeBypass(scope, hub.declarations.udynTag(targetType))) {
                        throw new CompilerException("type", op.file, op.line,
                                "'[]' on an 'unsafe dynarray' can only be used from within 'unsafe' code -- it "
                                        + "has no bounds-proof mechanism at all, safe or otherwise");
                    }
                    Token indexDelineatorUnsafe = op.right;
                    if (indexDelineatorUnsafe.childs.isEmpty()) {
                        throw new CompilerException("type", op.file, op.line, "'[]' is missing an index expression");
                    }
                    Token indexExprUnsafe = indexDelineatorUnsafe.childs.get(0);
                    f.x = indexExprUnsafe;
                    f.p = true;
                    f.phase = 2;
                    return f.call(indexExprUnsafe);
                }
                boolean isArray = hub.isArrayType(targetType.baseType);
                boolean isString = targetType.baseType.equals("string");
                boolean isDynArray = hub.isDynArrayType(targetType);
                if (!isString && !isArray && !isDynArray) {
                    throw new CompilerException("type", op.file, op.line,
                            "'[]' (LOOKUP) is only supported on strings, arrays, and dynarrays, got '"
                                    + targetType.canonical() + "'");
                }

                Token indexDelineator = op.right;
                if (indexDelineator.childs.isEmpty()) {
                    throw new CompilerException("type", op.file, op.line, "'[]' is missing an index expression");
                }
                Token indexExpr = indexDelineator.childs.get(0);

                // A dynarray length is never compile-time known: even a literal index needs a `match i in` proof.
                Long knownLength = null;
                if (isArray) {
                    knownLength = (long) hub.arrayLength(targetType.baseType);
                } else if (isString) {
                    if (op.left.type == TokenType.STRING) {
                        knownLength = (long) op.left.literalValue.length();
                    }
                }
                // A live `match i in range(target)` proof for this exact target lifts the known-length/literal
                // index restrictions (the match already emitted the runtime check).
                boolean provenInBounds = hub.accessAndAsync.isProvenInBounds(op.left, indexExpr, scope);
                // A literal index into a type-known length needs no proof (see Token.isLookupProofReliant).
                op.chk.isLookupProofReliant = !(indexExpr.type == TokenType.INTEGER && knownLength != null);

                // Resolve and reject a range-typed index (slicing was removed) BEFORE the knownLength bounds
                // error below, or dynarray operands would always hit that generic error first.
                f.x = indexExpr;
                f.q = provenInBounds;
                f.data = knownLength;
                f.phase = 2;
                return f.call(indexExpr);
            }
            default:
                if (f.p) {
                    return f.done(lookupUnsafeDynType(op, f.a, f.got));
                }
                return f.done(lookupType(op, scope, func, f.a, f.x, (Long) f.data, f.q, f.got));
        }
    }

    private TypeInfo lookupUnsafeDynType(Token op, TypeInfo targetType, TypeInfo indexTypeUnsafe) {
        if (!TypeCheckerModel.INTEGER_TYPES.contains(indexTypeUnsafe.baseType)) {
            throw new CompilerException("type", op.file, op.line,
                    "'unsafe dynarray' index must be an integer type, got '"
                            + indexTypeUnsafe.canonical() + "'");
        }
        // Keep element storage (owns/ref) as the safe cases below do.
        TypeInfo unsafeElem = targetType.unsafeDynArrayElementType;
        if (unsafeElem.storage != null) {
            return unsafeElem.withMutability(targetType.mutability);
        }
        return new TypeInfo(null, targetType.mutability, unsafeElem.baseType);
    }

    private TypeInfo lookupType(Token op, Scope scope, FuncInfo func,
            TypeInfo targetType, Token indexExpr, Long knownLength, boolean provenInBounds, TypeInfo indexType) {
        boolean isArray = hub.isArrayType(targetType.baseType);
        boolean isString = targetType.baseType.equals("string");
        boolean isDynArray = hub.isDynArrayType(targetType);
        if (hub.isRangeType(indexType)) {
            throw new CompilerException("type", indexExpr.file, indexExpr.line,
                    "slicing ('arr[range]') is no longer supported -- Caspien has removed the slice "
                            + "type; use a scalar index or a range/pointer pair explicitly");
        }

        if (knownLength == null && !provenInBounds) {
            throw new CompilerException("type", op.file, op.line,
                    "'[]' is not permitted here -- the compiler has no way to confirm this access is "
                            + "within bounds yet (a future 'match' statement will be able to prove "
                            + "this; for now, only a target whose length is fully known at compile "
                            + "time can be indexed at all)");
        }

        if (!TypeCheckerModel.INTEGER_TYPES.contains(indexType.baseType)) {
            throw new CompilerException("type", op.file, op.line,
                    (isArray ? "array" : isDynArray ? "dynarray" : "string")
                            + " index must be an integer type, got '" + indexType.canonical() + "'");
        }
        if (indexExpr.type != TokenType.INTEGER && !provenInBounds) {
            throw new CompilerException("type", op.file, op.line,
                    "'[]' is not permitted here -- the index must be a literal integer, provably "
                            + "within bounds (no mechanism to confirm this for a variable/computed "
                            + "index yet)");
        }
        if (indexExpr.type == TokenType.INTEGER) {
            long indexValue = Long.parseLong(indexExpr.text);
            if (knownLength != null && indexValue >= knownLength) {
                throw new CompilerException("type", indexExpr.file, indexExpr.line,
                        "index " + indexValue + " is out of bounds for a target of length " + knownLength);
            }
        }
        if (isString) {
            // Default copy behavior, per spec: LOOKUP on a string yields
            // an imut char, not a raw imut char.
            return new TypeInfo(null, "imut", "char");
        }
        if (isDynArray) {
            // Container mutability governs the result; element storage (owns/ref) is preserved.
            TypeInfo dynElem = targetType.dynArrayElementType;
            if (dynElem.storage != null) {
                return dynElem.withMutability(targetType.mutability);
            }
            return new TypeInfo(null, targetType.mutability, dynElem.baseType);
        }
        // Fixed array: storage-bearing element type is only available for array-literal types;
        // annotation-declared arrays ("Box[5]") fall through to the storage-less path.
        if (targetType.arrayElementType != null && targetType.arrayElementType.storage != null) {
            return targetType.arrayElementType.withMutability(targetType.mutability);
        }
        String elementBaseType = hub.arrayElementType(targetType.baseType);
        // Element mutability follows the array's (string case above stays imut by spec).
        if (elementBaseType.equals("string")) {
            // Annotation-declared string array elements must be static, like every other string value
            // (codegen's isPointerTypeString checks storage).
            return new TypeInfo("static", targetType.mutability, "string");
        }
        if (elementBaseType.startsWith("dynarray(") && elementBaseType.endsWith(")")) {
            // Annotation-declared array of dynarrays: rebuild a real dynarray TypeInfo via
            // TypeInfo.dynArray instead of a bare baseType string.
            String innerElementText = elementBaseType.substring(
                    "dynarray(".length(), elementBaseType.length() - 1);
            // innerElementText may carry a storage prefix (e.g. "owns_Leaf"): split it off.
            TypeInfo innerElementType = TypeInfo.parseDynArrayElementText(innerElementText);
            return TypeInfo.dynArray(targetType.mutability, innerElementType);
        }
        return new TypeInfo(null, targetType.mutability, elementBaseType);
    }


    /**
     * Reads literal bounds off a `range(a,b)` base-type string; null unless both are all digits
     * (identifiers never start with a digit). See docs/TypeChecker.md, "Lookup".
     */
    public long[] parseLiteralRangeBounds(String rangeBaseType) {
        if (!rangeBaseType.startsWith("range(") || !rangeBaseType.endsWith(")")) {
            return null;
        }
        String inner = rangeBaseType.substring("range(".length(), rangeBaseType.length() - 1);
        int comma = inner.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String left = inner.substring(0, comma);
        String right = inner.substring(comma + 1);
        if (!isAllDigits(left) || !isAllDigits(right)) {
            return null;
        }
        return new long[]{Long.parseLong(left), Long.parseLong(right)};
    }

    public boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a bare `as` target name for structural comparison only (lighter than
     * resolveTypeAnnotation: no pointer-required validation).
     */
    public TypeInfo resolveCastTargetBare(String name, Token at) {
        if (hub.model.aliasRawTargets.containsKey(name)) {
            String[] aliasNameHolder = new String[1];
            List<Token> synthetic = new ArrayList<>();
            synthetic.add(new Token(TokenType.VARREF, name, at.line, at.file));
            List<Token> expanded = hub.signatures.expandAliases(synthetic, new HashSet<>(), aliasNameHolder, at);
            // resolveTypeAnnotationRaw needs exactly one mutability modifier; the synthetic 'imut' is
            // never read by checkAs (mutability is untouched by a cast).
            boolean alreadyHasMutability = expanded.stream().anyMatch(t ->
                    t.type == TokenType.MODIFIER && (t.text.equals("mut") || t.text.equals("imut")));
            if (!alreadyHasMutability) {
                expanded.add(0, new Token(TokenType.MODIFIER, "imut", at.line, at.file));
            }
            TypeInfo resolved = hub.signatures.resolveTypeAnnotationRaw(expanded, at);
            return new TypeInfo(resolved.storage, resolved.mutability, resolved.baseType, aliasNameHolder[0]);
        }
        if (!hub.signatures.isKnownType(name)) {
            throw new CompilerException("type", at.file, at.line, "unknown type '" + name + "'");
        }
        return new TypeInfo(null, "imut", name);
    }

    /** True when an active, un-invalidated 'match x fits T' proof covers this exact slot and target type. */
    public boolean isProvenFits(Token expr, String targetBase, Scope scope) {
        String key = hub.expressions.slotKeyOf(expr);
        if (key == null) {
            return false;
        }
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            if (p.kind.equals("fits") && !p.invalidated && key.equals(p.slotKey) && targetBase.equals(p.targetName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * "value as TypeName": an alias relabel (same structure underneath), a same-signedness widening
     * (u8->u16->u32->u64, s8->..->s64), or a `match x fits T` proven tightening. The right side is read
     * off its token, not typed as an expression. See docs/TypeChecker.md, "as casts".
     */
    public TypeInfo checkAs(Token op, Scope scope, FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.AS, op, scope, func));
    }

    public int stepAs(CheckFrame f) {
        if (f.phase == 0) {
            f.phase = 1;
            return f.call(f.node.left);
        }
        return f.done(asType(f.node, f.scope, f.func, f.got));
    }

    private TypeInfo asType(Token op, Scope scope, FuncInfo func, TypeInfo leftType) {        // `x as f64` for an f32 x: always exact, never needs a float-state proof. A bare float literal
        // simply adapts to f64 (keeping its full decimal text) instead of being widened from f32.
        if (op.right.type == TokenType.VARREF && op.right.text.equals("f64") && leftType.storage == null
                && leftType.baseType.equals("f32") && !hub.model.aliasRawTargets.containsKey("f64")) {
            if (leftType.floatLiteral) {
                TypeInfo f64Slot = new TypeInfo(null, leftType.mutability, "f64");
                hub.adaptLiteral(op.left, f64Slot);
                op.chk.isAliasCast = true;
                return new TypeInfo(null, leftType.mutability, "f64");
            }
            op.chk.isFloatConvert = true;
            return new TypeInfo(null, leftType.mutability, "f64");
        }
        requireProvenFloatState(op.left, leftType, scope, op);

        if (op.right.type == TokenType.VARREF) {
            String targetBase = op.right.text;

            // Alias casts: legal when storage and base type match structurally (mutability untouched).
            if (hub.model.aliasRawTargets.containsKey(targetBase) || leftType.aliasName != null) {
                TypeInfo resolvedTarget = resolveCastTargetBare(targetBase, op.right);
                boolean structuralMatch = java.util.Objects.equals(leftType.storage, resolvedTarget.storage)
                        && leftType.baseType.equals(resolvedTarget.baseType);
                if (!structuralMatch) {
                    throw new CompilerException("type", op.right.file, op.right.line,
                            "cannot cast '" + leftType.canonical() + "' as '" + targetBase + "' -- they "
                                    + "don't share the same underlying representation");
                }
                op.chk.isAliasCast = true;
                return new TypeInfo(leftType.storage, leftType.mutability, resolvedTarget.baseType,
                        resolvedTarget.aliasName);
            }

            if (!TypeCheckerModel.INTEGER_TYPES.contains(leftType.baseType) || !TypeCheckerModel.INTEGER_TYPES.contains(targetBase)) {
                throw new CompilerException("type", op.right.file, op.right.line,
                        "'as' can only widen between integer types, got '" + leftType.canonical()
                                + "' as '" + targetBase + "'");
            }
            char leftFamily = leftType.baseType.charAt(0);
            char targetFamily = targetBase.charAt(0);
            if (isProvenFits(op.left, targetBase, scope)) {
                // Integer tightening: an active 'match x fits T' proof makes this cast lossless,
                // so it may narrow or change sign. Same width = pure relabel; narrower = TRUNC;
                // wider = SEXT/ZEXT by the source's signedness (the value is proven representable).
                int pw = TypeCheckerModel.INTEGER_WIDTH.get(leftType.baseType);
                int tw = TypeCheckerModel.INTEGER_WIDTH.get(targetBase);
                if (tw < pw) {
                    op.chk.isTruncCast = true;
                } else if (tw == pw) {
                    op.chk.isAliasCast = true;
                } else {
                    op.chk.isSignedWideningCast = (leftFamily == 's');
                }
                return new TypeInfo(leftType.storage, leftType.mutability, targetBase);
            }
            if (leftFamily != targetFamily) {
                throw new CompilerException("type", op.right.file, op.right.line,
                        "'as' cannot cast across signedness ('" + leftType.baseType + "' as '"
                                + targetBase + "'); cast within the same signed/unsigned family only, prove the "
                                + "value fits with 'match x fits " + targetBase + "{ ... }', or convert with "
                                + "'wrap:<" + targetBase + ">(x)' / 'sat:<" + targetBase + ">(x)'");
            }
            int leftWidth = TypeCheckerModel.INTEGER_WIDTH.get(leftType.baseType);
            int targetWidth = TypeCheckerModel.INTEGER_WIDTH.get(targetBase);
            if (targetWidth <= leftWidth) {
                throw new CompilerException("type", op.right.file, op.right.line,
                        "'as' only widens ('" + leftType.baseType + "' as '" + targetBase
                                + "' is not wider) -- prove the value fits with 'match x fits " + targetBase
                                + "{ ... }', or convert with 'wrap:<" + targetBase + ">(x)' / 'sat:<" + targetBase
                                + ">(x)'");
            }
            op.chk.isSignedWideningCast = (leftFamily == 's');
            return new TypeInfo(leftType.storage, leftType.mutability, targetBase);
        }

        throw new CompilerException("type", op.right.file, op.right.line,
                "'as' requires an integer type name (for widening) or a type/alias name on the right "
                        + "-- mutability is never affected by 'as' (use the 'mut'/'imut' prefix "
                        + "operator instead)");
    }


    /**
     * The single gate for "needs a `match Some(x)` proof before '.field'": real storage, not `auto`
     * (non-null by construction), not `some`-tagged, and no active proof for this slot. Shared by
     * checkDot's range/struct sites so they cannot drift.
     */
    public boolean requiresAliveProof(TypeInfo t, Token targetExpr, Scope scope) {
        if (t.storage == null || t.storage.equals("auto") || t.isSome) {
            return false;
        }
        return !isProvenAlive(targetExpr, scope);
    }

    public boolean isProvenFloatState(Token targetExpr, Scope scope) {
        String key = hub.expressions.lookupKeyOf(targetExpr);
        if (key == null) {
            return false;
        }
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            if (!p.invalidated && p.kind.equals("float_state") && key.equals(p.slotKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Float "access" (arithmetic, comparisons, unary minus, `as`) needs a known state: a type-level
     * `floatStates`, an active float_state proof for the slot, a literal, or 'unsafe'. Passing or
     * returning a float needs no proof. See docs/TypeChecker.md, "Proof lookups".
     */
    public void requireProvenFloatState(Token expr, TypeInfo type, Scope scope, Token op) {
        if (!TypeCheckerModel.isFloatType(type.baseType)) {
            return;
        }
        Token unwrapped = expr;
        while (unwrapped.type == TokenType.OPERATOR
                && (unwrapped.text.equals("mut") || unwrapped.text.equals("imut")) && unwrapped.left != null) {
            // See through mut/imut wrappers: "mut 0.0" is a literal too.
            unwrapped = unwrapped.left;
        }
        if (unwrapped.type == TokenType.OPERATOR && unwrapped.right == null && unwrapped.left != null
                && unwrapped.text.equals("-") && unwrapped.left.type == TokenType.FLOAT) {
            return; // a negated float literal is as known as the literal
        }
        if (unwrapped.type == TokenType.FLOAT) {
            // No NaN/Infinity literal syntax: every float literal is finite.
            return;
        }
        if (type.floatStates != null || type.floatLiteral) {
            return;
        }
        if (isProvenFloatState(unwrapped, scope)) {
            return;
        }
        throw new CompilerException("type", op.file, op.line,
                "'" + op.text + "' on an unproven '" + type.baseType + "' isn't permitted -- prove it first with "
                        + "'match " + hub.controlFlow.describeConditionForError(unwrapped) + "{ finite:{...} infinite:{...} "
                        + "nan:{...} }' (or a narrower set), or vouch for it with 'assume match "
                        + hub.controlFlow.describeConditionForError(unwrapped) + " : finite' in an 'unsafe assume' block"
                        + (hub.expressions.exprKeyOf(unwrapped) != null ? " [needs: assume match " + hub.expressions.exprHint(unwrapped) + " : finite]" : ""));
    }

    /**
     * True for a type with real storage, or a struct with such a member (recursively; struct cycles are
     * already a hard error, so this terminates). Used to ban passing/returning pointers to async.
     */
    public boolean containsPointerAnywhere(TypeInfo root) {
        Deque<TypeInfo> work = new ArrayDeque<>();
        work.push(root);
        while (!work.isEmpty()) {
            TypeInfo type = work.pop();
            if (type.storage != null) {
                return true;
            }
            StructInfo si = hub.model.structs.get(type.baseType);
            if (si != null) {
                si.members.values().forEach(work::push);
            }
        }
        return false;
    }

    /** True if a live "alive" proof covers this slot (`match x{..}` / `match Some(x)`); the match does not change x's type. */
    public boolean isProvenAlive(Token targetExpr, Scope scope) {
        String key = hub.expressions.lookupKeyOf(targetExpr);
        if (key == null) {
            return false;
        }
        List<Token.MatchPattern> cands = null;
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            if (!p.invalidated && p.kind.equals("alive") && key.equals(p.slotKey)) {
                if (cands == null) {
                    cands = new ArrayList<>();
                }
                cands.add(p);
            }
        }
        if (cands == null) {
            return false;
        }
        ProofKills.use(targetExpr, cands, key);
        for (Token.MatchPattern c : cands) {
            if (c.onId) {
                targetExpr.chk.refProofIsId = true;   // the proof is on a nullable `ref` (an id): the emitter reads the proof's address
                break;
            }
        }
        targetExpr.chk.refProofKey = key;   // a nullable `ref` subject is read from the proof's address slot (BytecodeEmitter)
        return true;
    }

    /** Division-safety counterpart of isProvenAlive ("match rht!=0 and lft!=0{ .. }"). */
    public boolean isProvenNonzero(Token targetExpr, Scope scope) {
        String key = hub.expressions.lookupKeyOf(targetExpr);
        if (key == null) {
            return false;
        }
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            if (!p.invalidated && p.kind.equals("nonzero") && key.equals(p.slotKey)) {
                return true;
            }
        }
        return false;
    }

    public CheckFrame lockMatchFrame(Token t, Scope scope, FuncInfo func, boolean insideLoop) {
        CheckFrame f = new CheckFrame(CheckFrame.LOCK_MATCH, t, scope, func);
        f.q = insideLoop;
        return f;
    }


}

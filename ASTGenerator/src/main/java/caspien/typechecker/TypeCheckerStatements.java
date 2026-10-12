package caspien.typechecker;
import caspien.bytecodeemitter.BytecodeEmitter;
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

/** TypeCheckerStatements: function bodies, recursion lowering, statement dispatch, unsafe and assume (part 5 of 11; see README.md in this folder). */
public final class TypeCheckerStatements {
    private final TypeChecker hub;

    TypeCheckerStatements(TypeChecker hub) {
        this.hub = hub;
    }

    public boolean isLockedAsyncHandle(TypeCheckerModel.TypeInfo t) {
        if (t == null || !"owns".equals(t.storage) || t.baseType == null || !t.baseType.startsWith("AsyncHandle")) {
            return false;
        }
        TypeCheckerModel.StructInfo si = hub.model.structs.get(t.baseType);
        return si != null && "gate".equals(si.swapFieldName);
    }

    public void checkFunctionBody(TypeCheckerModel.FuncInfo func) {
        Map<String, Token> savedDecls = hub.model.fnHandleDecls;
        Set<String> savedMatched = hub.model.fnLockMatched;
        Set<String> savedMoved = hub.model.fnEverMoved;
        hub.model.fnHandleDecls = new LinkedHashMap<>();
        hub.model.fnLockMatched = new HashSet<>();
        hub.model.fnEverMoved = new HashSet<>();
        try {
            checkFunctionBodyWithHandles(func);
        } finally {
            hub.model.fnHandleDecls = savedDecls;
            hub.model.fnLockMatched = savedMatched;
            hub.model.fnEverMoved = savedMoved;
        }
    }

    public void checkFunctionBodyWithHandles(TypeCheckerModel.FuncInfo func) {
        List<Integer> savedLoops = ProofKills.saveLoops();
        ProofKills.clearLoops();
        ProofKills.enterFunction(func);
        try {
            checkFunctionBodyInner(func);
        } finally {
            ProofKills.exitFunction();
            ProofKills.restoreLoops(savedLoops);
        }
        for (Map.Entry<String, Token> e : hub.model.fnHandleDecls.entrySet()) {
            if (!hub.model.fnLockMatched.contains(e.getKey()) && !hub.model.fnEverMoved.contains(e.getKey())) {
                throw new CompilerException("type", e.getValue().file, e.getValue().line,
                        "the 'par' handle '" + e.getKey() + "' is never looked at: take its lock with 'match @lock " + e.getKey()
                                + "{ OPEN:{ ... } CLOSED:default(n) }' (read 'state' and ':resolve()' inside OPEN), or pass it on to something that does");
            }
        }
    }

    public void checkFunctionBodyInner(TypeCheckerModel.FuncInfo func) {
        if (hub.impls.isPureRtFunc(func)) {
            for (int i = 0; i < func.paramTypes.size(); i++) {
                hub.impls.requirePureRtNoPointer(func.paramTypes.get(i), func.funcToken, func, "as parameter '" + func.paramNames.get(i) + "'");
            }
            hub.impls.requirePureRtNoPointer(func.returnType, func.funcToken, func, "as its return type");
        }
        TypeCheckerModel.Scope scope = new TypeCheckerModel.Scope(null, func.safetyTag);
        for (int i = 0; i < func.paramNames.size(); i++) {
            TypeCheckerModel.TypeInfo paramType = func.paramTypes.get(i);
            if ("ref".equals(paramType.storage) && paramType.isSome) {
                ProofKills.implicit(func.paramNames.get(i), hub.declarations.pointeeOf(paramType));   // the caller guarantees it is alive at entry
            }
            scope.vars.put(func.paramNames.get(i), func.paramTypes.get(i));
            // An owns parameter is owed a destruct unless moved; registered on the root scope (the obligation spans the whole function).
            if ("owns".equals(func.paramTypes.get(i).storage)) {
                scope.ownsDeclaredHere.add(func.paramNames.get(i));
                func.ownsLocalTypes.put(func.paramNames.get(i), func.paramTypes.get(i));
            }
        }
        validateStaticLetPlacement(func.funcToken.childs);
        validateRecursiveFuncRequirements(func);
        List<Token.MatchPattern> assumedLocks = hub.locksAndProofs.assumedLockPatterns(func);
        TypeCheckerModel.Scope bodyCheckScope = assumedLocks.isEmpty() ? scope : new TypeCheckerModel.Scope(scope, assumedLocks, false);
        hub.model.insideConstructorBodyForStruct = func.isConstructor ? func.constructorConcreteName : null;
        TypeCheckerModel.Scope bodyScope = checkLines(func.funcToken.childs, bodyCheckScope, func, false);
        hub.model.insideConstructorBodyForStruct = null;
        // `@throws` is required exactly when the body has a validated `throw` (func.sawThrow).
        if (func.isThrows && !func.sawThrow) {
            throw new CompilerException("type", func.funcToken.file, func.funcToken.line,
                    "'" + func.name + "' is decorated '@throws' but its body contains no 'throw' -- "
                            + "remove '@throws' (or add a 'throw')");
        }
        // Every path of a non-void function must end in a return (conservative; see definitelyReturns and docs/TypeChecker.md, "Control-flow termination checks").
        if (!isVoidReturnType(func.returnType) && !definitelyReturns(func.funcToken.childs)) {
            throw new CompilerException("type", func.funcToken.file, func.funcToken.line,
                    "'" + func.name + "' is declared to return '" + func.returnType.canonical()
                            + "' but not every path through its body ends in a 'return'");
        }
        // Falling off the end destructs everything live up to and including the root scope (owns parameters).
        func.funcToken.destructOnExit = collectNaturalEndDestruct(func.funcToken.childs, bodyScope, scope);
        // Runs after every restriction above, on the original (un-lowered) shape; the lowering only changes bytecode shape.
        if (hub.impls.getDecorator(func.funcToken.decorators, "recursive") != null) {
            lowerRecursiveFunctionToLoop(func, scope);
        }
    }

    public boolean isVoidReturnType(TypeCheckerModel.TypeInfo t) {
        return t.baseType.equals("void");
    }

    /** Conservative: does some statement of `lines` definitely end in a return (or throw)? See docs/TypeChecker.md, "Control-flow termination checks". */
    public boolean definitelyReturns(List<Token> lines) {
        for (Token lineTok : lines) {
            // Skip blank lines (a lowered match case body can contain one).
            if (lineTok.childs.isEmpty()) {
                continue;
            }
            Token stmt = lineTok.childs.get(0);
            if (statementDefinitelyReturns(stmt)) {
                return true;
            }
        }
        return false;
    }

    public boolean statementDefinitelyReturns(Token stmt) {
        if (stmt.type != TokenType.KEYWORD) {
            return false;
        }
        switch (stmt.text) {
            case "return":
            case "throw":
                return true;
            case "break":
                // Counts only while checking a `match @lock` CLOSED case (closedCaseTerminates): break = give up without the lock.
                return hub.model.breakCountsAsTerminator;
            case "continue":
                // Legal only in a catch body (checkStatement rejects other uses); jumps past the enclosing try-block, so it terminates like return/throw.
                return true;
            case "if":
            case "match":
                // if/match share the '.right'-linked chain: counts only if it ends in a plain else and every branch returns.
                boolean sawElse = false;
                for (Token branch = stmt; branch != null; branch = branch.right) {
                    if (!definitelyReturns(branch.childs)) {
                        return false;
                    }
                    if (branch.sub.isEmpty()) {
                        sawElse = true;
                    }
                }
                return sawElse;
            case "loop":
                // With no 'break' a loop can only end by returning (or never).
                return !containsBreak(stmt.childs);
            case "unsafe":
            case "safe":
                // Unconditional: propagates from the body.
                return definitelyReturns(stmt.childs);
            case "lock":
                // Same as unsafe/safe.
                return definitelyReturns(stmt.childs);
            case "try":
                // Only the plain "try { }" block is unconditional; a throwing "try EXPR catch" falls through to false.
                return stmt.isTryBlock && definitelyReturns(stmt.childs);
            default:
                // Anything else never proves a return.
                return false;
        }
    }

    /** True if a 'break' appears in `lines` at any depth, except inside a nested 'loop' (whose break is its own). */
    public boolean containsBreak(List<Token> lines) {
        for (Token lineTok : lines) {
            if (statementContainsBreak(lineTok.childs.get(0))) {
                return true;
            }
        }
        return false;
    }

    public boolean statementContainsBreak(Token stmt) {
        if (stmt.type != TokenType.KEYWORD) {
            return false;
        }
        switch (stmt.text) {
            case "break":
                return true;
            case "loop":
                return false; // a nested loop's own 'break' targets it, not the outer loop
            case "if":
                for (Token branch = stmt; branch != null; branch = branch.right) {
                    if (containsBreak(branch.childs)) {
                        return true;
                    }
                }
                return false;
            case "match":
                for (Token branch = stmt; branch != null; branch = branch.right) {
                    if (containsBreak(branch.childs)) {
                        return true;
                    }
                }
                return false;
            case "unsafe":
            case "safe":
                return containsBreak(stmt.childs);
            case "lock":
                // Not conditional: a break in its body belongs to the enclosing loop.
                return containsBreak(stmt.childs);
            case "try":
                // Only the plain try-block form is unconditional (as in statementDefinitelyReturns).
                return stmt.isTryBlock && containsBreak(stmt.childs);
            default:
                return false;
        }
    }

    /**
     * True if a real throwing "try EXPR catch(e){ }" appears anywhere in `lines` (any depth, also through for/assume and nested
     * try-blocks); used by checkTryBlock. Broader than definitelyReturns/containsBreak, which only walk the relevant constructs.
     */
    public boolean containsRealTryCatch(List<Token> lines) {
        for (Token lineTok : lines) {
            if (lineTok.childs.isEmpty()) {
                continue;
            }
            if (statementContainsRealTryCatch(lineTok.childs.get(0))) {
                return true;
            }
        }
        return false;
    }

    public boolean statementContainsRealTryCatch(Token stmt) {
        // A try used as a let/assignment RHS hangs off the "=" operator (through mut/imut), as in BytecodeEmitter.tryNodeOf.
        if (stmt.type == TokenType.OPERATOR && stmt.text.equals("=") && stmt.right != null) {
            Token rhs = stmt.right;
            while (rhs.type == TokenType.OPERATOR && (rhs.text.equals("mut") || rhs.text.equals("imut"))
                    && rhs.left != null) {
                rhs = rhs.left;
            }
            if (rhs.type == TokenType.KEYWORD && rhs.text.equals("try")) {
                return !rhs.isTryBlock || containsRealTryCatch(rhs.childs);
            }
            return false;
        }
        if (stmt.type != TokenType.KEYWORD) {
            return false;
        }
        switch (stmt.text) {
            case "try":
                // A real try/catch qualifies; a nested "try { }" block does not itself, but is searched.
                return !stmt.isTryBlock || containsRealTryCatch(stmt.childs);
            case "if":
            case "match":
                for (Token branch = stmt; branch != null; branch = branch.right) {
                    if (containsRealTryCatch(branch.childs)) {
                        return true;
                    }
                }
                return false;
            case "loop":
            case "for":
            case "unsafe":
            case "safe":
            case "lock":
                return containsRealTryCatch(stmt.childs);
            case "assume":
                return stmt.hasBlock && containsRealTryCatch(stmt.childs);
            case "return": {
                // "return ?f(x)" / "return try f(x) catch(e){...}": the returned expression is the try (through any mut/imut wrapper)
                if (stmt.sub == null || stmt.sub.size() != 1) {
                    return false;
                }
                Token rv = stmt.sub.get(0);
                while (rv.type == TokenType.OPERATOR && (rv.text.equals("mut") || rv.text.equals("imut")) && rv.left != null) {
                    rv = rv.left;
                }
                return rv.type == TokenType.KEYWORD && rv.text.equals("try") && !rv.isTryBlock;
            }
            default:
                return false;
        }
    }

    /**
     * Plain "try { }" lexical block (Token.isTryBlock): must contain at least one real try/catch (any depth); otherwise an ordinary
     * child Scope with the safety context inherited. See docs/TypeChecker.md, "Control-flow termination checks".
     */
    public void checkTryBlock(Token stmt, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, boolean insideLoop) {
        if (!containsRealTryCatch(stmt.childs)) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "'try { ... }' requires at least one real 'try ... catch(e) { ... }' statement "
                            + "somewhere inside it");
        }
        // TryBlockBoundaryMarker makes this scope the target a nested `continue` resolves via tryBlockBoundaryScope.
        TypeCheckerModel.Scope tryBlockScope = new TypeCheckerModel.Scope(scope, TypeCheckerModel.TryBlockBoundaryMarker.MARK);
        checkLinesInScope(stmt.childs, tryBlockScope, func, insideLoop);
        stmt.destructOnExit = collectNaturalEndDestruct(stmt.childs, tryBlockScope, tryBlockScope);
    }

    /** A `@recursive` function needs a final `imut range` parameter (any name) and a first statement (after `let static`s) of `match PARAM is base{...}`; structural checks only. See docs/TypeChecker.md, "@recursive functions". */
    public void validateRecursiveFuncRequirements(TypeCheckerModel.FuncInfo func) {
        if (hub.impls.getDecorator(func.funcToken.decorators, "recursive") == null) {
            return;
        }
        int n = func.paramNames.size();
        if (n == 0 || !func.paramTypes.get(n - 1).canonical().equals("imut_range")) {
            throw new CompilerException("type", func.funcToken.file, func.funcToken.line,
                    "a '@recursive' function must have an 'imut range' as its final parameter");
        }
        String rangeParamName = func.paramNames.get(n - 1);
        // Strict: any statement other than `let static` before the required match is rejected.
        Token firstNonStatic = null;
        for (Token lineTok : func.funcToken.childs) {
            Token stmt = lineTok.childs.get(0);
            if (staticLetTokenOf(stmt) != null) {
                continue;
            }
            firstNonStatic = stmt;
            break;
        }
        if (!isRequiredBaseCaseMatch(firstNonStatic, rangeParamName)) {
            throw new CompilerException("type", func.funcToken.file, func.funcToken.line,
                    "a '@recursive' function's first statement (after any leading 'let static' "
                            + "declarations) must be 'match " + rangeParamName + " is base{...}' (its own "
                            + "final range parameter)");
        }
    }

    /** Shape check only: "match RANGEPARAM is base{...}" (checkMatchCondition confirms the type later). */
    public boolean isRequiredBaseCaseMatch(Token stmt, String rangeParamName) {
        if (stmt == null || stmt.type != TokenType.KEYWORD || !stmt.text.equals("match") || stmt.sub.isEmpty()) {
            return false;
        }
        Token condition = stmt.sub.get(0);
        return condition.type == TokenType.OPERATOR && condition.text.equals("is")
                && condition.left.type == TokenType.VARREF && condition.left.text.equals(rangeParamName)
                && condition.right.type == TokenType.VARREF && condition.right.text.equals("base");
    }

    /** A self-call to a `@recursive` function may only be the sole expression of a `return`, checked by node identity with directReturnExprNode. See docs/TypeChecker.md, "@recursive functions". */
    public void requireRecursiveCallReturnSiteOnly(TypeCheckerModel.FuncInfo func, Token op) {
        if (op != hub.model.directReturnExprNode) {
            throw new CompilerException("type", op.file, op.line,
                    "a recursive call to '" + func.name + "' may only appear as the sole expression of "
                            + "a 'return' statement -- e.g. 'return " + func.name + "(...)' -- never bound "
                            + "to a variable first, and never nested inside a larger expression");
        }
        op.isRecursiveTailCallSite = true;
    }

    /** Every self-call must sit inside `match x within RANGEPARAM{}` with x an imut range; absolute rule, no unsafe bypass. See docs/TypeChecker.md, "@recursive functions". */
    public void requireRecursiveCallWithinProof(TypeCheckerModel.FuncInfo func, Token op, List<Token> argNodes,
            List<TypeCheckerModel.TypeInfo> argTypes, TypeCheckerModel.Scope scope) {
        // The last parameter is an imut range (validateRecursiveFuncRequirements).
        String rangeParamName = func.paramNames.get(func.paramNames.size() - 1);
        Token finalArg = argNodes.get(argNodes.size() - 1);
        // Reuse the argTypes from overload matching; do not resolve the node again.
        TypeCheckerModel.TypeInfo finalArgType = argTypes.get(argTypes.size() - 1);
        if (!"imut".equals(finalArgType.mutability)) {
            throw new CompilerException("type", finalArg.file, finalArg.line,
                    "a recursive call's own range argument must be 'imut', got '"
                            + finalArgType.canonical() + "'");
        }
        String argSlotKey = hub.expressions.slotKeyOf(finalArg);
        boolean proven = false;
        if (argSlotKey != null) {
            for (Token.MatchPattern p : scope.activeMatchPatterns) {
                if (!p.invalidated && p.kind.equals("in") && argSlotKey.equals(p.slotKey)
                        && rangeParamName.equals(p.rightSlotKey)) {
                    proven = true;
                    break;
                }
            }
        }
        if (!proven) {
            throw new CompilerException("type", op.file, op.line,
                    "a recursive call to '" + func.name + "' must be wrapped in 'match "
                            + (argSlotKey != null ? argSlotKey : "...") + " within " + rangeParamName
                            + "{...}' -- proving the recursive call's own range argument is a proper "
                            + "subrange of the function's own range parameter (no 'unsafe' bypass is "
                            + "available for this proof)");
        }
    }

    /**
     * Rewrites a validated `@recursive` function body into a bounded `for` loop over shadow locals; each tail-call return site becomes
     * a reassignment of the shadows. See docs/TypeChecker.md, "@recursive functions".
     */
    public void lowerRecursiveFunctionToLoop(TypeCheckerModel.FuncInfo func, TypeCheckerModel.Scope paramScope) {
        List<Token> original = func.funcToken.childs;
        int splitIdx = 0;
        while (splitIdx < original.size() && staticLetTokenOf(original.get(splitIdx).childs.get(0)) != null) {
            splitIdx++;
        }
        List<Token> leadingStatics = new ArrayList<>(original.subList(0, splitIdx));
        List<Token> restLines = new ArrayList<>(original.subList(splitIdx, original.size()));

        int line = func.funcToken.line;
        String file = func.funcToken.file;
        TypeCheckerModel.Scope wrapperScope = new TypeCheckerModel.Scope(paramScope);

        List<Token> newChilds = new ArrayList<>(leadingStatics);

        int n = func.paramNames.size();
        for (int i = 0; i < n; i++) {
            String paramName = func.paramNames.get(i);
            Token valueExpr = new Token(TokenType.VARREF, paramName, line, file);
            newChilds.add(wrapAsCheckedLine(buildCheckedLetAssign(paramName, valueExpr, wrapperScope, func)));
        }

        String rangeParamName = func.paramNames.get(n - 1);
        Token startDot = new Token(TokenType.OPERATOR, ".", line, file);
        startDot.left = new Token(TokenType.VARREF, rangeParamName, line, file);
        startDot.right = new Token(TokenType.VARREF, "start", line, file);
        Token mutStart = new Token(TokenType.OPERATOR, "mut", line, file);
        mutStart.left = startDot;
        newChilds.add(wrapAsCheckedLine(buildCheckedLetAssign("__recur_range_start", mutStart, wrapperScope, func)));

        Token endDot = new Token(TokenType.OPERATOR, ".", line, file);
        endDot.left = new Token(TokenType.VARREF, rangeParamName, line, file);
        endDot.right = new Token(TokenType.VARREF, "end", line, file);
        Token mutEnd = new Token(TokenType.OPERATOR, "mut", line, file);
        mutEnd.left = endDot;
        newChilds.add(wrapAsCheckedLine(buildCheckedLetAssign("__recur_range_end", mutEnd, wrapperScope, func)));

        Token rangeDotDot = new Token(TokenType.OPERATOR, "..", line, file);
        rangeDotDot.left = new Token(TokenType.VARREF, "__recur_range_start", line, file);
        rangeDotDot.right = new Token(TokenType.VARREF, "__recur_range_end", line, file);
        newChilds.add(wrapAsCheckedLine(buildCheckedLetAssign("__recur_range", rangeDotDot, wrapperScope, func)));

        // The loop runs (initial length) times, but a recursion shrinking by one per step needs one more call to reach its base case.
        // The range is then empty, so a copy of the validated base-case block after the loop does what that call would (cloned before the return sites are rewritten).
        Token baseCaseAfterLoop = restLines.get(0).deepCloneChecked();
        replaceRecursiveReturnsInLines(restLines, func);

        Token inTok = new Token(TokenType.OPERATOR, "in", line, file);
        inTok.left = new Token(TokenType.VARREF, "__recur_i", line, file);
        inTok.right = new Token(TokenType.VARREF, "__recur_range", line, file);
        Token forTok = new Token(TokenType.KEYWORD, "for", line, file);
        forTok.sub = new ArrayList<>();
        forTok.sub.add(inTok);
        forTok.childs = new ArrayList<>();
        // Validate only the loop header against an empty body (checkForLoop reused unchanged), then swap in the already-validated body:
        // re-checking it would reject the synthetic reassignment of imut parameters.
        hub.controlFlow.checkForLoop(forTok, wrapperScope, func);
        forTok.childs = restLines;
        forTok.destructOnExit = new ArrayList<>();
        newChilds.add(wrapAsCheckedLine(forTok));
        newChilds.add(baseCaseAfterLoop);

        func.funcToken.childs = newChilds;
    }

    /** Builds and fully, really type-checks "let NAME = valueExpr" against `scope`, returning the checked "=" node (not yet line-wrapped). */
    public Token buildCheckedLetAssign(String name, Token valueExpr, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        Token nameTok = new Token(TokenType.VARREF, name, valueExpr.line, valueExpr.file);
        Token letTok = new Token(TokenType.KEYWORD, "let", valueExpr.line, valueExpr.file);
        letTok.childs.add(nameTok);
        Token assign = new Token(TokenType.OPERATOR, "=", valueExpr.line, valueExpr.file);
        assign.left = letTok;
        assign.right = valueExpr;
        TypeCheckerModel.TypeInfo t = hub.expressions.checkAssign(assign, scope, func);
        assign.resolvedType = t.canonical();
        return assign;
    }

    /** The "LINE" wrapper Parser.wrapAsLine builds; statement walkers rely on lineTok.childs.get(0). */
    public Token wrapAsCheckedLine(Token stmt) {
        Token lineTok = new Token(TokenType.LINE, "line", stmt.line, stmt.file);
        lineTok.childs.add(stmt);
        return lineTok;
    }

    /**
     * Replaces every "return SELFCALL(..)" marked isRecursiveTailCallSite, wherever it nests, with the reassignment
     * lines; any number of call sites. See docs/TypeChecker.md, "Recursive tail-call lowering".
     */
    public void replaceRecursiveReturnsInLines(List<Token> lines, TypeCheckerModel.FuncInfo func) {
        for (int i = 0; i < lines.size(); i++) {
            Token lineTok = lines.get(i);
            if (lineTok.childs.isEmpty()) {
                continue;
            }
            Token stmt = lineTok.childs.get(0);
            if (stmt.type == TokenType.KEYWORD && stmt.text.equals("return") && !stmt.sub.isEmpty()
                    && stmt.sub.get(0).isRecursiveTailCallSite) {
                List<Token> replacement = buildTailCallReplacementLines(stmt, func);
                lines.remove(i);
                lines.addAll(i, replacement);
                i += replacement.size() - 1;
                continue;
            }
            if (stmt.type != TokenType.KEYWORD) {
                continue;
            }
            if (stmt.text.equals("match") && stmt.isCaseMatch) {
                for (Token caseTok : stmt.childs) {
                    replaceRecursiveReturnsInLines(caseTok.childs, func);
                }
            } else if (stmt.text.equals("if") || stmt.text.equals("match")) {
                for (Token branch = stmt; branch != null; branch = branch.right) {
                    replaceRecursiveReturnsInLines(branch.childs, func);
                }
            } else if (stmt.text.equals("for") || stmt.text.equals("loop") || stmt.text.equals("unsafe")
                    || stmt.text.equals("safe")) {
                replaceRecursiveReturnsInLines(stmt.childs, func);
            } else if (stmt.text.equals("assume") && stmt.hasBlock) {
                replaceRecursiveReturnsInLines(stmt.childs, func);
            }
        }
    }

    /**
     * Replacement for "return SELFCALL(a0, a1, ..)": one checked "let __recur_tmpN = argN" per argument (all evaluated
     * before any parameter changes), then an unchecked "paramN = __recur_tmpN" per parameter.
     * See docs/TypeChecker.md, "Recursive tail-call lowering".
     */
    public List<Token> buildTailCallReplacementLines(Token returnTok, TypeCheckerModel.FuncInfo func) {
        Token callOp = returnTok.sub.get(0);
        List<Token> argNodes = new ArrayList<>();
        if (!callOp.right.childs.isEmpty()) {
            hub.locksAndProofs.collectCommaArgs(callOp.right.childs.get(0), argNodes);
        }
        int line = returnTok.line;
        String file = returnTok.file;
        List<Token> out = new ArrayList<>();
        List<String> tempNames = new ArrayList<>();
        for (int i = 0; i < argNodes.size(); i++) {
            Token argNode = argNodes.get(i);
            String tempName = "__recur_tmp" + i;
            tempNames.add(tempName);
            Token nameTok = new Token(TokenType.VARREF, tempName, line, file);
            Token letTok = new Token(TokenType.KEYWORD, "let", line, file);
            letTok.childs.add(nameTok);
            letTok.resolvedType = argNode.resolvedType;
            Token assign = new Token(TokenType.OPERATOR, "=", line, file);
            assign.left = letTok;
            assign.right = argNode;
            assign.resolvedType = argNode.resolvedType;
            out.add(wrapAsCheckedLine(assign));
        }
        int n = func.paramNames.size();
        for (int i = 0; i < n && i < tempNames.size(); i++) {
            String paramName = func.paramNames.get(i);
            String paramType = func.paramTypes.get(i).canonical();
            Token targetRef = new Token(TokenType.VARREF, paramName, line, file);
            targetRef.resolvedType = paramType;
            Token sourceRef = new Token(TokenType.VARREF, tempNames.get(i) != null ? tempNames.get(i) : argNodes.get(i).text, line, file);
            sourceRef.resolvedType = paramType;
            Token assign = new Token(TokenType.OPERATOR, "=", line, file);
            assign.left = targetRef;
            assign.right = sourceRef;
            assign.resolvedType = paramType;
            out.add(wrapAsCheckedLine(assign));
        }
        return out;
    }

    /**
     * "let static" is only allowed as a leading run at the top of the function body (never in a nested block, which this
     * scan cannot see; checkAssign re-checks staticApproved for that reason). See docs/TypeChecker.md, "Static let placement".
     */
    public void validateStaticLetPlacement(List<Token> lines) {
        boolean stillLeading = true;
        for (Token lineTok : lines) {
            Token stmt = lineTok.childs.get(0);
            Token letTok = staticLetTokenOf(stmt);
            if (letTok != null) {
                if (!stillLeading) {
                    throw new CompilerException("type", stmt.file, stmt.line,
                            "'let static' is only allowed at the top of a function body, before any "
                                    + "other statement");
                }
                letTok.staticApproved = true;
            } else {
                stillLeading = false;
            }
        }
    }

    /** Returns the 'let' token if `stmt` is a "let static ... = ..." declaration, else null. */
    public Token staticLetTokenOf(Token stmt) {
        if (stmt.type == TokenType.OPERATOR && stmt.text.equals("=")
                && stmt.left.type == TokenType.KEYWORD && stmt.left.text.equals("let") && stmt.left.isStatic) {
            return stmt.left;
        }
        return null;
    }

    public TypeCheckerModel.Scope checkLines(List<Token> lines, TypeCheckerModel.Scope parentScope, TypeCheckerModel.FuncInfo func, boolean insideLoop) {
        TypeCheckerModel.Scope scope = new TypeCheckerModel.Scope(parentScope);
        checkLinesInScope(lines, scope, func, insideLoop);
        return scope;
    }

    /** Used only for a loop's own body ("loop"/desugared "for") -- this scope becomes the new innermost loop boundary 'break' destructs up to. */
    public TypeCheckerModel.Scope checkLinesAsLoopBody(List<Token> lines, TypeCheckerModel.Scope parentScope, TypeCheckerModel.FuncInfo func) {
        return checkLinesAsLoopBody(lines, parentScope, func, false);
    }

    /** `isDynArrayForLoopBody` is true only for a "for i in dynarrayExpr{...}" loop's own body specifically -- see Scope.insideDynArrayForLoopBody's own comment. */
    public TypeCheckerModel.Scope checkLinesAsLoopBody(List<Token> lines, TypeCheckerModel.Scope parentScope, TypeCheckerModel.FuncInfo func, boolean isDynArrayForLoopBody) {
        TypeCheckerModel.Scope scope = new TypeCheckerModel.Scope(parentScope, true, isDynArrayForLoopBody);
        hub.model.userLoopScopes.add(scope);
        ProofKills.enterLoop();
        try {
            checkLinesInScope(lines, scope, func, true);
        } finally {
            ProofKills.exitLoop();
        }
        return scope;
    }

    /** Lock-release calls owed from `from` up to (not past) `boundary`, in LIFO order (activeLockReleases is extended by copy, so the span is a suffix). */
    public List<Token> collectLockReleasesToBoundary(TypeCheckerModel.Scope from, TypeCheckerModel.Scope boundary) {
        List<Token> slice = new ArrayList<>(from.activeLockReleases.subList(
                boundary.activeLockReleases.size(), from.activeLockReleases.size()));
        Collections.reverse(slice);
        return slice;
    }

    public boolean chainHasElse(Token first) {
        Token last = first;
        while (last.right != null) {
            last = last.right;
        }
        return last.sub.isEmpty();
    }

    /** Replaces the pre-branch moved set with the union of the branches and records the partly-moved slots; `survivors` are the moved sets of branches that fall through, `implicitPath` adds the no-branch-taken path of a chain without else. */
    public void finishBranchMerge(Set<String> preState, Set<String> union, List<Set<String>> survivors,
            boolean implicitPath) {
        List<Set<String>> paths = new ArrayList<>(survivors);
        if (implicitPath) {
            paths.add(new HashSet<>(preState));
        }
        preState.clear();
        preState.addAll(union);
        if (paths.isEmpty()) {
            return;
        }
        Set<String> all = new HashSet<>(paths.get(0));
        for (Set<String> p : paths) {
            all.retainAll(p);
        }
        for (String m : union) {
            if (!all.contains(m)) {
                hub.model.partlyMovedSlots.add(m);
            }
        }
    }

    /**
     * Owns slots to GT_DESTRUCT, innermost scope and most recent declaration first, up to `boundary`; skips moved slots and
     * `preservedSlotKey` (a return statement's returned slot). One flat entry per slot. See docs/TypeChecker.md, "Scope exit: destruct and unlock lists".
     */
    public List<String> collectOwnsToDestruct(TypeCheckerModel.Scope from, TypeCheckerModel.Scope boundary, String preservedSlotKey) {
        List<String> result = new ArrayList<>();
        for (TypeCheckerModel.Scope s = from; s != null; s = s.parent) {
            for (int i = s.ownsDeclaredHere.size() - 1; i >= 0; i--) {
                String name = s.ownsDeclaredHere.get(i);
                if ((s.movedSlots.contains(name) && !hub.model.partlyMovedSlots.contains(name)) || name.equals(preservedSlotKey)) {
                    continue;
                }
                result.add(name);
            }
            if (s == boundary) {
                break;
            }
        }
        return result;
    }

    /** Natural-end destruct list; empty when the last statement is a literal return/break (the list would be dead code). Deliberately no full reachability analysis. */
    public List<String> collectNaturalEndDestruct(List<Token> lines, TypeCheckerModel.Scope from, TypeCheckerModel.Scope boundary) {
        if (!lines.isEmpty()) {
            Token last = lines.get(lines.size() - 1).childs.get(0);
            if (last.type == TokenType.KEYWORD && (last.text.equals("return") || last.text.equals("break"))) {
                return new ArrayList<>();
            }
        }
        return collectOwnsToDestruct(from, boundary, null);
    }

    public void checkLinesInScope(List<Token> lines, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, boolean insideLoop) {
        checkNoCodeAfterTerminalStatement(lines);
        TypeCheckerModel.UnsafeBlockRecord blockRec = scope.unsafeBlock;      // set only for the scope of a written `unsafe` block: its direct children are attributed one by one
        int childIdx = 0;
        for (Token lineTok : lines) {
            Token stmt = lineTok.childs.get(0);
            if (blockRec != null) {
                blockRec.noteChild(childIdx++, stmt);
            }
            if (stmt.type == TokenType.KEYWORD && stmt.text.equals("assume") && !stmt.hasBlock) {
                // "assume match expr;" without a block holds for the rest of the enclosing block, so it swaps `scope` for a nested
                // Scope with the extra proofs here instead of going through checkStatement.
                List<Token.MatchPattern> assumePatterns = checkAssumeMatchCondition(stmt, scope, func);
                List<Token.MatchPattern> assumeExtended = new ArrayList<>(scope.activeMatchPatterns);
                assumeExtended.addAll(assumePatterns);
                scope = new TypeCheckerModel.Scope(scope, assumeExtended, false);
                continue;
            }
            checkStatement(stmt, scope, func, insideLoop);
        }
        if (blockRec != null) {
            blockRec.cur = -1;
        }
    }

    /**
     * Rejects any statement following one that always ends the block (statementDefinitelyReturns: return/throw/continue,
     * exhaustive if chains, break-free loops). A partly-returning "if" does not count. See docs/TypeChecker.md, "Unreachable code".
     */
    public void checkNoCodeAfterTerminalStatement(List<Token> lines) {
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).childs.isEmpty()) {
                continue; // a genuinely blank line -- see definitelyReturns' own identical guard
            }
            Token stmt = lines.get(i).childs.get(0);
            if (statementDefinitelyReturns(stmt)) {
                // The *next* real (non-blank) line is the actual
                // unreachable statement to point the error at.
                Token next = null;
                for (int j = i + 1; j < lines.size(); j++) {
                    if (!lines.get(j).childs.isEmpty()) {
                        next = lines.get(j).childs.get(0);
                        break;
                    }
                }
                if (next == null) {
                    continue; // everything remaining is blank lines -- nothing to actually flag
                }
                throw new CompilerException("type", next.file, next.line,
                        "unreachable code: nothing may follow a statement that always ends this "
                                + "block's own execution ('return'/'throw'/'continue', or an exhaustive "
                                + "if/else chain or break-free loop that always does)");
            }
        }
    }

    /**
     * Cheap structural pre-scan of a loop body: seeds the moved set with every in-scope owns slot the body moves anywhere, so
     * one real pass sees cross-iteration use-after-move. Call arguments only count for a name with exactly one overload.
     * See docs/TypeChecker.md, "Loop move pre-scan".
     */
    public void collectLoopMoves(List<Token> lines, TypeCheckerModel.Scope scope, Set<String> out) {
        for (Token lineTok : lines) {
            collectMovesInNode(lineTok.childs.get(0), scope, out);
        }
    }

    public void collectMovesInNode(Token node, TypeCheckerModel.Scope scope, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("=")) {
            Token destTok = node.left;
            if (destTok.type == TokenType.KEYWORD && destTok.text.equals("let")) {
                TypeCheckerModel.TypeInfo rhsDeclared = lookupDeclaredTypeStructurally(node.right, scope);
                if (rhsDeclared != null && "owns".equals(rhsDeclared.storage)) {
                    String key = hub.expressions.slotKeyOf(node.right);
                    if (key != null) {
                        out.add(key);
                    }
                }
            } else {
                TypeCheckerModel.TypeInfo declared = lookupDeclaredTypeStructurally(destTok, scope);
                if (declared != null && "owns".equals(declared.storage)) {
                    String key = hub.expressions.slotKeyOf(node.right);
                    if (key != null) {
                        out.add(key);
                    }
                }
            }
        } else if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left.type == TokenType.VARREF) {
            List<TypeCheckerModel.FuncInfo> overloads = hub.model.functions.get(node.left.text);
            if (overloads != null && overloads.size() == 1 && !node.right.childs.isEmpty()) {
                List<Token> argNodes = new ArrayList<>();
                hub.locksAndProofs.collectCommaArgs(node.right.childs.get(0), argNodes);
                TypeCheckerModel.FuncInfo only = overloads.get(0);
                for (int i = 0; i < argNodes.size() && i < only.paramTypes.size(); i++) {
                    if ("owns".equals(only.paramTypes.get(i).storage)) {
                        String key = hub.expressions.slotKeyOf(argNodes.get(i));
                        if (key != null) {
                            out.add(key);
                        }
                    }
                }
            }
        }
        collectMovesInNode(node.left, scope, out);
        collectMovesInNode(node.right, scope, out);
        if (node.childs != null) {
            for (Token c : node.childs) {
                collectMovesInNode(c, scope, out);
            }
        }
        if (node.sub != null) {
            for (Token s : node.sub) {
                collectMovesInNode(s, scope, out);
            }
        }
    }

    /** Structural-only type lookup (Scope/StructInfo, no expression resolution) -- used solely by collectLoopMoves's cheap pre-scan and checkAddressOf's 'ref' validation. */
    public TypeCheckerModel.TypeInfo lookupDeclaredTypeStructurally(Token node, TypeCheckerModel.Scope scope) {
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
            return lookupDeclaredTypeStructurally(node.childs.get(0), scope);
        }
        if (node.type == TokenType.VARREF) {
            return scope.lookup(node.text);
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && node.right.type == TokenType.VARREF) {
            TypeCheckerModel.TypeInfo leftType = lookupDeclaredTypeStructurally(node.left, scope);
            if (leftType == null) {
                return null;
            }
            TypeCheckerModel.StructInfo si = hub.model.structs.get(leftType.baseType);
            return si == null ? null : si.members.get(node.right.text);
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("LOOKUP") && node.left != null) {
            // "x[i]": the element type of a safe dynarray or fixed array, so `ref x[i].member` can see an `owns` member of an element
            TypeCheckerModel.TypeInfo arrType = lookupDeclaredTypeStructurally(node.left, scope);
            if (arrType == null) {
                return null;
            }
            if (arrType.dynArrayElementType != null) {
                return arrType.dynArrayElementType;
            }
            return arrType.arrayElementType;
        }
        return null;
    }

    public void checkStatement(Token stmt, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, boolean insideLoop) {
        if (stmt.type == TokenType.KEYWORD) {
            switch (stmt.text) {
                case "if":
                    hub.controlFlow.checkIfChain(stmt, scope, func, insideLoop);
                    return;
                case "loop":
                    // A bare loop{} needs unsafe, except once in an @event_loop function. See docs/TypeChecker.md, "Statement dispatch".
                    boolean alreadyUnsafe = hub.declarations.unsafeBypass(scope, "loop");
                    boolean eventLoopExempt = !alreadyUnsafe && func != null
                            && func.eventLoopSafeLoopCount == 0
                            && hub.impls.hasDecorator(func.funcToken.decorators, "event_loop");
                    if (!alreadyUnsafe && !eventLoopExempt) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'loop' can only be used from within 'unsafe' code");
                    }
                    if (eventLoopExempt) {
                        // Spent only when the exemption was the reason the check passed.
                        func.eventLoopSafeLoopCount++;
                    }
                    hub.impls.validateDecorators(stmt.decorators, TypeCheckerModel.LOOP_DECORATORS, "a 'loop' statement");
                    collectLoopMoves(stmt.childs, scope, scope.movedSlots);
                    TypeCheckerModel.Scope loopBodyScope = checkLinesAsLoopBody(stmt.childs, scope, func);
                    // Every iteration is its own scope exit: owns locals are destructed at the natural bottom of the body.
                    stmt.destructOnExit = collectNaturalEndDestruct(stmt.childs, loopBodyScope, loopBodyScope);
                    return;
                case "for":
                    hub.impls.validateDecorators(stmt.decorators, TypeCheckerModel.LOOP_DECORATORS, "a 'for' statement");
                    if (hub.impls.getDecorator(stmt.decorators, "par") != null) {
                        hub.impls.requireNotPure(func, stmt, "a '@par' loop", "it runs iterations on other threads");
                    }
                    hub.controlFlow.checkForLoop(stmt, scope, func);
                    return;
                case "match":
                    if (stmt.isCaseMatch) {
                        hub.controlFlow.checkCaseMatch(stmt, scope, func, insideLoop);
                    } else {
                        checkMatchChain(stmt, scope, func, insideLoop);
                    }
                    return;
                case "lock":
                    if (stmt.isCaseMatch) {
                        // "match @lock EXPR{ OPEN:.. CLOSED:.. }": validated, then lowered in place into a loop (checkLockMatchStatement).
                        hub.locksAndProofs.checkLockMatchStatement(stmt, scope, func, insideLoop);
                        return;
                    }
                    throw new CompilerException("type", stmt.file, stmt.line, "internal error: a 'lock' statement that is not 'match @lock'");
                case "assume":
                    // Block form only; the no-block form is handled in checkLinesInScope.
                    if (!stmt.hasBlock) {
                        throw new IllegalStateException(
                                "no-block 'assume match' must be handled by checkLinesInScope, never reach here");
                    }
                    List<Token.MatchPattern> assumePatterns = checkAssumeMatchCondition(stmt, scope, func);
                    List<Token.MatchPattern> assumeExtended = new ArrayList<>(scope.activeMatchPatterns);
                    assumeExtended.addAll(assumePatterns);
                    TypeCheckerModel.Scope assumeBlockScope = new TypeCheckerModel.Scope(scope, assumeExtended, false);
                    checkLinesInScope(stmt.childs, assumeBlockScope, func, insideLoop);
                    stmt.destructOnExit = collectNaturalEndDestruct(stmt.childs, assumeBlockScope, assumeBlockScope);
                    return;
                case "unsafe":
                case "safe":
                    // unsafe inside safe is an error, safe inside unsafe is fine (root level: flattenRootLines).
                    if (stmt.text.equals("unsafe") && scope.currentSafety.equals("safe")) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'unsafe' cannot be nested inside 'safe'");
                    }
                    TypeCheckerModel.Scope safetyBlockScope = new TypeCheckerModel.Scope(scope, stmt.text);
                    safetyBlockScope.transparent = true;
                    if (stmt.text.equals("unsafe")) {
                        if (!stmt.synthesizedUnsafe) {
                            hub.impls.requireNotPure(func, stmt, "an 'unsafe' block", "unsafe code can do anything the compiler cannot check, so purity could not be guaranteed");
                        }
                        safetyBlockScope.unsafeBlock = recordUnsafeBlock(stmt);
                    }
                    checkLinesInScope(stmt.childs, safetyBlockScope, func, insideLoop);
                    if (stmt.text.equals("unsafe")) {
                        // Ends the proofs active before the block unless all its tags are in NON_FREEING_UNSAFE_TAGS (they cannot free by themselves).
                        // Uses inside the block are the programmer's responsibility.
                        boolean assumeOnly = stmt.unsafeTags != null && !stmt.unsafeTags.isEmpty()
                                && TypeCheckerModel.NON_FREEING_UNSAFE_TAGS.containsAll(stmt.unsafeTags);
                        if (!stmt.synthesizedUnsafe && !assumeOnly) {
                            ProofKills.kill(scope.activeMatchPatterns, stmt, "an 'unsafe' block");
                        }
                    }
                    // Natural end of a block is not a function/loop boundary.
                    stmt.destructOnExit = collectNaturalEndDestruct(stmt.childs, safetyBlockScope, safetyBlockScope);
                    return;
                case "return":
                    hub.controlFlow.checkReturn(stmt, scope, func);
                    return;
                case "throw":
                    hub.controlFlow.checkThrow(stmt, scope, func);
                    return;
                case "try":
                    // Two shapes share "try" (Token.isTryBlock): the "try { .. }" block and the bare "try foo() catch(e){..}" statement.
                    // The expression form goes through resolveExprTypeInner instead.
                    if (stmt.isTryBlock) {
                        checkTryBlock(stmt, scope, func, insideLoop);
                        return;
                    }
                    hub.controlFlow.checkTry(stmt, scope, func, insideLoop);
                    return;
                case "break":
                    if (!insideLoop) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'break' used outside of a loop");
                    }
                    stmt.destructOnExit = collectOwnsToDestruct(scope, scope.loopBoundaryScope, null);
                    stmt.unlockOnExit = collectLockReleasesToBoundary(scope, scope.loopBoundaryScope);
                    return;
                case "continue":
                    // Same as "break", targeting an enclosing try block. Order: a user for/loop (next iteration), the CLOSED case of a
                    // match @lock (retry the acquire; no lock is held), then a catch body (needs an enclosing try block).
                    // See docs/TypeChecker.md, "Statement dispatch".
                    stmt.isLoopContinue = false;
                    stmt.isLockRetryContinue = false;
                    boolean continueBelongsToCatch = hub.model.insideCatchBody
                            && scope.loopBoundaryScope == hub.model.catchEnclosingLoopBoundary;
                    if (!continueBelongsToCatch && scope.loopBoundaryScope != null
                            && hub.model.userLoopScopes.contains(scope.loopBoundaryScope)) {
                        stmt.isLoopContinue = true;
                        stmt.destructOnExit = collectOwnsToDestruct(scope, scope.loopBoundaryScope, null);
                        stmt.unlockOnExit = collectLockReleasesToBoundary(scope, scope.loopBoundaryScope);
                        return;
                    }
                    if (!hub.model.insideCatchBody && hub.model.lockClosedLoopScope != null
                            && scope.loopBoundaryScope == hub.model.lockClosedLoopScope) {
                        stmt.isLockRetryContinue = true;
                        hub.model.lockRetryContinuesSeen++;
                        stmt.destructOnExit = collectOwnsToDestruct(scope, hub.model.lockClosedLoopScope, null);
                        stmt.unlockOnExit = collectLockReleasesToBoundary(scope, hub.model.lockClosedLoopScope);
                        return;
                    }
                    if (!hub.model.insideCatchBody) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'continue' can only be used inside a 'for' or 'loop' (next iteration), inside a "
                                        + "'catch(e) { ... }' block, or directly in the 'CLOSED' case of a "
                                        + "'match @lock' (where it retries the acquire)");
                    }
                    if (scope.tryBlockBoundaryScope == null) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'continue' requires this 'catch' to be inside an enclosing 'try { ... }' "
                                        + "block -- there is nowhere well-defined for it to skip to "
                                        + "otherwise (the fallthrough it would produce is not allowed)");
                    }
                    stmt.destructOnExit = collectOwnsToDestruct(scope, scope.tryBlockBoundaryScope, null);
                    stmt.unlockOnExit = collectLockReleasesToBoundary(scope, scope.tryBlockBoundaryScope);
                    return;
                case "yield":
                    // Bare keyword, no restrictions (not even @async); emits YIELD.
                    return;
                case "ASM":
                    checkAsmDeclaration(stmt, scope);
                    return;
                default:
                    throw new CompilerException("type", stmt.file, stmt.line,
                            "'" + stmt.text + "' is not valid inside a function body");
            }
        }
        // A bare identifier statement naming a known ASM invokes it (takes priority over variable resolution).
        if (stmt.type == TokenType.VARREF) {
            TypeCheckerModel.AsmInfo info = hub.declarations.lookupAsmName(stmt.text, scope);
            if (info != null) {
                if (!hub.declarations.unsafeBypass(scope, "asm")) {
                    throw new CompilerException("type", stmt.file, stmt.line,
                            "invoking '" + stmt.text + "' requires 'unsafe' code");
                }
                stmt.resolvedAsmContent = info.rawText;
                return;
            }
        }
        // a plain expression statement, or a 'let' declaration surfaced as its '=' root
        hub.controlFlow.resolveExprType(stmt, scope, func);
    }

    /**
     * "ASM name {..}" / "ASM name \"path\"" as a statement. Unnamed: content is stashed on the token and emitted in place.
     * Named: registered in the current scope only. See docs/TypeChecker.md, "ASM declarations".
     */
    public void checkAsmDeclaration(Token stmt, TypeCheckerModel.Scope scope) {
        if (!hub.declarations.unsafeBypass(scope, "asm")) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "declaring 'ASM' requires 'unsafe' code");
        }
        String rawText = resolveAsmRawText(stmt);
        if (stmt.sub.isEmpty()) {
            stmt.resolvedAsmContent = rawText;
            return;
        }
        String name = stmt.sub.get(0).text;
        // Redeclaring in the same scope is an error; shadowing an outer scope is allowed (as for let).
        if (scope.localAsmNames.containsKey(name)) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "'ASM " + name + "' is already declared in this scope");
        }
        TypeCheckerModel.AsmInfo info = new TypeCheckerModel.AsmInfo();
        info.name = name;
        info.rawText = rawText;
        info.declTok = stmt;
        scope.declScope().localAsmNames.put(name, info);
    }

    /**
     * Block form text is already validated by Lexer.scanAsmBlock; the file form is read relative to the declaring file and
     * scanned for a stray ASM_END.
     */
    public String resolveAsmRawText(Token t) {
        if (t.asmBlockText != null) {
            return t.asmBlockText;
        }
        String rawPath = t.asmFilePath;
        Path dir = Paths.get(t.file).toAbsolutePath().getParent();
        Path resolvedPath = (dir != null ? dir : Paths.get(".")).resolve(rawPath).normalize();
        String content;
        DepsLog.record(resolvedPath);
        try {
            content = new String(Files.readAllBytes(resolvedPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CompilerException("type", t.file, t.line,
                    "could not read 'ASM' file '" + rawPath + "' (resolved to " + resolvedPath + "): "
                            + e.getMessage());
        }
        validateNoAsmEndMarker(content, resolvedPath.toString());
        return content;
    }

    /** Simplified copy of Lexer.scanAsmBlock's string/comment-aware ASM_END scan, without brace balancing (a whole file has no braces to balance). */
    public void validateNoAsmEndMarker(String content, String path) {
        int i = 0;
        int lineNo = 1;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '\n') {
                lineNo++;
                i++;
                continue;
            }
            if (c == '"' || c == '`' || c == '\'') {
                char quote = c;
                i++;
                while (i < content.length() && content.charAt(i) != quote) {
                    if (content.charAt(i) == '\\' && i + 1 < content.length()) {
                        i += 2;
                        continue;
                    }
                    if (content.charAt(i) == '\n') {
                        lineNo++;
                    }
                    i++;
                }
                i++; // consume the closing quote (or harmlessly run past EOF -- not this pass's concern)
                continue;
            }
            if (c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '/') {
                while (i < content.length() && content.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < content.length() && !(content.charAt(i) == '*' && content.charAt(i + 1) == '/')) {
                    if (content.charAt(i) == '\n') {
                        lineNo++;
                    }
                    i++;
                }
                i += 2;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < content.length() && (Character.isLetterOrDigit(content.charAt(i)) || content.charAt(i) == '_')) {
                    i++;
                }
                if (content.substring(start, i).equals("ASM_END")) {
                    throw new CompilerException("type", path, lineNo,
                            "illegal use of the reserved identifier 'ASM_END' inside an 'ASM' file");
                }
                continue;
            }
            i++;
        }
    }

    /** Registers (or finds) the record of a statement-level `unsafe` block and validates its tag words. */
    public TypeCheckerModel.UnsafeBlockRecord recordUnsafeBlock(Token stmt) {
        if (stmt.synthesizedUnsafe) {
            return null;
        }
        String key = stmt.file + ":" + stmt.line + ":" + (stmt.unsafeTags == null ? "" : String.join(",", stmt.unsafeTags));
        TypeCheckerModel.UnsafeBlockRecord rec = hub.model.unsafeBlockRecords.get(key);
        if (rec == null) {
            rec = new TypeCheckerModel.UnsafeBlockRecord(stmt);
            if (stmt.unsafeTags != null) {
                if (stmt.unsafeTags.contains("unaudited")) {
                    if (stmt.unsafeTags.size() != 1) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'unsafe unaudited' stands for every other tag; write it alone (or name the real reasons instead)");
                    }
                    if (TypeCheckerModel.isStdlibFile(stmt.file)) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'unsafe unaudited' is not allowed in the standard library: every unsafe block there must name its reasons");
                    }
                }
                for (String tag : stmt.unsafeTags) {
                    if (!TypeCheckerModel.UNSAFE_TAGS.contains(tag) && System.getenv("CASPIEN_UNSAFE_REPORT") == null) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'" + tag + "' is not an unsafe tag -- the tags are " + String.join(" ", TypeCheckerModel.UNSAFE_TAGS));
                    }
                    if (!rec.declared.add(tag)) {
                        throw new CompilerException("type", stmt.file, stmt.line, "unsafe tag '" + tag + "' is written twice");
                    }
                }
            }
            hub.model.unsafeBlockRecords.put(key, rec);
        }
        return rec;
    }

    /**
     * After the program is checked: each statement-level unsafe block must name exactly the reasons it needs. With env
     * CASPIEN_UNSAFE_REPORT set, the needed tags are appended to that file (file TAB line TAB tags) for tests/unsafe_tags_migrate.py
     * and nothing is reported. See docs/TypeChecker.md, "Unsafe block tags".
     */
    public void validateUnsafeBlockTags() {
        String reportPath = System.getenv("CASPIEN_UNSAFE_REPORT");
        if (reportPath != null && !reportPath.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (TypeCheckerModel.UnsafeBlockRecord rec : hub.model.unsafeBlockRecords.values()) {
                sb.append(rec.tok.file).append('\t').append(rec.tok.line).append('\t').append(String.join(" ", rec.used)).append('\n');
            }
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get(reportPath), sb.toString().getBytes(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot write " + reportPath, e);
            }
            return;
        }
        for (TypeCheckerModel.UnsafeBlockRecord rec : hub.model.unsafeBlockRecords.values()) {
            if (rec.declared.contains("unaudited")) {
                continue;   // vouches for everything, needed or not
            }
            Set<String> missing = new java.util.TreeSet<>(rec.used);
            missing.removeAll(rec.declared);
            Set<String> unused = new java.util.TreeSet<>(rec.declared);
            unused.removeAll(rec.used);
            if (!rec.declared.isEmpty() && missing.isEmpty() && unused.isEmpty()) {
                continue;
            }
            String need = rec.used.isEmpty() ? "nothing (remove the 'unsafe' block)" : "'unsafe " + String.join(" ", rec.used) + "{'";
            if (rec.declared.isEmpty()) {
                throw new CompilerException("type", rec.tok.file, rec.tok.line,
                        "a bare 'unsafe' block must say why it is unsafe; this one needs " + need);
            }
            throw new CompilerException("type", rec.tok.file, rec.tok.line,
                    "this 'unsafe " + String.join(" ", rec.declared) + "' block " + (missing.isEmpty() ? "" : "also needs " + missing + (unused.isEmpty() ? "" : " and "))
                            + (unused.isEmpty() ? "" : "does not need " + unused) + " -- it needs exactly " + need);
        }
    }

    /** "assume match EXPR": needs unsafe; reuses checkMatchCondition, the enum-field form is checkAssumeFieldVariant. See docs/TypeChecker.md, "Assume match". */
    public List<Token.MatchPattern> checkAssumeMatchCondition(Token stmt, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        if (!hub.declarations.unsafeBypass(scope, "assume")) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "'assume match' is only allowed inside 'unsafe' code");
        }
        List<Token.MatchPattern> patterns = new ArrayList<>();
        if (stmt.assumeFieldVariants != null) {
            checkAssumeFieldVariant(stmt, scope, func, patterns);
        } else {
            boolean savedAssumeKeyMode = hub.model.assumeKeyMode;
            hub.model.assumeKeyMode = true;
            try {
                hub.controlFlow.checkMatchCondition(stmt.sub.get(0), scope, func, patterns);
            } finally {
                hub.model.assumeKeyMode = savedAssumeKeyMode;
            }
        }
        return patterns;
    }

    /** "assume match x.field : V1|V2" and the float-state form; `stmt.sub.get(0)` is the built x.field tree, assumeFieldVariants the parsed variant names. */
    public void checkAssumeFieldVariant(Token stmt, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, List<Token.MatchPattern> out) {
        Token fieldAccess = stmt.sub.get(0);
        {
            // float-state form: records the same "float_state" proof as a real match case body, and never runs.
            if (fieldAccess.type == TokenType.OPERATOR && fieldAccess.text.equals(".")) {
                fieldAccess.isCompilerSynthesizedSwapAccess = true;
            }
            TypeCheckerModel.TypeInfo floatType = hub.controlFlow.resolveExprType(fieldAccess, scope, func);
            if (TypeCheckerModel.isFloatType(floatType.baseType)) {
                Set<String> allowed = floatType.floatStates != null ? floatType.floatStates : TypeCheckerModel.FLOAT_STATE_NAMES;
                for (String state : stmt.assumeFieldVariants) {
                    if (!hub.model.isFloatStateName(state)) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'" + state + "' isn't a valid float state -- expected 'finite', 'infinite', or 'nan'");
                    }
                    if (!allowed.contains(state)) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'" + state + "' is impossible here -- '" + floatType.canonical()
                                        + "'s own state modifier already rules it out");
                    }
                }
                String floatSlot = hub.expressions.exprKeyOf(fieldAccess);
                if (floatSlot != null) {
                    out.add(new Token.MatchPattern(floatSlot, "float_state", null));
                }
                return;
            }
        }
        if (fieldAccess.type != TokenType.OPERATOR || !fieldAccess.text.equals(".")) {
            throw new CompilerException("type", fieldAccess.file, fieldAccess.line,
                    "'assume match x.field : VARIANT' requires a '.' member access on the left");
        }
        // 'assume match r.status : LIVE' only names the field for the checker; nothing is read or written at run time,
        // so it is not an access to a 'swap' field that would need the 'swap' tag.
        fieldAccess.isCompilerSynthesizedSwapAccess = true;
        TypeCheckerModel.TypeInfo fieldType = hub.controlFlow.resolveExprType(fieldAccess, scope, func);
        TypeCheckerModel.EnumInfo enumInfo = hub.model.enums.get(fieldType.baseType);
        if (enumInfo == null) {
            throw new CompilerException("type", fieldAccess.file, fieldAccess.line,
                    "'assume match x.field : VARIANT' requires an enum-typed field, got '"
                            + fieldType.canonical() + "'");
        }
        for (String variant : stmt.assumeFieldVariants) {
            if (!enumInfo.variants.contains(variant)) {
                throw new CompilerException("type", stmt.file, stmt.line,
                        "'" + enumInfo.name + "' has no variant named '" + variant + "'");
            }
        }
        String fieldSlot = hub.expressions.slotKeyOf(fieldAccess.left);
        if (fieldSlot != null) {
            out.add(new Token.MatchPattern(fieldSlot, "enum_field_variants", fieldAccess.right.text,
                    stmt.assumeFieldVariants));
        }
    }

    /**
     * Safe code: a match/elsematch branch must add at least one proof not already active (an alive proof with kill events
     * since it was made counts as stale). Never called for a bare else or for case matches. See docs/TypeChecker.md, "Match chains".
     */
    public void requireNewTypeAssertion(List<Token.MatchPattern> newPatterns, TypeCheckerModel.Scope scope, Token at) {
        if (scope.currentSafety.equals("unsafe") || newPatterns.isEmpty()) {
            return;
        }
        for (Token.MatchPattern p : newPatterns) {
            // an alive proof that has seen a possibly destroying event since it was made may be stale: matching again is how it is renewed
            boolean alreadyActive = scope.activeMatchPatterns.stream().anyMatch(existing -> matchPatternsEqual(p, existing) && !ProofKills.hasEvents(existing));
            if (!alreadyActive) {
                return; // at least one genuinely new assertion -- not redundant
            }
        }
        throw new CompilerException("type", at.file, at.line,
                "this 'match' asserts nothing new to the type checker -- every one of its own proofs is "
                        + "already active here");
    }

    /** Field-by-field equality; MatchPattern has no equals(). */
    public boolean matchPatternsEqual(Token.MatchPattern a, Token.MatchPattern b) {
        return java.util.Objects.equals(a.slotKey, b.slotKey) && java.util.Objects.equals(a.kind, b.kind)
                && java.util.Objects.equals(a.targetName, b.targetName)
                && java.util.Objects.equals(a.rightSlotKey, b.rightSlotKey)
                && java.util.Objects.equals(a.provenVariants, b.provenVariants);
    }

    /** match / elsematch / else chain: union move-tracking like checkIfChain, per-branch proof scoping. See docs/TypeChecker.md, "Match chains". */
    public void checkMatchChain(Token matchTok, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, boolean insideLoop) {
        Set<String> preState = scope.movedSlots;
        Set<String> union = new HashSet<>();
        List<Set<String>> survivorMoves = new ArrayList<>();
        for (Token branch = matchTok; branch != null; branch = branch.right) {
            List<Token.MatchPattern> patterns = new ArrayList<>();
            boolean isBaseCaseMatch = false;
            if (!branch.sub.isEmpty()) {
                Token condition = branch.sub.get(0);
                hub.controlFlow.checkMatchCondition(condition, scope, func, patterns);
                branch.matchPatterns = patterns;
                isBaseCaseMatch = patterns.size() == 1 && patterns.get(0).kind.equals("is_base");
                requireNewTypeAssertion(patterns, scope, condition);
            }
            List<Token.MatchPattern> extended = new ArrayList<>(scope.activeMatchPatterns);
            extended.addAll(patterns);
            Set<String> branchMoved = new HashSet<>(preState);
            TypeCheckerModel.Scope branchScope = new TypeCheckerModel.Scope(scope, branchMoved, extended, isBaseCaseMatch);
            narrowInstanceofSlots(patterns, scope, branchScope);
            checkLinesInScope(branch.childs, branchScope, func, insideLoop);
            // Natural end of a branch is not a function/loop boundary.
            branch.destructOnExit = collectNaturalEndDestruct(branch.childs, branchScope, branchScope);
            union.addAll(branchMoved);
            if (!hub.declarations.closedCaseTerminates(branch.childs)) { survivorMoves.add(branchMoved); }
        }
        finishBranchMerge(preState, union, survivorMoves, !chainHasElse(matchTok));
    }

    /** Shadows a plain local variable with the concrete type proven by an "instanceof" proof for the branch (not for dotted/indexed slots). See docs/TypeChecker.md, "Match chains". */
    public void narrowInstanceofSlots(List<Token.MatchPattern> patterns, TypeCheckerModel.Scope outerScope, TypeCheckerModel.Scope branchScope) {
        for (Token.MatchPattern p : patterns) {
            if (!"instanceof".equals(p.kind) || p.slotKey == null || p.slotKey.contains(".") || p.slotKey.contains("[")) {
                continue;
            }
            TypeCheckerModel.TypeInfo currentType = outerScope.lookup(p.slotKey);
            if (currentType == null) {
                continue;
            }
            branchScope.vars.put(p.slotKey, new TypeCheckerModel.TypeInfo(currentType.storage, currentType.mutability, p.targetName));
        }
    }
}

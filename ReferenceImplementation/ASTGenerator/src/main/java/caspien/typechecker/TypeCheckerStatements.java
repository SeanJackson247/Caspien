package caspien.typechecker;
import caspien.config.CompilerException;
import caspien.config.DepsLog;
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
import caspien.token.MatchPattern;

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

    public boolean isLockedAsyncHandle(TypeInfo t) {
        if (t == null || !"owns".equals(t.storage) || t.baseType == null || !t.baseType.startsWith("AsyncHandle")) {
            return false;
        }
        StructInfo si = hub.model.structs.get(t.baseType);
        return si != null && "gate".equals(si.swapFieldName);
    }

    public void checkFunctionBody(FuncInfo func) {
        hub.driver.run(functionBodyFrame(func));
    }

    public CheckFrame functionBodyFrame(FuncInfo func) {
        return new CheckFrame(CheckFrame.FUNCTION_BODY, func.funcToken, null, func);
    }

    /** One function body: per-function state is saved here and restored at the end (phases: 0 head, 1 after the body lines, 2 after the recursion lowering). */
    public int stepFunctionBody(CheckFrame f) {
        FuncInfo func = f.func;
        if (f.phase == 0) {
            Map<String, Token> savedDecls = hub.model.fnHandleDecls;
            Set<String> savedMatched = hub.model.fnLockMatched;
            Set<String> savedMoved = hub.model.fnEverMoved;
            hub.model.fnHandleDecls = new LinkedHashMap<>();
            hub.model.fnLockMatched = new HashSet<>();
            hub.model.fnEverMoved = new HashSet<>();
            List<Integer> savedLoops = ProofKills.saveLoops();
            f.data = new Object[]{savedDecls, savedMatched, savedMoved, savedLoops};
            ProofKills.clearLoops();
            ProofKills.enterFunction(func);
            if (hub.impls.isPureRtFunc(func)) {
                for (int i = 0; i < func.paramTypes.size(); i++) {
                    hub.impls.requirePureRtNoPointer(func.paramTypes.get(i), func.funcToken, func, "as parameter '" + func.paramNames.get(i) + "'");
                }
                hub.impls.requirePureRtNoPointer(func.returnType, func.funcToken, func, "as its return type");
            }
            Scope scope = new Scope(null, func.safetyTag);
            for (int i = 0; i < func.paramNames.size(); i++) {
                TypeInfo paramType = func.paramTypes.get(i);
                if ("ref".equals(paramType.storage) && paramType.isSome) {
                    ProofKills.implicit(func.paramNames.get(i), hub.declarations.pointeeOf(paramType));   // the caller guarantees it is alive at entry
                }
                if (hub.lifetimes.isScoped(paramType)) {
                    hub.lifetimes.implicitAll(func.paramNames.get(i), paramType);   // a scoped parameter arrives with its borrows alive
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
            List<MatchPattern> assumedLocks = hub.locksAndProofs.assumedLockPatterns(func);
            Scope bodyCheckScope = assumedLocks.isEmpty() ? scope : new Scope(scope, assumedLocks, false);
            hub.model.insideConstructorBodyForStruct = func.isConstructor ? func.constructorConcreteName : null;
            f.sc1 = scope;
            f.phase = 1;
            return f.call(linesFrame(func.funcToken.childs, bodyCheckScope, func, false));
        }
        Scope scope = f.sc1;
        if (f.phase == 1) {
            Scope bodyScope = (Scope) f.gotFrame.out;
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
            func.funcToken.chk.destructOnExit = collectNaturalEndDestruct(func.funcToken.childs, bodyScope, scope);
            // Runs after every restriction above, on the original (un-lowered) shape; the lowering only changes bytecode shape.
            if (hub.impls.getDecorator(func.funcToken.decorators, "recursive") != null) {
                f.phase = 2;
                return f.call(recursiveLoweringFrame(func, scope));
            }
        }
        Object[] saved = (Object[]) f.data;
        ProofKills.exitFunction();
        ProofKills.restoreLoops(castLoops(saved[3]));
        for (Map.Entry<String, Token> e : hub.model.fnHandleDecls.entrySet()) {
            if (!hub.model.fnLockMatched.contains(e.getKey()) && !hub.model.fnEverMoved.contains(e.getKey())) {
                throw new CompilerException("type", e.getValue().file, e.getValue().line,
                        "the 'par' handle '" + e.getKey() + "' is never looked at: take its lock with 'match @lock " + e.getKey()
                                + "{ OPEN:{ ... } CLOSED:default(n) }' (read 'state' and ':resolve()' inside OPEN), or pass it on to something that does");
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Token> savedDecls = (Map<String, Token>) saved[0];
        @SuppressWarnings("unchecked")
        Set<String> savedMatched = (Set<String>) saved[1];
        @SuppressWarnings("unchecked")
        Set<String> savedMoved = (Set<String>) saved[2];
        hub.model.fnHandleDecls = savedDecls;
        hub.model.fnLockMatched = savedMatched;
        hub.model.fnEverMoved = savedMoved;
        return f.done(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> castLoops(Object o) {
        return (List<Integer>) o;
    }




    public boolean isVoidReturnType(TypeInfo t) {
        return t.baseType.equals("void");
    }

    /** Conservative: does some statement of `lines` definitely end in a return (or throw)? See docs/TypeChecker.md, "Control-flow termination checks". */
    public boolean definitelyReturns(List<Token> lines) {
        // frames: {kind 0 = any line of a list, list iterator, -} or {kind 1 = every branch of an if/match chain, next branch, {sawElse, current branch}}
        Deque<Object[]> frames = new ArrayDeque<>();
        frames.push(new Object[]{0, lines.iterator(), null});
        Boolean ret = null;
        while (!frames.isEmpty()) {
            Object[] fr = frames.peek();
            boolean any = (Integer) fr[0] == 0;
            if (ret != null) {
                boolean r = ret;
                ret = null;
                if (any) {
                    if (r) {
                        frames.pop();
                        ret = true;
                        continue;
                    }
                } else {
                    Object[] st = (Object[]) fr[2];
                    if (!r) {
                        frames.pop();
                        ret = false;
                        continue;
                    }
                    if (((Token) st[1]).sub.isEmpty()) {
                        st[0] = true;
                    }
                }
            }
            if (any) {
                @SuppressWarnings("unchecked")
                Iterator<Token> it = (Iterator<Token>) fr[1];
                if (!it.hasNext()) {
                    frames.pop();
                    ret = false;
                    continue;
                }
                Token lineTok = it.next();
                // Skip blank lines (a lowered match case body can contain one).
                if (lineTok.childs.isEmpty()) {
                    continue;
                }
                Token stmt = lineTok.childs.get(0);
                if (stmt.type != TokenType.KEYWORD) {
                    continue;
                }
                switch (stmt.text) {
                    case "return":
                    case "throw":
                    case "continue":   // legal only in a catch body; jumps past the enclosing try-block, so it terminates like return/throw
                        ret = true;
                        break;
                    case "break":
                        // Counts only while checking a `match @lock` CLOSED case (closedCaseTerminates): break = give up without the lock.
                        ret = hub.model.breakCountsAsTerminator;
                        break;
                    case "if":
                    case "match":
                        // if/match share the chain linked through `next`: counts only if it ends in a plain else and every branch returns.
                        frames.push(new Object[]{1, stmt, new Object[]{false, null}});
                        break;
                    case "loop":
                        // With no 'break' a loop can only end by returning (or never).
                        ret = !containsBreak(stmt.childs);
                        break;
                    case "unsafe":
                    case "safe":
                    case "lock":
                        frames.push(new Object[]{0, stmt.childs.iterator(), null});   // unconditional: propagates from the body
                        break;
                    case "try":
                        // Only the plain "try { }" block is unconditional; a throwing "try EXPR catch" falls through to false.
                        if (stmt.isTryBlock) {
                            frames.push(new Object[]{0, stmt.childs.iterator(), null});
                        }
                        break;
                    default:
                        break;   // anything else never proves a return
                }
            } else {
                Token branch = (Token) fr[1];
                Object[] st = (Object[]) fr[2];
                if (branch == null) {
                    frames.pop();
                    ret = (Boolean) st[0];
                    continue;
                }
                fr[1] = branch.next;
                st[1] = branch;
                frames.push(new Object[]{0, branch.childs.iterator(), null});
            }
        }
        return ret != null && ret;
    }

    /** Whether this single statement definitely ends in a return (or throw): `definitelyReturns` over a one-line list. */
    public boolean statementDefinitelyReturns(Token stmt) {
        return definitelyReturns(List.of(wrapAsCheckedLine(stmt)));
    }

    /** True if a 'break' appears in `lines` at any depth, except inside a nested 'loop' (whose break is its own). */
    public boolean containsBreak(List<Token> lines) {
        Deque<Iterator<Token>> its = new ArrayDeque<>();
        its.push(lines.iterator());
        while (!its.isEmpty()) {
            Iterator<Token> it = its.peek();
            if (!it.hasNext()) {
                its.pop();
                continue;
            }
            Token stmt = it.next().childs.get(0);
            if (stmt.type != TokenType.KEYWORD) {
                continue;
            }
            switch (stmt.text) {
                case "break":
                    return true;
                case "if":
                case "match": {
                    List<Token> branches = new ArrayList<>();
                    for (Token branch = stmt; branch != null; branch = branch.next) {
                        branches.add(branch);
                    }
                    for (int i = branches.size() - 1; i >= 0; i--) {
                        its.push(branches.get(i).childs.iterator());
                    }
                    break;
                }
                case "unsafe":
                case "safe":
                case "lock":   // not conditional: a break in its body belongs to the enclosing loop
                    its.push(stmt.childs.iterator());
                    break;
                case "try":    // only the plain try-block form is unconditional
                    if (stmt.isTryBlock) {
                        its.push(stmt.childs.iterator());
                    }
                    break;
                default:       // includes "loop": a nested loop's own 'break' targets it, not the outer loop
                    break;
            }
        }
        return false;
    }

    /**
     * True if a real throwing "try EXPR catch(e){ }" appears anywhere in `lines` (any depth, also through for/assume and nested
     * try-blocks); used by checkTryBlock. Broader than definitelyReturns/containsBreak, which only walk the relevant constructs.
     */
    public boolean containsRealTryCatch(List<Token> lines) {
        Deque<Iterator<Token>> its = new ArrayDeque<>();
        its.push(lines.iterator());
        while (!its.isEmpty()) {
            Iterator<Token> it = its.peek();
            if (!it.hasNext()) {
                its.pop();
                continue;
            }
            Token lineTok = it.next();
            if (lineTok.childs.isEmpty()) {
                continue;
            }
            Token stmt = lineTok.childs.get(0);
            // A try used as a let/assignment RHS hangs off the "=" operator (through mut/imut), as in BytecodeEmitter.tryNodeOf.
            if (stmt.type == TokenType.OPERATOR && stmt.text.equals("=") && stmt.right != null) {
                Token rhs = stmt.right;
                while (rhs.type == TokenType.OPERATOR && (rhs.text.equals("mut") || rhs.text.equals("imut"))
                        && rhs.left != null) {
                    rhs = rhs.left;
                }
                if (rhs.type == TokenType.KEYWORD && rhs.text.equals("try")) {
                    if (!rhs.isTryBlock) {
                        return true;
                    }
                    its.push(rhs.childs.iterator());
                }
                continue;
            }
            if (stmt.type != TokenType.KEYWORD) {
                continue;
            }
            switch (stmt.text) {
                case "try":
                    // A real try/catch qualifies; a nested "try { }" block does not itself, but is searched.
                    if (!stmt.isTryBlock) {
                        return true;
                    }
                    its.push(stmt.childs.iterator());
                    break;
                case "if":
                case "match": {
                    List<Token> branches = new ArrayList<>();
                    for (Token branch = stmt; branch != null; branch = branch.next) {
                        branches.add(branch);
                    }
                    for (int i = branches.size() - 1; i >= 0; i--) {
                        its.push(branches.get(i).childs.iterator());
                    }
                    break;
                }
                case "loop":
                case "for":
                case "unsafe":
                case "safe":
                case "lock":
                    its.push(stmt.childs.iterator());
                    break;
                case "assume":
                    if (stmt.hasBlock) {
                        its.push(stmt.childs.iterator());
                    }
                    break;
                case "return": {
                    // "return ?f(x)" / "return try f(x) catch(e){...}": the returned expression is the try (through any mut/imut wrapper)
                    if (stmt.sub == null || stmt.sub.size() != 1) {
                        break;
                    }
                    Token rv = stmt.operand();
                    while (rv.type == TokenType.OPERATOR && (rv.text.equals("mut") || rv.text.equals("imut")) && rv.left != null) {
                        rv = rv.left;
                    }
                    if (rv.type == TokenType.KEYWORD && rv.text.equals("try") && !rv.isTryBlock) {
                        return true;
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return false;
    }

    public void checkTryBlock(Token stmt, Scope scope, FuncInfo func, boolean insideLoop) {
        hub.driver.run(tryBlockFrame(stmt, scope, func, insideLoop));
    }

    public CheckFrame tryBlockFrame(Token stmt, Scope scope, FuncInfo func, boolean insideLoop) {
        CheckFrame f = new CheckFrame(CheckFrame.TRY_BLOCK, stmt, scope, func);
        f.q = insideLoop;
        return f;
    }

    /**
     * Plain "try { }" lexical block (Token.isTryBlock): must contain at least one real try/catch (any depth); otherwise an ordinary
     * child Scope with the safety context inherited. See docs/TypeChecker.md, "Control-flow termination checks".
     */
    public int stepTryBlock(CheckFrame f) {
        Token stmt = f.node;
        if (f.phase == 0) {
            if (!containsRealTryCatch(stmt.childs)) {
                throw new CompilerException("type", stmt.file, stmt.line,
                        "'try { ... }' requires at least one real 'try ... catch(e) { ... }' statement "
                                + "somewhere inside it");
            }
            // TryBlockBoundaryMarker makes this scope the target a nested `continue` resolves via tryBlockBoundaryScope.
            f.sc1 = new Scope(f.scope, TryBlockBoundaryMarker.MARK);
            f.phase = 1;
            return f.call(linesInScopeFrame(stmt.childs, f.sc1, f.func, f.q));
        }
        stmt.chk.destructOnExit = collectNaturalEndDestruct(stmt.childs, f.sc1, f.sc1);
        return f.done(null);
    }


    /** A `@recursive` function needs a final `imut range` parameter (any name) and a first statement (after `let static`s) of `match PARAM is base{...}`; structural checks only. See docs/TypeChecker.md, "@recursive functions". */
    public void validateRecursiveFuncRequirements(FuncInfo func) {
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
        Token condition = stmt.condition();
        return condition.type == TokenType.OPERATOR && condition.text.equals("is")
                && condition.left.type == TokenType.VARREF && condition.left.text.equals(rangeParamName)
                && condition.right.type == TokenType.VARREF && condition.right.text.equals("base");
    }

    /** A self-call to a `@recursive` function may only be the sole expression of a `return`, checked by node identity with directReturnExprNode. See docs/TypeChecker.md, "@recursive functions". */
    public void requireRecursiveCallReturnSiteOnly(FuncInfo func, Token op) {
        if (op != hub.model.directReturnExprNode) {
            throw new CompilerException("type", op.file, op.line,
                    "a recursive call to '" + func.name + "' may only appear as the sole expression of "
                            + "a 'return' statement -- e.g. 'return " + func.name + "(...)' -- never bound "
                            + "to a variable first, and never nested inside a larger expression");
        }
        op.chk.isRecursiveTailCallSite = true;
    }

    /** Every self-call must sit inside `match x within RANGEPARAM{}` with x an imut range; absolute rule, no unsafe bypass. See docs/TypeChecker.md, "@recursive functions". */
    public void requireRecursiveCallWithinProof(FuncInfo func, Token op, List<Token> argNodes,
            List<TypeInfo> argTypes, Scope scope) {
        // The last parameter is an imut range (validateRecursiveFuncRequirements).
        String rangeParamName = func.paramNames.get(func.paramNames.size() - 1);
        Token finalArg = argNodes.get(argNodes.size() - 1);
        // Reuse the argTypes from overload matching; do not resolve the node again.
        TypeInfo finalArgType = argTypes.get(argTypes.size() - 1);
        if (!"imut".equals(finalArgType.mutability)) {
            throw new CompilerException("type", finalArg.file, finalArg.line,
                    "a recursive call's own range argument must be 'imut', got '"
                            + finalArgType.canonical() + "'");
        }
        String argSlotKey = hub.expressions.slotKeyOf(finalArg);
        boolean proven = false;
        if (argSlotKey != null) {
            for (MatchPattern p : scope.activeMatchPatterns) {
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
    public void lowerRecursiveFunctionToLoop(FuncInfo func, Scope paramScope) {
        hub.driver.run(recursiveLoweringFrame(func, paramScope));
    }

    public CheckFrame recursiveLoweringFrame(FuncInfo func, Scope paramScope) {
        CheckFrame f = new CheckFrame(CheckFrame.RECURSIVE_LOWER, func.funcToken, paramScope, func);
        return f;
    }

    /** f.data = {newChilds, restLines, wrapperScope, lets (name, value pairs, checked in order), forTok, baseCaseAfterLoop}; f.i = next let, f.phase 1 = let done, 2 = loop header done. */
    public int stepRecursiveLower(CheckFrame f) {
        FuncInfo func = f.func;
        int line = func.funcToken.line;
        String file = func.funcToken.file;
        Object[] st;
        if (f.phase == 0) {
            Scope paramScope = f.scope;
        List<Token> original = func.funcToken.childs;
        int splitIdx = 0;
        while (splitIdx < original.size() && staticLetTokenOf(original.get(splitIdx).childs.get(0)) != null) {
            splitIdx++;
        }
        List<Token> leadingStatics = new ArrayList<>(original.subList(0, splitIdx));
        List<Token> restLines = new ArrayList<>(original.subList(splitIdx, original.size()));

        Scope wrapperScope = new Scope(paramScope);

        List<Token> newChilds = new ArrayList<>(leadingStatics);

        int n = func.paramNames.size();
            List<Object[]> lets = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String paramName = func.paramNames.get(i);
                Token valueExpr = new Token(TokenType.VARREF, paramName, line, file);
                lets.add(new Object[]{paramName, valueExpr});
            }

        String rangeParamName = func.paramNames.get(n - 1);
        Token startDot = new Token(TokenType.OPERATOR, ".", line, file);
        startDot.left = new Token(TokenType.VARREF, rangeParamName, line, file);
        startDot.right = new Token(TokenType.VARREF, "start", line, file);
        Token mutStart = new Token(TokenType.OPERATOR, "mut", line, file);
        mutStart.left = startDot;
        lets.add(new Object[]{"__recur_range_start", mutStart});

        Token endDot = new Token(TokenType.OPERATOR, ".", line, file);
        endDot.left = new Token(TokenType.VARREF, rangeParamName, line, file);
        endDot.right = new Token(TokenType.VARREF, "end", line, file);
        Token mutEnd = new Token(TokenType.OPERATOR, "mut", line, file);
        mutEnd.left = endDot;
        lets.add(new Object[]{"__recur_range_end", mutEnd});

        Token rangeDotDot = new Token(TokenType.OPERATOR, "..", line, file);
        rangeDotDot.left = new Token(TokenType.VARREF, "__recur_range_start", line, file);
        rangeDotDot.right = new Token(TokenType.VARREF, "__recur_range_end", line, file);
        lets.add(new Object[]{"__recur_range", rangeDotDot});

            st = new Object[]{newChilds, restLines, wrapperScope, lets, null, null};
            f.data = st;
            f.i = 0;
        } else {
            st = (Object[]) f.data;
            @SuppressWarnings("unchecked")
            List<Token> newChilds = (List<Token>) st[0];
            if (f.phase == 1) {
                newChilds.add(wrapAsCheckedLine((Token) f.gotFrame.out));
            }
        }
        @SuppressWarnings("unchecked")
        List<Token> newChilds = (List<Token>) st[0];
        @SuppressWarnings("unchecked")
        List<Token> restLines = (List<Token>) st[1];
        Scope wrapperScope = (Scope) st[2];
        @SuppressWarnings("unchecked")
        List<Object[]> lets = (List<Object[]>) st[3];
        if (f.phase < 2) {
            if (f.i < lets.size()) {
                Object[] let = lets.get(f.i++);
                f.phase = 1;
                return f.call(letAssignFrame((String) let[0], (Token) let[1], wrapperScope, func));
            }
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
            st[4] = forTok;
            st[5] = baseCaseAfterLoop;
            // Validate only the loop header against an empty body (checkForLoop reused unchanged), then swap in the already-validated body:
            // re-checking it would reject the synthetic reassignment of imut parameters.
            f.phase = 2;
            return f.call(hub.controlFlow.forLoopFrame(forTok, wrapperScope, func));
        }
        Token forTok = (Token) st[4];
        Token baseCaseAfterLoop = (Token) st[5];
        forTok.childs = restLines;
        forTok.chk.destructOnExit = new ArrayList<>();
        newChilds.add(wrapAsCheckedLine(forTok));
        newChilds.add(baseCaseAfterLoop);

        func.funcToken.childs = newChilds;
        return f.done(null);
    }


    public Token buildCheckedLetAssign(String name, Token valueExpr, Scope scope, FuncInfo func) {
        CheckFrame f = letAssignFrame(name, valueExpr, scope, func);
        hub.driver.run(f);
        return (Token) f.out;
    }

    /** Builds `let name = valueExpr` and checks it; the finished frame's `out` is the checked `=` token. */
    public CheckFrame letAssignFrame(String name, Token valueExpr, Scope scope, FuncInfo func) {
        CheckFrame f = new CheckFrame(CheckFrame.LET_ASSIGN, valueExpr, scope, func);
        f.s1 = name;
        return f;
    }

    public int stepLetAssign(CheckFrame f) {
        Token valueExpr = f.node;
        if (f.phase == 0) {
            Token nameTok = new Token(TokenType.VARREF, f.s1, valueExpr.line, valueExpr.file);
            Token letTok = new Token(TokenType.KEYWORD, "let", valueExpr.line, valueExpr.file);
            letTok.childs.add(nameTok);
            Token assign = new Token(TokenType.OPERATOR, "=", valueExpr.line, valueExpr.file);
            assign.left = letTok;
            assign.right = valueExpr;
            f.x = assign;
            f.phase = 1;
            return f.call(new CheckFrame(CheckFrame.ASSIGN, assign, f.scope, f.func));
        }
        f.x.chk.resolvedType = f.got.canonical();
        f.out = f.x;
        return f.done(f.got);
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
    public void replaceRecursiveReturnsInLines(List<Token> rootLines, FuncInfo func) {
        // frames: {line list, int[]{next index}}; nested lists are visited in document order (the replacement lines are numbered as they are built)
        Deque<Object[]> frames = new ArrayDeque<>();
        frames.push(new Object[]{rootLines, new int[]{0}});
        while (!frames.isEmpty()) {
            Object[] fr = frames.peek();
            @SuppressWarnings("unchecked")
            List<Token> lines = (List<Token>) fr[0];
            int[] next = (int[]) fr[1];
            if (next[0] >= lines.size()) {
                frames.pop();
                continue;
            }
            int i = next[0]++;
            Token lineTok = lines.get(i);
            if (lineTok.childs.isEmpty()) {
                continue;
            }
            Token stmt = lineTok.childs.get(0);
            if (stmt.type == TokenType.KEYWORD && stmt.text.equals("return") && !stmt.sub.isEmpty()
                    && stmt.operand().chk.isRecursiveTailCallSite) {
                List<Token> replacement = buildTailCallReplacementLines(stmt, func);
                lines.remove(i);
                lines.addAll(i, replacement);
                next[0] = i + replacement.size();
                continue;
            }
            if (stmt.type != TokenType.KEYWORD) {
                continue;
            }
            List<List<Token>> nested = new ArrayList<>();
            if (stmt.text.equals("match") && stmt.isCaseMatch) {
                for (Token caseTok : stmt.childs) {
                    nested.add(caseTok.childs);
                }
            } else if (stmt.text.equals("if") || stmt.text.equals("match")) {
                for (Token branch = stmt; branch != null; branch = branch.next) {
                    nested.add(branch.childs);
                }
            } else if (stmt.text.equals("for") || stmt.text.equals("loop") || stmt.text.equals("unsafe")
                    || stmt.text.equals("safe")) {
                nested.add(stmt.childs);
            } else if (stmt.text.equals("assume") && stmt.hasBlock) {
                nested.add(stmt.childs);
            }
            for (int k = nested.size() - 1; k >= 0; k--) {
                frames.push(new Object[]{nested.get(k), new int[]{0}});
            }
        }
    }

    /**
     * Replacement for "return SELFCALL(a0, a1, ..)": one checked "let __recur_tmpN = argN" per argument (all evaluated
     * before any parameter changes), then an unchecked "paramN = __recur_tmpN" per parameter.
     * See docs/TypeChecker.md, "Recursive tail-call lowering".
     */
    public List<Token> buildTailCallReplacementLines(Token returnTok, FuncInfo func) {
        Token callOp = returnTok.operand();
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
            letTok.chk.resolvedType = argNode.chk.resolvedType;
            Token assign = new Token(TokenType.OPERATOR, "=", line, file);
            assign.left = letTok;
            assign.right = argNode;
            assign.chk.resolvedType = argNode.chk.resolvedType;
            out.add(wrapAsCheckedLine(assign));
        }
        int n = func.paramNames.size();
        for (int i = 0; i < n && i < tempNames.size(); i++) {
            String paramName = func.paramNames.get(i);
            String paramType = func.paramTypes.get(i).canonical();
            Token targetRef = new Token(TokenType.VARREF, paramName, line, file);
            targetRef.chk.resolvedType = paramType;
            Token sourceRef = new Token(TokenType.VARREF, tempNames.get(i) != null ? tempNames.get(i) : argNodes.get(i).text, line, file);
            sourceRef.chk.resolvedType = paramType;
            Token assign = new Token(TokenType.OPERATOR, "=", line, file);
            assign.left = targetRef;
            assign.right = sourceRef;
            assign.chk.resolvedType = paramType;
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

    public Scope checkLines(List<Token> lines, Scope parentScope, FuncInfo func, boolean insideLoop) {
        CheckFrame f = linesFrame(lines, parentScope, func, insideLoop);
        hub.driver.run(f);
        return (Scope) f.out;
    }

    /** Checks `lines` in a new child scope of `parentScope`; the finished frame's `out` is that scope. */
    public CheckFrame linesFrame(List<Token> lines, Scope parentScope, FuncInfo func, boolean insideLoop) {
        CheckFrame f = new CheckFrame(CheckFrame.LINES, null, parentScope, func);
        f.toks = lines;
        f.q = insideLoop;
        return f;
    }

    public int stepLines(CheckFrame f) {
        if (f.phase == 0) {
            f.sc1 = new Scope(f.scope);
            f.phase = 1;
            return f.call(linesInScopeFrame(f.toks, f.sc1, f.func, f.q));
        }
        f.out = f.sc1;
        return f.done(null);
    }


    public Scope checkLinesAsLoopBody(List<Token> lines, Scope parentScope, FuncInfo func) {
        return checkLinesAsLoopBody(lines, parentScope, func, false);
    }


    public Scope checkLinesAsLoopBody(List<Token> lines, Scope parentScope, FuncInfo func, boolean isDynArrayForLoopBody) {
        CheckFrame f = loopBodyFrame(lines, parentScope, func, isDynArrayForLoopBody);
        hub.driver.run(f);
        return (Scope) f.out;
    }

    /** `isDynArrayForLoopBody` is true only for a "for i in dynarrayExpr{...}" loop's own body specifically -- see Scope.insideDynArrayForLoopBody's own comment. The finished frame's `out` is the body scope. */
    public CheckFrame loopBodyFrame(List<Token> lines, Scope parentScope, FuncInfo func, boolean isDynArrayForLoopBody) {
        CheckFrame f = new CheckFrame(CheckFrame.LOOP_BODY, null, parentScope, func);
        f.toks = lines;
        f.p = isDynArrayForLoopBody;
        return f;
    }

    public int stepLoopBody(CheckFrame f) {
        if (f.phase == 0) {
            f.sc1 = new Scope(f.scope, true, f.p);
            hub.model.userLoopScopes.add(f.sc1);
            ProofKills.enterLoop();
            f.phase = 1;
            return f.call(linesInScopeFrame(f.toks, f.sc1, f.func, true));
        }
        ProofKills.exitLoop();
        f.out = f.sc1;
        return f.done(null);
    }


    /** Lock-release calls owed from `from` up to (not past) `boundary`, in LIFO order (activeLockReleases is extended by copy, so the span is a suffix). */
    public List<Token> collectLockReleasesToBoundary(Scope from, Scope boundary) {
        List<Token> slice = new ArrayList<>(from.activeLockReleases.subList(
                boundary.activeLockReleases.size(), from.activeLockReleases.size()));
        Collections.reverse(slice);
        return slice;
    }

    public boolean chainHasElse(Token first) {
        Token last = first;
        while (last.next != null) {
            last = last.next;
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
    public List<String> collectOwnsToDestruct(Scope from, Scope boundary, String preservedSlotKey) {
        List<String> result = new ArrayList<>();
        for (Scope s = from; s != null; s = s.parent) {
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
    public List<String> collectNaturalEndDestruct(List<Token> lines, Scope from, Scope boundary) {
        if (!lines.isEmpty()) {
            Token last = lines.get(lines.size() - 1).childs.get(0);
            if (last.type == TokenType.KEYWORD && (last.text.equals("return") || last.text.equals("break"))) {
                return new ArrayList<>();
            }
        }
        return collectOwnsToDestruct(from, boundary, null);
    }

    public void checkLinesInScope(List<Token> lines, Scope scope, FuncInfo func, boolean insideLoop) {
        hub.driver.run(linesInScopeFrame(lines, scope, func, insideLoop));
    }

    public CheckFrame linesInScopeFrame(List<Token> lines, Scope scope, FuncInfo func, boolean insideLoop) {
        CheckFrame f = new CheckFrame(CheckFrame.LINES_IN_SCOPE, null, scope, func);
        f.toks = lines;
        f.q = insideLoop;
        return f;
    }

    /** phase 0 start, 1 after a statement, 2 after the proofs of a block-less `assume match` (f.i = next line; f.scope may be replaced by the assume's scope). */
    public int stepLinesInScope(CheckFrame f) {
        List<Token> lines = f.toks;
        if (f.phase == 0) {
            checkNoCodeAfterTerminalStatement(lines);
            f.data = f.scope.unsafeBlock;      // set only for the scope of a written `unsafe` block: its direct children are attributed one by one
        } else if (f.phase == 2) {
            @SuppressWarnings("unchecked")
            List<MatchPattern> assumePatterns = (List<MatchPattern>) f.gotFrame.out;
            List<MatchPattern> assumeExtended = new ArrayList<>(f.scope.activeMatchPatterns);
            assumeExtended.addAll(assumePatterns);
            f.scope = new Scope(f.scope, assumeExtended, false);
        }
        UnsafeBlockRecord blockRec = (UnsafeBlockRecord) f.data;
        while (f.i < lines.size()) {
            Token stmt = lines.get(f.i).childs.get(0);
            if (blockRec != null) {
                blockRec.noteChild(f.i, stmt);
            }
            f.i++;
            if (stmt.type == TokenType.KEYWORD && stmt.text.equals("assume") && !stmt.hasBlock) {
                // "assume match expr;" without a block holds for the rest of the enclosing block, so it swaps `scope` for a nested
                // Scope with the extra proofs here instead of going through checkStatement.
                f.phase = 2;
                return f.call(assumeCondFrame(stmt, f.scope, f.func));
            }
            f.phase = 1;
            return f.call(statementFrame(stmt, f.scope, f.func, f.q));
        }
        if (blockRec != null) {
            blockRec.cur = -1;
        }
        return f.done(null);
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
    public void collectLoopMoves(List<Token> lines, Scope scope, Set<String> out) {
        Set<String> already = new HashSet<>(out);
        for (Token lineTok : lines) {
            collectMovesInNode(lineTok.childs.get(0), scope, out);
        }
        // Seeded slots are only "maybe moved by an earlier iteration": a throw/return before the move still owns them, so exits keep destructing them (a real move nulls the slot).
        for (String key : out) {
            if (!already.contains(key)) {
                hub.model.partlyMovedSlots.add(key);
            }
        }
    }

    public void collectMovesInNode(Token root, Scope scope, Set<String> out) {
        Deque<Token> work = new java.util.LinkedList<>();   // may hold null children
        work.push(root);
        while (!work.isEmpty()) {
            Token node = work.pop();
            if (node == null) {
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.text.equals("=")) {
                Token destTok = node.left;
                if (destTok.type == TokenType.KEYWORD && destTok.text.equals("let")) {
                    TypeInfo rhsDeclared = lookupDeclaredTypeStructurally(node.right, scope);
                    if (rhsDeclared != null && "owns".equals(rhsDeclared.storage)) {
                        String key = hub.expressions.slotKeyOf(node.right);
                        if (key != null) {
                            out.add(key);
                        }
                    }
                } else {
                    TypeInfo declared = lookupDeclaredTypeStructurally(destTok, scope);
                    if (declared != null && "owns".equals(declared.storage)) {
                        String key = hub.expressions.slotKeyOf(node.right);
                        if (key != null) {
                            out.add(key);
                        }
                    }
                }
            } else if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left.type == TokenType.VARREF) {
                List<FuncInfo> overloads = hub.model.functions.get(node.left.text);
                if (overloads != null && overloads.size() == 1 && !node.right.childs.isEmpty()) {
                    List<Token> argNodes = new ArrayList<>();
                    hub.locksAndProofs.collectCommaArgs(node.right.childs.get(0), argNodes);
                    FuncInfo only = overloads.get(0);
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

    /** Structural-only type lookup (Scope/StructInfo, no expression resolution) -- used solely by collectLoopMoves's cheap pre-scan and checkAddressOf's 'ref' validation. */
    public TypeInfo lookupDeclaredTypeStructurally(Token node, Scope scope) {
        // walk down the `.member` / `[index]` spine to its root, then apply the steps back up
        Deque<Token> steps = new ArrayDeque<>();
        TypeInfo cur;
        while (true) {
            if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
                node = node.childs.get(0);
            } else if (node.type == TokenType.VARREF) {
                cur = scope.lookup(node.text);
                break;
            } else if (node.type == TokenType.OPERATOR && node.text.equals(".") && node.right.type == TokenType.VARREF) {
                steps.push(node);
                node = node.left;
            } else if (node.type == TokenType.OPERATOR && node.text.equals("LOOKUP") && node.left != null) {
                steps.push(node);   // "x[i]": the element type of a safe dynarray or fixed array, so `ref x[i].member` can see an `owns` member of an element
                node = node.left;
            } else {
                return null;
            }
        }
        while (!steps.isEmpty() && cur != null) {
            Token step = steps.pop();
            if (step.text.equals(".")) {
                StructInfo si = hub.model.structs.get(cur.baseType);
                cur = si == null ? null : si.members.get(step.right.text);
            } else {
                cur = cur.dynArrayElementType != null ? cur.dynArrayElementType : cur.arrayElementType;
            }
        }
        return cur;
    }

    public void checkStatement(Token stmt, Scope scope, FuncInfo func, boolean insideLoop) {
        hub.driver.run(statementFrame(stmt, scope, func, insideLoop));
    }

    public CheckFrame statementFrame(Token stmt, Scope scope, FuncInfo func, boolean insideLoop) {
        CheckFrame f = new CheckFrame(CheckFrame.STATEMENT, stmt, scope, func);
        f.q = insideLoop;
        return f;
    }

    /** One statement. Phase 0 dispatches; a statement with a nested block resumes in phase 1 (and 2); phase 99 = the delegate finished. */
    public int stepStatement(CheckFrame f) {
        Token stmt = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        boolean insideLoop = f.q;
        if (f.phase == 99) {
            return f.done(null);
        }
        if (f.phase != 0) {
            return stepStatementResume(f);
        }
        if (stmt.type == TokenType.KEYWORD) {
            switch (stmt.text) {
                case "if":
                    f.phase = 99;
                    return f.call(hub.controlFlow.ifChainFrame(stmt, scope, func, insideLoop));
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
                    f.phase = 1;
                    return f.call(loopBodyFrame(stmt.childs, scope, func, false));
                case "for":
                    hub.impls.validateDecorators(stmt.decorators, TypeCheckerModel.LOOP_DECORATORS, "a 'for' statement");
                    if (hub.impls.getDecorator(stmt.decorators, "par") != null) {
                        hub.impls.requireNotPure(func, stmt, "a '@par' loop", "it runs iterations on other threads");
                    }
                    f.phase = 99;
                    return f.call(hub.controlFlow.forLoopFrame(stmt, scope, func));
                case "match":
                    f.phase = 99;
                    if (stmt.isCaseMatch) {
                        return f.call(hub.controlFlow.caseMatchFrame(stmt, scope, func, insideLoop));
                    }
                    return f.call(matchChainFrame(stmt, scope, func, insideLoop));
                case "lock":
                    if (stmt.isCaseMatch) {
                        // "match @lock EXPR{ OPEN:.. CLOSED:.. }": validated, then lowered in place into a loop (checkLockMatchStatement).
                        f.phase = 99;
                        return f.call(hub.locksAndProofs.lockMatchFrame(stmt, scope, func, insideLoop));
                    }
                    throw new CompilerException("type", stmt.file, stmt.line, "internal error: a 'lock' statement that is not 'match @lock'");
                case "assume":
                    // Block form only; the no-block form is handled in checkLinesInScope.
                    if (!stmt.hasBlock) {
                        throw new IllegalStateException(
                                "no-block 'assume match' must be handled by checkLinesInScope, never reach here");
                    }
                    f.phase = 1;
                    return f.call(assumeCondFrame(stmt, scope, func));
                case "unsafe":
                case "safe":
                    // unsafe inside safe is an error, safe inside unsafe is fine (root level: flattenRootLines).
                    if (stmt.text.equals("unsafe") && scope.currentSafety.equals("safe")) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'unsafe' cannot be nested inside 'safe'");
                    }
                    Scope safetyBlockScope = new Scope(scope, stmt.text);
                    safetyBlockScope.transparent = true;
                    if (stmt.text.equals("unsafe")) {
                        if (!stmt.chk.synthesizedUnsafe) {
                            hub.impls.requireNotPure(func, stmt, "an 'unsafe' block", "unsafe code can do anything the compiler cannot check, so purity could not be guaranteed");
                        }
                        safetyBlockScope.unsafeBlock = recordUnsafeBlock(stmt);
                    }
                    f.sc1 = safetyBlockScope;
                    f.phase = 1;
                    return f.call(linesInScopeFrame(stmt.childs, safetyBlockScope, func, insideLoop));
                case "return":
                    f.phase = 99;
                    return f.call(hub.controlFlow.returnStmtFrame(stmt, scope, func));
                case "throw":
                    f.phase = 99;
                    return f.call(hub.controlFlow.throwStmtFrame(stmt, scope, func));
                case "try":
                    // Two shapes share "try" (Token.isTryBlock): the "try { .. }" block and the bare "try foo() catch(e){..}" statement.
                    // The expression form goes through resolveExprTypeInner instead.
                    if (stmt.isTryBlock) {
                        f.phase = 99;
                        return f.call(tryBlockFrame(stmt, scope, func, insideLoop));
                    }
                    f.phase = 99;
                    return f.call(hub.controlFlow.tryStmtFrame(stmt, scope, func, insideLoop));
                case "break":
                    if (!insideLoop) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'break' used outside of a loop");
                    }
                    stmt.chk.destructOnExit = collectOwnsToDestruct(scope, scope.loopBoundaryScope, null);
                    stmt.chk.unlockOnExit = collectLockReleasesToBoundary(scope, scope.loopBoundaryScope);
                    return f.done(null);
                case "continue":
                    // Same as "break", targeting an enclosing try block. Order: a user for/loop (next iteration), the CLOSED case of a
                    // match @lock (retry the acquire; no lock is held), then a catch body (needs an enclosing try block).
                    // See docs/TypeChecker.md, "Statement dispatch".
                    stmt.chk.isLoopContinue = false;
                    stmt.chk.isLockRetryContinue = false;
                    boolean continueBelongsToCatch = hub.model.insideCatchBody
                            && scope.loopBoundaryScope == hub.model.catchEnclosingLoopBoundary;
                    if (!continueBelongsToCatch && scope.loopBoundaryScope != null
                            && hub.model.userLoopScopes.contains(scope.loopBoundaryScope)) {
                        stmt.chk.isLoopContinue = true;
                        stmt.chk.destructOnExit = collectOwnsToDestruct(scope, scope.loopBoundaryScope, null);
                        stmt.chk.unlockOnExit = collectLockReleasesToBoundary(scope, scope.loopBoundaryScope);
                        return f.done(null);
                    }
                    if (!hub.model.insideCatchBody && hub.model.lockClosedLoopScope != null
                            && scope.loopBoundaryScope == hub.model.lockClosedLoopScope) {
                        stmt.chk.isLockRetryContinue = true;
                        hub.model.lockRetryContinuesSeen++;
                        stmt.chk.destructOnExit = collectOwnsToDestruct(scope, hub.model.lockClosedLoopScope, null);
                        stmt.chk.unlockOnExit = collectLockReleasesToBoundary(scope, hub.model.lockClosedLoopScope);
                        return f.done(null);
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
                    stmt.chk.destructOnExit = collectOwnsToDestruct(scope, scope.tryBlockBoundaryScope, null);
                    stmt.chk.unlockOnExit = collectLockReleasesToBoundary(scope, scope.tryBlockBoundaryScope);
                    return f.done(null);
                case "yield":
                    // Bare keyword, no restrictions (not even @async); emits YIELD.
                    return f.done(null);
                case "ASM":
                    checkAsmDeclaration(stmt, scope);
                    return f.done(null);
                default:
                    throw new CompilerException("type", stmt.file, stmt.line,
                            "'" + stmt.text + "' is not valid inside a function body");
            }
        }
        // A bare identifier statement naming a known ASM invokes it (takes priority over variable resolution).
        if (stmt.type == TokenType.VARREF) {
            AsmInfo info = hub.declarations.lookupAsmName(stmt.text, scope);
            if (info != null) {
                if (!hub.declarations.unsafeBypass(scope, "asm")) {
                    throw new CompilerException("type", stmt.file, stmt.line,
                            "invoking '" + stmt.text + "' requires 'unsafe' code");
                }
                stmt.chk.resolvedAsmContent = info.rawText;
                return f.done(null);
            }
        }
        // a plain expression statement, or a 'let' declaration surfaced as its '=' root
        f.phase = 99;
        return f.call(stmt);
    }

    /** The statements with a nested block, after the nested check finished (loop, assume block, unsafe/safe block). */
    private int stepStatementResume(CheckFrame f) {
        Token stmt = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        boolean insideLoop = f.q;
        switch (stmt.text) {
            case "loop": {
                Scope loopBodyScope = (Scope) f.gotFrame.out;
                // Every iteration is its own scope exit: owns locals are destructed at the natural bottom of the body.
                stmt.chk.destructOnExit = collectNaturalEndDestruct(stmt.childs, loopBodyScope, loopBodyScope);
                return f.done(null);
            }
            case "assume": {
                if (f.phase == 1) {
                    @SuppressWarnings("unchecked")
                    List<MatchPattern> assumePatterns = (List<MatchPattern>) f.gotFrame.out;
                    List<MatchPattern> assumeExtended = new ArrayList<>(scope.activeMatchPatterns);
                    assumeExtended.addAll(assumePatterns);
                    Scope assumeBlockScope = new Scope(scope, assumeExtended, false);
                    f.sc1 = assumeBlockScope;
                    f.phase = 2;
                    return f.call(linesInScopeFrame(stmt.childs, assumeBlockScope, func, insideLoop));
                }
                stmt.chk.destructOnExit = collectNaturalEndDestruct(stmt.childs, f.sc1, f.sc1);
                return f.done(null);
            }
            default: {   // "unsafe" / "safe"
                if (stmt.text.equals("unsafe")) {
                    // Ends the proofs active before the block unless all its tags are in NON_FREEING_UNSAFE_TAGS (they cannot free by themselves).
                    // Uses inside the block are the programmer's responsibility.
                    boolean assumeOnly = stmt.unsafeTags != null && !stmt.unsafeTags.isEmpty()
                            && TypeCheckerModel.NON_FREEING_UNSAFE_TAGS.containsAll(stmt.unsafeTags);
                    if (!stmt.chk.synthesizedUnsafe && !assumeOnly) {
                        ProofKills.kill(scope.activeMatchPatterns, stmt, "an 'unsafe' block");
                    }
                }
                // Natural end of a block is not a function/loop boundary.
                stmt.chk.destructOnExit = collectNaturalEndDestruct(stmt.childs, f.sc1, f.sc1);
                return f.done(null);
            }
        }
    }


    /**
     * "ASM name {..}" / "ASM name \"path\"" as a statement. Unnamed: content is stashed on the token and emitted in place.
     * Named: registered in the current scope only. See docs/TypeChecker.md, "ASM declarations".
     */
    public void checkAsmDeclaration(Token stmt, Scope scope) {
        if (!hub.declarations.unsafeBypass(scope, "asm")) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "declaring 'ASM' requires 'unsafe' code");
        }
        String rawText = resolveAsmRawText(stmt);
        if (stmt.sub.isEmpty()) {
            stmt.chk.resolvedAsmContent = rawText;
            return;
        }
        String name = stmt.sub.get(0).text;
        // Redeclaring in the same scope is an error; shadowing an outer scope is allowed (as for let).
        if (scope.localAsmNames.containsKey(name)) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "'ASM " + name + "' is already declared in this scope");
        }
        AsmInfo info = new AsmInfo();
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
    public UnsafeBlockRecord recordUnsafeBlock(Token stmt) {
        if (stmt.chk.synthesizedUnsafe) {
            return null;
        }
        String key = stmt.file + ":" + stmt.line + ":" + (stmt.unsafeTags == null ? "" : String.join(",", stmt.unsafeTags));
        UnsafeBlockRecord rec = hub.model.unsafeBlockRecords.get(key);
        if (rec == null) {
            rec = new UnsafeBlockRecord(stmt);
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
            for (UnsafeBlockRecord rec : hub.model.unsafeBlockRecords.values()) {
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
        for (UnsafeBlockRecord rec : hub.model.unsafeBlockRecords.values()) {
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

    public List<MatchPattern> checkAssumeMatchCondition(Token stmt, Scope scope, FuncInfo func) {
        CheckFrame f = assumeCondFrame(stmt, scope, func);
        hub.driver.run(f);
        @SuppressWarnings("unchecked")
        List<MatchPattern> patterns = (List<MatchPattern>) f.out;
        return patterns;
    }

    /** The finished frame's `out` is the list of proofs the `assume match` makes. */
    public CheckFrame assumeCondFrame(Token stmt, Scope scope, FuncInfo func) {
        return new CheckFrame(CheckFrame.ASSUME_COND, stmt, scope, func);
    }

    /** "assume match EXPR": needs unsafe; reuses checkMatchCondition, the enum-field form is checkAssumeFieldVariant. See docs/TypeChecker.md, "Assume match". */
    public int stepAssumeCond(CheckFrame f) {
        Token stmt = f.node;
        if (f.phase == 0) {
            if (!hub.declarations.unsafeBypass(f.scope, "assume")) {
                throw new CompilerException("type", stmt.file, stmt.line,
                        "'assume match' is only allowed inside 'unsafe' code");
            }
            List<MatchPattern> patterns = new ArrayList<>();
            f.out = patterns;
            if (stmt.assumeFieldVariants != null) {
                CheckFrame c = new CheckFrame(CheckFrame.ASSUME_FIELD, stmt, f.scope, f.func);
                c.data = patterns;
                f.phase = 1;
                return f.call(c);
            }
            f.p = hub.model.assumeKeyMode;
            hub.model.assumeKeyMode = true;
            f.phase = 2;
            return f.call(hub.controlFlow.matchConditionFrame(stmt.operand(), f.scope, f.func, patterns));
        }
        if (f.phase == 2) {
            hub.model.assumeKeyMode = f.p;
        }
        return f.done(null);
    }


    public void checkAssumeFieldVariant(Token stmt, Scope scope, FuncInfo func, List<MatchPattern> out) {
        CheckFrame f = new CheckFrame(CheckFrame.ASSUME_FIELD, stmt, scope, func);
        f.data = out;
        hub.driver.run(f);
    }

    /** "assume match x.field : V1|V2" and the float-state form; `stmt.sub.get(0)` is the built x.field tree, assumeFieldVariants the parsed variant names. f.data = the output proof list. */
    public int stepAssumeField(CheckFrame f) {
        Token stmt = f.node;
        Scope scope = f.scope;
        @SuppressWarnings("unchecked")
        List<MatchPattern> out = (List<MatchPattern>) f.data;
        Token fieldAccess = stmt.operand();
        if (f.phase == 0) {
            // float-state form: records the same "float_state" proof as a real match case body, and never runs.
            if (fieldAccess.type == TokenType.OPERATOR && fieldAccess.text.equals(".")) {
                fieldAccess.chk.isCompilerSynthesizedSwapAccess = true;
            }
            f.phase = 1;
            return f.call(fieldAccess);
        }
        if (f.phase == 1) {
            TypeInfo floatType = f.got;
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
                    out.add(new MatchPattern(floatSlot, "float_state", null));
                }
                return f.done(null);
            }
            if (fieldAccess.type != TokenType.OPERATOR || !fieldAccess.text.equals(".")) {
                throw new CompilerException("type", fieldAccess.file, fieldAccess.line,
                        "'assume match x.field : VARIANT' requires a '.' member access on the left");
            }
            // 'assume match r.status : LIVE' only names the field for the checker; nothing is read or written at run time,
            // so it is not an access to a 'swap' field that would need the 'swap' tag.
            fieldAccess.chk.isCompilerSynthesizedSwapAccess = true;
            f.phase = 2;
            return f.call(fieldAccess);
        }
        TypeInfo fieldType = f.got;
        EnumInfo enumInfo = hub.model.enums.get(fieldType.baseType);
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
            out.add(new MatchPattern(fieldSlot, "enum_field_variants", fieldAccess.right.text,
                    stmt.assumeFieldVariants));
        }
        return f.done(null);
    }


    /**
     * Safe code: a match/elsematch branch must add at least one proof not already active (an alive proof with kill events
     * since it was made counts as stale). Never called for a bare else or for case matches. See docs/TypeChecker.md, "Match chains".
     */
    public void requireNewTypeAssertion(List<MatchPattern> newPatterns, Scope scope, Token at) {
        if (scope.currentSafety.equals("unsafe") || newPatterns.isEmpty()) {
            return;
        }
        for (MatchPattern p : newPatterns) {
            // an alive proof that has seen a possibly destroying event since it was made may be stale: matching again is how it is renewed
            boolean alreadyActive = false;
            for (MatchPattern existing : scope.activeMatchPatterns) {
                if (matchPatternsEqual(p, existing) && !ProofKills.hasEvents(existing)) {
                    alreadyActive = true;
                    break;
                }
            }
            if (!alreadyActive) {
                return; // at least one genuinely new assertion -- not redundant
            }
        }
        throw new CompilerException("type", at.file, at.line,
                "this 'match' asserts nothing new to the type checker -- every one of its own proofs is "
                        + "already active here");
    }

    /** Field-by-field equality; MatchPattern has no equals(). */
    public boolean matchPatternsEqual(MatchPattern a, MatchPattern b) {
        return java.util.Objects.equals(a.slotKey, b.slotKey) && java.util.Objects.equals(a.kind, b.kind)
                && java.util.Objects.equals(a.targetName, b.targetName)
                && java.util.Objects.equals(a.rightSlotKey, b.rightSlotKey)
                && java.util.Objects.equals(a.provenVariants, b.provenVariants);
    }

    public void checkMatchChain(Token matchTok, Scope scope, FuncInfo func, boolean insideLoop) {
        hub.driver.run(matchChainFrame(matchTok, scope, func, insideLoop));
    }

    public CheckFrame matchChainFrame(Token matchTok, Scope scope, FuncInfo func, boolean insideLoop) {
        CheckFrame f = new CheckFrame(CheckFrame.MATCH_CHAIN, matchTok, scope, func);
        f.q = insideLoop;
        return f;
    }

    /** match / elsematch / else chain: union move-tracking like checkIfChain, per-branch proof scoping. See docs/TypeChecker.md, "Match chains". f.x = the branch being checked. */
    public int stepMatchChain(CheckFrame f) {
        Token matchTok = f.node;
        Scope scope = f.scope;
        FuncInfo func = f.func;
        Object[] st;
        if (f.phase == 0) {
            st = new Object[]{scope.movedSlots, new HashSet<String>(), new ArrayList<Set<String>>(), null, null, null};
            f.data = st;
            f.x = matchTok;
        } else if (f.phase == 1) {
            st = (Object[]) f.data;
            Token branch = f.x;
            @SuppressWarnings("unchecked")
            Set<String> union = (Set<String>) st[1];
            @SuppressWarnings("unchecked")
            List<Set<String>> survivorMoves = (List<Set<String>>) st[2];
            @SuppressWarnings("unchecked")
            Set<String> branchMoved = (Set<String>) st[3];
            Scope branchScope = (Scope) st[4];
            // Natural end of a branch is not a function/loop boundary.
            branch.chk.destructOnExit = collectNaturalEndDestruct(branch.childs, branchScope, branchScope);
            union.addAll(branchMoved);
            if (!hub.declarations.closedCaseTerminates(branch.childs)) { survivorMoves.add(branchMoved); }
            f.x = branch.next;
        } else {
            st = (Object[]) f.data;
        }
        if (f.x != null) {
            Token branch = f.x;
            @SuppressWarnings("unchecked")
            Set<String> preState = (Set<String>) st[0];
            @SuppressWarnings("unchecked")
            List<MatchPattern> patterns = (List<MatchPattern>) st[5];
            boolean isBaseCaseMatch = false;
            if (f.phase != 2) {
                patterns = new ArrayList<>();
                if (!branch.sub.isEmpty()) {
                    st[5] = patterns;
                    f.phase = 2;
                    return f.call(hub.controlFlow.matchConditionFrame(branch.condition(), scope, func, patterns));
                }
            } else if (!branch.sub.isEmpty()) {
                Token condition = branch.condition();
                branch.chk.matchPatterns = patterns;
                isBaseCaseMatch = patterns.size() == 1 && patterns.get(0).kind.equals("is_base");
                requireNewTypeAssertion(patterns, scope, condition);
            }
            List<MatchPattern> extended = new ArrayList<>(scope.activeMatchPatterns);
            extended.addAll(patterns);
            Set<String> branchMoved = new HashSet<>(preState);
            Scope branchScope = new Scope(scope, branchMoved, extended, isBaseCaseMatch);
            narrowInstanceofSlots(patterns, scope, branchScope);
            st[3] = branchMoved;
            st[4] = branchScope;
            f.phase = 1;
            return f.call(linesInScopeFrame(branch.childs, branchScope, func, f.q));
        }
        @SuppressWarnings("unchecked")
        Set<String> preState = (Set<String>) st[0];
        @SuppressWarnings("unchecked")
        Set<String> union = (Set<String>) st[1];
        @SuppressWarnings("unchecked")
        List<Set<String>> survivorMoves = (List<Set<String>>) st[2];
        finishBranchMerge(preState, union, survivorMoves, !chainHasElse(matchTok));
        return f.done(null);
    }


    /** Shadows a plain local variable with the concrete type proven by an "instanceof" proof for the branch (not for dotted/indexed slots). See docs/TypeChecker.md, "Match chains". */
    public void narrowInstanceofSlots(List<MatchPattern> patterns, Scope outerScope, Scope branchScope) {
        for (MatchPattern p : patterns) {
            if (!"instanceof".equals(p.kind) || p.slotKey == null || p.slotKey.contains(".") || p.slotKey.contains("[")) {
                continue;
            }
            TypeInfo currentType = outerScope.lookup(p.slotKey);
            if (currentType == null) {
                continue;
            }
            branchScope.vars.put(p.slotKey, new TypeInfo(currentType.storage, currentType.mutability, p.targetName));
        }
    }
}

package caspien.typechecker;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;

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

/** TypeCheckerExpressions: expression types, ownership moves, assignment, constants, arithmetic (part 7 of 11; see README.md in this folder). */
public final class TypeCheckerExpressions {
    private final TypeChecker hub;

    TypeCheckerExpressions(TypeChecker hub) {
        this.hub = hub;
    }

    public TypeCheckerModel.TypeInfo resolveExprTypeInner(Token node, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        switch (node.type) {
            case STRING:
                // String literals are 'static imut string' and 'some' (always present); 'some' coerces one-way to plain.
                return new TypeCheckerModel.TypeInfo("static", "imut", "string").withSome(true);
            case CHAR:
                return new TypeCheckerModel.TypeInfo(null, "indeterminate", "char");
            case INTEGER:
                // Built-in limit constants (u64Max, ...) carry a forced concrete type; other integer literals are indeterminate u64.
                if (node.forcedLiteralType != null) {
                    return new TypeCheckerModel.TypeInfo(null, "imut", node.forcedLiteralType);
                }
                {
                    TypeCheckerModel.TypeInfo literalType = new TypeCheckerModel.TypeInfo(null, "indeterminate", "u64");
                    try {
                        literalType.literalValue = new java.math.BigInteger(node.text);
                    } catch (NumberFormatException notADecimalLiteral) {
                        literalType.literalValue = null;
                    }
                    if (literalType.literalValue != null && literalType.literalValue.bitLength() > 64) {
                        // (decimal, hexadecimal and binary spellings alike: the lexer hands over decimal text) -- no integer type is wider than 64 bits
                        throw new CompilerException("type", node.file, node.line,
                                "integer literal " + node.text + " does not fit in any integer type (the largest, u64, ends at 18446744073709551615)");
                    }
                    return literalType;
                }
            case FLOAT:
                if (node.forcedLiteralType != null) {
                    return new TypeCheckerModel.TypeInfo(null, "imut", node.forcedLiteralType);
                }
                {
                    TypeCheckerModel.TypeInfo floatLit = new TypeCheckerModel.TypeInfo(null, "indeterminate", "f32");
                    floatLit.floatLiteral = true;
                    return floatLit;
                }
            case BOOL:
                return new TypeCheckerModel.TypeInfo(null, "indeterminate", "bool");
            case NULL:
                return new TypeCheckerModel.TypeInfo(null, null, "null");
            case TEMPLATE_STRING:
                return resolveTemplateString(node);
            case VARREF: {
                if (hub.declarations.lookupAsmName(node.text, scope) != null) {
                    throw new CompilerException("type", node.file, node.line,
                            "'" + node.text + "' is an 'ASM' name -- it can only be used as a bare, "
                                    + "whole statement ('" + node.text + ";'), never as part of an "
                                    + "expression, argument list, struct literal, or anything else");
                }
                if (scope.movedSlots.contains(node.text)) {
                    throw new CompilerException("type", node.file, node.line,
                            "use of '" + node.text + "' after its ownership was moved");
                }
                TypeCheckerModel.TypeInfo t = scope.lookup(node.text);
                if (t != null && hub.model.activeCatchTries.containsKey(node.text) && node != hub.model.rethrowOperand) {
                    // stack_trace.md, 3.6: the catch parameter is the caught error (message + trace), not the message string
                    throw new CompilerException("type", node.file, node.line,
                            "'" + node.text + "' is the caught error -- use '" + node.text + ".msg' for the message, '" + node.text
                                    + ".stack_trace' for the trace, or 'throw " + node.text + "' to re-throw it");
                }
                if (t != null) {
                    if ("ref".equals(t.storage) && t.isSome && node != hub.model.assignTargetVar) {
                        ProofKills.refSomeRead(node, scope.activeMatchPatterns);
                    }
                    if (scope.isStatic(node.text)) {
                        hub.impls.requireSharedStateOrUnsafe(t, node.text, scope, node);
                        hub.impls.requirePureDoesNotReadMutableStatic(t, node.text, func, node);
                    }
                    if (t.literalValue != null || t.literalElements != null || t.floatLiteral || t.floatArrayLiteral) {
                        // A variable is never a literal: `let x = mut 5` binds a plain u64 slot whose value can
                        // change, so reads of it must not adapt to narrower slots the way the literal 5 does.
                        t = t.withSome(t.isSome);
                        t.floatLiteral = false;
                        t.floatArrayLiteral = false;
                        t.literalValue = null;
                        t.literalElements = null;
                    }
                    return t;
                }
                if (hub.model.globals.containsKey(node.text)) {
                    // Globals already exist in `globals` while bodies are checked, so use-before-declaration needs an explicit declOrder comparison.
                    Integer declaredAt = hub.model.globalDeclOrder.get(node.text);
                    if (func != null && declaredAt != null && declaredAt >= func.declOrder) {
                        throw new CompilerException("type", node.file, node.line,
                                "'" + node.text + "' is used before its declaration");
                    }
                    if (!hub.model.publicGlobals.contains(node.text)) {
                        // Private globals are visible only in their declaring file (let/static are exempt).
                        String declFile = hub.model.globalRhsByName.get(node.text).file;
                        if (!declFile.equals(node.file)) {
                            throw new CompilerException("type", node.file, node.line,
                                    "'" + node.text + "' is private -- not accessible outside the file "
                                            + "it's declared in");
                        }
                    }
                    TypeCheckerModel.TypeInfo globalType = hub.model.globals.get(node.text);
                    hub.impls.requireSharedStateOrUnsafe(globalType, node.text, scope, node);
                    hub.impls.requirePureDoesNotReadMutableStatic(globalType, node.text, func, node);
                    return globalType;
                }
                if (hub.model.constExprs.containsKey(node.text)) {
                    // Consts are substituted: the VARREF token is mutated in place into the literal, so the emitter needs no const logic.
                    Token literal = resolveConstValue(node.text, node);
                    node.type = literal.type;
                    node.text = literal.text;
                    node.literalValue = literal.literalValue;
                    node.forcedLiteralType = literal.forcedLiteralType;
                    return resolveExprTypeInner(node, scope, func);
                }
                // A bare function name used as a value is a function pointer with its own signature and decorators.
                List<TypeCheckerModel.FuncInfo> asFunc = hub.model.functions.get(node.text);
                if (asFunc != null) {
                    if (asFunc.size() > 1) {
                        throw new CompilerException("type", node.file, node.line,
                                "'" + node.text + "' has multiple overloads -- a bare function reference "
                                        + "must resolve to exactly one signature");
                    }
                    TypeCheckerModel.FuncInfo target = asFunc.get(0);
                    if (hub.impls.hasDecorator(target.funcToken.decorators, "event_loop")) {
                        // '@event_loop' cannot be referenced anywhere, same as being called.
                        throw new CompilerException("type", node.file, node.line,
                                "'" + node.text + "' is '@event_loop' -- it cannot be called or "
                                        + "referenced anywhere in the compilation unit");
                    }
                    Set<String> decorators = new HashSet<>();
                    if (target.funcToken.decorators != null) {
                        for (Token.Decorator d : target.funcToken.decorators) {
                            decorators.add(d.name);
                        }
                    }
                    return TypeCheckerModel.TypeInfo.funcPointer("imut", target.paramTypes, target.returnType, decorators,
                            target.callConvention);
                }
                throw new CompilerException("type", node.file, node.line,
                        "use of undeclared variable '" + node.text + "'");
            }
            case KEYWORD:
                if (node.text.equals("let")) {
                    throw new CompilerException("type", node.file, node.line,
                            "internal error: bare 'let' token in expression position");
                }
                if (node.text.equals("try")) {
                    // Expression-position `try`; insideLoop is passed as false (see checkTry).
                    return hub.controlFlow.checkTry(node, scope, func, false);
                }
                break;
            case DELINEATOR:
                if (node.text.equals("(")) {
                    if (node.childs.isEmpty()) {
                        throw new CompilerException("type", node.file, node.line, "empty '()' expression");
                    }
                    return hub.controlFlow.resolveExprType(node.childs.get(0), scope, func);
                }
                if (node.text.equals("[")) {
                    return hub.locksAndProofs.checkArrayLiteral(node, scope, func);
                }
                break;
            case OPERATOR:
                return resolveOperatorType(node, scope, func);
            default:
                break;
        }
        throw new CompilerException("type", node.file, node.line,
                "internal error: don't know how to type '" + node.type + "(" + node.text + ")'");
    }

    public TypeCheckerModel.TypeInfo resolveOperatorType(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        // Captured once and reset before dispatch, so a stale 'true' from an enclosing context cannot reach other operators;
        // only checkLogical sees it. See docs/TypeChecker.md, "Operator dispatch context flags".
        boolean eligibleHere = hub.model.insideShortCircuitEligiblePosition;
        hub.model.insideShortCircuitEligiblePosition = false;
        // Same capture-and-reset for the struct-RVO destination flag; only checkCall consults it.
        boolean eligibleForRvoHere = hub.model.insideStructRvoDestinationPosition;
        hub.model.insideStructRvoDestinationPosition = false;
        switch (op.text) {
            case "=":
                return checkAssign(op, scope, func);
            case "+=": case "-=": case "*=": case "/=": case "%=":
                return checkCompoundAssign(op, scope, func);
            case "+": case "-": case "*": case "/": case "%":
                if (op.unary) {
                    return checkUnaryMinus(op, scope, func);
                }
                return checkArithmetic(op, scope, func);
            case "!":
                return checkLogicalNot(op, scope, func);
            case "++": case "--":
                return checkIncrement(op, scope, func);
            case "&&": case "||": case "&&then": case "||then":
                return checkLogical(op, scope, func, eligibleHere);
            case "<": case ">": case "<=": case ">=": case "==": case "!=":
                return checkComparison(op, scope, func);
            case "CALL":
                return hub.calls.checkCall(op, scope, func, eligibleForRvoHere);
            case "LOOKUP":
                return hub.locksAndProofs.checkLookup(op, scope, func);
            case "as":
                return hub.locksAndProofs.checkAs(op, scope, func);
            case ".":
                return hub.accessAndAsync.checkDot(op, scope, func);
            case "INSTANTIATE":
                return hub.accessAndAsync.checkInstantiate(op, scope, func);
            case "owns": case "ref": case "auto": case "raw": case "static":
                return hub.checkAddressOf(op, scope, func);
            case "mut": case "imut":
                // mut/imut are transparent for the RVO flag: re-armed one level down so the wrapped CALL still sees it.
                hub.model.insideStructRvoDestinationPosition = eligibleForRvoHere;
                return hub.checkMutabilityOf(op, scope, func);
            case "new":
                return hub.accessAndAsync.checkNew(op, scope, func);
            case "await":
                return hub.accessAndAsync.checkAwait(op, scope, func);
            case "par":
                return hub.accessAndAsync.checkPar(op, scope, func);
            case "unsafe":
                return hub.calls.checkUnsafeDynWrapper(op, scope, func);
            case "atomic":
                return hub.accessAndAsync.checkAtomicWrapper(op, scope, func);
            case "swap":
                return hub.accessAndAsync.checkSwapOperator(op, scope, func);
            case "..":
                return hub.checkRange(op, scope, func);
            case "in":
                return hub.checkIn(op, scope, func);
            case "within":
                return hub.checkWithin(op, scope, func);
            case "instanceof":
                return hub.locksAndProofs.checkInstanceof(op, scope, func);
            case "implements":
                return hub.locksAndProofs.checkImplementsOperator(op, scope, func);
            case "dup":
                // 'dup' is expanded earlier (DupExpander); reaching here means it was used outside an array literal.
                throw new CompilerException("type", op.file, op.line,
                        "'dup' can only be used as an element of an array literal");
            case ",":
                hub.controlFlow.resolveExprType(op.left, scope, func);
                return hub.controlFlow.resolveExprType(op.right, scope, func);
            case "and":
                // 'and' is only valid inside a match condition (checkMatchCondition walks the chain itself).
                throw new CompilerException("type", op.file, op.line,
                        "'and' can only be used to chain conditions inside a 'match' statement");
            case "into":
                // 'into' is legal only in match, for match and @lock(...), which call checkIn directly; any other use lands here.
                throw new CompilerException("type", op.file, op.line,
                        "'into' has no meaning as an ordinary expression -- it can only be used as a "
                                + "match condition ('match i into arr{...}'), a 'for match' loop header "
                                + "('for match i into arr{...}'), or inside an '@lock(...)' qualifier");
            case "fits":
                throw new CompilerException("type", op.file, op.line,
                        "'fits' has no meaning as an ordinary expression -- it can only be used as a match "
                                + "condition ('match x fits u8{...}')");
            default:
                throw new CompilerException("type", op.file, op.line,
                        "internal error: unhandled operator '" + op.text + "' in type checker");
        }
    }

    /** The tracked slot key of a bare name, an `a.b` member path or `x[i]` (parens transparent); null for untrackable shapes, which are never moved. */
    public String slotKeyOf(Token node) {
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
            return slotKeyOf(node.childs.get(0));
        }
        if (node.type == TokenType.VARREF) {
            return node.text;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && node.right.type == TokenType.VARREF) {
            String leftKey = slotKeyOf(node.left);
            return leftKey == null ? null : leftKey + "." + node.right.text;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("LOOKUP") && node.right != null
                && !node.right.childs.isEmpty()) {
            // `x[i]` as a slot, so a proof granted for a specific element can be matched by a later access.
            // Both target and index must themselves be slots.
            String leftKey = slotKeyOf(node.left);
            String idxKey = slotKeyOf(node.right.childs.get(0));
            if (leftKey == null || idxKey == null) {
                return null;
            }
            return leftKey + "[" + idxKey + "]";
        }
        return null;
    }

    /**
         * Invalidates every active match proof whose slotKey or rightSlotKey is `target`'s slot (or an assumed expression
         * key mentioning it); called from every write. See docs/TypeChecker.md, "Match-proof invalidation".
         */
    public void invalidateMatchPatternsForWrite(Token target, TypeCheckerModel.Scope scope) {
        String key = slotKeyOf(target);
        if (key == null) {
            return;
        }
        java.util.regex.Pattern word = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_.])" + java.util.regex.Pattern.quote(key) + "(?![A-Za-z0-9_])");
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            if (key.equals(p.slotKey) || key.equals(p.rightSlotKey)) {
                p.invalidated = true;
            } else if (p.slotKey != null && p.slotKey.startsWith("(") && word.matcher(p.slotKey).find()) {
                p.invalidated = true; // an assumed fact about an expression ("p+i") dies when any variable in it is written
            }
        }
    }

    /**
     * A canonical key for an arithmetic expression over variables and integer literals ("(p+i)"), used only to record
     * and look up facts vouched for with 'assume match'; null when the expression has any other shape. Always starts with
     * "(" so it can never collide with a variable/field slot key.
     */
    public String exprKeyOf(Token node) {
        if (node == null) {
            return null;
        }
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && node.childs != null && node.childs.size() == 1) {
            return exprKeyOf(node.childs.get(0));
        }
        if (node.type == TokenType.INTEGER || node.type == TokenType.FLOAT) {
            return node.text;
        }
        String slot = slotKeyOf(node);
        if (slot != null) {
            return slot;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("-") && node.right == null && node.left != null) {
            String uk = exprKeyOf(node.left);
            return uk == null ? null : "(-" + uk + ")";
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && node.left != null && node.right != null
                && node.right.type == TokenType.VARREF) {
            String dk = exprKeyOf(node.left);
            return dk == null ? null : dk + "." + node.right.text;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("LOOKUP") && node.left != null && node.right != null
                && !node.right.childs.isEmpty()) {
            String lk = exprKeyOf(node.left);
            String ik = exprKeyOf(node.right.childs.get(0));
            return lk == null || ik == null ? null : lk + "[" + ik + "]";
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left != null
                && node.left.type == TokenType.VARREF && node.right != null) {
            // a plain call `f(a, b)` keyed by name and argument keys (for 'assume match f(x) : finite')
            List<Token> callArgs = new ArrayList<>();
            if (!node.right.childs.isEmpty()) {
                hub.locksAndProofs.collectCommaArgs(node.right.childs.get(0), callArgs);
            }
            StringBuilder sb = new StringBuilder(node.left.text).append("(");
            for (int ai = 0; ai < callArgs.size(); ai++) {
                String ak = exprKeyOf(callArgs.get(ai));
                if (ak == null) {
                    return null;
                }
                sb.append(ai > 0 ? "," : "").append(ak);
            }
            return sb.append(")").toString();
        }
        if (node.type == TokenType.OPERATOR && node.left != null && node.right != null
                && java.util.Arrays.asList("+", "-", "*", "/", "%").contains(node.text)) {
            String l = exprKeyOf(node.left);
            String r = exprKeyOf(node.right);
            return l == null || r == null ? null : "(" + l + node.text + r + ")";
        }
        return null;
    }

    /** The key a proof is recorded under: the slot key, or (in an 'assume match' condition only) the expression key. */
    public String proofKeyOf(Token node) {
        String k = slotKeyOf(node);
        return (k == null && hub.model.assumeKeyMode) ? exprKeyOf(node) : k;
    }

    /** The key a proof is looked up under: the slot key, else the expression key. */
    public String lookupKeyOf(Token node) {
        String k = slotKeyOf(node);
        return k != null ? k : exprKeyOf(node);
    }

    /** Source-like text of an expression, for hints in error messages. */
    public String exprHint(Token node) {
        String k = exprKeyOf(node);
        if (k == null) {
            return node.text;
        }
        return k.startsWith("(") && k.endsWith(")") ? k.substring(1, k.length() - 1) : k;
    }

    /**
     * Operands of one call, array literal or `dyn([..])` are all checked before any of them is marked moved, so `f(a, a)` or
     * `dyn([a, a])` passes the read check. A slot that is already moved when its own move is being recorded was moved a moment ago
     * by a sibling operand (a later read of an earlier-moved slot is rejected when it is read): two owners of one block.
     */
    public void requireNotMovedAlready(String key, Token valueExpr, TypeCheckerModel.Scope scope) {
        if (scope.movedSlots.contains(key)) {
            throw new CompilerException("type", valueExpr.file, valueExpr.line,
                    "'" + key + "' is moved more than once in the same expression -- two owners of one block");
        }
    }

    /** Marks the source slot of a value bound to an `owns` type as moved (let, assignment, call argument, struct field). See docs/TypeChecker.md, "Ownership moves". */
    public void markMovedIfOwned(TypeCheckerModel.TypeInfo declaredType, Token valueExpr, TypeCheckerModel.Scope scope) {
        markMovedIfOwnedInner(declaredType, valueExpr, scope);
        if (hub.model.newDynDepth > 0 && valueExpr.chk.isOwnershipMoveSource) {
            ProofKills.kill(scope.activeMatchPatterns, valueExpr, "moving an owner into 'new' or 'dyn' (it is freed if the allocation fails)");
        }
    }

    public void markMovedIfOwnedInner(TypeCheckerModel.TypeInfo declaredType, Token valueExpr, TypeCheckerModel.Scope scope) {
        if (hub.declarations.isInlineOwningStruct(declaredType)) {
            // Copying a struct that owns memory would leave two owners: the source slot gives its owned members up
            // (nulled at run time, see BytecodeEmitter.emitOwnershipMoveNullOut) and can not be read afterwards.
            Token inner = valueExpr;
            while (true) {
                if (inner.type == TokenType.DELINEATOR && inner.text.equals("(") && !inner.childs.isEmpty()) {
                    inner = inner.childs.get(0);
                } else if (inner.type == TokenType.OPERATOR && inner.unary && (inner.text.equals("mut") || inner.text.equals("imut"))
                        && inner.left != null) {
                    inner = inner.left; // `let h2 = mut h`: the value is under the mutability wrapper
                } else {
                    break;
                }
            }
            if (inner.type == TokenType.OPERATOR && "CALL".equals(inner.text) && inner.left != null
                    && inner.left.type == TokenType.VARREF && "deref".equals(inner.left.text)) {
                throw new CompilerException("type", valueExpr.file, valueExpr.line,
                        "'deref(...)' would copy a '" + declaredType.baseType + "', which owns memory -- two owners of one block. "
                                + "Use 'clone(...)', or move the owned members out one by one");
            }
            Token chain = inner;
            while (chain != null && chain.type == TokenType.OPERATOR && ".".equals(chain.text)) {
                chain = chain.left; // `d[i].h`: still a read out of an indexed element
            }
            if (chain != null && chain.type == TokenType.OPERATOR && "LOOKUP".equals(chain.text)) {
                throw new CompilerException("type", valueExpr.file, valueExpr.line,
                        "reading '" + declaredType.baseType + "' out of an array element would copy a value that owns memory -- two owners "
                                + "of one block. Use 'clone(...)' on the element, or move the owned members out one by one");
            }
            String key = slotKeyOf(inner);
            if (key != null) {
                requireNotMovedAlready(key, valueExpr, scope);
                scope.movedSlots.add(key);
                valueExpr.chk.isOwnershipMoveSource = true;
                valueExpr.chk.inlineOwnsStruct = declaredType.baseType;
            }
            return;
        }
        if ("owns".equals(declaredType.storage)) {
            // `a = mut b`, `take(mut b)`, `let c = mut b`: the mutability wrapper is transparent -- b is what moves.
            Token source = valueExpr;
            while (true) {
                if (source.type == TokenType.DELINEATOR && source.text.equals("(") && !source.childs.isEmpty()) {
                    source = source.childs.get(0);
                } else if (source.type == TokenType.OPERATOR && source.unary
                        && (source.text.equals("mut") || source.text.equals("imut")) && source.left != null) {
                    source = source.left;
                } else {
                    break;
                }
            }
            String key = slotKeyOf(source);
            if (key != null) {
                requireNotMovedAlready(key, valueExpr, scope);
                scope.movedSlots.add(key);
                hub.model.fnEverMoved.add(key);
                valueExpr.chk.isOwnershipMoveSource = true;
            }
        }
    }

    /** 'deref(p)' yields a copy, never a place: rejected as the target of =, compound assignment, ++/-- and swap. */
    public void rejectDerefAsWriteTarget(Token target, String what) {
        Token t = target;
        if (t != null && "CALL".equals(t.text) && t.left != null && t.left.type == TokenType.VARREF
                && "deref".equals(t.left.text)) {
            throw new CompilerException("type", t.file, t.line,
                    "'deref(...)' cannot be the target of " + what + " -- it yields a copy of the value, not "
                            + "a place to write; write through a pointer with '.' member assignment on a "
                            + "proven-alive struct pointer, or with 'memcopy' in 'unsafe' code");
        }
    }

    public TypeCheckerModel.TypeInfo checkAssign(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        rejectDerefAsWriteTarget(op.left, "an assignment");
        if (op.left.type == TokenType.KEYWORD && op.left.text.equals("let")) {
            Token nameTok = op.left.childs.get(0);
            if (op.left.isStatic) {
                if (!op.left.staticApproved) {
                    throw new CompilerException("type", op.left.file, op.left.line,
                            "'let static' is only allowed at the top of a function body, before any "
                                    + "other statement");
                }
                checkNoRuntimeConstructs(op.right);
                // Compile-time constant: resolved in an empty Scope, so any variable reference fails as undeclared.
                TypeCheckerModel.TypeInfo rhsType = hub.controlFlow.resolveExprType(op.right, new TypeCheckerModel.Scope(null), func);
                TypeCheckerModel.TypeInfo bound = resolveAndValidateTypeBound(op.left, rhsType, op.right);
                if (bound != null) {
                    rhsType = bound;
                } else {
                    requireStaticMutable(rhsType, op.right);
                }
                // A direct atomic declaration is legal for 'let static'; an atomic merely read from elsewhere is stripped.
                if (rhsType.isAtomic && !hub.impls.isDirectAtomicDeclaration(op.right)) {
                    rhsType = rhsType.withAtomic(false);
                }
                scope.declare(nameTok.text, rhsType, nameTok);
                scope.declScope().staticNames.add(nameTok.text);
                if (op.left.isConst) {
                    scope.declScope().constNames.add(nameTok.text);
                }
                scope.movedSlots.remove(nameTok.text);
            hub.model.partlyMovedSlots.remove(nameTok.text);
                op.left.chk.resolvedType = rhsType.canonical();
                return rhsType;
            }
            // A plain 'let' RHS is always an eligible struct-RVO destination; checkCall decides whether it is such a call.
            boolean previousRvoEligible = hub.model.insideStructRvoDestinationPosition;
            hub.model.insideStructRvoDestinationPosition = true;
            TypeCheckerModel.TypeInfo rhsType;
            try {
                rhsType = hub.controlFlow.resolveExprType(op.right, scope, func);
            } finally {
                hub.model.insideStructRvoDestinationPosition = previousRvoEligible;
            }
            TypeCheckerModel.TypeInfo bound = resolveAndValidateTypeBound(op.left, rhsType, op.right);
            if (bound != null) {
                rhsType = bound;
            } else {
                requireConcreteMutability(rhsType, op.right);
            }
            // A direct atomic declaration is legal only for a global or 'let static'; an expression that merely resolves to an
            // atomic value is stripped to non-atomic. See docs/TypeChecker.md, "Atomic write rules".
            if (rhsType.isAtomic) {
                if (hub.impls.isDirectAtomicDeclaration(op.right)) {
                    throw new CompilerException("type", op.left.file, op.left.line,
                            "an 'atomic' value can only ever be the type of a global or 'let static' "
                                    + "local, never a plain 'let'");
                }
                rhsType = rhsType.withAtomic(false);
            }
            scope.declare(nameTok.text, rhsType, nameTok);
            if ("ref".equals(rhsType.storage) && rhsType.isSome) {
                ProofKills.implicit(nameTok.text, hub.declarations.pointeeOf(rhsType));
            }
            if (op.left.isConst) {
                scope.declScope().constNames.add(nameTok.text);
            }
            // Recorded in declaration order so this scope drops the owning local at every exit (natural end, return, break).
            if ("owns".equals(rhsType.storage) || hub.declarations.isInlineOwningStruct(rhsType)) {
                scope.declScope().ownsDeclaredHere.add(nameTok.text);
                func.ownsLocalTypes.put(nameTok.text, rhsType);
                if (hub.statements.isLockedAsyncHandle(rhsType) && !hub.model.synthesizingGlueBody) {
                    hub.model.fnHandleDecls.putIfAbsent(nameTok.text, nameTok);
                }
            }
            // A fresh 'let' is a new binding: clear any stale moved-flag from an earlier use of the same name (e.g. a loop body redeclaring it).
            scope.movedSlots.remove(nameTok.text);
            hub.model.partlyMovedSlots.remove(nameTok.text);
            op.left.chk.resolvedType = rhsType.canonical();
            hub.signatures.requireNoAutoContainer(rhsType, nameTok, "'" + nameTok.text + "'");
            markMovedIfOwned(rhsType, op.right, scope);
            return rhsType;
        }
        // A fresh assignment revives a moved slot; cleared before the left side is resolved so the move check does not reject it.
        String leftSlotKeyForRevival = slotKeyOf(op.left);
        if (leftSlotKeyForRevival != null) {
            scope.movedSlots.remove(leftSlotKeyForRevival);
            hub.model.partlyMovedSlots.remove(leftSlotKeyForRevival);
        }
        hub.model.assignTargetVar = op.left;
        TypeCheckerModel.TypeInfo leftType;
        try {
            leftType = hub.controlFlow.resolveExprType(op.left, scope, func);
        } finally {
            hub.model.assignTargetVar = null;
        }
        // A bare EnumName.Variant is always 'imut' and would not coerce to a 'mut' target; allowed as in checkInstantiate and
        // checkSwapOperator (needed by the synthesized lock-release write).
        boolean isBareEnumVariantRight = hub.model.enums.containsKey(leftType.baseType)
                && hub.accessAndAsync.isBareEnumVariantRefNode(op.right, leftType.baseType);
        // '=' on an atomic target is allowed only for a bare variable, literal or enum variant (isSwapRhsAllowedShape); compound
        // assignment and ++/-- are rejected outright. See docs/TypeChecker.md, "Atomic write rules".
        if (leftType.isAtomic && !isBareEnumVariantRight && !hub.accessAndAsync.isSwapRhsAllowedShape(op.right)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "an 'atomic' target can only be assigned a bare variable, a literal, or an "
                            + "'EnumName.Variant' reference with '=' -- not a computed expression. Use "
                            + "'swap' instead if the new value needs to be computed");
        }
        // A struct-RVO call is eligible here only when the assignment target is a bare variable.
        boolean previousRvoEligible = hub.model.insideStructRvoDestinationPosition;
        hub.model.insideStructRvoDestinationPosition = op.left.type == TokenType.VARREF;
        TypeCheckerModel.TypeInfo rightType;
        try {
            rightType = hub.controlFlow.resolveExprType(op.right, scope, func);
        } finally {
            hub.model.insideStructRvoDestinationPosition = previousRvoEligible;
        }
        if (!isBareEnumVariantRight && !hub.acceptsAdaptingLiteral(leftType, rightType, op.right)) {
            throw new CompilerException("type", op.file, op.line,
                    "cannot assign '" + rightType.canonical() + "' to '" + leftType.canonical() + "'");
        }
        requireMutable(op.left, leftType, scope, func, op);
        requireNotConst(op.left, leftType, scope, op);
        requireNotLoopImmutable(op.left, scope, op);
        if (op.left.type == TokenType.OPERATOR && op.left.text.equals("LOOKUP")) {
            hub.accessAndAsync.requireLookupWriteAuthorized(op.left, scope);
        }
        // Revive the target again: `arr = resize(arr, ..)` moves the slot while the RHS is resolved, and this assignment supersedes that move.
        if (leftSlotKeyForRevival != null) {
            scope.movedSlots.remove(leftSlotKeyForRevival);
            hub.model.partlyMovedSlots.remove(leftSlotKeyForRevival);
        }
        invalidateMatchPatternsForWrite(op.left, scope);
        if ("ref".equals(leftType.storage) && leftType.isSome && op.left.type == TokenType.VARREF) {
            ProofKills.implicit(op.left.text, hub.declarations.pointeeOf(leftType));
        }
        if (hub.model.typeOwnsMemory(leftType, new HashSet<>())) {
            ProofKills.kill(scope.activeMatchPatterns, op, "an assignment over a slot that owns memory (the old value is freed)", TypeCheckerModel.memberPathRoot(op.left), hub.declarations.destroyedTypes(leftType));
        }
        if (hub.declarations.isInlineOwningStruct(leftType)) {
            op.left.chk.inlineOwnsStruct = leftType.baseType;
        }
        markMovedIfOwned(leftType, op.right, scope);
        if (hub.model.typeOwnsMemory(leftType, new HashSet<>()) && hub.signatures.assignsInsideSwapLockStruct(op.left, scope)) {
            hub.signatures.requireFreshIntoSwapLock(op.right, "assigned into", String.valueOf(TypeCheckerModel.memberPathRoot(op.left)));
        }
        // Return the target's type (it differs from the value's when assigning 'null').
        return leftType;
    }

    /** Rejects compound assignment and ++/-- on an atomic target outright (they are read-then-write; use `swap`). See docs/TypeChecker.md, "Atomic write rules". */
    public void requireNotAtomicOrdinaryWriteTarget(TypeCheckerModel.TypeInfo targetType, Token at, String opText) {
        if (targetType.isAtomic) {
            throw new CompilerException("type", at.file, at.line,
                    "an 'atomic' value can't be written with '" + opText + "' -- every write to an "
                            + "already-declared atomic must go through 'swap' instead, which is the only "
                            + "primitive here that reads and writes it as one genuinely atomic step");
        }
    }

    /** Rejects assignment to a `let const` local, a const parameter or a const struct member. Struct-literal initialisation goes through checkInstantiate and never reaches here. */
    public void requireNotConst(Token target, TypeCheckerModel.TypeInfo targetType, TypeCheckerModel.Scope scope, Token at) {
        if (target.type == TokenType.VARREF && scope.isConstName(target.text)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + target.text + "' is 'const' -- it can never be assigned outside of its own "
                            + "declaration");
        }
        if (targetType.isConst) {
            String name = slotKeyOf(target);
            throw new CompilerException("type", at.file, at.line,
                    "'" + (name != null ? name : hub.controlFlow.describeConditionForError(target)) + "' is 'const' -- it "
                            + "can never be assigned");
        }
    }

    /** Rejects reassigning a for/for-match loop variable or its source expression inside the loop body (`arr[i] = ..` is governed by requireLookupWriteAuthorized). */
    public void requireNotLoopImmutable(Token target, TypeCheckerModel.Scope scope, Token at) {
        String key = slotKeyOf(target);
        if (key != null && scope.isLoopImmutable(key)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + key + "' is immutable for the duration of this 'for'/'for match' loop -- it's "
                            + "either the loop's own loop variable or its own source expression, and "
                            + "neither can be reassigned inside the loop body");
        }
    }

    /** A 'static' initializer may contain no CALL (except sizeof) and no `new`; variable references fail by resolving in an empty Scope. */
    public void checkNoRuntimeConstructs(Token node) {
        if (node == null) {
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL")) {
            if (node.left != null && node.left.type == TokenType.VARREF && node.left.text.equals("sizeof")) {
                // sizeof(T) is a compile-time constant (a struct's size is folded by the emitter from
                // its real layout); its argument is a type, so there is nothing further to check.
                return;
            }
            throw new CompilerException("type", node.file, node.line,
                    "a 'static' initializer must be a compile-time constant -- function calls are not "
                            + "allowed");
        }
        if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("new")) {
            // `new` is a runtime allocation and can never be folded to a constant.
            throw new CompilerException("type", node.file, node.line,
                    "a 'static' initializer must be a compile-time constant -- 'new' (a runtime heap "
                            + "allocation) is not allowed");
        }
        checkNoRuntimeConstructs(node.left);
        checkNoRuntimeConstructs(node.right);
        if (node.childs != null) {
            for (Token c : node.childs) {
                checkNoRuntimeConstructs(c);
            }
        }
    }

    /**
         * Looks up a `const`, evaluating lazily and memoizing (cycle-checked via constsBeingEvaluated); returns a literal Token that
         * the VARREF case substitutes in place. See docs/TypeChecker.md, "Compile-time constants".
         */
    public Token resolveConstValue(String name, Token referencingNode) {
        Token cached = hub.model.constResolvedValues.get(name);
        if (cached != null) {
            return cached;
        }
        if (hub.model.constsBeingEvaluated.contains(name)) {
            throw new CompilerException("type", referencingNode.file, referencingNode.line,
                    "'const " + name + "' is circularly defined (directly or indirectly references "
                            + "its own value)");
        }
        hub.model.constsBeingEvaluated.add(name);
        Token result;
        try {
            result = evaluateConstant(hub.model.constExprs.get(name));
        } finally {
            hub.model.constsBeingEvaluated.remove(name);
        }
        hub.model.constResolvedValues.put(name, result);
        return result;
    }

    /** Evaluates a const expression (literals, other consts, operators) to a literal Token, mirroring resolveOperatorType's dispatch; anything else throws. */
    public Token evaluateConstant(Token node) {
        switch (node.type) {
            case INTEGER:
            case FLOAT:
            case BOOL:
            case CHAR:
            case STRING:
            case NULL:
                return node;
            case VARREF:
                if (!hub.model.constExprs.containsKey(node.text)) {
                    throw new CompilerException("type", node.file, node.line,
                            "'" + node.text + "' is not a known const -- a compile-time constant "
                                    + "expression can only reference literals and other consts, never a "
                                    + "variable");
                }
                return resolveConstValue(node.text, node);
            case OPERATOR:
                return evaluateConstantOperator(node);
            default:
                throw new CompilerException("type", node.file, node.line,
                        "'" + node.text + "' cannot appear in a compile-time constant expression");
        }
    }

    /** Folds a template literal into a plain STRING token in place; each `${..}` is evaluated by evaluateConstant. See docs/TypeChecker.md, "Compile-time constants". */
    public TypeCheckerModel.TypeInfo resolveTemplateString(Token node) {
        StringBuilder sb = new StringBuilder();
        for (Token seg : node.childs) {
            if (seg.type == TokenType.OPERATOR && seg.text.equals("TEMPLATE_STRING_PART")) {
                sb.append(seg.literalValue);
                continue;
            }
            // The synthesized '(' group wraps one `${..}` segment (the Lexer emits '(' rather than '{').
            if (seg.childs.isEmpty()) {
                throw new CompilerException("type", seg.file, seg.line, "empty '${}' in template literal");
            }
            Token value = evaluateConstant(seg.childs.get(0));
            sb.append(stringifyTemplateSegmentValue(value));
        }
        node.type = TokenType.STRING;
        node.text = sb.toString();
        node.literalValue = sb.toString();
        node.childs = new ArrayList<>();
        return new TypeCheckerModel.TypeInfo("static", "imut", "string").withSome(true);
    }

    /** Stringifies one evaluated `${..}` segment; floats are rejected because no formatting convention is settled. */
    public String stringifyTemplateSegmentValue(Token value) {
        switch (value.type) {
            case STRING:
                return value.literalValue;
            case INTEGER:
                return value.text;
            case CHAR:
                return value.literalValue;
            case BOOL:
                return value.text;
            case FLOAT:
                throw new CompilerException("type", value.file, value.line,
                        "a 'float' value cannot appear in a template literal's '${...}' segment yet -- "
                                + "no formatting convention has been settled on; pass an already-"
                                + "stringified value instead");
            default:
                throw new CompilerException("type", value.file, value.line,
                        "'" + value.text + "' cannot appear in a template literal's '${...}' segment");
        }
    }

    public Token evaluateConstantOperator(Token op) {
        if (op.text.equals("CALL")) {
            throw new CompilerException("type", op.file, op.line,
                    "a compile-time constant expression cannot call a function");
        }
        if (op.text.equals("mut") || op.text.equals("imut")) {
            // Mutability never affects a literal's own value -- pass
            // straight through to whatever's wrapped.
            return evaluateConstant(op.left);
        }
        if (op.text.equals("+") || op.text.equals("-") || op.text.equals("*") || op.text.equals("/")
                || op.text.equals("%")) {
            if (op.unary) {
                return evaluateConstantUnaryMinus(op);
            }
            return evaluateConstantArithmetic(op);
        }
        if (op.text.equals("!")) {
            Token operand = evaluateConstant(op.left);
            requireConstBool(operand, op);
            return new Token(TokenType.BOOL, String.valueOf(!Boolean.parseBoolean(operand.text)),
                    op.line, op.file);
        }
        if (op.text.equals("&&") || op.text.equals("||")) {
            Token left = evaluateConstant(op.left);
            Token right = evaluateConstant(op.right);
            requireConstBool(left, op);
            requireConstBool(right, op);
            boolean l = Boolean.parseBoolean(left.text);
            boolean r = Boolean.parseBoolean(right.text);
            boolean result = op.text.equals("&&") ? (l && r) : (l || r);
            return new Token(TokenType.BOOL, String.valueOf(result), op.line, op.file);
        }
        if (op.text.equals("<") || op.text.equals(">") || op.text.equals("<=") || op.text.equals(">=")
                || op.text.equals("==") || op.text.equals("!=")) {
            return evaluateConstantComparison(op);
        }
        throw new CompilerException("type", op.file, op.line,
                "'" + op.text + "' cannot appear in a compile-time constant expression");
    }

    public void requireConstBool(Token t, Token op) {
        if (t.type != TokenType.BOOL) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' requires a 'bool' operand in a compile-time constant expression, "
                            + "got '" + t.type + "'");
        }
    }

    public boolean isConstNumeric(Token t) {
        return t.type == TokenType.INTEGER || t.type == TokenType.FLOAT;
    }

    public Token evaluateConstantUnaryMinus(Token op) {
        Token operand = evaluateConstant(op.left);
        if (!isConstNumeric(operand)) {
            throw new CompilerException("type", op.file, op.line,
                    "unary '-' requires a numeric operand in a compile-time constant expression, got '"
                            + operand.type + "'");
        }
        if (operand.type == TokenType.FLOAT) {
            return new Token(TokenType.FLOAT, formatConstFloat(-Double.parseDouble(operand.text)),
                    op.line, op.file);
        }
        return new Token(TokenType.INTEGER, Long.toString(-Long.parseLong(operand.text)), op.line, op.file);
    }

    public Token evaluateConstantArithmetic(Token op) {
        Token leftTok = evaluateConstant(op.left);
        Token rightTok = evaluateConstant(op.right);
        if (!isConstNumeric(leftTok) || !isConstNumeric(rightTok)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' requires numeric operands in a compile-time constant expression, "
                            + "got '" + leftTok.type + "' and '" + rightTok.type + "'");
        }
        if (leftTok.type == TokenType.FLOAT || rightTok.type == TokenType.FLOAT) {
            double l = Double.parseDouble(leftTok.text);
            double r = Double.parseDouble(rightTok.text);
            double result;
            switch (op.text) {
                case "+": result = l + r; break;
                case "-": result = l - r; break;
                case "*": result = l * r; break;
                case "/": result = l / r; break;
                case "%": result = l % r; break;
                default: throw new CompilerException("type", op.file, op.line,
                        "internal error: unhandled constant arithmetic operator '" + op.text + "'");
            }
            return new Token(TokenType.FLOAT, formatConstFloat(result), op.line, op.file);
        }
        long l = Long.parseLong(leftTok.text);
        long r = Long.parseLong(rightTok.text);
        if ((op.text.equals("/") || op.text.equals("%")) && r == 0) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' by a literal '0' always fails, even in a compile-time constant "
                            + "expression -- this isn't a matter of needing a proof or 'unsafe', the "
                            + "divisor is simply wrong");
        }
        long result;
        switch (op.text) {
            case "+": result = l + r; break;
            case "-": result = l - r; break;
            case "*": result = l * r; break;
            case "/": result = l / r; break;
            case "%": result = l % r; break;
            default: throw new CompilerException("type", op.file, op.line,
                    "internal error: unhandled constant arithmetic operator '" + op.text + "'");
        }
        return new Token(TokenType.INTEGER, Long.toString(result), op.line, op.file);
    }

    public Token evaluateConstantComparison(Token op) {
        Token leftTok = evaluateConstant(op.left);
        Token rightTok = evaluateConstant(op.right);
        boolean result;
        if (isConstNumeric(leftTok) && isConstNumeric(rightTok)) {
            double l = Double.parseDouble(leftTok.text);
            double r = Double.parseDouble(rightTok.text);
            switch (op.text) {
                case "<": result = l < r; break;
                case ">": result = l > r; break;
                case "<=": result = l <= r; break;
                case ">=": result = l >= r; break;
                case "==": result = l == r; break;
                case "!=": result = l != r; break;
                default: throw new CompilerException("type", op.file, op.line,
                        "internal error: unhandled constant comparison operator '" + op.text + "'");
            }
        } else if (leftTok.type == rightTok.type
                && (op.text.equals("==") || op.text.equals("!="))) {
            // Non-numeric equality compares values (literalValue holds the escape-processed form for char/string).
            String l = leftTok.literalValue != null ? leftTok.literalValue : leftTok.text;
            String r = rightTok.literalValue != null ? rightTok.literalValue : rightTok.text;
            boolean eq = l.equals(r);
            result = op.text.equals("==") ? eq : !eq;
        } else {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' can't compare '" + leftTok.type + "' and '" + rightTok.type
                            + "' in a compile-time constant expression");
        }
        return new Token(TokenType.BOOL, String.valueOf(result), op.line, op.file);
    }

    /** Matches this language's own float-literal text convention (always a decimal point present) without ever emitting redundant trailing zeros/scientific notation Double.toString() would otherwise produce. */
    public String formatConstFloat(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.format("%.1f", value);
        }
        return Double.toString(value);
    }

    /** A static/global scalar needs a concrete mutability ('mut' or 'imut'); a struct is already governed by its member declarations. */
    public void requireStaticMutable(TypeCheckerModel.TypeInfo type, Token at) {
        if (hub.model.structs.containsKey(type.baseType)) {
            return;
        }
        if (!"mut".equals(type.mutability) && !"imut".equals(type.mutability)) {
            throw new CompilerException("type", at.file, at.line,
                    "a 'static' variable must have a concrete mutability, got '" + type.canonical()
                            + "' -- write 'mut' or 'imut' before the value");
        }
    }

    /** A variable only accepts a value with concrete mutability; indeterminate values (literals, arithmetic results) need a `mut`/`imut` prefix. */
    public void requireConcreteMutability(TypeCheckerModel.TypeInfo type, Token at) {
        if (!"mut".equals(type.mutability) && !"imut".equals(type.mutability)) {
            throw new CompilerException("type", at.file, at.line,
                    "this value's mutability is indeterminate -- write 'mut' or 'imut' before it to "
                            + "store it in a variable");
        }
    }

    /**
         * Checks `let:<FullType> name = expr`: the RHS must match the bound exactly, be 'null' for a pointer bound, or implement an
         * interface bound; no other implicit casting. Returns null when no bound is written. See docs/TypeChecker.md, "Let type bounds".
         */
    public TypeCheckerModel.TypeInfo resolveAndValidateTypeBound(Token letTok, TypeCheckerModel.TypeInfo rhsType, Token rhsExpr) {
        if (letTok.typeBound == null) {
            return null;
        }
        TypeCheckerModel.TypeInfo bound = hub.signatures.resolveTypeAnnotationRaw(letTok.typeBound, letTok);
        TypeCheckerModel.TypeInfo result;
        if (rhsType.baseType.equals("null")) {
            // Only pointers (owns/ref/raw, not auto/static) can be null.
            if (!hub.isPointerType(bound)) {
                throw new CompilerException("type", rhsExpr.file, rhsExpr.line,
                        "'null' can only satisfy a pointer-typed bound ('owns'/'ref'/'raw'), got '"
                                + bound.canonical() + "'");
            }
            if (bound.isSome) {
                // A 'null' RHS is checked against a 'some' bound by hand here; this path does not go through typesCompatible.
                throw new CompilerException("type", rhsExpr.file, rhsExpr.line,
                        "'null' cannot satisfy a 'some'-tagged bound ('" + bound.canonical()
                                + "') -- a 'some' type guarantees a real value is present");
            }
            result = bound;
        } else {
            requireConcreteMutability(rhsType, rhsExpr);
            boolean sameOrAncestor = rhsType.baseType.equals(bound.baseType)
                    || (hub.model.interfaces.containsKey(bound.baseType) && hub.model.structs.containsKey(rhsType.baseType)
                            && hub.controlFlow.structImplementsInterface(rhsType.baseType, bound.baseType));
            boolean literalOk = hub.literalAdapts(bound, rhsType);
            sameOrAncestor = sameOrAncestor || literalOk;
            boolean storageOk = (bound.storage == null && rhsType.storage == null)
                    || (bound.storage != null && rhsType.storage != null
                            && hub.storageCompatible(bound.storage, rhsType.storage));
            if (!sameOrAncestor || !storageOk || !hub.mutabilityCompatible(bound.mutability, rhsType.mutability)) {
                throw new CompilerException("type", rhsExpr.file, rhsExpr.line,
                        "the initializer's type '" + rhsType.canonical() + "' doesn't satisfy the "
                                + "declared bound '" + bound.canonical() + "'");
            }
            if (literalOk) {
                hub.adaptLiteral(rhsExpr, bound);
            }
            result = bound;
        }
        // The declared type is the bound, so the 'let static' mutability rule applies to it.
        if (letTok.isStatic) {
            requireStaticMutable(result, rhsExpr);
        }
        return result;
    }

    public TypeCheckerModel.TypeInfo checkCompoundAssign(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        rejectDerefAsWriteTarget(op.left, "a compound assignment");
        TypeCheckerModel.TypeInfo leftType = hub.controlFlow.resolveExprType(op.left, scope, func);
        requireNotAtomicOrdinaryWriteTarget(leftType, op, op.text);
        TypeCheckerModel.TypeInfo rightType = hub.controlFlow.resolveExprType(op.right, scope, func);
        // C-style: "p += n" / "p -= n" on a raw pointer moves by n elements (n * pointee size bytes).
        boolean pointerCompound = TypeCheckerModel.isRawPointerType(leftType) && (op.text.equals("+=") || op.text.equals("-="))
                && !(hub.pointeeSize(leftType, op) == 1 && leftType.sameBaseType(rightType));
        if (pointerCompound) {
            hub.requireEightByteInteger(rightType, op);
            long scale = hub.pointeeSize(leftType, op);
            op.chk.pointerScale = scale > 1 ? scale : 0;
            op.chk.pointerScaleStruct = op.chk.pointerScale > 0 ? hub.pointeeStructName(leftType) : null;
        } else {
            hub.requireNumeric(leftType, op);
            hub.requireNumeric(rightType, op);
            rightType = hub.unifyLiteralOperands(op, leftType, rightType)[1];
            if (!leftType.sameBaseType(rightType)) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + op.text + "' requires both sides to be the same numeric type, got '"
                                + leftType.canonical() + "' and '" + rightType.canonical() + "'");
            }
        }
        if (op.text.equals("/=") || op.text.equals("%=")) {
            requireProvenNonzeroDivisor(op.right, rightType, scope, op);
        }
        requireMutable(op.left, leftType, scope, func, op);
        requireNotLoopImmutable(op.left, scope, op);
        if (op.left.type == TokenType.OPERATOR && op.left.text.equals("LOOKUP")) {
            hub.accessAndAsync.requireLookupWriteAuthorized(op.left, scope);
        }
        invalidateMatchPatternsForWrite(op.left, scope);
        return leftType;
    }

    public TypeCheckerModel.TypeInfo checkArithmetic(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        TypeCheckerModel.TypeInfo leftType = hub.controlFlow.resolveExprType(op.left, scope, func);
        TypeCheckerModel.TypeInfo rightType = hub.controlFlow.resolveExprType(op.right, scope, func);
        // C-style pointer arithmetic on 'raw' pointers: pointer +/- integer and
        // integer + pointer give a pointer moved by n elements; pointer - pointer
        // gives the signed element count between them. Any other operator on a
        // pointer keeps its previous (plain numeric) meaning.
        boolean leftPtr = TypeCheckerModel.isRawPointerType(leftType);
        boolean rightPtr = TypeCheckerModel.isRawPointerType(rightType);
        if ((leftPtr || rightPtr) && (op.text.equals("+") || op.text.equals("-"))) {
            if (leftPtr && rightPtr) {
                if (!op.text.equals("-")) {
                    throw new CompilerException("type", op.file, op.line, "cannot add two 'raw' pointers");
                }
                if (!leftType.baseType.equals(rightType.baseType)) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot subtract 'raw' pointers to different types ('" + leftType.canonical()
                                    + "' and '" + rightType.canonical() + "')");
                }
                op.chk.pointerArithKind = 3;
                op.chk.pointerScale = hub.pointeeSize(leftType, op);
                op.chk.pointerScaleStruct = hub.pointeeStructName(leftType);
                return new TypeCheckerModel.TypeInfo(null, "indeterminate", "s64");
            }
            if (leftPtr) {
                hub.requireEightByteInteger(rightType, op);
                op.chk.pointerArithKind = 1;
                op.chk.pointerScale = hub.pointeeSize(leftType, op);
                op.chk.pointerScaleStruct = hub.pointeeStructName(leftType);
                return hub.pointerResultType(leftType);
            }
            if (op.text.equals("-")) {
                throw new CompilerException("type", op.file, op.line, "cannot subtract a 'raw' pointer from an integer");
            }
            hub.requireEightByteInteger(leftType, op);
            op.chk.pointerArithKind = 2;
            op.chk.pointerScale = hub.pointeeSize(rightType, op);
            op.chk.pointerScaleStruct = hub.pointeeStructName(rightType);
            return hub.pointerResultType(rightType);
        }
        hub.requireNumeric(leftType, op);
        hub.requireNumeric(rightType, op);
        // arithmetic between two float literals is itself a float literal (it adapts, as a whole, to f32 or f64)
        boolean bothFloatLiterals = leftType.floatLiteral && rightType.floatLiteral;
        TypeCheckerModel.TypeInfo[] unified = hub.unifyLiteralOperands(op, leftType, rightType);
        leftType = unified[0];
        rightType = unified[1];
        if (!leftType.sameBaseType(rightType)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' requires both sides to be the same numeric type, got '"
                            + leftType.canonical() + "' and '" + rightType.canonical() + "'");
        }
        hub.locksAndProofs.requireProvenFloatState(op.left, leftType, scope, op);
        hub.locksAndProofs.requireProvenFloatState(op.right, rightType, scope, op);
        if (op.text.equals("/") || op.text.equals("%")) {
            requireProvenNonzeroDivisor(op.right, rightType, scope, op);
        }
        TypeCheckerModel.TypeInfo arith = new TypeCheckerModel.TypeInfo(null, "indeterminate", leftType.baseType);
        if (bothFloatLiterals) {
            arith.floatLiteral = true;
        }
        return arith;
    }

    /**
         * Integer '/' and '%' need a literal nonzero divisor, a live nonzero match proof, or `assume match`; a literal 0 is always an error.
         * Floats are exempt (IEEE gives inf/NaN, no trap). See docs/TypeChecker.md, "Division proofs".
         */
    public void requireProvenNonzeroDivisor(Token divisorExpr, TypeCheckerModel.TypeInfo divisorType, TypeCheckerModel.Scope scope, Token op) {
        if (!TypeCheckerModel.INTEGER_TYPES.contains(divisorType.baseType)) {
            return;
        }
        if (divisorExpr.type == TokenType.INTEGER) {
            if (divisorExpr.text.equals("0")) {
                throw new CompilerException("type", divisorExpr.file, divisorExpr.line,
                        "'" + op.text + "' by a literal '0' always fails -- this isn't a matter of "
                                + "needing a proof or 'unsafe', the divisor is simply wrong");
            }
            return;
        }
        if (!hub.locksAndProofs.isProvenNonzero(divisorExpr, scope)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' requires the divisor to be proven nonzero first (`match "
                            + divisorExpr.text + " != 0{...}` for an unsigned type, or `match "
                            + divisorExpr.text + " > 0{...}` for a signed one -- `!=0` alone "
                            + "isn't enough for a signed divisor, since it doesn't rule out the type's own "
                            + "minimum value divided by -1, which overflows), or vouched for with 'assume match' in an 'unsafe assume' block [needs: assume match " + exprHint(divisorExpr) + " != 0]");
        }
    }

    public TypeCheckerModel.TypeInfo checkUnaryMinus(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        TypeCheckerModel.TypeInfo operand = hub.controlFlow.resolveExprType(op.left, scope, func);
        hub.requireNumeric(operand, op);
        hub.locksAndProofs.requireProvenFloatState(op.left, operand, scope, op);
        String resultBase = TypeCheckerModel.UNSIGNED_TO_SIGNED.getOrDefault(operand.baseType, operand.baseType);
        TypeCheckerModel.TypeInfo negated = new TypeCheckerModel.TypeInfo(null, "indeterminate", resultBase);
        if (operand.literalValue != null && operand.baseType.equals("u64")) {
            // "-5" is still a literal (see TypeInfo.literalValue): its value is -5, its default
            // type s64, and it may adapt to any signed type that holds it.
            negated.literalValue = operand.literalValue.negate();
        }
        if (operand.floatLiteral) {
            negated.floatLiteral = true; // "-0.5" is still a float literal that adapts to f32 or f64
        }
        return negated;
    }

    public TypeCheckerModel.TypeInfo checkLogicalNot(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        TypeCheckerModel.TypeInfo operand = hub.controlFlow.resolveExprType(op.left, scope, func);
        hub.requireBoolOrInteger(operand, op);
        return new TypeCheckerModel.TypeInfo(null, "indeterminate", "bool");
    }

    /** Shared by both "++" and "--" -- identical rules either way (numeric, mutable, not the loop's own immutable slot); only the emitted mnemonic differs, decided later by BytecodeEmitter from op.text alone. */
    public TypeCheckerModel.TypeInfo checkIncrement(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        rejectDerefAsWriteTarget(op.left, "'++'/'--'");
        TypeCheckerModel.TypeInfo operand = hub.controlFlow.resolveExprType(op.left, scope, func);
        // C-style: "p++" on a raw pointer advances by the pointee size. A
        // one-byte pointee keeps the ordinary path below, byte-identical to before.
        if (TypeCheckerModel.isRawPointerType(operand)) {
            long scale = hub.pointeeSize(operand, op);
            if (scale > 1) {
                requireNotAtomicOrdinaryWriteTarget(operand, op, op.text);
                requireMutable(op.left, operand, scope, func, op);
                requireNotLoopImmutable(op.left, scope, op);
                invalidateMatchPatternsForWrite(op.left, scope);
                op.chk.pointerScale = scale;
                op.chk.pointerScaleStruct = hub.pointeeStructName(operand);
                return operand;
            }
        }
        requireNotAtomicOrdinaryWriteTarget(operand, op, op.text);
        hub.requireNumeric(operand, op);
        requireMutable(op.left, operand, scope, func, op);
        requireNotLoopImmutable(op.left, scope, op);
        invalidateMatchPatternsForWrite(op.left, scope);
        return new TypeCheckerModel.TypeInfo(null, "indeterminate", operand.baseType);
    }

    /** Modifying a value needs 'mut' on the target and on every prefix of a dotted/indexed path. */
    public void requireMutable(Token targetExpr, TypeCheckerModel.TypeInfo targetType, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, Token at) {
        if (!"mut".equals(targetType.mutability)) {
            throw new CompilerException("type", at.file, at.line,
                    "cannot modify this value -- its mutability is '"
                            + (targetType.mutability == null ? "indeterminate" : targetType.mutability)
                            + "', not 'mut'");
        }
        // '@pure' functions may not mutate through any pointer anywhere in the chain; unsafe gives no exemption.
        if (hub.impls.isPureFunc(func) && targetType.storage != null) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '@pure' and cannot mutate through a pointer -- '"
                            + targetType.canonical() + "' has 'raw'/'owns'/'ref'/'auto'/'static' storage");
        }
        if (targetExpr.type == TokenType.OPERATOR && targetExpr.text.equals(".")) {
            TypeCheckerModel.TypeInfo prefixType = hub.controlFlow.resolveExprType(targetExpr.left, scope, func);
            requireMutable(targetExpr.left, prefixType, scope, func, at);
        } else if (targetExpr.type == TokenType.OPERATOR && targetExpr.text.equals("LOOKUP")) {
            // LOOKUP is walked for the '@pure' pointer check, recursing so `p.arr[0]` reaches `p`.
            TypeCheckerModel.TypeInfo prefixType = hub.controlFlow.resolveExprType(targetExpr.left, scope, func);
            requireMutable(targetExpr.left, prefixType, scope, func, at);
        }
    }

    /**
         * `&&`/`||` (eager) and `&&then`/`||then` (lazy) share this check; the then-variants are legal only at the top of an
         * if/elseif condition (eligibleHere). See docs/TypeChecker.md, "Operator dispatch context flags".
         */
    public TypeCheckerModel.TypeInfo checkLogical(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, boolean eligibleHere) {
        boolean isThenVariant = op.text.equals("&&then") || op.text.equals("||then");
        if (isThenVariant && !eligibleHere) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' is only legal at the top level of an 'if'/'elseif' condition -- as "
                            + "that condition's own root operator, or nested only under further "
                            + "'&&then'/'||then' operators reached from it -- not used as a function argument, "
                            + "inside a '!' or comparison, as an operand of a plain '&&'/'||', inside a 'match', "
                            + "or anywhere else an ordinary expression is expected");
        }
        // Only a then-variant re-arms eligibility for its operands; a plain &&/|| leaves it consumed.
        if (isThenVariant) {
            hub.model.insideShortCircuitEligiblePosition = true;
        }
        TypeCheckerModel.TypeInfo leftType = hub.controlFlow.resolveExprType(op.left, scope, func);
        hub.model.insideShortCircuitEligiblePosition = false;
        if (isThenVariant) {
            hub.model.insideShortCircuitEligiblePosition = true;
        }
        TypeCheckerModel.TypeInfo rightType = hub.controlFlow.resolveExprType(op.right, scope, func);
        hub.model.insideShortCircuitEligiblePosition = false;
        hub.requireBoolOrInteger(leftType, op);
        hub.requireBoolOrInteger(rightType, op);
        return new TypeCheckerModel.TypeInfo(null, "indeterminate", "bool");
    }

    public TypeCheckerModel.TypeInfo checkComparison(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        TypeCheckerModel.TypeInfo leftType = hub.controlFlow.resolveExprType(op.left, scope, func);
        TypeCheckerModel.TypeInfo rightType = hub.controlFlow.resolveExprType(op.right, scope, func);
        TypeCheckerModel.TypeInfo[] unified = hub.unifyLiteralOperands(op, leftType, rightType);
        leftType = unified[0];
        rightType = unified[1];
        boolean sameType = leftType.sameBaseType(rightType);
        // '==' and '!=' also allow comparing a pointer with 'null' (either side); ordering comparisons do not.
        boolean nullVsPointer = (op.text.equals("==") || op.text.equals("!="))
                && ((rightType.baseType.equals("null") && hub.isPointerType(leftType))
                    || (leftType.baseType.equals("null") && hub.isPointerType(rightType)));
        if (!sameType && !nullVsPointer) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' requires both sides to be the same type, got '"
                            + leftType.canonical() + "' and '" + rightType.canonical() + "'");
        }
        hub.locksAndProofs.requireProvenFloatState(op.left, leftType, scope, op);
        hub.locksAndProofs.requireProvenFloatState(op.right, rightType, scope, op);
        if (op.text.equals("==") || op.text.equals("!=")) {
            normalizeRefComparison(op, leftType, rightType);
        }
        return new TypeCheckerModel.TypeInfo(null, "indeterminate", "bool");
    }

    /**
     * A nullable `ref` is an id and every other pointer an address, so `==`/`!=` between the two kinds first converts one side: an address
     * compared with a nullable `ref` becomes an id (GT_REF_ID, equal objects have equal ids); a `raw` pointer compared with one makes the id
     * an address (GT_REF_RESOLVE). A `null` literal is 0 in both forms and needs nothing.
     */
    public void normalizeRefComparison(Token op, TypeCheckerModel.TypeInfo leftType, TypeCheckerModel.TypeInfo rightType) {
        boolean leftId = "ref".equals(leftType.storage) && !leftType.isSome;
        boolean rightId = "ref".equals(rightType.storage) && !rightType.isSome;
        if (leftId == rightId || leftType.baseType.equals("null") || rightType.baseType.equals("null")) {
            return;
        }
        Token idSide = leftId ? op.left : op.right;
        Token addrSide = leftId ? op.right : op.left;
        TypeCheckerModel.TypeInfo addrType = leftId ? rightType : leftType;
        if (addrType.storage == null) {
            return;
        }
        if ("raw".equals(addrType.storage)) {
            idSide.chk.refToRaw = true;
        } else {
            addrSide.chk.refToId = true;
        }
    }
}

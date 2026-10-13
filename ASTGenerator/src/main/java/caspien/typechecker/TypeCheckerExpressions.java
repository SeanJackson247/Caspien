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
        while (true) {
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
                    continue;   // re-dispatch the substituted literal
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
                break;
            case DELINEATOR:
                break;
            default:
                break;
        }
        throw new CompilerException("type", node.file, node.line,
                "internal error: don't know how to type '" + node.type + "(" + node.text + ")'");
        }
    }

    /** The operators that are never valid as ordinary expressions (everything else has its own frame kind, see stepExpr). */
    public TypeCheckerModel.TypeInfo resolveOperatorType(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func,
            boolean eligibleForRvoHere) {
        switch (op.text) {
            case "dup":
                // 'dup' is expanded earlier (DupExpander); reaching here means it was used outside an array literal.
                throw new CompilerException("type", op.file, op.line,
                        "'dup' can only be used as an element of an array literal");
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

    /** resolveExprType as a frame: node-kind dispatch, converted operators run as their own frames, the rest through resolveOperatorType. */
    public int stepExpr(CheckFrame f) {
        Token node = f.node;
        if (f.phase != 0) {
            return finishExpr(f, f.got);
        }
        if (node.type == TokenType.DELINEATOR && node.text.equals("(")) {
            if (node.childs.isEmpty()) {
                throw new CompilerException("type", node.file, node.line, "empty '()' expression");
            }
            f.phase = 1;
            return f.call(node.childs.get(0));
        }
        if (node.type == TokenType.KEYWORD && node.text.equals("try")) {
            // Expression-position `try`; insideLoop is false (see checkTry).
            f.phase = 2;
            return f.call(hub.controlFlow.tryStmtFrame(node, f.scope, f.func, false));
        }
        if (node.type == TokenType.DELINEATOR && node.text.equals("[")) {
            f.phase = 2;
            return f.call(new CheckFrame(CheckFrame.ARRAY_LITERAL, node, f.scope, f.func));
        }
        if (node.type != TokenType.OPERATOR) {
            return finishExpr(f, resolveExprTypeInner(node, f.scope, f.func));
        }
        // Captured once and reset before dispatch, so a stale 'true' from an enclosing context cannot reach other operators;
        // only checkLogical sees it. See docs/TypeChecker.md, "Operator dispatch context flags".
        boolean eligibleHere = hub.model.insideShortCircuitEligiblePosition;
        hub.model.insideShortCircuitEligiblePosition = false;
        // Same capture-and-reset for the struct-RVO destination flag; only checkCall consults it.
        boolean eligibleForRvoHere = hub.model.insideStructRvoDestinationPosition;
        hub.model.insideStructRvoDestinationPosition = false;
        int kind = 0;
        boolean pFlag = eligibleHere;
        switch (node.text) {
            case ",": kind = CheckFrame.COMMA; break;
            case "+": case "-": case "*": case "/": case "%":
                kind = node.unary ? CheckFrame.UNARY_MINUS : CheckFrame.ARITH;
                break;
            case "!": kind = CheckFrame.LOGICAL_NOT; break;
            case "++": case "--": kind = CheckFrame.INCREMENT; break;
            case "&&": case "||": case "&&then": case "||then": kind = CheckFrame.LOGICAL; break;
            case "<": case ">": case "<=": case ">=": case "==": case "!=": kind = CheckFrame.COMPARISON; break;
            case "mut": case "imut":
                // mut/imut are transparent for the RVO flag: re-armed one level down so the wrapped CALL still sees it.
                hub.model.insideStructRvoDestinationPosition = eligibleForRvoHere;
                kind = CheckFrame.MUTABILITY_OF;
                break;
            case "..": kind = CheckFrame.RANGE; break;
            case "=": kind = CheckFrame.ASSIGN; pFlag = false; break;
            case "+=": case "-=": case "*=": case "/=": case "%=": kind = CheckFrame.COMPOUND_ASSIGN; pFlag = false; break;
            case "CALL": kind = CheckFrame.CALL_EXPR; pFlag = eligibleForRvoHere; break;
            case "LOOKUP": kind = CheckFrame.LOOKUP; pFlag = false; break;
            case "as": kind = CheckFrame.AS; pFlag = false; break;
            case ".": kind = CheckFrame.DOT; pFlag = false; break;
            case "INSTANTIATE": kind = CheckFrame.INSTANTIATE; pFlag = false; break;
            case "owns": case "ref": case "auto": case "raw": case "static": kind = CheckFrame.ADDRESS_OF; pFlag = false; break;
            case "new": kind = CheckFrame.NEW; pFlag = false; break;
            case "await": kind = CheckFrame.AWAIT; pFlag = false; break;
            case "par": kind = CheckFrame.PAR; pFlag = false; break;
            case "unsafe": kind = CheckFrame.UNSAFE_DYN_WRAPPER; pFlag = false; break;
            case "atomic": kind = CheckFrame.ATOMIC_WRAPPER; pFlag = false; break;
            case "swap": kind = CheckFrame.SWAP; pFlag = false; break;
            case "in": kind = CheckFrame.IN; pFlag = false; break;
            case "within": kind = CheckFrame.WITHIN; pFlag = false; break;
            case "instanceof": kind = CheckFrame.INSTANCEOF; pFlag = false; break;
            case "implements": kind = CheckFrame.IMPLEMENTS; pFlag = false; break;
            default: break;
        }
        if (kind == 0) {
            return finishExpr(f, resolveOperatorType(node, f.scope, f.func, eligibleForRvoHere));
        }
        CheckFrame handler = new CheckFrame(kind, node, f.scope, f.func);
        handler.p = pFlag;
        f.phase = 2;
        return f.call(handler);
    }

    private int finishExpr(CheckFrame f, TypeCheckerModel.TypeInfo result) {
        Token node = f.node;
        if (node.chk.resolvedCallTarget != null && node.type == TokenType.OPERATOR && "CALL".equals(node.text)) {
            ProofKills.call(f.scope.activeMatchPatterns, node, node.chk.resolvedCallTarget);
        }
        node.chk.resolvedType = result.canonical();
        if (f.func != null && hub.impls.isPureRtFunc(f.func)) {
            hub.impls.requirePureRtNoPointer(result, node, f.func, "in an expression");
        }
        return f.done(result);
    }

    /** `a, b`: both sides are resolved, the value is the right one. */
    public int stepComma(CheckFrame f) {
        switch (f.phase) {
            case 0:
                f.phase = 1;
                return f.call(f.node.left);
            case 1:
                f.phase = 2;
                return f.call(f.node.right);
            default:
                return f.done(f.got);
        }
    }

    public int stepArithmetic(CheckFrame f) {
        switch (f.phase) {
            case 0:
                f.phase = 1;
                return f.call(f.node.left);
            case 1:
                f.a = f.got;
                f.phase = 2;
                return f.call(f.node.right);
            default:
                return f.done(arithmeticType(f.node, f.scope, f.a, f.got));
        }
    }

    public int stepUnaryMinus(CheckFrame f) {
        if (f.phase == 0) {
            f.phase = 1;
            return f.call(f.node.left);
        }
        return f.done(unaryMinusType(f.node, f.scope, f.got));
    }

    public int stepLogicalNot(CheckFrame f) {
        if (f.phase == 0) {
            f.phase = 1;
            return f.call(f.node.left);
        }
        hub.requireBoolOrInteger(f.got, f.node);
        return f.done(new TypeCheckerModel.TypeInfo(null, "indeterminate", "bool"));
    }

    public int stepComparison(CheckFrame f) {
        switch (f.phase) {
            case 0:
                f.phase = 1;
                return f.call(f.node.left);
            case 1:
                f.a = f.got;
                f.phase = 2;
                return f.call(f.node.right);
            default:
                return f.done(comparisonType(f.node, f.scope, f.a, f.got));
        }
    }

    /**
     * `&&`/`||` (eager) and `&&then`/`||then` (lazy) share this check; the then-variants are legal only at the top of an
     * if/elseif condition (f.p = eligibleHere). See docs/TypeChecker.md, "Operator dispatch context flags".
     */
    public int stepLogical(CheckFrame f) {
        Token op = f.node;
        boolean isThenVariant = op.text.equals("&&then") || op.text.equals("||then");
        switch (f.phase) {
            case 0:
                if (isThenVariant && !f.p) {
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
                f.phase = 1;
                return f.call(op.left);
            case 1:
                f.a = f.got;
                hub.model.insideShortCircuitEligiblePosition = false;
                if (isThenVariant) {
                    hub.model.insideShortCircuitEligiblePosition = true;
                }
                f.phase = 2;
                return f.call(op.right);
            default:
                hub.model.insideShortCircuitEligiblePosition = false;
                hub.requireBoolOrInteger(f.a, op);
                hub.requireBoolOrInteger(f.got, op);
                return f.done(new TypeCheckerModel.TypeInfo(null, "indeterminate", "bool"));
        }
    }

    /** Shared by both "++" and "--" -- identical rules either way (numeric, mutable, not the loop's own immutable slot); only the emitted mnemonic differs, decided later by BytecodeEmitter from op.text alone. */
    public int stepIncrement(CheckFrame f) {
        Token op = f.node;
        switch (f.phase) {
            case 0:
                rejectDerefAsWriteTarget(op.left, "'++'/'--'");
                f.phase = 1;
                return f.call(op.left);
            case 1: {
                TypeCheckerModel.TypeInfo operand = f.got;
                f.a = operand;
                // C-style: "p++" on a raw pointer advances by the pointee size. A
                // one-byte pointee keeps the ordinary path below, byte-identical to before.
                if (TypeCheckerModel.isRawPointerType(operand)) {
                    long scale = hub.pointeeSize(operand, op);
                    if (scale > 1) {
                        requireNotAtomicOrdinaryWriteTarget(operand, op, op.text);
                        f.p = true;
                        f.phase = 2;
                        return f.call(requireMutableFrame(f, op.left, operand, op));
                    }
                }
                requireNotAtomicOrdinaryWriteTarget(operand, op, op.text);
                hub.requireNumeric(operand, op);
                f.phase = 2;
                return f.call(requireMutableFrame(f, op.left, operand, op));
            }
            default: {
                TypeCheckerModel.TypeInfo operand = f.a;
                requireNotLoopImmutable(op.left, f.scope, op);
                invalidateMatchPatternsForWrite(op.left, f.scope);
                if (f.p) {
                    op.chk.pointerScale = hub.pointeeSize(operand, op);
                    op.chk.pointerScaleStruct = hub.pointeeStructName(operand);
                    return f.done(operand);
                }
                return f.done(new TypeCheckerModel.TypeInfo(null, "indeterminate", operand.baseType));
            }
        }
    }

    private CheckFrame requireMutableFrame(CheckFrame f, Token targetExpr, TypeCheckerModel.TypeInfo targetType, Token at) {
        CheckFrame m = new CheckFrame(CheckFrame.REQUIRE_MUTABLE, targetExpr, f.scope, f.func);
        m.a = targetType;
        m.y = at;
        return m;
    }

    /** Modifying a value needs 'mut' on the target and on every prefix of a dotted/indexed path (f.node = target, f.a = its type, f.y = error anchor). */
    public int stepRequireMutable(CheckFrame f) {
        if (f.phase == 1) {
            f.node = f.node.left;     // the prefix: checked the same way, one level down
            f.a = f.got;
        }
        Token targetExpr = f.node;
        TypeCheckerModel.TypeInfo targetType = f.a;
        Token at = f.y;
        if (!"mut".equals(targetType.mutability)) {
            throw new CompilerException("type", at.file, at.line,
                    "cannot modify this value -- its mutability is '"
                            + (targetType.mutability == null ? "indeterminate" : targetType.mutability)
                            + "', not 'mut'");
        }
        // '@pure' functions may not mutate through any pointer anywhere in the chain; unsafe gives no exemption.
        if (hub.impls.isPureFunc(f.func) && targetType.storage != null) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + f.func.name + "' is '@pure' and cannot mutate through a pointer -- '"
                            + targetType.canonical() + "' has 'raw'/'owns'/'ref'/'auto'/'static' storage");
        }
        if (targetExpr.type == TokenType.OPERATOR && (targetExpr.text.equals(".") || targetExpr.text.equals("LOOKUP"))) {
            // "." and LOOKUP are walked (the '@pure' pointer check), so `p.arr[0]` reaches `p`.
            f.phase = 1;
            return f.call(targetExpr.left);
        }
        return f.done(null);
    }

    /** Resolves f.toks in order into f.list. */
    public int stepExprList(CheckFrame f) {
        if (f.phase == 0) {
            f.list = new ArrayList<>();
            f.phase = 1;
        } else {
            f.list.add(f.got);
        }
        if (f.i < f.toks.size()) {
            return f.call(f.toks.get(f.i++));
        }
        return f.done(null);
    }

    /** checkIncrement for code that is not a frame itself. */
    public TypeCheckerModel.TypeInfo checkIncrement(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        return hub.driver.run(new CheckFrame(CheckFrame.INCREMENT, op, scope, func));
    }

    /** requireMutable for code that is not a frame itself. */
    public void requireMutable(Token targetExpr, TypeCheckerModel.TypeInfo targetType, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, Token at) {
        CheckFrame m = new CheckFrame(CheckFrame.REQUIRE_MUTABLE, targetExpr, scope, func);
        m.a = targetType;
        m.y = at;
        hub.driver.run(m);
    }

    /** The tracked slot key of a bare name, an `a.b` member path or `x[i]` (parens transparent); null for untrackable shapes, which are never moved. */
    public String slotKeyOf(Token root) {
        // work items: {Token} = evaluate it; {Integer op, String text} = combine the results below with it
        Deque<Object[]> work = new ArrayDeque<>();
        List<String> results = new ArrayList<>();   // value stack (null = untrackable)
        work.push(new Object[]{root, null});
        while (!work.isEmpty()) {
            Object[] item = work.pop();
            if (!(item[0] instanceof Token)) {
                if ((Integer) item[0] == 0) {   // `.member`
                    String left = results.remove(results.size() - 1);
                    results.add(left == null ? null : left + "." + item[1]);
                } else {                          // `[index]`
                    String idx = results.remove(results.size() - 1);
                    String left = results.remove(results.size() - 1);
                    results.add(left == null || idx == null ? null : left + "[" + idx + "]");
                }
                continue;
            }
            Token node = (Token) item[0];
            if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
                work.push(new Object[]{node.childs.get(0), null});
            } else if (node.type == TokenType.VARREF) {
                results.add(node.text);
            } else if (node.type == TokenType.OPERATOR && node.text.equals(".") && node.right.type == TokenType.VARREF) {
                work.push(new Object[]{0, node.right.text});
                work.push(new Object[]{node.left, null});
            } else if (node.type == TokenType.OPERATOR && node.text.equals("LOOKUP") && node.right != null
                    && !node.right.childs.isEmpty()) {
                // `x[i]` as a slot, so a proof granted for a specific element can be matched by a later access.
                // Both target and index must themselves be slots.
                work.push(new Object[]{1, null});
                work.push(new Object[]{node.right.childs.get(0), null});
                work.push(new Object[]{node.left, null});
            } else {
                results.add(null);
            }
        }
        return results.get(0);
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
    public String exprKeyOf(Token root) {
        // work items: {Token} = evaluate it; {Integer op, Object extra} = combine the results below with it
        Deque<Object[]> work = new ArrayDeque<>();
        List<String> results = new ArrayList<>();   // value stack (null = no key)
        work.push(new Object[]{root, null});
        while (!work.isEmpty()) {
            Object[] item = work.pop();
            if (!(item[0] instanceof Token) && item[0] != null) {
                int op = (Integer) item[0];
                if (op == 0) {            // unary minus
                    String uk = results.remove(results.size() - 1);
                    results.add(uk == null ? null : "(-" + uk + ")");
                } else if (op == 1) {     // member
                    String dk = results.remove(results.size() - 1);
                    results.add(dk == null ? null : dk + "." + item[1]);
                } else if (op == 2) {     // index
                    String ik = results.remove(results.size() - 1);
                    String lk = results.remove(results.size() - 1);
                    results.add(lk == null || ik == null ? null : lk + "[" + ik + "]");
                } else if (op == 3) {     // call: {name, argument count}
                    Object[] info = (Object[]) item[1];
                    int n = (Integer) info[1];
                    List<String> keys = new ArrayList<>(results.subList(results.size() - n, results.size()));
                    results.subList(results.size() - n, results.size()).clear();
                    StringBuilder sb = new StringBuilder((String) info[0]).append("(");
                    boolean ok = true;
                    for (int ai = 0; ai < n; ai++) {
                        if (keys.get(ai) == null) {
                            ok = false;
                            break;
                        }
                        sb.append(ai > 0 ? "," : "").append(keys.get(ai));
                    }
                    results.add(ok ? sb.append(")").toString() : null);
                } else {                  // binary arithmetic
                    String r = results.remove(results.size() - 1);
                    String l = results.remove(results.size() - 1);
                    results.add(l == null || r == null ? null : "(" + l + item[1] + r + ")");
                }
                continue;
            }
            Token node = (Token) item[0];
            if (node == null) {
                results.add(null);
                continue;
            }
            if (node.type == TokenType.DELINEATOR && node.text.equals("(") && node.childs != null && node.childs.size() == 1) {
                work.push(new Object[]{node.childs.get(0), null});
                continue;
            }
            if (node.type == TokenType.INTEGER || node.type == TokenType.FLOAT) {
                results.add(node.text);
                continue;
            }
            String slot = slotKeyOf(node);
            if (slot != null) {
                results.add(slot);
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.text.equals("-") && node.right == null && node.left != null) {
                work.push(new Object[]{0, null});
                work.push(new Object[]{node.left, null});
            } else if (node.type == TokenType.OPERATOR && node.text.equals(".") && node.left != null && node.right != null
                    && node.right.type == TokenType.VARREF) {
                work.push(new Object[]{1, node.right.text});
                work.push(new Object[]{node.left, null});
            } else if (node.type == TokenType.OPERATOR && node.text.equals("LOOKUP") && node.left != null && node.right != null
                    && !node.right.childs.isEmpty()) {
                work.push(new Object[]{2, null});
                work.push(new Object[]{node.right.childs.get(0), null});
                work.push(new Object[]{node.left, null});
            } else if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left != null
                    && node.left.type == TokenType.VARREF && node.right != null) {
                // a plain call `f(a, b)` keyed by name and argument keys (for 'assume match f(x) : finite')
                List<Token> callArgs = new ArrayList<>();
                if (!node.right.childs.isEmpty()) {
                    hub.locksAndProofs.collectCommaArgs(node.right.childs.get(0), callArgs);
                }
                work.push(new Object[]{3, new Object[]{node.left.text, callArgs.size()}});
                for (int ai = callArgs.size() - 1; ai >= 0; ai--) {
                    work.push(new Object[]{callArgs.get(ai), null});
                }
            } else if (node.type == TokenType.OPERATOR && node.left != null && node.right != null
                    && java.util.Arrays.asList("+", "-", "*", "/", "%").contains(node.text)) {
                work.push(new Object[]{4, node.text});
                work.push(new Object[]{node.right, null});
                work.push(new Object[]{node.left, null});
            } else {
                results.add(null);
            }
        }
        return results.get(0);
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
        return hub.driver.run(new CheckFrame(CheckFrame.ASSIGN, op, scope, func));
    }

    /**
     * `=`: phases 0 start; 10 `let static` RHS done; 20 plain `let` RHS done; 30 assignment target done; 31 RHS done; 32 target mutability checked.
     * f.q = saved RVO flag, f.s1 = revival slot key, f.a = target type, f.p = the RHS is a bare enum variant.
     */
    public int stepAssign(CheckFrame f) {
        Token op = f.node;
        TypeCheckerModel.Scope scope = f.scope;
        TypeCheckerModel.FuncInfo func = f.func;
        switch (f.phase) {
            case 0:
                rejectDerefAsWriteTarget(op.left, "an assignment");
                if (op.left.type == TokenType.KEYWORD && op.left.text.equals("let")) {
                    if (op.left.isStatic) {
                        if (!op.left.staticApproved) {
                            throw new CompilerException("type", op.left.file, op.left.line,
                                    "'let static' is only allowed at the top of a function body, before any "
                                            + "other statement");
                        }
                        checkNoRuntimeConstructs(op.right);
                        // Compile-time constant: resolved in an empty Scope, so any variable reference fails as undeclared.
                        f.phase = 10;
                        return f.call(new CheckFrame(CheckFrame.EXPR, op.right, new TypeCheckerModel.Scope(null), func));
                    }
            // A plain 'let' RHS is always an eligible struct-RVO destination; checkCall decides whether it is such a call.
            f.q = hub.model.insideStructRvoDestinationPosition;
            hub.model.insideStructRvoDestinationPosition = true;
                    f.phase = 20;
                    return f.call(op.right);
                }
                // A fresh assignment revives a moved slot; cleared before the left side is resolved so the move check does not reject it.
                f.s1 = slotKeyOf(op.left);
                if (f.s1 != null) {
                    scope.movedSlots.remove(f.s1);
                    hub.model.partlyMovedSlots.remove(f.s1);
                }
                hub.model.assignTargetVar = op.left;
                f.phase = 30;
                return f.call(op.left);
            case 10:
                return f.done(assignStaticLet(op, scope, f.got));
            case 20:
                hub.model.insideStructRvoDestinationPosition = f.q;
                return f.done(assignPlainLet(op, scope, func, f.got));
            case 30: {
                TypeCheckerModel.TypeInfo leftType = f.got;
                hub.model.assignTargetVar = null;
                f.a = leftType;
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
                f.p = isBareEnumVariantRight;
                // A struct-RVO call is eligible here only when the assignment target is a bare variable.
                f.q = hub.model.insideStructRvoDestinationPosition;
                hub.model.insideStructRvoDestinationPosition = op.left.type == TokenType.VARREF;
                f.phase = 31;
                return f.call(op.right);
            }
            case 31: {
                hub.model.insideStructRvoDestinationPosition = f.q;
                TypeCheckerModel.TypeInfo leftType = f.a;
                TypeCheckerModel.TypeInfo rightType = f.got;
                boolean isBareEnumVariantRight = f.p;
                if (!isBareEnumVariantRight && !hub.acceptsAdaptingLiteral(leftType, rightType, op.right)) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot assign '" + rightType.canonical() + "' to '" + leftType.canonical() + "'");
                }
                f.phase = 32;
                return f.call(requireMutableFrame(f, op.left, leftType, op));
            }
            default: {
                TypeCheckerModel.TypeInfo leftType = f.a;
                String leftSlotKeyForRevival = f.s1;
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
                return f.done(leftType);
            }
        }
    }

    private TypeCheckerModel.TypeInfo assignStaticLet(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.TypeInfo rhsType) {
        Token nameTok = op.left.childs.get(0);
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

    private TypeCheckerModel.TypeInfo assignPlainLet(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, TypeCheckerModel.TypeInfo rhsType) {
        Token nameTok = op.left.childs.get(0);
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
    public void checkNoRuntimeConstructs(Token root) {
        Deque<Token> work = new java.util.LinkedList<>();   // may hold null children
        work.push(root);
        while (!work.isEmpty()) {
            Token node = work.pop();
            if (node == null) {
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.text.equals("CALL")) {
                if (node.left != null && node.left.type == TokenType.VARREF && node.left.text.equals("sizeof")) {
                    // sizeof(T) is a compile-time constant (a struct's size is folded by the emitter from
                    // its real layout); its argument is a type, so there is nothing further to check.
                    continue;
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
            if (node.childs != null) {
                for (int i = node.childs.size() - 1; i >= 0; i--) {
                    work.push(node.childs.get(i));
                }
            }
            work.push(node.right);
            work.push(node.left);
        }
    }

    /**
         * Looks up a `const`, evaluating lazily and memoizing (cycle-checked via constsBeingEvaluated); returns a literal Token that
         * the VARREF case substitutes in place. See docs/TypeChecker.md, "Compile-time constants".
         */
    public Token resolveConstValue(String name, Token referencingNode) {
        return runConstantEvaluation(new Object[]{9, new Object[]{name, referencingNode}});
    }

    /** Evaluates a const expression (literals, other consts, operators) to a literal Token, mirroring resolveOperatorType's dispatch; anything else throws. */
    public Token evaluateConstant(Token node) {
        return runConstantEvaluation(new Object[]{node, null});
    }

    /**
     * The constant folder as one loop. Work items: {Token, null} = evaluate it; {Integer op, payload} = combine the values below.
     * Ops: 0 const finished {name}, 2 unary minus {op}, 3 arithmetic {op}, 4 `!` {op}, 5 `&&`/`||` {op}, 6 comparison {op}, 9 const reference {name, referencing node}.
     */
    public Token runConstantEvaluation(Object[] first) {
        Deque<Object[]> work = new ArrayDeque<>();
        List<Token> values = new ArrayList<>();
        List<String> pending = new ArrayList<>();   // consts this call has started and not finished (so a throw leaves none marked)
        work.push(first);
        try {
            while (!work.isEmpty()) {
                Object[] item = work.pop();
                if (item[0] instanceof Token || item[0] == null) {
                    Token node = (Token) item[0];
                    switch (node.type) {
                        case INTEGER:
                        case FLOAT:
                        case BOOL:
                        case CHAR:
                        case STRING:
                        case NULL:
                            values.add(node);
                            break;
                        case VARREF:
                            if (!hub.model.constExprs.containsKey(node.text)) {
                                throw new CompilerException("type", node.file, node.line,
                                        "'" + node.text + "' is not a known const -- a compile-time constant "
                                                + "expression can only reference literals and other consts, never a "
                                                + "variable");
                            }
                            work.push(new Object[]{9, new Object[]{node.text, node}});
                            break;
                        case OPERATOR:
                            scheduleConstantOperator(node, work);
                            break;
                        default:
                            throw new CompilerException("type", node.file, node.line,
                                    "'" + node.text + "' cannot appear in a compile-time constant expression");
                    }
                    continue;
                }
                int op = (Integer) item[0];
                if (op == 9) {
                    Object[] ref = (Object[]) item[1];
                    String name = (String) ref[0];
                    Token referencingNode = (Token) ref[1];
                    Token cached = hub.model.constResolvedValues.get(name);
                    if (cached != null) {
                        values.add(cached);
                        continue;
                    }
                    if (hub.model.constsBeingEvaluated.contains(name)) {
                        throw new CompilerException("type", referencingNode.file, referencingNode.line,
                                "'const " + name + "' is circularly defined (directly or indirectly references "
                                        + "its own value)");
                    }
                    hub.model.constsBeingEvaluated.add(name);
                    pending.add(name);
                    work.push(new Object[]{0, name});
                    work.push(new Object[]{hub.model.constExprs.get(name), null});
                } else if (op == 0) {
                    String name = (String) item[1];
                    hub.model.constsBeingEvaluated.remove(name);
                    pending.remove(name);
                    hub.model.constResolvedValues.put(name, values.get(values.size() - 1));
                } else if (op == 2) {
                    Token operand = values.remove(values.size() - 1);
                    values.add(evaluateConstantUnaryMinus((Token) item[1], operand));
                } else if (op == 4) {
                    Token opTok = (Token) item[1];
                    Token operand = values.remove(values.size() - 1);
                    requireConstBool(operand, opTok);
                    values.add(new Token(TokenType.BOOL, String.valueOf(!Boolean.parseBoolean(operand.text)), opTok.line, opTok.file));
                } else {
                    Token opTok = (Token) item[1];
                    Token right = values.remove(values.size() - 1);
                    Token left = values.remove(values.size() - 1);
                    if (op == 3) {
                        values.add(evaluateConstantArithmetic(opTok, left, right));
                    } else if (op == 5) {
                        requireConstBool(left, opTok);
                        requireConstBool(right, opTok);
                        boolean l = Boolean.parseBoolean(left.text);
                        boolean r = Boolean.parseBoolean(right.text);
                        boolean result = opTok.text.equals("&&") ? (l && r) : (l || r);
                        values.add(new Token(TokenType.BOOL, String.valueOf(result), opTok.line, opTok.file));
                    } else {
                        values.add(evaluateConstantComparison(opTok, left, right));
                    }
                }
            }
        } finally {
            for (String name : pending) {
                hub.model.constsBeingEvaluated.remove(name);
            }
        }
        return values.get(0);
    }

    /** Pushes the work for one operator node of a constant expression (operands first, then the combining item). */
    public void scheduleConstantOperator(Token op, Deque<Object[]> work) {
        if (op.text.equals("CALL")) {
            throw new CompilerException("type", op.file, op.line,
                    "a compile-time constant expression cannot call a function");
        }
        if (op.text.equals("mut") || op.text.equals("imut")) {
            // Mutability never affects a literal's own value -- pass
            // straight through to whatever's wrapped.
            work.push(new Object[]{op.left, null});
            return;
        }
        if (op.text.equals("+") || op.text.equals("-") || op.text.equals("*") || op.text.equals("/")
                || op.text.equals("%")) {
            if (op.unary) {
                work.push(new Object[]{2, op});
                work.push(new Object[]{op.left, null});
                return;
            }
            work.push(new Object[]{3, op});
        } else if (op.text.equals("!")) {
            work.push(new Object[]{4, op});
            work.push(new Object[]{op.left, null});
            return;
        } else if (op.text.equals("&&") || op.text.equals("||")) {
            work.push(new Object[]{5, op});
        } else if (op.text.equals("<") || op.text.equals(">") || op.text.equals("<=") || op.text.equals(">=")
                || op.text.equals("==") || op.text.equals("!=")) {
            work.push(new Object[]{6, op});
        } else {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' cannot appear in a compile-time constant expression");
        }
        work.push(new Object[]{op.right, null});
        work.push(new Object[]{op.left, null});
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

    public Token evaluateConstantUnaryMinus(Token op, Token operand) {
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

    public Token evaluateConstantArithmetic(Token op, Token leftTok, Token rightTok) {
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

    public Token evaluateConstantComparison(Token op, Token leftTok, Token rightTok) {
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
        return hub.driver.run(new CheckFrame(CheckFrame.COMPOUND_ASSIGN, op, scope, func));
    }

    public int stepCompoundAssign(CheckFrame f) {
        Token op = f.node;
        TypeCheckerModel.Scope scope = f.scope;
        TypeCheckerModel.FuncInfo func = f.func;
        switch (f.phase) {
            case 0:
                rejectDerefAsWriteTarget(op.left, "a compound assignment");
                f.phase = 1;
                return f.call(op.left);
            case 1:
                f.a = f.got;
                requireNotAtomicOrdinaryWriteTarget(f.a, op, op.text);
                f.phase = 2;
                return f.call(op.right);
            case 2: {
                TypeCheckerModel.TypeInfo leftType = f.a;
                TypeCheckerModel.TypeInfo rightType = f.got;
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
                f.phase = 3;
                return f.call(requireMutableFrame(f, op.left, leftType, op));
            }
            default: {
                TypeCheckerModel.TypeInfo leftType = f.a;
                requireNotLoopImmutable(op.left, scope, op);
                if (op.left.type == TokenType.OPERATOR && op.left.text.equals("LOOKUP")) {
                    hub.accessAndAsync.requireLookupWriteAuthorized(op.left, scope);
                }
                invalidateMatchPatternsForWrite(op.left, scope);
                return f.done(leftType);
            }
        }
    }


    /** The type of `l op r` once both operands are resolved. */
    public TypeCheckerModel.TypeInfo arithmeticType(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.TypeInfo leftType, TypeCheckerModel.TypeInfo rightType) {
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

    public TypeCheckerModel.TypeInfo unaryMinusType(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.TypeInfo operand) {
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

    public TypeCheckerModel.TypeInfo comparisonType(Token op, TypeCheckerModel.Scope scope, TypeCheckerModel.TypeInfo leftType, TypeCheckerModel.TypeInfo rightType) {
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

package caspien.bytecodeemitter;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;
import caspien.typechecker.TypeCheckerModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import caspien.typechecker.ExternInfo;
import caspien.typechecker.FuncInfo;
import caspien.typechecker.StructInfo;
import caspien.typechecker.TypeInfo;
import caspien.bytecode.Text;

/**
 * Final front-end stage: walks the type-checked tree and emits the stack-based higher-order bytecode (HOB) as text.
 * Instruction shapes, if/elseif chaining and the ++ / compound-assignment desugaring: see docs/BytecodeEmitter.md, "Instruction shapes".
 */

/** BytecodeEmitterOperations: assignment, ownership moves, arithmetic, comparisons, calls and arguments (part 3 of 4; see README.md in this folder). */
final class BytecodeEmitterOperations {
    private final BytecodeEmitter hub;

    BytecodeEmitterOperations(BytecodeEmitter hub) {
        this.hub = hub;
    }

    /**
     * Before an owning assignment overwrites its target, destructs the old value (gt_destruct is null-safe); never for a fresh 'let'.
     * Result (`result` of the finished frame): true when the target has no flat name: its address is then on the stack (DUP_TOP + GT_DESTRUCT_ADDR)
     * and the assignment must not compute it again. See docs/BytecodeEmitter.md, "Moves and owning assignment". Frame: i = 1 (inline struct drop) or 2 (destruct) once the address was pushed.
     */
    public int stepDestructOld(EmitFrame f) {
        Token target = f.node;
        if (f.phase == 0) {
            if (target.type == TokenType.KEYWORD || target.chk.resolvedType == null
                    || !(target.chk.resolvedType.startsWith("owns") || target.chk.inlineOwnsStruct != null)) {
                f.result = false;
                return EmitFrame.DONE;
            }
            if (target.chk.inlineOwnsStruct != null && !hub.statements.isFlatOwnsDestructTarget(target)) {
                // an inline owning struct reached through a pointer or an index: drop the members of the value at the computed address
                f.i = 1;
                f.phase = 1;
                return f.callTarget(target);
            }
            if (target.type == TokenType.VARREF) {
                hub.core.requireGhostTableFunctionPresent("gt_destruct", target);
                hub.core.line("GT_DESTRUCT " + target.text);
                f.result = false;
                return EmitFrame.DONE;
            }
            if (target.type == TokenType.OPERATOR && target.text.equals(".") && hub.isQualifiedNameableDot(target)) {
                hub.core.requireGhostTableFunctionPresent("gt_destruct", target);
                hub.core.line("GT_DESTRUCT " + hub.qualifiedDotName(target));
                f.result = false;
                return EmitFrame.DONE;
            }
            if (target.type == TokenType.OPERATOR && (target.text.equals(".") || target.text.equals("LOOKUP"))) {
                f.i = 2;
                f.phase = 1;
                return f.callTarget(target);
            }
            f.result = false; // unreached today -- no other assignment-target shape exists
            return EmitFrame.DONE;
        }
        hub.core.line("DUP_TOP");
        hub.core.requireGhostTableFunctionPresent("gt_destruct", target);
        hub.core.line((f.i == 1 ? "GT_DROP_ADDR " : "GT_DESTRUCT_ADDR ") + target.chk.resolvedType);
        f.result = true;
        return EmitFrame.DONE;
    }

    /** Frame slots: b1 = lateDestruct, b2 = target address already on the stack, s1 = hidden local holding the early-evaluated right side (or null). */
    public int stepAssign(EmitFrame f) {
        Token op = f.node;
        while (true) {
            switch (f.phase) {
                case 0: {
                    if (unwrapMutWrappers(op.right).chk.isStructRvoCall) {
                        return f.tail(new EmitFrame(EmitFrame.RVO_ASSIGN, op));
                    }
                    // Flat-named owns target: destruct AFTER the right side ("s = pass(s)" moves the old value into the call, nulling the variable).
                    f.b1 = hub.statements.isFlatOwnsDestructTarget(op.left);
                    // Pointer/index-reached owns target (no flat name): the right side is evaluated FIRST into a hidden local, so a move out of
                    // the old value nulls it and a throw leaves it untouched.
                    boolean pointerCrossingOwns = !f.b1 && hub.core.lateAllocs != null && op.right.chk.resolvedType != null
                            && (op.right.chk.resolvedType.startsWith("owns") || op.left.chk.inlineOwnsStruct != null)
                            && hub.statements.isPointerCrossingOwnsTarget(op.left);
                    f.s1 = null;
                    f.b2 = false;
                    f.phase = 2;
                    if (pointerCrossingOwns) {
                        String rightType = op.left.chk.inlineOwnsStruct != null ? op.left.chk.resolvedType : op.right.chk.resolvedType;
                        f.s1 = hub.declareHiddenLocal(rightType);
                        hub.core.line("ADDR " + f.s1 + " " + rightType);
                        f.phase = 1;
                        return f.callExpr(op.right);
                    }
                    continue;
                }
                case 1: {
                    String rightType = op.left.chk.inlineOwnsStruct != null ? op.left.chk.resolvedType : op.right.chk.resolvedType;
                    hub.core.line("ASSIGN " + rightType + " " + rightType + " " + rightType);
                    f.phase = 2;
                    continue;
                }
                case 2:
                    if (!f.b1) {
                        f.phase = 3;
                        return f.call(new EmitFrame(EmitFrame.DESTRUCT_OLD, op.left));
                    }
                    f.phase = 4;
                    continue;
                case 3:
                    f.b2 = f.gotFrame.result;
                    f.phase = 4;
                    continue;
                case 4:
                    f.phase = 5;
                    if (!f.b2) {
                        return f.callTarget(op.left);
                    }
                    continue;
                case 5:
                    f.phase = 7;
                    if (f.s1 != null) {
                        hub.core.line("PUSH " + f.s1 + " " + (op.left.chk.inlineOwnsStruct != null ? op.left.chk.resolvedType : op.right.chk.resolvedType));
                        continue;
                    }
                    return f.callExpr(op.right);
                case 7:
                    if (f.b1) {
                        f.phase = 8;
                        return f.call(new EmitFrame(EmitFrame.DESTRUCT_OLD, op.left));
                    }
                    f.phase = 9;
                    continue;
                case 8:
                    f.phase = 9;
                    continue;
                case 9: {
                    String assignMnemonic = BytecodeEmitterCore.isAtomicCanonical(op.left.chk.resolvedType) ? "ATOMIC_ASSIGN" : "ASSIGN";
                    hub.core.line(assignMnemonic + " " + op.left.chk.resolvedType + " " + op.right.chk.resolvedType + " " + op.chk.resolvedType);
                    f.phase = 10;
                    if (op.right.chk.isOwnershipMoveSource) {
                        return f.call(hub.statements.nullOutFrame(op.right));
                    }
                    return EmitFrame.DONE;
                }
                default:
                    return EmitFrame.DONE;
            }
        }
    }

    /** Strips transparent mut/imut wrappers (and a non-block try) to find the underlying node, e.g. the struct-RVO CALL. */
    public Token unwrapMutWrappers(Token t) {
        while (true) {
            if (t.type == TokenType.OPERATOR && (t.text.equals("mut") || t.text.equals("imut"))) {
                t = t.left;
            } else if (t.type == TokenType.KEYWORD && t.text.equals("try") && !t.isTryBlock
                    && t.sub != null && !t.sub.isEmpty()) {
                // the isStructRvoCall flag travels through try; emitStructRvoCallSequence re-attaches the try's catch label (ownerTryOf)
                t = t.operand();
            } else {
                return t;
            }
        }
    }

    /** The non-block `try` token (of the function being emitted) whose directly wrapped call is `callNode`, or null. */
    public Token ownerTryOf(Token callNode) {
        for (Token tryTok : hub.core.tryCatchLabels.keySet()) {
            if (!tryTok.isTryBlock && tryTok.sub != null && !tryTok.sub.isEmpty() && tryTok.sub.get(0) == callNode) {
                return tryTok;
            }
        }
        return null;
    }

    /** Caller half of struct RVO: the callee gets the destination's address as a hidden first argument; no PUSH_RET/ASSIGN copy. See docs/BytecodeEmitter.md, "Return and struct return (RVO)". Frame: s1 = destination variable, s2 = its type, s3 = hidden local. */
    public int stepRvoAssign(EmitFrame f) {
        Token op = f.node;
        if (f.phase == 0) {
            Token callOp = unwrapMutWrappers(op.right);
            String funcName = resolveCallTargetForEmission(callOp.chk.resolvedCallTarget);
            FuncInfo targetInfo = hub.core.findFuncInfoByMangledName(funcName);
            String structName = targetInfo != null ? targetInfo.returnType.baseType
                    : op.left.chk.resolvedType.substring(op.left.chk.resolvedType.lastIndexOf('_') + 1);
            String destVarName = destVarNameOf(op.left);
            String destType = op.left.chk.resolvedType;
            String rawPtrType = "raw_indeterminate_" + structName;
            if (op.left.chk.inlineOwnsStruct != null && hub.core.lateAllocs != null) {
                // The destination already owns memory (an inline struct with owns members): the callee builds the new value in a
                // hidden local, then the old members are dropped and the value is copied over. A callee that throws leaves the
                // destination untouched, and an argument that moves a member out of it (`h = f(h.w)`) has already nulled it.
                String hidden = hub.declareHiddenLocal(destType);
                f.s1 = destVarName;
                f.s2 = destType;
                f.s3 = hidden;
                f.phase = 1;
                return f.call(rvoCallFrame(callOp, rawPtrType, Arrays.asList(
                        "PUSH " + hidden + " " + destType, "ADDR_OF RAW " + destType + " " + rawPtrType)));
            }
            // no PUSH_RET/ASSIGN: the callee already wrote the destination
            return f.tail(rvoCallFrame(callOp, rawPtrType, Arrays.asList(
                    "PUSH " + destVarName + " " + destType, "ADDR_OF RAW " + destType + " " + rawPtrType)));
        }
        hub.core.requireGhostTableFunctionPresent("gt_destruct", op.left);
        hub.core.line("GT_DESTRUCT " + f.s1);
        hub.core.line("ADDR " + f.s1 + " " + f.s2);
        hub.core.line("PUSH " + f.s3 + " " + f.s2);
        hub.core.line("ASSIGN " + f.s2 + " " + f.s2 + " " + f.s2);
        return EmitFrame.DONE;
    }

    /**
     * Shared RVO call site: arguments are transferred as a call sequence would, with the hidden destination pointer (destLines: the lines that push it)
     * in register slot 0. "return foo()" tail forwarding uses this function's own $ret_dest as destination (no address-of, no copy).
     * The staging write happens now; the returned CALL_SEQUENCE frame emits the rest.
     */
    public EmitFrame rvoCallFrame(Token callOp, String rawPtrType, List<String> destLines) {
        String funcName = resolveCallTargetForEmission(callOp.chk.resolvedCallTarget);
        List<Token> args = new ArrayList<>();
        Token argsDelineator = callOp.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        String conventionName = callOp.chk.resolvedCallConvention;
        Token ownerTry = ownerTryOf(callOp);
        if (ownerTry != null) {
            hub.core.pendingTryCatchLabel = hub.core.tryCatchLabels.get(ownerTry); // consumed by the staging write just below
        }
        hub.core.stageCallSiteForUnwind(callOp);
        hub.core.pendingTryCatchLabel = null;
        String mnemonic = funcName.equals(hub.core.currentFuncMangledName) ? "RECURSIVE_CALL" : "CALL";
        EmitFrame seq = callSequenceFrame(callOp, null, args, mnemonic + " " + funcName, null, conventionName, -1);
        seq.strs = destLines;
        seq.s4 = rawPtrType;
        return seq;
    }

    /** Name of the bare-variable destination (a 'let' binding or a plain assignment target). */
    public String destVarNameOf(Token target) {
        if (target.type == TokenType.KEYWORD && target.text.equals("let")) {
            return target.childs.get(0).text;
        }
        return target.text;
    }

    /** x += y desugars to the same bytecode as "x = x + y" -- no dedicated compound-assign opcode. */
    public EmitFrame compoundAssignFrame(Token op) {
        EmitFrame f = EmitFrame.script(op);
        f.target(op.left); // assign target
        f.expr(op.left);   // left operand of the arithmetic
        f.expr(op.right);  // right operand
        if (op.chk.pointerScale > 1) {
            // "p += n" on a raw pointer: n elements, i.e. n * sizeof(pointee) bytes.
            f.line(BytecodeEmitterCore.pointerScaleLine(op, "indeterminate_u64"));
            f.line("MUL " + op.right.chk.resolvedType + " indeterminate_u64 " + op.right.chk.resolvedType);
        }
        String arithOp = BytecodeEmitterCore.COMPOUND_TO_ARITH.get(op.text);
        f.line(BytecodeEmitterCore.ARITH_NAMES.get(arithOp) + " " + op.left.chk.resolvedType + " " + op.right.chk.resolvedType
                + " " + op.left.chk.resolvedType);
        f.line("ASSIGN " + op.left.chk.resolvedType + " " + op.left.chk.resolvedType + " " + op.chk.resolvedType);
        return f;
    }

    public EmitFrame binaryArithFrame(Token op) {
        if (op.chk.pointerArithKind != 0) {
            return pointerArithFrame(op);
        }
        EmitFrame f = EmitFrame.script(op);
        f.expr(op.left);
        f.expr(op.right);
        f.line(BytecodeEmitterCore.ARITH_NAMES.get(op.text) + " " + op.left.chk.resolvedType + " " + op.right.chk.resolvedType
                + " " + op.chk.resolvedType);
        return f;
    }

    /** C-style pointer arithmetic: p +/- n and n + p scale n by the pointee size; p - q is (p - q) / sizeof(pointee), signed. */
    public EmitFrame pointerArithFrame(Token op) {
        String l = op.left.chk.resolvedType;
        String r = op.right.chk.resolvedType;
        String arith = BytecodeEmitterCore.ARITH_NAMES.get(op.text);
        EmitFrame f = EmitFrame.script(op);
        if (op.chk.pointerArithKind == 3) {
            f.expr(op.left);
            f.expr(op.right);
            f.line(arith + " " + l + " " + r + " indeterminate_s64");
            if (op.chk.pointerScale > 1) {
                f.line(BytecodeEmitterCore.pointerScaleLine(op, "indeterminate_s64"));
                f.line("DIV indeterminate_s64 indeterminate_s64 indeterminate_s64");
            }
            return f;
        }
        f.expr(op.left);
        if (op.chk.pointerArithKind == 2 && op.chk.pointerScale > 1) {
            f.line(BytecodeEmitterCore.pointerScaleLine(op, "indeterminate_u64"));
            f.line("MUL " + l + " indeterminate_u64 " + l);
        }
        f.expr(op.right);
        if (op.chk.pointerArithKind == 1 && op.chk.pointerScale > 1) {
            f.line(BytecodeEmitterCore.pointerScaleLine(op, "indeterminate_u64"));
            f.line("MUL " + r + " indeterminate_u64 " + r);
        }
        f.line(arith + " " + l + " " + r + " " + op.chk.resolvedType);
        return f;
    }

    public EmitFrame unaryMinusFrame(Token op) {
        EmitFrame f = EmitFrame.script(op);
        // A negated integer literal is one constant: a single `PUSH -N` (so it also stays a plain PUSH inside a
        // packed array / struct construction run, which any other instruction between pushes would break).
        if (op.left.type == TokenType.INTEGER && op.left.forcedLiteralType == null && Text.isDigits(op.left.text)) {
            String digits = Text.stripLeadingZeros(op.left.text);
            f.line("PUSH " + (digits.equals("0") ? "0" : "-" + digits) + " " + op.chk.resolvedType);
            return f;
        }
        f.expr(op.left);
        f.line("NEG " + op.left.chk.resolvedType + " " + op.chk.resolvedType);
        return f;
    }

    public EmitFrame logicalNotFrame(Token op) {
        EmitFrame f = EmitFrame.script(op);
        f.expr(op.left);
        f.line("NOT " + op.left.chk.resolvedType + " " + op.chk.resolvedType);
        return f;
    }

    /** x++ / x-- desugar to the same bytecode shape as "x = x .INC": push target, push value, INC (DEC), ASSIGN; on a raw pointer wider than a byte, p = p +/- sizeof(pointee) as C does. */
    public EmitFrame incDecFrame(Token op, boolean increment) {
        EmitFrame f = EmitFrame.script(op);
        if (op.chk.pointerScale > 1) {
            String t = op.left.chk.resolvedType;
            f.target(op.left);
            f.expr(op.left);
            f.line(BytecodeEmitterCore.pointerScaleLine(op, "indeterminate_u64"));
            f.line((increment ? "ADD" : "SUB") + " " + t + " indeterminate_u64 " + t);
            f.line("ASSIGN " + t + " " + t + " " + t);
            return f;
        }
        f.target(op.left); // assign target
        f.expr(op.left);   // value to change
        f.line((increment ? "INC " : "DEC ") + op.left.chk.resolvedType + " " + op.chk.resolvedType);
        f.line("ASSIGN " + op.left.chk.resolvedType + " " + op.chk.resolvedType + " " + op.chk.resolvedType);
        return f;
    }

    public EmitFrame logicalFrame(Token op, String name) {
        EmitFrame f = EmitFrame.script(op);
        f.expr(op.left);
        f.expr(op.right);
        f.line(name + " " + op.left.chk.resolvedType + " " + op.right.chk.resolvedType + " " + op.chk.resolvedType);
        return f;
    }

    /** True for a "&&then"/"||then" node specifically -- never a plain "&&"/"||". */
    public boolean isThenVariant(Token node) {
        return node.type == TokenType.OPERATOR && (node.text.equals("&&then") || node.text.equals("||then"));
    }

    public EmitFrame shortCircuitFrame(Token node, String falseLabel, String trueLabel) {
        EmitFrame f = new EmitFrame(EmitFrame.SHORT_CIRCUIT, node);
        f.s1 = falseLabel;
        f.s2 = trueLabel;
        return f;
    }

    /**
     * Cascading CMP/JMP lowering of "&&then"/"||then". Exactly one of falseLabel (s1) / trueLabel (s2) is non-null; the other outcome falls
     * through. No AND/OR is emitted. See docs/BytecodeEmitter.md, "Short-circuit conditions". Frame: s3 = the label an enclosing-jump case creates.
     */
    public int stepShortCircuit(EmitFrame f) {
        Token node = f.node;
        String falseLabel = f.s1;
        String trueLabel = f.s2;
        if (!isThenVariant(node)) {
            if (f.phase == 0) {
                // leaf: any ordinary expression (a plain &&/|| subtree stays eager); only its bool is tested
                f.phase = 1;
                return f.callExpr(node);
            }
            hub.core.line("CMP");
            if (falseLabel != null) {
                // "CMP; JMP label" = jump if false, fall through if true
                hub.core.line("JMP " + falseLabel);
            } else {
                // jump to trueLabel only if true: there is no jump-if-true opcode, so invert with an extra unconditional JMP
                String skipLabel = hub.statements.newLabel("short_circuit_leaf_skip");
                hub.core.line("JMP " + skipLabel);
                hub.core.line("JMP " + trueLabel);
                hub.core.line(skipLabel + ":");
            }
            return EmitFrame.DONE;
        }
        boolean isAnd = node.text.equals("&&then");
        // The four shapes differ only in the labels the two operands get and in what follows them.
        String leftFalse;
        String leftTrue;
        String rightFalse;
        String rightTrue;
        if (f.phase == 0) {
            if (isAnd && trueLabel != null) {
                f.s3 = hub.statements.newLabel("andthen_fail");   // left operand of an enclosing "||then": a failure lands past the jump to trueLabel
            } else if (!isAnd && falseLabel != null) {
                f.s3 = hub.statements.newLabel("orthen_pass");    // left true: skip the right side and fall through as true
            }
        }
        if (isAnd) {
            leftFalse = trueLabel == null ? falseLabel : f.s3;
            leftTrue = null;
            rightFalse = leftFalse;
            rightTrue = null;
        } else {
            leftFalse = null;
            leftTrue = falseLabel == null ? trueLabel : f.s3;
            rightFalse = falseLabel;
            rightTrue = falseLabel == null ? trueLabel : null;
        }
        switch (f.phase) {
            case 0:
                f.phase = 1;
                return f.call(shortCircuitFrame(node.left, leftFalse, leftTrue));
            case 1:
                f.phase = 2;
                return f.call(shortCircuitFrame(node.right, rightFalse, rightTrue));
            default:
                if (isAnd && trueLabel != null) {
                    hub.core.line("JMP " + trueLabel);
                    hub.core.line(f.s3 + ":");
                } else if (!isAnd && falseLabel != null) {
                    hub.core.line(f.s3 + ":");
                }
                return EmitFrame.DONE;
        }
    }

    public EmitFrame comparisonFrame(Token op) {
        EmitFrame f = EmitFrame.script(op);
        f.expr(op.left);
        f.expr(op.right);
        f.line(BytecodeEmitterCore.COMPARISON_NAMES.get(op.text) + " " + op.left.chk.resolvedType + " " + op.right.chk.resolvedType
                + " " + op.chk.resolvedType);
        return f;
    }

    /** "a swap b": ATOMIC_SWAP, a read-modify-write; the left operand is pushed as an address, the result is the previous value. */
    public EmitFrame swapFrame(Token op) {
        // ATOMIC_SWAP consumes an ADDRESS (xchg through it); pushing the field's value worked only for statics and segfaulted for a swap-lock struct in a local
        EmitFrame f = EmitFrame.script(op);
        f.target(op.left);
        f.expr(op.right);
        f.line("ATOMIC_SWAP " + op.left.chk.resolvedType + " " + op.right.chk.resolvedType + " " + op.chk.resolvedType);
        return f;
    }

    /**
     * A call, or a builtin dispatched by reserved name: lines that precede the first nested emission are written now, the rest is the returned frame
     * (null when the whole thing was lines).
     */
    public EmitFrame callOrBuiltinFrame(Token op) {
        if (op.chk.isSleepCall) {
            return sleepCallFrame(op);
        }
        if ("AWAIT".equals(op.chk.asyncCallKind) || "PAR".equals(op.chk.asyncCallKind)) {
            return new EmitFrame(EmitFrame.ASYNC, op);
        }
        if (op.left.type == TokenType.VARREF) {
            switch (op.left.text) {
                case "sizeof":
                    // Direct PUSH; a struct size stays symbolic (SIZEOF) until SizeofResolutionPass.
                    if (op.chk.sizeofStruct != null) {
                        hub.core.line("SIZEOF " + op.chk.sizeofStruct + " " + op.chk.resolvedType);
                    } else {
                        hub.core.line("PUSH " + op.chk.builtinConstantValue + " " + op.chk.resolvedType);
                    }
                    return null;
                case "len": {
                    List<Token> lenArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), lenArgs);
                    if (lenArgs.size() == 2) {
                        // Unsafe 2-arg terminator scan: never foldable, own LEN_SCAN mnemonic.
                        Token targetArg = lenArgs.get(0);
                        Token termArg = lenArgs.get(1);
                        return EmitFrame.script(op).expr(targetArg).expr(termArg)
                                .line("LEN_SCAN " + targetArg.chk.resolvedType + " " + termArg.chk.resolvedType);
                    } else if (op.chk.builtinConstantValue != null) {
                        // Fixed array / string literal: folded to a PUSH.
                        hub.core.line("PUSH " + op.chk.builtinConstantValue + " " + op.chk.resolvedType);
                        return null;
                    }
                    // Safe dynarray: run-time length, read by LEN.
                    return EmitFrame.script(op).expr(singleBuiltinArg(op)).line("LEN");
                }
                case "range": {
                    // A range is two u64 words; start is always literal 0.
                    if (op.chk.builtinConstantValue != null) {
                        // String literal / fixed array: constant bounds, no RANGE instruction.
                        hub.core.line("PUSH 0 imut_u64");
                        hub.core.line("PUSH " + op.chk.builtinConstantValue + " imut_u64");
                        return null;
                    }
                    // Safe dynarray: length read by LEN.
                    hub.core.line("PUSH 0 imut_u64");
                    return EmitFrame.script(op).expr(singleBuiltinArg(op)).line("LEN");
                }
                case "deref":
                    // DEREF carries the pointee type because it is the copy width (the pushed pointer is always 8 bytes).
                    return EmitFrame.script(op).expr(singleBuiltinArg(op)).line("DEREF " + op.chk.resolvedType);
                case "clone":
                    return new EmitFrame(EmitFrame.CLONE, op);
                case "Some":
                    // Same GT_ALIVE_CHECK sequence as a match condition; both restrictions apply in any expression position.
                    if (hub.core.inGtSuppressedContext) {
                        throw new CompilerException("type", op.file, op.line,
                                "'Some(...)' (which needs '@gt_alive_check') can't be used here -- this "
                                        + "function is a ghost-table-decorated function, or is reachable "
                                        + "from one, and ghost-table instructions can never be generated "
                                        + "in that whole reachable set");
                    }
                    if (op.chk.someOnRef) {
                        return hub.statements.someOnRefFrame(op, singleBuiltinArg(op), op.chk.someProofKey, true);
                    }
                    hub.core.requireGhostTableFunctionPresent("gt_alive_check", op);
                    return EmitFrame.script(op).expr(singleBuiltinArg(op)).line("GT_ALIVE_CHECK");
                case "wrap":
                case "sat":
                    return hub.convertFrame(op);
                case "insecure_rand":
                    // rand() through the shared call sequence: no ExternInfo, default convention.
                    return callSequenceFrame(op, null, new ArrayList<>(), "EXTERN_CALL rand 0", "mut_u64",
                            hub.core.checker.model.getConfig().defaultConvention, -1);
                case "bits_not": {
                    // Unary: one operand, one instruction "BITS_NOT argType resultType" (the shape NOT/NEG already have).
                    List<Token> notArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), notArgs);
                    return EmitFrame.script(op).expr(notArgs.get(0))
                            .line("BITS_NOT " + notArgs.get(0).chk.resolvedType + " " + op.chk.resolvedType);
                }
                case "bits_left": case "bits_right": case "bits_rotl": case "bits_rotr": case "bits_or": case "bits_and": case "bits_xor": {
                    // Binary shape like ADD: both operands, then "OP leftType rightType resultType".
                    List<Token> bitsArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), bitsArgs);
                    Token bitsLeft = bitsArgs.get(0);
                    Token bitsRight = bitsArgs.get(1);
                    String bitsOpName;
                    switch (op.left.text) {
                        case "bits_left": bitsOpName = "SHL"; break;
                        case "bits_right": bitsOpName = "SHR"; break;
                        case "bits_rotl": bitsOpName = "ROTL"; break;
                        case "bits_rotr": bitsOpName = "ROTR"; break;
                        case "bits_and": bitsOpName = "BITS_AND"; break;
                        case "bits_xor": bitsOpName = "BITS_XOR"; break;
                        default: bitsOpName = "BITS_OR"; break;
                    }
                    return EmitFrame.script(op).expr(bitsLeft).expr(bitsRight)
                            .line(bitsOpName + " " + bitsLeft.chk.resolvedType + " " + bitsRight.chk.resolvedType + " " + op.chk.resolvedType);
                }
                case "memcopy": {
                    // Push all arguments, then MEMCOPY with the operand types.
                    List<Token> memcopyArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), memcopyArgs);
                    EmitFrame f = EmitFrame.script(op);
                    for (Token arg : memcopyArgs) {
                        f.expr(arg);
                    }
                    return f.line("MEMCOPY " + memcopyArgs.get(0).chk.resolvedType + " " + memcopyArgs.get(1).chk.resolvedType
                            + " " + memcopyArgs.get(2).chk.resolvedType + " " + op.chk.resolvedType);
                }
                case "dyn":
                    return new EmitFrame(EmitFrame.DYN, op);
                case "resize":
                    return new EmitFrame(EmitFrame.RESIZE, op);
                case "call": {
                    // PUSH fnptr, args, INVOKE n (n excludes the pointer); the pointer is not an ARGn word.
                    List<Token> callArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), callArgs);
                    Token funcPtrArg = callArgs.get(0);
                    List<Token> realArgs = callArgs.subList(1, callArgs.size());
                    return callSequenceFrame(op, funcPtrArg, realArgs, "INVOKE " + op.chk.invokeArgCount, voidSafeReturnType(op),
                            op.chk.resolvedCallConvention, -1);
                }
                default:
                    break;
            }
        }
        if (op.left.type == TokenType.VARREF && hub.core.checker.declarations.getExterns().containsKey(op.left.text)) {
            return externCallFrame(op, hub.core.checker.declarations.getExterns().get(op.left.text));
        }
        return callFrame(op);
    }

    public Token singleBuiltinArg(Token op) {
        List<Token> args = new ArrayList<>();
        collectCommaArgs(op.right.childs.get(0), args);
        return args.get(0);
    }

    /** clone(x): the pending catch label is held back while the operand is emitted (a nested call would consume it), then CLONE, GT_REGISTER and the failure check. Frame: s1 = held label, t1 = operand. */
    public int stepClone(EmitFrame f) {
        Token op = f.node;
        if (f.phase == 0) {
            // Hold back the pending catch label: a nested call would consume it (see "Allocation failure check").
            f.s1 = hub.core.pendingTryCatchLabel;
            hub.core.pendingTryCatchLabel = null;
            // CLONE carries argument and result types so lowering can size the pointee.
            f.t1 = singleBuiltinArg(op);
            f.phase = 1;
            return f.callExpr(f.t1);
        }
        hub.core.requireGhostTableFunctionPresent("gt_register", op);
        hub.core.line("CLONE " + f.t1.chk.resolvedType + " " + op.chk.resolvedType);
        // Same tail as `new`: a null result (failed malloc) jumps to the enclosing catch, otherwise
        // the copy is registered with the ghost table so `match Some` and the scope-end destruct work.
        hub.core.pendingTryCatchLabel = f.s1;
        hub.core.line("GT_REGISTER"); // before the check: a failed registration frees the block and leaves null
        hub.emitAllocFailureCheck(op);
        return EmitFrame.DONE;
    }

    /** dyn(text) / dyn([..]). Frame: s1 = held catch label, t1 = string operand or array literal, toks = literal elements, strs = their temporaries, strs2 = the previous dynLiteralTempNames. */
    public int stepDyn(EmitFrame f) {
        Token op = f.node;
        while (true) {
            switch (f.phase) {
                case 0: {
                    // Hold back the pending catch label (see "Allocation failure check").
                    f.s1 = hub.core.pendingTryCatchLabel;
                    hub.core.pendingTryCatchLabel = null;
                    if (op.chk.dynFromStringSource) {
                        // dyn(text): NEW_FROM_STRING copies the characters at run time.
                        f.t1 = singleBuiltinArg(op);
                        f.phase = 1;
                        return f.callExpr(f.t1);
                    }
                    // dyn([..]): push the elements, then NEW_DYN (safe, keeps count) or NEW_UDYN (unsafe, flat size); see docs/BytecodeEmitter.md, "dyn literals".
                    f.t1 = singleBuiltinArg(op);
                    f.toks = new ArrayList<>();
                    f.strs = new ArrayList<>();
                    f.phase = 3;
                    // `dyn:<T>([])` -- nothing to push for an empty literal.
                    if (!f.t1.childs.isEmpty()) {
                        f.strs2 = hub.core.dynLiteralTempNames;
                        hub.core.dynLiteralTempNames = f.strs;
                        f.phase = 2;
                        return f.callExpr(f.t1);
                    }
                    continue;
                }
                case 1:
                    hub.core.line("NEW_FROM_STRING " + op.chk.resolvedType.substring("owns_".length()) + " "
                            + f.t1.chk.resolvedType);
                    // Safe dyn(text) throws: same allocation failure check as new.
                    if (!op.chk.resolvedType.substring("owns_".length()).startsWith("indeterminate_unsafe_dynarray(")) {
                        hub.core.pendingTryCatchLabel = f.s1;
                        // The block start is registered with the ghost table (like `new`), so scope-end drop frees it;
                        // a failed registration frees the block and leaves null: the check below takes the OOM path.
                        hub.core.requireGhostTableFunctionPresent("gt_register", op);
                        hub.core.line("GT_REGISTER");
                        hub.emitAllocFailureCheck(op);
                    }
                    return EmitFrame.DONE;
                case 2:
                    hub.core.dynLiteralTempNames = f.strs2;
                    if (f.t1.type == TokenType.DELINEATOR && f.t1.text.equals("[")) {
                        collectCommaArgs(f.t1.childs.get(0), f.toks);
                    }
                    f.phase = 3;
                    continue;
                default: {
                    String typeText = op.chk.resolvedType.substring("owns_".length());
                    boolean isUnsafeLiteral = typeText.startsWith("indeterminate_unsafe_dynarray(");
                    String mnemonic = isUnsafeLiteral ? "NEW_UDYN" : "NEW_DYN";
                    hub.core.line(mnemonic + " " + typeText + " " + op.chk.builtinConstantValue);
                    // Safe dyn([..]) throws like new; NEW_UDYN stays non-throwing.
                    if (!isUnsafeLiteral) {
                        hub.core.pendingTryCatchLabel = f.s1;
                        hub.core.requireGhostTableFunctionPresent("gt_register", op);
                        hub.core.line("GT_REGISTER");
                        hub.emitAllocFailureCheck(op, f.toks, f.strs);
                    }
                    return EmitFrame.DONE;
                }
            }
        }
    }

    /**
     * resize(array, count..): takes ownership of the array and returns a new owns; RESIZE (safe) / URESIZE (unsafe). No CC bracket. see docs/BytecodeEmitter.md, "resize emission".
     * Frame: s1 = held catch label, t1 = array operand, toks = arguments, b1 = unsafe, b2 = null-out deferred, s2/s3/s4 = tailArr/tailCount/tailOld hidden locals, strs = temporaries, i = argument index.
     */
    public int stepResize(EmitFrame f) {
        Token op = f.node;
        while (true) {
            switch (f.phase) {
                case 0: {
                    // Hold back the pending catch label (see "Allocation failure check").
                    f.s1 = hub.core.pendingTryCatchLabel;
                    hub.core.pendingTryCatchLabel = null;
                    f.toks = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), f.toks);
                    f.t1 = f.toks.get(0);
                    f.b1 = f.t1.chk.resolvedType.startsWith("owns_mut_unsafe_dynarray(");
                    f.s2 = null;
                    f.s3 = null;
                    f.s4 = null;
                    f.phase = 3;
                    // A safe dynarray whose elements own memory: a shrink must drop the owns members of the elements it cuts
                    // off. The array pointer and the new count are evaluated once into hidden locals, the elements at index
                    // >= count are dropped (GT_DESTRUCT_TAIL, expanded by DropGlueGenerationPass), then the RESIZE runs on them.
                    if (!f.b1 && hub.core.lateAllocs != null) {
                        String dynType = f.t1.chk.resolvedType;
                        String elemText = dynType.substring(dynType.indexOf("dynarray(") + "dynarray(".length(), dynType.length() - 1);
                        if (hub.core.checker.declarations.elementTextOwnsMemory(elemText)) {
                            f.s2 = hub.declareHiddenLocal(dynType);
                            hub.core.line("ADDR " + f.s2 + " " + dynType);
                            f.phase = 1;
                            return f.callExpr(f.t1);
                        }
                    }
                    continue;
                }
                case 1: {
                    String dynType = f.t1.chk.resolvedType;
                    hub.core.line("ASSIGN " + dynType + " " + dynType + " " + dynType);
                    f.s3 = hub.declareHiddenLocal("mut_u64");
                    hub.core.line("ADDR " + f.s3 + " mut_u64");
                    f.phase = 2;
                    return f.callExpr(f.toks.get(1));
                }
                case 2: {
                    String dynType = f.t1.chk.resolvedType;
                    Token countArg = f.toks.get(1);
                    hub.core.line("ASSIGN mut_u64 " + countArg.chk.resolvedType + " mut_u64");
                    hub.core.line("GT_DESTRUCT_TAIL " + f.s2 + " " + dynType + " " + f.s3);
                    f.s4 = hub.declareHiddenLocal("mut_u64");
                    hub.core.line("ADDR " + f.s4 + " mut_u64");
                    hub.core.line("PUSH " + f.s2 + " " + dynType);
                    hub.core.line("LEN");
                    hub.core.line("ASSIGN mut_u64 indeterminate_u64 mut_u64");
                    hub.core.line("PUSH " + f.s2 + " " + dynType);
                    f.phase = 3;
                    continue;
                }
                case 3:
                    // A source that is a temporary (a call result) has no name to free if the realloc fails: it goes through a
                    // hidden local (like a temporary moved into `new`), which the failure branch destructs.
                    f.strs = new ArrayList<>();
                    if (f.s2 != null && hub.isOwnsTemporary(f.t1)) {
                        f.strs.add(f.s2);
                    }
                    f.phase = 4;
                    if (f.s2 == null) {
                        if (!f.b1 && hub.isOwnsTemporary(f.t1)) {
                            return f.call(hub.ownsTemporaryFrame(f.t1, f.strs));
                        }
                        return f.callExpr(f.t1);
                    }
                    continue;
                case 4:
                    // Safe resize: the source variable is only nulled once the realloc succeeded. When it fails the old block is
                    // still allocated and registered (realloc leaves it intact) but nothing else would ever reach it, so the
                    // failure branch frees it (like a failed `new` frees what was moved into it). Unsafe resize cannot fail.
                    f.b2 = !f.b1 && f.t1.chk.isOwnershipMoveSource;
                    f.i = 1;
                    f.phase = 5;
                    if (f.t1.chk.isOwnershipMoveSource && !f.b2) {
                        return f.call(hub.statements.nullOutFrame(f.t1));
                    }
                    continue;
                case 5:
                    if (f.i < f.toks.size()) {
                        if (f.i == 1 && f.s3 != null) {
                            hub.core.line("PUSH " + f.s3 + " mut_u64");
                            f.i++;
                            continue;
                        }
                        f.phase = 6;
                        return f.callExpr(f.toks.get(f.i));
                    }
                    f.phase = 7;
                    continue;
                case 6:
                    f.i++;
                    f.phase = 5;
                    continue;
                case 7: {
                    StringBuilder resizeLine = new StringBuilder(f.b1 ? "URESIZE " : "RESIZE ").append(f.t1.chk.resolvedType);
                    for (int k = 1; k < f.toks.size(); k++) {
                        resizeLine.append(' ').append(f.toks.get(k).chk.resolvedType);
                    }
                    hub.core.line(resizeLine.toString());
                    if (f.s4 != null) {
                        // growing: the new slots are bitwise copies of the moved-in fill value; give each its own deep clone
                        String arrType = f.t1.chk.resolvedType;
                        String filled = hub.declareHiddenLocal(arrType);
                        hub.core.line("POP " + filled + " " + arrType);
                        hub.core.line("CLONE_FILL " + filled + " " + arrType + " " + f.s4);
                        hub.core.line("PUSH " + filled + " " + arrType);
                    }
                    // Safe resize throws like new; URESIZE stays non-throwing.
                    if (!f.b1) {
                        hub.core.pendingTryCatchLabel = f.s1;
                        List<Token> resizeMoved = new ArrayList<>();
                        if (f.b2) {
                            resizeMoved.add(f.t1);
                        }
                        hub.emitAllocFailureCheck(op, resizeMoved, f.strs);
                        if (f.b2) {
                            f.phase = 8;
                            return f.call(hub.statements.nullOutFrame(f.t1));
                        }
                    }
                    return EmitFrame.DONE;
                }
                default:
                    return EmitFrame.DONE;
            }
        }
    }

    /**
     * Wraps one call in CC_START .. CC_END (first n arguments moved to ARGn registers) and adds PUSH_RET for a non-void result.
     * pushFuncPtr is non-null only for call(); regArgs are the register-transferred arguments; returnTypeOrNull null = void.
     * The convention is the one TypeChecker resolved, never re-derived. varargStartIndex: first true vararg of a variadic extern (-1 = none).
     * see docs/BytecodeEmitter.md, "Call sequence" and "Variadic calls".
     * Frame: t1 = pushFuncPtr, toks = regArgs, s1 = convention, s2 = the call instruction line, s3 = return type, n = varargStartIndex,
     * strs/s4 = (struct RVO only) the lines pushing the hidden destination and its pointer type, i = argument index.
     */
    public EmitFrame callSequenceFrame(Token at, Token pushFuncPtr, List<Token> regArgs, String callInstruction,
            String returnTypeOrNull, String conventionName, int varargStartIndex) {
        EmitFrame f = new EmitFrame(EmitFrame.CALL_SEQUENCE, at);
        f.t1 = pushFuncPtr;
        f.toks = regArgs;
        f.s1 = conventionName;
        f.s2 = callInstruction;
        f.s3 = returnTypeOrNull;
        f.n = varargStartIndex;
        return f;
    }

    public int stepCallSequence(EmitFrame f) {
        while (true) {
            switch (f.phase) {
                case 0:
                    f.counters = new ArgCounters(hub.core.checker.model.getConfig().callingConventions.get(f.s1));
                    hub.core.line("CC_START " + f.s1);
                    f.phase = 1;
                    if (f.t1 != null) {
                        return f.callExpr(f.t1);
                    }
                    continue;
                case 1:
                    if (f.strs != null) {
                        for (String destLine : f.strs) {
                            hub.core.line(destLine);
                        }
                        emitArgTransferTail(f.s4, f.counters, false);
                    }
                    f.i = 0;
                    f.phase = 2;
                    continue;
                case 2:
                    // The register index is a running counter advanced by the words each argument occupied (a range takes two); ArgCounters tracks the banks.
                    if (f.i < f.toks.size()) {
                        Token arg = f.toks.get(f.i);
                        boolean isTrueVararg = f.n >= 0 && f.i >= f.n;
                        f.phase = 3;
                        // an f32 in the "..." part of a variadic call is widened, any float gets win64's register duplication
                        return f.call(argTransferFrame(arg, f.counters, isTrueVararg));
                    }
                    f.phase = 5;
                    continue;
                case 3: {
                    Token arg = f.toks.get(f.i);
                    f.phase = 4;
                    if (arg.chk.isOwnershipMoveSource) {
                        // Null the moved owns slot right after its value is captured and before the call, so a throw inside the callee sees it null.
                        // A by-value range is never a move source. Register hazard: see emitOwnershipMoveNullOut.
                        return f.call(hub.statements.nullOutFrame(arg));
                    }
                    continue;
                }
                case 4:
                    f.i++;
                    f.phase = 2;
                    continue;
                default:
                    if (f.n >= 0) {
                        // SysV varargs: %al = xmm registers used. Only for a variadic call, just before the call instruction; see docs/BytecodeEmitter.md, "Variadic calls".
                        hub.core.line("VARARGS_XMM_COUNT " + f.counters.floatRegistersUsed());
                    }
                    hub.core.line(f.s2);
                    hub.core.line("CC_END " + f.s1);
                    if (f.s3 != null) {
                        hub.core.line("PUSH_RET " + f.s3);
                    }
                    return EmitFrame.DONE;
            }
        }
    }

    /** Runs a call sequence outside the emitter machine (the synthesized C-ABI main wrapper). */
    public void emitCallSequenceTop(Token at, List<Token> regArgs, String callInstruction, String returnTypeOrNull, String conventionName) {
        hub.driver.run(callSequenceFrame(at, null, regArgs, callInstruction, returnTypeOrNull, conventionName, -1));
    }

    /** Transfers one argument into its register(s); a by-value range becomes its two u64 bounds. promoteToDouble: a true f32 vararg. see docs/BytecodeEmitter.md, "Call sequence". */
    public EmitFrame argTransferFrame(Token arg, ArgCounters counters, boolean promoteToDouble) {
        EmitFrame f = new EmitFrame(EmitFrame.ARG_TRANSFER, arg);
        f.counters = counters;
        f.b1 = promoteToDouble;
        return f;
    }

    /** Frame: t1 = the ".." range being transferred word by word. */
    public int stepArgTransfer(EmitFrame f) {
        Token arg = f.node;
        switch (f.phase) {
            case 0: {
                Token unwrapped = unwrapParens(arg);
                if (Text.isByValueRangeType(arg.chk.resolvedType)) {
                    // A range's words are u64 bounds, never float.
                    if (unwrapped.type == TokenType.OPERATOR && unwrapped.text.equals("..")) {
                        f.t1 = unwrapped;
                        f.phase = 1;
                        return f.call(argWordFrame(unwrapped.left, f.counters, false));
                    }
                    String base = hub.qualifiedDotName(unwrapped);
                    emitSyntheticArgWord(base + ".start", "indeterminate_u64", f.counters);
                    emitSyntheticArgWord(base + ".end", "indeterminate_u64", f.counters);
                    return EmitFrame.DONE;
                }
                return f.tail(argWordFrame(arg, f.counters, f.b1));
            }
            case 1:
                f.phase = 2;
                return f.call(argWordFrame(f.t1.right, f.counters, false));
            default:
                return EmitFrame.DONE;
        }
    }

    /** Pushes one word and moves it to the next register of its bank; promoteToDouble inserts PROMOTE_F32_TO_F64 for a true f32 vararg. */
    public EmitFrame argWordFrame(Token expr, ArgCounters counters, boolean promoteToDouble) {
        EmitFrame f = new EmitFrame(EmitFrame.ARG_WORD, expr);
        f.counters = counters;
        f.b1 = promoteToDouble;
        return f;
    }

    public int stepArgWord(EmitFrame f) {
        Token expr = f.node;
        if (f.phase == 0) {
            f.phase = 1;
            return f.callExpr(expr);
        }
        if (f.b1 && BytecodeEmitterCore.isF32Canonical(expr.chk.resolvedType)) {
            hub.core.line("PROMOTE_F32_TO_F64");
        }
        // promoteToDouble doubles as the win64 variadic float duplication flag (same precondition).
        emitArgTransferTail(expr.chk.resolvedType, f.counters, f.b1);
        return EmitFrame.DONE;
    }

    /** Pushes a synthesized qualified-name word (e.g. "x.start") and transfers it; never float. */
    public void emitSyntheticArgWord(String qualifiedName, String type, ArgCounters counters) {
        hub.core.line("PUSH " + qualifiedName + " " + type);
        emitArgTransferTail(type, counters, false);
    }

    /** Emits POP ARGn / POP FARGn if the word's bank has a register left, else leaves it on the stack. isTrueVarargFloat additionally duplicates the word into the same-index int register on a shared-position (win64) convention. see docs/BytecodeEmitter.md, "Variadic calls". */
    public void emitArgTransferTail(String type, ArgCounters counters, boolean isTrueVarargFloat) {
        boolean isFloat = BytecodeEmitterCore.isFloatCanonical(type);
        int regIndex = counters.next(isFloat);
        boolean needsWin64VarargDup = isTrueVarargFloat && isFloat && regIndex >= 0 && counters.isShared();
        if (needsWin64VarargDup) {
            hub.core.line("DUP_TOP");
        }
        if (regIndex >= 0) {
            hub.core.line("POP " + (isFloat ? "FARG" : "ARG") + regIndex + " " + type);
            if (needsWin64VarargDup) {
                hub.core.line("POP ARG" + regIndex + " " + type);
            }
        }
    }

    /** Unwraps transparent grouping parens (the same '(' shape emitExpr's own DELINEATOR case treats as transparent) down to the real expression underneath, so e.g. "foo((x))" still recognizes "x" as the named range value it is. */
    public Token unwrapParens(Token t) {
        while (t.type == TokenType.DELINEATOR && t.text.equals("(") && t.childs.size() == 1) {
            t = t.childs.get(0);
        }
        return t;
    }

    /** Null for void ("imut_void"/"mut_void", exact match) so no PUSH_RET is emitted. */
    public String voidSafeReturnType(Token op) {
        String t = op.chk.resolvedType;
        if (t == null || t.equals("imut_void") || t.equals("mut_void")) {
            return null;
        }
        return t;
    }

    /** SLEEP sleep_call duration: dedicated mnemonic naming the @sleep function (resolved by TypeChecker) with the duration type. see docs/BytecodeEmitter.md, "Sleep call". */
    public EmitFrame sleepCallFrame(Token op) {
        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        Token durationArg = argNodes.get(0);
        return EmitFrame.script(op).expr(durationArg).line("SLEEP " + op.chk.resolvedCallTarget + " " + durationArg.chk.resolvedType);
    }

    /** Extern call; a variadic one carries its pushed-argument count in EXTERN_CALL. see docs/BytecodeEmitter.md, "Variadic calls". */
    public EmitFrame externCallFrame(Token op, ExternInfo info) {
        List<Token> args = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        int argCount = args.size();
        int varargStartIndex = info.hasVarargs ? info.paramTypes.size() : -1;
        return callSequenceFrame(op, null, args, "EXTERN_CALL " + info.linkName + " " + argCount,
                voidSafeReturnType(op), op.chk.resolvedCallConvention, varargStartIndex);
    }

    public EmitFrame callFrame(Token op) {
        String funcName = resolveCallTargetForEmission(op.chk.resolvedCallTarget);
        List<Token> args = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        // par/await are intercepted earlier (the async frame). A self-call (compared after dunder redirection) is RECURSIVE_CALL.
        String mnemonic = funcName.equals(hub.core.currentFuncMangledName) ? "RECURSIVE_CALL" : "CALL";
        // Staged before the argument sequence: the slot is only read by a deeper GT_UNWIND, after the real call runs.
        hub.core.stageCallSiteForUnwind(op);
        return callSequenceFrame(op, null, args, mnemonic + " " + funcName, voidSafeReturnType(op), op.chk.resolvedCallConvention, -1);
    }

    /**
     * Desugars a par/await site into ordinary ALLOC/NEW/GT_REGISTER/CALL bytecode with a hidden local __async_handle_N (emitted inline, not hoisted).
     * see docs/BytecodeEmitter.md, "par / await desugaring".
     * Frame: s1 = handle local, s2 = handle canonical type, s3 = held catch label, b1 = await, structInfo/entries/i = the handle layout walk,
     * toks = arguments, toks2/strs = moved-in sources / temporaries, toks3/strs2 = the deferral lists to restore, n = null-out index.
     */
    public int stepAsyncCall(EmitFrame f) {
        Token op = f.node;
        while (true) {
            switch (f.phase) {
                case 0: {
                    f.b1 = "AWAIT".equals(op.chk.asyncCallKind);
                    String handleStructName = op.chk.asyncHandleStructName;
                    f.s2 = op.chk.asyncHandleCanonicalType;
                    f.structInfo = hub.core.checker.declarations.getStructs().get(handleStructName);
                    f.toks = new ArrayList<>();
                    if (!op.right.childs.isEmpty()) {
                        collectCommaArgs(op.right.childs.get(0), f.toks);
                    }
                    f.s1 = "__async_handle_" + (++hub.core.asyncHandleTempCounter);
                    // The argument may contain calls that would consume the pending catch label: hold it back (as emitNew does).
                    // Moved-in owns arguments are only let go once the thread really started; if the handle can not be made or the
                    // thread can not be started they are destructed here instead (the failed 'par'/'await' never took them).
                    f.s3 = hub.core.pendingTryCatchLabel;
                    hub.core.pendingTryCatchLabel = null;
                    f.toks2 = new ArrayList<>();
                    f.strs = new ArrayList<>();
                    f.toks3 = hub.core.deferredMoveNullOuts;
                    f.strs2 = hub.core.deferredTempNames;

                    hub.core.line("ALLOC " + f.s1 + " " + f.s2);
                    hub.core.line("ADDR " + f.s1 + " " + f.s2);
                    if (f.structInfo.classId != null) {
                        hub.core.line("PUSH " + f.structInfo.classId + " imut_u64");
                    }
                    f.entries = hub.core.computeStructLayout(f.structInfo).entries;
                    f.i = 0;
                    f.phase = 1;
                    continue;
                }
                case 1: {
                    boolean waiting = false;
                    while (f.i < f.entries.size()) {
                        StructLayoutEntry entry = f.entries.get(f.i++);
                        if (entry.memberName == null) {
                            hub.core.line("STACK_LOCK " + entry.paddingBytes);
                            continue;
                        }
                        if (entry.memberName.equals("___type")) {
                            continue;
                        }
                        switch (entry.memberName) {
                            case "state":
                                hub.core.line("PUSH AsyncState.PENDING " + entry.canonicalType);
                                break;
                            case "result":
                                hub.core.line("STACK_LOCK " + hub.core.layoutSizeAndAlignOf(entry.canonicalType)[0]);
                                break;
                            case "value":
                                if (!f.toks.isEmpty()) {
                                    Token argTok = f.toks.get(0);
                                    hub.core.deferredMoveNullOuts = f.toks2;
                                    hub.core.deferredTempNames = f.strs;
                                    waiting = true;
                                } else {
                                    hub.core.line("PUSH 0 " + entry.canonicalType);
                                }
                                break;
                            case "threadId":
                                hub.core.line("PUSH 0 " + entry.canonicalType);
                                break;
                            case "gate":
                                hub.core.line("PUSH AsyncGate.OPEN " + entry.canonicalType);
                                break;
                            default:
                                throw new IllegalStateException(
                                        "unexpected AsyncHandle_T member '" + entry.memberName + "'");
                        }
                        if (waiting) {
                            break;
                        }
                    }
                    if (waiting) {
                        f.phase = 2;
                        return f.callExpr(f.toks.get(0));
                    }
                    f.phase = 3;
                    continue;
                }
                case 2: {
                    Token argTok = f.toks.get(0);
                    hub.core.deferredMoveNullOuts = f.toks3;
                    hub.core.deferredTempNames = f.strs2;
                    if (argTok.chk.isOwnershipMoveSource && !f.toks2.contains(argTok)) {
                        f.toks2.add(argTok);
                    }
                    f.phase = 1;
                    continue;
                }
                case 3: {
                    String handleCanonical = f.s2;
                    String tempName = f.s1;
                    boolean isAwait = f.b1;
                    StructInfo handleStructInfo = f.structInfo;
                    hub.core.line("NEW " + op.chk.asyncHandleStructName);
                    hub.core.requireGhostTableFunctionPresent("gt_register", op);
                    hub.core.line("GT_REGISTER");
                    // Same null check as 'new': a failed malloc or a failed registration leaves null here.
                    hub.core.line("DUP_TOP");
                    hub.core.line("PUSH null " + handleCanonical);
                    hub.core.line("EQ " + handleCanonical + " " + handleCanonical + " imut_bool");
                    hub.core.line("CMP");
                    String handleOkLabel = hub.statements.newLabel("async_handle_ok");
                    hub.core.line("JMP " + handleOkLabel);
                    if (!hub.core.inGtSuppressedContext && f.s3 != null) {
                        hub.emitDestructMovedSources(f.toks2);
                        hub.emitDestructTempNames(f.strs, op);
                        emitAsyncFailureJump(f.s3, "out of memory");
                    }
                    hub.core.line(handleOkLabel + ":");
                    hub.core.line("ASSIGN " + handleCanonical + " " + handleCanonical + " " + handleCanonical);

                    String glueDecorator = isAwait ? "await_call" : "par_call";
                    FuncInfo glueInfo = hub.core.checker.declarations.getGhostTableFunction(glueDecorator);
                    if (glueInfo == null) {
                        throw new CompilerException("bytecode", op.file, op.line,
                                "emitting a '" + (isAwait ? "await" : "par") + "' call here requires a function "
                                        + "decorated '@" + glueDecorator + "' to exist somewhere in the compilation unit "
                                        + "(none was found)");
                    }
                    String trampolineName = op.chk.asyncTrampolineName;
                    FuncInfo trampolineInfo = hub.core.checker.declarations.getFunctions().get(trampolineName).get(0);
                    String trampolineFuncPtrType = funcPointerCanonicalOf(trampolineInfo);

                    hub.core.line("CC_START " + glueInfo.callConvention);
                    hub.core.line("PUSH " + trampolineName + " " + trampolineFuncPtrType);
                    hub.core.line("POP ARG0 " + trampolineFuncPtrType);
                    hub.core.line("PUSH " + tempName + " " + handleCanonical);
                    hub.core.line("POP ARG1 " + handleCanonical);
                    if (!isAwait) {
                        // par_call also receives where to store the thread id: the handle's own threadId (so the handle's drop hook can join
                        // the thread), or null for the hidden fire-and-forget handle (its thread is detached).
                        if (handleStructInfo.members.containsKey("gate")) {
                            hub.core.line("PUSH " + tempName + " " + handleCanonical);
                            hub.core.line("PUSH_FIELDNAME threadId mut_u64");
                            hub.core.line("DOT_LHS " + handleCanonical + " mut_u64 mut_u64");
                        } else {
                            hub.core.line("PUSH null raw_mut_u64");
                        }
                        hub.core.line("POP ARG2 raw_mut_u64");
                    }
                    hub.core.line("CALL " + glueInfo.mangledName);
                    hub.core.line("CC_END " + glueInfo.callConvention);
                    // The glue returns false when pthread_create failed: nothing runs the trampoline, so nothing else will ever free
                    // the handle (or use the moved-in argument).
                    hub.core.line("PUSH_RET imut_bool");
                    hub.core.line("CMP");
                    String threadFailLabel = hub.statements.newLabel("async_thread_fail");
                    String threadOkLabel = hub.statements.newLabel("async_thread_ok");
                    hub.core.line("JMP " + threadFailLabel);
                    hub.core.line("JMP " + threadOkLabel);
                    hub.core.line(threadFailLabel + ":");
                    if (!hub.core.inGtSuppressedContext && f.s3 != null) {
                        hub.core.requireGhostTableFunctionPresent("gt_destruct", op);
                        hub.core.line("GT_DESTRUCT " + tempName);
                        hub.emitDestructMovedSources(f.toks2);
                        hub.emitDestructTempNames(f.strs, op);
                        emitAsyncFailureJump(f.s3, "cannot start thread");
                    }
                    hub.core.line(threadOkLabel + ":");
                    f.n = 0;
                    f.phase = 4;
                    continue;
                }
                case 4:
                    if (f.n < f.toks2.size()) {
                        Token moved = f.toks2.get(f.n++);
                        return f.call(hub.statements.nullOutFrame(moved)); // the thread now owns the argument
                    }
                    f.phase = 5;
                    continue;
                default: {
                    String handleCanonical = f.s2;
                    String tempName = f.s1;
                    boolean isVoidResult = isVoidCanonical(op.chk.resolvedType);
                    if (f.b1) {
                        if (!isVoidResult) {
                            String resultCanonical = op.chk.resolvedType;
                            hub.core.line("PUSH " + tempName + " " + handleCanonical);
                            hub.core.line("PUSH_FIELDNAME result " + resultCanonical);
                            hub.core.line("DOT_LHS " + handleCanonical + " " + resultCanonical + " " + resultCanonical);
                            hub.core.line("DEREF " + resultCanonical);
                        }
                        // Safe for void too: @await_call has already joined, no other thread touches the handle.
                        hub.core.requireGhostTableFunctionPresent("gt_destruct", op);
                        hub.core.line("GT_DESTRUCT " + tempName);
                    } else if (!isVoidResult) {
                        // Non-void par: the handle is the expression value; the consumer owns it.
                        hub.core.line("PUSH " + tempName + " " + handleCanonical);
                    } else {
                        // Void par: the hidden handle is destructed by the trampoline itself (selfDestructHidden), never here: the thread may still be reading it.
                    }
                    return EmitFrame.DONE;
                }
            }
        }
    }

    /** Sets the shared `gt_error_message` and jumps to the catch of the `try`/`?` wrapping a 'par'/'await' (same shape as the 'new' out-of-memory exit). */
    public void emitAsyncFailureJump(String catchLabel, String message) {
        String messageId = hub.core.hoistedStringId(message);
        hub.core.emitTraceCapture();
        hub.core.line("ADDR gt_error_message static_imut_string");
        hub.core.line("PUSH " + messageId + " static_imut_string");
        hub.core.line("ASSIGN static_imut_string static_imut_string static_imut_string");
        hub.core.line("JMP " + catchLabel);
    }

    /** Exact-equality void test, as voidSafeReturnType. */
    public boolean isVoidCanonical(String canonical) {
        return canonical == null || canonical.equals("imut_void") || canonical.equals("mut_void");
    }

    /** Canonical text of a bare function reference ("static_imut_func(params)ret"), mirroring TypeInfo.funcPointer; used for the trampoline address push. */
    public String funcPointerCanonicalOf(FuncInfo info) {
        // Function-pointer values always have static storage.
        StringBuilder sb = new StringBuilder("static_imut_func(");
        for (int i = 0; i < info.paramTypes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(info.paramTypes.get(i).canonical());
        }
        sb.append(')').append(info.returnType.canonical());
        return sb.toString();
    }

    /** Under gt-suppression a CALL to a gt-reachable function is redirected to its "__" duplicate. see docs/BytecodeEmitter.md, "Call target redirection". */
    public String resolveCallTargetForEmission(String mangledName) {
        if (hub.core.inGtSuppressedContext && hub.core.gtReachableMangledNames.contains(mangledName)) {
            return "__" + mangledName;
        }
        // main is emitted as __caspien_main when an event loop is the real entry (see emitFunc); redirect calls to it.
        if (mangledName.equals("main") && hub.core.checker.declarations.getEventLoopFunc() != null) {
            return "__caspien_main";
        }
        return mangledName;
    }

    public void collectCommaArgs(Token node, List<Token> out) {
        Deque<Token> work = new ArrayDeque<>();
        work.push(node);
        while (!work.isEmpty()) {
            Token t = work.pop();
            if (t.type == TokenType.OPERATOR && t.text.equals(",")) {
                work.push(t.right);
                work.push(t.left);
            } else {
                out.add(t);
            }
        }
    }
}

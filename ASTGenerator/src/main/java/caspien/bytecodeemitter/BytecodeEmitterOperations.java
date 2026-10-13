package caspien.bytecodeemitter;
import caspien.CompilerException;
import caspien.Token;
import caspien.TokenType;
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
     * Returns true when the target has no flat name: its address is then on the stack (DUP_TOP + GT_DESTRUCT_ADDR) and emitAssign
     * must not compute it again. See docs/BytecodeEmitter.md, "Moves and owning assignment".
     */
    public boolean emitDestructOldOwnedValue(Token target) {
        if (target.type == TokenType.KEYWORD || target.resolvedType == null
                || !(target.resolvedType.startsWith("owns") || target.inlineOwnsStruct != null)) {
            return false;
        }
        if (target.inlineOwnsStruct != null && !hub.statements.isFlatOwnsDestructTarget(target)) {
            // an inline owning struct reached through a pointer or an index: drop the members of the value at the computed address
            hub.statements.emitAssignTarget(target);
            hub.core.line("DUP_TOP");
            hub.core.requireGhostTableFunctionPresent("gt_destruct", target);
            hub.core.line("GT_DROP_ADDR " + target.resolvedType);
            return true;
        }
        if (target.type == TokenType.VARREF) {
            hub.core.requireGhostTableFunctionPresent("gt_destruct", target);
            hub.core.line("GT_DESTRUCT " + target.text);
            return false;
        }
        if (target.type == TokenType.OPERATOR && target.text.equals(".") && hub.isQualifiedNameableDot(target)) {
            hub.core.requireGhostTableFunctionPresent("gt_destruct", target);
            hub.core.line("GT_DESTRUCT " + hub.qualifiedDotName(target));
            return false;
        }
        if (target.type == TokenType.OPERATOR && (target.text.equals(".") || target.text.equals("LOOKUP"))) {
            hub.statements.emitAssignTarget(target);
            hub.core.line("DUP_TOP");
            hub.core.requireGhostTableFunctionPresent("gt_destruct", target);
            hub.core.line("GT_DESTRUCT_ADDR " + target.resolvedType);
            return true;
        }
        return false; // unreached today -- no other assignment-target shape exists
    }

    public void emitAssign(Token op) {
        if (unwrapMutWrappers(op.right).isStructRvoCall) {
            emitStructRvoAssign(op);
            return;
        }
        // Flat-named owns target: destruct AFTER the right side ("s = pass(s)" moves the old value into the call, nulling the variable).
        boolean lateDestruct = hub.statements.isFlatOwnsDestructTarget(op.left);
        // Pointer/index-reached owns target (no flat name): the right side is evaluated FIRST into a hidden local, so a move out of
        // the old value nulls it and a throw leaves it untouched.
        boolean pointerCrossingOwns = !lateDestruct && hub.core.lateAllocs != null && op.right.resolvedType != null
                && (op.right.resolvedType.startsWith("owns") || op.left.inlineOwnsStruct != null)
                && hub.statements.isPointerCrossingOwnsTarget(op.left);
        String spilledRight = null;
        if (pointerCrossingOwns) {
            String rightType = op.left.inlineOwnsStruct != null ? op.left.resolvedType : op.right.resolvedType;
            spilledRight = hub.declareHiddenLocal(rightType);
            hub.core.line("ADDR " + spilledRight + " " + rightType);
            hub.statements.emitExpr(op.right);
            hub.core.line("ASSIGN " + rightType + " " + rightType + " " + rightType);
        }
        boolean addressAlreadyOnStack = false;
        if (!lateDestruct) {
            addressAlreadyOnStack = emitDestructOldOwnedValue(op.left);
        }
        if (!addressAlreadyOnStack) {
            hub.statements.emitAssignTarget(op.left);
        }
        if (spilledRight != null) {
            hub.core.line("PUSH " + spilledRight + " " + (op.left.inlineOwnsStruct != null ? op.left.resolvedType : op.right.resolvedType));
        } else {
            hub.statements.emitExpr(op.right);
        }
        if (lateDestruct) {
            emitDestructOldOwnedValue(op.left);
        }
        String assignMnemonic = BytecodeEmitterCore.isAtomicCanonical(op.left.resolvedType) ? "ATOMIC_ASSIGN" : "ASSIGN";
        hub.core.line(assignMnemonic + " " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
        if (op.right.isOwnershipMoveSource) {
            hub.statements.emitOwnershipMoveNullOut(op.right);
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
                t = t.sub.get(0);
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

    /** Caller half of struct RVO: the callee gets the destination's address as a hidden first argument; no PUSH_RET/ASSIGN copy. See docs/BytecodeEmitter.md, "Return and struct return (RVO)". */
    public void emitStructRvoAssign(Token op) {
        Token callOp = unwrapMutWrappers(op.right);
        String funcName = resolveCallTargetForEmission(callOp.resolvedCallTarget);
        TypeCheckerModel.FuncInfo targetInfo = hub.core.findFuncInfoByMangledName(funcName);
        String structName = targetInfo != null ? targetInfo.returnType.baseType
                : op.left.resolvedType.substring(op.left.resolvedType.lastIndexOf('_') + 1);
        String destVarName = destVarNameOf(op.left);
        String destType = op.left.resolvedType;
        String rawPtrType = "raw_indeterminate_" + structName;
        if (op.left.inlineOwnsStruct != null && hub.core.lateAllocs != null) {
            // The destination already owns memory (an inline struct with owns members): the callee builds the new value in a
            // hidden local, then the old members are dropped and the value is copied over. A callee that throws leaves the
            // destination untouched, and an argument that moves a member out of it (`h = f(h.w)`) has already nulled it.
            String hidden = hub.declareHiddenLocal(destType);
            emitStructRvoCallSequence(callOp, rawPtrType, () -> {
                hub.core.line("PUSH " + hidden + " " + destType);
                hub.core.line("ADDR_OF RAW " + destType + " " + rawPtrType);
            });
            hub.core.requireGhostTableFunctionPresent("gt_destruct", op.left);
            hub.core.line("GT_DESTRUCT " + destVarName);
            hub.core.line("ADDR " + destVarName + " " + destType);
            hub.core.line("PUSH " + hidden + " " + destType);
            hub.core.line("ASSIGN " + destType + " " + destType + " " + destType);
            return;
        }
        emitStructRvoCallSequence(callOp, rawPtrType, () -> {
            hub.core.line("PUSH " + destVarName + " " + destType);
            hub.core.line("ADDR_OF RAW " + destType + " " + rawPtrType);
        });
        // no PUSH_RET/ASSIGN: the callee already wrote the destination
    }

    /** "return foo()" tail forwarding: this function's own $ret_dest pointer is the callee's hidden destination (no address-of, no copy). */
    public void emitStructRvoForwardCall(Token callOp, String ptrType) {
        emitStructRvoCallSequence(callOp, ptrType, () -> hub.core.line("PUSH $ret_dest " + ptrType));
    }

    /** Shared RVO call site: arguments are transferred as emitCallSequence would, with the hidden destination pointer (pushDestPointer) in register slot 0. */
    public void emitStructRvoCallSequence(Token callOp, String rawPtrType, Runnable pushDestPointer) {
        String funcName = resolveCallTargetForEmission(callOp.resolvedCallTarget);
        List<Token> args = new ArrayList<>();
        Token argsDelineator = callOp.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        String conventionName = callOp.resolvedCallConvention;
        Token ownerTry = ownerTryOf(callOp);
        if (ownerTry != null) {
            hub.core.pendingTryCatchLabel = hub.core.tryCatchLabels.get(ownerTry); // consumed by the staging write just below
        }
        hub.core.stageCallSiteForUnwind(callOp);
        hub.core.pendingTryCatchLabel = null;
        BytecodeEmitterCore.ArgCounters counters = new BytecodeEmitterCore.ArgCounters(hub.core.checker.model.getConfig().callingConventions.get(conventionName));
        hub.core.line("CC_START " + conventionName);
        pushDestPointer.run();
        emitArgTransferTail(rawPtrType, counters, false);
        for (Token arg : args) {
            emitArgTransfer(arg, counters, false);
            if (arg.isOwnershipMoveSource) {
                hub.statements.emitOwnershipMoveNullOut(arg);
            }
        }
        String mnemonic = funcName.equals(hub.core.currentFuncMangledName) ? "RECURSIVE_CALL" : "CALL";
        hub.core.line(mnemonic + " " + funcName);
        hub.core.line("CC_END " + conventionName);
    }

    /** Name of the bare-variable destination (a 'let' binding or a plain assignment target). */
    public String destVarNameOf(Token target) {
        if (target.type == TokenType.KEYWORD && target.text.equals("let")) {
            return target.childs.get(0).text;
        }
        return target.text;
    }

    /** x += y desugars to the same bytecode as "x = x + y" -- no dedicated compound-assign opcode. */
    public void emitCompoundAssign(Token op) {
        hub.statements.emitAssignTarget(op.left); // assign target
        hub.statements.emitExpr(op.left);         // left operand of the arithmetic
        hub.statements.emitExpr(op.right);        // right operand
        if (op.pointerScale > 1) {
            // "p += n" on a raw pointer: n elements, i.e. n * sizeof(pointee) bytes.
            hub.core.line(BytecodeEmitterCore.pointerScaleLine(op, "indeterminate_u64"));
            hub.core.line("MUL " + op.right.resolvedType + " indeterminate_u64 " + op.right.resolvedType);
        }
        String arithOp = BytecodeEmitterCore.COMPOUND_TO_ARITH.get(op.text);
        hub.core.line(BytecodeEmitterCore.ARITH_NAMES.get(arithOp) + " " + op.left.resolvedType + " " + op.right.resolvedType
                + " " + op.left.resolvedType);
        hub.core.line("ASSIGN " + op.left.resolvedType + " " + op.left.resolvedType + " " + op.resolvedType);
    }

    public void emitBinaryArith(Token op) {
        if (op.pointerArithKind != 0) {
            emitPointerArith(op);
            return;
        }
        hub.statements.emitExpr(op.left);
        hub.statements.emitExpr(op.right);
        hub.core.line(BytecodeEmitterCore.ARITH_NAMES.get(op.text) + " " + op.left.resolvedType + " " + op.right.resolvedType
                + " " + op.resolvedType);
    }

    /** C-style pointer arithmetic: p +/- n and n + p scale n by the pointee size; p - q is (p - q) / sizeof(pointee), signed. */
    public void emitPointerArith(Token op) {
        String l = op.left.resolvedType;
        String r = op.right.resolvedType;
        String arith = BytecodeEmitterCore.ARITH_NAMES.get(op.text);
        if (op.pointerArithKind == 3) {
            hub.statements.emitExpr(op.left);
            hub.statements.emitExpr(op.right);
            hub.core.line(arith + " " + l + " " + r + " indeterminate_s64");
            if (op.pointerScale > 1) {
                hub.core.line(BytecodeEmitterCore.pointerScaleLine(op, "indeterminate_s64"));
                hub.core.line("DIV indeterminate_s64 indeterminate_s64 indeterminate_s64");
            }
            return;
        }
        hub.statements.emitExpr(op.left);
        if (op.pointerArithKind == 2 && op.pointerScale > 1) {
            hub.core.line(BytecodeEmitterCore.pointerScaleLine(op, "indeterminate_u64"));
            hub.core.line("MUL " + l + " indeterminate_u64 " + l);
        }
        hub.statements.emitExpr(op.right);
        if (op.pointerArithKind == 1 && op.pointerScale > 1) {
            hub.core.line(BytecodeEmitterCore.pointerScaleLine(op, "indeterminate_u64"));
            hub.core.line("MUL " + r + " indeterminate_u64 " + r);
        }
        hub.core.line(arith + " " + l + " " + r + " " + op.resolvedType);
    }

    public void emitUnaryMinus(Token op) {
        // A negated integer literal is one constant: a single `PUSH -N` (so it also stays a plain PUSH inside a
        // packed array / struct construction run, which any other instruction between pushes would break).
        if (op.left.type == TokenType.INTEGER && op.left.forcedLiteralType == null && op.left.text.matches("[0-9]+")) {
            String digits = op.left.text.replaceFirst("^0+(?=.)", "");
            hub.core.line("PUSH " + (digits.equals("0") ? "0" : "-" + digits) + " " + op.resolvedType);
            return;
        }
        hub.statements.emitExpr(op.left);
        hub.core.line("NEG " + op.left.resolvedType + " " + op.resolvedType);
    }

    public void emitLogicalNot(Token op) {
        hub.statements.emitExpr(op.left);
        hub.core.line("NOT " + op.left.resolvedType + " " + op.resolvedType);
    }

    /** x++ desugars to the same bytecode shape as "x = x .INC" -- push target, push value, INC, ASSIGN. */
    public void emitIncrement(Token op) {
        if (op.pointerScale > 1) {
            emitPointerStep(op, "ADD");
            return;
        }
        hub.statements.emitAssignTarget(op.left); // assign target
        hub.statements.emitExpr(op.left);         // value to increment
        hub.core.line("INC " + op.left.resolvedType + " " + op.resolvedType);
        hub.core.line("ASSIGN " + op.left.resolvedType + " " + op.resolvedType + " " + op.resolvedType);
    }

    /** "p++"/"p--" on a raw pointer whose pointee is wider than a byte: p = p +/- sizeof(pointee), as C does. */
    public void emitPointerStep(Token op, String arith) {
        String t = op.left.resolvedType;
        hub.statements.emitAssignTarget(op.left);
        hub.statements.emitExpr(op.left);
        hub.core.line(BytecodeEmitterCore.pointerScaleLine(op, "indeterminate_u64"));
        hub.core.line(arith + " " + t + " indeterminate_u64 " + t);
        hub.core.line("ASSIGN " + t + " " + t + " " + t);
    }

    /** x-- : emitIncrement's shape with DEC. */
    public void emitDecrement(Token op) {
        if (op.pointerScale > 1) {
            emitPointerStep(op, "SUB");
            return;
        }
        hub.statements.emitAssignTarget(op.left); // assign target
        hub.statements.emitExpr(op.left);         // value to decrement
        hub.core.line("DEC " + op.left.resolvedType + " " + op.resolvedType);
        hub.core.line("ASSIGN " + op.left.resolvedType + " " + op.resolvedType + " " + op.resolvedType);
    }

    public void emitLogical(Token op, String name) {
        hub.statements.emitExpr(op.left);
        hub.statements.emitExpr(op.right);
        hub.core.line(name + " " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
    }

    /** True for a "&&then"/"||then" node specifically -- never a plain "&&"/"||". */
    public boolean isThenVariant(Token node) {
        return node.type == TokenType.OPERATOR && (node.text.equals("&&then") || node.text.equals("||then"));
    }

    /**
     * Cascading CMP/JMP lowering of "&&then"/"||then". Exactly one of falseLabel/trueLabel is non-null; the other outcome falls
     * through. No AND/OR is emitted. See docs/BytecodeEmitter.md, "Short-circuit conditions".
     */
    public void emitShortCircuit(Token node, String falseLabel, String trueLabel) {
        if (!isThenVariant(node)) {
            // leaf: any ordinary expression (a plain &&/|| subtree stays eager); only its bool is tested
            // is tested here.
            hub.statements.emitExpr(node);
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
            return;
        }
        boolean isAnd = node.text.equals("&&then");
        if (isAnd) {
            if (trueLabel == null) {
                // common case: fall through if both are true, jump to falseLabel as soon as either fails
                emitShortCircuit(node.left, falseLabel, null);
                emitShortCircuit(node.right, falseLabel, null);
            } else {
                // left operand of an enclosing "||then": jump to trueLabel only if both pass; a failure lands past that jump
                String bothFailLabel = hub.statements.newLabel("andthen_fail");
                emitShortCircuit(node.left, bothFailLabel, null);
                emitShortCircuit(node.right, bothFailLabel, null);
                hub.core.line("JMP " + trueLabel);
                hub.core.line(bothFailLabel + ":");
            }
        } else { // "||then"
            if (falseLabel == null) {
                // This "||then" is itself the left operand of an
                // left operand of an enclosing "||then": forward trueLabel to both sides
                // level up), falling through only if both are false.
                emitShortCircuit(node.left, null, trueLabel);
                emitShortCircuit(node.right, null, trueLabel);
            } else {
                // left true: skip the right side and fall through as true; otherwise test the right side against falseLabel
                String passLabel = hub.statements.newLabel("orthen_pass");
                emitShortCircuit(node.left, null, passLabel);
                emitShortCircuit(node.right, falseLabel, null);
                hub.core.line(passLabel + ":");
            }
        }
    }

    public void emitComparison(Token op) {
        hub.statements.emitExpr(op.left);
        hub.statements.emitExpr(op.right);
        hub.core.line(BytecodeEmitterCore.COMPARISON_NAMES.get(op.text) + " " + op.left.resolvedType + " " + op.right.resolvedType
                + " " + op.resolvedType);
    }

    /** "a swap b": ATOMIC_SWAP, a read-modify-write; the left operand is pushed as an address, the result is the previous value. */
    public void emitSwap(Token op) {
        // ATOMIC_SWAP consumes an ADDRESS (xchg through it); pushing the field's value worked only for statics and segfaulted for a swap-lock struct in a local
        hub.statements.emitAssignTarget(op.left);
        hub.statements.emitExpr(op.right);
        hub.core.line("ATOMIC_SWAP " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
    }

    public void emitCallOrBuiltin(Token op) {
        if (op.isSleepCall) {
            emitSleepCall(op);
            return;
        }
        if ("AWAIT".equals(op.asyncCallKind) || "PAR".equals(op.asyncCallKind)) {
            emitAsyncCall(op);
            return;
        }
        if (op.left.type == TokenType.VARREF) {
            switch (op.left.text) {
                case "sizeof":
                    // Direct PUSH; a struct size stays symbolic (SIZEOF) until SizeofResolutionPass.
                    if (op.sizeofStruct != null) {
                        hub.core.line("SIZEOF " + op.sizeofStruct + " " + op.resolvedType);
                    } else {
                        hub.core.line("PUSH " + op.builtinConstantValue + " " + op.resolvedType);
                    }
                    return;
                case "len": {
                    List<Token> lenArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), lenArgs);
                    if (lenArgs.size() == 2) {
                        // Unsafe 2-arg terminator scan: never foldable, own LEN_SCAN mnemonic.
                        Token targetArg = lenArgs.get(0);
                        Token termArg = lenArgs.get(1);
                        hub.statements.emitExpr(targetArg);
                        hub.statements.emitExpr(termArg);
                        hub.core.line("LEN_SCAN " + targetArg.resolvedType + " " + termArg.resolvedType);
                    } else if (op.builtinConstantValue != null) {
                        // Fixed array / string literal: folded to a PUSH.
                        hub.core.line("PUSH " + op.builtinConstantValue + " " + op.resolvedType);
                    } else {
                        // Safe dynarray: run-time length, read by LEN.
                        hub.statements.emitExpr(singleBuiltinArg(op));
                        hub.core.line("LEN");
                    }
                    return;
                }
                case "range": {
                    // A range is two u64 words; start is always literal 0.
                    if (op.builtinConstantValue != null) {
                        // String literal / fixed array: constant bounds, no RANGE instruction.
                        hub.core.line("PUSH 0 imut_u64");
                        hub.core.line("PUSH " + op.builtinConstantValue + " imut_u64");
                    } else {
                        // Safe dynarray: length read by LEN.
                        hub.core.line("PUSH 0 imut_u64");
                        hub.statements.emitExpr(singleBuiltinArg(op));
                        hub.core.line("LEN");
                    }
                    return;
                }
                case "deref":
                    // DEREF carries the pointee type because it is the copy width (the pushed pointer is always 8 bytes).
                    hub.statements.emitExpr(singleBuiltinArg(op));
                    hub.core.line("DEREF " + op.resolvedType);
                    return;
                case "clone": {
                    // Hold back the pending catch label: a nested call would consume it (see "Allocation failure check").
                    final String cloneCatchLabel = hub.core.pendingTryCatchLabel;
                    hub.core.pendingTryCatchLabel = null;
                    // CLONE carries argument and result types so lowering can size the pointee.
                    Token arg = singleBuiltinArg(op);
                    hub.statements.emitExpr(arg);
                    hub.core.requireGhostTableFunctionPresent("gt_register", op);
                    hub.core.line("CLONE " + arg.resolvedType + " " + op.resolvedType);
                    // Same tail as `new`: a null result (failed malloc) jumps to the enclosing catch, otherwise
                    // the copy is registered with the ghost table so `match Some` and the scope-end destruct work.
                    hub.core.pendingTryCatchLabel = cloneCatchLabel;
                    hub.core.line("GT_REGISTER"); // before the check: a failed registration frees the block and leaves null
                    hub.emitAllocFailureCheck(op);
                    return;
                }
                case "Some":
                    // Same GT_ALIVE_CHECK sequence as a match condition; both restrictions apply in any expression position.
                    if (hub.core.inGtSuppressedContext) {
                        throw new CompilerException("type", op.file, op.line,
                                "'Some(...)' (which needs '@gt_alive_check') can't be used here -- this "
                                        + "function is a ghost-table-decorated function, or is reachable "
                                        + "from one, and ghost-table instructions can never be generated "
                                        + "in that whole reachable set");
                    }
                    if (op.someOnRef) {
                        hub.statements.emitSomeOnRef(op, singleBuiltinArg(op), op.someProofKey);
                        return;
                    }
                    hub.core.requireGhostTableFunctionPresent("gt_alive_check", op);
                    hub.statements.emitExpr(singleBuiltinArg(op));
                    hub.core.line("GT_ALIVE_CHECK");
                    return;
                case "wrap":
                case "sat":
                    hub.emitConvert(op);
                    return;
                case "insecure_rand":
                    // rand() through the shared call sequence: no ExternInfo, default convention.
                    emitCallSequence(null, new ArrayList<>(), () -> hub.core.line("EXTERN_CALL rand 0"), "mut_u64",
                            hub.core.checker.model.getConfig().defaultConvention);
                    return;
                case "bits_not": {
                    // Unary: one operand, one instruction "BITS_NOT argType resultType" (the shape NOT/NEG already have).
                    List<Token> notArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), notArgs);
                    hub.statements.emitExpr(notArgs.get(0));
                    hub.core.line("BITS_NOT " + notArgs.get(0).resolvedType + " " + op.resolvedType);
                    return;
                }
                case "bits_left": case "bits_right": case "bits_rotl": case "bits_rotr": case "bits_or": case "bits_and": case "bits_xor": {
                    // Binary shape like ADD: both operands, then "OP leftType rightType resultType".
                    List<Token> bitsArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), bitsArgs);
                    Token bitsLeft = bitsArgs.get(0);
                    Token bitsRight = bitsArgs.get(1);
                    hub.statements.emitExpr(bitsLeft);
                    hub.statements.emitExpr(bitsRight);
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
                    hub.core.line(bitsOpName + " " + bitsLeft.resolvedType + " " + bitsRight.resolvedType + " "
                            + op.resolvedType);
                    return;
                }
                case "memcopy": {
                    // Push all arguments, then MEMCOPY with the operand types.
                    List<Token> memcopyArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), memcopyArgs);
                    for (Token arg : memcopyArgs) {
                        hub.statements.emitExpr(arg);
                    }
                    hub.core.line("MEMCOPY " + memcopyArgs.get(0).resolvedType + " " + memcopyArgs.get(1).resolvedType
                            + " " + memcopyArgs.get(2).resolvedType + " " + op.resolvedType);
                    return;
                }
                case "dyn": {
                    // Hold back the pending catch label (see "Allocation failure check").
                    final String dynCatchLabel = hub.core.pendingTryCatchLabel;
                    hub.core.pendingTryCatchLabel = null;
                    if (op.dynFromStringSource) {
                        // dyn(text): NEW_FROM_STRING copies the characters at run time.
                        Token stringArg = singleBuiltinArg(op);
                        hub.statements.emitExpr(stringArg);
                        hub.core.line("NEW_FROM_STRING " + op.resolvedType.substring("owns_".length()) + " "
                                + stringArg.resolvedType);
                        // Safe dyn(text) throws: same allocation failure check as new.
                        if (!op.resolvedType.substring("owns_".length()).startsWith("indeterminate_unsafe_dynarray(")) {
                            hub.core.pendingTryCatchLabel = dynCatchLabel;
                            // The block start is registered with the ghost table (like `new`), so scope-end drop frees it;
                            // a failed registration frees the block and leaves null: the check below takes the OOM path.
                            hub.core.requireGhostTableFunctionPresent("gt_register", op);
                            hub.core.line("GT_REGISTER");
                            hub.emitAllocFailureCheck(op);
                        }
                        return;
                    }
                    // dyn([..]): push the elements, then NEW_DYN (safe, keeps count) or NEW_UDYN (unsafe, flat size); see docs/BytecodeEmitter.md, "dyn literals".
                    Token arrayLiteral = singleBuiltinArg(op);
                    List<Token> literalElements = new ArrayList<>();
                    final List<String> literalTemps = new ArrayList<>();
                    // `dyn:<T>([])` -- nothing to push for an empty literal.
                    if (!arrayLiteral.childs.isEmpty()) {
                        final List<String> savedDynTemps = hub.core.dynLiteralTempNames;
                        hub.core.dynLiteralTempNames = literalTemps;
                        hub.statements.emitExpr(arrayLiteral);
                        hub.core.dynLiteralTempNames = savedDynTemps;
                        if (arrayLiteral.type == TokenType.DELINEATOR && arrayLiteral.text.equals("[")) {
                            collectCommaArgs(arrayLiteral.childs.get(0), literalElements);
                        }
                    }
                    String typeText = op.resolvedType.substring("owns_".length());
                    boolean isUnsafeLiteral = typeText.startsWith("indeterminate_unsafe_dynarray(");
                    String mnemonic = isUnsafeLiteral ? "NEW_UDYN" : "NEW_DYN";
                    hub.core.line(mnemonic + " " + typeText + " " + op.builtinConstantValue);
                    // Safe dyn([..]) throws like new; NEW_UDYN stays non-throwing.
                    if (!isUnsafeLiteral) {
                        hub.core.pendingTryCatchLabel = dynCatchLabel;
                        hub.core.requireGhostTableFunctionPresent("gt_register", op);
                        hub.core.line("GT_REGISTER");
                        hub.emitAllocFailureCheck(op, literalElements, literalTemps);
                    }
                    return;
                }
                case "resize": {
                    // Hold back the pending catch label (see "Allocation failure check").
                    final String resizeCatchLabel = hub.core.pendingTryCatchLabel;
                    hub.core.pendingTryCatchLabel = null;
                    // resize takes ownership of the array and returns a new owns; RESIZE (safe) / URESIZE (unsafe). No CC bracket. see docs/BytecodeEmitter.md, "resize emission".
                    List<Token> resizeArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), resizeArgs);
                    Token dynArrArg = resizeArgs.get(0);
                    boolean isUnsafeResize = dynArrArg.resolvedType.startsWith("owns_mut_unsafe_dynarray(");
                    // A safe dynarray whose elements own memory: a shrink must drop the owns members of the elements it cuts
                    // off. The array pointer and the new count are evaluated once into hidden locals, the elements at index
                    // >= count are dropped (GT_DESTRUCT_TAIL, expanded by DropGlueGenerationPass), then the RESIZE runs on them.
                    String tailArr = null;
                    String tailCount = null;
                    String tailOld = null;
                    if (!isUnsafeResize && hub.core.lateAllocs != null) {
                        String dynType = dynArrArg.resolvedType;
                        String elemText = dynType.substring(dynType.indexOf("dynarray(") + "dynarray(".length(), dynType.length() - 1);
                        if (hub.core.checker.declarations.elementTextOwnsMemory(elemText)) {
                            Token countArg = resizeArgs.get(1);
                            tailArr = hub.declareHiddenLocal(dynType);
                            hub.core.line("ADDR " + tailArr + " " + dynType);
                            hub.statements.emitExpr(dynArrArg);
                            hub.core.line("ASSIGN " + dynType + " " + dynType + " " + dynType);
                            tailCount = hub.declareHiddenLocal("mut_u64");
                            hub.core.line("ADDR " + tailCount + " mut_u64");
                            hub.statements.emitExpr(countArg);
                            hub.core.line("ASSIGN mut_u64 " + countArg.resolvedType + " mut_u64");
                            hub.core.line("GT_DESTRUCT_TAIL " + tailArr + " " + dynType + " " + tailCount);
                            tailOld = hub.declareHiddenLocal("mut_u64");
                            hub.core.line("ADDR " + tailOld + " mut_u64");
                            hub.core.line("PUSH " + tailArr + " " + dynType);
                            hub.core.line("LEN");
                            hub.core.line("ASSIGN mut_u64 indeterminate_u64 mut_u64");
                            hub.core.line("PUSH " + tailArr + " " + dynType);
                        }
                    }
                    // A source that is a temporary (a call result) has no name to free if the realloc fails: it goes through a
                    // hidden local (like a temporary moved into `new`), which the failure branch destructs.
                    final List<String> resizeTemps = new ArrayList<>();
                    if (tailArr != null && hub.isOwnsTemporary(dynArrArg)) {
                        resizeTemps.add(tailArr);
                    }
                    if (tailArr == null) {
                        if (!isUnsafeResize && hub.isOwnsTemporary(dynArrArg)) {
                            hub.emitOwnsTemporaryViaLocal(dynArrArg, resizeTemps);
                        } else {
                            hub.statements.emitExpr(dynArrArg);
                        }
                    }
                    // Safe resize: the source variable is only nulled once the realloc succeeded. When it fails the old block is
                    // still allocated and registered (realloc leaves it intact) but nothing else would ever reach it, so the
                    // failure branch frees it (like a failed `new` frees what was moved into it). Unsafe resize cannot fail.
                    final boolean deferResizeNullOut = !isUnsafeResize && dynArrArg.isOwnershipMoveSource;
                    if (dynArrArg.isOwnershipMoveSource && !deferResizeNullOut) {
                        hub.statements.emitOwnershipMoveNullOut(dynArrArg);
                    }
                    StringBuilder resizeLine = new StringBuilder(isUnsafeResize ? "URESIZE " : "RESIZE ")
                            .append(dynArrArg.resolvedType);
                    for (int i = 1; i < resizeArgs.size(); i++) {
                        Token arg = resizeArgs.get(i);
                        if (i == 1 && tailCount != null) {
                            hub.core.line("PUSH " + tailCount + " mut_u64");
                        } else {
                            hub.statements.emitExpr(arg);
                        }
                        resizeLine.append(' ').append(arg.resolvedType);
                    }
                    hub.core.line(resizeLine.toString());
                    if (tailOld != null) {
                        // growing: the new slots are bitwise copies of the moved-in fill value; give each its own deep clone
                        String filled = hub.declareHiddenLocal(dynArrArg.resolvedType);
                        hub.core.line("POP " + filled + " " + dynArrArg.resolvedType);
                        hub.core.line("CLONE_FILL " + filled + " " + dynArrArg.resolvedType + " " + tailOld);
                        hub.core.line("PUSH " + filled + " " + dynArrArg.resolvedType);
                    }
                    // Safe resize throws like new; URESIZE stays non-throwing.
                    if (!isUnsafeResize) {
                        hub.core.pendingTryCatchLabel = resizeCatchLabel;
                        List<Token> resizeMoved = new ArrayList<>();
                        if (deferResizeNullOut) {
                            resizeMoved.add(dynArrArg);
                        }
                        hub.emitAllocFailureCheck(op, resizeMoved, resizeTemps);
                        if (deferResizeNullOut) {
                            hub.statements.emitOwnershipMoveNullOut(dynArrArg);
                        }
                    }
                    return;
                }
                case "call": {
                    // PUSH fnptr, args, INVOKE n (n excludes the pointer); the pointer is not an ARGn word.
                    List<Token> callArgs = new ArrayList<>();
                    collectCommaArgs(op.right.childs.get(0), callArgs);
                    Token funcPtrArg = callArgs.get(0);
                    List<Token> realArgs = callArgs.subList(1, callArgs.size());
                    int invokeArgCount = op.invokeArgCount;
                    emitCallSequence(() -> hub.statements.emitExpr(funcPtrArg), realArgs,
                            () -> hub.core.line("INVOKE " + invokeArgCount), voidSafeReturnType(op), op.resolvedCallConvention);
                    return;
                }
                default:
                    break;
            }
        }
        if (op.left.type == TokenType.VARREF && hub.core.checker.declarations.getExterns().containsKey(op.left.text)) {
            emitExternCall(op, hub.core.checker.declarations.getExterns().get(op.left.text));
            return;
        }
        emitCall(op);
    }

    public Token singleBuiltinArg(Token op) {
        List<Token> args = new ArrayList<>();
        collectCommaArgs(op.right.childs.get(0), args);
        return args.get(0);
    }

    /**
     * Wraps one call in CC_START .. CC_END (first n arguments moved to ARGn registers) and adds PUSH_RET for a non-void result.
     * pushFuncPtr is non-null only for call(); regArgs are the register-transferred arguments; returnTypeOrNull null = void.
     * The convention is the one TypeChecker resolved, never re-derived. see docs/BytecodeEmitter.md, "Call sequence".
     */
    public void emitCallSequence(Runnable pushFuncPtr, List<Token> regArgs, Runnable emitCallInstruction,
            String returnTypeOrNull, String conventionName) {
        emitCallSequence(pushFuncPtr, regArgs, emitCallInstruction, returnTypeOrNull, conventionName, -1);
    }

    /** varargStartIndex: first true vararg of a variadic extern (-1 = none); only emitExternCall passes one. see docs/BytecodeEmitter.md, "Variadic calls". */
    public void emitCallSequence(Runnable pushFuncPtr, List<Token> regArgs, Runnable emitCallInstruction,
            String returnTypeOrNull, String conventionName, int varargStartIndex) {
        BytecodeEmitterCore.ArgCounters counters = new BytecodeEmitterCore.ArgCounters(hub.core.checker.model.getConfig().callingConventions.get(conventionName));
        hub.core.line("CC_START " + conventionName);
        if (pushFuncPtr != null) {
            pushFuncPtr.run();
        }
        // The register index is a running counter advanced by the words each argument occupied (a range takes two); ArgCounters tracks the banks.
        for (int argIndex = 0; argIndex < regArgs.size(); argIndex++) {
            Token arg = regArgs.get(argIndex);
            boolean isTrueVararg = varargStartIndex >= 0 && argIndex >= varargStartIndex;
            boolean promoteToDouble = isTrueVararg; // "in the ... part of a variadic call": an f32 is widened, any float gets win64's register duplication
            emitArgTransfer(arg, counters, promoteToDouble);
            if (arg.isOwnershipMoveSource) {
                // Null the moved owns slot right after its value is captured and before the call, so a throw inside the callee sees it null.
                // A by-value range is never a move source. Register hazard: see emitOwnershipMoveNullOut.
                hub.statements.emitOwnershipMoveNullOut(arg);
            }
        }
        if (varargStartIndex >= 0) {
            // SysV varargs: %al = xmm registers used. Only for a variadic call, just before the call instruction; see docs/BytecodeEmitter.md, "Variadic calls".
            hub.core.line("VARARGS_XMM_COUNT " + counters.floatRegistersUsed());
        }
        emitCallInstruction.run();
        hub.core.line("CC_END " + conventionName);
        if (returnTypeOrNull != null) {
            hub.core.line("PUSH_RET " + returnTypeOrNull);
        }
    }

    /** Transfers one argument into its register(s); a by-value range becomes its two u64 bounds. see docs/BytecodeEmitter.md, "Call sequence". */
    public void emitArgTransfer(Token arg, BytecodeEmitterCore.ArgCounters counters, boolean promoteToDouble) {
        Token unwrapped = unwrapParens(arg);
        if (BytecodeEmitterCore.BY_VALUE_RANGE_ARG.matcher(arg.resolvedType).matches()) {
            // A range's words are u64 bounds, never float.
            emitRangeArgWords(unwrapped, counters);
            return;
        }
        emitArgWord(arg, counters, promoteToDouble);
    }

    /** Range portion of emitArgTransfer; both words are int-bank u64. */
    public void emitRangeArgWords(Token unwrapped, BytecodeEmitterCore.ArgCounters counters) {
        if (unwrapped.type == TokenType.OPERATOR && unwrapped.text.equals("..")) {
            emitArgWord(unwrapped.left, counters, false);
            emitArgWord(unwrapped.right, counters, false);
            return;
        }
        String base = hub.qualifiedDotName(unwrapped);
        emitSyntheticArgWord(base + ".start", "indeterminate_u64", counters);
        emitSyntheticArgWord(base + ".end", "indeterminate_u64", counters);
    }

    /** Pushes one word and moves it to the next register of its bank; promoteToDouble inserts PROMOTE_F32_TO_F64 for a true f32 vararg. */
    public void emitArgWord(Token expr, BytecodeEmitterCore.ArgCounters counters, boolean promoteToDouble) {
        hub.statements.emitExpr(expr);
        if (promoteToDouble && BytecodeEmitterCore.isF32Canonical(expr.resolvedType)) {
            hub.core.line("PROMOTE_F32_TO_F64");
        }
        // promoteToDouble doubles as the win64 variadic float duplication flag (same precondition).
        emitArgTransferTail(expr.resolvedType, counters, promoteToDouble);
    }

    /** Pushes a synthesized qualified-name word (e.g. "x.start") and transfers it; never float. */
    public void emitSyntheticArgWord(String qualifiedName, String type, BytecodeEmitterCore.ArgCounters counters) {
        hub.core.line("PUSH " + qualifiedName + " " + type);
        emitArgTransferTail(type, counters, false);
    }

    /** Emits POP ARGn / POP FARGn if the word's bank has a register left, else leaves it on the stack. isTrueVarargFloat additionally duplicates the word into the same-index int register on a shared-position (win64) convention. see docs/BytecodeEmitter.md, "Variadic calls". */
    public void emitArgTransferTail(String type, BytecodeEmitterCore.ArgCounters counters, boolean isTrueVarargFloat) {
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
        String t = op.resolvedType;
        if (t == null || t.equals("imut_void") || t.equals("mut_void")) {
            return null;
        }
        return t;
    }

    /** SLEEP sleep_call duration: dedicated mnemonic naming the @sleep function (resolved by TypeChecker) with the duration type. see docs/BytecodeEmitter.md, "Sleep call". */
    public void emitSleepCall(Token op) {
        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        Token durationArg = argNodes.get(0);
        hub.statements.emitExpr(durationArg);
        hub.core.line("SLEEP " + op.resolvedCallTarget + " " + durationArg.resolvedType);
    }
    /** Extern call; a variadic one carries its pushed-argument count in EXTERN_CALL. see docs/BytecodeEmitter.md, "Variadic calls". */
    public void emitExternCall(Token op, TypeCheckerModel.ExternInfo info) {
        List<Token> args = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        int argCount = args.size();
        int varargStartIndex = info.hasVarargs ? info.paramTypes.size() : -1;
        emitCallSequence(null, args, () -> hub.core.line("EXTERN_CALL " + info.linkName + " " + argCount),
                voidSafeReturnType(op), op.resolvedCallConvention, varargStartIndex);
    }

    public void emitCall(Token op) {
        String funcName = resolveCallTargetForEmission(op.resolvedCallTarget);
        List<Token> args = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        // par/await are intercepted earlier (emitAsyncCall). A self-call (compared after dunder redirection) is RECURSIVE_CALL.
        String mnemonic = funcName.equals(hub.core.currentFuncMangledName) ? "RECURSIVE_CALL" : "CALL";
        // Staged before the argument sequence: the slot is only read by a deeper GT_UNWIND, after the real call runs.
        hub.core.stageCallSiteForUnwind(op);
        emitCallSequence(null, args, () -> hub.core.line(mnemonic + " " + funcName), voidSafeReturnType(op),
                op.resolvedCallConvention);
    }

    /**
     * Desugars a par/await site into ordinary ALLOC/NEW/GT_REGISTER/CALL bytecode with a hidden local __async_handle_N (emitted inline, not hoisted).
     * see docs/BytecodeEmitter.md, "par / await desugaring".
     */
    public void emitAsyncCall(Token op) {
        boolean isAwait = "AWAIT".equals(op.asyncCallKind);
        String handleStructName = op.asyncHandleStructName;
        String handleCanonical = op.asyncHandleCanonicalType;
        TypeCheckerModel.StructInfo handleStructInfo = hub.core.checker.declarations.getStructs().get(handleStructName);
        List<Token> args = new ArrayList<>();
        if (!op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), args);
        }
        String tempName = "__async_handle_" + (++hub.core.asyncHandleTempCounter);
        // The argument may contain calls that would consume the pending catch label: hold it back (as emitNew does).
        // Moved-in owns arguments are only let go once the thread really started; if the handle can not be made or the
        // thread can not be started they are destructed here instead (the failed 'par'/'await' never took them).
        final String asyncCatchLabel = hub.core.pendingTryCatchLabel;
        hub.core.pendingTryCatchLabel = null;
        final List<Token> movedSources = new ArrayList<>();
        final List<String> movedTemps = new ArrayList<>();
        final List<Token> savedDeferral = hub.core.deferredMoveNullOuts;
        final List<String> savedTempNames = hub.core.deferredTempNames;

        hub.core.line("ALLOC " + tempName + " " + handleCanonical);
        hub.core.line("ADDR " + tempName + " " + handleCanonical);
        if (handleStructInfo.classId != null) {
            hub.core.line("PUSH " + handleStructInfo.classId + " imut_u64");
        }
        for (BytecodeEmitterCore.StructLayoutEntry entry : hub.core.computeStructLayout(handleStructInfo).entries) {
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
                    if (!args.isEmpty()) {
                        Token argTok = args.get(0);
                        hub.core.deferredMoveNullOuts = movedSources;
                        hub.core.deferredTempNames = movedTemps;
                        hub.statements.emitExpr(argTok);
                        hub.core.deferredMoveNullOuts = savedDeferral;
                        hub.core.deferredTempNames = savedTempNames;
                        if (argTok.isOwnershipMoveSource && !movedSources.contains(argTok)) {
                            movedSources.add(argTok);
                        }
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
        }
        hub.core.line("NEW " + handleStructName);
        hub.core.requireGhostTableFunctionPresent("gt_register", op);
        hub.core.line("GT_REGISTER");
        // Same null check as 'new': a failed malloc or a failed registration leaves null here.
        hub.core.line("DUP_TOP");
        hub.core.line("PUSH null " + handleCanonical);
        hub.core.line("EQ " + handleCanonical + " " + handleCanonical + " imut_bool");
        hub.core.line("CMP");
        String handleOkLabel = hub.statements.newLabel("async_handle_ok");
        hub.core.line("JMP " + handleOkLabel);
        if (!hub.core.inGtSuppressedContext && asyncCatchLabel != null) {
            hub.emitDestructMovedSources(movedSources);
            hub.emitDestructTempNames(movedTemps, op);
            emitAsyncFailureJump(asyncCatchLabel, "out of memory");
        }
        hub.core.line(handleOkLabel + ":");
        hub.core.line("ASSIGN " + handleCanonical + " " + handleCanonical + " " + handleCanonical);

        String glueDecorator = isAwait ? "await_call" : "par_call";
        TypeCheckerModel.FuncInfo glueInfo = hub.core.checker.declarations.getGhostTableFunction(glueDecorator);
        if (glueInfo == null) {
            throw new CompilerException("bytecode", op.file, op.line,
                    "emitting a '" + (isAwait ? "await" : "par") + "' call here requires a function "
                            + "decorated '@" + glueDecorator + "' to exist somewhere in the compilation unit "
                            + "(none was found)");
        }
        String trampolineName = op.asyncTrampolineName;
        TypeCheckerModel.FuncInfo trampolineInfo = hub.core.checker.declarations.getFunctions().get(trampolineName).get(0);
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
        if (!hub.core.inGtSuppressedContext && asyncCatchLabel != null) {
            hub.core.requireGhostTableFunctionPresent("gt_destruct", op);
            hub.core.line("GT_DESTRUCT " + tempName);
            hub.emitDestructMovedSources(movedSources);
            hub.emitDestructTempNames(movedTemps, op);
            emitAsyncFailureJump(asyncCatchLabel, "cannot start thread");
        }
        hub.core.line(threadOkLabel + ":");
        for (Token moved : movedSources) {
            hub.statements.emitOwnershipMoveNullOut(moved); // the thread now owns the argument
        }

        boolean isVoidResult = isVoidCanonical(op.resolvedType);
        if (isAwait) {
            if (!isVoidResult) {
                String resultCanonical = op.resolvedType;
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
    public String funcPointerCanonicalOf(TypeCheckerModel.FuncInfo info) {
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
        if (node.type == TokenType.OPERATOR && node.text.equals(",")) {
            collectCommaArgs(node.left, out);
            collectCommaArgs(node.right, out);
        } else {
            out.add(node);
        }
    }
}

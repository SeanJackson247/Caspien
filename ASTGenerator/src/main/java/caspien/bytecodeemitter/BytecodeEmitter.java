package caspien.bytecodeemitter;
import caspien.typechecker.TypeChecker;
import caspien.*;
import caspien.hob.HOBStatement;

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

public class BytecodeEmitter extends BytecodeEmitterOperations {


    public void emitLookup(Token op) {
        String leftType = op.left.resolvedType;
        if (op.left.type == TokenType.VARREF && leftType != null && !hasStorage(leftType)
                && leftType.endsWith("]") && !leftType.contains("dynarray")
                && op.resolvedType != null && op.resolvedType.matches("(mut|imut|indeterminate)_(u8|u16|u32|u64|s8|s16|s32|s64|f32|f64|bool|char)")) {
            // Scalar element of a fixed array variable: take the element address (ADDR + LOOKUP_LHS) and load only it, instead of pushing the whole array. Aggregate elements keep the old path (DEREF carries no size).
            line("ADDR " + op.left.text + " " + leftType);
            Token idx = op.right.childs.get(0);
            emitExpr(idx);
            line("LOOKUP_LHS " + leftType + " " + idx.resolvedType + " " + op.resolvedType);
            line("DEREF " + op.resolvedType);
            return;
        }
        emitExpr(op.left);
        Token indexExpr = op.right.childs.get(0);
        emitExpr(indexExpr);
        line("LOOKUP " + op.left.resolvedType + " " + indexExpr.resolvedType + " " + op.resolvedType);
    }

    /** 'x fits T' as unsigned 64-bit compares only; see docs/BytecodeEmitter.md, "fits". */
    public void emitFits(Token node) {
        String xb = node.fitsSourceBase;
        String tb = node.fitsTargetBase;
        boolean xSigned = xb.charAt(0) == 's';
        boolean tSigned = tb.charAt(0) == 's';
        int xw = Integer.parseInt(xb.substring(1));
        int tw = Integer.parseInt(tb.substring(1));
        String u64 = "indeterminate_u64";
        emitExpr(node.left);
        if (xw < 64) {
            line((xSigned ? "SEXT " : "ZEXT ") + node.left.resolvedType + " " + u64);
        }
        java.math.BigInteger one = java.math.BigInteger.ONE;
        java.math.BigInteger tmax = tSigned ? one.shiftLeft(tw - 1).subtract(one) : one.shiftLeft(tw).subtract(one);
        java.math.BigInteger tmin = tSigned ? one.shiftLeft(tw - 1).negate() : java.math.BigInteger.ZERO;
        boolean alwaysTrue;
        if (!xSigned || !tSigned) {
            // x64 <= Tmax (unsigned). u64 target: an unsigned x always fits; a signed x fits iff non-negative.
            if (tb.equals("u64")) {
                tmax = xSigned ? one.shiftLeft(63).subtract(one) : java.math.BigInteger.ZERO;
                alwaysTrue = !xSigned;
            } else {
                alwaysTrue = false;
            }
            if (alwaysTrue) {
                line("PUSH 0 " + u64);
                line("GT_EQ " + u64 + " " + u64 + " imut_bool");
            } else {
                line("PUSH " + tmax + " " + u64);
                line("LT_EQ " + u64 + " " + u64 + " imut_bool");
            }
            return;
        }
        // both signed
        if (tw == 64 || (xw <= tw)) {
            line("PUSH 0 " + u64);
            line("GT_EQ " + u64 + " " + u64 + " imut_bool");
            return;
        }
        line("PUSH " + tmin.negate() + " " + u64);
        line("ADD " + u64 + " " + u64 + " " + u64);
        line("PUSH " + tmax.subtract(tmin) + " " + u64);
        line("LT_EQ " + u64 + " " + u64 + " imut_bool");
    }

    /** wrap:<T>(x) / sat:<T>(x), total integer conversions; see docs/BytecodeEmitter.md, "wrap and sat". */
    public void emitConvert(Token op) {
        final Token arg = singleBuiltinArg(op);
        if (op.isFloatConvert || (op.resolvedType != null && isFloatCanonical(op.resolvedType))) {
            // wrap:<f32>(x) / wrap:<f64>(x): a float conversion (a literal argument was already retagged in place).
            emitExpr(arg);
            if (op.isFloatConvert) {
                line("FCONV " + arg.resolvedType + " " + op.resolvedType);
            }
            return;
        }
        final boolean isSat = op.left.text.equals("sat");
        final String xb = baseOfCanonical(arg.resolvedType);
        final String tb = baseOfCanonical(op.resolvedType);
        final boolean xs = xb.charAt(0) == 's';
        final boolean ts = tb.charAt(0) == 's';
        final int xw = Integer.parseInt(xb.substring(1));
        final int tw = Integer.parseInt(tb.substring(1));
        final String u64 = "indeterminate_u64";
        final String ext = (xs ? "SEXT " : "ZEXT ") + arg.resolvedType + " " + u64;
        final java.math.BigInteger one = java.math.BigInteger.ONE;
        boolean alwaysFits = tw >= xw && (xs ? ts : (!ts || tw > xw));
        if (!isSat || alwaysFits) {
            emitExpr(arg);
            if (tw > xw) {
                line((xs ? "SEXT " : "ZEXT ") + arg.resolvedType + " " + op.resolvedType);
            } else if (tw < xw) {
                line("TRUNC " + arg.resolvedType + " " + op.resolvedType);
            }
            return;
        }
        final java.math.BigInteger tmax = ts ? one.shiftLeft(tw - 1).subtract(one) : one.shiftLeft(tw).subtract(one);
        final String i63 = one.shiftLeft(63).subtract(one).toString();
        final java.util.function.Consumer<String> bin = mn -> line(mn + " " + u64 + " " + u64 + " " + u64);
        final Runnable pushX = () -> {
            emitExpr(arg);
            if (xw < 64) {
                line(ext);
            }
        };
        // Xc: X for unsigned x; max(X,0) for signed x targeting an unsigned T
        final Runnable pushV;
        boolean needClampAbove;
        if (!xs) {
            pushV = pushX;
            needClampAbove = true;
        } else if (!ts) {
            pushV = () -> {                       // X * (X <=u 2^63-1)
                pushX.run();
                pushX.run();
                line("PUSH " + i63 + " " + u64);
                bin.accept("LT_EQ");
                bin.accept("MUL");
            };
            needClampAbove = tw < 64 && one.shiftLeft(xw - 1).subtract(one).compareTo(tmax) > 0;
        } else {
            pushV = null;                         // signed -> narrower signed: handled below
            needClampAbove = false;
        }
        if (pushV != null) {
            pushV.run();
            if (needClampAbove) {
                pushV.run();
                line("PUSH " + tmax + " " + u64);
                bin.accept("GT");
                line("PUSH " + tmax + " " + u64);
                pushV.run();
                bin.accept("SUB");
                bin.accept("MUL");
                bin.accept("ADD");
            }
        } else {
            java.math.BigInteger absMin = one.shiftLeft(tw - 1);
            java.math.BigInteger range = one.shiftLeft(tw).subtract(one);
            pushX.run();                                       // X
            pushX.run();
            line("PUSH " + absMin + " " + u64);
            bin.accept("ADD");
            line("PUSH " + range + " " + u64);
            bin.accept("GT");                                  // notInside
            line("PUSH 0 " + u64);
            line("PUSH " + absMin + " " + u64);
            bin.accept("SUB");                                 // Tmin (wrapped)
            pushX.run();
            line("PUSH " + i63 + " " + u64);
            bin.accept("LT_EQ");                               // X >= 0
            line("PUSH " + range + " " + u64);
            bin.accept("MUL");
            bin.accept("ADD");                                 // fallback
            pushX.run();
            bin.accept("SUB");                                 // fallback - X
            bin.accept("MUL");
            bin.accept("ADD");                                 // X + notInside*(fallback-X)
        }
        if (tw < 64) {
            line("TRUNC " + u64 + " " + op.resolvedType);
        }
    }
    /** "as": no instruction for an alias cast, FCONV / TRUNC / SEXT / ZEXT otherwise. see docs/BytecodeEmitter.md, "as casts". */
    public void emitAs(Token op) {
        emitExpr(op.left);
        if (op.isFloatConvert) {
            line("FCONV " + op.left.resolvedType + " " + op.resolvedType);
            return;
        }
        if (op.isTruncCast) {
            line("TRUNC " + op.left.resolvedType + " " + op.resolvedType);
            return;
        }
        if (op.isAliasCast) {
            return;
        }
        line((op.isSignedWideningCast ? "SEXT " : "ZEXT ") + op.left.resolvedType + " " + op.resolvedType);
    }

    /** Both EnumName.Variant and instance.member chains: one PUSH of the dot-joined name when the chain is nameable, else base + DOT / DOT_LHS+DEREF. see docs/BytecodeEmitter.md, "Dot access". */
    public void emitDot(Token op) {
        if (op.catchMember != null) {
            String param = op.catchTry.catchParamName;
            if (op.catchMember.equals("msg")) {
                line("PUSH " + param + " static_imut_string");
            } else {
                String arr = "mut_u64[" + (TraceConfig.depth + 1) + "]";
                line("PUSH " + param + "$trace " + arr);
                line("ADDR_OF AUTO " + arr + " auto_indeterminate_u64[" + (TraceConfig.depth + 1) + "]");
            }
            return;
        }
        if (isQualifiedNameableDot(op)) {
            line("PUSH " + qualifiedDotName(op) + " " + op.resolvedType);
            return;
        }
        // Not nameable (base is a LOOKUP/call, or a segment is a pointer): emit the base, then DOT (inline base) or DOT_LHS+DEREF (pointer base), decided per level.
        emitExpr(op.left);
        line("PUSH_FIELDNAME " + op.right.text + " " + op.resolvedType);
        if (hasStorage(op.left.resolvedType)) {
            line("DOT_LHS " + op.left.resolvedType + " " + op.resolvedType + " " + op.resolvedType);
            line("DEREF " + op.resolvedType);
        } else {
            line("DOT " + op.left.resolvedType + " " + op.resolvedType + " " + op.resolvedType);
        }
    }

    /** True when the whole chain is storage-free and rooted at a VARREF (a LOOKUP/CALL .text is a marker, not a name). */
    public boolean isQualifiedNameableDot(Token node) {
        if (node.catchMember != null) {
            return false;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".")) {
            // Per level: the segment dotted into must be storage-free too, the same bail-out AddressLoweringPass.resolveAddress applies.
            return isQualifiedNameableDot(node.left) && !hasStorage(node.left.resolvedType);
        }
        return node.type == TokenType.VARREF && !hasStorage(node.resolvedType);
    }

    public String qualifiedDotName(Token node) {
        if (node.type == TokenType.OPERATOR && node.text.equals(".")) {
            return qualifiedDotName(node.left) + "." + node.right.text;
        }
        return node.text;
    }

    /** Struct literal: optional hidden ___type push, then members in padded layout order (declaration order, not literal order). Single choke point for `new X{..}` and bare `X{..}`. see docs/BytecodeEmitter.md, "Struct literals". */
    public void emitInstantiate(Token op) {
        String structName = op.left.text;
        TypeChecker.StructInfo structInfo = checker.getStructs().get(structName);
        if (structInfo.classId != null) {
            line("PUSH " + structInfo.classId + " imut_u64");
        }

        if (op.isLockViolationConstruct) {
            // OTC lock violation: only the lock/discriminant value is pushed, then STACK_LOCK <structName>.
            Token lockAssignNode = op.right.childs.get(0).childs.get(0);
            Token lockValue = lockAssignNode.right;
            emitExpr(lockValue);
            if (lockValue.isOwnershipMoveSource) {
                emitOwnershipMoveNullOut(lockValue);
            }
            line("STACK_LOCK " + structName);
            return;
        }

        Map<String, Token> valueByMember = new HashMap<>();
        for (Token lineTok : op.right.childs) {
            Token assignNode = lineTok.childs.get(0);
            valueByMember.put(assignNode.left.text, assignNode.right);
        }
        // Walk the padded layout: ___type already pushed, each padding gap is a STACK_LOCK.
        for (StructLayoutEntry entry : computeStructLayout(structInfo).entries) {
            if (entry.memberName == null) {
                line("STACK_LOCK " + entry.paddingBytes);
                continue;
            }
            if (entry.memberName.equals("___type")) {
                continue;
            }
            Token value = valueByMember.get(entry.memberName);
            if (deferredTempNames != null && isOwnsTemporary(value)) {
                emitOwnsTemporaryViaLocal(value, deferredTempNames);
                continue;
            }
            emitExpr(value);
            if (value.isOwnershipMoveSource) {
                // Null the move source right away: a later field value may contain a throwing call.
                if (deferredMoveNullOuts != null) {
                    deferredMoveNullOuts.add(value); // inside `new`: nulled only once the allocation has succeeded
                } else {
                    emitOwnershipMoveNullOut(value);
                }
            }
        }
    }

    /** Declares a compiler-internal local of canonical type `type` in the function being emitted; its ALLOC is added to the function's alloc block when the function ends. */
    public String declareHiddenLocal(String type) {
        String name = "$mv" + (++hiddenLocalCounter);
        lateAllocs.add(HOBStatement.fromText("ALLOC " + name + " " + type, ORIGIN));
        return name;
    }

    /** An `owns` value that exists only as a computed result (a call), not in any variable or field. */
    public boolean isOwnsTemporary(Token value) {
        return value.resolvedType != null && value.resolvedType.startsWith("owns_") && !value.isOwnershipMoveSource
                && value.type == TokenType.OPERATOR && !value.text.equals(".") && lateAllocs != null;
    }

    /** Evaluates the temporary into a hidden local and pushes it from there, so a failing allocation can still free it. */
    public void emitOwnsTemporaryViaLocal(Token value, List<String> tempNames) {
        String type = value.resolvedType;
        String tmp = declareHiddenLocal(type);
        line("ADDR " + tmp + " " + type);
        emitExpr(value);
        line("ASSIGN " + type + " " + type + " " + type);
        line("PUSH " + tmp + " " + type);
        tempNames.add(tmp);
    }

    public void emitDestructTempNames(List<String> names, Token at) {
        for (String n : names) {
            requireGhostTableFunctionPresent("gt_destruct", at);
            line("GT_DESTRUCT " + n);
        }
    }

    /** `GT_DESTRUCT` for each moved-in source that is a plain `owns` variable or field path; temporaries are not addressable and are skipped. */
    public void emitDestructMovedSources(List<Token> sources) {
        for (Token t : sources) {
            if (t.resolvedType == null || !t.resolvedType.startsWith("owns")) {
                continue;
            }
            if (t.type == TokenType.VARREF) {
                requireGhostTableFunctionPresent("gt_destruct", t);
                line("GT_DESTRUCT " + t.text);
            } else if (t.type == TokenType.OPERATOR && t.text.equals(".") && isQualifiedNameableDot(t)) {
                requireGhostTableFunctionPresent("gt_destruct", t);
                line("GT_DESTRUCT " + qualifiedDotName(t));
            }
        }
    }

    /** `new ctor(..)`: construct into a hidden inline local through emitStructRvoCallSequence, then push it whole. see docs/BytecodeEmitter.md, "new". */
    public void emitNewFromStructRvoCall(Token callOp) {
        // Struct name from the callee return type, else from the call node's resolvedType.
        String funcName = resolveCallTargetForEmission(callOp.resolvedCallTarget);
        TypeChecker.FuncInfo targetInfo = findFuncInfoByMangledName(funcName);
        String structName = targetInfo != null ? targetInfo.returnType.baseType
                : callOp.resolvedType.substring(callOp.resolvedType.lastIndexOf('_') + 1);
        String tempName = "$new_ctor_tmp" + (++labelCounter);
        String structType = "mut_" + structName;
        String rawPtrType = "raw_indeterminate_" + structName;
        line("ALLOC " + tempName + " " + structType);
        emitStructRvoCallSequence(callOp, rawPtrType, () -> {
            line("PUSH " + tempName + " " + structType);
            line("ADDR_OF RAW " + structType + " " + rawPtrType);
        });
        line("PUSH " + tempName + " " + structType);
    }
    /** new: operand, NEW type, GT_REGISTER, null check jumping to the local catch; moved-in sources are nulled only after success. see docs/BytecodeEmitter.md, "new". */
    public void emitNew(Token op) {
        Token unwrapped = unwrapMutWrappers(op.left);
        List<Token> savedDeferral = deferredMoveNullOuts;
        List<Token> movedSources = new ArrayList<>();
        deferredMoveNullOuts = movedSources;
        final List<String> savedTempNames = deferredTempNames;
        final List<String> movedTemps = new ArrayList<>();
        deferredTempNames = movedTemps;
        // A call nested in the field values would consume the pending catch label (meant for the call-site staging of the
        // first call), and the allocation check below would then have none -- an unchecked null: hold it back and restore it.
        final String newCatchLabel = pendingTryCatchLabel;
        pendingTryCatchLabel = null;
        if (unwrapped.isStructRvoCall) {
            // A '@throws' constructor is itself the call the try/'?' guards: it must stage the catch label (an unstaged
            // throw would unwind past this function's own catch into the caller). The label is restored for the allocation check below.
            TypeChecker.FuncInfo ctorInfo = findFuncInfoByMangledName(resolveCallTargetForEmission(unwrapped.resolvedCallTarget));
            if (ctorInfo != null && ctorInfo.isThrows) {
                pendingTryCatchLabel = newCatchLabel;
            }
            emitNewFromStructRvoCall(unwrapped);
        } else {
            emitExpr(op.left);
        }
        deferredMoveNullOuts = savedDeferral;
        deferredTempNames = savedTempNames;
        pendingTryCatchLabel = newCatchLabel;
        requireGhostTableFunctionPresent("gt_register", op);
        String constructedType = op.resolvedType.substring("owns_".length());
        if (constructedType.startsWith("some_")) {
            constructedType = constructedType.substring("some_".length());
        }
        line("NEW " + constructedType);
        // Registered right away: when the ghost table can not grow, GT_REGISTER frees the block and leaves null, so the
        // check below sees a failed registration exactly like a failed allocation (same catch, same freeing of moved sources).
        line("GT_REGISTER");
        line("DUP_TOP");
        line("PUSH null " + op.resolvedType);
        line("EQ " + op.resolvedType + " " + op.resolvedType + " imut_bool");
        line("CMP");
        String okLabel = newLabel("new_ok");
        line("JMP " + okLabel);
        if (!inGtSuppressedContext && pendingTryCatchLabel != null) {
            emitDestructMovedSources(movedSources); // the moved-in values are lost with the failed allocation: free them
            emitDestructTempNames(movedTemps, op);
            String oomStringId = hoistedStringId("out of memory");
            emitTraceCapture();
            line("ADDR gt_error_message static_imut_string");
            line("PUSH " + oomStringId + " static_imut_string");
            line("ASSIGN static_imut_string static_imut_string static_imut_string");
            line("JMP " + pendingTryCatchLabel);
        }
        line(okLabel + ":");
        for (Token moved : movedSources) {
            emitOwnershipMoveNullOut(moved); // the allocation took ownership: now the sources let go
        }
    }

    /** Null check after NEW_DYN / NEW_FROM_STRING / RESIZE, the same sequence emitNew uses; expects the value on top of the stack. see docs/BytecodeEmitter.md, "Allocation failure check". */
    public void emitAllocFailureCheck(Token op) {
        emitAllocFailureCheck(op, java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    /** `movedSources`: owns variables just moved into the allocation (e.g. the elements of `dyn([a, b])`); freed if it fails. */
    public void emitAllocFailureCheck(Token op, List<Token> movedSources, List<String> movedTemps) {
        line("DUP_TOP");
        line("PUSH null " + op.resolvedType);
        line("EQ " + op.resolvedType + " " + op.resolvedType + " imut_bool");
        line("CMP");
        String okLabel = newLabel("alloc_ok");
        line("JMP " + okLabel);
        if (!inGtSuppressedContext && pendingTryCatchLabel != null) {
            emitDestructMovedSources(movedSources);
            emitDestructTempNames(movedTemps, op);
            String oomStringId = hoistedStringId("out of memory");
            emitTraceCapture();
            line("ADDR gt_error_message static_imut_string");
            line("PUSH " + oomStringId + " static_imut_string");
            line("ASSIGN static_imut_string static_imut_string static_imut_string");
            line("JMP " + pendingTryCatchLabel);
        }
        line(okLabel + ":");
    }
    /** ADDR_OF auto/raw/ref (nothing for `static`); see docs/BytecodeEmitter.md, "Address-of". */
    public void emitAddressOf(Token op) {
        emitExpr(op.left);
        if (op.text.equals("static")) {
            return;
        }
        line("ADDR_OF " + op.text.toUpperCase() + " " + op.left.resolvedType + " " + op.resolvedType);
    }

    public String literalValueOf(Token node) {
        switch (node.type) {
            case STRING:
                // Hoisted: the quoted text appears once in a STRING declaration at the top of the output.
                return hoistedStringId(node.literalValue);
            case CHAR:
                return "'" + escapeForBytecode(node.literalValue) + "'";
            case NULL:
                return "null";
            default:
                return node.text; // INTEGER, FLOAT, BOOL
        }
    }

    /** Re-escapes a decoded char/string value for the text bytecode: every lexer escape (\\ \" \n \t \r \0 \b \f \a \v). */
    public String escapeForBytecode(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                case '\r': sb.append("\\r"); break;
                case '\0': sb.append("\\0"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case 7: sb.append("\\a"); break; // bell -- no Java char-literal shorthand
                case 11: sb.append("\\v"); break; // vertical tab -- no Java char-literal shorthand
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}

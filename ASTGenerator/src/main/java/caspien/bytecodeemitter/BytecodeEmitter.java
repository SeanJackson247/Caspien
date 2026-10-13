package caspien.bytecodeemitter;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.DocComments;
import caspien.Token;
import caspien.TokenType;
import caspien.TraceConfig;
import caspien.typechecker.TypeChecker;
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

public final class BytecodeEmitter {
    final BytecodeEmitterCore core = new BytecodeEmitterCore(this);
    final BytecodeEmitterStatements statements = new BytecodeEmitterStatements(this);
    final BytecodeEmitterOperations operations = new BytecodeEmitterOperations(this);

    // Public surface used by Frontend and FixTool; everything else stays inside the package.
    public final Map<String, DocComments.Doc> funcTermLabels = core.funcTermLabels;
    public final Map<String, DocComments.Doc> loopTermLabels = core.loopTermLabels;
    public final Map<String, Long> spinBounds = core.spinBounds;
    public final List<String[]> termSites = core.termSites;
    public final Map<String, String> sourcePos = core.sourcePos;

    public BytecodeProgram emit(List<Token> rootLines, TypeChecker checker) {
        return core.emit(rootLines, checker);
    }

    public void emitLookup(Token op) {
        String leftType = op.left.chk.resolvedType;
        if (op.left.type == TokenType.VARREF && leftType != null && !BytecodeEmitterCore.hasStorage(leftType)
                && leftType.endsWith("]") && !leftType.contains("dynarray")
                && op.chk.resolvedType != null && op.chk.resolvedType.matches("(mut|imut|indeterminate)_(u8|u16|u32|u64|s8|s16|s32|s64|f32|f64|bool|char)")) {
            // Scalar element of a fixed array variable: take the element address (ADDR + LOOKUP_LHS) and load only it, instead of pushing the whole array. Aggregate elements keep the old path (DEREF carries no size).
            core.line("ADDR " + op.left.text + " " + leftType);
            Token idx = op.right.childs.get(0);
            statements.emitExpr(idx);
            core.line("LOOKUP_LHS " + leftType + " " + idx.chk.resolvedType + " " + op.chk.resolvedType);
            core.line("DEREF " + op.chk.resolvedType);
            return;
        }
        statements.emitExpr(op.left);
        Token indexExpr = op.right.childs.get(0);
        statements.emitExpr(indexExpr);
        core.line("LOOKUP " + op.left.chk.resolvedType + " " + indexExpr.chk.resolvedType + " " + op.chk.resolvedType);
    }

    /** 'x fits T' as unsigned 64-bit compares only; see docs/BytecodeEmitter.md, "fits". */
    public void emitFits(Token node) {
        String xb = node.chk.fitsSourceBase;
        String tb = node.chk.fitsTargetBase;
        boolean xSigned = xb.charAt(0) == 's';
        boolean tSigned = tb.charAt(0) == 's';
        int xw = Integer.parseInt(xb.substring(1));
        int tw = Integer.parseInt(tb.substring(1));
        String u64 = "indeterminate_u64";
        statements.emitExpr(node.left);
        if (xw < 64) {
            core.line((xSigned ? "SEXT " : "ZEXT ") + node.left.chk.resolvedType + " " + u64);
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
                core.line("PUSH 0 " + u64);
                core.line("GT_EQ " + u64 + " " + u64 + " imut_bool");
            } else {
                core.line("PUSH " + tmax + " " + u64);
                core.line("LT_EQ " + u64 + " " + u64 + " imut_bool");
            }
            return;
        }
        // both signed
        if (tw == 64 || (xw <= tw)) {
            core.line("PUSH 0 " + u64);
            core.line("GT_EQ " + u64 + " " + u64 + " imut_bool");
            return;
        }
        core.line("PUSH " + tmin.negate() + " " + u64);
        core.line("ADD " + u64 + " " + u64 + " " + u64);
        core.line("PUSH " + tmax.subtract(tmin) + " " + u64);
        core.line("LT_EQ " + u64 + " " + u64 + " imut_bool");
    }

    /** wrap:<T>(x) / sat:<T>(x), total integer conversions; see docs/BytecodeEmitter.md, "wrap and sat". */
    public void emitConvert(Token op) {
        final Token arg = operations.singleBuiltinArg(op);
        if (op.chk.isFloatConvert || (op.chk.resolvedType != null && BytecodeEmitterCore.isFloatCanonical(op.chk.resolvedType))) {
            // wrap:<f32>(x) / wrap:<f64>(x): a float conversion (a literal argument was already retagged in place).
            statements.emitExpr(arg);
            if (op.chk.isFloatConvert) {
                core.line("FCONV " + arg.chk.resolvedType + " " + op.chk.resolvedType);
            }
            return;
        }
        final boolean isSat = op.left.text.equals("sat");
        final String xb = BytecodeEmitterCore.baseOfCanonical(arg.chk.resolvedType);
        final String tb = BytecodeEmitterCore.baseOfCanonical(op.chk.resolvedType);
        final boolean xs = xb.charAt(0) == 's';
        final boolean ts = tb.charAt(0) == 's';
        final int xw = Integer.parseInt(xb.substring(1));
        final int tw = Integer.parseInt(tb.substring(1));
        final String u64 = "indeterminate_u64";
        final String ext = (xs ? "SEXT " : "ZEXT ") + arg.chk.resolvedType + " " + u64;
        final java.math.BigInteger one = java.math.BigInteger.ONE;
        boolean alwaysFits = tw >= xw && (xs ? ts : (!ts || tw > xw));
        if (!isSat || alwaysFits) {
            statements.emitExpr(arg);
            if (tw > xw) {
                core.line((xs ? "SEXT " : "ZEXT ") + arg.chk.resolvedType + " " + op.chk.resolvedType);
            } else if (tw < xw) {
                core.line("TRUNC " + arg.chk.resolvedType + " " + op.chk.resolvedType);
            }
            return;
        }
        final java.math.BigInteger tmax = ts ? one.shiftLeft(tw - 1).subtract(one) : one.shiftLeft(tw).subtract(one);
        final String i63 = one.shiftLeft(63).subtract(one).toString();
        final java.util.function.Consumer<String> bin = mn -> core.line(mn + " " + u64 + " " + u64 + " " + u64);
        final Runnable pushX = () -> {
            statements.emitExpr(arg);
            if (xw < 64) {
                core.line(ext);
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
                core.line("PUSH " + i63 + " " + u64);
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
                core.line("PUSH " + tmax + " " + u64);
                bin.accept("GT");
                core.line("PUSH " + tmax + " " + u64);
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
            core.line("PUSH " + absMin + " " + u64);
            bin.accept("ADD");
            core.line("PUSH " + range + " " + u64);
            bin.accept("GT");                                  // notInside
            core.line("PUSH 0 " + u64);
            core.line("PUSH " + absMin + " " + u64);
            bin.accept("SUB");                                 // Tmin (wrapped)
            pushX.run();
            core.line("PUSH " + i63 + " " + u64);
            bin.accept("LT_EQ");                               // X >= 0
            core.line("PUSH " + range + " " + u64);
            bin.accept("MUL");
            bin.accept("ADD");                                 // fallback
            pushX.run();
            bin.accept("SUB");                                 // fallback - X
            bin.accept("MUL");
            bin.accept("ADD");                                 // X + notInside*(fallback-X)
        }
        if (tw < 64) {
            core.line("TRUNC " + u64 + " " + op.chk.resolvedType);
        }
    }
    /** "as": no instruction for an alias cast, FCONV / TRUNC / SEXT / ZEXT otherwise. see docs/BytecodeEmitter.md, "as casts". */
    public void emitAs(Token op) {
        statements.emitExpr(op.left);
        if (op.chk.isFloatConvert) {
            core.line("FCONV " + op.left.chk.resolvedType + " " + op.chk.resolvedType);
            return;
        }
        if (op.chk.isTruncCast) {
            core.line("TRUNC " + op.left.chk.resolvedType + " " + op.chk.resolvedType);
            return;
        }
        if (op.chk.isAliasCast) {
            return;
        }
        core.line((op.chk.isSignedWideningCast ? "SEXT " : "ZEXT ") + op.left.chk.resolvedType + " " + op.chk.resolvedType);
    }

    /** Both EnumName.Variant and instance.member chains: one PUSH of the dot-joined name when the chain is nameable, else base + DOT / DOT_LHS+DEREF. see docs/BytecodeEmitter.md, "Dot access". */
    public void emitDot(Token op) {
        if (op.chk.catchMember != null) {
            String param = op.chk.catchTry.catchParamName;
            if (op.chk.catchMember.equals("msg")) {
                core.line("PUSH " + param + " static_imut_string");
            } else {
                String arr = "mut_u64[" + (TraceConfig.depth + 1) + "]";
                core.line("PUSH " + param + "$trace " + arr);
                core.line("ADDR_OF AUTO " + arr + " auto_indeterminate_u64[" + (TraceConfig.depth + 1) + "]");
            }
            return;
        }
        if (isQualifiedNameableDot(op)) {
            core.line("PUSH " + qualifiedDotName(op) + " " + op.chk.resolvedType);
            return;
        }
        // Not nameable (base is a LOOKUP/call, or a segment is a pointer): emit the base, then DOT (inline base) or DOT_LHS+DEREF (pointer base), decided per level.
        statements.emitExpr(op.left);
        core.line("PUSH_FIELDNAME " + op.right.text + " " + op.chk.resolvedType);
        if (BytecodeEmitterCore.hasStorage(op.left.chk.resolvedType)) {
            core.line("DOT_LHS " + op.left.chk.resolvedType + " " + op.chk.resolvedType + " " + op.chk.resolvedType);
            core.line("DEREF " + op.chk.resolvedType);
        } else {
            core.line("DOT " + op.left.chk.resolvedType + " " + op.chk.resolvedType + " " + op.chk.resolvedType);
        }
    }

    /** True when the whole chain is storage-free and rooted at a VARREF (a LOOKUP/CALL .text is a marker, not a name). */
    public boolean isQualifiedNameableDot(Token node) {
        if (node.chk.catchMember != null) {
            return false;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".")) {
            // Per level: the segment dotted into must be storage-free too, the same bail-out AddressLoweringPass.resolveAddress applies.
            return isQualifiedNameableDot(node.left) && !BytecodeEmitterCore.hasStorage(node.left.chk.resolvedType);
        }
        return node.type == TokenType.VARREF && !BytecodeEmitterCore.hasStorage(node.chk.resolvedType);
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
        TypeCheckerModel.StructInfo structInfo = core.checker.declarations.getStructs().get(structName);
        if (structInfo.classId != null) {
            core.line("PUSH " + structInfo.classId + " imut_u64");
        }

        if (op.chk.isLockViolationConstruct) {
            // OTC lock violation: only the lock/discriminant value is pushed, then STACK_LOCK <structName>.
            Token lockAssignNode = op.right.childs.get(0).childs.get(0);
            Token lockValue = lockAssignNode.right;
            statements.emitExpr(lockValue);
            if (lockValue.chk.isOwnershipMoveSource) {
                statements.emitOwnershipMoveNullOut(lockValue);
            }
            core.line("STACK_LOCK " + structName);
            return;
        }

        Map<String, Token> valueByMember = new HashMap<>();
        for (Token lineTok : op.right.childs) {
            Token assignNode = lineTok.childs.get(0);
            valueByMember.put(assignNode.left.text, assignNode.right);
        }
        // Walk the padded layout: ___type already pushed, each padding gap is a STACK_LOCK.
        for (BytecodeEmitterCore.StructLayoutEntry entry : core.computeStructLayout(structInfo).entries) {
            if (entry.memberName == null) {
                core.line("STACK_LOCK " + entry.paddingBytes);
                continue;
            }
            if (entry.memberName.equals("___type")) {
                continue;
            }
            Token value = valueByMember.get(entry.memberName);
            if (core.deferredTempNames != null && isOwnsTemporary(value)) {
                emitOwnsTemporaryViaLocal(value, core.deferredTempNames);
                continue;
            }
            statements.emitExpr(value);
            if (value.chk.isOwnershipMoveSource) {
                // Null the move source right away: a later field value may contain a throwing call.
                if (core.deferredMoveNullOuts != null) {
                    core.deferredMoveNullOuts.add(value); // inside `new`: nulled only once the allocation has succeeded
                } else {
                    statements.emitOwnershipMoveNullOut(value);
                }
            }
        }
    }

    /** Declares a compiler-internal local of canonical type `type` in the function being emitted; its ALLOC is added to the function's alloc block when the function ends. */
    public String declareHiddenLocal(String type) {
        String name = "$mv" + (++core.hiddenLocalCounter);
        core.lateAllocs.add(BytecodeStatement.fromText("ALLOC " + name + " " + type, BytecodeEmitterCore.ORIGIN));
        return name;
    }

    /** An `owns` value that exists only as a computed result (a call), not in any variable or field. */
    public boolean isOwnsTemporary(Token value) {
        return value.chk.resolvedType != null && value.chk.resolvedType.startsWith("owns_") && !value.chk.isOwnershipMoveSource
                && value.type == TokenType.OPERATOR && !value.text.equals(".") && core.lateAllocs != null;
    }

    /** Evaluates the temporary into a hidden local and pushes it from there, so a failing allocation can still free it. */
    public void emitOwnsTemporaryViaLocal(Token value, List<String> tempNames) {
        String type = value.chk.resolvedType;
        String tmp = declareHiddenLocal(type);
        core.line("ADDR " + tmp + " " + type);
        statements.emitExpr(value);
        core.line("ASSIGN " + type + " " + type + " " + type);
        core.line("PUSH " + tmp + " " + type);
        tempNames.add(tmp);
    }

    public void emitDestructTempNames(List<String> names, Token at) {
        for (String n : names) {
            core.requireGhostTableFunctionPresent("gt_destruct", at);
            core.line("GT_DESTRUCT " + n);
        }
    }

    /** `GT_DESTRUCT` for each moved-in source that is a plain `owns` variable or field path; temporaries are not addressable and are skipped. */
    public void emitDestructMovedSources(List<Token> sources) {
        for (Token t : sources) {
            if (t.chk.resolvedType == null || !t.chk.resolvedType.startsWith("owns")) {
                continue;
            }
            if (t.type == TokenType.VARREF) {
                core.requireGhostTableFunctionPresent("gt_destruct", t);
                core.line("GT_DESTRUCT " + t.text);
            } else if (t.type == TokenType.OPERATOR && t.text.equals(".") && isQualifiedNameableDot(t)) {
                core.requireGhostTableFunctionPresent("gt_destruct", t);
                core.line("GT_DESTRUCT " + qualifiedDotName(t));
            }
        }
    }

    /** `new ctor(..)`: construct into a hidden inline local through emitStructRvoCallSequence, then push it whole. see docs/BytecodeEmitter.md, "new". */
    public void emitNewFromStructRvoCall(Token callOp) {
        // Struct name from the callee return type, else from the call node's resolvedType.
        String funcName = operations.resolveCallTargetForEmission(callOp.chk.resolvedCallTarget);
        TypeCheckerModel.FuncInfo targetInfo = core.findFuncInfoByMangledName(funcName);
        String structName = targetInfo != null ? targetInfo.returnType.baseType
                : callOp.chk.resolvedType.substring(callOp.chk.resolvedType.lastIndexOf('_') + 1);
        String tempName = "$new_ctor_tmp" + (++core.labelCounter);
        String structType = "mut_" + structName;
        String rawPtrType = "raw_indeterminate_" + structName;
        core.line("ALLOC " + tempName + " " + structType);
        operations.emitStructRvoCallSequence(callOp, rawPtrType, () -> {
            core.line("PUSH " + tempName + " " + structType);
            core.line("ADDR_OF RAW " + structType + " " + rawPtrType);
        });
        core.line("PUSH " + tempName + " " + structType);
    }
    /** new: operand, NEW type, GT_REGISTER, null check jumping to the local catch; moved-in sources are nulled only after success. see docs/BytecodeEmitter.md, "new". */
    public void emitNew(Token op) {
        Token unwrapped = operations.unwrapMutWrappers(op.left);
        List<Token> savedDeferral = core.deferredMoveNullOuts;
        List<Token> movedSources = new ArrayList<>();
        core.deferredMoveNullOuts = movedSources;
        final List<String> savedTempNames = core.deferredTempNames;
        final List<String> movedTemps = new ArrayList<>();
        core.deferredTempNames = movedTemps;
        // A call nested in the field values would consume the pending catch label (meant for the call-site staging of the
        // first call), and the allocation check below would then have none -- an unchecked null: hold it back and restore it.
        final String newCatchLabel = core.pendingTryCatchLabel;
        core.pendingTryCatchLabel = null;
        if (unwrapped.chk.isStructRvoCall) {
            // A '@throws' constructor is itself the call the try/'?' guards: it must stage the catch label (an unstaged
            // throw would unwind past this function's own catch into the caller). The label is restored for the allocation check below.
            TypeCheckerModel.FuncInfo ctorInfo = core.findFuncInfoByMangledName(operations.resolveCallTargetForEmission(unwrapped.chk.resolvedCallTarget));
            if (ctorInfo != null && ctorInfo.isThrows) {
                core.pendingTryCatchLabel = newCatchLabel;
            }
            emitNewFromStructRvoCall(unwrapped);
        } else {
            statements.emitExpr(op.left);
        }
        core.deferredMoveNullOuts = savedDeferral;
        core.deferredTempNames = savedTempNames;
        core.pendingTryCatchLabel = newCatchLabel;
        core.requireGhostTableFunctionPresent("gt_register", op);
        String constructedType = op.chk.resolvedType.substring("owns_".length());
        if (constructedType.startsWith("some_")) {
            constructedType = constructedType.substring("some_".length());
        }
        core.line("NEW " + constructedType);
        // Registered right away: when the ghost table can not grow, GT_REGISTER frees the block and leaves null, so the
        // check below sees a failed registration exactly like a failed allocation (same catch, same freeing of moved sources).
        core.line("GT_REGISTER");
        core.line("DUP_TOP");
        core.line("PUSH null " + op.chk.resolvedType);
        core.line("EQ " + op.chk.resolvedType + " " + op.chk.resolvedType + " imut_bool");
        core.line("CMP");
        String okLabel = statements.newLabel("new_ok");
        core.line("JMP " + okLabel);
        if (!core.inGtSuppressedContext && core.pendingTryCatchLabel != null) {
            emitDestructMovedSources(movedSources); // the moved-in values are lost with the failed allocation: free them
            emitDestructTempNames(movedTemps, op);
            String oomStringId = core.hoistedStringId("out of memory");
            core.emitTraceCapture();
            core.line("ADDR gt_error_message static_imut_string");
            core.line("PUSH " + oomStringId + " static_imut_string");
            core.line("ASSIGN static_imut_string static_imut_string static_imut_string");
            core.line("JMP " + core.pendingTryCatchLabel);
        }
        core.line(okLabel + ":");
        for (Token moved : movedSources) {
            statements.emitOwnershipMoveNullOut(moved); // the allocation took ownership: now the sources let go
        }
    }

    /** Null check after NEW_DYN / NEW_FROM_STRING / RESIZE, the same sequence emitNew uses; expects the value on top of the stack. see docs/BytecodeEmitter.md, "Allocation failure check". */
    public void emitAllocFailureCheck(Token op) {
        emitAllocFailureCheck(op, java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    /** `movedSources`: owns variables just moved into the allocation (e.g. the elements of `dyn([a, b])`); freed if it fails. */
    public void emitAllocFailureCheck(Token op, List<Token> movedSources, List<String> movedTemps) {
        core.line("DUP_TOP");
        core.line("PUSH null " + op.chk.resolvedType);
        core.line("EQ " + op.chk.resolvedType + " " + op.chk.resolvedType + " imut_bool");
        core.line("CMP");
        String okLabel = statements.newLabel("alloc_ok");
        core.line("JMP " + okLabel);
        if (!core.inGtSuppressedContext && core.pendingTryCatchLabel != null) {
            emitDestructMovedSources(movedSources);
            emitDestructTempNames(movedTemps, op);
            String oomStringId = core.hoistedStringId("out of memory");
            core.emitTraceCapture();
            core.line("ADDR gt_error_message static_imut_string");
            core.line("PUSH " + oomStringId + " static_imut_string");
            core.line("ASSIGN static_imut_string static_imut_string static_imut_string");
            core.line("JMP " + core.pendingTryCatchLabel);
        }
        core.line(okLabel + ":");
    }
    /** ADDR_OF auto/raw/ref (nothing for `static`); see docs/BytecodeEmitter.md, "Address-of". */
    public void emitAddressOf(Token op) {
        statements.emitExpr(op.left);
        if (op.text.equals("static")) {
            return;
        }
        core.line("ADDR_OF " + op.text.toUpperCase() + " " + op.left.chk.resolvedType + " " + op.chk.resolvedType);
    }

    public String literalValueOf(Token node) {
        switch (node.type) {
            case STRING:
                // Hoisted: the quoted text appears once in a STRING declaration at the top of the output.
                return core.hoistedStringId(node.literalValue);
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

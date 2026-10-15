package caspien.bytecodeemitter;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.config.DocComments;
import caspien.token.Token;
import caspien.token.TokenType;
import caspien.config.TraceConfig;
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
import caspien.typechecker.FuncInfo;
import caspien.typechecker.StructInfo;
import caspien.config.DocComment;
import caspien.bytecode.Text;

/**
 * Final front-end stage: walks the type-checked tree and emits the stack-based higher-order bytecode (HOB) as text.
 * Instruction shapes, if/elseif chaining and the ++ / compound-assignment desugaring: see docs/BytecodeEmitter.md, "Instruction shapes".
 */

public final class BytecodeEmitter {
    final BytecodeEmitterCore core = new BytecodeEmitterCore(this);
    final BytecodeEmitterStatements statements = new BytecodeEmitterStatements(this);
    final BytecodeEmitterOperations operations = new BytecodeEmitterOperations(this);
    final EmitDriver driver = new EmitDriver(this);

    // Public surface used by Frontend and FixTool; everything else stays inside the package.
    public final Map<String, DocComment> funcTermLabels = core.funcTermLabels;
    public final Map<String, DocComment> loopTermLabels = core.loopTermLabels;
    public final Map<String, Long> spinBounds = core.spinBounds;
    public final List<String[]> termSites = core.termSites;
    public final Map<String, String> sourcePos = core.sourcePos;

    public BytecodeProgram emit(List<Token> rootLines, TypeChecker checker) {
        return core.emit(rootLines, checker);
    }

    public EmitFrame lookupFrame(Token op) {
        EmitFrame f = EmitFrame.script(op);
        String leftType = op.left.chk.resolvedType;
        if (op.left.type == TokenType.VARREF && leftType != null && !BytecodeEmitterCore.hasStorage(leftType)
                && leftType.endsWith("]") && !leftType.contains("dynarray")
                && op.chk.resolvedType != null && Text.isQualifiedScalar(op.chk.resolvedType, true)) {
            // Scalar element of a fixed array variable: take the element address (ADDR + LOOKUP_LHS) and load only it, instead of pushing the whole array. Aggregate elements keep the old path (DEREF carries no size).
            f.line("ADDR " + op.left.text + " " + leftType);
            Token idx = op.right.childs.get(0);
            f.expr(idx);
            f.line("LOOKUP_LHS " + leftType + " " + idx.chk.resolvedType + " " + op.chk.resolvedType);
            f.line("DEREF " + op.chk.resolvedType);
            return f;
        }
        f.expr(op.left);
        Token indexExpr = op.right.childs.get(0);
        f.expr(indexExpr);
        f.line("LOOKUP " + op.left.chk.resolvedType + " " + indexExpr.chk.resolvedType + " " + op.chk.resolvedType);
        return f;
    }

    /** 'x fits T' as unsigned 64-bit compares only; see docs/BytecodeEmitter.md, "fits". */
    public EmitFrame fitsFrame(Token node) {
        EmitFrame f = EmitFrame.script(node);
        String xb = node.chk.fitsSourceBase;
        String tb = node.chk.fitsTargetBase;
        boolean xSigned = xb.charAt(0) == 's';
        boolean tSigned = tb.charAt(0) == 's';
        int xw = Integer.parseInt(xb.substring(1));
        int tw = Integer.parseInt(tb.substring(1));
        String u64 = "indeterminate_u64";
        f.expr(node.left);
        if (xw < 64) {
            f.line((xSigned ? "SEXT " : "ZEXT ") + node.left.chk.resolvedType + " " + u64);
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
                f.line("PUSH 0 " + u64);
                f.line("GT_EQ " + u64 + " " + u64 + " imut_bool");
            } else {
                f.line("PUSH " + tmax + " " + u64);
                f.line("LT_EQ " + u64 + " " + u64 + " imut_bool");
            }
            return f;
        }
        // both signed
        if (tw == 64 || (xw <= tw)) {
            f.line("PUSH 0 " + u64);
            f.line("GT_EQ " + u64 + " " + u64 + " imut_bool");
            return f;
        }
        f.line("PUSH " + tmin.negate() + " " + u64);
        f.line("ADD " + u64 + " " + u64 + " " + u64);
        f.line("PUSH " + tmax.subtract(tmin) + " " + u64);
        f.line("LT_EQ " + u64 + " " + u64 + " imut_bool");
        return f;
    }

    /** wrap:<T>(x) / sat:<T>(x), total integer conversions; see docs/BytecodeEmitter.md, "wrap and sat". The operand is emitted again for every use in the formula. */
    public EmitFrame convertFrame(Token op) {
        final Token arg = operations.singleBuiltinArg(op);
        EmitFrame f = EmitFrame.script(op);
        if (op.chk.isFloatConvert || (op.chk.resolvedType != null && BytecodeEmitterCore.isFloatCanonical(op.chk.resolvedType))) {
            // wrap:<f32>(x) / wrap:<f64>(x): a float conversion (a literal argument was already retagged in place).
            f.expr(arg);
            if (op.chk.isFloatConvert) {
                f.line("FCONV " + arg.chk.resolvedType + " " + op.chk.resolvedType);
            }
            return f;
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
            f.expr(arg);
            if (tw > xw) {
                f.line((xs ? "SEXT " : "ZEXT ") + arg.chk.resolvedType + " " + op.chk.resolvedType);
            } else if (tw < xw) {
                f.line("TRUNC " + arg.chk.resolvedType + " " + op.chk.resolvedType);
            }
            return f;
        }
        final java.math.BigInteger tmax = ts ? one.shiftLeft(tw - 1).subtract(one) : one.shiftLeft(tw).subtract(one);
        final String i63 = one.shiftLeft(63).subtract(one).toString();
        // Xc: X for unsigned x; max(X,0) for signed x targeting an unsigned T
        final boolean hasPushV;
        boolean needClampAbove;
        if (!xs) {
            hasPushV = true;
            needClampAbove = true;
        } else if (!ts) {
            hasPushV = true;                      // X * (X <=u 2^63-1)
            needClampAbove = tw < 64 && one.shiftLeft(xw - 1).subtract(one).compareTo(tmax) > 0;
        } else {
            hasPushV = false;                     // signed -> narrower signed: handled below
            needClampAbove = false;
        }
        if (hasPushV) {
            pushConvertV(f, arg, xs, xw, ext, u64, i63);
            if (needClampAbove) {
                pushConvertV(f, arg, xs, xw, ext, u64, i63);
                f.line("PUSH " + tmax + " " + u64);
                f.line("GT " + u64 + " " + u64 + " " + u64);
                f.line("PUSH " + tmax + " " + u64);
                pushConvertV(f, arg, xs, xw, ext, u64, i63);
                f.line("SUB " + u64 + " " + u64 + " " + u64);
                f.line("MUL " + u64 + " " + u64 + " " + u64);
                f.line("ADD " + u64 + " " + u64 + " " + u64);
            }
        } else {
            java.math.BigInteger absMin = one.shiftLeft(tw - 1);
            java.math.BigInteger range = one.shiftLeft(tw).subtract(one);
            pushConvertX(f, arg, xw, ext);                                  // X
            pushConvertX(f, arg, xw, ext);
            f.line("PUSH " + absMin + " " + u64);
            f.line("ADD " + u64 + " " + u64 + " " + u64);
            f.line("PUSH " + range + " " + u64);
            f.line("GT " + u64 + " " + u64 + " " + u64);                    // notInside
            f.line("PUSH 0 " + u64);
            f.line("PUSH " + absMin + " " + u64);
            f.line("SUB " + u64 + " " + u64 + " " + u64);                   // Tmin (wrapped)
            pushConvertX(f, arg, xw, ext);
            f.line("PUSH " + i63 + " " + u64);
            f.line("LT_EQ " + u64 + " " + u64 + " " + u64);                 // X >= 0
            f.line("PUSH " + range + " " + u64);
            f.line("MUL " + u64 + " " + u64 + " " + u64);
            f.line("ADD " + u64 + " " + u64 + " " + u64);                   // fallback
            pushConvertX(f, arg, xw, ext);
            f.line("SUB " + u64 + " " + u64 + " " + u64);                   // fallback - X
            f.line("MUL " + u64 + " " + u64 + " " + u64);
            f.line("ADD " + u64 + " " + u64 + " " + u64);                   // X + notInside*(fallback-X)
        }
        if (tw < 64) {
            f.line("TRUNC " + u64 + " " + op.chk.resolvedType);
        }
        return f;
    }

    /** The operand widened to u64 (convertFrame's pushX). */
    private static void pushConvertX(EmitFrame f, Token arg, int xw, String ext) {
        f.expr(arg);
        if (xw < 64) {
            f.line(ext);
        }
    }

    /** convertFrame's pushV: X for an unsigned operand, X * (X <=u 2^63-1) for a signed one targeting an unsigned type. */
    private static void pushConvertV(EmitFrame f, Token arg, boolean xs, int xw, String ext, String u64, String i63) {
        pushConvertX(f, arg, xw, ext);
        if (xs) {
            pushConvertX(f, arg, xw, ext);
            f.line("PUSH " + i63 + " " + u64);
            f.line("LT_EQ " + u64 + " " + u64 + " " + u64);
            f.line("MUL " + u64 + " " + u64 + " " + u64);
        }
    }

    /** "as": no instruction for an alias cast, FCONV / TRUNC / SEXT / ZEXT otherwise. see docs/BytecodeEmitter.md, "as casts". */
    public EmitFrame asFrame(Token op) {
        EmitFrame f = EmitFrame.script(op);
        f.expr(op.left);
        if (op.chk.isFloatConvert) {
            f.line("FCONV " + op.left.chk.resolvedType + " " + op.chk.resolvedType);
            return f;
        }
        if (op.chk.isTruncCast) {
            f.line("TRUNC " + op.left.chk.resolvedType + " " + op.chk.resolvedType);
            return f;
        }
        if (op.chk.isAliasCast) {
            return f;
        }
        f.line((op.chk.isSignedWideningCast ? "SEXT " : "ZEXT ") + op.left.chk.resolvedType + " " + op.chk.resolvedType);
        return f;
    }

    /** Both EnumName.Variant and instance.member chains: one PUSH of the dot-joined name when the chain is nameable, else base + DOT / DOT_LHS+DEREF. see docs/BytecodeEmitter.md, "Dot access". Null when the lines were all written here. */
    public EmitFrame dotFrame(Token op) {
        if (op.chk.catchMember != null) {
            String param = op.chk.catchTry.catchParamName;
            if (op.chk.catchMember.equals("msg")) {
                core.line("PUSH " + param + " static_imut_string");
            } else {
                String arr = "mut_u64[" + (TraceConfig.depth + 1) + "]";
                core.line("PUSH " + param + "$trace " + arr);
                core.line("ADDR_OF AUTO " + arr + " auto_indeterminate_u64[" + (TraceConfig.depth + 1) + "]");
            }
            return null;
        }
        if (isQualifiedNameableDot(op)) {
            core.line("PUSH " + qualifiedDotName(op) + " " + op.chk.resolvedType);
            return null;
        }
        // Not nameable (base is a LOOKUP/call, or a segment is a pointer): emit the base, then DOT (inline base) or DOT_LHS+DEREF (pointer base), decided per level.
        EmitFrame f = EmitFrame.script(op);
        f.expr(op.left);
        f.line("PUSH_FIELDNAME " + op.right.text + " " + op.chk.resolvedType);
        if (BytecodeEmitterCore.hasStorage(op.left.chk.resolvedType)) {
            f.line("DOT_LHS " + op.left.chk.resolvedType + " " + op.chk.resolvedType + " " + op.chk.resolvedType);
            f.line("DEREF " + op.chk.resolvedType);
        } else {
            f.line("DOT " + op.left.chk.resolvedType + " " + op.chk.resolvedType + " " + op.chk.resolvedType);
        }
        return f;
    }

    /** True when the whole chain is storage-free and rooted at a VARREF (a LOOKUP/CALL .text is a marker, not a name). */
    public boolean isQualifiedNameableDot(Token node) {
        Token n = node;
        while (true) {
            if (n.chk.catchMember != null) {
                return false;
            }
            if (n.type == TokenType.OPERATOR && n.text.equals(".")) {
                // Per level: the segment dotted into must be storage-free too, the same bail-out AddressLoweringPass.resolveAddress applies.
                if (BytecodeEmitterCore.hasStorage(n.left.chk.resolvedType)) {
                    return false;
                }
                n = n.left;
                continue;
            }
            return n.type == TokenType.VARREF && !BytecodeEmitterCore.hasStorage(n.chk.resolvedType);
        }
    }

    public String qualifiedDotName(Token node) {
        Deque<String> segments = new ArrayDeque<>();
        Token n = node;
        while (n.type == TokenType.OPERATOR && n.text.equals(".")) {
            segments.push(n.right.text);
            n = n.left;
        }
        StringBuilder sb = new StringBuilder(n.text);
        for (String segment : segments) {
            sb.append('.').append(segment);
        }
        return sb.toString();
    }

    /**
     * Struct literal: optional hidden ___type push, then members in padded layout order (declaration order, not literal order). Single choke point for `new X{..}` and bare `X{..}`. see docs/BytecodeEmitter.md, "Struct literals".
     * Frame: t1 = value being emitted, s1 = struct name (lock violation), entries/i = the layout walk, valueByMember.
     */
    public int stepInstantiate(EmitFrame f) {
        Token op = f.node;
        while (true) {
            switch (f.phase) {
                case 0: {
                    String structName = op.left.text;
                    f.structInfo = core.checker.declarations.getStructs().get(structName);
                    if (f.structInfo.classId != null) {
                        core.line("PUSH " + f.structInfo.classId + " imut_u64");
                    }
                    if (op.chk.isLockViolationConstruct) {
                        // OTC lock violation: only the lock/discriminant value is pushed, then STACK_LOCK <structName>.
                        Token lockAssignNode = op.right.childs.get(0).childs.get(0);
                        f.t1 = lockAssignNode.right;
                        f.s1 = structName;
                        f.phase = 1;
                        return f.callExpr(f.t1);
                    }
                    f.valueByMember = new HashMap<>();
                    for (Token lineTok : op.right.childs) {
                        Token assignNode = lineTok.childs.get(0);
                        f.valueByMember.put(assignNode.left.text, assignNode.right);
                    }
                    // Walk the padded layout: ___type already pushed, each padding gap is a STACK_LOCK.
                    f.entries = core.computeStructLayout(f.structInfo).entries;
                    f.i = 0;
                    f.phase = 3;
                    continue;
                }
                case 1:
                    f.phase = 2;
                    if (f.t1.chk.isOwnershipMoveSource) {
                        return f.call(statements.nullOutFrame(f.t1));
                    }
                    continue;
                case 2:
                    core.line("STACK_LOCK " + f.s1);
                    return EmitFrame.DONE;
                case 3:
                    while (f.i < f.entries.size()) {
                        StructLayoutEntry entry = f.entries.get(f.i++);
                        if (entry.memberName == null) {
                            core.line("STACK_LOCK " + entry.paddingBytes);
                            continue;
                        }
                        if (entry.memberName.equals("___type")) {
                            continue;
                        }
                        Token value = f.valueByMember.get(entry.memberName);
                        f.t1 = value;
                        if (core.deferredTempNames != null && isOwnsTemporary(value)) {
                            return f.call(ownsTemporaryFrame(value, core.deferredTempNames));
                        }
                        f.phase = 4;
                        return f.callExpr(value);
                    }
                    return EmitFrame.DONE;
                default:
                    f.phase = 3;
                    if (f.t1.chk.isOwnershipMoveSource) {
                        // Null the move source right away: a later field value may contain a throwing call.
                        if (core.deferredMoveNullOuts != null) {
                            core.deferredMoveNullOuts.add(f.t1); // inside `new`: nulled only once the allocation has succeeded
                        } else {
                            return f.call(statements.nullOutFrame(f.t1));
                        }
                    }
                    continue;
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

    /** Evaluates the temporary into a hidden local and pushes it from there, so a failing allocation can still free it. The local is added to `tempNames`. */
    public EmitFrame ownsTemporaryFrame(Token value, List<String> tempNames) {
        EmitFrame f = new EmitFrame(EmitFrame.OWNS_TEMP, value);
        f.strs = tempNames;
        return f;
    }

    /** Frame: s1 = the hidden local. */
    public int stepOwnsTemporary(EmitFrame f) {
        Token value = f.node;
        String type = value.chk.resolvedType;
        if (f.phase == 0) {
            f.s1 = declareHiddenLocal(type);
            core.line("ADDR " + f.s1 + " " + type);
            f.phase = 1;
            return f.callExpr(value);
        }
        core.line("ASSIGN " + type + " " + type + " " + type);
        core.line("PUSH " + f.s1 + " " + type);
        f.strs.add(f.s1);
        return EmitFrame.DONE;
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

    public EmitFrame newFromStructRvoCallFrame(Token callOp) {
        return new EmitFrame(EmitFrame.NEW_FROM_RVO, callOp);
    }

    /** `new ctor(..)`: construct into a hidden inline local through the struct-RVO call sequence, then push it whole. see docs/BytecodeEmitter.md, "new". Frame: s1 = the local, s2 = its struct type. */
    public int stepNewFromStructRvoCall(EmitFrame f) {
        Token callOp = f.node;
        if (f.phase == 0) {
            // Struct name from the callee return type, else from the call node's resolvedType.
            String funcName = operations.resolveCallTargetForEmission(callOp.chk.resolvedCallTarget);
            FuncInfo targetInfo = core.findFuncInfoByMangledName(funcName);
            String structName = targetInfo != null ? targetInfo.returnType.baseType
                    : callOp.chk.resolvedType.substring(callOp.chk.resolvedType.lastIndexOf('_') + 1);
            f.s1 = "$new_ctor_tmp" + (++core.labelCounter);
            f.s2 = "mut_" + structName;
            String rawPtrType = "raw_indeterminate_" + structName;
            core.line("ALLOC " + f.s1 + " " + f.s2);
            f.phase = 1;
            return f.call(operations.rvoCallFrame(callOp, rawPtrType, Arrays.asList(
                    "PUSH " + f.s1 + " " + f.s2, "ADDR_OF RAW " + f.s2 + " " + rawPtrType)));
        }
        core.line("PUSH " + f.s1 + " " + f.s2);
        return EmitFrame.DONE;
    }

    /**
     * new: operand, NEW type, GT_REGISTER, null check jumping to the local catch; moved-in sources are nulled only after success. see docs/BytecodeEmitter.md, "new".
     * Frame: toks = moved-in sources, strs = moved-in temporaries, toks2/strs2 = the deferral lists to restore, s1 = held catch label, n = null-out index.
     */
    public int stepNew(EmitFrame f) {
        Token op = f.node;
        while (true) {
            switch (f.phase) {
                case 0: {
                    Token unwrapped = operations.unwrapMutWrappers(op.left);
                    f.toks2 = core.deferredMoveNullOuts;
                    f.toks = new ArrayList<>();
                    core.deferredMoveNullOuts = f.toks;
                    f.strs2 = core.deferredTempNames;
                    f.strs = new ArrayList<>();
                    core.deferredTempNames = f.strs;
                    // A call nested in the field values would consume the pending catch label (meant for the call-site staging of the
                    // first call), and the allocation check below would then have none -- an unchecked null: hold it back and restore it.
                    f.s1 = core.pendingTryCatchLabel;
                    core.pendingTryCatchLabel = null;
                    f.phase = 1;
                    if (unwrapped.chk.isStructRvoCall) {
                        // A '@throws' constructor is itself the call the try/'?' guards: it must stage the catch label (an unstaged
                        // throw would unwind past this function's own catch into the caller). The label is restored for the allocation check below.
                        FuncInfo ctorInfo = core.findFuncInfoByMangledName(operations.resolveCallTargetForEmission(unwrapped.chk.resolvedCallTarget));
                        if (ctorInfo != null && ctorInfo.isThrows) {
                            core.pendingTryCatchLabel = f.s1;
                        }
                        return f.call(newFromStructRvoCallFrame(unwrapped));
                    }
                    return f.callExpr(op.left);
                }
                case 1: {
                    core.deferredMoveNullOuts = f.toks2;
                    core.deferredTempNames = f.strs2;
                    core.pendingTryCatchLabel = f.s1;
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
                        emitDestructMovedSources(f.toks); // the moved-in values are lost with the failed allocation: free them
                        emitDestructTempNames(f.strs, op);
                        String oomStringId = core.hoistedStringId("out of memory");
                        core.emitTraceCapture();
                        core.line("ADDR gt_error_message static_imut_string");
                        core.line("PUSH " + oomStringId + " static_imut_string");
                        core.line("ASSIGN static_imut_string static_imut_string static_imut_string");
                        core.line("JMP " + core.pendingTryCatchLabel);
                    }
                    core.line(okLabel + ":");
                    f.n = 0;
                    f.phase = 2;
                    continue;
                }
                default:
                    if (f.n < f.toks.size()) {
                        // the allocation took ownership: now the sources let go
                        return f.call(statements.nullOutFrame(f.toks.get(f.n++)));
                    }
                    return EmitFrame.DONE;
            }
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
    public EmitFrame addressOfFrame(Token op) {
        EmitFrame f = EmitFrame.script(op);
        f.expr(op.left);
        if (!op.text.equals("static")) {
            f.line("ADDR_OF " + op.text.toUpperCase() + " " + op.left.chk.resolvedType + " " + op.chk.resolvedType);
        }
        return f;
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

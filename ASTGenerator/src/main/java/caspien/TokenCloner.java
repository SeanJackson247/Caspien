package caspien;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * The one deep copier for token trees (iterative, no recursion). Two flavours:
 * {@link #syntax} copies a gathered, tree-built, not yet type-checked tree (generic expansion, lazily materialised generic methods):
 * the flags and lists the Parser set travel along, `decorators`/`unsafeTags`/`pinnedComments` are shared, the checker's marks
 * (`chk`, `staticApproved`) and the generic-instance bookkeeping (`fromGenericInstance`, `pendingGenericBoundChecks`) start fresh.
 * {@link #checked} copies everything, including `chk`, for an already checked statement (the recursive-function lowering).
 * A shared sub-tree stays shared inside the copy. `tests/token_clone_check.sh` fails when a field of Token or TokenChecked is not handled here.
 */
public final class TokenCloner {

    private final IdentityHashMap<Token, Token> made = new IdentityHashMap<>();
    private final ArrayDeque<Token> pending = new ArrayDeque<>();
    private final boolean checked;

    private TokenCloner(boolean checked) {
        this.checked = checked;
    }

    public static Token syntax(Token root) {
        return new TokenCloner(false).run(root);
    }

    public static Token checked(Token root) {
        return new TokenCloner(true).run(root);
    }

    private Token run(Token root) {
        Token result = shell(root);
        while (!pending.isEmpty()) {
            Token s = pending.poll();
            fill(s, made.get(s));
        }
        return result;
    }

    /** The copy of `src`, created (and queued to be filled) on first sight. */
    private Token shell(Token src) {
        Token c = made.get(src);
        if (c == null) {
            c = new Token(src.type, src.text, src.line, src.file);
            made.put(src, c);
            pending.add(src);
        }
        return c;
    }

    private Token t(Token x) {
        return x == null ? null : shell(x);
    }

    private List<Token> tl(List<Token> l) {
        if (l == null) {
            return null;
        }
        List<Token> out = new ArrayList<>(l.size());
        for (Token x : l) {
            out.add(t(x));
        }
        return out;
    }

    private static <T> List<T> copy(List<T> l) {
        return l == null ? null : new ArrayList<>(l);
    }

    private void fill(Token s, Token d) {
        d.literalValue = s.literalValue;
        d.unary = s.unary;
        d.left = t(s.left);
        d.right = t(s.right);
        d.next = t(s.next);
        d.childs = s.childs == null ? new ArrayList<>() : tl(s.childs);
        d.sub = s.sub == null ? new ArrayList<>() : tl(s.sub);
        d.typeBound = tl(s.typeBound);
        if (s.genericArgs == null) {
            d.genericArgs = null;
        } else {
            d.genericArgs = new ArrayList<>();
            for (List<Token> group : s.genericArgs) {
                d.genericArgs.add(tl(group));
            }
        }
        d.typeParams = copy(s.typeParams);
        d.typeParamBounds = copy(s.typeParamBounds);
        d.extendsNames = copy(s.extendsNames);
        d.implementsNames = copy(s.implementsNames);
        d.assumeFieldVariants = copy(s.assumeFieldVariants);
        d.isStatic = s.isStatic;
        d.isConst = s.isConst;
        d.isStaticMethod = s.isStaticMethod;
        d.isCaseMatch = s.isCaseMatch;
        d.isForMatch = s.isForMatch;
        d.catchParamName = s.catchParamName;
        d.isTryBlock = s.isTryBlock;
        d.hasBlock = s.hasBlock;
        d.explicitEnumValue = s.explicitEnumValue;
        d.explicitRangeStart = s.explicitRangeStart;
        d.explicitRangeEnd = s.explicitRangeEnd;
        d.isConstructorDecl = s.isConstructorDecl;
        d.constructorConcreteName = s.constructorConcreteName;
        d.forcedLiteralType = s.forcedLiteralType;
        d.isPubBlockMember = s.isPubBlockMember;
        d.asmBlockText = s.asmBlockText;
        d.asmFilePath = s.asmFilePath;
        if (checked) {
            d.pinnedComments = tl(s.pinnedComments);
            d.decorators = copy(s.decorators);
            d.unsafeTags = copy(s.unsafeTags);
            d.pendingGenericBoundChecks = copy(s.pendingGenericBoundChecks);
            d.fromGenericInstance = s.fromGenericInstance;
            d.staticApproved = s.staticApproved;
            fillChecked(s.chk, d.chk);
        } else {
            d.pinnedComments = s.pinnedComments;
            d.decorators = s.decorators;
            d.unsafeTags = s.unsafeTags;
        }
    }

    private void fillChecked(TokenChecked s, TokenChecked d) {
        d.resolvedCallTarget = s.resolvedCallTarget;
        d.resolvedCallConvention = s.resolvedCallConvention;
        d.resolvedType = s.resolvedType;
        d.synthesizedUnsafe = s.synthesizedUnsafe;
        d.insideTry = s.insideTry;
        d.coveredByThrowingNew = s.coveredByThrowingNew;
        d.catchUsesStackTrace = s.catchUsesStackTrace;
        d.catchMember = s.catchMember;
        d.catchTry = t(s.catchTry);
        d.isOwnershipMoveSource = s.isOwnershipMoveSource;
        d.inlineOwnsStruct = s.inlineOwnsStruct;
        d.isLockViolationConstruct = s.isLockViolationConstruct;
        d.isStructRvoCall = s.isStructRvoCall;
        d.dynFromStringSource = s.dynFromStringSource;
        d.isRecursiveTailCallSite = s.isRecursiveTailCallSite;
        d.isGuaranteedEnumMembershipCheck = s.isGuaranteedEnumMembershipCheck;
        d.isStringMembershipCheck = s.isStringMembershipCheck;
        d.isFloatConvert = s.isFloatConvert;
        d.isTruncCast = s.isTruncCast;
        d.fitsSourceBase = s.fitsSourceBase;
        d.fitsTargetBase = s.fitsTargetBase;
        d.isAliasCast = s.isAliasCast;
        d.isSignedWideningCast = s.isSignedWideningCast;
        d.asyncCallKind = s.asyncCallKind;
        d.isSleepCall = s.isSleepCall;
        d.isTrampolineOwnCall = s.isTrampolineOwnCall;
        d.asyncHandleStructName = s.asyncHandleStructName;
        d.asyncHandleCanonicalType = s.asyncHandleCanonicalType;
        d.asyncTrampolineName = s.asyncTrampolineName;
        d.isLookupProofReliant = s.isLookupProofReliant;
        d.someIndexElementExpr = t(s.someIndexElementExpr);
        d.refToId = s.refToId;
        d.refToRaw = s.refToRaw;
        d.refProofKey = s.refProofKey;
        d.refProofIsId = s.refProofIsId;
        d.someOnRef = s.someOnRef;
        d.someProofKey = s.someProofKey;
        d.matchPatterns = copy(s.matchPatterns);
        d.builtinConstantValue = s.builtinConstantValue;
        d.sizeofStruct = s.sizeofStruct;
        d.pointerScaleStruct = s.pointerScaleStruct;
        d.pointerScale = s.pointerScale;
        d.pointerArithKind = s.pointerArithKind;
        d.invokeArgCount = s.invokeArgCount;
        d.destructOnExit = copy(s.destructOnExit);
        d.unlockOnExit = tl(s.unlockOnExit);
        d.isCompilerSynthesizedSwapAccess = s.isCompilerSynthesizedSwapAccess;
        d.preLoopInit = tl(s.preLoopInit);
        d.isLockSpinLoop = s.isLockSpinLoop;
        d.lockSpinBound = s.lockSpinBound;
        d.isLockRetryContinue = s.isLockRetryContinue;
        d.isLoopContinue = s.isLoopContinue;
        d.resolvedAsmContent = s.resolvedAsmContent;
    }
}

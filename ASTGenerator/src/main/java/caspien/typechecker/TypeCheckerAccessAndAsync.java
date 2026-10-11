package caspien.typechecker;
import caspien.parser.Parser;
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

/** TypeCheckerAccessAndAsync: member access, new/par/await, synthesized async and dispatch code (part 10 of 11; see README.md in this folder). */
abstract class TypeCheckerAccessAndAsync extends TypeCheckerLocksAndProofs {


    /**
     * True if a live "in" (or "into") proof covers this exact index slot AND this exact lookup target
     * (the target named in the match's `in` clause, not just any array in the block).
     */
    public boolean isProvenInBounds(Token lookupTarget, Token indexExpr, Scope scope) {
        String indexKey = boundsSlotKeyOf(indexExpr);
        String targetKey = boundsSlotKeyOf(lookupTarget);
        if (indexKey == null || targetKey == null) {
            return false;
        }
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            // "into" is a superset of "in" (adds write access).
            if (!p.invalidated && (p.kind.equals("in") || p.kind.equals("into")) && indexKey.equals(p.slotKey)
                    && targetKey.equals(p.rightSlotKey)) {
                return true;
            }
        }
        return false;
    }

    /** Write counterpart of isProvenInBounds: only an "into" proof for the exact (index, target) pair. Mutability is checked separately by requireMutable. */
    public boolean isProvenWritable(Token lookupTarget, Token indexExpr, Scope scope) {
        String indexKey = boundsSlotKeyOf(indexExpr);
        String targetKey = boundsSlotKeyOf(lookupTarget);
        if (indexKey == null || targetKey == null) {
            return false;
        }
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            if (!p.invalidated && p.kind.equals("into") && indexKey.equals(p.slotKey)
                    && targetKey.equals(p.rightSlotKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Write gate layered on checkLookup's bounds gate, called from checkAssign/checkCompoundAssign for a
     * LOOKUP target: a literal provably-safe access needs nothing more, everything else needs an "into"
     * proof for this exact (index, target) ("arr[i+1] = .." does not qualify).
     */
    public void requireLookupWriteAuthorized(Token lookupOp, Scope scope) {
        if (!lookupOp.isLookupProofReliant) {
            return;
        }
        Token indexExpr = lookupOp.right.childs.get(0);
        if (isProvenWritable(lookupOp.left, indexExpr, scope)) {
            return;
        }
        throw new CompilerException("type", lookupOp.file, lookupOp.line,
                "'[]' cannot be written to here -- an 'in'/'within' proof only authorizes reading this "
                        + "index; writing through it requires a write-authorizing 'into' proof instead "
                        + "('match ... into ...' or 'for match ... into ...')");
    }

    /**
     * slotKeyOf extended for bounds proofs: string and integer literals are slots too (keyed on their
     * text). Kept out of slotKeyOf itself because ownership tracking must not treat two equal string
     * literals as the same slot.
     */
    public String boundsSlotKeyOf(Token node) {
        if (node.type == TokenType.STRING) {
            return "STRING_LITERAL:" + node.literalValue;
        }
        if (node.type == TokenType.INTEGER) {
            // A literal index can be proven like a variable: "match 1 in a{ a[1] }".
            // A literal can never be reassigned, so the proof needs no invalidation.
            return "INT_LITERAL:" + node.text;
        }
        return slotKeyOf(node);
    }

    /** A non-public struct member is referenceable only in an impl for that struct (or an interface impl on it). */
    public void checkStructMemberVisibility(StructInfo structInfo, String receiverStructName, String memberName,
            Token at, FuncInfo callerFunc) {
        if (structInfo.publicMembers.contains(memberName)) {
            return;
        }
        String declFile = structInfo.memberDeclFile.get(memberName);
        if (declFile != null && !declFile.equals(at.file)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + memberName + "' is private -- not accessible outside the file it's declared in");
        }
        boolean qualifies = callerFunc != null && callerFunc.enclosingImpl != null
                && callerFunc.enclosingImpl.concreteName.equals(receiverStructName);
        if (!qualifies) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + memberName + "' is private -- only referenceable in an impl for '"
                            + receiverStructName + "' or an impl for an interface on it");
        }
    }

    /**
     * A "swap"-tagged field may only be touched through its own `match @lock` (reads and writes alike,
     * unsynchronised access can corrupt the mutex). Exempt: 'unsafe atomic', and the compiler's own
     * acquire/release access (Token.isCompilerSynthesizedSwapAccess). Struct-literal construction is a
     * separate path and needs no unsafe.
     */
    public void requireSwapFieldAccessAuthorized(StructInfo structInfo, String memberName, Scope scope, Token op) {
        if (!memberName.equals(structInfo.swapFieldName)) {
            return;
        }
        if (op.isCompilerSynthesizedSwapAccess || unsafeBypass(scope, "atomic")) {
            return;
        }
        throw new CompilerException("type", op.file, op.line,
                "'" + memberName + "' is a 'swap'-tagged atomic mutex-state field -- it can only be read or "
                        + "written through its own 'match @lock' statement, or from 'unsafe atomic{...}' code");
    }

    /**
     * '.' means either EnumName.Variant (left is a bare name that is not a declared variable but is an
     * enum) or instance.member (left resolved as an expression; chains work as '.' is left-associative).
     * A declared local shadows an enum of the same name.
     */
    public TypeInfo checkDot(Token op, Scope scope, FuncInfo func) {
        boolean leftIsBareName = op.left.type == TokenType.VARREF;
        boolean leftIsDeclaredVar = leftIsBareName && scope.lookup(op.left.text) != null;

        // `e.msg` / `e.stack_trace` on a catch parameter (stack_trace.md, 3.6)
        if (leftIsBareName && leftIsDeclaredVar && activeCatchTries.containsKey(op.left.text)
                && op.right.type == TokenType.VARREF
                && (op.right.text.equals("msg") || op.right.text.equals("stack_trace"))) {
            Token tryTok = activeCatchTries.get(op.left.text);
            op.catchMember = op.right.text;
            op.catchTry = tryTok;
            if (op.right.text.equals("msg")) {
                return new TypeInfo("static", "imut", "string");
            }
            tryTok.catchUsesStackTrace = true;
            return new TypeInfo("auto", "imut", "u64[" + (TraceConfig.depth + 1) + "]");
        }

        // "X.enum" is only legal as a match condition (handled before dispatch in checkCaseMatch);
        // anything reaching here is an illegal use.
        if (leftIsBareName && !leftIsDeclaredVar && op.right.type == TokenType.VARREF
                && op.right.text.equals("enum") && (structs.containsKey(op.left.text)
                        || interfaces.containsKey(op.left.text))) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.left.text + ".enum' is only legal as the right-hand side of 'instanceof'/"
                            + "'implements' inside a match condition ('match x instanceof " + op.left.text
                            + ".enum{...}'), never as an ordinary expression");
        }

        if (leftIsBareName && !leftIsDeclaredVar && enums.containsKey(op.left.text)) {
            String enumName = op.left.text;
            EnumInfo enumInfo = enums.get(enumName);
            if (op.right.type != TokenType.VARREF) {
                throw new CompilerException("type", op.right.file, op.right.line,
                        "expected an enum variant name after '.'");
            }
            String variantName = op.right.text;
            int variantIdx = enumInfo.variants.indexOf(variantName);
            if (variantIdx < 0) {
                throw new CompilerException("type", op.right.file, op.right.line,
                        "enum '" + enumName + "' has no variant named '" + variantName + "'");
            }
            if (enumInfo.isRangeValued()) {
                // Range-valued enum variant: resolves to a literal-bounds range value (not enum-typed, not fungible).
                long start = enumInfo.explicitRangeStarts.get(variantIdx);
                long end = enumInfo.explicitRangeEnds.get(variantIdx);
                return new TypeInfo(null, "imut", "range(" + start + "," + end + ")");
            }
            return new TypeInfo(null, "imut", enumName);
        }

        // instance.member
        TypeInfo leftType = resolveExprType(op.left, scope, func);

        if (isRangeType(leftType)) {
            // A plain `..` range has null storage; non-null storage only comes from an explicit annotation (e.g. "raw range(0,5)"), which still needs the alive proof.
            if (requiresAliveProof(leftType, op.left, scope)) {
                throw new CompilerException("type", op.left.file, op.left.line,
                        "member access on a pointer-typed range value is not permitted -- the type "
                                + "system can't yet confirm it isn't null ('match Some(...)' will be able "
                                + "to prove this)");
            }
            if (op.right.type != TokenType.VARREF
                    || !(op.right.text.equals("start") || op.right.text.equals("end"))) {
                throw new CompilerException("type", op.right.file, op.right.line,
                        "a range only has 'start' and 'end', got '" + op.right.text + "'");
            }
            // Derived value: "indeterminate" (checkForLoop's desugared loop variable coerces it to 'mut').
            return new TypeInfo(null, "indeterminate", "u64");
        }

        StructInfo structInfo = structs.get(leftType.baseType);
        if (structInfo == null) {
            if (enums.containsKey(leftType.baseType)) {
                throw new CompilerException("type", op.left.file, op.left.line,
                        "enums don't have members to access with '.' -- did you mean '"
                                + leftType.baseType + ".VariantName'?");
            }
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'.' left-hand side must be a struct instance or a known enum name, got type '"
                            + leftType.canonical() + "'");
        }
        if (requiresAliveProof(leftType, op.left, scope)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "member access on a pointer-typed struct value is not permitted -- the type "
                            + "system can't yet confirm it isn't null ('match Some(...)' will be able to "
                            + "prove this) -- only an inline (no-storage), 'auto', or 'some'-tagged "
                            + "struct value supports '.' without one");
        }
        if (op.right.type != TokenType.VARREF) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "expected a member name after '.'");
        }
        String memberName = op.right.text;
        TypeInfo memberType = structInfo.members.get(memberName);
        if (memberType == null) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "struct '" + leftType.baseType + "' has no member named '" + memberName + "'");
        }
        checkStructMemberVisibility(structInfo, leftType.baseType, memberName, op, func);
        requireStructLockProof(structInfo, memberName, op.left, scope, op);
        requireSwapFieldAccessAuthorized(structInfo, memberName, scope, op);
        String slotKey = slotKeyOf(op);
        if (slotKey != null && scope.movedSlots.contains(slotKey)) {
            throw new CompilerException("type", op.file, op.line,
                    "use of '" + slotKey + "' after its ownership was moved");
        }
        return memberType;
    }

    /**
     * "StructName{ member= value, ... }" under the one-true-constructor policy: every member exactly
     * once, none unknown. Member names are read off the raw tokens. See docs/TypeChecker.md, "Struct literals".
     */
    public TypeInfo checkInstantiate(Token op, Scope scope, FuncInfo func) {
        if (op.left.type != TokenType.VARREF) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "struct instantiation expects a struct name (e.g. 'Point{ x= 1, y= 2 }')");
        }
        String structName = op.left.text;
        StructInfo structInfo = structs.get(structName);
        if (structInfo == null) {
            if (enums.containsKey(structName)) {
                throw new CompilerException("type", op.left.file, op.left.line,
                        "'" + structName + "' is an enum, not a struct -- use '"
                                + structName + ".Variant' instead of '{ ... }' instantiation");
            }
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'" + structName + "' is not a known struct");
        }
        checkTypeNameVisibility(structName, op.left);
        // A struct with its own constructors may only be built with a bare literal inside its own constructor bodies.
        if (hasConstructors(structName) && !structName.equals(insideConstructorBodyForStruct)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'" + structName + "' declares its own constructor(s) -- its struct-literal form ('"
                            + structName + "{...}') can only be used inside one of those constructors' own "
                            + "bodies; construct it with '" + structName + "(...)' or 'new " + structName
                            + "(...)' instead");
        }

        Set<String> provided = new HashSet<>();
        Token lockFieldValueNode = null;
        Token swapFieldValueNode = null;
        for (Token lineTok : op.right.childs) {
            Token assignNode = lineTok.childs.get(0);
            if (assignNode.type != TokenType.OPERATOR
                    || !(assignNode.text.equals("=") || assignNode.text.equals(":"))
                    || assignNode.left.type != TokenType.VARREF) {
                throw new CompilerException("type", assignNode.file, assignNode.line,
                        "expected 'memberName = value' or 'memberName: value' in struct literal for '"
                                + structName + "'");
            }
            String memberName = assignNode.left.text;
            TypeInfo declaredType = structInfo.members.get(memberName);
            if (declaredType == null) {
                throw new CompilerException("type", assignNode.left.file, assignNode.left.line,
                        "struct '" + structName + "' has no member named '" + memberName + "'");
            }
            checkStructMemberVisibility(structInfo, structName, memberName, assignNode.left, func);
            if (!provided.add(memberName)) {
                throw new CompilerException("type", assignNode.left.file, assignNode.left.line,
                        "member '" + memberName + "' is specified more than once in this '"
                                + structName + "' literal");
            }
            TypeInfo valueType = resolveExprType(assignNode.right, scope, func);
            boolean isSwapFieldBareVariantLiteral = memberName.equals(structInfo.swapFieldName)
                    && isBareEnumVariantRefNode(assignNode.right, declaredType.baseType);
            if (!isSwapFieldBareVariantLiteral && !acceptsAdaptingLiteral(declaredType, valueType, assignNode.right)) {
                throw new CompilerException("type", assignNode.right.file, assignNode.right.line,
                        "member '" + memberName + "' expects type '" + declaredType.canonical()
                                + "', got '" + valueType.canonical() + "'");
            }
            assignNode.resolvedType = declaredType.canonical();
            assignNode.left.resolvedType = declaredType.canonical();
            markMovedIfOwned(declaredType, assignNode.right, scope);
            if (structInfo.swapFieldName != null && typeOwnsMemory(declaredType, new HashSet<>())) {
                requireFreshIntoSwapLock(assignNode.right, "used to build", structName);
            }
            if (memberName.equals(structInfo.lockFieldName)) {
                lockFieldValueNode = assignNode.right;
            }
            if (memberName.equals(structInfo.swapFieldName)) {
                swapFieldValueNode = assignNode.right;
            }
        }
        if (swapFieldValueNode != null) {
            checkSwapFieldNotConstructedClosed(structInfo, structName, swapFieldValueNode);
        }

        List<String> missing = new ArrayList<>();
        for (String requiredMember : structInfo.members.keySet()) {
            if (!provided.contains(requiredMember)) {
                missing.add(requiredMember);
            }
        }
        if (!missing.isEmpty()) {
            if (structInfo.lockFieldName != null && provided.size() == 1
                    && provided.contains(structInfo.lockFieldName)) {
                // Narrow OTC exception for lock-field structs: only the lock field given, as a provably
                // non-satisfying literal (see checkOtcLockViolation).
                checkOtcLockViolation(op, structInfo, structName, lockFieldValueNode);
                op.isLockViolationConstruct = true;
            } else {
                throw new CompilerException("type", op.file, op.line,
                        "struct '" + structName + "' literal is missing member(s): "
                                + String.join(", ", missing));
            }
        }

        // A literal's top-level mutability is indeterminate until stated; member mutability comes from the definition.
        return new TypeInfo(null, "indeterminate", structName);
    }

    /**
     * Swap-flavoured construction rule: a literal 'EnumName.CLOSED' value is rejected (a fresh mutex is
     * never handed out held); non-literal values pass unchecked. See docs/TypeChecker.md, "Struct literals".
     */
    public void checkSwapFieldNotConstructedClosed(StructInfo structInfo, String structName,
            Token swapFieldValueNode) {
        TypeInfo swapFieldType = structInfo.members.get(structInfo.swapFieldName);
        boolean isBareEnumVariantRef = isBareEnumVariantRefNode(swapFieldValueNode, swapFieldType.baseType);
        if (isBareEnumVariantRef && swapFieldValueNode.right.text.equals("CLOSED")) {
            throw new CompilerException("type", swapFieldValueNode.file, swapFieldValueNode.line,
                    "'" + structName + "' cannot be constructed with its 'swap'-tagged mutex field '"
                            + structInfo.swapFieldName + "' already 'CLOSED' -- a fresh mutex must always "
                            + "start 'OPEN'");
        }
    }

    /**
     * True when `node` is a raw bare "EnumName.Variant" reference to enum `enumName` (checked on the raw
     * expression: a variable of the same type resolves identically). Shared by the swap/lock checks.
     */
    public boolean isBareEnumVariantRefNode(Token node, String enumName) {
        return node.type == TokenType.OPERATOR && node.text.equals(".")
                && node.left != null && node.left.type == TokenType.VARREF
                && node.right != null && node.right.type == TokenType.VARREF
                && enums.containsKey(node.left.text)
                && node.left.text.equals(enumName);
    }

    /**
     * Allowed right-hand shapes of a swap: a literal (number, string, char, bool, null, negative number),
     * a bare enum variant, or a bare variable. Computation must sit on its own line so the syntax never
     * suggests a non-atomic read-compute is part of the atomic write. See docs/TypeChecker.md, "Swap and atomic".
     */
    public boolean isSwapRhsAllowedShape(Token node) {
        switch (node.type) {
            case INTEGER:
            case FLOAT:
            case STRING:
            case CHAR:
            case BOOL:
            case NULL:
            case VARREF:
                return true;
            default:
                break;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("-") && node.left != null
                && (node.left.type == TokenType.INTEGER || node.left.type == TokenType.FLOAT)) {
            return true; // a negative numeric literal, e.g. "-5" -- still zero runtime provenance
        }
        return false;
    }

    /**
     * Validates the lock-struct OTC exception (only the lock field provided, with a value that does not
     * satisfy the lock). See docs/TypeChecker.md, "Struct literals".
     */
    public void checkOtcLockViolation(Token op, StructInfo structInfo, String structName,
            Token lockFieldValueNode) {
        TypeInfo lockFieldType = structInfo.members.get(structInfo.lockFieldName);
        if (!"imut".equals(lockFieldType.mutability)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + structName + "' cannot be constructed by omitting its other members -- "
                            + "'" + structInfo.lockFieldName + "' must be declared 'imut' for this "
                            + "to be provably safe (it's declared 'mut' here)");
        }
        // The value must be a raw bare "EnumName.Variant" so the mismatch is provable statically.
        boolean isBareEnumVariantRef = lockFieldValueNode.type == TokenType.OPERATOR
                && lockFieldValueNode.text.equals(".")
                && lockFieldValueNode.left != null && lockFieldValueNode.left.type == TokenType.VARREF
                && lockFieldValueNode.right != null && lockFieldValueNode.right.type == TokenType.VARREF
                && enums.containsKey(lockFieldValueNode.left.text)
                && lockFieldValueNode.left.text.equals(lockFieldType.baseType);
        if (!isBareEnumVariantRef) {
            throw new CompilerException("type", lockFieldValueNode.file, lockFieldValueNode.line,
                    "omitting every other member of '" + structName + "' requires '"
                            + structInfo.lockFieldName + "' to be given as a direct '"
                            + lockFieldType.baseType + ".Variant' reference -- never a variable or a "
                            + "call result, so the mismatch can be proven at compile time");
        }
        String variantName = lockFieldValueNode.right.text;
        EnumInfo lockEnumInfo = enums.get(lockFieldType.baseType);
        if (!lockEnumInfo.variants.contains(variantName)) {
            throw new CompilerException("type", lockFieldValueNode.file, lockFieldValueNode.line,
                    "'" + lockFieldType.baseType + "' has no variant named '" + variantName + "'");
        }
        if (structInfo.lockVariants.contains(variantName)) {
            throw new CompilerException("type", lockFieldValueNode.file, lockFieldValueNode.line,
                    "'" + structName + "' cannot be constructed by omitting its other members -- '"
                            + lockFieldType.baseType + "." + variantName + "' satisfies this struct's own "
                            + "lock ('@lock(match self." + structInfo.lockFieldName + " : "
                            + String.join("|", structInfo.lockVariants) + ")'), so every other member "
                            + "must be provided instead");
        }
    }

    /**
     * "new T{..}" homes a fresh value on the heap and yields `owns some T` (mutability is the operand's own;
     * a bare literal is `indeterminate`, so `new mut ..` is needed for a let). The operand must construct a
     * value, not name an existing one. 'new' always throws on allocation failure, so it must be covered by
     * `insideThrowingAllocContext` (set by checkTry for the whole descent, since a nested 'new' cannot get
     * its own try); the containing function needs no `@throws` unless the catch re-throws. No
     * destructOnExit is computed: the catch is always local to this frame.
     * See docs/TypeChecker.md, "Storage constructors and new".
     */
    public TypeInfo checkNew(Token op, Scope scope, FuncInfo func) {
        TypeInfo result;
        newDynDepth++;
        try {
            result = checkNewInner(op, scope, func);
        } finally {
            newDynDepth--;
        }
        return result;
    }

    public TypeInfo checkNewInner(Token op, Scope scope, FuncInfo func) {
        requireNotPureRt(func, op, "'new'", "allocation depends on heap state (it can fail), so the result is not a function of the arguments");
        usesOwnsRefDynNew = true;
        usesThrow = true;
        if (!isValueConstructingExpr(op.left)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'new' requires a value-constructing expression (a struct literal, primitive "
                            + "literal, enum variant, array literal, or a call to a struct's own "
                            + "constructor) -- not a reference to an already-existing value");
        }
        if (!insideThrowingAllocContext) {
            throw new CompilerException("type", op.file, op.line,
                    "'new' can fail (it throws when the underlying allocation fails) and must be "
                            + "wrapped in 'try ... catch { ... }'");
        }
        // A struct-RVO call (constructor or struct-returning function) may be the operand: 'new' supplies a
        // stable destination (a hidden stack temp copied by the existing NEW/GT_REGISTER sequence).
        boolean previousRvoEligible = insideStructRvoDestinationPosition;
        insideStructRvoDestinationPosition = true;
        if (op.left.type == TokenType.OPERATOR && op.left.text.equals("CALL")) {
            op.left.coveredByThrowingNew = true; // insideThrowingAllocContext was verified just above
        }
        TypeInfo operandType;
        try {
            operandType = resolveExprType(op.left, scope, func);
        } finally {
            insideStructRvoDestinationPosition = previousRvoEligible;
        }
        return new TypeInfo("owns", operandType.mutability, operandType.baseType).withSome(true);
    }

    /**
     * "atomic x": tags a plain scalar's type as atomic (TypeInfo.isAtomic). Mutability is preserved
     * (still needs an explicit mut/imut wrap to bind); only integer/bool/char, no storage.
     */
    public TypeInfo checkAtomicWrapper(Token op, Scope scope, FuncInfo func) {
        TypeInfo operandType = resolveExprType(op.left, scope, func);
        if (operandType.storage != null) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'atomic' can only wrap a plain (no-storage) scalar value, not a pointer");
        }
        if (!ATOMIC_ELIGIBLE_BASE_TYPES.contains(operandType.baseType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'" + operandType.baseType + "' cannot be made 'atomic' -- only an integer, 'bool', "
                            + "or 'char' type can be backed by a real atomic instruction");
        }
        return new TypeInfo(null, operandType.mutability, operandType.baseType).withAtomic(true);
    }

    /**
     * "a swap b": atomic read-modify-write, yields the previous value (dedicated ATOMIC_SWAP, it cannot
     * be decomposed into PUSH + ASSIGN atomically).
     */
    public TypeInfo checkSwapOperator(Token op, Scope scope, FuncInfo func) {
        rejectDerefAsWriteTarget(op.left, "'swap'");
        // Left: a bare reference (atomic global/static) or a '.' chain (a 'swap'-tagged struct field, used
        // by checkLockMatchStatement's synthesized acquire).
        boolean isBareOrFieldChain = op.left.type == TokenType.VARREF
                || (op.left.type == TokenType.OPERATOR && op.left.text.equals("."));
        if (!isBareOrFieldChain) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "the left operand of 'swap' must be a bare reference to an atomic variable, or a "
                            + "'.'-member-access chain naming an atomic struct field");
        }
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        if (!leftType.isAtomic) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "the left operand of 'swap' must be an 'atomic'-typed value");
        }
        if (!"mut".equals(leftType.mutability)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "the left operand of 'swap' must be concretely 'mut' -- an 'imut' atomic can never "
                            + "be swapped");
        }
        // A bare EnumName.Variant is always 'imut' and would not coerce to a 'mut' atomic slot via
        // typesCompatible; same exception as checkInstantiate's swap-field case.
        boolean isBareEnumVariantRight = enums.containsKey(leftType.baseType)
                && isBareEnumVariantRefNode(op.right, leftType.baseType);
        // Syntax-honesty rule (see isSwapRhsAllowedShape); checked on the raw tree before type resolution.
        if (!isBareEnumVariantRight && !isSwapRhsAllowedShape(op.right)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "the right operand of 'swap' must be a bare variable, a literal, or an 'EnumName.Variant' "
                            + "reference -- not a computed expression. 'swap' is a single atomic replace; "
                            + "writing the value to swap in as its own separate 'let' first keeps that "
                            + "computation's own non-atomicity visible, rather than letting it look bundled "
                            + "into one atomic step");
        }
        TypeInfo rightType = resolveExprType(op.right, scope, func);
        if (!isBareEnumVariantRight && !typesCompatible(leftType, rightType)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'swap' requires the right operand to be type-compatible with the atomic's own value "
                            + "type ('" + leftType.canonical() + "'), but found '" + rightType.canonical() + "'");
        }
        invalidateMatchPatternsForWrite(op.left, scope);
        // A plain (never atomic) snapshot of the old value, "indeterminate" like any computed result.
        return new TypeInfo(null, "indeterminate", leftType.baseType);
    }

    /**
     * "await f(args)": blocks and yields the callee's own return type (no handle). The CALL is marked
     * `asyncCallKind = "AWAIT"` before dispatch so checkCall enforces the @async requirement in one place.
     */
    public TypeInfo checkAwait(Token op, Scope scope, FuncInfo func) {
        TypeInfo result;
        result = checkAwaitInner(op, scope, func);
        ProofKills.kill(scope.activeMatchPatterns, op, "'await' (another thread runs)");
        return result;
    }

    public TypeInfo checkAwaitInner(Token op, Scope scope, FuncInfo func) {
        requireNotPure(func, op, "'await'", "it waits on another thread");
        if (op.left.type != TokenType.OPERATOR || !op.left.text.equals("CALL")) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'await' must directly wrap a function call, e.g. 'await foo(...)'");
        }
        if (!op.insideTry) {
            throw new CompilerException("type", op.file, op.line,
                    "'await' can fail (out of memory for its handle, or the thread cannot be started), so it must be "
                            + "wrapped in 'try ... catch(e){ ... }' or written '?await ...'");
        }
        usesThrow = true; // like 'new': the failure exits set gt_error_message
        op.left.asyncCallKind = "AWAIT";
        TypeInfo calleeReturnType = resolveExprType(op.left, scope, func);
        prepareAsyncCallSite(op.left, calleeReturnType);
        return calleeReturnType;
    }

    /**
     * Shared by checkAwait/checkPar after the CALL is resolved: finds the top-level @async FuncInfo,
     * obtains the handle type (exposed for non-void, internal for void), records its names on the CALL
     * token for BytecodeEmitter.emitAsyncCall, and synthesizes the (memoized) trampoline.
     */
    public TypeInfo prepareAsyncCallSite(Token callTok, TypeInfo calleeReturnType) {
        // par/await need the ghost table even if the program never writes owns/ref/new/dyn: without this
        // flag GT_INIT never runs and gt_register grows from capacity 0 forever (heap corruption).
        usesOwnsRefDynNew = true;
        FuncInfo asyncFuncInfo = findTopLevelAsyncFuncInfo(callTok);
        TypeInfo paramType = asyncFuncInfo.paramTypes.isEmpty() ? null : asyncFuncInfo.paramTypes.get(0);
        boolean isVoid = calleeReturnType.baseType.equals("void");
        TypeInfo handleType = getOrCreateAsyncHandleType(isVoid ? null : calleeReturnType, paramType, callTok,
                "PAR".equals(callTok.asyncCallKind));
        callTok.asyncHandleStructName = handleType.baseType;
        callTok.asyncHandleCanonicalType = handleType.canonical();
        // A hidden (void) handle reached via par is never visible to the caller and its thread may still read
        // it, so it needs a self-destructing trampoline; void via await shares the ordinary one (it joins first).
        boolean selfDestructHidden = isVoid && "PAR".equals(callTok.asyncCallKind);
        boolean lockedHandle = !isVoid && "PAR".equals(callTok.asyncCallKind);
        String trampolineName = "__trampoline_" + asyncFuncInfo.mangledName
                + (selfDestructHidden ? "__par_fire_and_forget" : lockedHandle ? "__par_locked" : "");
        callTok.asyncTrampolineName = trampolineName;
        synthesizeAsyncTrampoline(asyncFuncInfo, handleType, paramType, isVoid ? null : calleeReturnType,
                trampolineName, selfDestructHidden, lockedHandle);
        if (lockedHandle) {
            synthesizeAsyncDropHook(handleType.baseType, asyncFuncInfo.funcToken.file);
        }
        return handleType;
    }

    /**
     * Finds the FuncInfo of the resolved @async call target (plain top-level functions only: there is no
     * way to take a method's function-pointer value for "@par_call"/"@await_call").
     */
    public FuncInfo findTopLevelAsyncFuncInfo(Token callTok) {
        for (List<FuncInfo> overloads : functions.values()) {
            for (FuncInfo fi : overloads) {
                if (fi.mangledName.equals(callTok.resolvedCallTarget)) {
                    return fi;
                }
            }
        }
        throw new CompilerException("type", callTok.file, callTok.line,
                "'par'/'await' on an '@async' method (as opposed to a plain top-level function) isn't "
                        + "supported yet -- only a plain top-level '@async' function can be used here");
    }

    /**
     * Synthesizes the `AsyncState` enum (PENDING, RUNNING, READY) once, lazily, as EnumInfo plus a root
     * token appended to `rootLinesRef`, so it is available without any import.
     */
    public void ensureAsyncStateEnum() {
        if (asyncStateEnumEnsured) {
            return;
        }
        asyncStateEnumEnsured = true;
        EnumInfo info = new EnumInfo("AsyncState");
        info.variants.add("PENDING");
        info.variants.add("RUNNING");
        info.variants.add("READY");
        // Non-exhaustive so `match h.state{ READY:{..} default:{..} }` works.
        info.isNonExhaustive = true;
        enums.put("AsyncState", info);
        Token nameTok = new Token(TokenType.VARREF, "AsyncState", 0, "<synthesized>");
        Token enumTok = new Token(TokenType.KEYWORD, "enum", 0, "<synthesized>");
        enumTok.sub = new ArrayList<>();
        enumTok.sub.add(nameTok);
        enumTok.childs = new ArrayList<>();
        appendSynthesizedRootToken(enumTok);
    }

    /** The two-variant (OPEN, CLOSED) enum behind the swap-mutex `gate` member of a `par` handle (see getOrCreateAsyncHandleType). */
    public void ensureAsyncGateEnum() {
        if (asyncGateEnumEnsured) {
            return;
        }
        asyncGateEnumEnsured = true;
        EnumInfo info = new EnumInfo("AsyncGate");
        info.variants.add("OPEN");
        info.variants.add("CLOSED");
        enums.put("AsyncGate", info);
        appendSynthesizedEnumRootToken("AsyncGate");
    }

    /** Wraps `t` in a LINE token and appends it to the live root list BytecodeEmitter walks later. */
    public void appendSynthesizedRootToken(Token t) {
        Token lineTok = new Token(TokenType.LINE, "line", t.line, t.file);
        lineTok.childs = new ArrayList<>();
        lineTok.childs.add(t);
        rootLinesRef.add(lineTok);
    }

    /**
     * Generates the compiler-owned `Class` enum: one u64 variant per non-@untyped struct, in declaration
     * order (0..n-1) = the hidden `___type` id used by interface dispatch and `instanceof`. Called at the
     * end of `collectDeclarations`; no eligible struct = no enum; `Class` is reserved.
     */
    public void generateClassHierarchyEnums() {
        if (enums.containsKey("Class") || structs.containsKey("Class") || interfaces.containsKey("Class")) {
            throw new CompilerException("type", "<synthesized>", 0,
                    "'Class' is reserved for the compiler-generated enum of every struct -- "
                            + "rename the conflicting declaration");
        }
        EnumInfo classInfo = new EnumInfo("Class");
        classInfo.explicitValues = new ArrayList<>();
        long next = 0;
        for (String name : structDeclOrder) {
            if (isUntyped(name)) {
                continue;
            }
            classInfo.variants.add(name);
            classInfo.explicitValues.add(next);
            structs.get(name).classId = next;
            next++;
        }
        if (classInfo.variants.isEmpty()) {
            return;
        }
        enums.put("Class", classInfo);
        appendSynthesizedEnumRootToken("Class");
    }


    public void appendSynthesizedEnumRootToken(String enumName) {
        Token nameTok = new Token(TokenType.VARREF, enumName, 0, "<synthesized>");
        Token enumTok = new Token(TokenType.KEYWORD, "enum", 0, "<synthesized>");
        enumTok.sub = new ArrayList<>();
        enumTok.sub.add(nameTok);
        enumTok.childs = new ArrayList<>();
        appendSynthesizedRootToken(enumTok);
    }

    /**
     * For each interface, a compiler-generated u64 enum of its implementing structs (values = their
     * `Class` ids), stored under the never-user-nameable key "$enum_for_<Interface>" and reached only
     * via the "Owner.enum" syntax (legal only in a match condition; see checkDot/checkMatchCondition).
     */
    public void generateEnumForEnums() {
        for (String interfaceName : interfaces.keySet()) {
            List<String> implementers = new ArrayList<>();
            for (String structName : structDeclOrder) {
                StructInfo si = structs.get(structName);
                if (si.classId == null) {
                    continue;
                }
                List<ImplInfo> impls = implsByConcreteType.get(structName);
                if (impls == null) {
                    continue;
                }
                for (ImplInfo impl : impls) {
                    if (interfaceName.equals(impl.interfaceName)) {
                        implementers.add(structName);
                        break;
                    }
                }
            }
            if (!implementers.isEmpty()) {
                registerEnumForEnum(interfaceName, implementers);
            }
        }
    }

    public void registerEnumForEnum(String ownerName, List<String> memberNames) {
        String syntheticName = "$enum_for_" + ownerName;
        EnumInfo info = new EnumInfo(syntheticName);
        info.explicitValues = new ArrayList<>();
        for (String memberName : memberNames) {
            info.variants.add(memberName);
            info.explicitValues.add(structs.get(memberName).classId);
        }
        enums.put(syntheticName, info);
        appendSynthesizedEnumRootToken(syntheticName);
    }

    /**
     * Synthesizes one dispatcher function per (interface, method) so `c.foo(c, ..)` compiles when `c` is
     * only known by its interface: a `match Some(p_self){ match p_self implements I.enum{ Impl:{ [return]
     * p_self.m(..) } .. } }` body built directly as Token nodes, plus a trailing default-literal return
     * for non-void. Skipped (clear error at the call) for methods with no `self`-typed parameter or with
     * no synthesizable default return. See docs/TypeChecker.md, "Compiler-generated enums and interface dispatchers".
     */
    public void generateInterfaceDispatchers() {
        // Skipped unless a '@gt_alive_check' function exists: the bodies use 'match Some(..)' (GT_ALIVE_CHECK),
        // and generating them in programs without the ghost-table stdlib would newly require it.
        if (!hasGtAliveCheckFunction()) {
            return;
        }
        for (Map.Entry<String, InterfaceInfo> ifaceEntry : interfaces.entrySet()) {
            String interfaceName = ifaceEntry.getKey();
            EnumInfo enumForInfo = enums.get("$enum_for_" + interfaceName);
            if (enumForInfo == null || enumForInfo.variants.isEmpty()) {
                continue; // no real implementers -- nothing to dispatch to
            }
            for (Map.Entry<String, RawMethodSig> methodEntry : ifaceEntry.getValue().methods.entrySet()) {
                RawMethodSig sig = methodEntry.getValue();
                if (sig.isStatic) {
                    continue; // no receiver -- "TypeName.method()" is already unambiguous, needs no dispatcher
                }
                if (getDecorator(sig.decorators, "lock") != null) {
                    // @lock methods are skipped: the synthesized call would need a lock proof (future work).
                    continue;
                }
                generateOneInterfaceDispatcher(interfaceName, methodEntry.getKey(), sig, enumForInfo.variants);
            }
        }
    }

    /** Whether any function is decorated '@gt_alive_check' (gates generateInterfaceDispatchers). */
    public boolean hasGtAliveCheckFunction() {
        for (List<FuncInfo> overloads : functions.values()) {
            for (FuncInfo fi : overloads) {
                if (getDecorator(fi.funcToken.decorators, "gt_alive_check") != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Name `checkMethodCall` redirects to; a collision is caught by collectFunc's "already declared" check. */
    public String interfaceDispatcherName(String interfaceName, String methodName) {
        return "__iface_dispatch__" + interfaceName + "__" + methodName;
    }

    public void generateOneInterfaceDispatcher(String interfaceName, String methodName, RawMethodSig sig,
            List<String> implementers) {
        String dispatcherName = interfaceDispatcherName(interfaceName, methodName);
        if (functions.containsKey(dispatcherName)) {
            return; // name collision (see interfaceDispatcherName's own doc) -- leave this method un-dispatchable rather than risk overwriting something real
        }
        List<List<Token>> rawGroups = splitIntoRawGroups(sig.paramsTok.childs);
        List<String> paramNames = new ArrayList<>();
        List<TypeInfo> paramTypes = new ArrayList<>();
        int selfIdx = -1;
        for (List<Token> group : rawGroups) {
            String pname = group.get(0).text;
            List<Token> origTypeTokens = group.subList(2, group.size());
            if (selfIdx == -1 && !origTypeTokens.isEmpty()
                    && origTypeTokens.get(origTypeTokens.size() - 1).type == TokenType.VARREF
                    && origTypeTokens.get(origTypeTokens.size() - 1).text.equals("self")) {
                selfIdx = paramNames.size();
            }
            List<Token> substituted = substituteSelfCopy(origTypeTokens, interfaceName);
            paramNames.add(pname);
            paramTypes.add(resolveTypeAnnotation(substituted, group.get(0)));
        }
        if (selfIdx == -1) {
            return; // no 'self'-typed parameter anywhere -- see this method's own doc comment for why this can't be dispatched
        }
        List<Token> substitutedReturn = substituteSelfCopy(sig.returnTypeTokens, interfaceName);
        TypeInfo returnType = substitutedReturn.isEmpty()
                ? new TypeInfo(null, "imut", "void")
                : resolveTypeAnnotation(substitutedReturn, sig.nameTok, true);
        Token fallbackReturnLiteral = returnType.baseType.equals("void") ? null
                : buildDefaultLiteralFor(returnType, sig.nameTok);
        if (!returnType.baseType.equals("void") && fallbackReturnLiteral == null) {
            return; // can't synthesize a fallback for this return type -- see this method's own doc comment
        }

        Token at = sig.nameTok;
        String recvName = paramNames.get(selfIdx);

        // "match Some(recv){ match recv implements Interface.enum{ Case1:{...} ... } }"
        Token someArgsDelin = new Token(TokenType.DELINEATOR, "(", at.line, at.file);
        someArgsDelin.childs = new ArrayList<>();
        someArgsDelin.childs.add(new Token(TokenType.VARREF, recvName, at.line, at.file));
        Token someCall = new Token(TokenType.OPERATOR, "CALL", at.line, at.file);
        someCall.left = new Token(TokenType.VARREF, "Some", at.line, at.file);
        someCall.right = someArgsDelin;

        Token dotEnum = new Token(TokenType.OPERATOR, ".", at.line, at.file);
        dotEnum.left = new Token(TokenType.VARREF, interfaceName, at.line, at.file);
        dotEnum.right = new Token(TokenType.VARREF, "enum", at.line, at.file);
        Token implementsOp = new Token(TokenType.OPERATOR, "implements", at.line, at.file);
        implementsOp.left = new Token(TokenType.VARREF, recvName, at.line, at.file);
        implementsOp.right = dotEnum;

        Token caseMatchTok = new Token(TokenType.KEYWORD, "match", at.line, at.file);
        caseMatchTok.isCaseMatch = true;
        caseMatchTok.decorators = new ArrayList<>();
        caseMatchTok.sub = new ArrayList<>();
        caseMatchTok.sub.add(implementsOp);
        caseMatchTok.childs = new ArrayList<>();
        for (String implName : implementers) {
            // recv.method(p0, p1, ..): every dispatcher param passed through unchanged (recv at its own position).
            Token dotCall = new Token(TokenType.OPERATOR, ".", at.line, at.file);
            dotCall.left = new Token(TokenType.VARREF, recvName, at.line, at.file);
            dotCall.right = new Token(TokenType.VARREF, methodName, at.line, at.file);
            Token argsDelin = new Token(TokenType.DELINEATOR, "(", at.line, at.file);
            argsDelin.childs = new ArrayList<>();
            if (!paramNames.isEmpty()) {
                Token commaChain = new Token(TokenType.VARREF, paramNames.get(0), at.line, at.file);
                for (int i = 1; i < paramNames.size(); i++) {
                    Token comma = new Token(TokenType.OPERATOR, ",", at.line, at.file);
                    comma.left = commaChain;
                    comma.right = new Token(TokenType.VARREF, paramNames.get(i), at.line, at.file);
                    commaChain = comma;
                }
                argsDelin.childs.add(commaChain);
            }
            Token callOp = new Token(TokenType.OPERATOR, "CALL", at.line, at.file);
            callOp.left = dotCall;
            callOp.right = argsDelin;

            Token stmt;
            if (returnType.baseType.equals("void")) {
                stmt = callOp; // a bare call statement, same shape any real "_self.method(...)" statement line already has
            } else {
                stmt = new Token(TokenType.KEYWORD, "return", at.line, at.file);
                stmt.sub = new ArrayList<>();
                stmt.sub.add(callOp);
            }
            Token stmtLine = new Token(TokenType.LINE, "line", at.line, at.file);
            stmtLine.childs = new ArrayList<>();
            stmtLine.childs.add(stmt);

            Token caseTok = new Token(TokenType.VARREF, implName, at.line, at.file);
            caseTok.sub = new ArrayList<>();
            caseTok.sub.add(new Token(TokenType.VARREF, implName, at.line, at.file));
            caseTok.childs = new ArrayList<>();
            caseTok.childs.add(stmtLine);
            caseMatchTok.childs.add(caseTok);
        }
        Token caseMatchLine = new Token(TokenType.LINE, "line", at.line, at.file);
        caseMatchLine.childs = new ArrayList<>();
        caseMatchLine.childs.add(caseMatchTok);

        Token someMatchTok = new Token(TokenType.KEYWORD, "match", at.line, at.file);
        someMatchTok.decorators = new ArrayList<>();
        someMatchTok.sub = new ArrayList<>();
        someMatchTok.sub.add(someCall);
        someMatchTok.childs = new ArrayList<>();
        someMatchTok.childs.add(caseMatchLine);
        Token someMatchLine = new Token(TokenType.LINE, "line", at.line, at.file);
        someMatchLine.childs = new ArrayList<>();
        someMatchLine.childs.add(someMatchTok);

        Token funcTok = new Token(TokenType.KEYWORD, "func", at.line, at.file);
        funcTok.decorators = new ArrayList<>();
        funcTok.sub = new ArrayList<>();
        funcTok.sub.add(new Token(TokenType.VARREF, dispatcherName, at.line, at.file));
        funcTok.childs = new ArrayList<>();
        funcTok.childs.add(someMatchLine);
        if (fallbackReturnLiteral != null) {
            Token fallbackReturn = new Token(TokenType.KEYWORD, "return", at.line, at.file);
            fallbackReturn.sub = new ArrayList<>();
            fallbackReturn.sub.add(fallbackReturnLiteral);
            Token fallbackLine = new Token(TokenType.LINE, "line", at.line, at.file);
            fallbackLine.childs = new ArrayList<>();
            fallbackLine.childs.add(fallbackReturn);
            funcTok.childs.add(fallbackLine);
        }

        FuncInfo info = new FuncInfo();
        info.name = dispatcherName;
        info.mangledName = dispatcherName;
        info.paramNames.addAll(paramNames);
        info.paramTypes.addAll(paramTypes);
        info.returnType = returnType;
        info.callConvention = config.defaultConvention;
        info.isPublic = true;
        info.funcToken = funcTok;
        functions.computeIfAbsent(dispatcherName, k -> new ArrayList<>()).add(info);
        appendSynthesizedRootToken(funcTok);
    }

    /** Trivial well-typed default literal for a dispatcher's unreachable fallback return; null (skip the dispatcher) outside {non-'some' pointer, bool, integer, float}. */
    public Token buildDefaultLiteralFor(TypeInfo t, Token at) {
        if (t.storage != null) {
            return t.isSome ? null : new Token(TokenType.NULL, "null", at.line, at.file);
        }
        if (t.baseType.equals("bool")) {
            return mutabilityWrap(t, new Token(TokenType.BOOL, "false", at.line, at.file));
        }
        if (isFloatType(t.baseType)) {
            return mutabilityWrap(t, new Token(TokenType.FLOAT, "0.0", at.line, at.file));
        }
        if (Arrays.asList("u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64").contains(t.baseType)) {
            return mutabilityWrap(t, new Token(TokenType.INTEGER, "0", at.line, at.file));
        }
        return null;
    }

    /** "mut 0"/"imut 0" in the unary-prefix shape RpnConverter builds (OPERATOR, unary, operand on `.left`). */
    public Token mutabilityWrap(TypeInfo t, Token literal) {
        String mutability = "mut".equals(t.mutability) ? "mut" : "imut";
        Token op = new Token(TokenType.OPERATOR, mutability, literal.line, literal.file);
        op.unary = true;
        op.left = literal;
        return op;
    }

    /**
     * Synthesizes the handle struct for an @async call as compiler-internal data (GenericsExpander runs
     * too early to infer T): non-void `par` handles get `state`, `result`, `value`, `threadId` plus a
     * `resolve()` method (raw token tree, type-checked later by the ordinary impl loop) and are returned
     * as `owns some` so `.state`/`:resolve()` need no `match Some`. `resultType == null` (void) gives the
     * hidden internal shape (`value`, `threadId` only). `locked` makes a `par` handle a swap-lock struct.
     * Memoized by (result|void, param|none[, par]). See docs/TypeChecker.md, "Async: await, par, handles".
     */
    public TypeInfo getOrCreateAsyncHandleType(TypeInfo resultType, TypeInfo paramType, Token at, boolean locked) {
        boolean hidden = resultType == null;
        // A handle that `par` hands to the caller (non-void) is a real swap-lock struct: `gate` first, every other member behind
        // `match @lock`. `await`'s handle is compiler-internal (joined before anyone reads it) and stays a plain struct.
        locked = locked && !hidden;
        String key = (hidden ? "void" : resultType.canonical()) + "|" + (paramType == null ? "none" : paramType.canonical())
                + (locked ? "|par" : "");
        TypeInfo cached = asyncHandleTypesByResultCanonical.get(key);
        if (cached != null) {
            return cached;
        }
        // The name is a hex hash, not a readable "AsyncHandle_<types>": the LOB stage's CanonicalType.parse
        // misreads underscore names shaped like "..._mutability_baseType" as canonical types and mis-sizes NEW.
        String structName = "AsyncHandle" + Integer.toHexString(key.hashCode() & 0xFFFFFFF);

        StructInfo si = new StructInfo(structName);
        if (locked) {
            ensureAsyncGateEnum();
            si.members.put("gate", new TypeInfo(null, "mut", "AsyncGate").withAtomic(true));
            si.publicMembers.add("gate");
            si.lockFieldName = "gate";
            si.lockVariants = new ArrayList<>(List.of("OPEN"));
            si.swapFieldName = "gate";
        }
        if (!hidden) {
            ensureAsyncStateEnum();
            si.members.put("state", new TypeInfo(null, "mut", "AsyncState"));
            si.publicMembers.add("state");
        }
        // "value" has the parameter's own exact type (casts only widen, so a uniform u64 slot could not be
        // unpacked); a plain unused "mut u64" for a 0-parameter function.
        si.members.put("value", paramType != null ? paramType : new TypeInfo(null, "mut", "u64"));
        // "threadId": written by "@par_call"/"@await_call" (pthread_create out-parameter), never by the trampoline.
        si.members.put("threadId", new TypeInfo(null, "mut", "u64"));
        if (!hidden) {
            si.members.put("result", resultType.withMutability("mut"));
            si.publicMembers.add("result");
        }
        si.publicMembers.add("value");
        si.publicMembers.add("threadId");
        si.flattened = true;
        si.isPublic = true;
        Token structNameTok = new Token(TokenType.VARREF, structName, at.line, at.file);
        Token structTok = new Token(TokenType.KEYWORD, "struct", at.line, at.file);
        structTok.sub = new ArrayList<>();
        structTok.sub.add(structNameTok);
        structTok.childs = new ArrayList<>();
        structTok.decorators = new ArrayList<>();
        si.declTok = structTok;
        structs.put(structName, si);
        appendSynthesizedRootToken(structTok);

        TypeInfo handleType = new TypeInfo("owns", "indeterminate", structName).withSome(true);

        if (!hidden) {
            // "return _self.result" as a raw unresolved tree (type-checked later); not needed for the hidden shape.
            Token selfRef = new Token(TokenType.VARREF, "_self", at.line, at.file);
            Token resultRef = new Token(TokenType.VARREF, "result", at.line, at.file);
            Token dotOp = new Token(TokenType.OPERATOR, ".", at.line, at.file);
            dotOp.left = selfRef;
            dotOp.right = resultRef;
            Token returnTok = new Token(TokenType.KEYWORD, "return", at.line, at.file);
            returnTok.sub = new ArrayList<>();
            returnTok.sub.add(dotOp);
            Token bodyLine = new Token(TokenType.LINE, "line", at.line, at.file);
            bodyLine.childs = new ArrayList<>();
            bodyLine.childs.add(returnTok);
            Token methodTok = new Token(TokenType.KEYWORD, "func", at.line, at.file);
            methodTok.decorators = new ArrayList<>();
            if (locked) {
                Token.Decorator lockDec = new Token.Decorator("lock", new ArrayList<>(), new ArrayList<>(), at.line, at.file);
                lockDec.lockFieldName = "gate";
                lockDec.lockVariants = new ArrayList<>(List.of("OPEN"));
                methodTok.decorators.add(lockDec);
            }
            methodTok.childs = new ArrayList<>();
            methodTok.childs.add(bodyLine);

            FuncInfo resolveFunc = new FuncInfo();
            resolveFunc.name = "resolve";
            resolveFunc.mangledName = structName + "_resolve";
            resolveFunc.paramNames.add("_self");
            resolveFunc.paramTypes.add(new TypeInfo("ref", "imut", structName).withSome(true));
            resolveFunc.returnType = resultType;
            resolveFunc.callConvention = config.defaultConvention;
            // Public: called from outside this synthesized impl.
            resolveFunc.isPublic = true;
            resolveFunc.funcToken = methodTok;

            ImplInfo implInfo = new ImplInfo();
            implInfo.concreteName = structName;
            // Public regardless of which file first triggers the (memoized) synthesis.
            implInfo.isPublic = true;
            implInfo.methods.put("resolve", resolveFunc);
            if (locked) {
                resolveFunc.enclosingImpl = implInfo; // lets the method's own `@lock(match self.gate : OPEN)` grant its body the proof
            }
            implsByConcreteType.computeIfAbsent(structName, k -> new ArrayList<>()).add(implInfo);

            Token implTok = new Token(TokenType.KEYWORD, "impl", at.line, at.file);
            implTok.sub = new ArrayList<>();
            implTok.sub.add(structNameTok);
            implTok.childs = new ArrayList<>();
            implTok.childs.add(methodTok);
            appendSynthesizedRootToken(implTok);
        }

        asyncHandleTypesByResultCanonical.put(key, handleType);
        return handleType;
    }

    /** Type of `par call(..)`: void for a void '@async' callee (bare statement), else an `owns some AsyncHandle_<T>`. See docs/TypeChecker.md, "par / await and trampolines". */
    public TypeInfo checkPar(Token op, Scope scope, FuncInfo func) {
        TypeInfo result;
        result = checkParInner(op, scope, func);
        ProofKills.kill(scope.activeMatchPatterns, op, "'par' (another thread starts)");
        return result;
    }

    public TypeInfo checkParInner(Token op, Scope scope, FuncInfo func) {
        requireNotPure(func, op, "'par'", "it starts another thread");
        if (op.left.type != TokenType.OPERATOR || !op.left.text.equals("CALL")) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'par' must directly wrap a function call, e.g. 'par foo(...)'");
        }
        if (!op.insideTry) {
            throw new CompilerException("type", op.file, op.line,
                    "'par' can fail (out of memory for its handle, or the thread cannot be started), so it must be "
                            + "wrapped in 'try ... catch(e){ ... }' or written '?par ...'");
        }
        usesThrow = true; // like 'new': the failure exits set gt_error_message
        op.left.asyncCallKind = "PAR";
        TypeInfo calleeReturnType = resolveExprType(op.left, scope, func);
        TypeInfo handleType = prepareAsyncCallSite(op.left, calleeReturnType);
        TypeInfo result = calleeReturnType.baseType.equals("void") ? calleeReturnType : handleType;
        op.left.resolvedType = result.canonical();
        return result;
    }

    /**
     * Synthesizes (once per function) the pthread-shaped trampoline `raw mut u8 __trampoline_<name>(raw mut u8)` as Caspien source text run through the front end,
     * and checks its body eagerly (check()'s sweep worklist is already fixed). See docs/TypeChecker.md, "par / await and trampolines".
     */
    public void synthesizeAsyncTrampoline(FuncInfo asyncFuncInfo, TypeInfo handleType, TypeInfo paramType,
            TypeInfo resultTypeOrNull, String trampolineName, boolean selfDestructHidden, boolean lockedHandle) {
        if (!asyncTrampolinesSynthesized.add(trampolineName)) {
            return; // already synthesized (either ordinarily, or for this exact self-destructing shape)
        }
        String handleStructName = handleType.baseType;
        boolean hasParam = paramType != null;
        boolean hasResult = resultTypeOrNull != null;
        // the handle is recovered from argPtr when the real function reads/writes it or this trampoline self-destructs it
        boolean needsHandlePtr = hasParam || hasResult || selfDestructHidden;

        StringBuilder src = new StringBuilder();
        src.append("func ").append(trampolineName).append("(argPtr: raw mut u8) raw mut u8{\n");
        src.append("\tunsafe{\n");
        if (needsHandlePtr) {
            // two-step reinterpret: a plain raw local is filled by memcopy, then rebound to a `some` local ("null" cannot satisfy `some`)
            src.append("\t\tlet:<raw mut ").append(handleStructName).append("> handlePlain = null\n");
            src.append("\t\tmemcopy(raw handlePlain, mut 8, raw argPtr)\n");
            src.append("\t\tlet:<raw some mut ").append(handleStructName)
                    .append("> handlePtr = handlePlain\n");
        }
        String callExpr = asyncFuncInfo.name + "(" + (hasParam ? "handlePtr.value" : "") + ")";
        if (hasResult && lockedHandle) {
            // publish result and state together under the handle's lock (compiler-written code may retry without a bound)
            src.append("\t\tlet realResult = mut ").append(callExpr).append("\n");
            src.append("\t\tmatch @lock handlePtr{\n");
            src.append("\t\t\tOPEN:{\n");
            src.append("\t\t\t\thandlePtr.result = realResult\n");
            src.append("\t\t\t\thandlePtr.state = AsyncState.READY\n");
            src.append("\t\t\t}\n");
            src.append("\t\t\tCLOSED:{ continue }\n");
            src.append("\t\t}\n");
        } else if (hasResult) {
            src.append("\t\tlet realResult = mut ").append(callExpr).append("\n");
            src.append("\t\thandlePtr.result = realResult\n");
            src.append("\t\thandlePtr.state = AsyncState.READY\n");
        } else {
            src.append("\t\t").append(callExpr).append("\n");
        }
        if (selfDestructHidden) {
            // fire-and-forget par: nobody else destructs the hidden handle, so the thread does it; gt_destruct needs a raw u8, hence the memcopy reinterpret
            if (getGhostTableFunction("gt_destruct") == null) {
                throw new CompilerException("type", asyncFuncInfo.funcToken.file, asyncFuncInfo.funcToken.line,
                        "a 'par' call to void '@async' function '" + asyncFuncInfo.name + "' requires a "
                                + "function decorated '@gt_destruct' to exist somewhere in the compilation "
                                + "unit (none was found) -- its hidden handle must be reclaimed once the "
                                + "real function finishes running on its own thread");
            }
            src.append("\t\tlet:<raw mut u8> handleAsU8 = null\n");
            src.append("\t\tmemcopy(raw handleAsU8, mut 8, raw handlePtr)\n");
            src.append("\t\tgt_destruct(handleAsU8)\n");
        }
        src.append("\t}\n");
        src.append("\treturn null\n");
        src.append("}\n");

        String file = asyncFuncInfo.funcToken.file;
        Lexer lexer = new Lexer(src.toString(), file, file);
        List<Token> tokens = lexer.tokenize();
        Parser parser = new Parser();
        List<Token> parsed = parser.parse(tokens);
        new RpnConverter().convertLines(parsed);
        new TreeBuilder().buildLines(parsed);

        Token funcTok = parsed.get(0).childs.get(0);
        markSynthesizedUnsafe(funcTok);
        Token callNode = findCallNodeByCalleeName(funcTok, asyncFuncInfo.name);
        if (callNode == null) {
            throw new IllegalStateException(
                    "internal error: trampoline synthesis lost its own call to '" + asyncFuncInfo.name + "'");
        }
        callNode.isTrampolineOwnCall = true;

        FuncInfo trampolineInfo = new FuncInfo();
        trampolineInfo.funcToken = funcTok;
        buildFuncInfo(funcTok, trampolineInfo);
        trampolineInfo.declOrder = Integer.MAX_VALUE;
        trampolineInfo.isPublic = false;
        functions.computeIfAbsent(trampolineInfo.name, k -> new ArrayList<>()).add(trampolineInfo);
        boolean savedGlue = synthesizingGlueBody;
        synthesizingGlueBody = true;
        try {
            checkFunctionBody(trampolineInfo);
        } finally {
            synthesizingGlueBody = savedGlue;
        }
        appendSynthesizedRootToken(funcTok);
    }

    /**
     * The `@drop` hook of a `par` handle: when the handle is dropped (scope end, throw unwind, a destruct after a failed start), wait for
     * its thread by joining it, so the handle's memory never goes away under a running thread. `threadId` is 0 when no thread was started
     * (pthread_create failed), then there is nothing to join. Written as Caspien source like the trampoline; trusted glue code.
     */
    public void synthesizeAsyncDropHook(String handleStructName, String file) {
        if (!asyncDropHooksSynthesized.add(handleStructName)) {
            return;
        }
        String hookName = "__join_" + handleStructName;
        StringBuilder src = new StringBuilder();
        src.append("@drop\n");
        src.append("func ").append(hookName).append("(r: ref some mut ").append(handleStructName).append(") void{\n");
        src.append("\tunsafe extern{\n");
        src.append("\t\tlet tid = mut r.threadId\n");
        src.append("\t\tif tid != 0{\n");
        src.append("\t\t\tpthread_join(tid, null)\n");
        src.append("\t\t}\n");
        src.append("\t}\n");
        src.append("}\n");
        Lexer lexer = new Lexer(src.toString(), file, file);
        List<Token> tokens = lexer.tokenize();
        Parser parser = new Parser();
        List<Token> parsed = parser.parse(tokens);
        new RpnConverter().convertLines(parsed);
        new TreeBuilder().buildLines(parsed);
        Token funcTok = parsed.get(0).childs.get(0);
        markSynthesizedUnsafe(funcTok);
        FuncInfo hookInfo = new FuncInfo();
        hookInfo.funcToken = funcTok;
        buildFuncInfo(funcTok, hookInfo);
        hookInfo.declOrder = Integer.MAX_VALUE;
        hookInfo.isPublic = false;
        functions.computeIfAbsent(hookInfo.name, k -> new ArrayList<>()).add(hookInfo);
        boolean savedGlue = synthesizingGlueBody;
        synthesizingGlueBody = true;
        try {
            checkFunctionBody(hookInfo);
        } finally {
            synthesizingGlueBody = savedGlue;
        }
        appendSynthesizedRootToken(funcTok);
    }

    /** Marks every 'unsafe' block inside compiler-written code as exempt from the unsafe-tag rules. */
    public void markSynthesizedUnsafe(Token node) {
        if (node == null) {
            return;
        }
        if (node.type == TokenType.KEYWORD && node.text.equals("unsafe")) {
            node.synthesizedUnsafe = true;
        }
        if (node.childs != null) {
            for (Token c : node.childs) {
                markSynthesizedUnsafe(c);
            }
        }
    }

    /** Recursive left/right/childs/sub walk (the same generic Token-tree traversal shape `collectCallTargetsAndBanInvoke` in BytecodeEmitter already uses for an unrelated purpose) for the first "CALL" node whose own callee is a bare VARREF named `calleeName` -- used only to find synthesizeAsyncTrampoline's own single, known-unique call into the real "@async" function it wraps. */
    public Token findCallNodeByCalleeName(Token node, String calleeName) {
        if (node == null) {
            return null;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left != null
                && node.left.type == TokenType.VARREF && node.left.text.equals(calleeName)) {
            return node;
        }
        Token found = findCallNodeByCalleeName(node.left, calleeName);
        if (found != null) {
            return found;
        }
        found = findCallNodeByCalleeName(node.right, calleeName);
        if (found != null) {
            return found;
        }
        if (node.childs != null) {
            for (Token c : node.childs) {
                found = findCallNodeByCalleeName(c, calleeName);
                if (found != null) {
                    return found;
                }
            }
        }
        if (node.sub != null) {
            for (Token c : node.sub) {
                found = findCallNodeByCalleeName(c, calleeName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public boolean isValueConstructingExpr(Token node) {
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
            return isValueConstructingExpr(node.childs.get(0)); // transparent grouping
        }
        if (node.type == TokenType.VARREF) {
            return false; // a bare reference to an existing variable
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".")
                && !(node.left.type == TokenType.VARREF && enums.containsKey(node.left.text))) {
            return false; // a member-access chain on an existing value (not an EnumName.Variant reference)
        }
        return true;
    }
}

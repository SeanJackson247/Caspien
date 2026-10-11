package caspien.typechecker;
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

public class TypeChecker extends TypeCheckerAccessAndAsync {


    public TypeInfo checkAddressOf(Token op, Scope scope, FuncInfo func) {
        String storage = op.text;
        if (storage.equals("raw") && !unsafeBypass(scope, "raw")) {
            throw new CompilerException("type", op.file, op.line,
                    "'raw' pointers can only be constructed from within 'unsafe' code");
        }
        // 'raw' needs an addressable lvalue; a string literal is the one exception (already static memory). See docs/TypeChecker.md, "Address-of operators".
        if (storage.equals("raw") && op.left.type != TokenType.STRING && !isAddressableLvalue(op.left)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'raw' requires an addressable variable (or a struct member access chain rooted in "
                            + "one), or a string literal (already real, static memory) -- not another kind "
                            + "of literal or a freshly-computed value; bind it to a 'let' first");
        }
        if (storage.equals("owns")) {
            throw new CompilerException("type", op.file, op.line,
                    "'owns' pointers can't be constructed with the 'owns' prefix -- this language has "
                            + "no general heap allocation mechanism yet; use 'new' instead");
        }
        if (storage.equals("ref")) {
            // a 'ref' can only be taken from an existing 'owns' value (already heap-resident)
            TypeInfo operandDeclaredType = lookupDeclaredTypeStructurally(op.left, scope);
            if (operandDeclaredType == null || !"owns".equals(operandDeclaredType.storage)) {
                throw new CompilerException("type", op.file, op.line,
                        "'ref' can only be constructed from an existing 'owns'-typed value -- this "
                                + "language has no general heap allocation mechanism yet");
            }
        }
        if (storage.equals("static") && !(op.left.type == TokenType.STRING || op.left.type == TokenType.CHAR
                || (op.left.type == TokenType.VARREF
                        && (scope.isStatic(op.left.text) || globals.containsKey(op.left.text))))) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'static' can only be applied to a string literal, a char literal, or a reference to "
                            + "a 'let static' variable or global");
        }
        if (storage.equals("static") && op.left.type == TokenType.VARREF) {
            // `static x` of a struct-typed global is lowered as a copy of the struct into an 8-byte pointer slot (stack corruption, or a
            // garbage pointer): a struct static is reached by its own name (a swap-lock static under `match @lock`), never by a pointer.
            TypeInfo staticOperand = scope.lookup(op.left.text) != null ? scope.lookup(op.left.text) : globals.get(op.left.text);
            if (staticOperand != null && staticOperand.baseType != null && structs.containsKey(stripArrayDims(staticOperand.baseType))) {
                throw new CompilerException("type", op.left.file, op.left.line,
                        "'static " + op.left.text + "' is not supported: '" + op.left.text + "' is a struct, and a struct static is reached "
                                + "by its own name (a swap-lock static under 'match @lock'), not through a pointer");
            }
        }
        if (storage.equals("auto") && !isAddressableLvalue(op.left)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'auto' requires an addressable variable (or a struct member access chain rooted "
                            + "in one), not a literal or a freshly-computed value");
        }
        if (storage.equals("auto")) {
            // `auto` is a pointer to the CALLER's stack slot. A static or global lives for the whole program and is shared between
            // threads, so a pointer to it (or into a swap-lock static's members) would carry shared memory out of its lock.
            Token root = op.left;
            while (root != null && ((root.type == TokenType.OPERATOR && ".".equals(root.text) && root.left != null)
                    || (root.type == TokenType.DELINEATOR && "(".equals(root.text) && !root.childs.isEmpty()))) {
                root = root.type == TokenType.OPERATOR ? root.left : root.childs.get(0);
            }
            if (root != null && root.type == TokenType.VARREF
                    && (scope.isStatic(root.text) || (scope.lookup(root.text) == null && globals.containsKey(root.text)))) {
                throw new CompilerException("type", op.left.file, op.left.line,
                        "'auto' cannot point at the static or global '" + root.text + "': it is shared memory, and a pointer to it "
                                + "would reach it without its lock. Take the value inside the lock instead");
            }
        }
        TypeInfo operandType = resolveExprType(op.left, scope, func);
        if (storage.equals("auto") && operandType.storage != null && !"static".equals(operandType.storage)) {
            // `auto c` of a variable that is itself a pointer (`owns`/`ref`/`raw`/`auto`) was typed as a pointer to the pointee but produced the
            // address of the VARIABLE, so writes through it landed in the neighbouring stack slots (9 Oct demo: `auto c.n += 1` left c.n at 41).
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'auto' cannot be applied to '" + (op.left.text == null ? "this expression" : op.left.text) + "', which is already a pointer ("
                            + operandType.canonical() + "): use the pointer itself, or 'ref' for a borrow of an owned value");
        }
        // the result is a fresh value: indeterminate mutability, needing mut/imut before a 'let' binds it
        TypeInfo prefixResult = operandType.withMutability("indeterminate").withStorage(storage);
        if (storage.equals("ref") && !prefixResult.isSome) {
            op.refToId = true;   // `ref x` of a nullable owner is a nullable `ref`: an id, not the address
        }
        return prefixResult;
    }

    /** True for a VARREF, or a '.' chain of member accesses rooted in one -- the things 'auto' can validly point to. */
    public boolean isAddressableLvalue(Token node) {
        if (node.type == TokenType.VARREF) {
            return true;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".")) {
            return isAddressableLvalue(node.left);
        }
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
            // transparent grouping parens
            return isAddressableLvalue(node.childs.get(0));
        }
        return false;
    }

    /**
     * Weaker than isAddressableLvalue: also allows LOOKUP anywhere in the chain ('arr[i].f', 'm[i][j]'). Used only by checkReturn's struct-RVO restriction.
     * See docs/TypeChecker.md, "Address-of operators".
     */
    public boolean isReadableValueChain(Token node) {
        if (node.type == TokenType.VARREF) {
            return true;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".")) {
            return isReadableValueChain(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("LOOKUP")) {
            return isReadableValueChain(node.left);
        }
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
            return isReadableValueChain(node.childs.get(0));
        }
        return false;
    }

    /** Prefix `mut`/`imut`: forces concrete mutability; only imut -> mut is rejected. See docs/TypeChecker.md, "Address-of operators". */
    public TypeInfo checkMutabilityOf(Token op, Scope scope, FuncInfo func) {
        String target = op.text;
        TypeInfo operandType = resolveExprType(op.left, scope, func);
        if ("imut".equals(operandType.mutability) && target.equals("mut")) {
            throw new CompilerException("type", op.file, op.line,
                    "cannot make an 'imut' value 'mut' -- only an indeterminate or already-'mut' "
                            + "value can become 'mut'");
        }
        return operandType.withMutability(target);
    }

    /** `a..b`: a range type whose base-type string embeds its bounds; both operands u64, each a bare literal or variable. See docs/TypeChecker.md, "Ranges and membership operators". */
    public TypeInfo checkRange(Token op, Scope scope, FuncInfo func) {
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        TypeInfo rightType = resolveExprType(op.right, scope, func);
        if (!isPlainU64(leftType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'..' operands must resolve to a plain 'u64', got '" + leftType.canonical() + "'");
        }
        if (!isPlainU64(rightType)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'..' operands must resolve to a plain 'u64', got '" + rightType.canonical() + "'");
        }
        String leftDesc = rangeBoundDescriptor(op.left, leftType);
        String rightDesc = rangeBoundDescriptor(op.right, rightType);
        String baseType = "range(" + leftDesc + "," + rightDesc + ")";
        return new TypeInfo(null, "imut", baseType);
    }

    public boolean isPlainU64(TypeInfo t) {
        return t.storage == null && t.baseType.equals("u64");
    }

    public String rangeBoundDescriptor(Token boundExpr, TypeInfo boundType) {
        if (boundExpr.type == TokenType.INTEGER) {
            return boundExpr.text;
        }
        if (boundExpr.type == TokenType.VARREF) {
            return boundType.mutability + "_" + boundExpr.text;
        }
        throw new CompilerException("type", boundExpr.file, boundExpr.line,
                "a range bound must be a bare integer literal or a bare variable, not a computed "
                        + "expression");
    }

    /** True for "dynarray(T)" (not the "unsafe dynarray(T)" variant, see isUnsafeDynArrayType). */
    public boolean isDynArrayType(TypeInfo t) {
        return t.dynArrayElementType != null;
    }

    /** True for "unsafe dynarray(T)"; never true together with isDynArrayType. */
    public boolean isUnsafeDynArrayType(TypeInfo t) {
        return t.unsafeDynArrayElementType != null;
    }

    /** True for any range type, complete or incomplete (bare "range" behind a pointer). */
    public boolean isRangeType(TypeInfo t) {
        return t.baseType.equals("range") || t.baseType.startsWith("range(");
    }

    /** True only for a fully-specified range -- false for a bare, incomplete "range" pointer. */
    public boolean isCompleteRange(TypeInfo t) {
        return t.baseType.startsWith("range(");
    }

    /** Builds the synthesized call "range(arr)" around an already-parsed fixed-array expression (same CALL shape the parser produces for a written-out `range(arr)`). */
    public Token wrapFixedArrayAsRange(Token arr) {
        Token args = new Token(TokenType.DELINEATOR, "(", arr.line, arr.file);
        args.childs = new ArrayList<>();
        args.childs.add(arr);
        Token call = new Token(TokenType.OPERATOR, "CALL", arr.line, arr.file);
        call.left = new Token(TokenType.VARREF, "range", arr.line, arr.file);
        call.right = args;
        return call;
    }

    /** `x in R`: u64 or range on the left; range, dynarray, fixed array, string or guaranteed enum on the right. See docs/TypeChecker.md, "Ranges and membership operators". */
    public TypeInfo checkIn(Token op, Scope scope, FuncInfo func) {
        // a bare enum name on the right is a different shape: `u64 in Enum`
        if (op.right.type == TokenType.VARREF && enums.containsKey(op.right.text)) {
            return checkGuaranteedEnumMembership(op, scope, func);
        }
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        TypeInfo rightType = resolveExprType(op.right, scope, func);
        if (isArrayType(rightType.baseType)) {
            // sugar for range(arr), using the array's compile-time length
            op.right = wrapFixedArrayAsRange(op.right);
            rightType = resolveExprType(op.right, scope, func);
        }
        // `u64 in string`: runtime byte scan up to the NUL (IN_SCAN)
        if (rightType.baseType.equals("string")) {
            if (!isPlainU64(leftType)) {
                throw new CompilerException("type", op.left.file, op.left.line,
                        "'in' against a string requires a plain 'u64' on the left, got '"
                                + leftType.canonical() + "'");
            }
            op.isStringMembershipCheck = true;
            return new TypeInfo(null, "indeterminate", "bool");
        }
        if (!isPlainU64(leftType) && !isRangeType(leftType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'in' requires a plain 'u64' or a range on the left, got '"
                            + leftType.canonical() + "'");
        }
        if (isUnsafeDynArrayType(rightType)) {
            // an unsafe dynarray has no length to bound against: dedicated message
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'in'/'into' cannot be used on an 'unsafe dynarray' -- it has no length to bound-check "
                            + "against; index it directly inside 'unsafe' code instead");
        }
        if (!isRangeType(rightType) && !isDynArrayType(rightType)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'in' requires a range or a dynarray on the right, got '"
                            + rightType.canonical() + "'");
        }
        return new TypeInfo(null, "indeterminate", "bool");
    }

    /** `u64 in EnumName`: the enum must be "guaranteed" (every variant has an explicit discriminant). See docs/TypeChecker.md, "Ranges and membership operators". */
    public TypeInfo checkGuaranteedEnumMembership(Token op, Scope scope, FuncInfo func) {
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        if (!isPlainU64(leftType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'in' against a guaranteed enum requires a plain 'u64' on the left, got '"
                            + leftType.canonical() + "'");
        }
        String enumName = op.right.text;
        EnumInfo enumInfo = enums.get(enumName);
        if (enumInfo.explicitValues == null) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'" + enumName + "' is not a guaranteed enum -- 'u64 in " + enumName + "' requires "
                            + "every one of its variants to carry an explicit discriminant value (e.g. '"
                            + enumInfo.variants.get(0) + ": 0'), not just some of them");
        }
        op.right.resolvedType = enumName;
        op.isGuaranteedEnumMembershipCheck = true;
        return new TypeInfo(null, "indeterminate", "bool");
    }

    /** `within`: like `in` but range on both sides; strict proper subrange (IN-style typing, WITHIN opcode). See docs/TypeChecker.md, "Ranges and membership operators". */
    public TypeInfo checkWithin(Token op, Scope scope, FuncInfo func) {
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        TypeInfo rightType = resolveExprType(op.right, scope, func);
        if (!isRangeType(leftType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'within' requires a range on the left, got '"
                            + leftType.canonical() + "'");
        }
        if (!isRangeType(rightType)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'within' requires a range on the right, got '"
                            + rightType.canonical() + "'");
        }
        return new TypeInfo(null, "indeterminate", "bool");
    }

    /** Array base types are "elem[len]" (nestable); the length is part of the type. */
    public boolean isArrayType(String baseType) {
        return baseType.endsWith("]") && baseType.contains("[");
    }

    /** True for a storage-less struct-typed value (the Return Value Optimization shape). */
    public boolean isStructByValueReturn(TypeInfo t) {
        return t.storage == null && structs.containsKey(t.baseType);
    }

    /** Everything before the last top-level '[' -- the element type, one dimension down for a nested array. */
    public String arrayElementType(String baseType) {
        return baseType.substring(0, baseType.lastIndexOf('['));
    }

    public int arrayLength(String baseType) {
        int idx = baseType.lastIndexOf('[');
        return Integer.parseInt(baseType.substring(idx + 1, baseType.length() - 1));
    }


    /** True for any type with a storage modifier. */
    public boolean isPointerType(TypeInfo t) {
        return t.storage != null;
    }

    /** Argument matching: like typesCompatible, but a `some` parameter also accepts a not-`some` argument proven alive by `match Some`. See docs/TypeChecker.md, "Type compatibility". */
    public boolean argSatisfiesParam(TypeInfo paramType, TypeInfo argType, Token argExpr, Scope scope) {
        if (typesCompatible(paramType, argType)) {
            return true;
        }
        if (argExpr == null || !paramType.isSome || argType.isSome) {
            return false;
        }
        TypeInfo relaxed = argType.withSome(true);
        return typesCompatible(paramType, relaxed) && isProvenAlive(argExpr, scope);
    }

    /**
     * Integer tightening: in a binary operator, an integer literal on one side adapts to the other
     * side's concrete integer type when its value fits (`a + 1` with `a: u8`). Returns the operand types
     * to use afterwards (unchanged when nothing adapted); the literal's expression is retagged.
     */
    public TypeInfo[] unifyLiteralOperands(Token op, TypeInfo leftType, TypeInfo rightType) {
        // operand mutability never matters for arithmetic, so test adaptation with the slot's own mutability
        if ((rightType.literalValue != null || rightType.floatLiteral) && leftType.literalValue == null && !leftType.floatLiteral && literalAdapts(leftType, rightType.withMutability(leftType.mutability))) {
            adaptLiteral(op.right, leftType);
            rightType = leftType;
        } else if ((leftType.literalValue != null || leftType.floatLiteral) && rightType.literalValue == null && !rightType.floatLiteral && literalAdapts(rightType, leftType.withMutability(rightType.mutability))) {
            adaptLiteral(op.left, rightType);
            leftType = rightType;
        }
        return new TypeInfo[]{leftType, rightType};
    }

    /** After an overload is chosen: retag every integer-literal argument that adapted to its parameter's type. */
    public void retagLiteralArgs(List<TypeInfo> paramTypes, List<TypeInfo> argTypes, List<Token> argNodes) {
        for (int i = 0; i < argTypes.size() && i < paramTypes.size(); i++) {
            TypeInfo a = argTypes.get(i);
            markRefConversion(paramTypes.get(i), a, argNodes.get(i));
            if ((a.literalValue != null || a.literalElements != null || a.floatLiteral) && literalAdapts(paramTypes.get(i), a) && argNodes.get(i) != null) {
                adaptLiteral(argNodes.get(i), paramTypes.get(i));
            }
        }
    }

    /**
     * Overload preference: a candidate the arguments fit WITHOUT any literal adapting comes before one that
     * needs a literal to adapt, so `f(5)` with `f(u64)` and `f(u8)` picks `f(u64)`. Stable otherwise.
     */
    public List<FuncInfo> orderByLiteralExactness(List<FuncInfo> candidates, List<TypeInfo> argTypes) {
        List<FuncInfo> exact = new ArrayList<>();
        List<FuncInfo> rest = new ArrayList<>();
        for (FuncInfo c : candidates) {
            boolean needsAdapt = false;
            if (c.paramTypes.size() == argTypes.size()) {
                for (int i = 0; i < argTypes.size(); i++) {
                    TypeInfo a = argTypes.get(i);
                    if ((a.literalValue != null || a.literalElements != null || a.floatLiteral) && !c.paramTypes.get(i).baseType.equals(a.baseType)) {
                        needsAdapt = true;
                        break;
                    }
                }
            }
            (needsAdapt ? rest : exact).add(c);
        }
        exact.addAll(rest);
        return exact;
    }

    /**
     * Integer tightening, part 1: an integer literal (or negated literal) satisfies any plain integer
     * slot whose type holds its value, with a mutability the literal's own mutability satisfies -- a
     * literal has no width of its own, so the slot decides. The caller must follow a successful match
     * with `adaptLiteral` on the literal's expression so its node is retagged with the slot's type.
     */
    public boolean literalAdapts(TypeInfo expected, TypeInfo actual) {
        if (actual.literalElements != null) {
            return arrayLiteralAdapts(expected, actual);
        }
        if (actual.floatArrayLiteral) {
            return floatArrayLiteralAdapts(expected, actual);
        }
        if (actual.floatLiteral) {
            return actual.storage == null
                    && expected.storage == null
                    && !expected.isSome
                    && isFloatType(expected.baseType)
                    && mutabilityCompatible(expected.mutability, actual.mutability);
        }
        return actual.literalValue != null
                && actual.storage == null
                && expected.storage == null
                && !expected.isSome
                && INTEGER_WIDTH.containsKey(expected.baseType)
                && literalFits(actual.literalValue, expected.baseType)
                && mutabilityCompatible(expected.mutability, actual.mutability);
    }

    /** An all-integer-literal array literal satisfies a same-length `T[n]` slot (T a plain integer type) that holds every element. */
    public boolean arrayLiteralAdapts(TypeInfo expected, TypeInfo actual) {
        if (expected.storage != null || expected.isSome || !mutabilityCompatible(expected.mutability, actual.mutability)) {
            return false;
        }
        String bt = expected.baseType;
        int open = bt.indexOf('[');
        if (open <= 0 || !bt.endsWith("]")) {
            return false;
        }
        String elem = bt.substring(0, open);
        if (!INTEGER_WIDTH.containsKey(elem)) {
            return false;
        }
        // the dimensions ("[3][2]") must match exactly; the literal's flattened values must all fit the element type
        int aopen = actual.baseType.indexOf('[');
        if (aopen <= 0 || !bt.substring(open).equals(actual.baseType.substring(aopen))) {
            return false;
        }
        for (java.math.BigInteger v : actual.literalElements) {
            if (!literalFits(v, elem)) {
                return false;
            }
        }
        return true;
    }

    /** An all-float-literal array literal (possibly nested) satisfies a same-shape `f32[n]..`/`f64[n]..` slot. */
    public boolean floatArrayLiteralAdapts(TypeInfo expected, TypeInfo actual) {
        if (expected.storage != null || expected.isSome || !mutabilityCompatible(expected.mutability, actual.mutability)) {
            return false;
        }
        String bt = expected.baseType;
        int open = bt.indexOf('[');
        if (open <= 0 || !bt.endsWith("]") || !isFloatType(bt.substring(0, open))) {
            return false;
        }
        int aopen = actual.baseType.indexOf('[');
        return aopen > 0 && bt.substring(open).equals(actual.baseType.substring(aopen));
    }

    /**
     * Retags an integer-literal expression (through `mut`/`imut` wrappers and one unary minus) with the
     * type of the slot it was just accepted into, so the emitted `PUSH`/`NEG` carry the slot's width and
     * signedness instead of the literal's `u64`/`s64` default. Safe to call on an expression whose type
     * was already the slot's type. A no-op unless `actualType.literalValue` was set.
     */
    public void adaptLiteral(Token expr, TypeInfo expected) {
        if (expr == null || expr.resolvedType == null) {
            return;
        }
        String mutPrefix = expr.resolvedType.substring(0, expr.resolvedType.indexOf('_'));
        if (expr.type == TokenType.OPERATOR && (expr.text.equals("mut") || expr.text.equals("imut"))) {
            adaptLiteral(expr.left, expected);
            expr.resolvedType = mutPrefix + "_" + expected.baseType;
            return;
        }
        if (expr.type == TokenType.DELINEATOR && expr.text.equals("(") && expr.childs.size() == 1) {
            // grouping parentheses are transparent
            adaptLiteral(expr.childs.get(0), expected);
            expr.resolvedType = mutPrefix + "_" + expected.baseType;
            return;
        }
        if (expr.type == TokenType.OPERATOR && expr.text.equals("-") && expr.unary) {
            expr.resolvedType = mutPrefix + "_" + expected.baseType;
            // the operand of the NEG is the same-width UNSIGNED value (NEG's left/return types differ that way);
            // a float literal's operand is just the same float type.
            if (expr.left != null && expr.left.resolvedType != null) {
                String inner = expr.left.resolvedType.substring(0, expr.left.resolvedType.indexOf('_'));
                if (isFloatType(expected.baseType)) {
                    // retag the whole operand tree (`-(3.7 * 0.3)`, `-(0.5 + 0.25)`), not just its root
                    adaptLiteral(expr.left, expected);
                    expr.left.resolvedType = inner + "_" + expected.baseType;
                } else {
                    expr.left.resolvedType = inner + "_" + unsignedCounterpart(expected.baseType);
                }
            }
            return;
        }
        if (expr.type == TokenType.INTEGER || expr.type == TokenType.FLOAT) {
            expr.resolvedType = mutPrefix + "_" + expected.baseType;
        }
        if (isFloatType(expected.baseType) && expr.type == TokenType.OPERATOR && !expr.unary && expr.left != null && expr.right != null
                && (expr.text.equals("+") || expr.text.equals("-") || expr.text.equals("*") || expr.text.equals("/"))) {
            // an all-literal float expression: retag the whole tree so every operation runs at the target width
            expr.resolvedType = mutPrefix + "_" + expected.baseType;
            adaptLiteral(expr.left, expected);
            adaptLiteral(expr.right, expected);
        }
        if (expr.type == TokenType.DELINEATOR && expr.text.equals("[") && expected.baseType.indexOf('[') > 0
                && !expr.childs.isEmpty()) {
            String elem = expected.baseType.substring(0, expected.baseType.lastIndexOf('['));   // one dimension fewer ("u32[3][2]" -> "u32[3]")
            List<Token> elements = new ArrayList<>();
            collectCommaArgs(expr.childs.get(0), elements);
            for (Token e : elements) {
                adaptLiteral(e, new TypeInfo(null, "indeterminate", elem));
            }
            expr.resolvedType = mutPrefix + "_" + expected.baseType;
        }
    }

    /**
     * A nullable `ref` (storage `ref`, not `some`) is stored as a 64-bit id; a `ref some`, a proven binding and an `owns` value are addresses.
     * Where an address-valued expression is accepted by a nullable `ref` slot the emitter converts it (GT_REF_ID); where a nullable `ref`
     * is accepted by a `raw` slot it is resolved to its current address (GT_REF_RESOLVE; null when dead). Called only for the finally chosen
     * parameter/target, never for a rejected overload candidate.
     */
    public void markRefConversion(TypeInfo expected, TypeInfo actual, Token expr) {
        if (expr == null || expected == null || actual == null || actual.storage == null || expected.storage == null) {
            return;
        }
        boolean actualNullableRef = "ref".equals(actual.storage) && !actual.isSome;
        if ("ref".equals(expected.storage) && !expected.isSome) {
            if (!actualNullableRef && ("ref".equals(actual.storage) || "owns".equals(actual.storage))) {
                expr.refToId = true;
            }
        } else if ("raw".equals(expected.storage) && actualNullableRef) {
            expr.refToRaw = true;
        }
    }

    /** `typesCompatible`, plus the retagging `adaptLiteral` a literal that adapted needs. Use wherever the argument expression is in hand. */
    public boolean acceptsAdaptingLiteral(TypeInfo expected, TypeInfo actual, Token expr) {
        if (!typesCompatible(expected, actual)) {
            return false;
        }
        markRefConversion(expected, actual, expr);
        if ((actual.literalValue != null || actual.literalElements != null || actual.floatLiteral) && literalAdapts(expected, actual)) {
            adaptLiteral(expr, expected);
        }
        return true;
    }

    /** Directional: may `actual` be used where `expected` is required. See docs/TypeChecker.md, "Type compatibility". */
    public boolean typesCompatible(TypeInfo expected, TypeInfo actual) {
        if (literalAdapts(expected, actual)) {
            return true;
        }
        if (actual.baseType.equals("null") && isPointerType(expected)) {
            // null satisfies any pointer slot except a `some` one
            return !expected.isSome;
        }
        // a complete range(a,b) satisfies a bare `range` slot, by value too (not the reverse)
        if (expected.baseType.equals("range") && isRangeType(actual) && isCompleteRange(actual)) {
            if (!mutabilityCompatible(expected.mutability, actual.mutability)) {
                return false;
            }
            if (expected.storage == null) {
                return true;
            }
            if (actual.storage == null) {
                return false;
            }
            return storageCompatible(expected.storage, actual.storage);
        }
        // a struct satisfies an interface-typed slot it implements (one direction only)
        if (interfaces.containsKey(expected.baseType) && structs.containsKey(actual.baseType)
                && structImplementsInterface(actual.baseType, expected.baseType)) {
            if (!mutabilityCompatible(expected.mutability, actual.mutability)) {
                return false;
            }
            if (expected.storage == null) {
                return true;
            }
            if (actual.storage == null) {
                return false;
            }
            if (expected.isSome && !actual.isSome) {
                return false;
            }
            return storageCompatible(expected.storage, actual.storage);
        }
        if (!expected.baseType.equals(actual.baseType)) {
            return false;
        }
        // the actual funcptr must carry every decorator the expected one requires
        if (expected.funcPtrParamTypes != null && !actual.funcPtrDecorators.containsAll(expected.funcPtrDecorators)) {
            return false;
        }
        // nominal alias: 'u8' and an alias of it must not satisfy each other
        if (!java.util.Objects.equals(expected.aliasName, actual.aliasName)) {
            return false;
        }
        if (!mutabilityCompatible(expected.mutability, actual.mutability)) {
            return false;
        }
        if (expected.storage == null) {
            return true;
        }
        if (actual.storage == null) {
            return false;
        }
        if (expected.isSome && !actual.isSome) {
            // `some` coerces to non-`some`, not the reverse
            return false;
        }
        return storageCompatible(expected.storage, actual.storage);
    }

    /** indeterminate -> mut/imut, mut -> imut; nothing coerces into indeterminate or imut -> mut. */
    public boolean mutabilityCompatible(String expected, String actual) {
        if (expected == null || actual == null) {
            // only the "null" literal type has a null mutability (handled earlier)
            return true;
        }
        if (expected.equals(actual)) {
            return true;
        }
        if (expected.equals("indeterminate")) {
            // only indeterminate itself coerces into indeterminate
            return false;
        }
        if (actual.equals("indeterminate")) {
            return true;
        }
        return actual.equals("mut") && expected.equals("imut");
    }

    public boolean storageCompatible(String expectedStorage, String actualStorage) {
        if (expectedStorage.equals(actualStorage)) {
            return true;
        }
        if (expectedStorage.equals("raw")) {
            return true;
        }
        return expectedStorage.equals("ref") && actualStorage.equals("owns");
    }

    /** The type of `ptr +/- n`: the same raw pointer type, with indeterminate mutability (a fresh value). */
    public TypeInfo pointerResultType(TypeInfo ptr) {
        return ptr.rawPointeeType != null
                ? TypeInfo.rawPointer("indeterminate", ptr.rawPointeeType)
                : new TypeInfo("raw", "indeterminate", ptr.baseType);
    }

    /** Pointee size of a 'raw' pointer for C-style arithmetic (void and unknown sizes step by one byte). */
    public long pointeeSize(TypeInfo ptr, Token at) {
        TypeInfo pointee = ptr.rawPointeeType != null ? ptr.rawPointeeType : new TypeInfo(null, "imut", ptr.baseType);
        if (pointee.storage != null) {
            return POINTER_SIZE;
        }
        if (pointee.baseType.equals("void")) {
            return 1;
        }
        return computeSizeOf(pointee, at);
    }

    /** The struct a raw pointer points to (through any array dimensions), or null: its scale is symbolic (`SIZEOF name`), see Token.pointerScaleStruct. */
    public String pointeeStructName(TypeInfo ptr) {
        TypeInfo pointee = ptr.rawPointeeType != null ? ptr.rawPointeeType : new TypeInfo(null, "imut", ptr.baseType);
        return sizedStructName(pointee);
    }

    /** The integer half of pointer +/- integer: an 8-byte integer (u64/s64), so it can be added straight to the 8-byte address; narrower integers need an explicit 'as u64' first. */
    public void requireEightByteInteger(TypeInfo t, Token at) {
        if (!t.baseType.equals("u64") && !t.baseType.equals("s64")) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + at.text + "' on a 'raw' pointer requires a 'u64' or 's64' integer on the other side, got '"
                            + t.canonical() + "' (widen it with 'as u64' first)");
        }
    }

    public void requireNumeric(TypeInfo t, Token at) {
        if (!NUMERIC_TYPES.contains(t.baseType)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + at.text + "' requires a numeric type, got '" + t.canonical() + "'");
        }
    }

    public void requireBoolOrInteger(TypeInfo t, Token at) {
        if (!t.baseType.equals("bool") && !INTEGER_TYPES.contains(t.baseType)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + at.text + "' requires a bool or integer type, got '" + t.canonical() + "'");
        }
    }

    public TypeChecker(CompilerConfig config) {
        this.config = config;
        ProofKills.reset();
    }
}

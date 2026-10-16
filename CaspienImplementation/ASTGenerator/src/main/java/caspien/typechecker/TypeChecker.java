package caspien.typechecker;
import caspien.config.CompilerConfig;
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
import java.util.Deque;
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

public final class TypeChecker {
    public final TypeCheckerModel model = new TypeCheckerModel(this);
    public final TypeCheckerDeclarations declarations = new TypeCheckerDeclarations(this);
    public final TypeCheckerImpls impls = new TypeCheckerImpls(this);
    public final TypeCheckerSignatures signatures = new TypeCheckerSignatures(this);
    public final TypeCheckerStatements statements = new TypeCheckerStatements(this);
    public final TypeCheckerControlFlow controlFlow = new TypeCheckerControlFlow(this);
    public final TypeCheckerExpressions expressions = new TypeCheckerExpressions(this);
    public final TypeCheckerCalls calls = new TypeCheckerCalls(this);
    public final TypeCheckerLocksAndProofs locksAndProofs = new TypeCheckerLocksAndProofs(this);
    public final TypeCheckerAccessAndAsync accessAndAsync = new TypeCheckerAccessAndAsync(this);
    public final CheckDriver driver = new CheckDriver(this);

    /** The public entry point; the checker's state is public through the part fields (`model` holds most of it). */
    public void check(List<Token> rootLines) { }

    public TypeInfo checkAddressOf(Token op, Scope scope, FuncInfo func) { return null; }

    public int stepAddressOf(CheckFrame f) { return 0; }

    private TypeInfo addressOfHead(Token op, Scope scope, FuncInfo func) { return null; }

    private TypeInfo addressOfTail(Token op, Scope scope, FuncInfo func, TypeInfo operandType) { return null; }


    /** True for a VARREF, or a '.' chain of member accesses rooted in one -- the things 'auto' can validly point to. */
    public boolean isAddressableLvalue(Token node) { return false; }

    /**
     * Weaker than isAddressableLvalue: also allows LOOKUP anywhere in the chain ('arr[i].f', 'm[i][j]'). Used only by checkReturn's struct-RVO restriction.
     * See docs/TypeChecker.md, "Address-of operators".
     */
    public boolean isReadableValueChain(Token node) { return false; }

    /** Prefix `mut`/`imut`: forces concrete mutability; only imut -> mut is rejected. See docs/TypeChecker.md, "Address-of operators". */
    public int stepMutabilityOf(CheckFrame f) { return 0; }

    /** `a..b`: a range type whose base-type string embeds its bounds; both operands u64, each a bare literal or variable. See docs/TypeChecker.md, "Ranges and membership operators". */
    public int stepRange(CheckFrame f) { return 0; }

    public boolean isPlainU64(TypeInfo t) { return false; }

    public String rangeBoundDescriptor(Token boundExpr, TypeInfo boundType) { return null; }

    /** True for "dynarray(T)" (not the "unsafe dynarray(T)" variant, see isUnsafeDynArrayType). */
    public boolean isDynArrayType(TypeInfo t) { return false; }

    /** True for "unsafe dynarray(T)"; never true together with isDynArrayType. */
    public boolean isUnsafeDynArrayType(TypeInfo t) { return false; }

    /** True for any range type, complete or incomplete (bare "range" behind a pointer). */
    public boolean isRangeType(TypeInfo t) { return false; }

    /** True only for a fully-specified range -- false for a bare, incomplete "range" pointer. */
    public boolean isCompleteRange(TypeInfo t) { return false; }

    /** Builds the synthesized call "range(arr)" around an already-parsed fixed-array expression (same CALL shape the parser produces for a written-out `range(arr)`). */
    public Token wrapFixedArrayAsRange(Token arr) { return null; }

    /** `x in R`: u64 or range on the left; range, dynarray, fixed array, string or guaranteed enum on the right. See docs/TypeChecker.md, "Ranges and membership operators". */
    public TypeInfo checkIn(Token op, Scope scope, FuncInfo func) { return null; }

    /** Phases: 0 start, 1 left done, 2 right done, 3 right re-resolved after a fixed array was wrapped as a range. */
    public int stepIn(CheckFrame f) { return 0; }

    private TypeInfo inType(Token op, TypeInfo leftType, TypeInfo rightType) { return null; }


    /** `u64 in EnumName`: the enum must be "guaranteed" (every variant has an explicit discriminant). See docs/TypeChecker.md, "Ranges and membership operators". */
    public TypeInfo checkGuaranteedEnumMembership(Token op, Scope scope, FuncInfo func) { return null; }

    public int stepGuaranteedEnumMembership(CheckFrame f) { return 0; }

    private TypeInfo enumMembershipTail(Token op, Scope scope, FuncInfo func, TypeInfo leftType) { return null; }


    /** `within`: like `in` but range on both sides; strict proper subrange (IN-style typing, WITHIN opcode). See docs/TypeChecker.md, "Ranges and membership operators". */
    public TypeInfo checkWithin(Token op, Scope scope, FuncInfo func) { return null; }

    public int stepWithin(CheckFrame f) { return 0; }

    private TypeInfo withinType(Token op, TypeInfo leftType, TypeInfo rightType) { return null; }


    /** Array base types are "elem[len]" (nestable); the length is part of the type. */
    public boolean isArrayType(String baseType) { return false; }

    /** True for a storage-less struct-typed value (the Return Value Optimization shape). */
    public boolean isStructByValueReturn(TypeInfo t) { return false; }

    /** Everything before the last top-level '[' -- the element type, one dimension down for a nested array. */
    public String arrayElementType(String baseType) { return null; }

    public int arrayLength(String baseType) { return 0; }

    /** True for any type with a storage modifier. */
    public boolean isPointerType(TypeInfo t) { return false; }

    /** Argument matching: like typesCompatible, but a `some` parameter also accepts a not-`some` argument proven alive by `match Some`. See docs/TypeChecker.md, "Type compatibility". */
    public boolean argSatisfiesParam(TypeInfo paramType, TypeInfo argType, Token argExpr, Scope scope) { return false; }

    /**
     * Integer tightening: in a binary operator, an integer literal on one side adapts to the other
     * side's concrete integer type when its value fits (`a + 1` with `a: u8`). Returns the operand types
     * to use afterwards (unchanged when nothing adapted); the literal's expression is retagged.
     */
    public TypeInfo[] unifyLiteralOperands(Token op, TypeInfo leftType, TypeInfo rightType) { return null; }

    /** After an overload is chosen: retag every integer-literal argument that adapted to its parameter's type. */
    public void retagLiteralArgs(List<TypeInfo> paramTypes, List<TypeInfo> argTypes, List<Token> argNodes) { }

    /**
     * Overload preference: a candidate the arguments fit WITHOUT any literal adapting comes before one that
     * needs a literal to adapt, so `f(5)` with `f(u64)` and `f(u8)` picks `f(u64)`. Stable otherwise.
     */
    public List<FuncInfo> orderByLiteralExactness(List<FuncInfo> candidates, List<TypeInfo> argTypes) { return null; }

    /**
     * Integer tightening, part 1: an integer literal (or negated literal) satisfies any plain integer
     * slot whose type holds its value, with a mutability the literal's own mutability satisfies -- a
     * literal has no width of its own, so the slot decides. The caller must follow a successful match
     * with `adaptLiteral` on the literal's expression so its node is retagged with the slot's type.
     */
    public boolean literalAdapts(TypeInfo expected, TypeInfo actual) { return false; }

    /** An all-integer-literal array literal satisfies a same-length `T[n]` slot (T a plain integer type) that holds every element. */
    public boolean arrayLiteralAdapts(TypeInfo expected, TypeInfo actual) { return false; }

    /** An all-float-literal array literal (possibly nested) satisfies a same-shape `f32[n]..`/`f64[n]..` slot. */
    public boolean floatArrayLiteralAdapts(TypeInfo expected, TypeInfo actual) { return false; }

    /**
     * Retags an integer-literal expression (through `mut`/`imut` wrappers and one unary minus) with the
     * type of the slot it was just accepted into, so the emitted `PUSH`/`NEG` carry the slot's width and
     * signedness instead of the literal's `u64`/`s64` default. Safe to call on an expression whose type
     * was already the slot's type. A no-op unless `actualType.literalValue` was set.
     */
    public void adaptLiteral(Token root, TypeInfo rootExpected) { }

    /**
     * A nullable `ref` (storage `ref`, not `some`) is stored as a 64-bit id; a `ref some`, a proven binding and an `owns` value are addresses.
     * Where an address-valued expression is accepted by a nullable `ref` slot the emitter converts it (GT_REF_ID); where a nullable `ref`
     * is accepted by a `raw` slot it is resolved to its current address (GT_REF_RESOLVE; null when dead). Called only for the finally chosen
     * parameter/target, never for a rejected overload candidate.
     */
    public void markRefConversion(TypeInfo expected, TypeInfo actual, Token expr) { }

    /** `typesCompatible`, plus the retagging `adaptLiteral` a literal that adapted needs. Use wherever the argument expression is in hand. */
    public boolean acceptsAdaptingLiteral(TypeInfo expected, TypeInfo actual, Token expr) { return false; }

    /** Directional: may `actual` be used where `expected` is required. See docs/TypeChecker.md, "Type compatibility". */
    public boolean typesCompatible(TypeInfo expected, TypeInfo actual) { return false; }

    /** indeterminate -> mut/imut, mut -> imut; nothing coerces into indeterminate or imut -> mut. */
    public boolean mutabilityCompatible(String expected, String actual) { return false; }

    public boolean storageCompatible(String expectedStorage, String actualStorage) { return false; }

    /** The type of `ptr +/- n`: the same raw pointer type, with indeterminate mutability (a fresh value). */
    public TypeInfo pointerResultType(TypeInfo ptr) { return null; }

    /** Pointee size of a 'raw' pointer for C-style arithmetic (void and unknown sizes step by one byte). */
    public long pointeeSize(TypeInfo ptr, Token at) { return 0L; }

    /** The struct a raw pointer points to (through any array dimensions), or null: its scale is symbolic (`SIZEOF name`), see Token.pointerScaleStruct. */
    public String pointeeStructName(TypeInfo ptr) { return null; }

    /** The integer half of pointer +/- integer: an 8-byte integer (u64/s64), so it can be added straight to the 8-byte address; narrower integers need an explicit 'as u64' first. */
    public void requireEightByteInteger(TypeInfo t, Token at) { }

    public void requireNumeric(TypeInfo t, Token at) { }

    public void requireBoolOrInteger(TypeInfo t, Token at) { }

    public TypeChecker(CompilerConfig config) { }
}

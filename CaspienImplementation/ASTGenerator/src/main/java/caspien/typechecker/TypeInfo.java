package caspien.typechecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Package-visible so BytecodeEmitter can reuse resolved type info directly. */
public class TypeInfo {
    public final String storage;     // nullable
    public final String mutability;  // "mut"/"imut", or null for the "null" literal type
    public final String baseType;    // always the *real*, fully-resolved representation -- never an alias name
    /** Alias name used at this reference (nullable): a nominal tag for typesCompatible/sameSignature only; structural checks use baseType. */
    public final String aliasName;
    /** Function-pointer parts, kept as real objects (decorators are not in baseType; typesCompatible compares them as a subset). See docs/TypeChecker.md, "Type model". */
    public final List<TypeInfo> funcPtrParamTypes;
    public final TypeInfo funcPtrReturnType;
    public final Set<String> funcPtrDecorators;
    /** Resolved calling convention of a function-pointer type; INVOKE reads it from here. Null on every other TypeInfo. */
    public final String funcPtrCallConvention;
    /** Set only for a safe "dynarray(T)": element type kept as a field instead of re-parsing baseType. See docs/TypeChecker.md, "Type model". */
    public final TypeInfo dynArrayElementType;
    /** Set only for "unsafe dynarray(T)"; a separate field so isDynArrayType() excludes it. See docs/TypeChecker.md, "Type model". */
    public final TypeInfo unsafeDynArrayElementType;
    /** Set only for a fixed array built from a real value (checkArrayLiteral): the element type with its storage, for checkLookup. See docs/TypeChecker.md, "Type model". */
    public final TypeInfo arrayElementType;
    /** Set only for a "raw" pointer whose target is itself a full type ("raw mut raw mut char"). See docs/TypeChecker.md, "Type model". */
    public final TypeInfo rawPointeeType;
    /** True when annotated "some" (non-null); meaningful with owns/ref/raw/static storage. Set via withSome(). */
    public final boolean isSome;
    /** Subset of {finite, infinite, nan} for an f32/f64; null = unconstrained. A TreeSet so the canonical form is alphabetical. See docs/TypeChecker.md, "Type model". */
    public final java.util.TreeSet<String> floatStates;
    /** Parameter/struct-member "const"; not part of baseType or canonical() (checked at assignment sites only). */
    public final boolean isConst;
    /** Exact value of an integer literal (or negated/mut-wrapped one), else null; lets the literal adapt to a narrower or signed slot. Not part of type identity. See docs/TypeChecker.md, "Type model". */
    public java.math.BigInteger literalValue = null;
    /** True on a bare float literal: adapts to an f32 or f64 slot, f32 when unconstrained. */
    public boolean floatLiteral = false;
    /** An array literal whose elements are all float literals: adapts to an `f32[n]`/`f64[n]` slot as a whole. */
    public boolean floatArrayLiteral = false;
    /** On an array literal whose elements are all integer literals: their values, so the literal can adapt to a `T[n]` slot. */
    public java.util.List<java.math.BigInteger> literalElements = null;
    /** True for a type built by the "atomic" prefix (checkAtomicWrapper); embedded in canonical() but ignored by type compatibility. */
    public final boolean isAtomic;

    public TypeInfo(String storage, String mutability, String baseType) { }

    public TypeInfo(String storage, String mutability, String baseType, String aliasName) { }

    /** Used only to construct a function-pointer type -- see the field comment above. */
    public static TypeInfo funcPointer(String mutability, List<TypeInfo> paramTypes, TypeInfo returnType,
            Set<String> decorators, String callConvention) { return null; }

    /** Element text embedded in a dynarray baseType: the element's storage keyword (if any) plus base type, never mutability. See docs/TypeChecker.md, "Type model". */
    public static String dynArrayElementText(TypeInfo elementType) { return null; }

    /** Storage keywords `dynArrayElementText` ever prepends -- kept as one list so parsing it back apart (below) can never drift out of sync with producing it. */
    public static final String[] DYN_ARRAY_ELEMENT_STORAGE_KEYWORDS = {"owns", "ref", "raw", "static", "auto"};

    /** Inverse of dynArrayElementText, for dynarray element text that is only available as a baseType string. */
    public static TypeInfo parseDynArrayElementText(String text) { return null; }

    /** Builds a "dynarray(T)" type; always owns storage. */
    public static TypeInfo dynArray(String mutability, TypeInfo elementType) { return null; }

    /** Builds a fixed-array literal type "elemType[N]"; baseType embeds only the element base type. */
    public static TypeInfo array(TypeInfo elementType, int length) { return null; }

    /** Builds a recursive "raw" pointer type; baseType embeds the pointee's full canonical() (its mutability is part of the identity). */
    public static TypeInfo rawPointer(String mutability, TypeInfo pointeeType) { return null; }

    /** Builds an "unsafe dynarray(T)" type; its distinct baseType keeps it non-coercible with the safe dynarray. */
    public static TypeInfo unsafeDynArray(String mutability, TypeInfo elementType) { return null; }

    public TypeInfo(String storage, String mutability, String baseType, String aliasName,
            List<TypeInfo> funcPtrParamTypes, TypeInfo funcPtrReturnType, Set<String> funcPtrDecorators,
            String funcPtrCallConvention, TypeInfo dynArrayElementType, TypeInfo rawPointeeType) { }

    public TypeInfo(String storage, String mutability, String baseType, String aliasName,
            List<TypeInfo> funcPtrParamTypes, TypeInfo funcPtrReturnType, Set<String> funcPtrDecorators,
            String funcPtrCallConvention, TypeInfo dynArrayElementType, TypeInfo arrayElementType,
            TypeInfo rawPointeeType) { }

    public TypeInfo(String storage, String mutability, String baseType, String aliasName,
            List<TypeInfo> funcPtrParamTypes, TypeInfo funcPtrReturnType, Set<String> funcPtrDecorators,
            String funcPtrCallConvention, TypeInfo dynArrayElementType, TypeInfo arrayElementType,
            TypeInfo rawPointeeType, boolean isSome, java.util.TreeSet<String> floatStates, boolean isConst) { }

    /** The full constructor all others delegate to. */
    public TypeInfo(String storage, String mutability, String baseType, String aliasName,
            List<TypeInfo> funcPtrParamTypes, TypeInfo funcPtrReturnType, Set<String> funcPtrDecorators,
            String funcPtrCallConvention, TypeInfo dynArrayElementType, TypeInfo unsafeDynArrayElementType,
            TypeInfo arrayElementType, TypeInfo rawPointeeType, boolean isSome,
            java.util.TreeSet<String> floatStates, boolean isConst, boolean isAtomic) { }

    /** Copy changing only isSome; copies go through here so no field is dropped. */
    public TypeInfo withSome(boolean newIsSome) { return null; }

    /** The `isConst` counterpart of withSome just above -- identical reasoning and shape. */
    public TypeInfo withConst(boolean newIsConst) { return null; }

    /** The `floatStates` counterpart of withSome just above -- identical reasoning and shape. */
    public TypeInfo withFloatStates(java.util.TreeSet<String> newFloatStates) { return null; }

    /** Copy changing only isAtomic. */
    public TypeInfo withAtomic(boolean newIsAtomic) { return null; }

    /** Canonical type string: storage_some_mutability_atomic_states_@decorators_baseType; aliases never appear. See docs/TypeChecker.md, "Type model". */
    public String canonical() { return null; }

    public boolean sameBaseType(TypeInfo other) { return false; }

    /** Copy with a different mutability, every other field kept (the one place "mut X"/"imut X" wrapping is implemented). */
    public TypeInfo withMutability(String newMutability) { return null; }

    /** Copy with a different storage keyword, every other field kept (checkAddressOf). */
    public TypeInfo withStorage(String newStorage) { return null; }
}

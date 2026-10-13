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

    public TypeInfo(String storage, String mutability, String baseType) {
        this(storage, mutability, baseType, null);
    }

    public TypeInfo(String storage, String mutability, String baseType, String aliasName) {
        this.storage = storage;
        this.mutability = mutability;
        this.baseType = baseType;
        this.aliasName = aliasName;
        this.funcPtrParamTypes = null;
        this.funcPtrReturnType = null;
        this.funcPtrDecorators = null;
        this.funcPtrCallConvention = null;
        this.dynArrayElementType = null;
        this.unsafeDynArrayElementType = null;
        this.arrayElementType = null;
        this.rawPointeeType = null;
        this.isSome = false;
        this.floatStates = null;
        this.isConst = false;
        this.isAtomic = false;
    }

    /** Used only to construct a function-pointer type -- see the field comment above. */
    public static TypeInfo funcPointer(String mutability, List<TypeInfo> paramTypes, TypeInfo returnType,
            Set<String> decorators, String callConvention) {
        StringBuilder sb = new StringBuilder("func(");
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(paramTypes.get(i).canonical());
        }
        sb.append(')').append(returnType.canonical());
        return new TypeInfo("static", mutability, sb.toString(), null,
                paramTypes, returnType, decorators, callConvention, null, null);
    }

    /** Element text embedded in a dynarray baseType: the element's storage keyword (if any) plus base type, never mutability. See docs/TypeChecker.md, "Type model". */
    public static String dynArrayElementText(TypeInfo elementType) {
        return elementType.storage != null ? elementType.storage + "_" + elementType.baseType
                : elementType.baseType;
    }

    /** Storage keywords `dynArrayElementText` ever prepends -- kept as one list so parsing it back apart (below) can never drift out of sync with producing it. */
    public static final String[] DYN_ARRAY_ELEMENT_STORAGE_KEYWORDS = {"owns", "ref", "raw", "static", "auto"};

    /** Inverse of dynArrayElementText, for dynarray element text that is only available as a baseType string. */
    public static TypeInfo parseDynArrayElementText(String text) {
        for (String storage : DYN_ARRAY_ELEMENT_STORAGE_KEYWORDS) {
            if (text.startsWith(storage + "_")) {
                return new TypeInfo(storage, "indeterminate", text.substring(storage.length() + 1));
            }
        }
        return new TypeInfo(null, "indeterminate", text);
    }

    /** Builds a "dynarray(T)" type; always owns storage. */
    public static TypeInfo dynArray(String mutability, TypeInfo elementType) {
        String baseType = "dynarray(" + dynArrayElementText(elementType) + ")";
        return new TypeInfo("owns", mutability, baseType, null,
                null, null, null, null, elementType, null);
    }

    /** Builds a fixed-array literal type "elemType[N]"; baseType embeds only the element base type. */
    public static TypeInfo array(TypeInfo elementType, int length) {
        String baseType = elementType.baseType + "[" + length + "]";
        return new TypeInfo(null, "indeterminate", baseType, null,
                null, null, null, null, null, elementType, null);
    }

    /** Builds a recursive "raw" pointer type; baseType embeds the pointee's full canonical() (its mutability is part of the identity). */
    public static TypeInfo rawPointer(String mutability, TypeInfo pointeeType) {
        return new TypeInfo("raw", mutability, pointeeType.canonical(), null,
                null, null, null, null, null, null, pointeeType);
    }

    /** Builds an "unsafe dynarray(T)" type; its distinct baseType keeps it non-coercible with the safe dynarray. */
    public static TypeInfo unsafeDynArray(String mutability, TypeInfo elementType) {
        String baseType = "unsafe_dynarray(" + dynArrayElementText(elementType) + ")";
        return new TypeInfo("owns", mutability, baseType, null,
                null, null, null, null, null, elementType, null, null, false, null, false, false);
    }

    public TypeInfo(String storage, String mutability, String baseType, String aliasName,
            List<TypeInfo> funcPtrParamTypes, TypeInfo funcPtrReturnType, Set<String> funcPtrDecorators,
            String funcPtrCallConvention, TypeInfo dynArrayElementType, TypeInfo rawPointeeType) {
        this(storage, mutability, baseType, aliasName, funcPtrParamTypes,
                funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention, dynArrayElementType, null,
                rawPointeeType, false, null, false);
    }

    public TypeInfo(String storage, String mutability, String baseType, String aliasName,
            List<TypeInfo> funcPtrParamTypes, TypeInfo funcPtrReturnType, Set<String> funcPtrDecorators,
            String funcPtrCallConvention, TypeInfo dynArrayElementType, TypeInfo arrayElementType,
            TypeInfo rawPointeeType) {
        this(storage, mutability, baseType, aliasName, funcPtrParamTypes,
                funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention, dynArrayElementType,
                arrayElementType, rawPointeeType, false, null, false);
    }

    public TypeInfo(String storage, String mutability, String baseType, String aliasName,
            List<TypeInfo> funcPtrParamTypes, TypeInfo funcPtrReturnType, Set<String> funcPtrDecorators,
            String funcPtrCallConvention, TypeInfo dynArrayElementType, TypeInfo arrayElementType,
            TypeInfo rawPointeeType, boolean isSome, java.util.TreeSet<String> floatStates, boolean isConst) {
        this(storage, mutability, baseType, aliasName, funcPtrParamTypes,
                funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention, dynArrayElementType,
                null, arrayElementType, rawPointeeType, isSome, floatStates, isConst, false);
    }

    /** The full constructor all others delegate to. */
    public TypeInfo(String storage, String mutability, String baseType, String aliasName,
            List<TypeInfo> funcPtrParamTypes, TypeInfo funcPtrReturnType, Set<String> funcPtrDecorators,
            String funcPtrCallConvention, TypeInfo dynArrayElementType, TypeInfo unsafeDynArrayElementType,
            TypeInfo arrayElementType, TypeInfo rawPointeeType, boolean isSome,
            java.util.TreeSet<String> floatStates, boolean isConst, boolean isAtomic) {
        this.storage = storage;
        this.mutability = mutability;
        this.baseType = baseType;
        this.aliasName = aliasName;
        this.funcPtrParamTypes = funcPtrParamTypes;
        this.funcPtrReturnType = funcPtrReturnType;
        this.funcPtrDecorators = funcPtrDecorators;
        this.funcPtrCallConvention = funcPtrCallConvention;
        this.dynArrayElementType = dynArrayElementType;
        this.unsafeDynArrayElementType = unsafeDynArrayElementType;
        this.arrayElementType = arrayElementType;
        this.rawPointeeType = rawPointeeType;
        this.isSome = isSome;
        this.floatStates = floatStates;
        this.isAtomic = isAtomic;
        this.isConst = isConst;
    }

    /** Copy changing only isSome; copies go through here so no field is dropped. */
    public TypeInfo withSome(boolean newIsSome) {
        TypeInfo copy = new TypeInfo(storage, mutability, baseType, aliasName,
                funcPtrParamTypes, funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention,
                dynArrayElementType, unsafeDynArrayElementType, arrayElementType, rawPointeeType,
                newIsSome, floatStates, isConst, isAtomic);
        copy.literalValue = this.literalValue;
        copy.floatLiteral = this.floatLiteral;
        copy.floatArrayLiteral = this.floatArrayLiteral;
        copy.literalElements = this.literalElements;
        return copy;
    }

    /** The `isConst` counterpart of withSome just above -- identical reasoning and shape. */
    public TypeInfo withConst(boolean newIsConst) {
        TypeInfo copy = new TypeInfo(storage, mutability, baseType, aliasName,
                funcPtrParamTypes, funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention,
                dynArrayElementType, unsafeDynArrayElementType, arrayElementType, rawPointeeType,
                isSome, floatStates, newIsConst, isAtomic);
        copy.literalValue = this.literalValue;
        copy.floatLiteral = this.floatLiteral;
        copy.floatArrayLiteral = this.floatArrayLiteral;
        copy.literalElements = this.literalElements;
        return copy;
    }

    /** The `floatStates` counterpart of withSome just above -- identical reasoning and shape. */
    public TypeInfo withFloatStates(java.util.TreeSet<String> newFloatStates) {
        TypeInfo copy = new TypeInfo(storage, mutability, baseType, aliasName,
                funcPtrParamTypes, funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention,
                dynArrayElementType, unsafeDynArrayElementType, arrayElementType, rawPointeeType,
                isSome, newFloatStates, isConst, isAtomic);
        copy.literalValue = this.literalValue;
        copy.floatLiteral = this.floatLiteral;
        copy.floatArrayLiteral = this.floatArrayLiteral;
        copy.literalElements = this.literalElements;
        return copy;
    }

    /** Copy changing only isAtomic. */
    public TypeInfo withAtomic(boolean newIsAtomic) {
        TypeInfo copy = new TypeInfo(storage, mutability, baseType, aliasName,
                funcPtrParamTypes, funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention,
                dynArrayElementType, unsafeDynArrayElementType, arrayElementType, rawPointeeType,
                isSome, floatStates, isConst, newIsAtomic);
        copy.literalValue = this.literalValue;
        copy.floatLiteral = this.floatLiteral;
        copy.floatArrayLiteral = this.floatArrayLiteral;
        copy.literalElements = this.literalElements;
        return copy;
    }

    /** Canonical type string: storage_some_mutability_atomic_states_@decorators_baseType; aliases never appear. See docs/TypeChecker.md, "Type model". */
    public String canonical() {
        StringBuilder sb = new StringBuilder();
        if (storage != null) {
            sb.append(storage).append('_');
        }
        if (isSome) {
            sb.append("some").append('_');
        }
        if (mutability != null) {
            sb.append(mutability).append('_');
        }
        if (isAtomic) {
            sb.append("atomic").append('_');
        }
        if (floatStates != null) {
            sb.append(String.join("|", floatStates)).append('_');
        }
        if (funcPtrDecorators != null && !funcPtrDecorators.isEmpty()) {
            List<String> sorted = new ArrayList<>(funcPtrDecorators);
            Collections.sort(sorted);
            for (String d : sorted) {
                sb.append('@').append(d);
                if (d.equals("call_convention") && funcPtrCallConvention != null) {
                    sb.append('(').append(funcPtrCallConvention).append(')');
                }
                sb.append('_');
            }
        }
        sb.append(baseType);
        return sb.toString();
    }

    public boolean sameBaseType(TypeInfo other) {
        return other != null && baseType.equals(other.baseType);
    }

    /** Copy with a different mutability, every other field kept (the one place "mut X"/"imut X" wrapping is implemented). */
    public TypeInfo withMutability(String newMutability) {
        TypeInfo copy = new TypeInfo(storage, newMutability, baseType, aliasName,
                funcPtrParamTypes, funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention,
                dynArrayElementType, unsafeDynArrayElementType, arrayElementType, rawPointeeType,
                isSome, floatStates, isConst, isAtomic);
        copy.literalValue = this.literalValue;
        copy.floatLiteral = this.floatLiteral;
        copy.floatArrayLiteral = this.floatArrayLiteral;
        copy.literalElements = this.literalElements;
        return copy;
    }

    /** Copy with a different storage keyword, every other field kept (checkAddressOf). */
    public TypeInfo withStorage(String newStorage) {
        TypeInfo copy = new TypeInfo(newStorage, mutability, baseType, aliasName,
                funcPtrParamTypes, funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention,
                dynArrayElementType, unsafeDynArrayElementType, arrayElementType, rawPointeeType,
                isSome, floatStates, isConst, isAtomic);
        copy.literalValue = this.literalValue;
        copy.floatLiteral = this.floatLiteral;
        copy.floatArrayLiteral = this.floatArrayLiteral;
        copy.literalElements = this.literalElements;
        return copy;
    }
}

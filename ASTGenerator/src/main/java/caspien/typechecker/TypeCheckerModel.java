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

/** TypeCheckerModel: fields, nested types and static helpers of TypeChecker (part 1 of 11; see README.md in this folder). */
abstract class TypeCheckerModel {
    // methods defined in later parts of the chain
    abstract public String stripArrayDims(String baseType);
    abstract public void check(List<Token> rootLines);



    /** The loaded compiler.config (required): validates @call_convention names, supplies the default convention, and is handed to BytecodeEmitter via getConfig. */
    public CompilerConfig config;

    /** `unsafe` reasons that cannot free memory by themselves; a block with only these keeps the alive proofs (see the 'unsafe' statement case). */
    public static final Set<String> NON_FREEING_UNSAFE_TAGS = new HashSet<>(Arrays.asList(
            "memcopy", "raw", "deref", "clone", "global", "loop", "atomic", "assume"));

    public static final Set<String> NUMERIC_TYPES = new HashSet<>(Arrays.asList(
            "u64", "s64", "u32", "s32", "u16", "s16", "u8", "s8", "f32", "f64"));
    /** The two IEEE-754 float types: f32 (4 bytes) and f64 (8 bytes). */
    public static final Set<String> FLOAT_TYPES = new HashSet<>(Arrays.asList("f32", "f64"));
    public static boolean isFloatType(String baseType) {
        return baseType != null && FLOAT_TYPES.contains(baseType);
    }
    public static final Set<String> INTEGER_TYPES = new HashSet<>(Arrays.asList(
            "u64", "s64", "u32", "s32", "u16", "s16", "u8", "s8"));
    /** Reserved builtin names; a user function may not shadow them. */
    public static final Set<String> BUILTIN_NAMES = new HashSet<>(Arrays.asList(
            "sizeof", "len", "range", "deref", "clone", "call",
            "bits_left", "bits_right", "bits_rotl", "bits_rotr", "bits_or", "bits_and", "bits_xor", "bits_not", "memcopy", "dyn", "resize", "Some",
            "insecure_rand", "wrap", "sat"
    ));
    /** Float state modifiers: contextual words (type annotation before f32, match-on-f32 case labels), not lexer keywords. */
    public static final Set<String> FLOAT_STATE_NAMES = new HashSet<>(Arrays.asList("finite", "infinite", "nan"));
    /** Byte size per primitive, used only by 'sizeof'; bool and char are assumed 1 byte. See docs/TypeChecker.md, "Built-in names and sizes". */
    public static final Map<String, Long> PRIMITIVE_SIZE = new HashMap<>();
    static {
        PRIMITIVE_SIZE.put("u8", 1L); PRIMITIVE_SIZE.put("s8", 1L);
        PRIMITIVE_SIZE.put("u16", 2L); PRIMITIVE_SIZE.put("s16", 2L);
        PRIMITIVE_SIZE.put("u32", 4L); PRIMITIVE_SIZE.put("s32", 4L);
        PRIMITIVE_SIZE.put("u64", 8L); PRIMITIVE_SIZE.put("s64", 8L);
        PRIMITIVE_SIZE.put("f32", 4L);
        PRIMITIVE_SIZE.put("f64", 8L);
        PRIMITIVE_SIZE.put("bool", 1L);
        PRIMITIVE_SIZE.put("char", 1L);
    }
    /** Assumed 8 (64-bit), not stated explicitly anywhere -- every pointer, of any storage or pointee, is this size for 'sizeof' purposes. */
    public static final long POINTER_SIZE = 8L;
    /** Types that must carry a storage modifier (owns/ref/raw/auto/static) wherever explicitly annotated. */
    public final Set<String> pointerLikeBaseTypes = new HashSet<>(Arrays.asList("string"));
    /** Unary minus on an unsigned type promotes to the matching signed type -- u64 can't represent -2. */
    public static final Map<String, String> UNSIGNED_TO_SIGNED = new HashMap<>();
    static {
        UNSIGNED_TO_SIGNED.put("u64", "s64");
        UNSIGNED_TO_SIGNED.put("u32", "s32");
        UNSIGNED_TO_SIGNED.put("u16", "s16");
        UNSIGNED_TO_SIGNED.put("u8", "s8");
    }
    /** Bit width per integer type, used by 'as' to enforce widening-only casts within one signedness family. */
    public static final Map<String, Integer> INTEGER_WIDTH = new HashMap<>();
    static {
        INTEGER_WIDTH.put("u8", 8);
        INTEGER_WIDTH.put("u16", 16);
        INTEGER_WIDTH.put("u32", 32);
        INTEGER_WIDTH.put("u64", 64);
        INTEGER_WIDTH.put("s8", 8);
        INTEGER_WIDTH.put("s16", 16);
        INTEGER_WIDTH.put("s32", 32);
        INTEGER_WIDTH.put("s64", 64);
    }

    /** Package-visible so BytecodeEmitter can reuse resolved type info directly. */
    public static class TypeInfo {
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

    public static class StructInfo {
        public final String name;
        /** Final member set (a struct has no ancestors). Populated by the flatten pass -- empty/partial before it runs. */
        public final LinkedHashMap<String, TypeInfo> members = new LinkedHashMap<>();
        /** Declared interface conformance ("implements A,B") -- a compile-time contract, checked once all impls are collected. */
        public List<String> implementsNames = new ArrayList<>();
        /** Own (non-"extends") member declaration lines, kept until the flatten pass consumes them. */
        public List<Token> rawMemberLines;
        /** Memoization/cycle-detection marker for the flatten pass. */
        public boolean flattened;
        public Token declTok;
        /** Gates the struct itself: a private struct is not nameable outside its declaring file. See docs/TypeChecker.md, "Visibility". */
        public boolean isPublic;
        /** Members marked public through a @pub{} block; all others are private. Meaningful only when `isPublic`. */
        public final Set<String> publicMembers = new HashSet<>();
        /** Each member's original declaring file, for the private-member file-locality check. */
        public final Map<String, String> memberDeclFile = new HashMap<>();
        /** For "@lock(match self.x : X)" on a struct: `lockFieldName` is x (always freely readable), `lockVariants` the |-chained variants. */
        public String lockFieldName;
        public List<String> lockVariants;
        /** Name of the struct's single 'swap'-tagged mutex member (a mut atomic OPEN/CLOSED enum), validated by validateSwapMutexField; must equal `lockFieldName` when both are set. */
        public String swapFieldName;
        /** The struct's "impl default match @lock" policy template (at most one). Only deep-cloned copies per use site are checked. See DefaultLockMatchPolicy. */
        public DefaultLockMatchPolicy defaultLockMatchPolicy;
        /** Class id in the flat `Class` enum (generateClassHierarchyEnums); null for an @untyped struct. Populates the hidden "___type" field. */
        public Long classId;
        public StructInfo(String name) { this.name = name; }
    }

    /**
     * "impl default match @lock" policy template for a match @lock CLOSED case ("CLOSED:default" / "CLOSED:default(args)").
     * Kept as raw tokens, never checked itself; a fresh clone is checked per use site. See docs/TypeChecker.md, "Default lock-match policy".
     */
    public static class DefaultLockMatchPolicy {
        /** The struct this policy is declared for (StructInfo.defaultLockMatchPolicy's own owner). */
        public final String structName;
        /** Raw "(" token of the parameter list; the first parameter is the automatic per-iteration "tries" counter, never supplied by the call site. */
        public final Token rawParamsTok;
        /** Raw body lines, exactly the parser's `blockTok.childs`; never run through gatherKeywordBlocks/RPN/tree-build. */
        public final List<Token> rawBodyLines;
        public final Token declTok;
        public DefaultLockMatchPolicy(String structName, Token rawParamsTok, List<Token> rawBodyLines, Token declTok) {
            this.structName = structName;
            this.rawParamsTok = rawParamsTok;
            this.rawBodyLines = rawBodyLines;
            this.declTok = declTok;
        }
    }

    public static class EnumInfo {
        public final String name;
        public final List<String> variants = new ArrayList<>();
        /** Decorated @non_exhaustive: the enum must end with a `default` case. */
        public boolean isNonExhaustive;
        /** Explicit integer discriminants; null unless every variant has one (no partial mixing). Also the test for a "guaranteed" enum (checkGuaranteedEnumMembership). */
        public List<Long> explicitValues;
        /** Explicit [start, end] range per variant for a range-valued enum; null otherwise, exclusive with `explicitValues`. */
        public List<Long> explicitRangeStarts;
        public List<Long> explicitRangeEnds;
        /** True when this enum is range-valued. */
        public boolean isRangeValued() { return explicitRangeStarts != null; }
        public EnumInfo(String name) { this.name = name; }
    }

    public static class FuncInfo {
        public String name;
        /** Defaults to `name`; overridden for impl methods. */
        public String mangledName;
        public final List<String> paramNames = new ArrayList<>();
        public final List<TypeInfo> paramTypes = new ArrayList<>();
        public TypeInfo returnType;
        public Token funcToken;
        /** Root-list position (impl method: its impl's), for the globals "referenced before declaration" check; MAX_VALUE = no restriction. */
        public int declOrder = Integer.MAX_VALUE;
        /** "unsafe"/"safe"/"indeterminate" from the enclosing wrapper block; never null. */
        public String safetyTag = "indeterminate";
        /** Marked by "export foo": BytecodeEmitter emits an EXPORT line. Never true for impl methods. */
        public boolean isExported = false;
        /** Resolved calling convention (decorator or config default); read for every CALL/RECURSIVE_CALL. */
        public String callConvention;
        /** @async: invoked only through par/await, and par/await only accept @async functions (checkCall enforces both directions). */
        public boolean isAsync;
        /** @throws: required, and only allowed, on a function whose body throws; every call needs try/catch (requireThrowsWrapping). */
        public boolean isThrows;
        /** Set when a 'throw' is checked in this body; checkFunctionBody uses it to reject an unneeded @throws. */
        public boolean sawThrow;
        /** @pub; for impl methods also gated by the enclosing ImplInfo's `isPublic`. See docs/TypeChecker.md, "Visibility". */
        public boolean isPublic;
        /** Null for a plain top-level func; else the impl this method belongs to. */
        public ImplInfo enclosingImpl;
        /** Null unless a library function: the library that declared it (for requireLibraryVisibility). */
        public LibraryInfo enclosingLibrary;
        /** Count of bare loop{} statements already exempted in an @event_loop function (only the first is). */
        public int eventLoopSafeLoopCount;
        /** Every owns-typed local of this function, across all scopes, so BytecodeEmitter can null a slot after its scope-end GT_DESTRUCT (see emitDestructList). */
        public Map<String, TypeInfo> ownsLocalTypes = new LinkedHashMap<>();
        /** Argument shape of the real 'main': null, "c_args" or "safe_args" (validateMainFunction). Read by validateTickEventLoop and BytecodeEmitter. */
        public String mainArgShape;
        /** True for "impl constructor for Name(...)" (see collectFunc). */
        public boolean isConstructor;
        /** For a constructor: the struct it builds; read by checkInstantiate's OTC gate and checkNew/checkCall errors. */
        public String constructorConcreteName;
    }

    /** "extern name(types...) ret": a signature the linked assembly provides. No body, no mangling, no overloading; globally unique by name. */
    public static class ExternInfo {
        public String name;
        /** Literal link symbol: "@link_name(x)" if present, else `name`. */
        public String linkName;
        /** Parameter types only; extern parameters are unnamed. */
        public final List<TypeInfo> paramTypes = new ArrayList<>();
        /** Signature ended in '...': extra unchecked arguments allowed at call sites. */
        public boolean hasVarargs;
        public TypeInfo returnType;
        public Token declTok;
        /** Resolved calling convention (decorator or config default). */
        public String callConvention;
    }

    /** "ASM name { ... }" or "ASM name \"path\"": opaque assembly; `rawText` is the final content for both forms. */
    public static class AsmInfo {
        /** Null for an unnamed declaration, which self-emits at its declaration point. */
        public String name;
        public String rawText;
        public Token declTok;
    }

    /** An interface's raw (unresolved -- 'self' isn't a concrete type yet) method signatures. */
    public static class InterfaceInfo {
        public String name;
        /** Final, flattened method set (own + every extended interface's, name-collision-checked). Populated by the flatten pass. */
        public final LinkedHashMap<String, RawMethodSig> methods = new LinkedHashMap<>();
        public List<String> extendsNames = new ArrayList<>();
        /** This interface's own declared methods only, kept until the flatten pass consumes them. */
        public LinkedHashMap<String, RawMethodSig> ownMethods;
        public boolean flattened;
        public Token declTok;
        /** Gates the interface itself; its methods are always public. */
        public boolean isPublic;
    }

    public static class RawMethodSig {
        public Token nameTok;
        public Token paramsTok;
        public List<Token> returnTypeTokens;
        /** True if this came from a "@default func ...{...}" (a body-bearing method) rather than a plain signature-only declaration. */
        public boolean isDefault;
        /** Only for isDefault: the interface's gathered "func" token, deep-cloned for each impl that omits the method (see materializeDefaultMethod). */
        public Token defaultBodyTok;
        /** Decorators of the interface method declaration (e.g. @lock/@unlock on @guard interfaces), checked against the implementing method. */
        public List<Token.Decorator> decorators;
        /** "static" signature; kept in the same maps as instance signatures (a name clash is still an error), this flag keeps the two kinds apart. */
        public boolean isStatic;
    }

    /** "impl InterfaceName for ConcreteName{...}" (interfaceName set) or "impl ConcreteName{...}" (interfaceName null). */
    public static class ImplInfo {
        public String interfaceName;
        public String concreteName;
        public final LinkedHashMap<String, FuncInfo> methods = new LinkedHashMap<>();
        /** Static methods, in a separate map so instance/static dispatch stay structurally apart. */
        public final LinkedHashMap<String, FuncInfo> staticMethods = new LinkedHashMap<>();
        /**
         * Overload lists by bare name (includes the first method). A second same-named method with a different signature is stored in
         * `methods`/`staticMethods` under the key "name#signature". Only a bare `impl` may overload. Look up through candidates().
         */
        public final LinkedHashMap<String, List<FuncInfo>> methodOverloads = new LinkedHashMap<>();
        public final LinkedHashMap<String, List<FuncInfo>> staticMethodOverloads = new LinkedHashMap<>();
        /** Every overload of `name` in declaration order; empty if none. */
        public List<FuncInfo> candidates(String name, boolean isStatic) {
            List<FuncInfo> list = (isStatic ? staticMethodOverloads : methodOverloads).get(name);
            if (list != null) {
                return list;
            }
            FuncInfo single = (isStatic ? staticMethods : methods).get(name);
            return single == null ? java.util.Collections.<FuncInfo>emptyList() : java.util.Collections.singletonList(single);
        }
        public Token declTok;
        /** Outer visibility gate, checked in addition to each method's own isPublic. */
        public boolean isPublic;
        /** The impl's safety tag, used when checking a generic method instantiated at a call site. */
        public String safetyTag;
        /** Generic methods (own impl-level type params) held back by name; materialized per call site by instantiateGenericMethod. See docs/TypeChecker.md, "Generic impl methods". */
        public final LinkedHashMap<String, Token> genericMethodTemplates = new LinkedHashMap<>();
        /** Memoized instantiations of genericMethodTemplates, keyed "method<arg1,arg2>". */
        public final LinkedHashMap<String, FuncInfo> genericMethodInstances = new LinkedHashMap<>();
    }

    /**
     * "library Name { ... }": functions dispatched by bare library name ("Math.sin(x)"); no instance, no self.
     * Same privacy rules as an impl; unlimited 'extends'.
     */
    public static class LibraryInfo {
        public String name;
        public List<String> extendsNames = new ArrayList<>();
        /** Own functions only, name to overloads (overloaded by full signature, unlike an impl). */
        public LinkedHashMap<String, List<FuncInfo>> ownFunctions;
        /** Final, flattened function set (own + every extended library's, full-signature-collision-checked). Populated by flattenLibrary. */
        public final LinkedHashMap<String, List<FuncInfo>> functions = new LinkedHashMap<>();
        public boolean flattened;
        public Token declTok;
        /** Gates the library itself, not its own functions -- exactly mirrors ImplInfo.isPublic (see requireLibraryVisibility). */
        public boolean isPublic;
    }

    /** Marker type giving the try-block Scope constructor a signature distinct from Scope(Scope, boolean, boolean). */
    public enum TryBlockBoundaryMarker { MARK }

    /** The reasons a statement-level `unsafe` block must name (`unsafe deref extern{`); `unaudited` is the catch-all, written alone, and never allowed in the stdlib. See docs/TypeChecker.md, "Unsafe block tags". */
    public static final Set<String> UNSAFE_TAGS = new java.util.LinkedHashSet<>(Arrays.asList(
            "extern", "memcopy", "raw", "deref", "clone", "global", "loop", "udyn", "udyn:owns",
            "assume", "call", "asm", "async", "atomic", "file", "unaudited"));

    /** One written `unsafe ...{` block: what it declares, and (unioned over every time it is checked, e.g. per generic instantiation) what it really used. */
    public static class UnsafeBlockRecord {
        public final Token tok;
        public final Set<String> declared = new java.util.TreeSet<>();
        public final Set<String> used = new java.util.TreeSet<>();
        /** For `--fix unsafe-split`: per direct child statement its source line, whether it is a bare `assume match`, and the tags it needed. */
        public final List<Integer> childLine = new ArrayList<>();
        public final List<Boolean> childAssume = new ArrayList<>();
        public final List<Set<String>> childTags = new ArrayList<>();
        /** Tags used while no direct child was being checked (the split cannot place them). */
        public final Set<String> unattributed = new java.util.TreeSet<>();
        public int cur = -1;
        public UnsafeBlockRecord(Token tok) { this.tok = tok; }
        public void noteChild(int idx, Token stmt) {
            while (childLine.size() <= idx) {
                childLine.add(0);
                childAssume.add(false);
                childTags.add(new java.util.TreeSet<>());
            }
            childLine.set(idx, stmt.line);
            childAssume.set(idx, stmt.type == TokenType.KEYWORD && stmt.text.equals("assume") && !stmt.hasBlock);
            cur = idx;
        }
        public void use(String tag) {
            used.add(tag);
            if (cur >= 0) {
                childTags.get(cur).add(tag);
            } else {
                unattributed.add(tag);
            }
        }
    }

    /** What `--fix unsafe-split` needs about one written statement-level `unsafe` block. */
    public static final class SplitInfo {
        public final String file;
        public final int line;
        public final Set<String> declared;
        public final List<Integer> childLine;
        public final List<Boolean> childAssume;
        public final List<Set<String>> childTags;
        public final Set<String> unattributed;
        public SplitInfo(String file, int line, Set<String> declared, List<Integer> childLine, List<Boolean> childAssume, List<Set<String>> childTags, Set<String> unattributed) {
            this.file = file; this.line = line; this.declared = declared; this.childLine = childLine; this.childAssume = childAssume;
            this.childTags = childTags; this.unattributed = unattributed;
        }
    }

    public final Map<String, UnsafeBlockRecord> unsafeBlockRecords = new java.util.LinkedHashMap<>();

    public static class Scope {
        public final Scope parent;
        public final Map<String, TypeInfo> vars = new HashMap<>();
        /** Names declared via 'let static' in this exact scope -- checkAddressOf's 'static' case needs to know this to accept "raw someStaticVar"-style pointers. */
        public final Set<String> staticNames = new HashSet<>();
        /** Names declared by 'let const' in this scope (local counterpart of TypeInfo.isConst); see isConstName. */
        public final Set<String> constNames = new HashSet<>();
        /** Slot keys made unassignable by a for/for-match loop for its variable and source expression, for the loop body. Unlike constNames, checked along the whole scope chain. */
        public Set<String> ownLoopImmutableSlots;
        /** Walks all parent scopes: a loop's immutability grant covers nested blocks. */
        public boolean isLoopImmutable(String slotKey) {
            if (slotKey == null) {
                return false;
            }
            for (Scope s = this; s != null; s = s.parent) {
                if (s.ownLoopImmutableSlots != null && s.ownLoopImmutableSlots.contains(slotKey)) {
                    return true;
                }
            }
            return false;
        }
        /** ASM names declared inside a function body; lexical, visible only after the declaration is checked. */
        public final Map<String, AsmInfo> localAsmNames = new HashMap<>();
        /** Slots (variable name or "a.b" path) currently holding a moved-away owns value. Shared across all scopes of one function, since moves are not lexically scoped. */
        public final Set<String> movedSlots;
        /** Current safety context ("unsafe"/"safe"/"indeterminate"), inherited and overridable by a nested block. */
        public final String currentSafety;
        /** The tagged 'unsafe' block this scope sits in (set on the scope that block opens, found via parents); null outside any statement-level unsafe block. */
        public UnsafeBlockRecord unsafeBlock;
        /** Proven MatchPatterns, inherited and extended by entering a match block; never mutated. */
        public final List<Token.MatchPattern> activeMatchPatterns;
        /** True inside the base-case match of a @recursive function; checkCall/checkMethodCall reject self-calls there. */
        public final boolean suspendsSelfRecursion;
        /** Owns slots declared directly in this scope, in order (per block, not inherited); dropped at scope end. Filled by checkAssign (lets) and checkFunctionBody (params, in the root scope). */
        public final List<String> ownsDeclaredHere = new ArrayList<>();
        /** The function's outermost scope (itself at the root); a 'return' destructs owns locals up to and including it. */
        public final Scope functionRootScope;
        /** Innermost loop body scope (null outside loops); a 'break' destructs up to and including it. Reset by each nested loop. */
        public final Scope loopBoundaryScope;
        /** Innermost "try { }" body scope (null outside); a 'continue' in a catch body destructs/unlocks up to it and jumps past the try block. Reset by each nested try. */
        public final Scope tryBlockBoundaryScope;
        /** True inside a "for i in dynarrayExpr" body: the dynarray may not be resized, even through another function. Inherited; can only be turned on. */
        public final boolean insideDynArrayForLoopBody;
        /** Owed lock releases (synthesized unlock calls) in acquisition order, inherited and extended by lock/match @lock OPEN bodies; return reads all, break the suffix past the loop boundary. */
        public final List<Token> activeLockReleases;
        public Scope(Scope parent) {
            this.parent = parent;
            this.movedSlots = (parent != null) ? parent.movedSlots : new HashSet<>();
            this.currentSafety = (parent != null) ? parent.currentSafety : "indeterminate";
            this.activeMatchPatterns = (parent != null) ? parent.activeMatchPatterns : Collections.emptyList();
            this.suspendsSelfRecursion = parent != null && parent.suspendsSelfRecursion;
            this.functionRootScope = (parent != null) ? parent.functionRootScope : this;
            this.loopBoundaryScope = (parent != null) ? parent.loopBoundaryScope : null;
            this.tryBlockBoundaryScope = (parent != null) ? parent.tryBlockBoundaryScope : null;
            this.insideDynArrayForLoopBody = (parent != null) && parent.insideDynArrayForLoopBody;
            this.activeLockReleases = (parent != null) ? parent.activeLockReleases : Collections.emptyList();
        }
        /** Used only where movement needs to be tracked in an *isolated* copy (branch-checking) rather than the real, shared set. */
        public Scope(Scope parent, Set<String> movedSlotsOverride) {
            this.parent = parent;
            this.movedSlots = movedSlotsOverride;
            this.currentSafety = (parent != null) ? parent.currentSafety : "indeterminate";
            this.activeMatchPatterns = (parent != null) ? parent.activeMatchPatterns : Collections.emptyList();
            this.suspendsSelfRecursion = parent != null && parent.suspendsSelfRecursion;
            this.functionRootScope = (parent != null) ? parent.functionRootScope : this;
            this.loopBoundaryScope = (parent != null) ? parent.loopBoundaryScope : null;
            this.tryBlockBoundaryScope = (parent != null) ? parent.tryBlockBoundaryScope : null;
            this.insideDynArrayForLoopBody = (parent != null) && parent.insideDynArrayForLoopBody;
            this.activeLockReleases = (parent != null) ? parent.activeLockReleases : Collections.emptyList();
        }
        /** Used only when entering a nested "unsafe"/"safe" block within a function body -- overrides currentSafety for this scope and its descendants. */
        public Scope(Scope parent, String currentSafetyOverride) {
            this.parent = parent;
            this.movedSlots = (parent != null) ? parent.movedSlots : new HashSet<>();
            this.currentSafety = currentSafetyOverride;
            this.activeMatchPatterns = (parent != null) ? parent.activeMatchPatterns : Collections.emptyList();
            this.suspendsSelfRecursion = parent != null && parent.suspendsSelfRecursion;
            this.functionRootScope = (parent != null) ? parent.functionRootScope : this;
            this.loopBoundaryScope = (parent != null) ? parent.loopBoundaryScope : null;
            this.tryBlockBoundaryScope = (parent != null) ? parent.tryBlockBoundaryScope : null;
            this.insideDynArrayForLoopBody = (parent != null) && parent.insideDynArrayForLoopBody;
            this.activeLockReleases = (parent != null) ? parent.activeLockReleases : Collections.emptyList();
        }
        /** Entering a match block: extends the proven patterns; suspends self-recursion for the required "RANGE_PARAM is base" match. */
        public Scope(Scope parent, List<Token.MatchPattern> extendedMatchPatterns, boolean suspendsSelfRecursionOverride) {
            this.parent = parent;
            this.movedSlots = (parent != null) ? parent.movedSlots : new HashSet<>();
            this.currentSafety = (parent != null) ? parent.currentSafety : "indeterminate";
            this.activeMatchPatterns = extendedMatchPatterns;
            ProofKills.stamp(extendedMatchPatterns);
            this.suspendsSelfRecursion = (parent != null && parent.suspendsSelfRecursion) || suspendsSelfRecursionOverride;
            this.functionRootScope = (parent != null) ? parent.functionRootScope : this;
            this.loopBoundaryScope = (parent != null) ? parent.loopBoundaryScope : null;
            this.tryBlockBoundaryScope = (parent != null) ? parent.tryBlockBoundaryScope : null;
            this.insideDynArrayForLoopBody = (parent != null) && parent.insideDynArrayForLoopBody;
            this.activeLockReleases = (parent != null) ? parent.activeLockReleases : Collections.emptyList();
        }
        /** One branch of a match/elsematch/else chain: isolated movedSlots copy plus this branch's patterns (a plain else adds none and never suspends recursion). */
        public Scope(Scope parent, Set<String> movedSlotsOverride, List<Token.MatchPattern> extendedMatchPatterns,
                boolean suspendsSelfRecursionOverride) {
            this.parent = parent;
            this.movedSlots = movedSlotsOverride;
            this.currentSafety = (parent != null) ? parent.currentSafety : "indeterminate";
            this.activeMatchPatterns = extendedMatchPatterns;
            ProofKills.stamp(extendedMatchPatterns);
            this.suspendsSelfRecursion = (parent != null && parent.suspendsSelfRecursion) || suspendsSelfRecursionOverride;
            this.functionRootScope = (parent != null) ? parent.functionRootScope : this;
            this.loopBoundaryScope = (parent != null) ? parent.loopBoundaryScope : null;
            this.tryBlockBoundaryScope = (parent != null) ? parent.tryBlockBoundaryScope : null;
            this.insideDynArrayForLoopBody = (parent != null) && parent.insideDynArrayForLoopBody;
            this.activeLockReleases = (parent != null) ? parent.activeLockReleases : Collections.emptyList();
        }
        /** Entering a loop body: this scope becomes the innermost loop boundary. */
        public Scope(Scope parent, boolean isNewLoopBoundary) {
            this.parent = parent;
            this.movedSlots = (parent != null) ? parent.movedSlots : new HashSet<>();
            this.currentSafety = (parent != null) ? parent.currentSafety : "indeterminate";
            this.activeMatchPatterns = (parent != null) ? parent.activeMatchPatterns : Collections.emptyList();
            this.suspendsSelfRecursion = parent != null && parent.suspendsSelfRecursion;
            this.functionRootScope = (parent != null) ? parent.functionRootScope : this;
            this.loopBoundaryScope = isNewLoopBoundary ? this : ((parent != null) ? parent.loopBoundaryScope : null);
            this.tryBlockBoundaryScope = (parent != null) ? parent.tryBlockBoundaryScope : null;
            this.insideDynArrayForLoopBody = (parent != null) && parent.insideDynArrayForLoopBody;
            this.activeLockReleases = (parent != null) ? parent.activeLockReleases : Collections.emptyList();
        }
        /** Entering a "for i in dynarrayExpr" body: loop boundary plus insideDynArrayForLoopBody. */
        public Scope(Scope parent, boolean isNewLoopBoundary, boolean isDynArrayForLoopBody) {
            this.parent = parent;
            this.movedSlots = (parent != null) ? parent.movedSlots : new HashSet<>();
            this.currentSafety = (parent != null) ? parent.currentSafety : "indeterminate";
            this.activeMatchPatterns = (parent != null) ? parent.activeMatchPatterns : Collections.emptyList();
            this.suspendsSelfRecursion = parent != null && parent.suspendsSelfRecursion;
            this.functionRootScope = (parent != null) ? parent.functionRootScope : this;
            this.loopBoundaryScope = isNewLoopBoundary ? this : ((parent != null) ? parent.loopBoundaryScope : null);
            this.tryBlockBoundaryScope = (parent != null) ? parent.tryBlockBoundaryScope : null;
            this.insideDynArrayForLoopBody = isDynArrayForLoopBody || (parent != null && parent.insideDynArrayForLoopBody);
            this.activeLockReleases = (parent != null) ? parent.activeLockReleases : Collections.emptyList();
        }
        /** Entering a lock body or match @lock OPEN body: appends the synthesized release call. */
        public Scope(Scope parent, Token extraLockRelease) {
            this.parent = parent;
            this.movedSlots = (parent != null) ? parent.movedSlots : new HashSet<>();
            this.currentSafety = (parent != null) ? parent.currentSafety : "indeterminate";
            this.activeMatchPatterns = (parent != null) ? parent.activeMatchPatterns : Collections.emptyList();
            this.suspendsSelfRecursion = parent != null && parent.suspendsSelfRecursion;
            this.functionRootScope = (parent != null) ? parent.functionRootScope : this;
            this.loopBoundaryScope = (parent != null) ? parent.loopBoundaryScope : null;
            this.tryBlockBoundaryScope = (parent != null) ? parent.tryBlockBoundaryScope : null;
            this.insideDynArrayForLoopBody = (parent != null) && parent.insideDynArrayForLoopBody;
            List<Token> extended = new ArrayList<>((parent != null) ? parent.activeLockReleases : Collections.emptyList());
            extended.add(extraLockRelease);
            this.activeLockReleases = extended;
        }
        /** match @lock: isolated movedSlots copy plus the OPEN case's release call. */
        public Scope(Scope parent, Set<String> movedSlotsOverride, Token extraLockRelease) {
            this.parent = parent;
            this.movedSlots = movedSlotsOverride;
            this.currentSafety = (parent != null) ? parent.currentSafety : "indeterminate";
            this.activeMatchPatterns = (parent != null) ? parent.activeMatchPatterns : Collections.emptyList();
            this.suspendsSelfRecursion = parent != null && parent.suspendsSelfRecursion;
            this.functionRootScope = (parent != null) ? parent.functionRootScope : this;
            this.loopBoundaryScope = (parent != null) ? parent.loopBoundaryScope : null;
            this.tryBlockBoundaryScope = (parent != null) ? parent.tryBlockBoundaryScope : null;
            this.insideDynArrayForLoopBody = (parent != null) && parent.insideDynArrayForLoopBody;
            List<Token> extended = new ArrayList<>((parent != null) ? parent.activeLockReleases : Collections.emptyList());
            extended.add(extraLockRelease);
            this.activeLockReleases = extended;
        }
        /** Entering a "try { }" body: this scope becomes the innermost try boundary (a marker type avoids an erasure clash with Scope(Scope, boolean, boolean)). */
        public Scope(Scope parent, TryBlockBoundaryMarker ignoredMarker) {
            this.parent = parent;
            this.movedSlots = (parent != null) ? parent.movedSlots : new HashSet<>();
            this.currentSafety = (parent != null) ? parent.currentSafety : "indeterminate";
            this.activeMatchPatterns = (parent != null) ? parent.activeMatchPatterns : Collections.emptyList();
            this.suspendsSelfRecursion = parent != null && parent.suspendsSelfRecursion;
            this.functionRootScope = (parent != null) ? parent.functionRootScope : this;
            this.loopBoundaryScope = (parent != null) ? parent.loopBoundaryScope : null;
            this.tryBlockBoundaryScope = this;
            this.insideDynArrayForLoopBody = (parent != null) && parent.insideDynArrayForLoopBody;
            this.activeLockReleases = (parent != null) ? parent.activeLockReleases : Collections.emptyList();
        }

        public TypeInfo lookup(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.vars.containsKey(name)) {
                    return s.vars.get(name);
                }
            }
            return null;
        }

        public boolean isStatic(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.vars.containsKey(name)) {
                    return s.staticNames.contains(name);
                }
            }
            return false;
        }

        public boolean isConstName(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.vars.containsKey(name)) {
                    return s.constNames.contains(name);
                }
            }
            return false;
        }

        /** An `unsafe`/`safe` block is a permission marker, not a lexical scope: what it declares belongs to the nearest enclosing real scope. */
        public boolean transparent;

        public Scope declScope() {
            Scope s = this;
            while (s.transparent && s.parent != null) {
                s = s.parent;
            }
            return s;
        }

        public void declare(String name, TypeInfo type, Token at) {
            if (transparent && parent != null) {
                parent.declare(name, type, at);
                return;
            }
            if (vars.containsKey(name)) {
                throw new CompilerException("type", at.file, at.line,
                        "variable '" + name + "' is already declared in this scope");
            }
            vars.put(name, type);
        }
    }

    public final Map<String, StructInfo> structs = new HashMap<>();
    /** Struct names in declaration order (imports included), for generateClassHierarchyEnums; appended only by registerStructLike. */
    public final List<String> structDeclOrder = new ArrayList<>();
    public final Map<String, EnumInfo> enums = new HashMap<>();
    /** The list `check(...)` was given, shared with BytecodeEmitter, so root tokens appended later (par's AsyncHandle_T, see getOrCreateAsyncHandleType) reach emission. */
    public List<Token> rootLinesRef;
    public final Map<String, List<FuncInfo>> functions = new HashMap<>();
    public final Map<String, InterfaceInfo> interfaces = new HashMap<>();
    /** extern name -> signature; collectFunc/collectExtern cross-check so an extern and a function never share a name. */
    public final Map<String, ExternInfo> externs = new HashMap<>();
    /** Top-level func names already claimed by an 'export' (a second export of the name, or of overloads sharing it, is an error). */
    public final Set<String> exportedNames = new HashSet<>();
    /** Named root-level ASM declarations, in their own namespace. Visible from any function body; a root-level "asm_name;" invocation is processed in file order. See collectAsmDeclarationsAndInvocations. */
    public final Map<String, AsmInfo> rootAsms = new HashMap<>();
    /** Concrete type name -> every impl block declared for it (at most one bare impl, plus at most one per interface -- enforced at collection time). */
    public final Map<String, List<ImplInfo>> implsByConcreteType = new HashMap<>();
    /** Every generic constructor's FuncInfo, registered by collectImpl into `functions` (so check()'s two body loops miss it); check() runs a third loop over this list. See docs/TypeChecker.md, "Generic constructors". */
    public final List<FuncInfo> genericConstructorFuncInfos = new ArrayList<>();

    /** Thrown by buildFuncInfo for a generic-instance method whose signature is illegal only because of the chosen type argument (by-value struct parameter). */
    public static final class DeferredSignatureException extends CompilerException {
        public final String raw;
        public DeferredSignatureException(String file, int line, String message) {
            super("type", file, line, message);
            this.raw = message;
        }
    }

    /** Methods/constructors dropped from a generic impl instantiation because their signature is illegal for the type argument, keyed "Concrete.method" or "__ctor__Concrete"; reasons are appended to a later "no match" error (unavailableNote). */
    public final Map<String, List<String>> unavailableGenericMethods = new HashMap<>();
    /** Library name -> LibraryInfo; shares its namespace with structs/enums/interfaces (registerLibraryName cross-checks). */
    public final Map<String, LibraryInfo> libraries = new LinkedHashMap<>();
    /** Root-level "let static NAME = ..." globals -- name -> resolved type. */
    public final Map<String, TypeInfo> globals = new HashMap<>();
    /** "type NAME ..." aliases: name -> raw target tokens, resolved lazily per use, so an unused alias is never validated. */
    public final Map<String, List<Token>> aliasRawTargets = new HashMap<>();    /** Root-list position each global was declared at, for "cannot be referenced before declaration." */
    public final Map<String, Integer> globalDeclOrder = new HashMap<>();
    /** Declaration order, so BytecodeEmitter emits every 'GLOBAL' line in a stable, deterministic order. */
    public final List<String> globalOrder = new ArrayList<>();
    /** Each global's own initializer expression, kept for BytecodeEmitter to fold into 'GLOBAL' lines later. */
    public final Map<String, Token> globalRhsByName = new HashMap<>();
    /** Public root-level globals (private by default; a local 'let static' needs no entry). The declaring file is in globalRhsByName. */
    public final Set<String> publicGlobals = new HashSet<>();
    /** "const NAME EXPR": pure compile-time substitution; name -> unevaluated expression root, evaluated lazily on first use (consts are forward-declarable). */
    public final Map<String, Token> constExprs = new HashMap<>();
    /** Memoized evaluateConstant result per const. */
    public final Map<String, Token> constResolvedValues = new HashMap<>();
    /** Consts mid-evaluation, for cycle detection. */
    public final Set<String> constsBeingEvaluated = new HashSet<>();
    /** Set per collectDeclarations(): func/impl KEYWORD token (impl methods share the impl's) -> root-list position. */
    public Map<Token, Integer> pendingDeclOrder = new HashMap<>();
    /** Generates unique names for the hidden range-binding variable a for-loop desugars to (see checkForLoop). */
    public int forVarCounter = 0;
    /** Identity of the sole expression of the 'return' being resolved, to tell a bare tail self-call (legal for @recursive) from one nested in a larger expression (requireRecursiveCallReturnSiteOnly). */
    public Token directReturnExprNode;
    /** Null unless 'main' is '@with_tick': the @event_loop function (set by validateTickEventLoop), which BytecodeEmitter makes the entry point instead of main. */
    public FuncInfo eventLoopFunc;

    /** At most one function per ghost-table decorator (@gt_init/@gt_alive_check/@gt_destruct/@gt_register...), filled by validateGhostTableDecoratorSignatures; BytecodeEmitter requires the function at each emission point. */
    public final Map<String, FuncInfo> ghostTableFunctions = new HashMap<>();

    /** Whole-program, syntactic flag: any owns/ref storage modifier, 'new' or 'dyn(...)' appears anywhere. Read by BytecodeEmitter to require GT_INIT in the entry point. See docs/TypeChecker.md, "Whole-program flags". */
    public boolean usesOwnsRefDynNew = false;

    /** Whole-program flag: any 'throw' was checked. OR'd with usesOwnsRefDynNew to decide whether each function gets the gt_routine/GT_UNWIND prologue. See docs/TypeChecker.md, "Whole-program flags". */
    public boolean usesThrow = false;

    /** True while checking anything under a "try new/dyn/resize ... catch" wrapper; nested allocations then share the one catch. See docs/TypeChecker.md, "Checker context flags". */
    public boolean insideThrowingAllocContext = false;

    /** True while checking a "catch(e){ }" body, the only place `continue` exits the try block. Save/restored by checkTry. */
    public boolean insideCatchBody = false;
    /** Innermost loop boundary when the catch body being checked started: a `continue` still targeting it belongs to the catch (skips the try block); one in a loop opened inside the catch belongs to that loop. */
    public Scope catchEnclosingLoopBoundary = null;
    /** Loop-body scopes of user-written `for`/`loop` statements (not the synthesized `match @lock` spin loop): the only scopes a loop `continue` may target. */
    public final Set<Scope> userLoopScopes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Spin-loop scope of the match @lock CLOSED case being checked (null elsewhere, and for OPEN): a `continue` directly inside retries the acquire. */
    public Scope lockClosedLoopScope = null;
    public int lockRetryContinuesSeen = 0;      // every `continue` that retries a `match @lock` acquire

    /** True only while `closedCaseTerminates` runs: makes `statementDefinitelyReturns` count a 'break' as a terminator. */
    public boolean breakCountsAsTerminator = false;

    /** Single-shot: true only for the next resolveExprType call, which may resolve a top-level `&&then`/`||then` (from an if/elseif condition root or through other then-nodes only). See docs/TypeChecker.md, "Checker context flags". */
    public boolean insideShortCircuitEligiblePosition = false;

    /** Single-shot, like insideShortCircuitEligiblePosition: true only where a struct-returning call (RVO) is legal, i.e. a plain let RHS or a bare-variable '=' RHS in checkAssign; not a return. See docs/TypeChecker.md, "Checker context flags". */
    public boolean insideStructRvoDestinationPosition = false;

    /** Name of the struct whose constructor body is being checked, else null. checkInstantiate's OTC gate: once a struct has a constructor, its bare literal is legal only inside it. */
    public String insideConstructorBodyForStruct = null;

    /** One root-level declaration line, tagged with its enclosing unsafe/safe context (if any). */
    public static class FlatDecl {
        public final Token lineTok;
        public final String safety;
        public FlatDecl(Token lineTok, String safety) {
            this.lineTok = lineTok;
            this.safety = safety;
        }
    }

    // ---- decorators ("@name(args)") -------------------------------------

    /** Valid on any func-shaped token (top-level func, interface default method, or impl method) regardless of context; @default/@overrides/@realizes are validated separately, per-context, since which of those (if any) is *required* depends on where the func sits. */
    public static final Set<String> FUNC_BASE_DECORATORS = new HashSet<>(Arrays.asList(
            "pure", "recursive", "inline", "call_convention", "reads", "writes",
            "gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve", "par_call", "await_call", "sleep",
            "lock", "async", "pub", "with_tick", "tick", "event_loop", "make_safe_args", "throws", "drop", "fs_root", "fs_unsafe", "non"));
    public final Map<String, String> dropHookOwners = new HashMap<>();

    /** True for a file inside the compiler's own stdlib folder (the project's stdlib, see ProjectRoot). */
    public static boolean isStdlibFile(String file) {
        try {
            java.nio.file.Path stdlib = caspien.ProjectRoot.stdlib();
            return java.nio.file.Paths.get(file).toAbsolutePath().normalize().startsWith(stdlib);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public final List<String> fsReportLines = new ArrayList<>();

    public static final Set<String> STRUCT_DECORATORS = new HashSet<>(Arrays.asList("unpadded", "untyped", "pub", "lock"));
    public static final Set<String> ENUM_DECORATORS = new HashSet<>(Arrays.asList("non_exhaustive"));
    public static final Set<String> INTERFACE_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    /** Only "pub" is allowed on an impl. */
    public static final Set<String> IMPL_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    public static final Set<String> LIBRARY_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    /** A top-level "let static" has its own decorator allowlist. */
    public static final Set<String> GLOBAL_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    public static final Set<String> LOOP_DECORATORS = new HashSet<>(Arrays.asList("par", "unroll"));
    public static final Set<String> READS_WRITES_ARGS = new HashSet<>(Arrays.asList("all", "self", "others", "globals"));

    public static String pureKind(boolean rt) {
        return rt ? "@pure(rt)" : "@pure";
    }

    /** Decorators an extern accepts (no other decorator has C-linkage meaning). */
    public static final Set<String> EXTERN_DECORATORS = new HashSet<>(Arrays.asList("call_convention", "link_name"));

    public static final class ExternParamParseResult {
        public final List<TypeInfo> fixedTypes;
        public final boolean hasVarargs;
        public ExternParamParseResult(List<TypeInfo> fixedTypes, boolean hasVarargs) {
            this.fixedTypes = fixedTypes;
            this.hasVarargs = hasVarargs;
        }
    }

    public static final Set<String> FUNC_ALL_DECORATORS;
    static {
        FUNC_ALL_DECORATORS = new HashSet<>(FUNC_BASE_DECORATORS);
        FUNC_ALL_DECORATORS.addAll(Arrays.asList("default", "overrides", "realizes"));
    }

    /** @async functions take at most one parameter, and it must fit one machine register (no ranges, no structs). See docs/TypeChecker.md, "Decorator signature checks". */
    public static final Set<String> ASYNC_PARAM_ALLOWED_BASE_TYPES = new HashSet<>(Arrays.asList(
            "u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "bool", "char"));

    // ---- name:type parsing (shared by struct members and func params) ---

    public static class NameType {
        public final String name;
        public final TypeInfo type;
        /** True only for a struct member declared with `swap`. */
        public final boolean isSwap;
        public NameType(String name, TypeInfo type, boolean isSwap) { this.name = name; this.type = type; this.isSwap = isSwap; }
    }

    // ---- pass 2: check every function body ---------------------------

    /** One recorded function call, tagged with the call *site's* effective safety context (from Scope.currentSafety at the moment it was checked). */
    public static class CallEdge {
        public final FuncInfo caller;
        public final FuncInfo callee;
        public final String safety;
        public final Token at;
        /** True only when this exact call site sits inside a "for i in dynarrayExpr{...}" loop's own body -- see Scope.insideDynArrayForLoopBody's own comment, and checkDynArrayForLoopResizeSafety below. */
        public final boolean insideDynArrayForLoop;
        public CallEdge(FuncInfo caller, FuncInfo callee, String safety, Token at, boolean insideDynArrayForLoop) {
            this.caller = caller;
            this.callee = callee;
            this.safety = safety;
            this.at = at;
            this.insideDynArrayForLoop = insideDynArrayForLoop;
        }
    }

    public final List<CallEdge> callEdges = new ArrayList<>();
    /** One validated `throw` statement; read by validateNoThrowReachableFromGhostTableFunctions. */
    public static class ThrowSite {
        public final FuncInfo func;
        public final Token at;
        public ThrowSite(FuncInfo func, Token at) {
            this.func = func;
            this.at = at;
        }
    }
    public final List<ThrowSite> throwSites = new ArrayList<>();
    /** Functions calling `resize` directly: the seed of checkDynArrayForLoopResizeSafety's reachability walk. */
    public final Set<FuncInfo> funcsCallingResizeDirectly = new HashSet<>();

    /** Per function being checked: `par` handle locals (name -> declaring token), names a `match @lock` was taken on, and owns slots ever moved (must-match rule for locked `par` handles). */
    public Map<String, Token> fnHandleDecls = new LinkedHashMap<>();
    public Set<String> fnLockMatched = new HashSet<>();
    public Set<String> fnEverMoved = new HashSet<>();


    /** Slots moved on only SOME paths into a merge point: still "moved" for use-after-move, but their scope-exit destruct is kept (null-safe). See docs/TypeChecker.md, "Branch merge and partly-moved slots". */
    public final Set<String> partlyMovedSlots = new HashSet<>();

    /** True while the condition of an 'assume match' is being checked: a proof there is an assertion to the checker that never runs, so it may name an arbitrary expression, not only a variable. */
    public boolean assumeKeyMode = false;

    /** > 0 while the operands of a `new` / `dyn` are being checked: an owner moved in there is freed if the allocation fails, so it ends alive proofs. */
    public int newDynDepth = 0;

    /** `v.f`, `v.f[i].g` ...: the variable v when `t` is a member/index path rooted at a bare variable (at least one step), else null. */
    public static String memberPathRoot(Token t) {
        Token cur = t;
        int steps = 0;
        while (cur != null && cur.type == TokenType.OPERATOR && (".".equals(cur.text) || "LOOKUP".equals(cur.text)) && cur.left != null) {
            cur = cur.left;
            steps++;
        }
        return steps > 0 && cur != null && cur.type == TokenType.VARREF ? cur.text : null;
    }

    /** The bare variable on the left of the assignment being checked: assigning to it is not a read of it. */
    public Token assignTargetVar = null;

    /** What `substituteDefaultLockMatchPolicyIfPresent` returns: the pre-loop `let` assigns (Token.preLoopInit) and the Scope they were declared in. */
    public static class DefaultPolicySubstitution {
        public final List<Token> preLoopInit;
        public final Scope preLoopScope;
        /** Most retries of the spin: > 0 a literal limit, -1 a run-time value (0 = not known). */
        public long spinBound;
        public DefaultPolicySubstitution(List<Token> preLoopInit, Scope preLoopScope) {
            this.preLoopInit = preLoopInit;
            this.preLoopScope = preLoopScope;
        }
        public DefaultPolicySubstitution withBound(long b) {
            this.spinBound = b;
            return this;
        }
    }

    /** The value of an integer literal token (underscores, 0x/0b allowed), or -1 when it is not a literal or does not fit. */
    public static long literalLimit(Token t) {
        if (t == null || t.type != TokenType.INTEGER) {
            return -1;
        }
        try {
            String s = t.text.replace("_", "").toLowerCase();
            long v = s.startsWith("0b") ? Long.parseLong(s.substring(2), 2) : Long.decode(s);
            return v < 0 ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The attempt limit used when a registered `impl default match @lock` policy has no u64 `limit` parameter. */
    public static final long LOCK_SPIN_LIMIT = 1_000_000_000L;

    /**
     * Non-fatal diagnostic: records "[warning] file:line - message" in {@link #WARNINGS} (the caller reports them) and checking continues.
     */
    public static final java.util.List<String> WARNINGS = new java.util.ArrayList<>();

    public static void warn(String file, int line, String message) {
        StringBuilder sb = new StringBuilder("[warning] ");
        if (file != null) {
            sb.append(file);
            if (line > 0) {
                sb.append(":").append(line);
            }
            sb.append(" - ");
        }
        sb.append(message);
        WARNINGS.add(sb.toString());
    }

    /** Names the integer types that can hold `v`, for "no overload matches" messages about a literal argument. */
    public static String fittingTypes(java.math.BigInteger v) {
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"u8", "s8", "u16", "s16", "u32", "s32", "u64", "s64"}) {
            if (literalFits(v, name)) {
                sb.append(sb.length() == 0 ? "" : ", ").append(name);
            }
        }
        return sb.length() == 0 ? "no integer type" : sb.toString();
    }

    /** Catch parameters of the catch bodies being checked (name -> the try token that binds it). */
    public final Map<String, Token> activeCatchTries = new HashMap<>();
    /** The operand of the `throw` being checked (a bare catch parameter there is a re-throw, not a use of the message). */
    public Token rethrowOperand = null;

    /** Base types backed by a real atomic CPU instruction: integers, bool, char. */
    public static final Set<String> ATOMIC_ELIGIBLE_BASE_TYPES = new HashSet<>(Arrays.asList(
            "u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "bool", "char"));

    /** Memoization guard for ensureAsyncStateEnum. */
    public boolean asyncStateEnumEnsured;

    public boolean asyncGateEnumEnsured;

    /** One AsyncHandle struct/impl per distinct (result, param, locked) key. */
    public final Map<String, TypeInfo> asyncHandleTypesByResultCanonical = new HashMap<>();

    /** True while the body of a compiler-written par/await glue function (trampoline, drop hook) is being checked: such code is trusted, so it may touch a locked handle's members outside `match @lock` and retry the writer's lock without a bound. */
    public boolean synthesizingGlueBody;

    /** Memoizes which "@async" functions already have a synthesized trampoline (one per function, never per call site or per handle type -- see synthesizeAsyncTrampoline's own doc comment). */
    public final Set<String> asyncTrampolinesSynthesized = new HashSet<>();

    public final Set<String> asyncDropHooksSynthesized = new HashSet<>();

    /** Smallest and largest value an integer base type ("u8".."s64") holds, or null for a non-integer type. */
    public static java.math.BigInteger[] integerBounds(String baseType) {
        Integer bits = INTEGER_WIDTH.get(baseType);
        if (bits == null) {
            return null;
        }
        java.math.BigInteger one = java.math.BigInteger.ONE;
        if (baseType.charAt(0) == 'u') {
            return new java.math.BigInteger[]{java.math.BigInteger.ZERO, one.shiftLeft(bits).subtract(one)};
        }
        return new java.math.BigInteger[]{one.shiftLeft(bits - 1).negate(), one.shiftLeft(bits - 1).subtract(one)};
    }

    /** True when an integer literal of value `v` fits `baseType` exactly. */
    public static boolean literalFits(java.math.BigInteger v, String baseType) {
        java.math.BigInteger[] b = integerBounds(baseType);
        return b != null && v.compareTo(b[0]) >= 0 && v.compareTo(b[1]) <= 0;
    }

    public static String unsignedCounterpart(String signedBase) {
        for (Map.Entry<String, String> e : UNSIGNED_TO_SIGNED.entrySet()) {
            if (e.getValue().equals(signedBase)) {
                return e.getKey();
            }
        }
        return signedBase;
    }

    /** True for a 'raw' pointer value -- the only type C-style pointer arithmetic (scaled by the pointee size) applies to. */
    public static boolean isRawPointerType(TypeInfo t) {
        return t != null && "raw".equals(t.storage) && t.funcPtrParamTypes == null;
    }

    /** For ProofKills.check: every function the program defines, and the link names of its externs. */
    public void checkProofKills() {
        List<FuncInfo> all = new ArrayList<>();
        for (List<FuncInfo> l : functions.values()) {
            all.addAll(l);
        }
        for (List<ImplInfo> l : implsByConcreteType.values()) {
            for (ImplInfo impl : l) {
                all.addAll(impl.methods.values());
                all.addAll(impl.staticMethods.values());
                all.addAll(impl.genericMethodInstances.values());
            }
        }
        for (LibraryInfo lib : libraries.values()) {
            for (List<FuncInfo> l : lib.ownFunctions.values()) {
                all.addAll(l);
            }
        }
        Set<String> ext = new HashSet<>();
        for (ExternInfo e : externs.values()) {
            ext.add(e.linkName);
            ext.add(e.name);
        }
        ProofKills.check((TypeChecker) this, all, ext);
    }

    /** For BytecodeEmitter: the loaded compiler.config this whole compilation is using. */
    public CompilerConfig getConfig() { return config; }

    public boolean isFloatStateName(String text) {
        return FLOAT_STATE_NAMES.contains(text);
    }

    public List<SplitInfo> unsafeSplitInfo() {
        List<SplitInfo> out = new ArrayList<>();
        for (UnsafeBlockRecord rec : unsafeBlockRecords.values()) {
            out.add(new SplitInfo(rec.tok.file, rec.tok.line, rec.declared, rec.childLine, rec.childAssume, rec.childTags, rec.unattributed));
        }
        return out;
    }

    /** For `--audit`: what every checked statement-level `unsafe` block really needed, keyed "absolute file:line" (unioned over generic instantiations). */
    public Map<String, java.util.Set<String>> unsafeTagsNeeded() {
        Map<String, java.util.Set<String>> out = new java.util.HashMap<>();
        for (UnsafeBlockRecord rec : unsafeBlockRecords.values()) {
            String k = java.nio.file.Paths.get(rec.tok.file).toAbsolutePath().normalize() + ":" + rec.tok.line;
            out.computeIfAbsent(k, x -> new java.util.TreeSet<>()).addAll(rec.used);
        }
        return out;
    }

    /** True when a value of type `t` holds memory it must free: an `owns` pointer, a dynarray, or an inline struct with such a member. */
    public boolean typeOwnsMemory(TypeInfo t, Set<String> visiting) {
        if (t == null) {
            return false;
        }
        if ("owns".equals(t.storage)) {
            return true;
        }
        String elemBase = t.storage == null ? stripArrayDims(t.baseType) : t.baseType;
        StructInfo si = structs.get(elemBase);
        if (si == null || !visiting.add(elemBase)) {
            return false;
        }
        for (TypeInfo m : si.members.values()) {
            if (typeOwnsMemory(m, visiting)) {
                return true;
            }
        }
        return false;
    }
}

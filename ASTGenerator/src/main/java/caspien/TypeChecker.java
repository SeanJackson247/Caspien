package caspien;

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
public class TypeChecker {

    /** The loaded compiler.config (required): validates @call_convention names, supplies the default convention, and is handed to BytecodeEmitter via getConfig. */
    private final CompilerConfig config;

    /** `unsafe` reasons that cannot free memory by themselves; a block with only these keeps the alive proofs (see the 'unsafe' statement case). */
    private static final Set<String> NON_FREEING_UNSAFE_TAGS = new HashSet<>(Arrays.asList(
            "memcopy", "raw", "deref", "clone", "global", "loop", "atomic", "assume"));

    public TypeChecker(CompilerConfig config) {
        this.config = config;
        ProofKills.reset();
    }

    /** For ProofKills.check: every function the program defines, and the link names of its externs. */
    void checkProofKills() {
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
        ProofKills.check(this, all, ext);
    }

    /** For BytecodeEmitter: the loaded compiler.config this whole compilation is using. */
    CompilerConfig getConfig() { return config; }

    private static final Set<String> NUMERIC_TYPES = new HashSet<>(Arrays.asList(
            "u64", "s64", "u32", "s32", "u16", "s16", "u8", "s8", "f32", "f64"));
    /** The two IEEE-754 float types: f32 (4 bytes) and f64 (8 bytes). */
    private static final Set<String> FLOAT_TYPES = new HashSet<>(Arrays.asList("f32", "f64"));
    private static boolean isFloatType(String baseType) {
        return baseType != null && FLOAT_TYPES.contains(baseType);
    }
    private static final Set<String> INTEGER_TYPES = new HashSet<>(Arrays.asList(
            "u64", "s64", "u32", "s32", "u16", "s16", "u8", "s8"));
    /** Reserved builtin names; a user function may not shadow them. */
    private static final Set<String> BUILTIN_NAMES = new HashSet<>(Arrays.asList(
            "sizeof", "len", "range", "deref", "clone", "call",
            "bits_left", "bits_right", "bits_rotl", "bits_rotr", "bits_or", "bits_and", "bits_xor", "bits_not", "memcopy", "dyn", "resize", "Some",
            "insecure_rand", "wrap", "sat"
    ));
    /** Float state modifiers: contextual words (type annotation before f32, match-on-f32 case labels), not lexer keywords. */
    private static final Set<String> FLOAT_STATE_NAMES = new HashSet<>(Arrays.asList("finite", "infinite", "nan"));

    private boolean isFloatStateName(String text) {
        return FLOAT_STATE_NAMES.contains(text);
    }
    /** Byte size per primitive, used only by 'sizeof'; bool and char are assumed 1 byte. See docs/TypeChecker.md, "Built-in names and sizes". */
    private static final Map<String, Long> PRIMITIVE_SIZE = new HashMap<>();
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
    private static final long POINTER_SIZE = 8L;
    /** Types that must carry a storage modifier (owns/ref/raw/auto/static) wherever explicitly annotated. */
    private final Set<String> pointerLikeBaseTypes = new HashSet<>(Arrays.asList("string"));
    /** Unary minus on an unsigned type promotes to the matching signed type -- u64 can't represent -2. */
    private static final Map<String, String> UNSIGNED_TO_SIGNED = new HashMap<>();
    static {
        UNSIGNED_TO_SIGNED.put("u64", "s64");
        UNSIGNED_TO_SIGNED.put("u32", "s32");
        UNSIGNED_TO_SIGNED.put("u16", "s16");
        UNSIGNED_TO_SIGNED.put("u8", "s8");
    }
    /** Bit width per integer type, used by 'as' to enforce widening-only casts within one signedness family. */
    private static final Map<String, Integer> INTEGER_WIDTH = new HashMap<>();
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
    static class TypeInfo {
        final String storage;     // nullable
        final String mutability;  // "mut"/"imut", or null for the "null" literal type
        final String baseType;    // always the *real*, fully-resolved representation -- never an alias name
        /** Alias name used at this reference (nullable): a nominal tag for typesCompatible/sameSignature only; structural checks use baseType. */
        final String aliasName;
        /** Function-pointer parts, kept as real objects (decorators are not in baseType; typesCompatible compares them as a subset). See docs/TypeChecker.md, "Type model". */
        final List<TypeInfo> funcPtrParamTypes;
        final TypeInfo funcPtrReturnType;
        final Set<String> funcPtrDecorators;
        /** Resolved calling convention of a function-pointer type; INVOKE reads it from here. Null on every other TypeInfo. */
        final String funcPtrCallConvention;
        /** Set only for a safe "dynarray(T)": element type kept as a field instead of re-parsing baseType. See docs/TypeChecker.md, "Type model". */
        final TypeInfo dynArrayElementType;
        /** Set only for "unsafe dynarray(T)"; a separate field so isDynArrayType() excludes it. See docs/TypeChecker.md, "Type model". */
        final TypeInfo unsafeDynArrayElementType;
        /** Set only for a fixed array built from a real value (checkArrayLiteral): the element type with its storage, for checkLookup. See docs/TypeChecker.md, "Type model". */
        final TypeInfo arrayElementType;
        /** Set only for a "raw" pointer whose target is itself a full type ("raw mut raw mut char"). See docs/TypeChecker.md, "Type model". */
        final TypeInfo rawPointeeType;
        /** True when annotated "some" (non-null); meaningful with owns/ref/raw/static storage. Set via withSome(). */
        final boolean isSome;
        /** Subset of {finite, infinite, nan} for an f32/f64; null = unconstrained. A TreeSet so the canonical form is alphabetical. See docs/TypeChecker.md, "Type model". */
        final java.util.TreeSet<String> floatStates;
        /** Parameter/struct-member "const"; not part of baseType or canonical() (checked at assignment sites only). */
        final boolean isConst;
        /** Exact value of an integer literal (or negated/mut-wrapped one), else null; lets the literal adapt to a narrower or signed slot. Not part of type identity. See docs/TypeChecker.md, "Type model". */
        java.math.BigInteger literalValue = null;
        /** True on a bare float literal: adapts to an f32 or f64 slot, f32 when unconstrained. */
        boolean floatLiteral = false;
        /** An array literal whose elements are all float literals: adapts to an `f32[n]`/`f64[n]` slot as a whole. */
        boolean floatArrayLiteral = false;
        /** On an array literal whose elements are all integer literals: their values, so the literal can adapt to a `T[n]` slot. */
        java.util.List<java.math.BigInteger> literalElements = null;
        /** True for a type built by the "atomic" prefix (checkAtomicWrapper); embedded in canonical() but ignored by type compatibility. */
        final boolean isAtomic;

        TypeInfo(String storage, String mutability, String baseType) {
            this(storage, mutability, baseType, null);
        }

        TypeInfo(String storage, String mutability, String baseType, String aliasName) {
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
        static TypeInfo funcPointer(String mutability, List<TypeInfo> paramTypes, TypeInfo returnType,
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
        private static String dynArrayElementText(TypeInfo elementType) {
            return elementType.storage != null ? elementType.storage + "_" + elementType.baseType
                    : elementType.baseType;
        }

        /** Storage keywords `dynArrayElementText` ever prepends -- kept as one list so parsing it back apart (below) can never drift out of sync with producing it. */
        private static final String[] DYN_ARRAY_ELEMENT_STORAGE_KEYWORDS = {"owns", "ref", "raw", "static", "auto"};

        /** Inverse of dynArrayElementText, for dynarray element text that is only available as a baseType string. */
        private static TypeInfo parseDynArrayElementText(String text) {
            for (String storage : DYN_ARRAY_ELEMENT_STORAGE_KEYWORDS) {
                if (text.startsWith(storage + "_")) {
                    return new TypeInfo(storage, "indeterminate", text.substring(storage.length() + 1));
                }
            }
            return new TypeInfo(null, "indeterminate", text);
        }

        /** Builds a "dynarray(T)" type; always owns storage. */
        static TypeInfo dynArray(String mutability, TypeInfo elementType) {
            String baseType = "dynarray(" + dynArrayElementText(elementType) + ")";
            return new TypeInfo("owns", mutability, baseType, null,
                    null, null, null, null, elementType, null);
        }

        /** Builds a fixed-array literal type "elemType[N]"; baseType embeds only the element base type. */
        static TypeInfo array(TypeInfo elementType, int length) {
            String baseType = elementType.baseType + "[" + length + "]";
            return new TypeInfo(null, "indeterminate", baseType, null,
                    null, null, null, null, null, elementType, null);
        }

        /** Builds a recursive "raw" pointer type; baseType embeds the pointee's full canonical() (its mutability is part of the identity). */
        static TypeInfo rawPointer(String mutability, TypeInfo pointeeType) {
            return new TypeInfo("raw", mutability, pointeeType.canonical(), null,
                    null, null, null, null, null, null, pointeeType);
        }

        /** Builds an "unsafe dynarray(T)" type; its distinct baseType keeps it non-coercible with the safe dynarray. */
        static TypeInfo unsafeDynArray(String mutability, TypeInfo elementType) {
            String baseType = "unsafe_dynarray(" + dynArrayElementText(elementType) + ")";
            return new TypeInfo("owns", mutability, baseType, null,
                    null, null, null, null, null, elementType, null, null, false, null, false, false);
        }

        private TypeInfo(String storage, String mutability, String baseType, String aliasName,
                List<TypeInfo> funcPtrParamTypes, TypeInfo funcPtrReturnType, Set<String> funcPtrDecorators,
                String funcPtrCallConvention, TypeInfo dynArrayElementType, TypeInfo rawPointeeType) {
            this(storage, mutability, baseType, aliasName, funcPtrParamTypes,
                    funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention, dynArrayElementType, null,
                    rawPointeeType, false, null, false);
        }

        private TypeInfo(String storage, String mutability, String baseType, String aliasName,
                List<TypeInfo> funcPtrParamTypes, TypeInfo funcPtrReturnType, Set<String> funcPtrDecorators,
                String funcPtrCallConvention, TypeInfo dynArrayElementType, TypeInfo arrayElementType,
                TypeInfo rawPointeeType) {
            this(storage, mutability, baseType, aliasName, funcPtrParamTypes,
                    funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention, dynArrayElementType,
                    arrayElementType, rawPointeeType, false, null, false);
        }

        private TypeInfo(String storage, String mutability, String baseType, String aliasName,
                List<TypeInfo> funcPtrParamTypes, TypeInfo funcPtrReturnType, Set<String> funcPtrDecorators,
                String funcPtrCallConvention, TypeInfo dynArrayElementType, TypeInfo arrayElementType,
                TypeInfo rawPointeeType, boolean isSome, java.util.TreeSet<String> floatStates, boolean isConst) {
            this(storage, mutability, baseType, aliasName, funcPtrParamTypes,
                    funcPtrReturnType, funcPtrDecorators, funcPtrCallConvention, dynArrayElementType,
                    null, arrayElementType, rawPointeeType, isSome, floatStates, isConst, false);
        }

        /** The full constructor all others delegate to. */
        private TypeInfo(String storage, String mutability, String baseType, String aliasName,
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
        TypeInfo withSome(boolean newIsSome) {
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
        TypeInfo withConst(boolean newIsConst) {
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
        TypeInfo withFloatStates(java.util.TreeSet<String> newFloatStates) {
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
        TypeInfo withAtomic(boolean newIsAtomic) {
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
        String canonical() {
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

        boolean sameBaseType(TypeInfo other) {
            return other != null && baseType.equals(other.baseType);
        }

        /** Copy with a different mutability, every other field kept (the one place "mut X"/"imut X" wrapping is implemented). */
        TypeInfo withMutability(String newMutability) {
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
        TypeInfo withStorage(String newStorage) {
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

    static class StructInfo {
        final String name;
        /** Final member set (a struct has no ancestors). Populated by the flatten pass -- empty/partial before it runs. */
        final LinkedHashMap<String, TypeInfo> members = new LinkedHashMap<>();
        /** Declared interface conformance ("implements A,B") -- a compile-time contract, checked once all impls are collected. */
        List<String> implementsNames = new ArrayList<>();
        /** Own (non-"extends") member declaration lines, kept until the flatten pass consumes them. */
        List<Token> rawMemberLines;
        /** Memoization/cycle-detection marker for the flatten pass. */
        boolean flattened;
        Token declTok;
        /** Gates the struct itself: a private struct is not nameable outside its declaring file. See docs/TypeChecker.md, "Visibility". */
        boolean isPublic;
        /** Members marked public through a @pub{} block; all others are private. Meaningful only when `isPublic`. */
        final Set<String> publicMembers = new HashSet<>();
        /** Each member's original declaring file, for the private-member file-locality check. */
        final Map<String, String> memberDeclFile = new HashMap<>();
        /** For "@lock(match self.x : X)" on a struct: `lockFieldName` is x (always freely readable), `lockVariants` the |-chained variants. */
        String lockFieldName;
        List<String> lockVariants;
        /** Name of the struct's single 'swap'-tagged mutex member (a mut atomic OPEN/CLOSED enum), validated by validateSwapMutexField; must equal `lockFieldName` when both are set. */
        String swapFieldName;
        /** The struct's "impl default match @lock" policy template (at most one). Only deep-cloned copies per use site are checked. See DefaultLockMatchPolicy. */
        DefaultLockMatchPolicy defaultLockMatchPolicy;
        /** Class id in the flat `Class` enum (generateClassHierarchyEnums); null for an @untyped struct. Populates the hidden "___type" field. */
        Long classId;
        StructInfo(String name) { this.name = name; }
    }

    /**
     * "impl default match @lock" policy template for a match @lock CLOSED case ("CLOSED:default" / "CLOSED:default(args)").
     * Kept as raw tokens, never checked itself; a fresh clone is checked per use site. See docs/TypeChecker.md, "Default lock-match policy".
     */
    static class DefaultLockMatchPolicy {
        /** The struct this policy is declared for (StructInfo.defaultLockMatchPolicy's own owner). */
        final String structName;
        /** Raw "(" token of the parameter list; the first parameter is the automatic per-iteration "tries" counter, never supplied by the call site. */
        final Token rawParamsTok;
        /** Raw body lines, exactly the parser's `blockTok.childs`; never run through gatherKeywordBlocks/RPN/tree-build. */
        final List<Token> rawBodyLines;
        final Token declTok;
        DefaultLockMatchPolicy(String structName, Token rawParamsTok, List<Token> rawBodyLines, Token declTok) {
            this.structName = structName;
            this.rawParamsTok = rawParamsTok;
            this.rawBodyLines = rawBodyLines;
            this.declTok = declTok;
        }
    }

    static class EnumInfo {
        final String name;
        final List<String> variants = new ArrayList<>();
        /** Decorated @non_exhaustive: the enum must end with a `default` case. */
        boolean isNonExhaustive;
        /** Explicit integer discriminants; null unless every variant has one (no partial mixing). Also the test for a "guaranteed" enum (checkGuaranteedEnumMembership). */
        List<Long> explicitValues;
        /** Explicit [start, end] range per variant for a range-valued enum; null otherwise, exclusive with `explicitValues`. */
        List<Long> explicitRangeStarts;
        List<Long> explicitRangeEnds;
        /** True when this enum is range-valued. */
        boolean isRangeValued() { return explicitRangeStarts != null; }
        EnumInfo(String name) { this.name = name; }
    }

    static class FuncInfo {
        String name;
        /** Defaults to `name`; overridden for impl methods. */
        String mangledName;
        final List<String> paramNames = new ArrayList<>();
        final List<TypeInfo> paramTypes = new ArrayList<>();
        TypeInfo returnType;
        Token funcToken;
        /** Root-list position (impl method: its impl's), for the globals "referenced before declaration" check; MAX_VALUE = no restriction. */
        int declOrder = Integer.MAX_VALUE;
        /** "unsafe"/"safe"/"indeterminate" from the enclosing wrapper block; never null. */
        String safetyTag = "indeterminate";
        /** Marked by "export foo": BytecodeEmitter emits an EXPORT line. Never true for impl methods. */
        boolean isExported = false;
        /** Resolved calling convention (decorator or config default); read for every CALL/RECURSIVE_CALL. */
        String callConvention;
        /** @async: invoked only through par/await, and par/await only accept @async functions (checkCall enforces both directions). */
        boolean isAsync;
        /** @throws: required, and only allowed, on a function whose body throws; every call needs try/catch (requireThrowsWrapping). */
        boolean isThrows;
        /** Set when a 'throw' is checked in this body; checkFunctionBody uses it to reject an unneeded @throws. */
        boolean sawThrow;
        /** @pub; for impl methods also gated by the enclosing ImplInfo's `isPublic`. See docs/TypeChecker.md, "Visibility". */
        boolean isPublic;
        /** Null for a plain top-level func; else the impl this method belongs to. */
        ImplInfo enclosingImpl;
        /** Null unless a library function: the library that declared it (for requireLibraryVisibility). */
        LibraryInfo enclosingLibrary;
        /** Count of bare loop{} statements already exempted in an @event_loop function (only the first is). */
        int eventLoopSafeLoopCount;
        /** Every owns-typed local of this function, across all scopes, so BytecodeEmitter can null a slot after its scope-end GT_DESTRUCT (see emitDestructList). */
        Map<String, TypeInfo> ownsLocalTypes = new LinkedHashMap<>();
        /** Argument shape of the real 'main': null, "c_args" or "safe_args" (validateMainFunction). Read by validateTickEventLoop and BytecodeEmitter. */
        String mainArgShape;
        /** True for "impl constructor for Name(...)" (see collectFunc). */
        boolean isConstructor;
        /** For a constructor: the struct it builds; read by checkInstantiate's OTC gate and checkNew/checkCall errors. */
        String constructorConcreteName;
    }

    /** "extern name(types...) ret": a signature the linked assembly provides. No body, no mangling, no overloading; globally unique by name. */
    static class ExternInfo {
        String name;
        /** Literal link symbol: "@link_name(x)" if present, else `name`. */
        String linkName;
        /** Parameter types only; extern parameters are unnamed. */
        final List<TypeInfo> paramTypes = new ArrayList<>();
        /** Signature ended in '...': extra unchecked arguments allowed at call sites. */
        boolean hasVarargs;
        TypeInfo returnType;
        Token declTok;
        /** Resolved calling convention (decorator or config default). */
        String callConvention;
    }

    /** "ASM name { ... }" or "ASM name \"path\"": opaque assembly; `rawText` is the final content for both forms. */
    static class AsmInfo {
        /** Null for an unnamed declaration, which self-emits at its declaration point. */
        String name;
        String rawText;
        Token declTok;
    }

    /** An interface's raw (unresolved -- 'self' isn't a concrete type yet) method signatures. */
    static class InterfaceInfo {
        String name;
        /** Final, flattened method set (own + every extended interface's, name-collision-checked). Populated by the flatten pass. */
        final LinkedHashMap<String, RawMethodSig> methods = new LinkedHashMap<>();
        List<String> extendsNames = new ArrayList<>();
        /** This interface's own declared methods only, kept until the flatten pass consumes them. */
        LinkedHashMap<String, RawMethodSig> ownMethods;
        boolean flattened;
        Token declTok;
        /** Gates the interface itself; its methods are always public. */
        boolean isPublic;
    }

    static class RawMethodSig {
        Token nameTok;
        Token paramsTok;
        List<Token> returnTypeTokens;
        /** True if this came from a "@default func ...{...}" (a body-bearing method) rather than a plain signature-only declaration. */
        boolean isDefault;
        /** Only for isDefault: the interface's gathered "func" token, deep-cloned for each impl that omits the method (see materializeDefaultMethod). */
        Token defaultBodyTok;
        /** Decorators of the interface method declaration (e.g. @lock/@unlock on @guard interfaces), checked against the implementing method. */
        List<Token.Decorator> decorators;
        /** "static" signature; kept in the same maps as instance signatures (a name clash is still an error), this flag keeps the two kinds apart. */
        boolean isStatic;
    }

    /** "impl InterfaceName for ConcreteName{...}" (interfaceName set) or "impl ConcreteName{...}" (interfaceName null). */
    static class ImplInfo {
        String interfaceName;
        String concreteName;
        final LinkedHashMap<String, FuncInfo> methods = new LinkedHashMap<>();
        /** Static methods, in a separate map so instance/static dispatch stay structurally apart. */
        final LinkedHashMap<String, FuncInfo> staticMethods = new LinkedHashMap<>();
        /**
         * Overload lists by bare name (includes the first method). A second same-named method with a different signature is stored in
         * `methods`/`staticMethods` under the key "name#signature". Only a bare `impl` may overload. Look up through candidates().
         */
        final LinkedHashMap<String, List<FuncInfo>> methodOverloads = new LinkedHashMap<>();
        final LinkedHashMap<String, List<FuncInfo>> staticMethodOverloads = new LinkedHashMap<>();
        /** Every overload of `name` in declaration order; empty if none. */
        List<FuncInfo> candidates(String name, boolean isStatic) {
            List<FuncInfo> list = (isStatic ? staticMethodOverloads : methodOverloads).get(name);
            if (list != null) {
                return list;
            }
            FuncInfo single = (isStatic ? staticMethods : methods).get(name);
            return single == null ? java.util.Collections.<FuncInfo>emptyList() : java.util.Collections.singletonList(single);
        }
        Token declTok;
        /** Outer visibility gate, checked in addition to each method's own isPublic. */
        boolean isPublic;
        /** The impl's safety tag, used when checking a generic method instantiated at a call site. */
        String safetyTag;
        /** Generic methods (own impl-level type params) held back by name; materialized per call site by instantiateGenericMethod. See docs/TypeChecker.md, "Generic impl methods". */
        final LinkedHashMap<String, Token> genericMethodTemplates = new LinkedHashMap<>();
        /** Memoized instantiations of genericMethodTemplates, keyed "method<arg1,arg2>". */
        final LinkedHashMap<String, FuncInfo> genericMethodInstances = new LinkedHashMap<>();
    }

    /**
     * "library Name { ... }": functions dispatched by bare library name ("Math.sin(x)"); no instance, no self.
     * Same privacy rules as an impl; unlimited 'extends'.
     */
    static class LibraryInfo {
        String name;
        List<String> extendsNames = new ArrayList<>();
        /** Own functions only, name to overloads (overloaded by full signature, unlike an impl). */
        LinkedHashMap<String, List<FuncInfo>> ownFunctions;
        /** Final, flattened function set (own + every extended library's, full-signature-collision-checked). Populated by flattenLibrary. */
        final LinkedHashMap<String, List<FuncInfo>> functions = new LinkedHashMap<>();
        boolean flattened;
        Token declTok;
        /** Gates the library itself, not its own functions -- exactly mirrors ImplInfo.isPublic (see requireLibraryVisibility). */
        boolean isPublic;
    }

    /** Marker type giving the try-block Scope constructor a signature distinct from Scope(Scope, boolean, boolean). */
    private enum TryBlockBoundaryMarker { MARK }

    /** The reasons a statement-level `unsafe` block must name (`unsafe deref extern{`); `unaudited` is the catch-all, written alone, and never allowed in the stdlib. See docs/TypeChecker.md, "Unsafe block tags". */
    static final Set<String> UNSAFE_TAGS = new java.util.LinkedHashSet<>(Arrays.asList(
            "extern", "memcopy", "raw", "deref", "clone", "global", "loop", "udyn", "udyn:owns",
            "assume", "call", "asm", "async", "atomic", "file", "unaudited"));

    /** One written `unsafe ...{` block: what it declares, and (unioned over every time it is checked, e.g. per generic instantiation) what it really used. */
    private static class UnsafeBlockRecord {
        final Token tok;
        final Set<String> declared = new java.util.TreeSet<>();
        final Set<String> used = new java.util.TreeSet<>();
        /** For `--fix unsafe-split`: per direct child statement its source line, whether it is a bare `assume match`, and the tags it needed. */
        final List<Integer> childLine = new ArrayList<>();
        final List<Boolean> childAssume = new ArrayList<>();
        final List<Set<String>> childTags = new ArrayList<>();
        /** Tags used while no direct child was being checked (the split cannot place them). */
        final Set<String> unattributed = new java.util.TreeSet<>();
        int cur = -1;
        UnsafeBlockRecord(Token tok) { this.tok = tok; }
        void noteChild(int idx, Token stmt) {
            while (childLine.size() <= idx) {
                childLine.add(0);
                childAssume.add(false);
                childTags.add(new java.util.TreeSet<>());
            }
            childLine.set(idx, stmt.line);
            childAssume.set(idx, stmt.type == TokenType.KEYWORD && stmt.text.equals("assume") && !stmt.hasBlock);
            cur = idx;
        }
        void use(String tag) {
            used.add(tag);
            if (cur >= 0) {
                childTags.get(cur).add(tag);
            } else {
                unattributed.add(tag);
            }
        }
    }

    /** What `--fix unsafe-split` needs about one written statement-level `unsafe` block. */
    static final class SplitInfo {
        final String file;
        final int line;
        final Set<String> declared;
        final List<Integer> childLine;
        final List<Boolean> childAssume;
        final List<Set<String>> childTags;
        final Set<String> unattributed;
        SplitInfo(String file, int line, Set<String> declared, List<Integer> childLine, List<Boolean> childAssume, List<Set<String>> childTags, Set<String> unattributed) {
            this.file = file; this.line = line; this.declared = declared; this.childLine = childLine; this.childAssume = childAssume;
            this.childTags = childTags; this.unattributed = unattributed;
        }
    }

    List<SplitInfo> unsafeSplitInfo() {
        List<SplitInfo> out = new ArrayList<>();
        for (UnsafeBlockRecord rec : unsafeBlockRecords.values()) {
            out.add(new SplitInfo(rec.tok.file, rec.tok.line, rec.declared, rec.childLine, rec.childAssume, rec.childTags, rec.unattributed));
        }
        return out;
    }

    private final Map<String, UnsafeBlockRecord> unsafeBlockRecords = new java.util.LinkedHashMap<>();

    /** For `--audit`: what every checked statement-level `unsafe` block really needed, keyed "absolute file:line" (unioned over generic instantiations). */
    Map<String, java.util.Set<String>> unsafeTagsNeeded() {
        Map<String, java.util.Set<String>> out = new java.util.HashMap<>();
        for (UnsafeBlockRecord rec : unsafeBlockRecords.values()) {
            String k = java.nio.file.Paths.get(rec.tok.file).toAbsolutePath().normalize() + ":" + rec.tok.line;
            out.computeIfAbsent(k, x -> new java.util.TreeSet<>()).addAll(rec.used);
        }
        return out;
    }

    /** True when a value of type `t` holds memory it must free: an `owns` pointer, a dynarray, or an inline struct with such a member. */
    private boolean typeOwnsMemory(TypeInfo t, Set<String> visiting) {
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

    /** Struct name a `ref` points at, for ProofKills (null when it is not a plain struct: then any destroying event ends the proof). */
    String pointeeOf(TypeInfo t) {
        if (t == null || t.baseType == null) {
            return null;
        }
        String b = stripArrayDims(t.baseType);
        return structs.containsKey(b) ? b : null;
    }

    /** Names of the types an object of type t can contain by ownership (itself, owned members, inline members, transitively); null = unknown. */
    Set<String> destroyedTypes(TypeInfo t) {
        if (t == null || t.baseType == null) {
            return null;
        }
        Set<String> out = new HashSet<>();
        collectDestroyed(t, out);
        return out;
    }

    private void collectDestroyed(TypeInfo t, Set<String> out) {
        if (t == null || t.baseType == null || (t.storage != null && !"owns".equals(t.storage))) {
            return;
        }
        String b = stripArrayDims(t.baseType);
        if (!out.add(b)) {
            return;
        }
        StructInfo si = structs.get(b);
        if (si != null) {
            for (TypeInfo m : si.members.values()) {
                collectDestroyed(m, out);
            }
        }
    }

    /** An inline (no storage keyword) struct value that owns memory through some member: it has to be dropped at scope end and moved, not copied. */
    boolean isInlineOwningStruct(TypeInfo t) {
        if (t == null || t.storage != null || t.baseType == null) {
            return false;
        }
        String elem = stripArrayDims(t.baseType);
        return structs.containsKey(elem) && elementTextOwnsMemory(elem);
    }

    /** `Holder[2][3]` -> `Holder`: a fixed array of inline owning structs is an owned slot too (dropped element by element, moved as a whole). */
    String stripArrayDims(String baseType) {
        while (isArrayType(baseType)) {
            baseType = arrayElementType(baseType);
        }
        return baseType;
    }

    /** True for a fixed array type (`Holder[2]`). */
    boolean isFixedArrayTypeText(String baseType) {
        return baseType != null && isArrayType(baseType);
    }

    /** True when a dynarray element, given as the canonical text in a dynarray type (`Holder`, `owns_some_mut_World`), owns memory. */
    boolean elementTextOwnsMemory(String elemText) {
        if (elemText.startsWith("owns_") || elemText.startsWith("dynarray(") || elemText.startsWith("unsafe_dynarray(")) {
            return true;
        }
        StructInfo si = structs.get(elemText);
        if (si == null) {
            return false;
        }
        Set<String> visiting = new HashSet<>();
        visiting.add(elemText);
        for (TypeInfo m : si.members.values()) {
            if (typeOwnsMemory(m, visiting)) {
                return true;
            }
        }
        return false;
    }

    /** The unsafe tag an operation on this unsafe dynarray needs: `udyn:owns` when its elements own memory (the programmer destructs them by hand), else `udyn`. */
    private String udynTag(TypeInfo unsafeDynArrayType) {
        TypeInfo el = unsafeDynArrayType == null ? null : unsafeDynArrayType.unsafeDynArrayElementType;
        return typeOwnsMemory(el, new HashSet<>()) ? "udyn:owns" : "udyn";
    }

    /** Called where `unsafe` bypasses a rule: true in unsafe context, and records `tag` on the enclosing tagged block. Call only when the bypass is actually exercised. */
    private boolean unsafeBypass(Scope scope, String tag) {
        if (!"unsafe".equals(scope.currentSafety)) {
            return false;
        }
        for (Scope sc = scope; sc != null; sc = sc.parent) {
            if (sc.unsafeBlock != null) {
                sc.unsafeBlock.use(tag);
                break;
            }
        }
        return true;
    }

    private static class Scope {
        final Scope parent;
        final Map<String, TypeInfo> vars = new HashMap<>();
        /** Names declared via 'let static' in this exact scope -- checkAddressOf's 'static' case needs to know this to accept "raw someStaticVar"-style pointers. */
        final Set<String> staticNames = new HashSet<>();
        /** Names declared by 'let const' in this scope (local counterpart of TypeInfo.isConst); see isConstName. */
        final Set<String> constNames = new HashSet<>();
        /** Slot keys made unassignable by a for/for-match loop for its variable and source expression, for the loop body. Unlike constNames, checked along the whole scope chain. */
        Set<String> ownLoopImmutableSlots;
        /** Walks all parent scopes: a loop's immutability grant covers nested blocks. */
        boolean isLoopImmutable(String slotKey) {
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
        final Map<String, AsmInfo> localAsmNames = new HashMap<>();
        /** Slots (variable name or "a.b" path) currently holding a moved-away owns value. Shared across all scopes of one function, since moves are not lexically scoped. */
        final Set<String> movedSlots;
        /** Current safety context ("unsafe"/"safe"/"indeterminate"), inherited and overridable by a nested block. */
        final String currentSafety;
        /** The tagged 'unsafe' block this scope sits in (set on the scope that block opens, found via parents); null outside any statement-level unsafe block. */
        UnsafeBlockRecord unsafeBlock;
        /** Proven MatchPatterns, inherited and extended by entering a match block; never mutated. */
        final List<Token.MatchPattern> activeMatchPatterns;
        /** True inside the base-case match of a @recursive function; checkCall/checkMethodCall reject self-calls there. */
        final boolean suspendsSelfRecursion;
        /** Owns slots declared directly in this scope, in order (per block, not inherited); dropped at scope end. Filled by checkAssign (lets) and checkFunctionBody (params, in the root scope). */
        final List<String> ownsDeclaredHere = new ArrayList<>();
        /** The function's outermost scope (itself at the root); a 'return' destructs owns locals up to and including it. */
        final Scope functionRootScope;
        /** Innermost loop body scope (null outside loops); a 'break' destructs up to and including it. Reset by each nested loop. */
        final Scope loopBoundaryScope;
        /** Innermost "try { }" body scope (null outside); a 'continue' in a catch body destructs/unlocks up to it and jumps past the try block. Reset by each nested try. */
        final Scope tryBlockBoundaryScope;
        /** True inside a "for i in dynarrayExpr" body: the dynarray may not be resized, even through another function. Inherited; can only be turned on. */
        final boolean insideDynArrayForLoopBody;
        /** Owed lock releases (synthesized unlock calls) in acquisition order, inherited and extended by lock/match @lock OPEN bodies; return reads all, break the suffix past the loop boundary. */
        final List<Token> activeLockReleases;
        Scope(Scope parent) {
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
        Scope(Scope parent, Set<String> movedSlotsOverride) {
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
        Scope(Scope parent, String currentSafetyOverride) {
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
        Scope(Scope parent, List<Token.MatchPattern> extendedMatchPatterns, boolean suspendsSelfRecursionOverride) {
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
        Scope(Scope parent, Set<String> movedSlotsOverride, List<Token.MatchPattern> extendedMatchPatterns,
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
        Scope(Scope parent, boolean isNewLoopBoundary) {
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
        Scope(Scope parent, boolean isNewLoopBoundary, boolean isDynArrayForLoopBody) {
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
        Scope(Scope parent, Token extraLockRelease) {
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
        Scope(Scope parent, Set<String> movedSlotsOverride, Token extraLockRelease) {
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
        Scope(Scope parent, TryBlockBoundaryMarker ignoredMarker) {
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

        TypeInfo lookup(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.vars.containsKey(name)) {
                    return s.vars.get(name);
                }
            }
            return null;
        }

        boolean isStatic(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.vars.containsKey(name)) {
                    return s.staticNames.contains(name);
                }
            }
            return false;
        }

        boolean isConstName(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.vars.containsKey(name)) {
                    return s.constNames.contains(name);
                }
            }
            return false;
        }

        /** An `unsafe`/`safe` block is a permission marker, not a lexical scope: what it declares belongs to the nearest enclosing real scope. */
        boolean transparent;

        Scope declScope() {
            Scope s = this;
            while (s.transparent && s.parent != null) {
                s = s.parent;
            }
            return s;
        }

        void declare(String name, TypeInfo type, Token at) {
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

    private final Map<String, StructInfo> structs = new HashMap<>();
    /** Struct names in declaration order (imports included), for generateClassHierarchyEnums; appended only by registerStructLike. */
    private final List<String> structDeclOrder = new ArrayList<>();
    private final Map<String, EnumInfo> enums = new HashMap<>();
    /** The list `check(...)` was given, shared with BytecodeEmitter, so root tokens appended later (par's AsyncHandle_T, see getOrCreateAsyncHandleType) reach emission. */
    private List<Token> rootLinesRef;
    private final Map<String, List<FuncInfo>> functions = new HashMap<>();
    private final Map<String, InterfaceInfo> interfaces = new HashMap<>();
    /** extern name -> signature; collectFunc/collectExtern cross-check so an extern and a function never share a name. */
    private final Map<String, ExternInfo> externs = new HashMap<>();
    /** Top-level func names already claimed by an 'export' (a second export of the name, or of overloads sharing it, is an error). */
    private final Set<String> exportedNames = new HashSet<>();
    /** Named root-level ASM declarations, in their own namespace. Visible from any function body; a root-level "asm_name;" invocation is processed in file order. See collectAsmDeclarationsAndInvocations. */
    private final Map<String, AsmInfo> rootAsms = new HashMap<>();

    /** Local ASM names (scope chain) first, then root-level; null if unknown. */
    private AsmInfo lookupAsmName(String name, Scope scope) {
        for (Scope s = scope; s != null; s = s.parent) {
            AsmInfo info = s.localAsmNames.get(name);
            if (info != null) {
                return info;
            }
        }
        return rootAsms.get(name);
    }
    /** Concrete type name -> every impl block declared for it (at most one bare impl, plus at most one per interface -- enforced at collection time). */
    private final Map<String, List<ImplInfo>> implsByConcreteType = new HashMap<>();
    /** Every generic constructor's FuncInfo, registered by collectImpl into `functions` (so check()'s two body loops miss it); check() runs a third loop over this list. See docs/TypeChecker.md, "Generic constructors". */
    private final List<FuncInfo> genericConstructorFuncInfos = new ArrayList<>();

    /** Thrown by buildFuncInfo for a generic-instance method whose signature is illegal only because of the chosen type argument (by-value struct parameter). */
    private static final class DeferredSignatureException extends CompilerException {
        final String raw;
        DeferredSignatureException(String file, int line, String message) {
            super("type", file, line, message);
            this.raw = message;
        }
    }

    /** Methods/constructors dropped from a generic impl instantiation because their signature is illegal for the type argument, keyed "Concrete.method" or "__ctor__Concrete"; reasons are appended to a later "no match" error (unavailableNote). */
    private final Map<String, List<String>> unavailableGenericMethods = new HashMap<>();

    private void recordUnavailable(String key, String shownName, String concreteName, boolean ctor, DeferredSignatureException e) {
        String msg = (ctor ? "constructor of '" : "method '" + shownName + "' of '") + concreteName
                + "' is unavailable for this type argument: "
                + e.raw;
        unavailableGenericMethods.computeIfAbsent(key, k -> new ArrayList<>()).add(msg);
    }

    private String unavailableNote(String key) {
        List<String> l = unavailableGenericMethods.get(key);
        if (l == null) {
            return "";
        }
        return " (" + String.join("; ", l) + ")";
    }
    /** Library name -> LibraryInfo; shares its namespace with structs/enums/interfaces (registerLibraryName cross-checks). */
    private final Map<String, LibraryInfo> libraries = new LinkedHashMap<>();
    /** Root-level "let static NAME = ..." globals -- name -> resolved type. */
    private final Map<String, TypeInfo> globals = new HashMap<>();
    /** "type NAME ..." aliases: name -> raw target tokens, resolved lazily per use, so an unused alias is never validated. */
    private final Map<String, List<Token>> aliasRawTargets = new HashMap<>();    /** Root-list position each global was declared at, for "cannot be referenced before declaration." */
    private final Map<String, Integer> globalDeclOrder = new HashMap<>();
    /** Declaration order, so BytecodeEmitter emits every 'GLOBAL' line in a stable, deterministic order. */
    private final List<String> globalOrder = new ArrayList<>();
    /** Each global's own initializer expression, kept for BytecodeEmitter to fold into 'GLOBAL' lines later. */
    private final Map<String, Token> globalRhsByName = new HashMap<>();
    /** Public root-level globals (private by default; a local 'let static' needs no entry). The declaring file is in globalRhsByName. */
    private final Set<String> publicGlobals = new HashSet<>();
    /** "const NAME EXPR": pure compile-time substitution; name -> unevaluated expression root, evaluated lazily on first use (consts are forward-declarable). */
    private final Map<String, Token> constExprs = new HashMap<>();
    /** Memoized evaluateConstant result per const. */
    private final Map<String, Token> constResolvedValues = new HashMap<>();
    /** Consts mid-evaluation, for cycle detection. */
    private final Set<String> constsBeingEvaluated = new HashSet<>();
    /** Set per collectDeclarations(): func/impl KEYWORD token (impl methods share the impl's) -> root-list position. */
    private Map<Token, Integer> pendingDeclOrder = new HashMap<>();
    /** Generates unique names for the hidden range-binding variable a for-loop desugars to (see checkForLoop). */
    private int forVarCounter = 0;
    /** Identity of the sole expression of the 'return' being resolved, to tell a bare tail self-call (legal for @recursive) from one nested in a larger expression (requireRecursiveCallReturnSiteOnly). */
    private Token directReturnExprNode;

    /** For BytecodeEmitter: the resolved declarations this checker collected, valid after check() runs. */
    Map<String, StructInfo> getStructs() { return structs; }
    Map<String, EnumInfo> getEnums() { return enums; }
    Map<String, List<FuncInfo>> getFunctions() { return functions; }
    Map<String, InterfaceInfo> getInterfaces() { return interfaces; }
    Map<String, List<ImplInfo>> getImpls() { return implsByConcreteType; }
    Map<String, LibraryInfo> getLibraries() { return libraries; }
    Map<String, ExternInfo> getExterns() { return externs; }
    List<String> getGlobalOrder() { return globalOrder; }
    Map<String, TypeInfo> getGlobals() { return globals; }
    /** Null unless 'main' is '@with_tick': the @event_loop function (set by validateTickEventLoop), which BytecodeEmitter makes the entry point instead of main. */
    private FuncInfo eventLoopFunc;
    FuncInfo getEventLoopFunc() { return eventLoopFunc; }
    Map<String, Token> getGlobalRhs() { return globalRhsByName; }

    /** At most one function per ghost-table decorator (@gt_init/@gt_alive_check/@gt_destruct/@gt_register...), filled by validateGhostTableDecoratorSignatures; BytecodeEmitter requires the function at each emission point. */
    private final Map<String, FuncInfo> ghostTableFunctions = new HashMap<>();

    /** For BytecodeEmitter: the (at most one) function decorated with the given ghost-table decorator name, or null if none exists in this compilation unit. */
    FuncInfo getGhostTableFunction(String decoratorName) { return ghostTableFunctions.get(decoratorName); }

    /** Whole-program, syntactic flag: any owns/ref storage modifier, 'new' or 'dyn(...)' appears anywhere. Read by BytecodeEmitter to require GT_INIT in the entry point. See docs/TypeChecker.md, "Whole-program flags". */
    private boolean usesOwnsRefDynNew = false;
    boolean usesOwnsRefDynNew() { return usesOwnsRefDynNew; }

    /** Whole-program flag: any 'throw' was checked. OR'd with usesOwnsRefDynNew to decide whether each function gets the gt_routine/GT_UNWIND prologue. See docs/TypeChecker.md, "Whole-program flags". */
    private boolean usesThrow = false;
    /** True when the program throws, or when stack tracing is on (it needs the throw-style frame layout in every function). */
    boolean usesThrow() { return usesThrow || TraceConfig.usesTrace; }

    /** True while checking anything under a "try new/dyn/resize ... catch" wrapper; nested allocations then share the one catch. See docs/TypeChecker.md, "Checker context flags". */
    private boolean insideThrowingAllocContext = false;

    /** True while checking a "catch(e){ }" body, the only place `continue` exits the try block. Save/restored by checkTry. */
    private boolean insideCatchBody = false;
    /** Innermost loop boundary when the catch body being checked started: a `continue` still targeting it belongs to the catch (skips the try block); one in a loop opened inside the catch belongs to that loop. */
    private Scope catchEnclosingLoopBoundary = null;
    /** Loop-body scopes of user-written `for`/`loop` statements (not the synthesized `match @lock` spin loop): the only scopes a loop `continue` may target. */
    private final Set<Scope> userLoopScopes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Spin-loop scope of the match @lock CLOSED case being checked (null elsewhere, and for OPEN): a `continue` directly inside retries the acquire. */
    private Scope lockClosedLoopScope = null;
    private int lockRetryContinuesSeen = 0;      // every `continue` that retries a `match @lock` acquire

    /** True only while `closedCaseTerminates` runs: makes `statementDefinitelyReturns` count a 'break' as a terminator. */
    private boolean breakCountsAsTerminator = false;

    /** Does every path through a 'match @lock' CLOSED body end in continue / return / throw / break? */
    private boolean closedCaseTerminates(List<Token> lines) {
        boolean saved = breakCountsAsTerminator;
        breakCountsAsTerminator = true;
        try {
            return definitelyReturns(lines);
        } finally {
            breakCountsAsTerminator = saved;
        }
    }

    /** Single-shot: true only for the next resolveExprType call, which may resolve a top-level `&&then`/`||then` (from an if/elseif condition root or through other then-nodes only). See docs/TypeChecker.md, "Checker context flags". */
    private boolean insideShortCircuitEligiblePosition = false;

    /** Single-shot, like insideShortCircuitEligiblePosition: true only where a struct-returning call (RVO) is legal, i.e. a plain let RHS or a bare-variable '=' RHS in checkAssign; not a return. See docs/TypeChecker.md, "Checker context flags". */
    private boolean insideStructRvoDestinationPosition = false;

    /** Name of the struct whose constructor body is being checked, else null. checkInstantiate's OTC gate: once a struct has a constructor, its bare literal is legal only inside it. */
    private String insideConstructorBodyForStruct = null;

    /** True once "structName" has at least one declared constructor. */
    private boolean hasConstructors(String structName) {
        return functions.containsKey(Parser.CONSTRUCTOR_NAME_PREFIX + structName);
    }

    /** One root-level declaration line, tagged with its enclosing unsafe/safe context (if any). */
    private static class FlatDecl {
        final Token lineTok;
        final String safety;
        FlatDecl(Token lineTok, String safety) {
            this.lineTok = lineTok;
            this.safety = safety;
        }
    }

    /**
     * Flattens root lines through unsafe/safe wrappers into declarations tagged with their safety context;
     * an 'unsafe' nested in 'safe' is an error (the reverse is allowed).
     */
    private List<FlatDecl> flattenRootLines(List<Token> lines, String currentSafety) {
        List<FlatDecl> out = new ArrayList<>();
        for (Token lineTok : lines) {
            Token t = lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && (t.text.equals("unsafe") || t.text.equals("safe"))) {
                if (t.text.equals("unsafe") && currentSafety.equals("safe")) {
                    throw new CompilerException("type", t.file, t.line,
                            "'unsafe' cannot be nested inside 'safe'");
                }
                out.addAll(flattenRootLines(t.childs, t.text));
            } else {
                out.add(new FlatDecl(lineTok, currentSafety));
            }
        }
        return out;
    }

    /** Entry point: type/logic-checks a fully resolved program's top-level statements. */
    public void check(List<Token> rootLines) {
        rootLinesRef = rootLines;
        collectDeclarations(rootLines);
        forceGhostTableFunctionNames();
        validateGenericBounds(rootLines);
        for (FlatDecl fd : flattenRootLines(rootLines, "indeterminate")) {
            Token t = fd.lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && t.text.equals("func")) {
                checkFunctionBody(functionInfoFor(t));
            }
        }
        for (List<ImplInfo> implList : implsByConcreteType.values()) {
            for (ImplInfo implInfo : implList) {
                for (FuncInfo methodInfo : implInfo.methods.values()) {
                    checkFunctionBody(methodInfo);
                }
                for (FuncInfo methodInfo : implInfo.staticMethods.values()) {
                    checkFunctionBody(methodInfo);
                }
            }
        }
        for (LibraryInfo libInfo : libraries.values()) {
            for (List<FuncInfo> overloads : libInfo.ownFunctions.values()) {
                for (FuncInfo fn : overloads) {
                    checkFunctionBody(fn);
                }
            }
        }
        // Generic constructors are in `functions` only, so neither loop above reaches them.
        for (FuncInfo ctorInfo : genericConstructorFuncInfos) {
            checkFunctionBody(ctorInfo);
        }
        checkSafetyCallGraph();
        checkDynArrayForLoopResizeSafety();
        checkNoRecursion();
        validateNoThrowReachableFromGhostTableFunctions();
        validateNoAutoReturns();
        validateNoRefSomeStructMembers();
        validateNoAutoStructMembers();
        validateNoRecursiveStructs();
        validateNoPointersInLockStructs();
        validateMainFunction();
        validateUnsafeBlockTags();
    }

    /** All top-level funcs (any overload) with the given decorator; @tick/@event_loop/@make_safe_args are found by decorator, not by name. */
    private List<FuncInfo> findFuncsWithDecorator(String decoratorName) {
        List<FuncInfo> found = new ArrayList<>();
        for (List<FuncInfo> overloads : functions.values()) {
            for (FuncInfo f : overloads) {
                if (hasDecorator(f.funcToken.decorators, decoratorName)) {
                    found.add(f);
                }
            }
        }
        return found;
    }

    /** Requires "owns some mut" of a concrete struct; returns the struct name so callers can compare "same T". */
    private String requireOwnsSomeMutStruct(TypeInfo t, String context, Token at) {
        boolean shapeOk = "owns".equals(t.storage) && t.isSome && "mut".equals(t.mutability)
                && structs.containsKey(t.baseType);
        if (!shapeOk) {
            throw new CompilerException("type", at.file, at.line,
                    context + " must be 'owns some mut' a concrete struct type -- got '" + t.canonical()
                            + "'");
        }
        return t.baseType;
    }

    /** Validates @with_tick, @tick and @event_loop together with main. See docs/TypeChecker.md, "main, @tick, @event_loop, @make_safe_args". */
    private void validateTickEventLoop(FuncInfo main, boolean withTick) {
        List<FuncInfo> tickFuncs = findFuncsWithDecorator("tick");
        List<FuncInfo> eventLoopFuncs = findFuncsWithDecorator("event_loop");
        if (!withTick) {
            // Without @with_tick, neither decorator may appear.
            if (!tickFuncs.isEmpty()) {
                Token at = tickFuncs.get(0).funcToken;
                throw new CompilerException("type", at.file, at.line,
                        "'@tick' can only be used when 'main' is '@with_tick'");
            }
            if (!eventLoopFuncs.isEmpty()) {
                Token at = eventLoopFuncs.get(0).funcToken;
                throw new CompilerException("type", at.file, at.line,
                        "'@event_loop' can only be used when 'main' is '@with_tick'");
            }
            return;
        }
        // With @with_tick, @tick and @event_loop are each required exactly once.
        if (tickFuncs.isEmpty()) {
            throw new CompilerException("type", main.funcToken.file, main.funcToken.line,
                    "'main' is '@with_tick', so a '@tick' function is required");
        }
        if (tickFuncs.size() > 1) {
            Token at = tickFuncs.get(1).funcToken;
            throw new CompilerException("type", at.file, at.line,
                    "'@tick' may only be used once in the final compilation unit");
        }
        FuncInfo tick = tickFuncs.get(0);
        Token tickAt = tick.funcToken;
        if (tick.paramNames.size() != 1) {
            throw new CompilerException("type", tickAt.file, tickAt.line,
                    "'@tick' must take exactly one parameter, 'owns some mut' a concrete struct type");
        }
        String tickParamStruct = requireOwnsSomeMutStruct(tick.paramTypes.get(0), "'@tick's own parameter",
                tickAt);
        String tickReturnStruct = requireOwnsSomeMutStruct(tick.returnType, "'@tick's own return type",
                tickAt);
        if (!tickParamStruct.equals(tickReturnStruct)) {
            throw new CompilerException("type", tickAt.file, tickAt.line,
                    "'@tick's own parameter ('" + tickParamStruct + "') and return type ('"
                            + tickReturnStruct + "') must be the same struct");
        }
        // main must return the same struct as @tick.
        String mainReturnStruct = requireOwnsSomeMutStruct(main.returnType, "'main'", main.funcToken);
        if (!mainReturnStruct.equals(tickReturnStruct)) {
            throw new CompilerException("type", main.funcToken.file, main.funcToken.line,
                    "'main' returns '" + mainReturnStruct + "', but '@tick' is for '" + tickReturnStruct
                            + "' -- both must be the same struct");
        }
        // @event_loop is required exactly once.
        if (eventLoopFuncs.isEmpty()) {
            throw new CompilerException("type", main.funcToken.file, main.funcToken.line,
                    "'main' is '@with_tick', so an '@event_loop' function is required");
        }
        if (eventLoopFuncs.size() > 1) {
            Token at = eventLoopFuncs.get(1).funcToken;
            throw new CompilerException("type", at.file, at.line,
                    "'@event_loop' may only be used once in the final compilation unit");
        }
        FuncInfo eventLoop = eventLoopFuncs.get(0);
        Token elAt = eventLoop.funcToken;
        // @event_loop is the real entry point, so its signature follows main's argument shape; its body is not validated.
        if (main.mainArgShape == null) {
            if (!eventLoop.paramNames.isEmpty()) {
                throw new CompilerException("type", elAt.file, elAt.line,
                        "'@event_loop' must take no parameters ('main' itself takes none)");
            }
        } else {
            boolean elArgsOk = eventLoop.paramNames.size() == 2
                    && eventLoop.paramTypes.get(0).canonical().equals("imut_s32")
                    && eventLoop.paramTypes.get(1).canonical().equals("raw_mut_raw_mut_char");
            if (!elArgsOk) {
                throw new CompilerException("type", elAt.file, elAt.line,
                        "'@event_loop' must take '(imut s32, raw mut raw mut char)' -- 'main' itself "
                                + "takes arguments, so the real entry point needs to receive the raw "
                                + "C-ABI arguments to pass (or wrap) through to it");
            }
        }
        String elReturn = eventLoop.returnType.canonical();
        boolean elReturnOk = elReturn.equals("imut_s32") || elReturn.equals("imut_bool")
                || elReturn.equals("imut_void");
        if (!elReturnOk) {
            throw new CompilerException("type", elAt.file, elAt.line,
                    "'@event_loop' must return 'imut s32', 'imut bool', or 'void' (one of the 'main' "
                            + "signatures that doesn't return a pointer) -- got '" + elReturn + "'");
        }
        eventLoopFunc = eventLoop;
    }

    /** Exactly one 'main' with a valid signature, over the final import-flattened `functions` map. See docs/TypeChecker.md, "main, @tick, @event_loop, @make_safe_args". */
    private void validateMainFunction() {
        List<FuncInfo> mains = functions.get("main");
        if (mains == null || mains.isEmpty()) {
            Token fallback = rootLinesRef.isEmpty() ? null : rootLinesRef.get(0);
            String file = fallback != null ? fallback.file : "<unknown>";
            int line = fallback != null ? fallback.line : 0;
            throw new CompilerException("type", file, line,
                    "no 'main' function found -- every program needs exactly one, with signature "
                            + "'func main() imut s32', 'func main() imut bool', or 'func main() void'");
        }
        if (mains.size() > 1) {
            FuncInfo second = mains.get(1);
            throw new CompilerException("type", second.funcToken.file, second.funcToken.line,
                    "more than one 'main' function found -- a program can only have exactly one");
        }
        FuncInfo main = mains.get(0);
        Token at = main.funcToken;
        main.mainArgShape = validateAndResolveMainArgShape(main, at);
        validateMakeSafeArgs(main);
        boolean withTick = getDecorator(at.decorators, "with_tick") != null;
        if (!withTick) {
            String canonicalReturn = main.returnType.canonical();
            boolean validReturn = canonicalReturn.equals("imut_s32") || canonicalReturn.equals("imut_bool")
                    || canonicalReturn.equals("imut_void");
            if (!validReturn) {
                throw new CompilerException("type", at.file, at.line,
                        "'main' must return 'imut s32', 'imut bool', or 'void' -- got '" + canonicalReturn
                                + "' (or be decorated '@with_tick' and return 'owns some mut' a "
                                + "concrete struct type matching '@tick')");
            }
        }
        validateTickEventLoop(main, withTick);
    }

    /** Resolves main's argument shape: none (null), C-ABI "c_args", or the wrapped "safe_args". */
    private String validateAndResolveMainArgShape(FuncInfo main, Token at) {
        if (main.paramNames.isEmpty()) {
            return null;
        }
        String cArgsDesc = "'(imut s32, raw mut raw mut char)' (the C-ABI argc/argv shape)";
        String safeArgsDesc = "'(" + safeArgsType().canonical() + ")' (the safe, wrapped shape)";
        if (main.paramNames.size() == 2) {
            if (!main.paramTypes.get(0).canonical().equals("imut_s32")
                    || !main.paramTypes.get(1).canonical().equals("raw_mut_raw_mut_char")) {
                throw new CompilerException("type", at.file, at.line,
                        "'main' with two parameters must be exactly " + cArgsDesc + " -- got '"
                                + main.paramTypes.get(0).canonical() + "', '"
                                + main.paramTypes.get(1).canonical() + "'");
            }
            return "c_args";
        }
        if (main.paramNames.size() == 1) {
            if (!main.paramTypes.get(0).canonical().equals(safeArgsType().canonical())) {
                throw new CompilerException("type", at.file, at.line,
                        "'main' with one parameter must be exactly " + safeArgsDesc + " -- got '"
                                + main.paramTypes.get(0).canonical() + "'");
            }
            return "safe_args";
        }
        throw new CompilerException("type", at.file, at.line,
                "'main' must take no parameters, " + cArgsDesc + ", or " + safeArgsDesc + " -- got "
                        + main.paramNames.size() + " parameters");
    }

    /** Type of the safe main argument: owns some mut dynarray of imut dynarrays of imut char. */
    TypeInfo safeArgsType() {
        TypeInfo charType = new TypeInfo(null, "imut", "char");
        TypeInfo innerDynArray = TypeInfo.dynArray("imut", charType);
        return TypeInfo.dynArray("mut", innerDynArray).withSome(true);
    }

    /** @make_safe_args must exist exactly once iff main uses safe_args, with signature (imut s32, raw mut raw mut char) returning safeArgsType(). */
    private void validateMakeSafeArgs(FuncInfo main) {
        List<FuncInfo> candidates = findFuncsWithDecorator("make_safe_args");
        if (candidates.size() > 1) {
            FuncInfo second = candidates.get(1);
            throw new CompilerException("type", second.funcToken.file, second.funcToken.line,
                    "'@make_safe_args' may only be used once in the final compilation unit");
        }
        boolean needed = "safe_args".equals(main.mainArgShape);
        if (needed && candidates.isEmpty()) {
            throw new CompilerException("type", main.funcToken.file, main.funcToken.line,
                    "'main' uses the safe, wrapped argument shape, which requires exactly one "
                            + "'@make_safe_args' function to exist somewhere in the compilation unit -- "
                            + "none was found");
        }
        if (candidates.isEmpty()) {
            return;
        }
        FuncInfo msa = candidates.get(0);
        Token at = msa.funcToken;
        boolean sigOk = msa.paramNames.size() == 2
                && msa.paramTypes.get(0).canonical().equals("imut_s32")
                && msa.paramTypes.get(1).canonical().equals("raw_mut_raw_mut_char")
                && msa.returnType.canonical().equals(safeArgsType().canonical());
        if (!sigOk) {
            throw new CompilerException("type", at.file, at.line,
                    "'@make_safe_args' must be exactly 'func(argc: imut s32, argv: raw mut raw mut char) "
                            + safeArgsType().canonical() + "'");
        }
    }

    // ---- pass 1: collect and validate top-level declarations ------------

    private void collectDeclarations(List<Token> rootLines) {
        List<FlatDecl> flatDecls = flattenRootLines(rootLines, "indeterminate");
        // Pass 1a: register structs, interfaces, enums, libraries, aliases and consts raw (see docs/TypeChecker.md, "collectDeclarations passes").
        for (FlatDecl fd : flatDecls) {
            if (globalLetAssignOf(fd.lineTok) != null) {
                continue;
            }
            Token t = requireTopLevelDecl(fd.lineTok);
            switch (t.text) {
                case "struct": registerStructLike(t); break;
                                case "enum": collectEnum(t); break;
                case "interface": registerInterface(t); break;
                case "library": registerLibraryName(t); break;
                case "type": registerAlias(t); break;
                case "const": registerConst(t); break;
                default: break; // func/impl handled below
            }
        }
        // Pass 1a.5: flatten struct members and interface methods (before impls and globals are processed).
        for (String name : new ArrayList<>(structs.keySet())) {
            flattenStruct(name, new HashSet<>());
        }
        for (String name : new ArrayList<>(interfaces.keySet())) {
            flattenInterface(name, new HashSet<>());
        }
        // Pass 1a.6: record each func/impl's root-list position (FuncInfo.declOrder).
        pendingDeclOrder = new HashMap<>();
        for (int i = 0; i < flatDecls.size(); i++) {
            Token t = flatDecls.get(i).lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD
                    && (t.text.equals("func") || t.text.equals("impl") || t.text.equals("library"))) {
                pendingDeclOrder.put(t, i);
                if (t.text.equals("impl") || t.text.equals("library")) {
                    for (Token method : t.childs) {
                        pendingDeclOrder.put(method, i);
                    }
                }
            }
        }
        // Pass 1a.7: externs, before func/impl collection so a colliding function name is caught.
        for (FlatDecl fd : flatDecls) {
            Token t = fd.lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && t.text.equals("extern")) {
                collectExtern(t);
            }
        }
        // Pass 1a.8: globals, in file order.
        for (int i = 0; i < flatDecls.size(); i++) {
            Token assignNode = globalLetAssignOf(flatDecls.get(i).lineTok);
            if (assignNode != null) {
                collectGlobal(assignNode, i);
            }
        }
        // Pass 1b: func and impl (impl validates against what pass 1a collected)
        for (FlatDecl fd : flatDecls) {
            Token t = fd.lineTok.childs.get(0);
            switch (t.text) {
                case "func": collectFunc(t, fd.safety); break;
                case "impl": collectImpl(t, fd.safety); break;
                case "impl_default_lock_match": collectDefaultLockMatchPolicy(t); break;
                case "library": collectLibrary(t, fd.safety); break;
                default: break; // already handled above
            }
        }
        // Pass 1b.5: flatten library 'extends' chains (needs every library's function set).
        for (String name : new ArrayList<>(libraries.keySet())) {
            flattenLibrary(name, new HashSet<>());
        }
        // Pass 1c: check every struct's "implements" contract.
        for (StructInfo info : structs.values()) {
            checkImplementsContract(info);
        }
        // Pass 1c-bis: synthesize the Class enum and the "enum for X" membership enums (order matters).
        generateClassHierarchyEnums();
        generateEnumForEnums();
        // Pass 1c-ter: synthesize interface-method dispatchers (after enum-for and impl validation).
        generateInterfaceDispatchers();
        // Pass 1d: export statements.
        for (FlatDecl fd : flatDecls) {
            Token t = fd.lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && t.text.equals("export")) {
                collectExport(t);
            }
        }
        // Pass 1e: root-level ASM declarations and invocations, in strict file order.
        for (FlatDecl fd : flatDecls) {
            Token t = fd.lineTok.childs.get(0);
            if (t.type == TokenType.KEYWORD && t.text.equals("ASM")) {
                collectRootAsmDeclaration(t, fd.safety);
            } else if (t.type == TokenType.VARREF) {
                collectRootAsmInvocation(t, fd.safety);
            }
        }
        // Pass 1f: mangle overloaded top-level funcs (only a name with more than one overload).
        for (List<FuncInfo> overloads : functions.values()) {
            if (overloads.size() <= 1) {
                continue;
            }
            for (FuncInfo fi : overloads) {
                fi.mangledName = fi.name + "__sig_" + signatureSuffix(fi.paramTypes);
            }
        }
    }

    /** "ASM name {...}"/"ASM name \"path\"" at the root -- see collectDeclarations' Pass 1e. */
    private void collectRootAsmDeclaration(Token t, String safety) {
        if (!"unsafe".equals(safety)) {
            throw new CompilerException("type", t.file, t.line,
                    "declaring 'ASM' requires 'unsafe' code");
        }
        String rawText = resolveAsmRawText(t);
        t.resolvedAsmContent = rawText; // read by BytecodeEmitter directly when unnamed (self-emits here); harmlessly unused otherwise
        if (t.sub.isEmpty()) {
            return; // unnamed -- never registered under any name at all
        }
        String name = t.sub.get(0).text;
        if (rootAsms.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'ASM " + name + "' is already declared");
        }
        AsmInfo info = new AsmInfo();
        info.name = name;
        info.rawText = rawText;
        info.declTok = t;
        rootAsms.put(name, info);
    }

    /** A bare "asm_name;" line at the root -- see collectDeclarations' Pass 1e. */
    private void collectRootAsmInvocation(Token t, String safety) {
        if (!"unsafe".equals(safety)) {
            throw new CompilerException("type", t.file, t.line,
                    "invoking '" + t.text + "' requires 'unsafe' code");
        }
        AsmInfo info = rootAsms.get(t.text);
        if (info == null) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + t.text + "' is not a declared 'ASM' name (or is used before its declaration)");
        }
        t.resolvedAsmContent = info.rawText;
    }

    /** Returns the "=" assign node if `lineTok` is a root-level "let static NAME = ..." global, else null. */
    private Token globalLetAssignOf(Token lineTok) {
        Token t = lineTok.childs.get(0);
        if (t.type == TokenType.OPERATOR && t.text.equals("=")
                && t.left.type == TokenType.KEYWORD && t.left.text.equals("let")) {
            return t;
        }
        return null;
    }

    /**
     * "let static NAME = ..." at the root: the RHS follows the local-static rules but may name an earlier global.
     * See docs/TypeChecker.md, "Top-level declaration collection".
     */
    private void collectGlobal(Token assignNode, int declOrder) {
        Token letTok = assignNode.left;
        Token nameTok = letTok.childs.get(0);
        String name = nameTok.text;
        if (!letTok.isStatic) {
            throw new CompilerException("type", letTok.file, letTok.line,
                    "only 'let static' is allowed at the top level -- a plain 'let' has no meaning "
                            + "outside a function body");
        }
        validateDecorators(letTok.decorators, GLOBAL_DECORATORS, "a top-level 'let static'");
        if (getDecorator(letTok.decorators, "pub") != null) {
            publicGlobals.add(name);
        }
        if (globals.containsKey(name) || structs.containsKey(name) || enums.containsKey(name)
                || interfaces.containsKey(name) || aliasRawTargets.containsKey(name)
                || constExprs.containsKey(name)) {
            throw new CompilerException("type", letTok.file, letTok.line,
                    "'" + name + "' is already declared");
        }
        checkNoRuntimeConstructs(assignNode.right);
        FuncInfo globalCheckContext = new FuncInfo();
        globalCheckContext.declOrder = declOrder;
        TypeInfo rhsType = resolveExprType(assignNode.right, new Scope(null), globalCheckContext);
        // `let static:<mut f64> g = mut 0.5` -- an explicit type bound on a global (checked and literal-adapted like a local's)
        TypeInfo globalBound = resolveAndValidateTypeBound(letTok, rhsType, assignNode.right);
        if (globalBound != null) {
            rhsType = globalBound;
        }
        requireStaticMutable(rhsType, assignNode.right);
        // Direct atomic declaration vs. merely reading an atomic value: see isDirectAtomicDeclaration.
        if (rhsType.isAtomic && !isDirectAtomicDeclaration(assignNode.right)) {
            rhsType = rhsType.withAtomic(false);
        }
        letTok.resolvedType = rhsType.canonical();

        globals.put(name, rhsType);
        globalDeclOrder.put(name, declOrder);
        globalOrder.add(name);
        globalRhsByName.put(name, assignNode.right);
    }

    private void checkImplementsContract(StructInfo info) {
        if (info.implementsNames.isEmpty()) {
            return;
        }
        List<ImplInfo> impls = implsByConcreteType.get(info.name);
        for (String interfaceName : info.implementsNames) {
            boolean satisfied = false;
            if (impls != null) {
                for (ImplInfo impl : impls) {
                    if (interfaceName.equals(impl.interfaceName)) {
                        satisfied = true;
                        break;
                    }
                }
            }
            if (!satisfied) {
                throw new CompilerException("type", info.declTok.file, info.declTok.line,
                        "'" + info.name + "' declares 'implements " + interfaceName + "' but no matching "
                                + "'impl " + interfaceName + " for " + info.name + "{...}' was found "
                                + "anywhere in this compilation unit");
            }
        }
    }

    private Token requireTopLevelDecl(Token lineTok) {
        if (lineTok.childs.size() != 1) {
            throw new CompilerException("type", lineTok.file, lineTok.line,
                    "only 'struct', 'enum', 'interface', 'impl', 'library', 'func', 'type', "
                            + "'extern', 'export', and 'ASM' declarations (or a bare 'asm_name;' "
                            + "invocation) are allowed at the top level");
        }
        Token t = lineTok.childs.get(0);
        // A bare VARREF line is allowed: it can only be an "asm_name;" invocation (validated in
        // collectAsmInvocations).
        if (t.type == TokenType.VARREF) {
            return t;
        }
        if (t.type != TokenType.KEYWORD
                || !(t.text.equals("struct") || t.text.equals("enum")
                        || t.text.equals("func") || t.text.equals("interface") || t.text.equals("impl")
                        || t.text.equals("impl_default_lock_match")
                        || t.text.equals("library")
                        || t.text.equals("type") || t.text.equals("extern") || t.text.equals("export")
                        || t.text.equals("ASM") || t.text.equals("const"))) {
            throw new CompilerException("type", t.file, t.line,
                    "only 'struct', 'enum', 'interface', 'impl', 'library', 'func', 'type', "
                            + "'const', 'extern', 'export', and 'ASM' declarations (or a bare 'asm_name;' "
                            + "invocation) are allowed at the top level, found '" + t.text + "'");
        }
        return t;
    }

    /**
     * "type NAME <target>": stores the raw target tokens. Collision with globals is checked from collectGlobal's
     * side.
     */
    private void registerAlias(Token t) {
        String name = t.sub.get(0).text;
        if (aliasRawTargets.containsKey(name) || structs.containsKey(name) || enums.containsKey(name)
                || interfaces.containsKey(name) || libraries.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        aliasRawTargets.put(name, t.sub.subList(1, t.sub.size()));
    }

    /**
     * "const NAME EXPR": registered raw and evaluated lazily on first reference (consts are forward-declarable,
     * see resolveConstValue). See docs/TypeChecker.md, "Top-level declaration collection".
     */
    private void registerConst(Token t) {
        String name = t.sub.get(0).text;
        if (constExprs.containsKey(name) || structs.containsKey(name) || enums.containsKey(name)
                || interfaces.containsKey(name) || aliasRawTargets.containsKey(name)
                || libraries.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        constExprs.put(name, t.childs.get(0));
    }

    /**
     * Raw registration of a library name (shares the struct/enum/interface namespace); its functions are built
     * later in collectLibrary.
     */
    private void registerLibraryName(Token t) {
        String name = t.sub.get(0).text;
        if (structs.containsKey(name) || enums.containsKey(name) || interfaces.containsKey(name)
                || libraries.containsKey(name) || aliasRawTargets.containsKey(name)
                || constExprs.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        validateDecorators(t.decorators, LIBRARY_DECORATORS, "a 'library'");
        LibraryInfo info = new LibraryInfo();
        info.name = name;
        info.declTok = t;
        info.isPublic = getDecorator(t.decorators, "pub") != null;
        info.extendsNames = t.extendsNames != null ? t.extendsNames : new ArrayList<>();
        libraries.put(name, info);
    }

    private void registerStructLike(Token t) {
        String name = t.sub.get(0).text;
        if (structs.containsKey(name) || enums.containsKey(name) || interfaces.containsKey(name)
                || libraries.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        validateDecorators(t.decorators, STRUCT_DECORATORS, "a struct");
        // @unpadded is recognised but rejected: every struct is compiler-padded (see emitStruct). Kept in
        // STRUCT_DECORATORS to give this specific error.
        if (getDecorator(t.decorators, "unpadded") != null) {
            throw new CompilerException("type", t.file, t.line,
                    "'@unpadded' is not supported yet -- every struct is compiler-padded to its own "
                            + "natural alignment");
        }
        StructInfo info = new StructInfo(name);
        info.isPublic = getDecorator(t.decorators, "pub") != null;
        info.implementsNames = t.implementsNames != null ? t.implementsNames : new ArrayList<>();
        info.rawMemberLines = t.childs;
        info.declTok = t;
        structs.put(name, info);
        structDeclOrder.add(name);
    }

    /**
     * Resolves `name`'s final member set (a struct has only its own
     * members -- struct inheritance does not exist) and validates its
     * `swap` mutex field and struct-level `@lock` clause.
     */
    private void flattenStruct(String name, Set<String> visiting) {
        StructInfo info = structs.get(name);
        if (info == null || info.flattened) {
            return;
        }

        String swapFieldName = null;
        Token swapFieldDeclTok = null;
        for (Token memberLine : info.rawMemberLines) {
            boolean linePublic = !memberLine.childs.isEmpty() && memberLine.childs.get(0).isPubBlockMember;
            for (NameType nt : parseNameTypeGroup(memberLine.childs)) {
                if (info.members.containsKey(nt.name)) {
                    throw new CompilerException("type", memberLine.file, memberLine.line,
                            "'" + name + "' already has a member named '" + nt.name + "'");
                }
                info.members.put(nt.name, nt.type);
                if (linePublic) {
                    info.publicMembers.add(nt.name);
                }
                info.memberDeclFile.put(nt.name, memberLine.file);
                if (nt.isSwap) {
                    if (swapFieldName != null) {
                        throw new CompilerException("type", memberLine.file, memberLine.line,
                                "'" + name + "' already has a 'swap'-tagged member named '" + swapFieldName
                                        + "' -- a struct can have at most one");
                    }
                    swapFieldName = nt.name;
                    swapFieldDeclTok = memberLine;
                }
            }
        }
        if (swapFieldName != null) {
            validateSwapMutexField(info, name, swapFieldName, swapFieldDeclTok);
            info.swapFieldName = swapFieldName;
        }

        Token.Decorator lockDecorator = null;
        for (Token.Decorator d : (info.declTok.decorators == null ? List.<Token.Decorator>of() : info.declTok.decorators)) {
            if (d.name.equals("lock") && d.lockFieldName != null) {
                lockDecorator = d;
                break;
            }
        }
        if (lockDecorator != null) {
            // Validated at the end of flattenStruct, once member types are known.
            validateLockEnumClause(lockDecorator, info, name);
            // The first user-declared member must be the lock field (the hidden ___type is not in info.members).
            if (!info.members.isEmpty()) {
                String firstMember = info.members.keySet().iterator().next();
                if (!firstMember.equals(lockDecorator.lockFieldName)) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "'" + name + "' is decorated '@lock(match self." + lockDecorator.lockFieldName
                                    + " : ...)', so '" + lockDecorator.lockFieldName + "' must be its first member, "
                                    + "but its first member is '" + firstMember + "'");
                }
            }
            info.lockFieldName = lockDecorator.lockFieldName;
            info.lockVariants = lockDecorator.lockVariants;
            if (info.swapFieldName != null && !info.swapFieldName.equals(info.lockFieldName)) {
                // A swap member plus a struct-level @lock(...) must name the same field. See docs/TypeChecker.md,
                // "Struct flattening and @lock clauses".
                throw new CompilerException("type", info.declTok.file, info.declTok.line,
                        "'" + name + "'s '@lock(match self." + info.lockFieldName + " : ...)' decorator must "
                                + "name the same field as its 'swap'-tagged member ('" + info.swapFieldName
                                + "'), not a different one");
            }
        }

        info.flattened = true;
    }

    /**
     * The swap member of a "match @lock" target must be a two-variant enum named exactly OPEN and CLOSED (codegen
     * builds ATOMIC_SWAP calls against those names).
     */
    private void validateSwapMutexField(StructInfo structInfo, String structName, String fieldName, Token at) {
        TypeInfo fieldType = structInfo.members.get(fieldName);
        EnumInfo enumInfo = enums.get(fieldType.baseType);
        if (enumInfo == null) {
            throw new CompilerException("type", at.file, at.line,
                    "'swap " + fieldName + "' must be an enum-typed atomic member, got '"
                            + fieldType.canonical() + "'");
        }
        if (enumInfo.variants.size() != 2 || !enumInfo.variants.contains("OPEN")
                || !enumInfo.variants.contains("CLOSED")) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + enumInfo.name + "' is used here as a swap-mutex state -- it must be a plain, "
                            + "exactly-two-variant enum named 'OPEN' and 'CLOSED' (found: "
                            + enumInfo.variants + ")");
        }
    }

    /**
     * Validates "@lock(match self.x : X)": the field exists, is enum-typed, and every variant belongs to that
     * enum. Shared by struct-level and method-level use.
     */
    private void validateLockEnumClause(Token.Decorator d, StructInfo structInfo, String structName) {
        String fieldName = d.lockFieldName;
        TypeInfo fieldType = structInfo.members.get(fieldName);
        if (fieldType == null) {
            throw new CompilerException("type", d.file, d.line,
                    "'@lock(match self." + fieldName + " : ...)' -- '" + structName + "' has no member "
                            + "named '" + fieldName + "'");
        }
        EnumInfo lockEnumInfo = enums.get(fieldType.baseType);
        if (lockEnumInfo == null) {
            throw new CompilerException("type", d.file, d.line,
                    "'@lock(match self." + fieldName + " : ...)' -- '" + fieldName + "' must be an "
                            + "enum-typed member, got '" + fieldType.canonical() + "'");
        }
        for (String variant : d.lockVariants) {
            if (!lockEnumInfo.variants.contains(variant)) {
                throw new CompilerException("type", d.file, d.line,
                        "'" + lockEnumInfo.name + "' has no variant named '" + variant + "'");
            }
        }
    }

    /**
     * Validates every "@lock(match ...)" decorator on one method (impl method or interface signature); struct
     * receivers only. See docs/TypeChecker.md, "Method-level @lock".
     */
    private void validateMethodLocks(List<Token.Decorator> decorators, List<String> paramNames,
            List<TypeInfo> paramTypes, String concreteName) {
        if (decorators == null || !structs.containsKey(concreteName)) {
            return;
        }
        flattenStruct(concreteName, new HashSet<>());
        StructInfo si = structs.get(concreteName);
        for (Token.Decorator d : decorators) {
            if (!d.name.equals("lock")) {
                continue;
            }
            if (d.lockFieldName != null) {
                validateLockEnumClause(d, si, concreteName);
            } else if (d.lockInParams != null) {
                validateLockInClause(d, si, concreteName, paramNames, paramTypes);
            }
            // else: no qualified @lock clause to validate.
        }
    }

    /**
     * Validates "@lock(match x in|within self.f)" against the typing rules of checkIn / checkWithin (chosen per
     * clause by d.lockInOperators).
     */
    private void validateLockInClause(Token.Decorator d, StructInfo si, String concreteName,
            List<String> paramNames, List<TypeInfo> paramTypes) {
        for (int i = 0; i < d.lockInParams.size(); i++) {
            String paramName = d.lockInParams.get(i);
            String fieldName = d.lockInFields.get(i);
            String operatorText = d.lockInOperators.get(i);
            int paramIdx = paramNames.indexOf(paramName);
            if (paramIdx < 0) {
                throw new CompilerException("type", d.file, d.line,
                        "'@lock(match " + paramName + " " + operatorText + " ...)' -- no parameter named '"
                                + paramName + "'");
            }
            TypeInfo paramType = paramTypes.get(paramIdx);
            if (!"imut".equals(paramType.mutability)) {
                throw new CompilerException("type", d.file, d.line,
                        "'@lock(match " + paramName + " " + operatorText + " ...)' -- '" + paramName
                                + "' must be an 'imut' parameter, got '" + paramType.canonical() + "'");
            }
            TypeInfo fieldType = si.members.get(fieldName);
            if (fieldType == null) {
                throw new CompilerException("type", d.file, d.line,
                        "'@lock(match " + paramName + " " + operatorText + " self." + fieldName + ")' -- '"
                                + concreteName + "' has no member named '" + fieldName + "'");
            }
            boolean isWithin = operatorText.equals("within");
            boolean leftOk = isWithin ? isRangeType(paramType)
                    : (isPlainU64(paramType) || isRangeType(paramType));
            if (!leftOk) {
                throw new CompilerException("type", d.file, d.line,
                        "'@lock(match " + paramName + " " + operatorText + " ...)' -- '" + operatorText
                                + "' requires a " + (isWithin ? "range" : "plain 'u64' or a range")
                                + " on the left, got '" + paramType.canonical() + "'");
            }
            boolean rightOk = isWithin ? isRangeType(fieldType)
                    : (isRangeType(fieldType) || isDynArrayType(fieldType));
            if (!rightOk) {
                throw new CompilerException("type", d.file, d.line,
                        "'@lock(match ... " + operatorText + " self." + fieldName + ")' -- '" + operatorText
                                + "' requires a range" + (isWithin ? "" : " or a dynarray")
                                + " on the right, got '" + fieldType.canonical() + "'");
            }
            // "Some(i) in self.x" needs self.x to be a dynarray of pointer elements (as checkSomeIndexBoundsCondition
            // requires).
            if (d.lockInSomeWrapped != null && Boolean.TRUE.equals(d.lockInSomeWrapped.get(i))) {
                if (!isDynArrayType(fieldType) || fieldType.dynArrayElementType == null
                        || fieldType.dynArrayElementType.storage == null) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@lock(match Some(" + paramName + ") " + operatorText + " self." + fieldName
                                    + ")' -- 'Some(...)' requires 'self." + fieldName + "' to be a dynarray "
                                    + "of pointer-typed elements, got '" + fieldType.canonical() + "'");
                }
            }
        }
    }


    private void collectEnum(Token t) {
        String name = t.sub.get(0).text;
        if (structs.containsKey(name) || enums.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        validateDecorators(t.decorators, ENUM_DECORATORS, "an enum");
        EnumInfo info = new EnumInfo(name);
        info.isNonExhaustive = getDecorator(t.decorators, "non_exhaustive") != null;
        int explicitValueCount = 0;
        int explicitRangeCount = 0;
        for (Token variant : t.childs) {
            if (info.variants.contains(variant.text)) {
                throw new CompilerException("type", variant.file, variant.line,
                        "enum '" + name + "' already has a variant named '" + variant.text + "'");
            }
            info.variants.add(variant.text);
            if (variant.explicitEnumValue != null) {
                explicitValueCount++;
            }
            if (variant.explicitRangeStart != null) {
                explicitRangeCount++;
            }
        }
        if (explicitValueCount > 0 && explicitRangeCount > 0) {
            // An enum is plain, u64-valued, or range-valued -- never a
            // mix of the last two within one declaration.
            throw new CompilerException("type", t.file, t.line,
                    "enum '" + name + "' mixes u64-valued and range-valued variants -- an enum is either "
                            + "u64-valued or range-valued, never both");
        }
        if (explicitValueCount != 0 && explicitValueCount != t.childs.size()) {
            // "no partial mixing," confirmed directly.
            throw new CompilerException("type", t.file, t.line,
                    "enum '" + name + "' mixes explicit and default variant values -- either every variant "
                            + "has one, or none do");
        }
        if (explicitRangeCount != 0 && explicitRangeCount != t.childs.size()) {
            // Same "no partial mixing" rule, for the range-valued form.
            throw new CompilerException("type", t.file, t.line,
                    "enum '" + name + "' mixes explicit and default variant ranges -- either every variant "
                            + "has one, or none do");
        }
        if (explicitValueCount > 0) {
            info.explicitValues = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (Token variant : t.childs) {
                if (!seen.add(variant.explicitEnumValue)) {
                    throw new CompilerException("type", variant.file, variant.line,
                            "enum '" + name + "' has two variants both explicitly valued '"
                                    + variant.explicitEnumValue + "'");
                }
                info.explicitValues.add(variant.explicitEnumValue);
            }
        }
        if (explicitRangeCount > 0) {
            info.explicitRangeStarts = new ArrayList<>();
            info.explicitRangeEnds = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Token variant : t.childs) {
                if (variant.explicitRangeStart > variant.explicitRangeEnd) {
                    throw new CompilerException("type", variant.file, variant.line,
                            "enum '" + name + "' variant '" + variant.text + "' has a range whose start ("
                                    + variant.explicitRangeStart + ") is after its end ("
                                    + variant.explicitRangeEnd + ")");
                }
                String key = variant.explicitRangeStart + ".." + variant.explicitRangeEnd;
                if (!seen.add(key)) {
                    throw new CompilerException("type", variant.file, variant.line,
                            "enum '" + name + "' has two variants both explicitly valued the identical "
                                    + "range '" + key + "'");
                }
                info.explicitRangeStarts.add(variant.explicitRangeStart);
                info.explicitRangeEnds.add(variant.explicitRangeEnd);
            }
        }
        enums.put(name, info);
    }

    /**
     * "interface Name{...}": signatures stay raw (self is not concrete yet) and are only checked structurally;
     * resolved per impl in collectImpl.
     */
    private void registerInterface(Token t) {
        String name = t.sub.get(0).text;
        if (structs.containsKey(name) || enums.containsKey(name) || interfaces.containsKey(name)
                || libraries.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line, "'" + name + "' is already declared");
        }
        validateDecorators(t.decorators, INTERFACE_DECORATORS, "an interface");
        InterfaceInfo info = new InterfaceInfo();
        info.name = name;
        info.declTok = t;
        info.isPublic = getDecorator(t.decorators, "pub") != null;
        info.extendsNames = t.extendsNames != null ? t.extendsNames : new ArrayList<>();
        info.ownMethods = new LinkedHashMap<>();
        for (Token methodTok : t.childs) {
            boolean isDefault = methodTok.text.equals("func");
            validateInterfaceMethodDecorators(methodTok, isDefault, name);
            if (methodTok.isStaticMethod) {
                // A static signature never gets a "self": reject any use outright. See docs/TypeChecker.md,
                // "Interfaces".
                forbidSelfReference(methodTok, "a static method");
            } else if (isDefault) {
                forbidSelfReference(methodTok, "a '@default' method");
            }
            String methodName = methodTok.sub.get(0).text;
            if (info.ownMethods.containsKey(methodName)) {
                throw new CompilerException("type", methodTok.file, methodTok.line,
                        "interface '" + name + "' already declares a method named '" + methodName + "'");
            }
            RawMethodSig sig = new RawMethodSig();
            sig.nameTok = methodTok.sub.get(0);
            sig.paramsTok = methodTok.sub.get(1);
            sig.returnTypeTokens = methodTok.sub.subList(2, methodTok.sub.size());
            sig.isDefault = isDefault;
            sig.decorators = methodTok.decorators;
            sig.isStatic = methodTok.isStaticMethod;
            sig.defaultBodyTok = isDefault ? methodTok : null;
            if (!methodTok.isStaticMethod) {
                validateSelfIsPointer(sig, name);
            }
            info.ownMethods.put(methodName, sig);
        }
        interfaces.put(name, info);
    }

    /**
     * Body-bearing interface methods require @default, signature-only ones forbid it; @overrides/@realizes belong
     * to impl methods only.
     */
    private void validateInterfaceMethodDecorators(Token methodTok, boolean isDefault, String interfaceName) {
        if (isDefault) {
            buildFuncInfoDecoratorsOnly(methodTok); // reuses the generic func-decorator arg-shape validation
            if (!hasDecorator(methodTok.decorators, "default")) {
                throw new CompilerException("type", methodTok.file, methodTok.line,
                        "a method with a body inside interface '" + interfaceName
                                + "' must be labelled '@default'");
            }
        } else if (hasDecorator(methodTok.decorators, "default")) {
            throw new CompilerException("type", methodTok.file, methodTok.line,
                    "'@default' requires a method body -- '" + interfaceName + "' declares this one as a "
                            + "signature only");
        }
        for (String forbidden : new String[]{"overrides", "realizes"}) {
            Token.Decorator bad = getDecorator(methodTok.decorators, forbidden);
            if (bad != null) {
                throw new CompilerException("type", bad.file, bad.line,
                        "'@" + forbidden + "' decorates an impl's method, not an interface's own "
                                + "declaration");
            }
        }
    }

    /** Validates just the decorator arg-shapes on a func-shaped token, without building a real FuncInfo (used for interface default methods, which aren't registered as callable functions). */
    private void buildFuncInfoDecoratorsOnly(Token t) {
        validateDecorators(t.decorators, FUNC_ALL_DECORATORS, "a function");
    }

    /**
     * Rejects 'self' anywhere in a signature or body: used for @default methods and static methods (`context`
     * names which, for the message).
     */
    private void forbidSelfReference(Token methodTok, String context) {
        for (Token s : methodTok.sub) {
            forbidSelfReferenceIn(s, context);
        }
        if (methodTok.childs != null) {
            for (Token c : methodTok.childs) {
                forbidSelfReferenceIn(c, context);
            }
        }
    }

    private void forbidSelfReferenceIn(Token node, String context) {
        if (node == null) {
            return;
        }
        if (node.type == TokenType.VARREF && node.text.equals("self")) {
            throw new CompilerException("type", node.file, node.line,
                    context + " cannot reference 'self' anywhere in its signature or body");
        }
        forbidSelfReferenceIn(node.left, context);
        forbidSelfReferenceIn(node.right, context);
        if (node.childs != null) {
            for (Token c : node.childs) {
                forbidSelfReferenceIn(c, context);
            }
        }
        if (node.sub != null) {
            for (Token s : node.sub) {
                forbidSelfReferenceIn(s, context);
            }
        }
    }

    /** Resolves an interface's flattened method set (any number of extends; name collisions are errors). */
    private void flattenInterface(String name, Set<String> visiting) {
        InterfaceInfo info = interfaces.get(name);
        if (info == null || info.flattened) {
            return;
        }
        if (visiting.contains(name)) {
            throw new CompilerException("type", info.declTok.file, info.declTok.line,
                    "circular inheritance involving interface '" + name + "'");
        }
        visiting.add(name);

        for (String parentName : info.extendsNames) {
            InterfaceInfo parentInfo = interfaces.get(parentName);
            if (parentInfo == null) {
                throw new CompilerException("type", info.declTok.file, info.declTok.line,
                        "'" + parentName + "' is not a known interface");
            }
            flattenInterface(parentName, visiting);
            for (Map.Entry<String, RawMethodSig> e : parentInfo.methods.entrySet()) {
                if (info.methods.containsKey(e.getKey())) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "interface '" + name + "' inherits a method named '" + e.getKey()
                                    + "' from more than one extended interface");
                }
                info.methods.put(e.getKey(), e.getValue());
            }
        }

        for (Map.Entry<String, RawMethodSig> e : info.ownMethods.entrySet()) {
            if (info.methods.containsKey(e.getKey())) {
                throw new CompilerException("type", info.declTok.file, info.declTok.line,
                        "interface '" + name + "' already has a method named '" + e.getKey()
                                + "' via an extended interface");
            }
            info.methods.put(e.getKey(), e.getValue());
        }

        info.flattened = true;
        visiting.remove(name);
    }

    private void validateSelfIsPointer(RawMethodSig sig, String interfaceName) {
        for (List<Token> group : splitIntoRawGroups(sig.paramsTok.childs)) {
            if (group.size() < 3 || group.get(1).type != TokenType.OPERATOR || !group.get(1).text.equals(":")) {
                continue; // malformed; the later real parse (at impl-matching time) reports it properly
            }
            checkSelfStorageInTypeTokens(group.subList(2, group.size()), interfaceName);
        }
        checkSelfStorageInTypeTokens(sig.returnTypeTokens, interfaceName);
    }

    private void checkSelfStorageInTypeTokens(List<Token> typeTokens, String interfaceName) {
        boolean hasStorage = false;
        Token selfTok = null;
        for (Token t : typeTokens) {
            if (t.type == TokenType.VARREF && t.text.equals("self")) {
                selfTok = t;
            }
            if (t.type == TokenType.MODIFIER && !t.text.equals("mut") && !t.text.equals("imut")) {
                hasStorage = true;
            }
        }
        if (selfTok != null && !hasStorage) {
            throw new CompilerException("type", selfTok.file, selfTok.line,
                    "'self' must be a pointer in interface '" + interfaceName + "' (owns/ref/raw/auto/static)");
        }
    }

    /**
     * "impl default match @lock S{ CLOSED:(tries:imut u64,...)=>{...} }": registers a retry policy, validated
     * eagerly at its declaration (only the parameter shape; the body is checked at each use site). See
     * docs/TypeChecker.md, "Impl collection".
     */
    private void collectDefaultLockMatchPolicy(Token t) {
        String structName = t.sub.get(0).text;
        StructInfo structInfo = structs.get(structName);
        if (structInfo == null) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + structName + "' is not a known struct");
        }
        if (structInfo.swapFieldName == null) {
            throw new CompilerException("type", t.file, t.line,
                    "'impl default match @lock " + structName + "' requires '" + structName
                            + "' to declare a 'swap'-tagged atomic mutex-state field");
        }
        if (structInfo.defaultLockMatchPolicy != null) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + structName + "' already has a registered 'default match @lock' policy -- at most one "
                            + "is allowed per struct");
        }
        Token paramsTok = t.sub.get(1);
        List<NameType> params = parseParamList(paramsTok.childs);
        if (params.isEmpty()) {
            throw new CompilerException("type", t.file, t.line,
                    "'impl default match @lock' requires at least one parameter -- its own first parameter is "
                            + "always the automatic, per-iteration 'tries' counter, never supplied by a "
                            + "'CLOSED:default(...)' call site");
        }
        TypeInfo triesType = params.get(0).type;
        if (triesType.storage != null || !"imut".equals(triesType.mutability) || !"u64".equals(triesType.baseType)) {
            throw new CompilerException("type", t.file, t.line,
                    "'impl default match @lock's own first parameter ('" + params.get(0).name + "') must be "
                            + "exactly 'imut u64' -- the automatic, per-iteration 'tries' counter -- got '"
                            + triesType.canonical() + "'");
        }
        structInfo.defaultLockMatchPolicy = new DefaultLockMatchPolicy(structName, paramsTok, t.childs, t);
    }

    /** Registers "impl [I for] C{...}". See docs/TypeChecker.md, "Impl collection" for the rules. */
    private void collectImpl(Token t, String safetyTag) {
        String interfaceName = t.sub.size() == 2 ? t.sub.get(0).text : null;
        String concreteName = t.sub.size() == 2 ? t.sub.get(1).text : t.sub.get(0).text;

        boolean concreteIsStruct = structs.containsKey(concreteName);
        boolean concreteIsEnum = enums.containsKey(concreteName);
        if (!concreteIsStruct && !concreteIsEnum) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + concreteName + "' is not a known struct or enum");
        }
        InterfaceInfo interfaceInfo = null;
        if (interfaceName != null) {
            interfaceInfo = interfaces.get(interfaceName);
            if (interfaceInfo == null) {
                throw new CompilerException("type", t.file, t.line,
                        "'" + interfaceName + "' is not a known interface");
            }
            if (concreteIsEnum) {
                throw new CompilerException("type", t.file, t.line,
                        "cannot implement an interface for an enum ('" + concreteName + "')");
            }
        }

        ImplInfo implInfo = new ImplInfo();
        implInfo.interfaceName = interfaceName;
        implInfo.concreteName = concreteName;
        implInfo.declTok = t;
        implInfo.safetyTag = safetyTag;
        validateDecorators(t.decorators, IMPL_DECORATORS, "an 'impl'");
        implInfo.isPublic = getDecorator(t.decorators, "pub") != null;

        // Residual-generic templates are held back in genericMethodTemplates; their tokens are removed from t.childs
        // after the loop (BytecodeEmitter walks t.childs).
        List<Token> genericTemplateTokensToRemove = new ArrayList<>();
        boolean droppedUnavailable = false;
        for (Token methodTok : t.childs) {
            // methodTok is a gathered 'func' token: sub = [nameTok, paramsTok, ...returnTypeTokens].
            if (methodTok.isConstructorDecl) {
                // Generic constructor wrapped by Parser.gatherConstructorImpl: registered as an ordinary overload in
                // `functions` (like collectFunc) but left in t.childs for BytecodeEmitter. See docs/TypeChecker.md,
                // "Impl collection".
                FuncInfo ctorInfo = new FuncInfo();
                ctorInfo.funcToken = methodTok;
                try {
                    buildFuncInfo(methodTok, ctorInfo);
                } catch (DeferredSignatureException e) {
                    recordUnavailable(methodTok.sub.get(0).text, "constructor", concreteName, true, e);
                    genericTemplateTokensToRemove.add(methodTok);
                    droppedUnavailable = true;
                    continue;
                }
                registerConstructorFuncInfo(methodTok, ctorInfo);
                ctorInfo.declOrder = pendingDeclOrder.getOrDefault(t, Integer.MAX_VALUE);
                ctorInfo.safetyTag = safetyTag;
                if (externs.containsKey(ctorInfo.name)) {
                    throw new CompilerException("type", methodTok.file, methodTok.line,
                            "'" + ctorInfo.name + "' collides with an already-declared 'extern "
                                    + ctorInfo.name + "'");
                }
                if (constExprs.containsKey(ctorInfo.name)) {
                    throw new CompilerException("type", methodTok.file, methodTok.line,
                            "'" + ctorInfo.name + "' collides with an already-declared 'const "
                                    + ctorInfo.name + "'");
                }
                List<FuncInfo> ctorOverloads = functions.computeIfAbsent(ctorInfo.name, k -> new ArrayList<>());
                for (FuncInfo existing : ctorOverloads) {
                    if (sameSignature(existing, ctorInfo)) {
                        throw new CompilerException("type", methodTok.file, methodTok.line,
                                "constructor '" + t.sub.get(t.sub.size() - 1).text
                                        + "' with this parameter signature is already declared");
                    }
                }
                ctorOverloads.add(ctorInfo);
                genericConstructorFuncInfos.add(ctorInfo);
                continue;
            }
            if (methodTok.typeParams != null) {
                // Residual-generic method template (own type params): held back, instantiated per call site by an
                // explicit ":<U>".
                if (methodTok.isStaticMethod) {
                    // Only instance methods are supported; a static one would need handling in checkStaticMethodCall.
                    throw new CompilerException("type", methodTok.file, methodTok.line,
                            "a 'static' method cannot declare its own residual type parameter(s) yet -- "
                                    + "only an instance method's own residual generics are supported");
                }
                String genericMethodName = methodTok.sub.get(0).text;
                if (implInfo.genericMethodTemplates.containsKey(genericMethodName)) {
                    throw new CompilerException("type", methodTok.file, methodTok.line,
                            "'" + concreteName + "' already has a method named '"
                                    + genericMethodName + "' in this impl block");
                }
                if (interfaceInfo != null && interfaceInfo.methods.containsKey(genericMethodName)) {
                    throw new CompilerException("type", methodTok.file, methodTok.line,
                            "'" + genericMethodName + "' is a residual-generic method of this impl's own "
                                    + "(uses an impl-only type parameter), and cannot also be the name of "
                                    + "an interface method '" + interfaceName + "' requires");
                }
                implInfo.genericMethodTemplates.put(genericMethodName, methodTok);
                genericTemplateTokensToRemove.add(methodTok);
                continue;
            }
            if (methodTok.isStaticMethod) {
                // A static method cannot mention 'self' (signature or body).
                forbidSelfReference(methodTok, "a static method");
            } else {
                // 'self' is replaced by the concrete type name before buildFuncInfo.
                substituteSelfInPlace(methodTok.sub.get(1).childs, concreteName);
                substituteSelfInPlace(methodTok.sub.subList(2, methodTok.sub.size()), concreteName);
            }

            if (interfaceInfo == null) {
                // A bare impl has no interface: @default/@overrides/@realizes do not apply.
                for (String forbidden : new String[]{"default", "overrides", "realizes"}) {
                    Token.Decorator bad = getDecorator(methodTok.decorators, forbidden);
                    if (bad != null) {
                        throw new CompilerException("type", bad.file, bad.line,
                                "'@" + forbidden + "' only applies to a method implementing an "
                                        + "interface -- '" + concreteName + "' has no 'for InterfaceName' "
                                        + "clause on this impl");
                    }
                }
            }

            FuncInfo info = new FuncInfo();
            info.funcToken = methodTok;
            try {
                buildFuncInfo(methodTok, info);
            } catch (DeferredSignatureException e) {
                recordUnavailable(concreteName + "." + methodTok.sub.get(0).text, methodTok.sub.get(0).text,
                        concreteName, false, e);
                genericTemplateTokensToRemove.add(methodTok);
                droppedUnavailable = true;
                continue;
            }
            info.enclosingImpl = implInfo;
            info.declOrder = pendingDeclOrder.getOrDefault(t, Integer.MAX_VALUE);
            info.safetyTag = safetyTag;
            // Static methods get "static_" in the mangled name so they cannot collide with an instance method of the
            // same name.
            String methodNamePart = methodTok.isStaticMethod ? "static_" + info.name : info.name;
            info.mangledName = interfaceName != null
                    ? concreteName + "__" + interfaceName + "_" + methodNamePart
                    : concreteName + "_" + methodNamePart;
            validateMethodLocks(methodTok.decorators, info.paramNames, info.paramTypes, concreteName);

            registerImplMethod(implInfo, info, methodTok, concreteName, interfaceInfo != null);
        }
        t.childs.removeAll(genericTemplateTokensToRemove);

        if (interfaceInfo != null) {
            for (Map.Entry<String, RawMethodSig> entry : interfaceInfo.methods.entrySet()) {
                String methodName = entry.getKey();
                RawMethodSig ifaceSig = entry.getValue();
                boolean ifaceIsStatic = ifaceSig.isStatic;
                LinkedHashMap<String, FuncInfo> implMap = ifaceIsStatic ? implInfo.staticMethods : implInfo.methods;
                if (!implMap.containsKey(methodName)) {
                    // An omitted method is an error unless the interface gave it a @default body (materialized below).
                    if (ifaceSig.isDefault) {
                        continue;
                    }
                    throw new CompilerException("type", t.file, t.line,
                            "impl of '" + interfaceName + "' for '" + concreteName + "' is missing "
                                    + (ifaceIsStatic ? "static " : "") + "method '" + methodName + "'");
                }
            }
            for (Map.Entry<String, FuncInfo> entry : implInfo.methods.entrySet()) {
                String methodName = entry.getKey();
                RawMethodSig ifaceSig = interfaceInfo.methods.get(methodName);
                if (ifaceSig == null || ifaceSig.isStatic) {
                    throw new CompilerException("type", t.file, t.line,
                            "impl of '" + interfaceName + "' for '" + concreteName + "' declares '"
                                    + methodName + "', which is not part of the interface (an impl "
                                    + "must match the interface's method set exactly)");
                }
                validateExactSignatureMatch(ifaceSig, entry.getValue(), concreteName, interfaceName);
                validateOverridesRealizes(entry.getValue(), ifaceSig, concreteName, interfaceName);
                requireIdenticalLocks(entry.getValue(), ifaceSig, concreteName, interfaceName);
                requirePublicInterfaceImpl(entry.getValue(), methodName, concreteName, interfaceName);
            }
            for (Map.Entry<String, FuncInfo> entry : implInfo.staticMethods.entrySet()) {
                String methodName = entry.getKey();
                RawMethodSig ifaceSig = interfaceInfo.methods.get(methodName);
                if (ifaceSig == null || !ifaceSig.isStatic) {
                    throw new CompilerException("type", t.file, t.line,
                            "impl of '" + interfaceName + "' for '" + concreteName + "' declares static '"
                                    + methodName + "', which is not part of the interface's own static "
                                    + "method set (an impl must match the interface's method set exactly)");
                }
                validateExactSignatureMatch(ifaceSig, entry.getValue(), concreteName, interfaceName);
                validateOverridesRealizes(entry.getValue(), ifaceSig, concreteName, interfaceName);
                requireIdenticalLocks(entry.getValue(), ifaceSig, concreteName, interfaceName);
                requirePublicInterfaceImpl(entry.getValue(), methodName, concreteName, interfaceName);
            }

            // Materialize omitted @default methods last, after every written method has been validated.
            for (Map.Entry<String, RawMethodSig> entry : interfaceInfo.methods.entrySet()) {
                String methodName = entry.getKey();
                RawMethodSig ifaceSig = entry.getValue();
                LinkedHashMap<String, FuncInfo> implMap = ifaceSig.isStatic ? implInfo.staticMethods : implInfo.methods;
                if (!implMap.containsKey(methodName) && ifaceSig.isDefault) {
                    materializeDefaultMethod(implInfo, concreteName, interfaceName, methodName, ifaceSig,
                            safetyTag, t);
                }
            }
        }

        // An impl made only of constructors is not a "bare impl block" for the one-bare-impl rule.
        if (droppedUnavailable && t.childs.isEmpty()) {
            return; // every method of this impl instance was unavailable for this type argument
        }
        boolean constructorsOnlyImpl = interfaceName == null && !t.childs.isEmpty();
        for (Token ctorCandidate : t.childs) {
            if (!ctorCandidate.isConstructorDecl) {
                constructorsOnlyImpl = false;
                break;
            }
        }
        if (constructorsOnlyImpl) {
            return;
        }
        List<ImplInfo> existingImpls = implsByConcreteType.computeIfAbsent(concreteName, k -> new ArrayList<>());
        for (ImplInfo existing : existingImpls) {
            if (interfaceName == null && existing.interfaceName == null) {
                throw new CompilerException("type", t.file, t.line,
                        "'" + concreteName + "' already has a bare 'impl' block");
            }
            if (interfaceName != null && interfaceName.equals(existing.interfaceName)) {
                throw new CompilerException("type", t.file, t.line,
                        "'" + concreteName + "' already implements '" + interfaceName + "'");
            }
            for (Map.Entry<String, FuncInfo> entry : implInfo.methods.entrySet()) {
                FuncInfo mine = entry.getValue();
                for (FuncInfo existingMethod : existing.candidates(mine.name, false)) {
                    if (sameSignature(existingMethod, mine)) {
                        throw new CompilerException("type", t.file, t.line,
                                "method '" + mine.name + "' for '" + concreteName + "' is ambiguous: "
                                        + "both '" + describeImplSource(existing) + "' and '"
                                        + describeImplSource(implInfo) + "' declare a method with the same "
                                        + "name and parameter signature, which cannot be resolved at the "
                                        + "call site");
                    }
                }
            }
            for (Map.Entry<String, FuncInfo> entry : implInfo.staticMethods.entrySet()) {
                FuncInfo mine = entry.getValue();
                for (FuncInfo existingMethod : existing.candidates(mine.name, true)) {
                    if (sameSignature(existingMethod, mine)) {
                        throw new CompilerException("type", t.file, t.line,
                                "static method '" + mine.name + "' for '" + concreteName + "' is ambiguous: "
                                        + "both '" + describeImplSource(existing) + "' and '"
                                        + describeImplSource(implInfo) + "' declare a static method with the "
                                        + "same name and parameter signature, which cannot be resolved at the "
                                        + "call site");
                    }
                }
            }
        }
        existingImpls.add(implInfo);
    }

    /**
     * Materializes a callable entry for an interface @default method that this impl omits: the default body is
     * deep-cloned per impl and none of the user-impl validations apply. See docs/TypeChecker.md, "Default
     * interface methods".
     */
    private void materializeDefaultMethod(ImplInfo implInfo, String concreteName, String interfaceName,
            String methodName, RawMethodSig ifaceSig, String safetyTag, Token implDeclTok) {
        // The default body was never RPN-converted or tree-built (RpnConverter/TreeBuilder skip "interface"), so wrap
        // the clone and convert it here.
        Token methodTok = deepCloneCheckedTree(ifaceSig.defaultBodyTok);
        Token wrapLine = new Token(TokenType.LINE, "line", methodTok.line, methodTok.file);
        wrapLine.childs.add(methodTok);
        List<Token> wrapList = new ArrayList<>();
        wrapList.add(wrapLine);
        new RpnConverter().convertLines(wrapList);
        new TreeBuilder().buildLines(wrapList);
        if (!methodTok.isStaticMethod) {
            substituteSelfInPlace(methodTok.sub.get(1).childs, concreteName);
            substituteSelfInPlace(methodTok.sub.subList(2, methodTok.sub.size()), concreteName);
        }
        FuncInfo info = new FuncInfo();
        info.funcToken = methodTok;
        buildFuncInfo(methodTok, info);
        // Must be public to satisfy the interface; forced because the @default source need not carry @pub.
        info.isPublic = true;
        info.enclosingImpl = implInfo;
        info.declOrder = pendingDeclOrder.getOrDefault(implDeclTok, Integer.MAX_VALUE);
        info.safetyTag = safetyTag;
        String methodNamePart = methodTok.isStaticMethod ? "static_" + info.name : info.name;
        info.mangledName = concreteName + "__" + interfaceName + "_" + methodNamePart;
        validateMethodLocks(methodTok.decorators, info.paramNames, info.paramTypes, concreteName);
        LinkedHashMap<String, FuncInfo> targetMap = methodTok.isStaticMethod
                ? implInfo.staticMethods : implInfo.methods;
        targetMap.put(info.name, info);
        // BytecodeEmitter emits impl methods by walking the impl token's childs (matching FuncInfo by Token identity),
        // so the cloned token must be appended there or the method is never emitted.
        implDeclTok.childs.add(methodTok);
    }

    /**
     * Deep-clones a gathered, tree-built, not yet type-checked Token tree (a copy of GenericsExpander's private
     * deepClone; Token.deepCloneRaw has a narrower field set).
     */
    private Token deepCloneCheckedTree(Token src) {
        Token copy = new Token(src.type, src.text, src.line, src.file);
        copy.literalValue = src.literalValue;
        copy.quoteDelimiter = src.quoteDelimiter;
        copy.forcedLiteralType = src.forcedLiteralType;
        copy.pinnedComments = src.pinnedComments;
        copy.unary = src.unary;
        copy.typeParams = src.typeParams == null ? null : new ArrayList<>(src.typeParams);
        copy.typeParamBounds = src.typeParamBounds == null ? null : new ArrayList<>(src.typeParamBounds);
        copy.decorators = src.decorators;
        copy.unsafeTags = src.unsafeTags;
        copy.isPubBlockMember = src.isPubBlockMember;
        copy.isStatic = src.isStatic;
        copy.isConst = src.isConst;
        if (src.typeBound == null) {
            copy.typeBound = null;
        } else {
            copy.typeBound = new ArrayList<>();
            for (Token bt : src.typeBound) {
                copy.typeBound.add(deepCloneCheckedTree(bt));
            }
        }
        copy.left = src.left == null ? null : deepCloneCheckedTree(src.left);
        copy.right = src.right == null ? null : deepCloneCheckedTree(src.right);
        copy.childs = new ArrayList<>();
        if (src.childs != null) {
            for (Token c : src.childs) {
                copy.childs.add(deepCloneCheckedTree(c));
            }
        }
        copy.sub = new ArrayList<>();
        if (src.sub != null) {
            for (Token s : src.sub) {
                copy.sub.add(deepCloneCheckedTree(s));
            }
        }
        if (src.genericArgs != null) {
            copy.genericArgs = new ArrayList<>();
            for (List<Token> group : src.genericArgs) {
                List<Token> copiedGroup = new ArrayList<>();
                for (Token g : group) {
                    copiedGroup.add(deepCloneCheckedTree(g));
                }
                copy.genericArgs.add(copiedGroup);
            }
        }
        copy.isStaticMethod = src.isStaticMethod;
        copy.extendsNames = src.extendsNames == null ? null : new ArrayList<>(src.extendsNames);
        copy.implementsNames = src.implementsNames == null ? null : new ArrayList<>(src.implementsNames);
        return copy;
    }

    /**
     * Builds a library's own function set (self-less; overloaded by full signature, unlike functions and impl
     * methods). See docs/TypeChecker.md, "Libraries".
     */
    private void collectLibrary(Token t, String safetyTag) {
        String name = t.sub.get(0).text;
        LibraryInfo info = libraries.get(name);
        info.ownFunctions = new LinkedHashMap<>();
        for (Token funcTok : t.childs) {
            forbidSelfReference(funcTok, "a library function");
            for (String forbidden : new String[]{"default", "overrides", "realizes"}) {
                Token.Decorator bad = getDecorator(funcTok.decorators, forbidden);
                if (bad != null) {
                    throw new CompilerException("type", bad.file, bad.line,
                            "'@" + forbidden + "' is only valid on an interface method or an impl method, "
                                    + "not a library function");
                }
            }
            FuncInfo fi = new FuncInfo();
            fi.funcToken = funcTok;
            buildFuncInfo(funcTok, fi);
            fi.enclosingLibrary = info;
            fi.declOrder = pendingDeclOrder.getOrDefault(t, Integer.MAX_VALUE);
            fi.safetyTag = safetyTag;
            fi.mangledName = name + "_" + fi.name + "__sig_" + signatureSuffix(fi.paramTypes);

            List<FuncInfo> overloads = info.ownFunctions.computeIfAbsent(fi.name, k -> new ArrayList<>());
            for (FuncInfo existing : overloads) {
                if (sameSignature(existing, fi)) {
                    throw new CompilerException("type", funcTok.file, funcTok.line,
                            "library '" + name + "' already declares a function named '" + fi.name
                                    + "' with this parameter signature");
                }
            }
            overloads.add(fi);
        }
    }

    /**
     * Registers a method into implInfo. A bare impl allows overloads by signature (unique mangled name
     * "<first>__sig_<signature>"; the first keeps its name). An interface impl cannot overload. See
     * docs/TypeChecker.md, "Impl method overloading".
     */
    private void registerImplMethod(ImplInfo implInfo, FuncInfo info, Token methodTok, String concreteName,
            boolean hasInterface) {
        boolean isStatic = methodTok.isStaticMethod;
        LinkedHashMap<String, FuncInfo> targetMap = isStatic ? implInfo.staticMethods : implInfo.methods;
        LinkedHashMap<String, List<FuncInfo>> overloadMap =
                isStatic ? implInfo.staticMethodOverloads : implInfo.methodOverloads;
        String kind = isStatic ? "static " : "";
        List<FuncInfo> existing = overloadMap.get(info.name);
        if (existing == null && targetMap.containsKey(info.name)) {
            existing = new ArrayList<>();
            existing.add(targetMap.get(info.name));
        }
        if (existing == null) {
            targetMap.put(info.name, info);
            List<FuncInfo> fresh = new ArrayList<>();
            fresh.add(info);
            overloadMap.put(info.name, fresh);
            return;
        }
        if (hasInterface) {
            throw new CompilerException("type", methodTok.file, methodTok.line,
                    "'" + concreteName + "' already has a " + kind + "method named '" + info.name
                            + "' in this impl block");
        }
        for (FuncInfo other : existing) {
            if (sameSignature(other, info)) {
                throw new CompilerException("type", methodTok.file, methodTok.line,
                        "'" + concreteName + "' already has a " + kind + "method named '" + info.name
                                + "' with this parameter signature in this impl block");
            }
        }
        info.mangledName = info.mangledName + "__sig_" + signatureSuffix(info.paramTypes);
        targetMap.put(info.name + "#" + signatureSuffix(info.paramTypes), info);
        if (!overloadMap.containsKey(info.name)) {
            overloadMap.put(info.name, existing);
        }
        existing.add(info);
    }

    /**
     * Parameter-type suffix for overload mangled names; always appended for library functions so same-named
     * overloads never collide at FUNC_START/CALL.
     */
    private String signatureSuffix(List<TypeInfo> paramTypes) {
        if (paramTypes.isEmpty()) {
            return "void";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) {
                sb.append('_');
            }
            sb.append(paramTypes.get(i).canonical());
        }
        // The suffix ends up in an assembly label; a parameter type such
        // as owns_mut_dynarray(u64) contains characters no assembler
        // accepts in a symbol, so anything outside [A-Za-z0-9_] becomes '_'.
        return sb.toString().replaceAll("[^A-Za-z0-9_]", "_");
    }

    /**
     * Flattens a library's function set: multiple extends, only a full-signature collision is an error, functions
     * inherited by reference. See docs/TypeChecker.md, "Libraries".
     */
    private void flattenLibrary(String name, Set<String> visiting) {
        LibraryInfo info = libraries.get(name);
        if (info == null || info.flattened) {
            return;
        }
        if (visiting.contains(name)) {
            throw new CompilerException("type", info.declTok.file, info.declTok.line,
                    "circular inheritance involving library '" + name + "'");
        }
        visiting.add(name);

        for (String parentName : info.extendsNames) {
            LibraryInfo parentInfo = libraries.get(parentName);
            if (parentInfo == null) {
                throw new CompilerException("type", info.declTok.file, info.declTok.line,
                        "'" + parentName + "' is not a known library");
            }
            flattenLibrary(parentName, visiting);
            for (Map.Entry<String, List<FuncInfo>> e : parentInfo.functions.entrySet()) {
                mergeLibraryOverloads(info, name, e.getKey(), e.getValue());
            }
        }

        for (Map.Entry<String, List<FuncInfo>> e : info.ownFunctions.entrySet()) {
            mergeLibraryOverloads(info, name, e.getKey(), e.getValue());
        }

        info.flattened = true;
        visiting.remove(name);
    }

    /**
     * Merges one function name's overloads into `info.functions`, rejecting only a full-signature collision (see
     * flattenLibrary).
     */
    private void mergeLibraryOverloads(LibraryInfo info, String name, String funcName, List<FuncInfo> incoming) {
        List<FuncInfo> existing = info.functions.computeIfAbsent(funcName, k -> new ArrayList<>());
        for (FuncInfo candidate : incoming) {
            for (FuncInfo already : existing) {
                if (sameSignature(already, candidate)) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "library '" + name + "' inherits a function named '" + funcName
                                    + "' with this exact parameter signature from more than one extended "
                                    + "library -- rename one of them or drop the conflicting 'extends'");
                }
            }
            existing.add(candidate);
        }
    }

    /**
     * Library visibility, mirroring checkImplMethodVisibility: the declaring library must be @pub (or in the same
     * file), then the function must be public or called from the same library.
     */
    private void requireLibraryVisibility(FuncInfo candidate, String funcName, Token at, FuncInfo callerFunc) {
        LibraryInfo declLib = candidate.enclosingLibrary;
        String declFile = candidate.funcToken.file;
        boolean sameFile = declFile.equals(at.file);
        if (!declLib.isPublic && !sameFile) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + declLib.name + "' is not '@pub' -- not accessible outside the file it's declared "
                            + "in");
        }
        if (candidate.isPublic) {
            return;
        }
        if (callerFunc == null || callerFunc.enclosingLibrary != declLib) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + funcName + "' is private -- it can only be referenced by another function within "
                            + "that same library");
        }
    }

    /**
     * "Lib.f(args)": library counterpart of checkStaticMethodCall (overload search, call-edge bookkeeping); no
     * guard-lock or method-lock machinery applies.
     */
    private TypeInfo checkLibraryCall(Token op, String libName, String funcName, Scope scope, FuncInfo func) {
        LibraryInfo lib = libraries.get(libName);
        List<FuncInfo> overloads = lib.functions.get(funcName);
        if (overloads == null) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + libName + "' has no function named '" + funcName + "'");
        }

        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        List<TypeInfo> argTypes = new ArrayList<>();
        for (Token argNode : argNodes) {
            argTypes.add(resolveExprType(argNode, scope, func));
        }

        for (FuncInfo candidate : orderByLiteralExactness(overloads, argTypes)) {
            if (candidate.paramTypes.size() != argTypes.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < argTypes.size(); i++) {
                if (!argSatisfiesParam(candidate.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                retagLiteralArgs(candidate.paramTypes, argTypes, argNodes);
                requireLibraryVisibility(candidate, funcName, op, func);
                op.resolvedCallTarget = candidate.mangledName;
                op.resolvedCallConvention = candidate.callConvention;
                requireThrowsWrapping(candidate, op);
                for (int i = 0; i < argNodes.size(); i++) {
                    markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
                }
                // Per-call-site destruct list, as in checkThrow (BytecodeEmitter uses it only when the program uses
                // throw).
                op.destructOnExit = collectOwnsToDestruct(scope, scope.functionRootScope, null);
                op.unlockOnExit = collectLockReleasesToBoundary(scope, scope.functionRootScope);
                if (candidate == func && scope.suspendsSelfRecursion) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot call '" + func.name + "' from within its own base-case match "
                                    + "block -- the func loses its recursive privileges there");
                }
                if (candidate == func && getDecorator(func.funcToken.decorators, "recursive") != null) {
                    requireRecursiveCallReturnSiteOnly(func, op);
                    requireRecursiveCallWithinProof(func, op, argNodes, argTypes, scope);
                }
                requirePureCalleeIfPure(func, candidate, op);
                callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
                return candidate.returnType;
            }
        }
        throw new CompilerException("type", op.file, op.line,
                "'" + libName + "' has no function '" + funcName + "' matching argument types ("
                        + describeTypes(argTypes) + ")");
    }

    private String describeImplSource(ImplInfo info) {
        return info.interfaceName != null
                ? "impl " + info.interfaceName + " for " + info.concreteName
                : "impl " + info.concreteName;
    }

    private void substituteSelfInPlace(List<Token> tokens, String concreteName) {
        for (Token t : tokens) {
            if (t.type == TokenType.VARREF && t.text.equals("self")) {
                t.text = concreteName;
            }
        }
    }

    /** self-substituted copy, used only for re-resolving the interface's own raw signature at impl-validation time -- doesn't mutate the interface's own tokens. */
    private List<Token> substituteSelfCopy(List<Token> tokens, String concreteName) {
        List<Token> result = new ArrayList<>();
        for (Token t : tokens) {
            if (t.type == TokenType.VARREF && t.text.equals("self")) {
                result.add(new Token(TokenType.VARREF, concreteName, t.line, t.file));
            } else {
                result.add(t);
            }
        }
        return result;
    }

    /** Every method satisfying an interface must be @pub (interface members are public). */
    private void requirePublicInterfaceImpl(FuncInfo implMethod, String methodName, String concreteName,
            String interfaceName) {
        if (!implMethod.isPublic) {
            throw new CompilerException("type", implMethod.funcToken.file, implMethod.funcToken.line,
                    "'" + concreteName + "'s '" + methodName + "' implements interface '" + interfaceName
                            + "', so it must be '@pub'");
        }
    }

    /**
     * Each interface-impl method must carry exactly one of @overrides (the interface method is @default) or
     * @realizes (it is a plain signature).
     */
    private void validateOverridesRealizes(FuncInfo implMethod, RawMethodSig interfaceMethod,
            String concreteName, String interfaceName) {
        List<Token.Decorator> decorators = implMethod.funcToken.decorators;
        boolean hasOverrides = hasDecorator(decorators, "overrides");
        boolean hasRealizes = hasDecorator(decorators, "realizes");
        Token at = implMethod.funcToken;
        if (hasOverrides && hasRealizes) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + concreteName + "'s '" + implMethod.name + "' cannot be labelled both "
                            + "'@overrides' and '@realizes'");
        }
        if (interfaceMethod.isDefault) {
            if (hasRealizes) {
                throw new CompilerException("type", at.file, at.line,
                        "'@realizes' is for fulfilling a plain interface method -- '" + interfaceName
                                + "'s '" + implMethod.name + "' is '@default'; use '@overrides' instead");
            }
            if (!hasOverrides) {
                throw new CompilerException("type", at.file, at.line,
                        "'" + concreteName + "'s '" + implMethod.name + "' overrides a '@default' method "
                                + "of '" + interfaceName + "' and must be labelled '@overrides'");
            }
        } else {
            if (hasOverrides) {
                throw new CompilerException("type", at.file, at.line,
                        "'@overrides' is for overriding a '@default' method -- '" + interfaceName + "'s '"
                                + implMethod.name + "' is a plain signature; use '@realizes' instead");
            }
            if (!hasRealizes) {
                throw new CompilerException("type", at.file, at.line,
                        "'" + concreteName + "'s '" + implMethod.name + "' realizes '" + interfaceName
                                + "'s '" + implMethod.name + "' and must be labelled '@realizes'");
            }
        }
    }

    /**
     * An interface method's "@lock(match ...)" qualifiers must be repeated exactly, in order, by each implementing
     * method; "in"-style clauses match by parameter position, not name.
     */
    private void requireIdenticalLocks(FuncInfo actual, RawMethodSig ifaceSig, String concreteName,
            String interfaceName) {
        List<Token.Decorator> ifaceLocks = filterQualifiedLockDecorators(ifaceSig.decorators);
        List<Token.Decorator> implLocks = filterQualifiedLockDecorators(actual.funcToken.decorators);
        Token at = actual.funcToken;
        if (ifaceLocks.size() != implLocks.size()) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + concreteName + "'s '" + actual.name + "' must carry exactly " + ifaceLocks.size()
                            + " '@lock(match ...)' qualifier(s) to match '" + interfaceName + "'s own "
                            + "declaration -- found " + implLocks.size());
        }
        List<String> ifaceParamNames = new ArrayList<>();
        for (List<Token> group : splitIntoRawGroups(ifaceSig.paramsTok.childs)) {
            ifaceParamNames.add(group.get(0).text);
        }
        for (int idx = 0; idx < ifaceLocks.size(); idx++) {
            Token.Decorator ifaceLock = ifaceLocks.get(idx);
            Token.Decorator implLock = implLocks.get(idx);
            String mismatch = describeLockMismatch(ifaceLock, implLock, ifaceParamNames, actual.paramNames);
            if (mismatch != null) {
                throw new CompilerException("type", at.file, at.line,
                        "'" + concreteName + "'s '" + actual.name + "' own '@lock(...)' #" + (idx + 1)
                                + " doesn't match '" + interfaceName + "'s declaration: " + mismatch);
            }
        }
    }

    /** Every "@lock(match ...)" decorator (either qualified shape) on `decorators`, in source order -- excludes the plain, argument-less "@lock" (the @guard method decorator), which `requireIdenticalLocks` has nothing to say about. */
    private List<Token.Decorator> filterQualifiedLockDecorators(List<Token.Decorator> decorators) {
        List<Token.Decorator> result = new ArrayList<>();
        if (decorators == null) {
            return result;
        }
        for (Token.Decorator d : decorators) {
            if (d.name.equals("lock") && (d.lockFieldName != null || d.lockInParams != null)) {
                result.add(d);
            }
        }
        return result;
    }

    /** Null if `ifaceLock` and `implLock` describe the identical requirement; otherwise a short, human-readable reason they don't. */
    private String describeLockMismatch(Token.Decorator ifaceLock, Token.Decorator implLock,
            List<String> ifaceParamNames, List<String> implParamNames) {
        if (ifaceLock.lockFieldName != null) {
            if (implLock.lockFieldName == null) {
                return "the interface's own qualifier is enum-style ('match self.x : X'), the "
                        + "implementation's own isn't";
            }
            if (!ifaceLock.lockFieldName.equals(implLock.lockFieldName)
                    || !ifaceLock.lockVariants.equals(implLock.lockVariants)) {
                return "expected 'match self." + ifaceLock.lockFieldName + " : "
                        + String.join("|", ifaceLock.lockVariants) + "'";
            }
            return null;
        }
        if (implLock.lockInParams == null) {
            return "the interface's own qualifier is 'in'-style, the implementation's own isn't";
        }
        if (ifaceLock.lockInParams.size() != implLock.lockInParams.size()) {
            return "expected " + ifaceLock.lockInParams.size() + " 'in'-clause(s), got "
                    + implLock.lockInParams.size();
        }
        for (int j = 0; j < ifaceLock.lockInParams.size(); j++) {
            int ifacePos = ifaceParamNames.indexOf(ifaceLock.lockInParams.get(j));
            int implPos = implParamNames.indexOf(implLock.lockInParams.get(j));
            // The operator must match too ("in" vs "within").
            if (ifacePos != implPos || !ifaceLock.lockInFields.get(j).equals(implLock.lockInFields.get(j))
                    || !ifaceLock.lockInOperators.get(j).equals(implLock.lockInOperators.get(j))) {
                return "clause " + (j + 1) + " must be on the same parameter (by position), the same "
                        + "field, and the same operator as the interface's own '"
                        + ifaceLock.lockInOperators.get(j) + " self." + ifaceLock.lockInFields.get(j) + "'";
            }
            // Some(...)-wrapping must match exactly (no superset/subset relation).
            boolean ifaceSome = ifaceLock.lockInSomeWrapped != null
                    && Boolean.TRUE.equals(ifaceLock.lockInSomeWrapped.get(j));
            boolean implSome = implLock.lockInSomeWrapped != null
                    && Boolean.TRUE.equals(implLock.lockInSomeWrapped.get(j));
            if (ifaceSome != implSome) {
                return "clause " + (j + 1) + " must match the interface's own use of 'Some(...)' exactly -- "
                        + "the interface's own clause is "
                        + (ifaceSome ? "'Some(...)'-wrapped" : "not 'Some(...)'-wrapped")
                        + ", the implementation's own isn't the same";
            }
        }
        return null;
    }

    /**
     * True when `t` is a struct with a swap-tagged mutex field: the only kind of global/static safe code may touch
     * directly.
     */
    private boolean isSwapLockStructType(TypeInfo t) {
        StructInfo structInfo = structs.get(t.baseType);
        return structInfo != null && structInfo.swapFieldName != null;
    }

    /**
     * True for a direct (optionally mut/imut-wrapped) "atomic" operator, i.e. a new atomic declaration, as opposed
     * to an expression that merely resolves to an atomic type (reading one yields an ordinary non-atomic local).
     */
    private boolean isDirectAtomicDeclaration(Token expr) {
        Token t = expr;
        while (t.type == TokenType.OPERATOR && t.unary && (t.text.equals("mut") || t.text.equals("imut"))) {
            t = t.left;
        }
        return t.type == TokenType.OPERATOR && t.text.equals("atomic");
    }

    /**
     * A global or static local can only be touched (read or written) outside `unsafe` code when its own type is an atomic primitive
     * (`isAtomic`, and then only from `unsafe atomic{}`) or a struct declaring a 'swap'-tagged atomic mutex-state field
     * (`isSwapLockStructType`, touched through its own `match @lock`).
     */
    private void requireSharedStateOrUnsafe(TypeInfo t, String name, Scope scope, Token at) {
        if (t.isAtomic) {
            // An atomic primitive: each access is one atomic step but program correctness cannot be checked, so every
            // access needs an audited `unsafe atomic{}`.
            if (!unsafeBypass(scope, "atomic")) {
                throw new CompilerException("type", at.file, at.line,
                        "'" + name + "' is an atomic -- outside a lock it can only be read, written or swapped from 'unsafe atomic{...}' code; "
                                + "shared state in safe code belongs in a struct with a 'swap'-tagged mutex-state field, touched through 'match @lock'");
            }
            return;
        }
        if (!isSwapLockStructType(t) && !unsafeBypass(scope, "global")) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + name + "' is a global/static variable -- in safe code, only a struct with a 'swap'-tagged atomic mutex-state "
                            + "field (touched through its own 'match @lock') can be accessed without 'unsafe' code");
        }
    }

    /**
     * A @pure function may not read mutable globals/statics (imut ones are fine); @pure(rt) may read none. Unsafe
     * code is not exempt. See docs/TypeChecker.md, "Purity".
     */
    private void requirePureDoesNotReadMutableStatic(TypeInfo t, String name, FuncInfo func, Token at) {
        if (isPureRtFunc(func)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '@pure(rt)' and cannot read '" + name
                            + "' -- a global/static variable is memory the arguments do not determine");
        }
        if (isPureFunc(func) && "mut".equals(t.mutability)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '@pure' and cannot read '" + name
                            + "' -- it is a mutable global/static variable");
        }
    }

    private void validateExactSignatureMatch(RawMethodSig rawSig, FuncInfo actual, String concreteName,
            String interfaceName) {
        List<List<Token>> rawParamGroups = splitIntoRawGroups(rawSig.paramsTok.childs);
        if (rawParamGroups.size() != actual.paramTypes.size()) {
            throw new CompilerException("type", actual.funcToken.file, actual.funcToken.line,
                    "method '" + actual.name + "' in impl of '" + interfaceName + "' for '" + concreteName
                            + "' has " + actual.paramTypes.size() + " parameter(s), interface declares "
                            + rawParamGroups.size());
        }
        for (int i = 0; i < rawParamGroups.size(); i++) {
            List<Token> group = rawParamGroups.get(i);
            List<Token> typeTokens = substituteSelfCopy(group.subList(2, group.size()), concreteName);
            TypeInfo expected = resolveTypeAnnotation(typeTokens, group.get(0));
            TypeInfo got = actual.paramTypes.get(i);
            if (!expected.canonical().equals(got.canonical())) {
                throw new CompilerException("type", actual.funcToken.file, actual.funcToken.line,
                        "method '" + actual.name + "' parameter " + (i + 1) + " must be exactly '"
                                + expected.canonical() + "' to match interface '" + interfaceName
                                + "', got '" + got.canonical() + "'");
            }
        }
        List<Token> returnTypeTokens = substituteSelfCopy(rawSig.returnTypeTokens, concreteName);
        TypeInfo expectedReturn = returnTypeTokens.isEmpty()
                ? new TypeInfo(null, "imut", "void")
                : resolveTypeAnnotation(returnTypeTokens, rawSig.nameTok, true);
        if (!expectedReturn.canonical().equals(actual.returnType.canonical())) {
            throw new CompilerException("type", actual.funcToken.file, actual.funcToken.line,
                    "method '" + actual.name + "' must return exactly '" + expectedReturn.canonical()
                            + "' to match interface '" + interfaceName + "', got '"
                            + actual.returnType.canonical() + "'");
        }
    }

    // ---- decorators ("@name(args)") -------------------------------------

    /** Valid on any func-shaped token (top-level func, interface default method, or impl method) regardless of context; @default/@overrides/@realizes are validated separately, per-context, since which of those (if any) is *required* depends on where the func sits. */
    private static final Set<String> FUNC_BASE_DECORATORS = new HashSet<>(Arrays.asList(
            "pure", "recursive", "inline", "call_convention", "reads", "writes",
            "gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve", "par_call", "await_call", "sleep",
            "lock", "async", "pub", "with_tick", "tick", "event_loop", "make_safe_args", "throws", "drop", "fs_root", "fs_unsafe", "non"));
    /** `@drop` marks a struct's cleanup function: the drop glue calls it with the struct's address just before the struct (an `owns` value) is freed, so a handle type can close what it holds. It takes exactly one parameter, `ref some mut S` for a struct S, returns void and does not throw. */
    private void requireDropHookShape(Token t, FuncInfo info, List<Token> returnTypeTokens) {
        TypeInfo p = info.paramTypes.size() == 1 ? info.paramTypes.get(0) : null;
        boolean shapeOk = p != null && "ref".equals(p.storage) && p.isSome && structs.containsKey(p.baseType);
        if (!shapeOk || info.isThrows || info.isAsync) {
            throw new CompilerException("type", t.file, t.line,
                    "'@drop' function '" + info.name + "' must take exactly one parameter of type 'ref some mut <struct>', must not be '@throws' or '@async', and returns void");
        }
        String prior = dropHookOwners.put(p.baseType, info.name);
        if (prior != null && !prior.equals(info.name)) {
            throw new CompilerException("type", t.file, t.line,
                    "struct '" + p.baseType + "' already has a '@drop' function ('" + prior + "')");
        }
    }
    private final Map<String, String> dropHookOwners = new HashMap<>();

    /** True for a file inside the compiler's own stdlib folder (../stdlib from the ASTGenerator working directory). */
    private static boolean isStdlibFile(String file) {
        try {
            java.nio.file.Path stdlib = java.nio.file.Paths.get("..", "stdlib").toAbsolutePath().normalize();
            return java.nio.file.Paths.get(file).toAbsolutePath().normalize().startsWith(stdlib);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private final List<String> fsReportLines = new ArrayList<>();

    /** `--fs-report`: what the program may do to the file system, as seen by the compiler. */
    void printFsReport(java.io.PrintStream out) {
        FsPolicy p = FsPolicy.current;
        out.println("[fs-report] roots: " + (p.rootsEnforced ? (p.roots.isEmpty() ? "(none: every path open is refused)" : p.runtimeRootsText()) : "(no fs-roots in fs.config: unrestricted)"));
        out.println("[fs-report] denied externs: " + (p.denyExterns.isEmpty() ? "(none)" : String.join(", ", p.denyExterns)));
        for (String l : fsReportLines) {
            out.println("[fs-report] " + l);
        }
        out.println("[fs-report] not covered: 'unsafe asm' (raw syscalls), 'call(...)' function pointers, extern names not in the deny list, and the C library functions the stdlib itself links.");
    }

    /** Checks the file-system rules of a call to a static method carrying '@fs_root' (literal path, checked against fs-roots) or '@fs_unsafe' (needs 'unsafe file'). */
    private void checkFsPolicyAtCall(FuncInfo callee, Token op, List<Token> argNodes, Scope scope) {
        Token.Decorator root = getDecorator(callee.funcToken.decorators, "fs_root");
        if (root != null) {
            boolean rw = !root.args.isEmpty() && root.args.get(0).equals("rw");
            Token a0 = argNodes.isEmpty() ? null : argNodes.get(0);
            if (a0 != null && a0.type == TokenType.STRING) {
                String why = FsPolicy.current.checkLiteralRoot(a0.literalValue, rw);
                if (why != null) {
                    throw new CompilerException("type", op.file, op.line,
                            "'" + callee.name + "' cannot open this path: " + why);
                }
                fsReportLines.add(op.file + ":" + op.line + " opens \"" + a0.literalValue + "\" " + (rw ? "read-write" : "read-only"));
            } else {
                throw new CompilerException("type", op.file, op.line,
                        "'" + callee.name + "' needs a string literal path inside an fs-roots entry; for a computed path use Root.check(path) "
                                + "to get a checked RootPath, or 'unsafe file{ Dir.rootAny(path) }'");
            }
        }
        if (getDecorator(callee.funcToken.decorators, "fs_unsafe") != null) {
            if (!unsafeBypass(scope, "file")) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + callee.name + "' opens an unchecked path and needs 'unsafe file{ ... }'");
            }
            fsReportLines.add(op.file + ":" + op.line + " UNSAFE unchecked path via " + callee.name);
        }
    }
    /** True when the type text names (directly, through struct members, or as a dynarray element) a struct that has a `@drop` hook. */
    private boolean typeReachesDropHook(String baseType, Set<String> seen) {
        if (baseType == null) {
            return false;
        }
        for (String id : baseType.split("[^A-Za-z0-9_]+")) {
            if (id.isEmpty() || !seen.add(id)) {
                continue;
            }
            if (dropHookOwners.containsKey(id)) {
                return true;
            }
            StructInfo si = structs.get(id);
            if (si != null) {
                for (TypeInfo m : si.members.values()) {
                    if (typeReachesDropHook(m.baseType, seen)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static final Set<String> STRUCT_DECORATORS = new HashSet<>(Arrays.asList("unpadded", "untyped", "pub", "lock"));
    private static final Set<String> ENUM_DECORATORS = new HashSet<>(Arrays.asList("non_exhaustive"));
    private static final Set<String> INTERFACE_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    /** Only "pub" is allowed on an impl. */
    private static final Set<String> IMPL_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    private static final Set<String> LIBRARY_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    /** A top-level "let static" has its own decorator allowlist. */
    private static final Set<String> GLOBAL_DECORATORS = new HashSet<>(Arrays.asList("pub"));
    private static final Set<String> LOOP_DECORATORS = new HashSet<>(Arrays.asList("par", "unroll"));
    private static final Set<String> READS_WRITES_ARGS = new HashSet<>(Arrays.asList("all", "self", "others", "globals"));

    private boolean hasDecorator(List<Token.Decorator> decorators, String name) {
        return getDecorator(decorators, name) != null;
    }

    private Token.Decorator getDecorator(List<Token.Decorator> decorators, String name) {
        if (decorators == null) {
            return null;
        }
        for (Token.Decorator d : decorators) {
            if (d.name.equals(name)) {
                return d;
            }
        }
        return null;
    }

    /**
     * The "@call_convention(name)" argument if present (already validated), else compiler.config's default. Shared
     * by func and extern collection.
     */
    private String resolveCallConvention(List<Token.Decorator> decorators) {
        Token.Decorator d = getDecorator(decorators, "call_convention");
        return d != null ? d.args.get(0) : config.defaultConvention;
    }

    private boolean isPureFunc(FuncInfo f) {
        return f != null && f.funcToken != null && getDecorator(f.funcToken.decorators, "pure") != null;
    }

    /** `@pure(rt)`: referentially transparent (the argument was validated to be exactly `rt`). */
    private boolean isPureRtFunc(FuncInfo f) {
        if (f == null || f.funcToken == null) {
            return false;
        }
        Token.Decorator d = getDecorator(f.funcToken.decorators, "pure");
        return d != null && !d.args.isEmpty();
    }

    private static String pureKind(boolean rt) {
        return rt ? "@pure(rt)" : "@pure";
    }

    /** Things a plain `@pure` function (and therefore a `@pure(rt)` one) may not do: unsafe code, the `match @lock` spin lock, threads. Compiler-synthesized unsafe blocks never reach this. */
    private void requireNotPure(FuncInfo func, Token at, String what, String why) {
        if (isPureFunc(func)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '" + pureKind(isPureRtFunc(func)) + "' and cannot use " + what + " -- " + why);
        }
    }

    /** Things only a `@pure(rt)` function may not do: allocation, randomness, reading through a pointer. A plain `@pure` function may (they are non-deterministic, not side effects). */
    private void requireNotPureRt(FuncInfo func, Token at, String what, String why) {
        if (isPureRtFunc(func)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '@pure(rt)' (referentially transparent) and cannot use " + what + " -- " + why);
        }
    }

    /** A `@pure(rt)` function never holds a pointer: no `ref`/`raw`/`owns`/`auto`/`static` typed value and no dynarray (reading through one depends on memory the arguments do not determine). */
    private void requirePureRtNoPointer(TypeInfo t, Token at, FuncInfo func, String where) {
        if (t != null && (t.storage != null || t.dynArrayElementType != null)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '@pure(rt)' (referentially transparent) and cannot have a pointer or dynamic array ('"
                            + t.canonical() + "') " + where + " -- it may not read memory through pointers");
        }
    }

    /**
     * Per-edge rule (not transitive): a @pure caller may only call @pure callees, a @pure(rt) caller only
     * @pure(rt) ones. Checked even in unsafe code. See docs/TypeChecker.md, "Purity".
     */
    private void requirePureCalleeIfPure(FuncInfo caller, FuncInfo callee, Token at) {
        if (isPureFunc(caller) && !isPureFunc(callee)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + caller.name + "' is '" + pureKind(isPureRtFunc(caller)) + "' and can only call other '@pure' functions -- '"
                            + callee.name + "' is not");
        }
        if (isPureRtFunc(caller) && !isPureRtFunc(callee)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + caller.name + "' is '@pure(rt)' and can only call other '@pure(rt)' functions -- '"
                            + callee.name + "' is only '@pure'" + (callee.funcToken != null && getDecorator(callee.funcToken.decorators, "non") != null ? " and '@non(deterministic)'" : ""));
        }
    }

    /**
     * Checks each decorator is a real one, allowed in this context, with the right argument shape. "@dont(X)" is
     * valid exactly where X is.
     */
    private void validateDecorators(List<Token.Decorator> decorators, Set<String> allowedNames, String contextDesc) {
        if (decorators == null) {
            return;
        }
        for (Token.Decorator d : decorators) {
            if (d.name.equals("dont")) {
                if (d.args.size() != 1) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@dont' takes exactly one argument (par/unroll/inline)");
                }
                String target = d.args.get(0);
                if (!allowedNames.contains(target)) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@dont(" + target + ")' is not valid on " + contextDesc);
                }
                continue;
            }
            if (!allowedNames.contains(d.name)) {
                throw new CompilerException("type", d.file, d.line,
                        "'@" + d.name + "' is not a valid decorator on " + contextDesc);
            }
            if (d.name.equals("lock") && d.lockFieldName == null && d.lockInParams == null) {
                throw new CompilerException("type", d.file, d.line,
                        "a bare '@lock' method decorator has been removed (it belonged to '@guard'); write '@lock(match self.field : VARIANT)' "
                                + "or '@lock(match x in self.field)'");
            }
            validateDecoratorArgShape(d);
        }
        Token.Decorator pureDec = getDecorator(decorators, "pure");
        Token.Decorator nonDec = getDecorator(decorators, "non");
        if (pureDec != null && !pureDec.args.isEmpty() && nonDec != null) {
            throw new CompilerException("type", nonDec.file, nonDec.line,
                    "'@pure(rt)' (referentially transparent) cannot also be '@non(deterministic)'");
        }
    }

    private void validateDecoratorArgShape(Token.Decorator d) {
        switch (d.name) {
            case "call_convention":
                if (d.args.size() != 1) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@call_convention' takes exactly one argument, e.g. '@call_convention(win64)'");
                }
                if (d.argIsString.get(0)) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@call_convention' takes a bare name, not a string -- '@call_convention("
                                    + d.args.get(0) + ")', not '@call_convention(\"" + d.args.get(0) + "\")'");
                }
                // Accepted names are read live from compiler.config.
                if (!config.callingConventions.containsKey(d.args.get(0))) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@call_convention(" + d.args.get(0) + ")' is not a calling convention declared in "
                                    + "compiler.config (known: " + config.callingConventions.keySet() + ")");
                }
                break;
            case "link_name":
                // "@link_name(realCName)": extern escape hatch for a C symbol that is not a valid Caspien identifier
                // (e.g. "sleep", now a keyword). A bare word, not a string. See docs/TypeChecker.md, "Decorators".
                if (d.args.size() != 1) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@link_name' takes exactly one argument, e.g. '@link_name(sleep)'");
                }
                if (d.argIsString.get(0)) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@link_name' takes a bare name, not a string -- '@link_name(sleep)', not "
                                    + "'@link_name(\"sleep\")'");
                }
                break;
            case "pure":
                // "@pure" (side-effect free) or "@pure(rt)" (referentially transparent: also no pointer reads, no statics, no allocation, no non-determinism)
                if (d.args.size() > 1 || (d.args.size() == 1 && (d.argIsString.get(0) || !d.args.get(0).equals("rt")))) {
                    throw new CompilerException("type", d.file, d.line, "'@pure' takes nothing or 'rt' ('@pure(rt)', referentially transparent)");
                }
                break;
            case "non":
                if (d.args.size() != 1 || d.argIsString.get(0) || !d.args.get(0).equals("deterministic")) {
                    throw new CompilerException("type", d.file, d.line, "'@non' takes exactly 'deterministic': '@non(deterministic)'");
                }
                break;
            case "fs_root":
                if (d.args.size() > 1 || (d.args.size() == 1 && !d.args.get(0).equals("rw"))) {
                    throw new CompilerException("type", d.file, d.line, "'@fs_root' takes nothing (read-only) or 'rw'");
                }
                break;
            case "unroll":
                // "@unroll" (unroll fully) or "@unroll(N)" (partial unroll by factor N, 2..64)
                if (d.args.size() > 1) {
                    throw new CompilerException("type", d.file, d.line, "'@unroll' takes nothing or one factor, e.g. '@unroll(4)'");
                }
                if (d.args.size() == 1) {
                    String a = d.args.get(0);
                    boolean digits = !d.argIsString.get(0) && !a.isEmpty() && a.length() <= 3 && a.chars().allMatch(Character::isDigit);
                    if (!digits || Integer.parseInt(a) < 2 || Integer.parseInt(a) > 64) {
                        throw new CompilerException("type", d.file, d.line,
                                "'@unroll(N)' needs a whole-number factor from 2 to 64, found '" + a + "'");
                    }
                }
                break;
            case "reads":
            case "writes":
                if (d.args.isEmpty()) {
                    throw new CompilerException("type", d.file, d.line,
                            "'@" + d.name + "' requires at least one argument (all/self/others/globals)");
                }
                for (String a : d.args) {
                    if (!READS_WRITES_ARGS.contains(a)) {
                        throw new CompilerException("type", d.file, d.line,
                                "'@" + d.name + "' argument must be one of all/self/others/globals, got '"
                                        + a + "'");
                    }
                }
                break;
            default:
                if (!d.args.isEmpty()) {
                    throw new CompilerException("type", d.file, d.line, "'@" + d.name + "' takes no arguments");
                }
        }
    }

    /**
     * Constructor bookkeeping shared by collectFunc (plain constructor) and collectImpl (monomorphized generic
     * constructor).
     */
    private void registerConstructorFuncInfo(Token t, FuncInfo info) {
        if (!structs.containsKey(t.constructorConcreteName)) {
            throw new CompilerException("type", t.file, t.line,
                    "'impl constructor for " + t.constructorConcreteName + "' -- '"
                            + t.constructorConcreteName + "' is not a declared struct");
        }
        if (!isStructByValueReturn(info.returnType) || !info.returnType.baseType.equals(t.constructorConcreteName)) {
            throw new CompilerException("type", t.file, t.line,
                    "internal error: constructor for '" + t.constructorConcreteName
                            + "' resolved an unexpected return type '" + info.returnType.canonical() + "'");
        }
        info.isConstructor = true;
        info.constructorConcreteName = t.constructorConcreteName;
        info.isPublic = true; // constructors are always callable wherever the struct itself is visible -- no separate '@pub' concept for v1
    }

    private void collectFunc(Token t, String safetyTag) {
        for (String contextOnly : new String[]{"default", "overrides", "realizes"}) {
            Token.Decorator bad = getDecorator(t.decorators, contextOnly);
            if (bad != null) {
                throw new CompilerException("type", bad.file, bad.line,
                        "'@" + contextOnly + "' is only valid on an interface method or an impl method, "
                                + "not a plain top-level function");
            }
        }
        FuncInfo info = new FuncInfo();
        info.funcToken = t;
        buildFuncInfo(t, info);
        if (t.isConstructorDecl) {
            // Constructor: registered as an ordinary overload (name and return type were fixed by
            // Parser.gatherConstructorImpl); shared with collectImpl via registerConstructorFuncInfo.
            registerConstructorFuncInfo(t, info);
        } else if (BUILTIN_NAMES.contains(info.name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + info.name + "' is a reserved built-in name (sizeof/len/range/deref/clone) "
                            + "and can't be used as a function name");
        }
        info.declOrder = pendingDeclOrder.getOrDefault(t, Integer.MAX_VALUE);
        info.safetyTag = safetyTag;

        if (externs.containsKey(info.name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + info.name + "' collides with an already-declared 'extern " + info.name
                            + "' -- an extern and an ordinary function can't share a name");
        }
        if (constExprs.containsKey(info.name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + info.name + "' collides with an already-declared 'const " + info.name
                            + "' -- a const and a function can't share a name");
        }
        List<FuncInfo> overloads = functions.computeIfAbsent(info.name, k -> new ArrayList<>());
        for (FuncInfo existing : overloads) {
            if (sameSignature(existing, info)) {
                throw new CompilerException("type", t.file, t.line,
                        "function '" + info.name + "' with this parameter signature is already declared");
            }
        }
        overloads.add(info);
    }

    /** Decorators an extern accepts (no other decorator has C-linkage meaning). */
    private static final Set<String> EXTERN_DECORATORS = new HashSet<>(Arrays.asList("call_convention", "link_name"));

    /**
     * "extern name(types) ret": signature-only declaration with its own namespace, cross-checked against
     * `functions` both ways (no overloading). See docs/TypeChecker.md, "Externs and exports".
     */
    private void collectExtern(Token t) {
        validateDecorators(t.decorators, EXTERN_DECORATORS, "an extern");
        Token nameTok = t.sub.get(0);
        Token paramsTok = t.sub.get(1);
        List<Token> returnTypeTokens = t.sub.subList(2, t.sub.size());
        String name = nameTok.text;

        if (BUILTIN_NAMES.contains(name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + name + "' is a reserved built-in name (sizeof/len/range/deref/clone) "
                            + "and can't be used as an extern name");
        }
        if (externs.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'extern " + name + "' is already declared");
        }
        if (functions.containsKey(name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'extern " + name + "' collides with an already-declared function '" + name
                            + "' -- an extern and an ordinary function can't share a name");
        }

        ExternInfo info = new ExternInfo();
        info.name = name;
        Token.Decorator linkNameDecorator = getDecorator(t.decorators, "link_name");
        info.linkName = linkNameDecorator != null ? linkNameDecorator.args.get(0) : name;
        info.declTok = t;
        info.callConvention = resolveCallConvention(t.decorators);
        if (FsPolicy.current.denies(name, info.linkName) && !isStdlibFile(t.file)) {
            throw new CompilerException("type", t.file, t.line,
                    "extern '" + name + "' (links to '" + info.linkName + "') is forbidden by fs-deny-externs in the compiler's fs.config -- "
                            + "use the stdlib's fs API (Dir/File) instead");
        }
        ExternParamParseResult params = parseExternParamList(paramsTok.childs, t);
        info.paramTypes.addAll(params.fixedTypes);
        info.hasVarargs = params.hasVarargs;
        info.returnType = returnTypeTokens.isEmpty()
                ? new TypeInfo(null, "imut", "void")
                : resolveTypeAnnotation(returnTypeTokens, t, true);

        // Same by-pointer-only return rule as ordinary functions.
        if ((structs.containsKey(info.returnType.baseType) || enums.containsKey(info.returnType.baseType)
                || isArrayType(info.returnType.baseType)) && info.returnType.storage == null) {
            throw new CompilerException("type", t.file, t.line,
                    "'extern " + name + "' returns '" + info.returnType.baseType
                            + "' without a storage modifier -- a struct/enum/array cannot be "
                            + "returned by value, only by pointer (owns/ref/raw/auto/static)");
        }
        externs.put(name, info);
    }

    private static final class ExternParamParseResult {
        final List<TypeInfo> fixedTypes;
        final boolean hasVarargs;
        ExternParamParseResult(List<TypeInfo> fixedTypes, boolean hasVarargs) {
            this.fixedTypes = fixedTypes;
            this.hasVarargs = hasVarargs;
        }
    }

    /**
     * Splits an extern's params into bare types (no "name :"); a final '...' marks varargs and is rejected
     * anywhere else.
     */
    private ExternParamParseResult parseExternParamList(List<Token> flat, Token at) {
        List<List<Token>> groups = splitIntoRawGroups(flat);
        List<TypeInfo> types = new ArrayList<>();
        boolean hasVarargs = false;
        for (int i = 0; i < groups.size(); i++) {
            List<Token> group = groups.get(i);
            boolean isVarargsMarker = group.size() == 1 && group.get(0).type == TokenType.OPERATOR
                    && group.get(0).text.equals("...");
            if (isVarargsMarker) {
                if (i != groups.size() - 1) {
                    throw new CompilerException("type", group.get(0).file, group.get(0).line,
                            "'...' must be the last parameter in an 'extern' declaration");
                }
                hasVarargs = true;
                continue;
            }
            types.add(resolveTypeAnnotation(group, at));
        }
        return new ExternParamParseResult(types, hasVarargs);
    }

    /** "export foo": foo must be a declared, overload-free, non-generic top-level func. */
    private void collectExport(Token t) {
        Token nameTok = t.sub.get(0);
        String name = nameTok.text;
        List<FuncInfo> overloads = functions.get(name);
        if (overloads == null || overloads.isEmpty()) {
            throw new CompilerException("type", t.file, t.line,
                    "'export " + name + "' does not name a declared function");
        }
        if (overloads.size() > 1) {
            throw new CompilerException("type", t.file, t.line,
                    "'export " + name + "' is ambiguous -- '" + name + "' has " + overloads.size()
                            + " overloads, and C has no overloading; only an overload-free function "
                            + "can be exported");
        }
        FuncInfo info = overloads.get(0);
        if (info.funcToken.typeParams != null && !info.funcToken.typeParams.isEmpty()) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + name + "' is generic and can't be exported -- C has no equivalent of a "
                            + "monomorphized function family");
        }
        if (exportedNames.contains(name)) {
            throw new CompilerException("type", t.file, t.line,
                    "'" + name + "' is already exported");
        }
        exportedNames.add(name);
        info.isExported = true;
    }

    private static final Set<String> FUNC_ALL_DECORATORS;
    static {
        FUNC_ALL_DECORATORS = new HashSet<>(FUNC_BASE_DECORATORS);
        FUNC_ALL_DECORATORS.addAll(Arrays.asList("default", "overrides", "realizes"));
    }

    private void buildFuncInfo(Token t, FuncInfo info) {
        validateDecorators(t.decorators, FUNC_ALL_DECORATORS, "a function");
        Token nameTok = t.sub.get(0);
        Token paramsTok = t.sub.get(1);
        List<Token> returnTypeTokens = t.sub.subList(2, t.sub.size());

        info.name = nameTok.text;
        info.mangledName = info.name; // overridden for impl methods, see collectImpl
        info.callConvention = resolveCallConvention(t.decorators);
        info.isAsync = getDecorator(t.decorators, "async") != null;
        info.isThrows = getDecorator(t.decorators, "throws") != null;
        info.isPublic = getDecorator(t.decorators, "pub") != null;
        for (NameType nt : parseParamList(paramsTok.childs)) {
            info.paramNames.add(nt.name);
            info.paramTypes.add(nt.type);
        }
        if (getDecorator(t.decorators, "drop") != null) {
            requireDropHookShape(t, info, returnTypeTokens);
        }
        if (info.isAsync) {
            requireAsyncParamShape(t, info);
        }
        info.returnType = returnTypeTokens.isEmpty()
                ? new TypeInfo(null, "imut", "void")
                : resolveTypeAnnotation(returnTypeTokens, t, true);

        // Enum/array returns need a storage modifier (struct returns are legal: RVO, see isStructByValueReturn).
        if ((enums.containsKey(info.returnType.baseType) || isArrayType(info.returnType.baseType))
                && info.returnType.storage == null) {
            throw new CompilerException("type", t.file, t.line,
                    "function '" + info.name + "' returns '" + info.returnType.baseType
                            + "' without a storage modifier -- an enum/array cannot be "
                            + "returned by value, only by pointer (owns/ref/raw/auto/static)");
        }

        // Array parameters also need a storage modifier.
        for (int i = 0; i < info.paramTypes.size(); i++) {
            TypeInfo paramType = info.paramTypes.get(i);
            if (isArrayType(paramType.baseType) && paramType.storage == null) {
                throw new CompilerException("type", t.file, t.line,
                        "parameter '" + info.paramNames.get(i) + "' of function '" + info.name
                                + "' is an array without a storage modifier -- arrays can only be "
                                + "passed as parameters by pointer (owns/ref/raw/auto/static)");
            }
            // A by-value struct parameter is illegal at any size. See docs/TypeChecker.md, "Function signature rules".
            StructInfo paramStructInfo = structs.get(paramType.baseType);
            if (paramStructInfo != null && paramType.storage == null) {
                if (t.fromGenericInstance) {
                    // Monomorphized generic: the method is only unavailable for this T, an error only if called.
                    throw new DeferredSignatureException(t.file, t.line,
                            "parameter '" + info.paramNames.get(i) + "' of '" + info.name
                                    + "' is a plain (by-value) '" + paramType.baseType + "' (the generic "
                                    + "type argument is a struct) -- passing a struct on the stack, not by "
                                    + "pointer, is illegal; use a pointer-taking variant of this method "
                                    + "(auto/ref/raw parameter) if the library offers one");
                }
                throw new CompilerException("type", t.file, t.line,
                        "parameter '" + info.paramNames.get(i) + "' of function '" + info.name
                                + "' is a plain (by-value) '" + paramType.baseType + "' -- passing a "
                                + "struct on the stack, not by pointer, is illegal; pass a pointer "
                                + "instead (owns/ref/raw/auto/static)");
            }
        }
        validateGhostTableDecoratorSignatures(t, info);
    }

    /** Checks the fixed signature of each ghost-table / async / sleep hook decorator and registers it (behavior is never validated). See docs/TypeChecker.md, "Decorator signature checks". */
    private void validateGhostTableDecoratorSignatures(Token t, FuncInfo info) {
        if (getDecorator(t.decorators, "gt_init") != null) {
            requireGhostTableSignature(t, info, "gt_init", 0, "void");
            registerGhostTableFunction(t, info, "gt_init");
        }
        if (getDecorator(t.decorators, "gt_alive_check") != null) {
            requireGhostTableSignature(t, info, "gt_alive_check", 1, "bool");
            registerGhostTableFunction(t, info, "gt_alive_check");
        }
        if (getDecorator(t.decorators, "gt_destruct") != null) {
            requireGhostTableSignature(t, info, "gt_destruct", 1, "void");
            registerGhostTableFunction(t, info, "gt_destruct");
        }
        if (getDecorator(t.decorators, "gt_register") != null) {
            requireGhostTableSignature(t, info, "gt_register", 1, "bool");
            registerGhostTableFunction(t, info, "gt_register");
        }
        if (getDecorator(t.decorators, "gt_moved") != null) {
            requireGhostTableSignature(t, info, "gt_moved", 1, "void");
            registerGhostTableFunction(t, info, "gt_moved");
        }
        // @gt_ref_id / @gt_ref_resolve: nullable-ref hooks. @par_call / @await_call: async glue; all share the one-function-per-decorator map.
        if (getDecorator(t.decorators, "gt_ref_id") != null) {
            requireGtRefSignature(t, info, "gt_ref_id", true);
            registerGhostTableFunction(t, info, "gt_ref_id");
        }
        if (getDecorator(t.decorators, "gt_ref_resolve") != null) {
            requireGtRefSignature(t, info, "gt_ref_resolve", false);
            registerGhostTableFunction(t, info, "gt_ref_resolve");
        }
        if (getDecorator(t.decorators, "par_call") != null) {
            requireAsyncGlueSignature(t, info, "par_call");
            registerGhostTableFunction(t, info, "par_call");
        }
        if (getDecorator(t.decorators, "await_call") != null) {
            requireAsyncGlueSignature(t, info, "await_call");
            registerGhostTableFunction(t, info, "await_call");
        }
        // @sleep: call sites resolve it by mangled name (checkSleepCall), so no name forcing is needed.
        if (getDecorator(t.decorators, "sleep") != null) {
            requireSleepSignature(t, info);
            registerGhostTableFunction(t, info, "sleep");
        }
    }

    /** @sleep takes one plain u64 and returns a plain u64, like C sleep(unsigned int). */
    private void requireSleepSignature(Token t, FuncInfo info) {
        if (info.paramTypes.size() != 1) {
            throw new CompilerException("type", t.file, t.line,
                    "'@sleep' requires exactly 1 parameter (matching the real C 'sleep(unsigned int)'), got "
                            + info.paramTypes.size());
        }
        TypeInfo paramType = info.paramTypes.get(0);
        if (paramType.storage != null || !paramType.baseType.equals("u64")) {
            throw new CompilerException("type", t.file, t.line,
                    "'@sleep's parameter must be a plain 'u64' (matching the real C 'sleep(unsigned int)'), "
                            + "got '" + paramType.canonical() + "'");
        }
        if (info.returnType.storage != null || !info.returnType.baseType.equals("u64")) {
            throw new CompilerException("type", t.file, t.line,
                    "'@sleep' requires a plain 'u64' return type (matching the real C 'sleep(unsigned int)'), "
                            + "got '" + info.returnType.canonical() + "'");
        }
    }

    /** Fixed shape of @par_call / @await_call. See docs/TypeChecker.md, "Decorator signature checks". */
    private void requireAsyncGlueSignature(Token t, FuncInfo info, String decoratorName) {
        boolean isPar = decoratorName.equals("par_call");
        if (info.paramTypes.size() != (isPar ? 3 : 2)) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "' requires exactly " + (isPar ? "3 parameters (a trampoline function pointer, "
                            + "a handle pointer and a 'raw mut u64' that receives the thread id, or null)"
                            : "2 parameters (a trampoline function pointer and a handle pointer)")
                            + ", got " + info.paramTypes.size());
        }
        if (isPar) {
            TypeInfo tidParam = info.paramTypes.get(2);
            if (!"raw".equals(tidParam.storage) || !tidParam.baseType.equals("u64")) {
                throw new CompilerException("type", t.file, t.line,
                        "'@par_call's third parameter (where the started thread's id is stored, or null) must be "
                                + "'raw mut u64', got '" + tidParam.canonical() + "'");
            }
        }
        TypeInfo fnParam = info.paramTypes.get(0);
        if (!"raw".equals(fnParam.storage) || !fnParam.baseType.equals("u8")) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "'s first parameter (the trampoline function pointer) must be "
                            + "'raw u8', got '" + fnParam.canonical() + "'");
        }
        TypeInfo handleParam = info.paramTypes.get(1);
        if (!"raw".equals(handleParam.storage) || !handleParam.baseType.equals("u8")) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "'s second parameter (the async handle pointer) must be "
                            + "'raw u8', got '" + handleParam.canonical() + "'");
        }
        if (!info.returnType.baseType.equals("bool")) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "' requires a return type of 'bool' (true = the thread was started, "
                            + "false = it could not be), got '" + info.returnType.canonical() + "'");
        }
    }

    /** @async functions take at most one parameter, and it must fit one machine register (no ranges, no structs). See docs/TypeChecker.md, "Decorator signature checks". */
    private static final Set<String> ASYNC_PARAM_ALLOWED_BASE_TYPES = new HashSet<>(Arrays.asList(
            "u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "bool", "char"));

    private void requireAsyncParamShape(Token t, FuncInfo info) {
        if (info.paramTypes.size() > 1) {
            throw new CompilerException("type", t.file, t.line,
                    "'@async' function '" + info.name + "' takes " + info.paramTypes.size()
                            + " parameters -- an '@async' function may take at most one");
        }
        if (info.paramTypes.isEmpty()) {
            return;
        }
        TypeInfo paramType = info.paramTypes.get(0);
        boolean fitsInOneRegister = paramType.storage != null
                || ASYNC_PARAM_ALLOWED_BASE_TYPES.contains(paramType.baseType);
        if (!fitsInOneRegister) {
            throw new CompilerException("type", t.file, t.line,
                    "'@async' function '" + info.name + "'s parameter must fit in a single register (<=8 "
                            + "bytes) -- 'u8/16/32/64', 's8/16/32/64', 'bool', 'char', or a pointer/'raw' "
                            + "type -- got '" + paramType.canonical() + "'");
        }
    }

    /**
     * Forces each gt_* decorated function's mangledName to the literal decorator name, because X86Backend calls those
     * symbols by literal name. Runs after all functions are collected so the collision check is complete.
     * See docs/TypeChecker.md, "Decorator signature checks".
     */
    private void forceGhostTableFunctionNames() {
        for (String decoratorName : Arrays.asList("gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve")) {
            FuncInfo decorated = ghostTableFunctions.get(decoratorName);
            if (decorated == null) {
                continue;
            }
            if (!decorated.name.equals(decoratorName)) {
                List<FuncInfo> collision = functions.get(decoratorName);
                if (collision != null && !collision.isEmpty()) {
                    throw new CompilerException("type", decorated.funcToken.file, decorated.funcToken.line,
                            "'" + decorated.name + "' is decorated '@" + decoratorName + "' but a separate "
                                    + "function literally named '" + decoratorName + "' also exists in this "
                                    + "compilation unit -- the decorated function must be emitted under the "
                                    + "literal name '" + decoratorName + "', so the two can't coexist; rename "
                                    + "one of them");
                }
            }
            decorated.mangledName = decoratorName;
        }
    }

    /** At most one function per ghost-table decorator in the whole compilation unit (imports included). */
    private void registerGhostTableFunction(Token t, FuncInfo info, String decoratorName) {
        FuncInfo existing = ghostTableFunctions.get(decoratorName);
        if (existing != null && existing != info) {
            throw new CompilerException("type", t.file, t.line,
                    "only one function may be decorated '@" + decoratorName + "' in the whole compilation "
                            + "unit -- '" + existing.name + "' already carries it");
        }
        ghostTableFunctions.put(decoratorName, info);
    }

    /** Parameter count is 0 (gt_init) or 1 (a `raw u8`, either mutability). */
    private void requireGhostTableSignature(Token t, FuncInfo info, String decoratorName, int expectedParamCount,
            String expectedReturnBaseType) {
        if (info.paramTypes.size() != expectedParamCount) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "' requires exactly " + expectedParamCount + " parameter(s), got "
                            + info.paramTypes.size());
        }
        if (expectedParamCount == 1) {
            TypeInfo paramType = info.paramTypes.get(0);
            if (!"raw".equals(paramType.storage) || !paramType.baseType.equals("u8")) {
                throw new CompilerException("type", t.file, t.line,
                        "'@" + decoratorName + "' requires its parameter to be 'raw u8', got '"
                                + paramType.canonical() + "'");
            }
        }
        if (!info.returnType.baseType.equals(expectedReturnBaseType)) {
            throw new CompilerException("type", t.file, t.line,
                    "'@" + decoratorName + "' requires a return type of '" + expectedReturnBaseType + "', got '"
                            + info.returnType.canonical() + "'");
        }
    }

    /** `@gt_ref_id`: `func(ptr: raw u8) u64`; `@gt_ref_resolve`: `func(id: u64) raw u8`. */
    private void requireGtRefSignature(Token t, FuncInfo info, String decoratorName, boolean idOfPointer) {
        boolean ok = info.paramTypes.size() == 1;
        if (ok) {
            TypeInfo p = info.paramTypes.get(0);
            TypeInfo r = info.returnType;
            if (idOfPointer) {
                ok = "raw".equals(p.storage) && p.baseType.equals("u8") && r.storage == null && r.baseType.equals("u64");
            } else {
                ok = p.storage == null && p.baseType.equals("u64") && "raw".equals(r.storage) && r.baseType.equals("u8");
            }
        }
        if (!ok) {
            throw new CompilerException("type", t.file, t.line, idOfPointer
                    ? "'@gt_ref_id' must have the shape 'func f(ptr: raw u8) u64'"
                    : "'@gt_ref_resolve' must have the shape 'func f(id: u64) raw u8'");
        }
    }

    private boolean sameSignature(FuncInfo a, FuncInfo b) {
        if (a.paramTypes.size() != b.paramTypes.size()) {
            return false;
        }
        for (int i = 0; i < a.paramTypes.size(); i++) {
            TypeInfo pa = a.paramTypes.get(i);
            TypeInfo pb = b.paramTypes.get(i);
            if (!pa.baseType.equals(pb.baseType)) {
                return false;
            }
            // Differently aliased params are nominally distinct overloads.
            if (!java.util.Objects.equals(pa.aliasName, pb.aliasName)) {
                return false;
            }
        }
        return true;
    }

    private FuncInfo functionInfoFor(Token funcTok) {
        String name = funcTok.sub.get(0).text;
        for (FuncInfo info : functions.get(name)) {
            if (info.funcToken == funcTok) {
                return info;
            }
        }
        throw new CompilerException("type", funcTok.file, funcTok.line,
                "internal error: no collected FuncInfo for '" + name + "'");
    }

    // ---- name:type parsing (shared by struct members and func params) ---

    private static class NameType {
        final String name;
        final TypeInfo type;
        /** True only for a struct member declared with `swap`. */
        final boolean isSwap;
        NameType(String name, TypeInfo type, boolean isSwap) { this.name = name; this.type = type; this.isSwap = isSwap; }
    }

    /** Splits a flat token list on ',' / stray TERMINATORs into "name : type" groups (param lists may span lines). */
    private List<NameType> parseParamList(List<Token> flat) {
        return parseNameTypeGroupsFrom(flat, false);
    }

    /** Struct members only: the one place `swap` is recognized. */
    private List<NameType> parseNameTypeGroup(List<Token> flat) {
        return parseNameTypeGroupsFrom(flat, true);
    }

    private List<NameType> parseNameTypeGroupsFrom(List<Token> flat, boolean allowSwap) {
        List<NameType> result = new ArrayList<>();
        for (List<Token> group : splitIntoRawGroups(flat)) {
            result.add(parseOneNameType(group, allowSwap));
        }
        return result;
    }

    /** Splits into raw "name : type..." groups without resolving types (interface signature checks need the raw tokens, since `self` is not a type yet). */
    private List<List<Token>> splitIntoRawGroups(List<Token> flat) {
        List<List<Token>> result = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        for (Token t : flat) {
            boolean isSeparator = (t.type == TokenType.OPERATOR && t.text.equals(","))
                    || t.type == TokenType.TERMINATOR;
            if (isSeparator) {
                if (!current.isEmpty()) {
                    result.add(current);
                    current = new ArrayList<>();
                }
                continue;
            }
            current.add(t);
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    private NameType parseOneNameType(List<Token> group, boolean allowSwap) {
        boolean isSwap = false;
        Token swapTok = null;
        if (allowSwap && !group.isEmpty() && group.get(0).type == TokenType.OPERATOR
                && group.get(0).text.equals("swap")) {
            // `swap` always lexes as an OPERATOR, so it cannot collide with a member name.
            isSwap = true;
            swapTok = group.get(0);
            group = group.subList(1, group.size());
        }
        if (group.size() < 3 || group.get(0).type != TokenType.VARREF
                || group.get(1).type != TokenType.OPERATOR || !group.get(1).text.equals(":")) {
            Token at = group.isEmpty() ? swapTok : group.get(0);
            throw new CompilerException("type", at.file, at.line,
                    "expected 'name : type', found '" + describeGroup(group) + "'");
        }
        String name = group.get(0).text;
        List<Token> typeTokens = group.subList(2, group.size());
        TypeInfo type = isSwap ? resolveSwapMemberType(typeTokens, group.get(0))
                : resolveTypeAnnotation(typeTokens, group.get(0));
        return new NameType(name, type, isSwap);
    }

    /** A `swap` member's type must start with `atomic` (accepted only here), be concretely mut and carry no storage. See docs/TypeChecker.md, "Name:type groups and swap members". */
    private TypeInfo resolveSwapMemberType(List<Token> typeTokens, Token at) {
        if (typeTokens.isEmpty() || typeTokens.get(0).type != TokenType.KEYWORD
                || !typeTokens.get(0).text.equals("atomic")) {
            throw new CompilerException("type", at.file, at.line,
                    "a 'swap'-tagged struct member's type must begin with 'atomic', e.g. "
                            + "'swap flag: atomic mut MutexState'");
        }
        List<Token> rest = typeTokens.subList(1, typeTokens.size());
        TypeInfo resolved = resolveTypeAnnotation(rest, at);
        if (!"mut".equals(resolved.mutability)) {
            throw new CompilerException("type", at.file, at.line,
                    "a 'swap'-tagged member's atomic type must be concretely 'mut' -- found '"
                            + resolved.mutability + "'");
        }
        if (resolved.storage != null) {
            throw new CompilerException("type", at.file, at.line,
                    "a 'swap'-tagged member's atomic type cannot carry a storage modifier");
        }
        return resolved.withAtomic(true);
    }

    private String describeGroup(List<Token> group) {
        StringBuilder sb = new StringBuilder();
        for (Token t : group) {
            sb.append(t.text).append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * Resolves a type annotation, splicing in `type` aliases first (textual substitution, cycle-checked); the outermost
     * alias name is kept as TypeInfo.aliasName (nominal tag only). See docs/TypeChecker.md, "Type annotation resolution".
     */
    private TypeInfo resolveTypeAnnotation(List<Token> typeTokens, Token at) {
        return resolveTypeAnnotation(typeTokens, at, false);
    }

    /**
     * `allowIndeterminateMutability` is true only when resolving a function's own return type (func/impl method, extern,
     * interface method signature); nested resolutions always pass false. See docs/TypeChecker.md, "Type annotation resolution".
     */
    private TypeInfo resolveTypeAnnotation(List<Token> typeTokens, Token at, boolean allowIndeterminateMutability) {
        String[] aliasNameHolder = new String[1];
        List<Token> expanded = expandAliases(typeTokens, new HashSet<>(), aliasNameHolder, at);
        TypeInfo resolved = resolveTypeAnnotationRaw(expanded, at, allowIndeterminateMutability);
        if (aliasNameHolder[0] == null) {
            return resolved;
        }
        return new TypeInfo(resolved.storage, resolved.mutability, resolved.baseType, aliasNameHolder[0]);
    }

    /** Splices alias base types in place; only the first VARREF counts as the base type, the rest (array suffixes etc.) passes through. */
    private List<Token> expandAliases(List<Token> tokens, Set<String> expanding, String[] aliasNameHolder, Token at) {
        List<Token> result = new ArrayList<>();
        boolean sawBaseType = false;
        for (Token t : tokens) {
            if (t.type == TokenType.VARREF && !sawBaseType) {
                sawBaseType = true;
                if (aliasRawTargets.containsKey(t.text)) {
                    if (expanding.contains(t.text)) {
                        throw new CompilerException("type", t.file, t.line,
                                "circular type alias involving '" + t.text + "'");
                    }
                    if (aliasNameHolder[0] == null) {
                        aliasNameHolder[0] = t.text;
                    }
                    expanding.add(t.text);
                    result.addAll(expandAliases(aliasRawTargets.get(t.text), expanding, aliasNameHolder, at));
                    expanding.remove(t.text);
                    continue;
                }
            }
            result.add(t);
        }
        return result;
    }

    /** A struct/interface named in a type annotation must be @pub or declared in the same file; other names are ignored. */
    private void checkTypeNameVisibility(String baseType, Token at) {
        StructInfo si = structs.get(baseType);
        if (si != null) {
            if (!si.isPublic && !si.declTok.file.equals(at.file)) {
                throw new CompilerException("type", at.file, at.line,
                        "'" + baseType + "' is private -- not accessible outside the file it's "
                                + "declared in");
            }
            return;
        }
        InterfaceInfo ii = interfaces.get(baseType);
        if (ii != null && !ii.isPublic && !ii.declTok.file.equals(at.file)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + baseType + "' is private -- not accessible outside the file it's declared "
                            + "in");
        }
    }

    /** Modifiers must come in the order `const`, storage, `some`, mutability, base type; compares ranks of those present. */
    private void requireModifierOrder(int seenRank, int thisRank, String thisKind, Token at) {
        if (thisRank < seenRank) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + at.text + "' (" + thisKind + ") is out of order -- this language requires "
                            + "'const owns some imut T' order (constancy, then storage, then presence, "
                            + "then mutability, then the base type)");
        }
    }

    private TypeInfo resolveTypeAnnotationRaw(List<Token> typeTokens, Token at) {
        return resolveTypeAnnotationRaw(typeTokens, at, false);
    }

    private TypeInfo resolveTypeAnnotationRaw(List<Token> typeTokens, Token at,
            boolean allowIndeterminateMutability) {
        // `const` must be leftmost; stripped before the modifier loop (a let's const is Token.isConst, set earlier).
        boolean isConst = false;
        if (!typeTokens.isEmpty() && typeTokens.get(0).type == TokenType.KEYWORD
                && typeTokens.get(0).text.equals("const")) {
            isConst = true;
            typeTokens = typeTokens.subList(1, typeTokens.size());
        }
        String mutability = null;
        String storage = null;
        boolean isSome = false;
        // modifierRank: storage 1, some 2, mutability 3 (const is handled above); only modifiers present are compared.
        int modifierRank = 0;
        java.util.TreeSet<String> floatStates = null;
        String baseType = null;
        // Token the base type came from: the visibility check anchors to its file, not `at` (matters for monomorphized generics).
        Token baseTypeTok = null;
        StringBuilder arraySuffix = new StringBuilder();
        boolean baseIsIncompleteRange = false;
        TypeInfo dynArrayElementType = null;
        TypeInfo unsafeDynArrayElementType = null;
        TypeInfo rawPointeeType = null;
        List<TypeInfo> funcPtrParams = null;
        TypeInfo funcPtrReturn = null;
        Set<String> funcPtrDecorators = null;
        String funcPtrCallConventionName = null;

        for (int i = 0; i < typeTokens.size(); i++) {
            Token t = typeTokens.get(i);
            if (t.type == TokenType.OPERATOR && t.text.equals("@")) {
                // Inline `@name` inside a function-pointer annotation (normal decorators attach to a statement line).
                if (i + 1 >= typeTokens.size() || typeTokens.get(i + 1).type != TokenType.VARREF) {
                    throw new CompilerException("type", t.file, t.line,
                            "'@' must be followed by a decorator name");
                }
                String decoratorName = typeTokens.get(i + 1).text;
                if (funcPtrDecorators == null) {
                    funcPtrDecorators = new HashSet<>();
                }
                funcPtrDecorators.add(decoratorName);
                i++; // consumed the decorator name token too
                // Optional "(args)": only @call_convention carries a value.
                if (i + 1 < typeTokens.size() && typeTokens.get(i + 1).type == TokenType.DELINEATOR
                        && typeTokens.get(i + 1).text.equals("(")) {
                    Token parenTok = typeTokens.get(i + 1);
                    i++;
                    if (decoratorName.equals("call_convention")) {
                        funcPtrCallConventionName = parseInlineCallConventionArg(parenTok);
                    }
                }
            } else if (t.type == TokenType.KEYWORD && t.text.equals("func") && baseType == null) {
                // "func(P1,P2,...) R": the return type is everything after the parameter list.
                if (i + 1 >= typeTokens.size() || typeTokens.get(i + 1).type != TokenType.DELINEATOR
                        || !typeTokens.get(i + 1).text.equals("(")) {
                    throw new CompilerException("type", t.file, t.line,
                            "'func' requires a parenthesized parameter list");
                }
                Token paramsParen = typeTokens.get(i + 1);
                funcPtrParams = new ArrayList<>();
                if (!paramsParen.childs.isEmpty()) {
                    for (List<Token> paramSpan : splitTopLevelArgGroups(paramsParen.childs)) {
                        funcPtrParams.add(resolveTypeAnnotationRaw(paramSpan, at));
                    }
                }
                List<Token> returnSpan = typeTokens.subList(i + 2, typeTokens.size());
                if (returnSpan.isEmpty()) {
                    throw new CompilerException("type", t.file, t.line, "'func(...)' requires a return type");
                }
                funcPtrReturn = resolveTypeAnnotationRaw(new ArrayList<>(returnSpan), at);
                baseType = "func"; // placeholder -- TypeInfo.funcPointer builds the real baseType below
                i = typeTokens.size(); // consumed everything remaining
            } else if (t.type == TokenType.MODIFIER) {
                if (t.text.equals("mut") || t.text.equals("imut")) {
                    if (mutability != null) {
                        throw new CompilerException("type", t.file, t.line,
                                "duplicate mutability modifier in type annotation");
                    }
                    requireModifierOrder(modifierRank, 3, "mutability", t);
                    modifierRank = 3;
                    mutability = t.text;
                } else if (t.text.equals("some")) {
                    // `some` is a presence flag, not a storage; rank 2.
                    if (isSome) {
                        throw new CompilerException("type", t.file, t.line,
                                "duplicate 'some' modifier in type annotation");
                    }
                    requireModifierOrder(modifierRank, 2, "presence", t);
                    modifierRank = 2;
                    isSome = true;
                } else {
                    if (storage != null) {
                        if (storage.equals("raw") && t.text.equals("raw")) {
                            // A second `raw` starts a nested annotation ("raw mut raw mut char"), resolved recursively.
                            List<Token> pointeeSpan = new ArrayList<>(typeTokens.subList(i, typeTokens.size()));
                            rawPointeeType = resolveTypeAnnotationRaw(pointeeSpan, at);
                            baseType = rawPointeeType.canonical();
                            i = typeTokens.size(); // consumed everything remaining
                            continue;
                        }
                        throw new CompilerException("type", t.file, t.line,
                                "duplicate storage modifier in type annotation");
                    }
                    requireModifierOrder(modifierRank, 1, "storage", t);
                    modifierRank = 1;
                    storage = t.text;
                    if (storage.equals("owns") || storage.equals("ref")) {
                        // Any annotation that writes owns/ref counts (syntactic presence).
                        usesOwnsRefDynNew = true;
                    }
                }
            } else if (t.type == TokenType.VARREF && baseType == null && i + 1 < typeTokens.size()
                    && isFloatStateName(t.text)) {
                // Float-state modifier (finite|infinite|nan, chainable with '|'): contextual identifiers, not keywords.
                // A VARREF that is the last token is the base type instead.
                if (floatStates != null) {
                    throw new CompilerException("type", t.file, t.line,
                            "duplicate float-state modifier in type annotation");
                }
                floatStates = new java.util.TreeSet<>();
                floatStates.add(t.text);
                while (i + 2 < typeTokens.size() && typeTokens.get(i + 1).type == TokenType.OPERATOR
                        && typeTokens.get(i + 1).text.equals("|") && isFloatStateName(typeTokens.get(i + 2).text)) {
                    String next = typeTokens.get(i + 2).text;
                    if (!floatStates.add(next)) {
                        throw new CompilerException("type", t.file, t.line,
                                "'" + next + "' repeated in a float-state modifier chain");
                    }
                    i += 2;
                }
                if (floatStates.size() == FLOAT_STATE_NAMES.size()) {
                    throw new CompilerException("type", t.file, t.line,
                            "'" + String.join("|", floatStates) + "' names every possible float state -- "
                                    + "write the bare type instead (with no state modifier at all), which "
                                    + "already means exactly that");
                }
            } else if (t.type == TokenType.KEYWORD && t.text.equals("unsafe") && baseType == null
                    && i + 1 < typeTokens.size() && typeTokens.get(i + 1).type == TokenType.VARREF
                    && typeTokens.get(i + 1).text.equals("dynarray")) {
                // "unsafe dynarray(T)": the element type must be spelled out.
                Token dynTok = typeTokens.get(i + 1);
                Token parenTok = (i + 2 < typeTokens.size()) ? typeTokens.get(i + 2) : null;
                if (parenTok == null || parenTok.type != TokenType.DELINEATOR || !parenTok.text.equals("(")) {
                    throw new CompilerException("type", dynTok.file, dynTok.line,
                            "'unsafe dynarray' requires its element type spelled out, e.g. "
                                    + "'unsafe dynarray(u64)' -- there's no bare/incomplete form of this type");
                }
                List<List<Token>> args = splitTopLevelArgGroups(parenTok.childs);
                if (args.size() != 1) {
                    throw new CompilerException("type", parenTok.file, parenTok.line,
                            "'unsafe dynarray(...)' requires exactly one argument: the element type");
                }
                unsafeDynArrayElementType = resolveTypeAnnotationRaw(args.get(0), at);
                checkNotRefSomeElement(unsafeDynArrayElementType, parenTok, "an 'unsafe dynarray' element type");
                baseType = "unsafe_dynarray(" + TypeInfo.dynArrayElementText(unsafeDynArrayElementType) + ")";
                i += 2; // consumed 'dynarray' and the '(' token's whole span as one unit
            } else if (t.type == TokenType.VARREF && t.text.equals("dynarray") && baseType == null) {
                // "dynarray(T)": always complete, no bare form.
                Token next = (i + 1 < typeTokens.size()) ? typeTokens.get(i + 1) : null;
                if (next == null || next.type != TokenType.DELINEATOR || !next.text.equals("(")) {
                    throw new CompilerException("type", t.file, t.line,
                            "'dynarray' requires its element type spelled out, e.g. 'dynarray(u64)' -- "
                                    + "there's no bare/incomplete form of this type");
                }
                List<List<Token>> args = splitTopLevelArgGroups(next.childs);
                if (args.size() != 1) {
                    throw new CompilerException("type", next.file, next.line,
                            "'dynarray(...)' requires exactly one argument: the element type");
                }
                dynArrayElementType = resolveTypeAnnotationRaw(args.get(0), at);
                checkNotRefSomeElement(dynArrayElementType, next, "a 'dynarray' element type");
                baseType = "dynarray(" + TypeInfo.dynArrayElementText(dynArrayElementType) + ")";
                i++; // consumed the '(' token's whole span as one unit
            } else if (t.type == TokenType.VARREF && t.text.equals("slice") && baseType == null) {
                // `slice` was removed from the language; rejected with a dedicated message.
                throw new CompilerException("type", t.file, t.line,
                        "'slice' is no longer a valid type -- Caspien has removed the slice type; use a "
                                + "scalar index or a range/pointer pair explicitly");
            } else if (t.type == TokenType.VARREF && t.text.equals("range") && baseType == null) {
                Token next = (i + 1 < typeTokens.size()) ? typeTokens.get(i + 1) : null;
                if (next != null && next.type == TokenType.DELINEATOR && next.text.equals("(")) {
                    // "range(a,b)": bounds as in rangeBoundDescriptor.
                    baseType = parseRangeAnnotationArgs(next, at);
                    i++; // consumed the '(' token's whole span as one unit
                } else {
                    // Bare "range": legal by value without storage (two u64 bounds, unknown at compile time).
                    baseType = t.text;
                    baseIsIncompleteRange = true;
                }
            } else if (t.type == TokenType.VARREF) {
                if (baseType != null) {
                    throw new CompilerException("type", t.file, t.line,
                            "unexpected extra token '" + t.text + "' in type annotation");
                }
                baseType = t.text;
                baseTypeTok = t;
            } else if (t.type == TokenType.DELINEATOR && t.text.equals("[")) {
                // "T[N]": N is one integer literal; repeatable for multi-dimensional arrays.
                if (baseType == null) {
                    throw new CompilerException("type", t.file, t.line,
                            "array length annotation '[...]' with no base type before it");
                }
                if (t.childs.size() != 1 || t.childs.get(0).type != TokenType.INTEGER) {
                    throw new CompilerException("type", t.file, t.line,
                            "array length must be a single integer literal");
                }
                arraySuffix.append('[').append(t.childs.get(0).text).append(']');
            } else {
                throw new CompilerException("type", t.file, t.line,
                        "unexpected token '" + t.text + "' in type annotation");
            }
        }
        if (baseType == null) {
            throw new CompilerException("type", at.file, at.line, "type annotation is missing a base type");
        }
        if (isSome) {
            // `some` needs owns/ref/raw/static storage ('auto' is already non-null).
            if (storage == null || storage.equals("auto")) {
                throw new CompilerException("type", at.file, at.line,
                        "'some' requires 'owns'/'ref'/'raw'/'static' storage, got '"
                                + (storage == null ? "none" : storage) + "'");
            }
            // No fixed-array-specific `ref some` restriction here. See docs/TypeChecker.md, "Type annotation resolution".
        }
        if (floatStates != null && !isFloatType(baseType)) {
            throw new CompilerException("type", at.file, at.line,
                    "a float-state modifier ('" + String.join("|", floatStates) + "') requires 'f32' or 'f64', got '"
                            + baseType + "'");
        }
        if (baseIsIncompleteRange) {
            // Bare `range` is a plain by-value type: nothing to check.
        } else if (funcPtrParams != null) {
            // Function-pointer types need 'static' storage.
            if (!"static".equals(storage)) {
                throw new CompilerException("type", at.file, at.line,
                        "a function-pointer type's storage must be 'static', got '"
                                + (storage == null ? "none" : storage) + "'");
            }
            if (mutability == null) {
                throw new CompilerException("type", at.file, at.line,
                        "type annotations require an explicit 'mut' or 'imut' -- mutability is never "
                                + "inferred or defaulted here");
            }
            return TypeInfo.funcPointer(mutability, funcPtrParams, funcPtrReturn,
                    funcPtrDecorators != null ? funcPtrDecorators : new HashSet<>(),
                    funcPtrCallConventionName != null ? funcPtrCallConventionName : config.defaultConvention)
                    .withSome(isSome).withFloatStates(floatStates).withConst(isConst);
        } else if (!baseType.startsWith("range(")
                && !baseType.startsWith("dynarray(") && !baseType.startsWith("unsafe_dynarray(")
                && rawPointeeType == null && !isKnownType(baseType)) {
            throw new CompilerException("type", at.file, at.line, "unknown type '" + baseType + "'");
        }
        if (mutability == null && !baseType.equals("void") && !allowIndeterminateMutability) {
            // Mutability is mandatory in annotations except on a function's own return type (allowIndeterminateMutability).
            throw new CompilerException("type", at.file, at.line,
                    "type annotations require an explicit 'mut' or 'imut' -- mutability is never "
                            + "inferred or defaulted here");
        }
        if (mutability == null && allowIndeterminateMutability && !baseType.equals("void")) {
            // Return types may omit mutability; recorded as "indeterminate".
            mutability = "indeterminate";
        }
        if (mutability == null) {
            // `void` is exempt; "imut" so it equals an omitted return type.
            mutability = "imut";
        }
        // string and interface types require storage; structs/enums may be inline values. Array rules are enforced by the caller.
        // The visibility check is anchored to baseTypeTok (see above).
        checkTypeNameVisibility(baseType, baseTypeTok != null ? baseTypeTok : at);
        if ((pointerLikeBaseTypes.contains(baseType) || interfaces.containsKey(baseType)) && storage == null) {
            throw new CompilerException("type", at.file, at.line,
                    "type '" + baseType + "' is a pointer type and requires a storage modifier "
                            + "(owns/ref/raw/auto/static)");
        }
        if (dynArrayElementType != null && arraySuffix.length() == 0) {
            // The generic constructor below drops dynArrayElementType, so build the TypeInfo here.
            // Only for a bare "dynarray(T)", not "dynarray(T)[3]"; storage is forced to owns.
            // See docs/TypeChecker.md, "Type annotation resolution".
            if (storage != null && !storage.equals("owns")) {
                throw new CompilerException("type", at.file, at.line,
                        "a dynarray is always 'owns' storage -- it's inherently a heap-allocated, "
                                + "owned value, with no other storage it could have -- '" + storage
                                + "' can't be written before it");
            }
            return new TypeInfo("owns", mutability, baseType + arraySuffix, null,
                    null, null, null, null, dynArrayElementType, null)
                            .withSome(isSome).withFloatStates(floatStates).withConst(isConst);
        }
        if (unsafeDynArrayElementType != null) {
            // Same for "unsafe dynarray(T)": keep the element type, force owns.
            if (storage != null && !storage.equals("owns")) {
                throw new CompilerException("type", at.file, at.line,
                        "an unsafe dynarray is always 'owns' storage -- it's inherently a "
                                + "heap-allocated, owned value, with no other storage it could have -- '"
                                + storage + "' can't be written before it");
            }
            return new TypeInfo("owns", mutability, baseType + arraySuffix, null,
                    null, null, null, null, null, unsafeDynArrayElementType, null, null,
                    isSome, floatStates, isConst, false);
        }
        if (rawPointeeType != null) {
            // Explicit nested raw: keep rawPointeeType.
            return new TypeInfo(storage, mutability, baseType + arraySuffix, null,
                    null, null, null, null, null, rawPointeeType)
                            .withSome(isSome).withFloatStates(floatStates).withConst(isConst);
        }
        return new TypeInfo(storage, mutability, baseType + arraySuffix, null)
                .withSome(isSome).withFloatStates(floatStates).withConst(isConst);
    }

    /** `ref some` is rejected as a dynarray / unsafe-dynarray element type (struct members: validateNoRefSomeStructMembers). */
    private void checkNotRefSomeElement(TypeInfo elementType, Token at, String contextDesc) {
        if ("ref".equals(elementType.storage) && elementType.isSome) {
            throw new CompilerException("type", at.file, at.line,
                    "'ref some' is not allowed as " + contextDesc);
        }
    }

    /** Parses `@call_convention(name)` in a function-pointer annotation: one bare name declared in compiler.config. */
    private String parseInlineCallConventionArg(Token parenTok) {
        List<String> args = new ArrayList<>();
        for (Token argTok : parenTok.childs) {
            if (argTok.type == TokenType.OPERATOR && argTok.text.equals(",")) {
                continue;
            }
            if (argTok.type != TokenType.VARREF) {
                throw new CompilerException("type", argTok.file, argTok.line,
                        "'@call_convention' takes a bare name, not '" + argTok.text + "'");
            }
            args.add(argTok.text);
        }
        if (args.size() != 1) {
            throw new CompilerException("type", parenTok.file, parenTok.line,
                    "'@call_convention' takes exactly one argument, e.g. '@call_convention(win64)'");
        }
        if (!config.callingConventions.containsKey(args.get(0))) {
            throw new CompilerException("type", parenTok.file, parenTok.line,
                    "'@call_convention(" + args.get(0) + ")' is not a calling convention declared in "
                            + "compiler.config (known: " + config.callingConventions.keySet() + ")");
        }
        return args.get(0);
    }

    /** "range(0,5)" or "range(0,x)" -- the composite range annotation body (inside the parens), reusing rangeBoundDescriptor's exact bare-literal-or-bare-identifier shape. */
    private String parseRangeAnnotationArgs(Token parenTok, Token at) {
        List<List<Token>> args = splitTopLevelArgGroups(parenTok.childs);
        if (args.size() != 2) {
            throw new CompilerException("type", parenTok.file, parenTok.line,
                    "'range(...)' requires exactly two bounds");
        }
        return "range(" + rangeAnnotationBoundDescriptor(args.get(0))
                + "," + rangeAnnotationBoundDescriptor(args.get(1)) + ")";
    }

    /** A range bound in a type annotation: an integer literal or a bare identifier (assumed imut). */
    private String rangeAnnotationBoundDescriptor(List<Token> boundTokens) {
        if (boundTokens.size() != 1) {
            throw new CompilerException("type", boundTokens.get(0).file, boundTokens.get(0).line,
                    "a range bound must be a single integer literal or a bare identifier");
        }
        Token t = boundTokens.get(0);
        if (t.type == TokenType.INTEGER) {
            return t.text;
        }
        if (t.type == TokenType.VARREF) {
            return "imut_" + t.text;
        }
        throw new CompilerException("type", t.file, t.line,
                "a range bound must be a single integer literal or a bare identifier, found '" + t.text + "'");
    }

    private List<List<Token>> splitTopLevelArgGroups(List<Token> tokens) {
        List<List<Token>> groups = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        for (Token t : tokens) {
            if (t.type == TokenType.WHITESPACE) {
                continue;
            }
            if (t.type == TokenType.OPERATOR && t.text.equals(",")) {
                groups.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(t);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private boolean isKnownType(String name) {
        if (NUMERIC_TYPES.contains(name)) {
            return true;
        }
        switch (name) {
            case "bool": case "char": case "string": case "void": case "null":
                return true;
            default:
                return structs.containsKey(name) || enums.containsKey(name) || interfaces.containsKey(name);
        }
    }

    // ---- pass 2: check every function body ---------------------------

    /** One recorded function call, tagged with the call *site's* effective safety context (from Scope.currentSafety at the moment it was checked). */
    private static class CallEdge {
        final FuncInfo caller;
        final FuncInfo callee;
        final String safety;
        final Token at;
        /** True only when this exact call site sits inside a "for i in dynarrayExpr{...}" loop's own body -- see Scope.insideDynArrayForLoopBody's own comment, and checkDynArrayForLoopResizeSafety below. */
        final boolean insideDynArrayForLoop;
        CallEdge(FuncInfo caller, FuncInfo callee, String safety, Token at, boolean insideDynArrayForLoop) {
            this.caller = caller;
            this.callee = callee;
            this.safety = safety;
            this.at = at;
            this.insideDynArrayForLoop = insideDynArrayForLoop;
        }
    }

    private final List<CallEdge> callEdges = new ArrayList<>();
    /** One validated `throw` statement; read by validateNoThrowReachableFromGhostTableFunctions. */
    private static class ThrowSite {
        final FuncInfo func;
        final Token at;
        ThrowSite(FuncInfo func, Token at) {
            this.func = func;
            this.at = at;
        }
    }
    private final List<ThrowSite> throwSites = new ArrayList<>();
    /** Functions calling `resize` directly: the seed of checkDynArrayForLoopResizeSafety's reachability walk. */
    private final Set<FuncInfo> funcsCallingResizeDirectly = new HashSet<>();

    /** A call made in a "safe" context may not reach "unsafe" code, transitively. Runs after all bodies are checked. See docs/TypeChecker.md, "Whole-program call-graph checks". */
    private void checkSafetyCallGraph() {
        Map<FuncInfo, List<FuncInfo>> callees = new HashMap<>();
        for (CallEdge e : callEdges) {
            callees.computeIfAbsent(e.caller, k -> new ArrayList<>()).add(e.callee);
        }
        Map<FuncInfo, Boolean> reachesUnsafeCache = new HashMap<>();
        for (CallEdge e : callEdges) {
            if (!e.safety.equals("safe")) {
                continue;
            }
            if (e.callee.safetyTag.equals("unsafe") || reachesUnsafe(e.callee, callees, reachesUnsafeCache)) {
                throw new CompilerException("type", e.at.file, e.at.line,
                        "'" + e.caller.name + "' calls '" + e.callee.name + "' from within a 'safe' "
                                + "context, but '" + e.callee.name + "' is (or transitively calls) "
                                + "'unsafe' code");
            }
        }
    }

    /** Direct recursion needs @recursive; any indirect cycle is an error. `call()` targets are not CallEdges, so they are never seen. See docs/TypeChecker.md, "Whole-program call-graph checks". */
    private void checkNoRecursion() {
        Map<FuncInfo, List<CallEdge>> outEdges = new HashMap<>();
        for (CallEdge e : callEdges) {
            outEdges.computeIfAbsent(e.caller, k -> new ArrayList<>()).add(e);
        }
        for (CallEdge e : callEdges) {
            if (e.caller == e.callee && getDecorator(e.caller.funcToken.decorators, "recursive") == null) {
                throw new CompilerException("type", e.at.file, e.at.line,
                        "'" + e.caller.name + "' calls itself -- recursion is not allowed unless the "
                                + "function is decorated '@recursive'");
            }
        }
        Set<FuncInfo> visited = new HashSet<>();
        Set<FuncInfo> onStack = new HashSet<>();
        for (FuncInfo start : outEdges.keySet()) {
            if (!visited.contains(start)) {
                detectIndirectCycle(start, outEdges, visited, onStack);
            }
        }
    }

    private void detectIndirectCycle(FuncInfo f, Map<FuncInfo, List<CallEdge>> outEdges, Set<FuncInfo> visited,
            Set<FuncInfo> onStack) {
        visited.add(f);
        onStack.add(f);
        for (CallEdge e : outEdges.getOrDefault(f, new ArrayList<>())) {
            if (e.caller == e.callee) {
                continue; // direct self-recursion already checked separately, with its own rule
            }
            if (onStack.contains(e.callee)) {
                throw new CompilerException("type", e.at.file, e.at.line,
                        "indirect recursion detected: '" + e.caller.name + "' calls '" + e.callee.name
                                + "', which (directly or transitively) calls back to '" + e.caller.name
                                + "' -- recursion is never allowed indirectly, even for a '@recursive' "
                                + "function");
            }
            if (!visited.contains(e.callee)) {
                detectIndirectCycle(e.callee, outEdges, visited, onStack);
            }
        }
        onStack.remove(f);
    }

    /** No `throw` may be reachable from a gt_* decorated function (a local try/catch is fine). See docs/TypeChecker.md, "Whole-program call-graph checks". */
    private void validateNoThrowReachableFromGhostTableFunctions() {
        List<FuncInfo> gtFuncs = new ArrayList<>();
        for (String decoratorName : Arrays.asList("gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve")) {
            FuncInfo f = ghostTableFunctions.get(decoratorName);
            if (f != null) {
                gtFuncs.add(f);
            }
        }
        if (gtFuncs.isEmpty() || throwSites.isEmpty()) {
            return;
        }
        Map<FuncInfo, List<FuncInfo>> callees = new HashMap<>();
        for (CallEdge e : callEdges) {
            callees.computeIfAbsent(e.caller, k -> new ArrayList<>()).add(e.callee);
        }
        Set<FuncInfo> gtFuncSet = new HashSet<>(gtFuncs);
        Set<FuncInfo> reachable = new HashSet<>();
        List<FuncInfo> stack = new ArrayList<>(gtFuncs);
        reachable.addAll(gtFuncs);
        while (!stack.isEmpty()) {
            FuncInfo current = stack.remove(stack.size() - 1);
            for (FuncInfo callee : callees.getOrDefault(current, Collections.emptyList())) {
                if (reachable.add(callee)) {
                    stack.add(callee);
                }
            }
        }
        for (ThrowSite site : throwSites) {
            if (reachable.contains(site.func) && !gtFuncSet.contains(site.func)) {
                throw new CompilerException("type", site.at.file, site.at.line,
                        "'throw' is not allowed here -- '" + site.func.name + "' is (transitively) "
                                + "reachable from a '@gt_init'/'@gt_alive_check'/'@gt_destruct'/"
                                + "'@gt_register' function, and no 'throw' may ever escape a ghost-table "
                                + "function's own call graph (calling an '@throws' function and catching "
                                + "it locally is still fine -- only an unhandled 'throw' reachable from "
                                + "here is banned)");
            }
        }
    }

    /** True when `t` is an `auto`, or an array / dynarray of them (a pointer to a stack slot that cannot outlive its frame). */
    private boolean typeHoldsAuto(TypeInfo t) {
        if (t == null) {
            return false;
        }
        if ("auto".equals(t.storage)) {
            return true;
        }
        if (t.arrayElementType != null && typeHoldsAuto(t.arrayElementType)) {
            return true;
        }
        if (t.dynArrayElementType != null && typeHoldsAuto(t.dynArrayElementType)) {
            return true;
        }
        String base = t.baseType;
        if (base == null) {
            return false;
        }
        base = stripArrayDims(base);
        if (base.startsWith("dynarray(") || base.startsWith("unsafe_dynarray(")) {
            int open = base.indexOf('(');
            int close = base.lastIndexOf(')');
            return close > open && typeHoldsAuto(parseDynArrayElementTextForCheck(base.substring(open + 1, close)));
        }
        return false;
    }

    private TypeInfo parseDynArrayElementTextForCheck(String text) {
        return text.startsWith("auto_") ? new TypeInfo("auto", "indeterminate", text.substring(5)) : new TypeInfo(null, "indeterminate", text);
    }

    /**
     * `auto` points into a stack frame, so it must not be stored anywhere that can outlive that frame: a struct member (the struct can be
     * returned, moved to the heap, or handed to another function that keeps it) is refused, and a function cannot return one
     * (see `requireNoAutoReturn`). An `auto` parameter or local is fine.
     */
    private void validateNoAutoStructMembers() {
        for (StructInfo si : structs.values()) {
            for (Map.Entry<String, TypeInfo> member : si.members.entrySet()) {
                if (typeHoldsAuto(member.getValue())) {
                    throw new CompilerException("type", si.declTok.file, si.declTok.line,
                            "'" + si.name + "'s member '" + member.getKey() + "' is an 'auto' pointer (" + member.getValue().canonical()
                                    + ") -- an 'auto' points into a stack frame and cannot be stored in a struct, which could outlive it. "
                                    + "Use 'ref' to an owned value instead");
                }
            }
        }
    }

    /** A container (array / dynarray) of `auto`: a callee could push its own local's address into a longer-lived container. */
    private void requireNoAutoContainer(TypeInfo t, Token where, String what) {
        if (t != null && !"auto".equals(t.storage) && typeHoldsAuto(t)) {
            throw new CompilerException("type", where.file, where.line,
                    what + " has the type '" + t.canonical() + "', a container of 'auto' pointers: an 'auto' points into a stack frame, "
                            + "and a callee could store its own local's address into a container that outlives it");
        }
    }

    private void requireNoAutoReturn(FuncInfo f) {
        for (int pi = 0; pi < f.paramTypes.size(); pi++) {
            requireNoAutoContainer(f.paramTypes.get(pi), f.funcToken, "parameter '" + f.paramNames.get(pi) + "' of '" + f.name + "'");
        }
        if (typeHoldsAuto(f.returnType)) {
            throw new CompilerException("type", f.funcToken.file, f.funcToken.line,
                    "'" + f.name + "' returns an 'auto' pointer (" + f.returnType.canonical() + ") -- it would point into the stack frame "
                            + "of the function that is returning. Return a 'ref' to an owned value, or the value itself");
        }
    }

    /** `ref some` is rejected as a struct member type; members bypass checkNotRefSomeElement, hence this post-collection pass. */
    private void validateNoRefSomeStructMembers() {
        for (StructInfo si : structs.values()) {
            for (Map.Entry<String, TypeInfo> member : si.members.entrySet()) {
                TypeInfo mt = member.getValue();
                if ("ref".equals(mt.storage) && mt.isSome) {
                    throw new CompilerException("type", si.declTok.file, si.declTok.line,
                            "'" + si.name + "'s member '" + member.getKey() + "' is 'ref some' -- "
                                    + "'ref some' is not allowed as a struct member type");
                }
            }
        }
    }

    private TypeInfo declaredTypeIncludingGlobals(Token node, Scope scope) {
        if (node.type == TokenType.VARREF && scope.lookup(node.text) == null) {
            return globals.get(node.text);
        }
        if (node.type == TokenType.OPERATOR && ".".equals(node.text) && node.right != null && node.right.type == TokenType.VARREF) {
            TypeInfo l = declaredTypeIncludingGlobals(node.left, scope);
            StructInfo si = l == null ? null : structs.get(l.baseType);
            return si == null ? null : si.members.get(node.right.text);
        }
        return lookupDeclaredTypeStructurally(node, scope);
    }

    /** Whether the assignment target `a.b.c` / `a.items[i]` lies inside a swap-lock struct. */
    private boolean assignsInsideSwapLockStruct(Token target, Scope scope) {
        Token cur = target;
        while (cur != null && cur.type == TokenType.OPERATOR && (".".equals(cur.text) || "LOOKUP".equals(cur.text)) && cur.left != null) {
            if (".".equals(cur.text)) {
                TypeInfo holder = declaredTypeIncludingGlobals(cur.left, scope);
                if (holder != null && holder.baseType != null) {
                    StructInfo si = structs.get(holder.baseType);
                    if (si != null && si.swapFieldName != null) {
                        return true;
                    }
                }
            }
            cur = cur.left;
        }
        return false;
    }

    /**
     * A value entering a swap-lock struct must be a fresh allocation (`null`, `new`, `dyn`, `clone`), never a moved variable: an
     * earlier `ref` would reach the data without the lock. See docs/TypeChecker.md, "Lock struct rules".
     */
    private void requireFreshIntoSwapLock(Token rhs, String verb, String targetText) {
        Token v = rhs;
        while (true) {
            if (v.type == TokenType.DELINEATOR && "(".equals(v.text) && !v.childs.isEmpty()) {
                v = v.childs.get(0);
            } else if (v.type == TokenType.OPERATOR && v.unary && ("mut".equals(v.text) || "imut".equals(v.text)) && v.left != null) {
                v = v.left;
            } else if (v.type == TokenType.KEYWORD && "try".equals(v.text) && !v.isTryBlock && v.sub != null && !v.sub.isEmpty()) {
                v = v.sub.get(0);
            } else {
                break;
            }
        }
        boolean fresh = v.type == TokenType.NULL
                || (v.type == TokenType.OPERATOR && v.unary && "new".equals(v.text))
                || (v.type == TokenType.OPERATOR && "CALL".equals(v.text) && v.left != null && v.left.type == TokenType.VARREF
                        && ("dyn".equals(v.left.text) || "clone".equals(v.left.text)));
        if (!fresh || containsMoveSource(v)) {
            throw new CompilerException("type", rhs.file, rhs.line,
                    "a value " + verb + " the lock struct ('" + targetText + "') must be a fresh allocation: 'null', 'new ...', 'dyn(...)' or "
                            + "'clone(...)', with no owned variable moved into it. A 'ref' taken before the move would still reach the data "
                            + "without the lock. Copy an existing value in with 'clone(x)'");
        }
    }

    private boolean containsMoveSource(Token t) {
        if (t == null) {
            return false;
        }
        if (t.isOwnershipMoveSource) {
            return true;
        }
        if (containsMoveSource(t.left) || containsMoveSource(t.right)) {
            return true;
        }
        if (t.childs != null) {
            for (Token c : t.childs) {
                if (containsMoveSource(c)) {
                    return true;
                }
            }
        }
        if (t.sub != null) {
            for (Token c : t.sub) {
                if (containsMoveSource(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** No `ref`/`auto` anywhere inside a `@lock` struct (nested structs, arrays, owns pointees, interface implementers). See docs/TypeChecker.md, "Lock struct rules". */
    private void validateNoPointersInLockStructs() {
        for (Map.Entry<String, StructInfo> entry : structs.entrySet()) {
            StructInfo info = entry.getValue();
            if (info.lockFieldName == null) {
                continue;
            }
            Set<String> visiting = new HashSet<>();
            visiting.add(entry.getKey());
            for (Map.Entry<String, TypeInfo> member : info.members.entrySet()) {
                String found = findLockForbiddenPointer(member.getValue(), entry.getKey() + "." + member.getKey(), visiting);
                if (found != null) {
                    throw new CompilerException("type", info.declTok.file, info.declTok.line,
                            "'" + entry.getKey() + "' is decorated '@lock(...)', so it cannot contain a 'ref' or 'auto' anywhere inside it, "
                                    + "but '" + found + "' is one: a reference taken out of the lock would reach data the lock does not protect. "
                                    + "Make the struct own its data ('owns') and refer to items by index inside the lock");
                }
            }
        }
    }

    /** The path of the first `ref`/`auto` reachable from `t` (through nested structs, fixed arrays, dynarrays and `owns` pointees), or null. */
    private String findLockForbiddenPointer(TypeInfo t, String path, Set<String> visiting) {
        if (t == null) {
            return null;
        }
        if ("ref".equals(t.storage) || "auto".equals(t.storage)) {
            return path + " (" + t.canonical() + ")";
        }
        String base = t.baseType;
        if (base == null) {
            return null;
        }
        base = stripArrayDims(base);
        if (base.startsWith("dynarray(") || base.startsWith("unsafe_dynarray(")) {
            int open = base.indexOf('(');
            int close = base.lastIndexOf(')');
            if (close > open) {
                return findLockForbiddenPointer(TypeInfo.parseDynArrayElementText(base.substring(open + 1, close)), path + "[]", visiting);
            }
            return null;
        }
        if (interfaces.containsKey(base)) {
            // an interface-typed value can hold any implementer, so every struct that implements it counts (whole program)
            for (List<ImplInfo> implList : implsByConcreteType.values()) {
                for (ImplInfo impl : implList) {
                    if (base.equals(impl.interfaceName)) {
                        String found = findLockForbiddenPointer(new TypeInfo(null, "imut", impl.concreteName),
                                path + "<" + impl.concreteName + ">", visiting);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
            return null;
        }
        StructInfo inner = structs.get(base);
        if (inner == null || !visiting.add(base)) {
            return null;
        }
        try {
            for (Map.Entry<String, TypeInfo> member : inner.members.entrySet()) {
                String found = findLockForbiddenPointer(member.getValue(), path + "." + member.getKey(), visiting);
                if (found != null) {
                    return found;
                }
            }
        } finally {
            visiting.remove(base);
        }
        return null;
    }

    /** Rejects recursive structs (cycle of any length, any member storage), after monomorphization. A plain nullable `ref` member is not an edge. See docs/TypeChecker.md, "Recursive structs". */
    private void validateNoRecursiveStructs() {
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        for (String structName : structs.keySet()) {
            if (!visited.contains(structName)) {
                detectStructCycle(structName, visited, onStack);
            }
        }
    }

    private void detectStructCycle(String structName, Set<String> visited, Set<String> onStack) {
        visited.add(structName);
        onStack.add(structName);
        StructInfo info = structs.get(structName);
        for (Map.Entry<String, TypeInfo> member : info.members.entrySet()) {
            TypeInfo memberType = member.getValue();
            if ("ref".equals(memberType.storage) && !memberType.isSome) {
                continue; // a plain (nullable) `ref` member may point back at its own struct: a borrow never owns, drops or clones what it points at
            }
            String memberBaseType = member.getValue().baseType;
            String referencedStruct = isArrayType(memberBaseType) ? arrayElementType(memberBaseType) : memberBaseType;
            if (!structs.containsKey(referencedStruct)) {
                continue;
            }
            if (onStack.contains(referencedStruct)) {
                throw new CompilerException("type", info.declTok.file, info.declTok.line,
                        "recursive struct detected: '" + structName + "'s member '" + member.getKey()
                                + "' (directly or transitively) refers back to '" + structName
                                + "' -- recursive structs are not allowed, including through pointers "
                                + "and indirectly through other structs");
            }
            if (!visited.contains(referencedStruct)) {
                detectStructCycle(referencedStruct, visited, onStack);
            }
        }
        onStack.remove(structName);
    }

    /** Runs `requireNoAutoReturn` over every function and impl method, once all declarations are collected. */
    private void validateNoAutoReturns() {
        for (List<FuncInfo> overloads : functions.values()) {
            for (FuncInfo f : overloads) {
                requireNoAutoReturn(f);
            }
        }
        for (List<ImplInfo> impls : implsByConcreteType.values()) {
            for (ImplInfo impl : impls) {
                for (FuncInfo f : impl.methods.values()) {
                    requireNoAutoReturn(f);
                }
                for (FuncInfo f : impl.staticMethods.values()) {
                    requireNoAutoReturn(f);
                }
            }
        }
    }

    private boolean reachesUnsafe(FuncInfo f, Map<FuncInfo, List<FuncInfo>> callees, Map<FuncInfo, Boolean> cache) {
        Boolean cached = cache.get(f);
        if (cached != null) {
            return cached;
        }
        cache.put(f, false); // break cycles conservatively -- a cycle with no unsafe function in it never reaches one
        boolean result = f.safetyTag.equals("unsafe");
        if (!result) {
            for (FuncInfo callee : callees.getOrDefault(f, Collections.emptyList())) {
                if (reachesUnsafe(callee, callees, cache)) {
                    result = true;
                    break;
                }
            }
        }
        cache.put(f, result);
        return result;
    }

    /** Calling, from a `for i in dynarrayExpr{}` body, any function that may `resize` is barred outright (conservative: no alias analysis). See docs/TypeChecker.md, "Whole-program call-graph checks". */
    private void checkDynArrayForLoopResizeSafety() {
        Map<FuncInfo, List<FuncInfo>> callees = new HashMap<>();
        for (CallEdge e : callEdges) {
            callees.computeIfAbsent(e.caller, k -> new ArrayList<>()).add(e.callee);
        }
        Map<FuncInfo, Boolean> reachesResizeCache = new HashMap<>();
        for (CallEdge e : callEdges) {
            if (!e.insideDynArrayForLoop) {
                continue;
            }
            if (funcsCallingResizeDirectly.contains(e.callee)
                    || reachesResize(e.callee, callees, reachesResizeCache)) {
                throw new CompilerException("type", e.at.file, e.at.line,
                        "'" + e.caller.name + "' calls '" + e.callee.name + "' from inside a "
                                + "\"for i in dynarrayExpr{...}\" loop's own body, but '" + e.callee.name
                                + "' calls (or transitively calls) 'resize' -- resizing a dynarray "
                                + "(even a different one, conservatively) while iterating one is not allowed");
            }
        }
    }

    private boolean reachesResize(FuncInfo f, Map<FuncInfo, List<FuncInfo>> callees, Map<FuncInfo, Boolean> cache) {
        Boolean cached = cache.get(f);
        if (cached != null) {
            return cached;
        }
        cache.put(f, false); // break cycles conservatively, same reasoning reachesUnsafe already uses
        boolean result = funcsCallingResizeDirectly.contains(f);
        if (!result) {
            for (FuncInfo callee : callees.getOrDefault(f, Collections.emptyList())) {
                if (reachesResize(callee, callees, cache)) {
                    result = true;
                    break;
                }
            }
        }
        cache.put(f, result);
        return result;
    }

    /** Per function being checked: `par` handle locals (name -> declaring token), names a `match @lock` was taken on, and owns slots ever moved (must-match rule for locked `par` handles). */
    private Map<String, Token> fnHandleDecls = new LinkedHashMap<>();
    private Set<String> fnLockMatched = new HashSet<>();
    private Set<String> fnEverMoved = new HashSet<>();

    private boolean isLockedAsyncHandle(TypeInfo t) {
        if (t == null || !"owns".equals(t.storage) || t.baseType == null || !t.baseType.startsWith("AsyncHandle")) {
            return false;
        }
        StructInfo si = structs.get(t.baseType);
        return si != null && "gate".equals(si.swapFieldName);
    }

    private void checkFunctionBody(FuncInfo func) {
        Map<String, Token> savedDecls = fnHandleDecls;
        Set<String> savedMatched = fnLockMatched;
        Set<String> savedMoved = fnEverMoved;
        fnHandleDecls = new LinkedHashMap<>();
        fnLockMatched = new HashSet<>();
        fnEverMoved = new HashSet<>();
        try {
            checkFunctionBodyWithHandles(func);
        } finally {
            fnHandleDecls = savedDecls;
            fnLockMatched = savedMatched;
            fnEverMoved = savedMoved;
        }
    }

    private void checkFunctionBodyWithHandles(FuncInfo func) {
        List<Integer> savedLoops = ProofKills.saveLoops();
        ProofKills.clearLoops();
        ProofKills.enterFunction(func);
        try {
            checkFunctionBodyInner(func);
        } finally {
            ProofKills.exitFunction();
            ProofKills.restoreLoops(savedLoops);
        }
        for (Map.Entry<String, Token> e : fnHandleDecls.entrySet()) {
            if (!fnLockMatched.contains(e.getKey()) && !fnEverMoved.contains(e.getKey())) {
                throw new CompilerException("type", e.getValue().file, e.getValue().line,
                        "the 'par' handle '" + e.getKey() + "' is never looked at: take its lock with 'match @lock " + e.getKey()
                                + "{ OPEN:{ ... } CLOSED:default(n) }' (read 'state' and ':resolve()' inside OPEN), or pass it on to something that does");
            }
        }
    }

    private void checkFunctionBodyInner(FuncInfo func) {
        if (isPureRtFunc(func)) {
            for (int i = 0; i < func.paramTypes.size(); i++) {
                requirePureRtNoPointer(func.paramTypes.get(i), func.funcToken, func, "as parameter '" + func.paramNames.get(i) + "'");
            }
            requirePureRtNoPointer(func.returnType, func.funcToken, func, "as its return type");
        }
        Scope scope = new Scope(null, func.safetyTag);
        for (int i = 0; i < func.paramNames.size(); i++) {
            TypeInfo paramType = func.paramTypes.get(i);
            if ("ref".equals(paramType.storage) && paramType.isSome) {
                ProofKills.implicit(func.paramNames.get(i), pointeeOf(paramType));   // the caller guarantees it is alive at entry
            }
            scope.vars.put(func.paramNames.get(i), func.paramTypes.get(i));
            // An owns parameter is owed a destruct unless moved; registered on the root scope (the obligation spans the whole function).
            if ("owns".equals(func.paramTypes.get(i).storage)) {
                scope.ownsDeclaredHere.add(func.paramNames.get(i));
                func.ownsLocalTypes.put(func.paramNames.get(i), func.paramTypes.get(i));
            }
        }
        validateStaticLetPlacement(func.funcToken.childs);
        validateRecursiveFuncRequirements(func);
        List<Token.MatchPattern> assumedLocks = assumedLockPatterns(func);
        Scope bodyCheckScope = assumedLocks.isEmpty() ? scope : new Scope(scope, assumedLocks, false);
        insideConstructorBodyForStruct = func.isConstructor ? func.constructorConcreteName : null;
        Scope bodyScope = checkLines(func.funcToken.childs, bodyCheckScope, func, false);
        insideConstructorBodyForStruct = null;
        // `@throws` is required exactly when the body has a validated `throw` (func.sawThrow).
        if (func.isThrows && !func.sawThrow) {
            throw new CompilerException("type", func.funcToken.file, func.funcToken.line,
                    "'" + func.name + "' is decorated '@throws' but its body contains no 'throw' -- "
                            + "remove '@throws' (or add a 'throw')");
        }
        // Every path of a non-void function must end in a return (conservative; see definitelyReturns and docs/TypeChecker.md, "Control-flow termination checks").
        if (!isVoidReturnType(func.returnType) && !definitelyReturns(func.funcToken.childs)) {
            throw new CompilerException("type", func.funcToken.file, func.funcToken.line,
                    "'" + func.name + "' is declared to return '" + func.returnType.canonical()
                            + "' but not every path through its body ends in a 'return'");
        }
        // Falling off the end destructs everything live up to and including the root scope (owns parameters).
        func.funcToken.destructOnExit = collectNaturalEndDestruct(func.funcToken.childs, bodyScope, scope);
        // Runs after every restriction above, on the original (un-lowered) shape; the lowering only changes bytecode shape.
        if (getDecorator(func.funcToken.decorators, "recursive") != null) {
            lowerRecursiveFunctionToLoop(func, scope);
        }
    }

    private boolean isVoidReturnType(TypeInfo t) {
        return t.baseType.equals("void");
    }

    /** Conservative: does some statement of `lines` definitely end in a return (or throw)? See docs/TypeChecker.md, "Control-flow termination checks". */
    private boolean definitelyReturns(List<Token> lines) {
        for (Token lineTok : lines) {
            // Skip blank lines (a lowered match case body can contain one).
            if (lineTok.childs.isEmpty()) {
                continue;
            }
            Token stmt = lineTok.childs.get(0);
            if (statementDefinitelyReturns(stmt)) {
                return true;
            }
        }
        return false;
    }

    private boolean statementDefinitelyReturns(Token stmt) {
        if (stmt.type != TokenType.KEYWORD) {
            return false;
        }
        switch (stmt.text) {
            case "return":
            case "throw":
                return true;
            case "break":
                // Counts only while checking a `match @lock` CLOSED case (closedCaseTerminates): break = give up without the lock.
                return breakCountsAsTerminator;
            case "continue":
                // Legal only in a catch body (checkStatement rejects other uses); jumps past the enclosing try-block, so it terminates like return/throw.
                return true;
            case "if":
            case "match":
                // if/match share the '.right'-linked chain: counts only if it ends in a plain else and every branch returns.
                boolean sawElse = false;
                for (Token branch = stmt; branch != null; branch = branch.right) {
                    if (!definitelyReturns(branch.childs)) {
                        return false;
                    }
                    if (branch.sub.isEmpty()) {
                        sawElse = true;
                    }
                }
                return sawElse;
            case "loop":
                // With no 'break' a loop can only end by returning (or never).
                return !containsBreak(stmt.childs);
            case "unsafe":
            case "safe":
                // Unconditional: propagates from the body.
                return definitelyReturns(stmt.childs);
            case "lock":
                // Same as unsafe/safe.
                return definitelyReturns(stmt.childs);
            case "try":
                // Only the plain "try { }" block is unconditional; a throwing "try EXPR catch" falls through to false.
                return stmt.isTryBlock && definitelyReturns(stmt.childs);
            default:
                // Anything else never proves a return.
                return false;
        }
    }

    /** True if a 'break' appears in `lines` at any depth, except inside a nested 'loop' (whose break is its own). */
    private boolean containsBreak(List<Token> lines) {
        for (Token lineTok : lines) {
            if (statementContainsBreak(lineTok.childs.get(0))) {
                return true;
            }
        }
        return false;
    }

    private boolean statementContainsBreak(Token stmt) {
        if (stmt.type != TokenType.KEYWORD) {
            return false;
        }
        switch (stmt.text) {
            case "break":
                return true;
            case "loop":
                return false; // a nested loop's own 'break' targets it, not the outer loop
            case "if":
                for (Token branch = stmt; branch != null; branch = branch.right) {
                    if (containsBreak(branch.childs)) {
                        return true;
                    }
                }
                return false;
            case "match":
                for (Token branch = stmt; branch != null; branch = branch.right) {
                    if (containsBreak(branch.childs)) {
                        return true;
                    }
                }
                return false;
            case "unsafe":
            case "safe":
                return containsBreak(stmt.childs);
            case "lock":
                // Not conditional: a break in its body belongs to the enclosing loop.
                return containsBreak(stmt.childs);
            case "try":
                // Only the plain try-block form is unconditional (as in statementDefinitelyReturns).
                return stmt.isTryBlock && containsBreak(stmt.childs);
            default:
                return false;
        }
    }

    /**
     * True if a real throwing "try EXPR catch(e){ }" appears anywhere in `lines` (any depth, also through for/assume and nested
     * try-blocks); used by checkTryBlock. Broader than definitelyReturns/containsBreak, which only walk the relevant constructs.
     */
    private boolean containsRealTryCatch(List<Token> lines) {
        for (Token lineTok : lines) {
            if (lineTok.childs.isEmpty()) {
                continue;
            }
            if (statementContainsRealTryCatch(lineTok.childs.get(0))) {
                return true;
            }
        }
        return false;
    }

    private boolean statementContainsRealTryCatch(Token stmt) {
        // A try used as a let/assignment RHS hangs off the "=" operator (through mut/imut), as in BytecodeEmitter.tryNodeOf.
        if (stmt.type == TokenType.OPERATOR && stmt.text.equals("=") && stmt.right != null) {
            Token rhs = stmt.right;
            while (rhs.type == TokenType.OPERATOR && (rhs.text.equals("mut") || rhs.text.equals("imut"))
                    && rhs.left != null) {
                rhs = rhs.left;
            }
            if (rhs.type == TokenType.KEYWORD && rhs.text.equals("try")) {
                return !rhs.isTryBlock || containsRealTryCatch(rhs.childs);
            }
            return false;
        }
        if (stmt.type != TokenType.KEYWORD) {
            return false;
        }
        switch (stmt.text) {
            case "try":
                // A real try/catch qualifies; a nested "try { }" block does not itself, but is searched.
                return !stmt.isTryBlock || containsRealTryCatch(stmt.childs);
            case "if":
            case "match":
                for (Token branch = stmt; branch != null; branch = branch.right) {
                    if (containsRealTryCatch(branch.childs)) {
                        return true;
                    }
                }
                return false;
            case "loop":
            case "for":
            case "unsafe":
            case "safe":
            case "lock":
                return containsRealTryCatch(stmt.childs);
            case "assume":
                return stmt.hasBlock && containsRealTryCatch(stmt.childs);
            case "return": {
                // "return ?f(x)" / "return try f(x) catch(e){...}": the returned expression is the try (through any mut/imut wrapper)
                if (stmt.sub == null || stmt.sub.size() != 1) {
                    return false;
                }
                Token rv = stmt.sub.get(0);
                while (rv.type == TokenType.OPERATOR && (rv.text.equals("mut") || rv.text.equals("imut")) && rv.left != null) {
                    rv = rv.left;
                }
                return rv.type == TokenType.KEYWORD && rv.text.equals("try") && !rv.isTryBlock;
            }
            default:
                return false;
        }
    }

    /**
     * Plain "try { }" lexical block (Token.isTryBlock): must contain at least one real try/catch (any depth); otherwise an ordinary
     * child Scope with the safety context inherited. See docs/TypeChecker.md, "Control-flow termination checks".
     */
    private void checkTryBlock(Token stmt, Scope scope, FuncInfo func, boolean insideLoop) {
        if (!containsRealTryCatch(stmt.childs)) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "'try { ... }' requires at least one real 'try ... catch(e) { ... }' statement "
                            + "somewhere inside it");
        }
        // TryBlockBoundaryMarker makes this scope the target a nested `continue` resolves via tryBlockBoundaryScope.
        Scope tryBlockScope = new Scope(scope, TryBlockBoundaryMarker.MARK);
        checkLinesInScope(stmt.childs, tryBlockScope, func, insideLoop);
        stmt.destructOnExit = collectNaturalEndDestruct(stmt.childs, tryBlockScope, tryBlockScope);
    }


    /** A `@recursive` function needs a final `imut range` parameter (any name) and a first statement (after `let static`s) of `match PARAM is base{...}`; structural checks only. See docs/TypeChecker.md, "@recursive functions". */
    private void validateRecursiveFuncRequirements(FuncInfo func) {
        if (getDecorator(func.funcToken.decorators, "recursive") == null) {
            return;
        }
        int n = func.paramNames.size();
        if (n == 0 || !func.paramTypes.get(n - 1).canonical().equals("imut_range")) {
            throw new CompilerException("type", func.funcToken.file, func.funcToken.line,
                    "a '@recursive' function must have an 'imut range' as its final parameter");
        }
        String rangeParamName = func.paramNames.get(n - 1);
        // Strict: any statement other than `let static` before the required match is rejected.
        Token firstNonStatic = null;
        for (Token lineTok : func.funcToken.childs) {
            Token stmt = lineTok.childs.get(0);
            if (staticLetTokenOf(stmt) != null) {
                continue;
            }
            firstNonStatic = stmt;
            break;
        }
        if (!isRequiredBaseCaseMatch(firstNonStatic, rangeParamName)) {
            throw new CompilerException("type", func.funcToken.file, func.funcToken.line,
                    "a '@recursive' function's first statement (after any leading 'let static' "
                            + "declarations) must be 'match " + rangeParamName + " is base{...}' (its own "
                            + "final range parameter)");
        }
    }

    /** Shape check only: "match RANGEPARAM is base{...}" (checkMatchCondition confirms the type later). */
    private boolean isRequiredBaseCaseMatch(Token stmt, String rangeParamName) {
        if (stmt == null || stmt.type != TokenType.KEYWORD || !stmt.text.equals("match") || stmt.sub.isEmpty()) {
            return false;
        }
        Token condition = stmt.sub.get(0);
        return condition.type == TokenType.OPERATOR && condition.text.equals("is")
                && condition.left.type == TokenType.VARREF && condition.left.text.equals(rangeParamName)
                && condition.right.type == TokenType.VARREF && condition.right.text.equals("base");
    }

    /** A self-call to a `@recursive` function may only be the sole expression of a `return`, checked by node identity with directReturnExprNode. See docs/TypeChecker.md, "@recursive functions". */
    private void requireRecursiveCallReturnSiteOnly(FuncInfo func, Token op) {
        if (op != directReturnExprNode) {
            throw new CompilerException("type", op.file, op.line,
                    "a recursive call to '" + func.name + "' may only appear as the sole expression of "
                            + "a 'return' statement -- e.g. 'return " + func.name + "(...)' -- never bound "
                            + "to a variable first, and never nested inside a larger expression");
        }
        op.isRecursiveTailCallSite = true;
    }

    /** Every self-call must sit inside `match x within RANGEPARAM{}` with x an imut range; absolute rule, no unsafe bypass. See docs/TypeChecker.md, "@recursive functions". */
    private void requireRecursiveCallWithinProof(FuncInfo func, Token op, List<Token> argNodes,
            List<TypeInfo> argTypes, Scope scope) {
        // The last parameter is an imut range (validateRecursiveFuncRequirements).
        String rangeParamName = func.paramNames.get(func.paramNames.size() - 1);
        Token finalArg = argNodes.get(argNodes.size() - 1);
        // Reuse the argTypes from overload matching; do not resolve the node again.
        TypeInfo finalArgType = argTypes.get(argTypes.size() - 1);
        if (!"imut".equals(finalArgType.mutability)) {
            throw new CompilerException("type", finalArg.file, finalArg.line,
                    "a recursive call's own range argument must be 'imut', got '"
                            + finalArgType.canonical() + "'");
        }
        String argSlotKey = slotKeyOf(finalArg);
        boolean proven = false;
        if (argSlotKey != null) {
            for (Token.MatchPattern p : scope.activeMatchPatterns) {
                if (!p.invalidated && p.kind.equals("in") && argSlotKey.equals(p.slotKey)
                        && rangeParamName.equals(p.rightSlotKey)) {
                    proven = true;
                    break;
                }
            }
        }
        if (!proven) {
            throw new CompilerException("type", op.file, op.line,
                    "a recursive call to '" + func.name + "' must be wrapped in 'match "
                            + (argSlotKey != null ? argSlotKey : "...") + " within " + rangeParamName
                            + "{...}' -- proving the recursive call's own range argument is a proper "
                            + "subrange of the function's own range parameter (no 'unsafe' bypass is "
                            + "available for this proof)");
        }
    }

    /**
     * Rewrites a validated `@recursive` function body into a bounded `for` loop over shadow locals; each tail-call return site becomes
     * a reassignment of the shadows. See docs/TypeChecker.md, "@recursive functions".
     */
    private void lowerRecursiveFunctionToLoop(FuncInfo func, Scope paramScope) {
        List<Token> original = func.funcToken.childs;
        int splitIdx = 0;
        while (splitIdx < original.size() && staticLetTokenOf(original.get(splitIdx).childs.get(0)) != null) {
            splitIdx++;
        }
        List<Token> leadingStatics = new ArrayList<>(original.subList(0, splitIdx));
        List<Token> restLines = new ArrayList<>(original.subList(splitIdx, original.size()));

        int line = func.funcToken.line;
        String file = func.funcToken.file;
        Scope wrapperScope = new Scope(paramScope);

        List<Token> newChilds = new ArrayList<>(leadingStatics);

        int n = func.paramNames.size();
        for (int i = 0; i < n; i++) {
            String paramName = func.paramNames.get(i);
            Token valueExpr = new Token(TokenType.VARREF, paramName, line, file);
            newChilds.add(wrapAsCheckedLine(buildCheckedLetAssign(paramName, valueExpr, wrapperScope, func)));
        }

        String rangeParamName = func.paramNames.get(n - 1);
        Token startDot = new Token(TokenType.OPERATOR, ".", line, file);
        startDot.left = new Token(TokenType.VARREF, rangeParamName, line, file);
        startDot.right = new Token(TokenType.VARREF, "start", line, file);
        Token mutStart = new Token(TokenType.OPERATOR, "mut", line, file);
        mutStart.left = startDot;
        newChilds.add(wrapAsCheckedLine(buildCheckedLetAssign("__recur_range_start", mutStart, wrapperScope, func)));

        Token endDot = new Token(TokenType.OPERATOR, ".", line, file);
        endDot.left = new Token(TokenType.VARREF, rangeParamName, line, file);
        endDot.right = new Token(TokenType.VARREF, "end", line, file);
        Token mutEnd = new Token(TokenType.OPERATOR, "mut", line, file);
        mutEnd.left = endDot;
        newChilds.add(wrapAsCheckedLine(buildCheckedLetAssign("__recur_range_end", mutEnd, wrapperScope, func)));

        Token rangeDotDot = new Token(TokenType.OPERATOR, "..", line, file);
        rangeDotDot.left = new Token(TokenType.VARREF, "__recur_range_start", line, file);
        rangeDotDot.right = new Token(TokenType.VARREF, "__recur_range_end", line, file);
        newChilds.add(wrapAsCheckedLine(buildCheckedLetAssign("__recur_range", rangeDotDot, wrapperScope, func)));

        // The loop runs (initial length) times, but a recursion shrinking by one per step needs one more call to reach its base case.
        // The range is then empty, so a copy of the validated base-case block after the loop does what that call would (cloned before the return sites are rewritten).
        Token baseCaseAfterLoop = restLines.get(0).deepCloneChecked();
        replaceRecursiveReturnsInLines(restLines, func);

        Token inTok = new Token(TokenType.OPERATOR, "in", line, file);
        inTok.left = new Token(TokenType.VARREF, "__recur_i", line, file);
        inTok.right = new Token(TokenType.VARREF, "__recur_range", line, file);
        Token forTok = new Token(TokenType.KEYWORD, "for", line, file);
        forTok.sub = new ArrayList<>();
        forTok.sub.add(inTok);
        forTok.childs = new ArrayList<>();
        // Validate only the loop header against an empty body (checkForLoop reused unchanged), then swap in the already-validated body:
        // re-checking it would reject the synthetic reassignment of imut parameters.
        checkForLoop(forTok, wrapperScope, func);
        forTok.childs = restLines;
        forTok.destructOnExit = new ArrayList<>();
        newChilds.add(wrapAsCheckedLine(forTok));
        newChilds.add(baseCaseAfterLoop);

        func.funcToken.childs = newChilds;
    }

    /** Builds and fully, really type-checks "let NAME = valueExpr" against `scope`, returning the checked "=" node (not yet line-wrapped). */
    private Token buildCheckedLetAssign(String name, Token valueExpr, Scope scope, FuncInfo func) {
        Token nameTok = new Token(TokenType.VARREF, name, valueExpr.line, valueExpr.file);
        Token letTok = new Token(TokenType.KEYWORD, "let", valueExpr.line, valueExpr.file);
        letTok.childs.add(nameTok);
        Token assign = new Token(TokenType.OPERATOR, "=", valueExpr.line, valueExpr.file);
        assign.left = letTok;
        assign.right = valueExpr;
        TypeInfo t = checkAssign(assign, scope, func);
        assign.resolvedType = t.canonical();
        return assign;
    }

    /** The "LINE" wrapper Parser.wrapAsLine builds; statement walkers rely on lineTok.childs.get(0). */
    private Token wrapAsCheckedLine(Token stmt) {
        Token lineTok = new Token(TokenType.LINE, "line", stmt.line, stmt.file);
        lineTok.childs.add(stmt);
        return lineTok;
    }

    /**
     * Replaces every "return SELFCALL(..)" marked isRecursiveTailCallSite, wherever it nests, with the reassignment
     * lines; any number of call sites. See docs/TypeChecker.md, "Recursive tail-call lowering".
     */
    private void replaceRecursiveReturnsInLines(List<Token> lines, FuncInfo func) {
        for (int i = 0; i < lines.size(); i++) {
            Token lineTok = lines.get(i);
            if (lineTok.childs.isEmpty()) {
                continue;
            }
            Token stmt = lineTok.childs.get(0);
            if (stmt.type == TokenType.KEYWORD && stmt.text.equals("return") && !stmt.sub.isEmpty()
                    && stmt.sub.get(0).isRecursiveTailCallSite) {
                List<Token> replacement = buildTailCallReplacementLines(stmt, func);
                lines.remove(i);
                lines.addAll(i, replacement);
                i += replacement.size() - 1;
                continue;
            }
            if (stmt.type != TokenType.KEYWORD) {
                continue;
            }
            if (stmt.text.equals("match") && stmt.isCaseMatch) {
                for (Token caseTok : stmt.childs) {
                    replaceRecursiveReturnsInLines(caseTok.childs, func);
                }
            } else if (stmt.text.equals("if") || stmt.text.equals("match")) {
                for (Token branch = stmt; branch != null; branch = branch.right) {
                    replaceRecursiveReturnsInLines(branch.childs, func);
                }
            } else if (stmt.text.equals("for") || stmt.text.equals("loop") || stmt.text.equals("unsafe")
                    || stmt.text.equals("safe")) {
                replaceRecursiveReturnsInLines(stmt.childs, func);
            } else if (stmt.text.equals("assume") && stmt.hasBlock) {
                replaceRecursiveReturnsInLines(stmt.childs, func);
            }
        }
    }

    /**
     * Replacement for "return SELFCALL(a0, a1, ..)": one checked "let __recur_tmpN = argN" per argument (all evaluated
     * before any parameter changes), then an unchecked "paramN = __recur_tmpN" per parameter.
     * See docs/TypeChecker.md, "Recursive tail-call lowering".
     */
    private List<Token> buildTailCallReplacementLines(Token returnTok, FuncInfo func) {
        Token callOp = returnTok.sub.get(0);
        List<Token> argNodes = new ArrayList<>();
        if (!callOp.right.childs.isEmpty()) {
            collectCommaArgs(callOp.right.childs.get(0), argNodes);
        }
        int line = returnTok.line;
        String file = returnTok.file;
        List<Token> out = new ArrayList<>();
        List<String> tempNames = new ArrayList<>();
        for (int i = 0; i < argNodes.size(); i++) {
            Token argNode = argNodes.get(i);
            String tempName = "__recur_tmp" + i;
            tempNames.add(tempName);
            Token nameTok = new Token(TokenType.VARREF, tempName, line, file);
            Token letTok = new Token(TokenType.KEYWORD, "let", line, file);
            letTok.childs.add(nameTok);
            letTok.resolvedType = argNode.resolvedType;
            Token assign = new Token(TokenType.OPERATOR, "=", line, file);
            assign.left = letTok;
            assign.right = argNode;
            assign.resolvedType = argNode.resolvedType;
            out.add(wrapAsCheckedLine(assign));
        }
        int n = func.paramNames.size();
        for (int i = 0; i < n && i < tempNames.size(); i++) {
            String paramName = func.paramNames.get(i);
            String paramType = func.paramTypes.get(i).canonical();
            Token targetRef = new Token(TokenType.VARREF, paramName, line, file);
            targetRef.resolvedType = paramType;
            Token sourceRef = new Token(TokenType.VARREF, tempNames.get(i) != null ? tempNames.get(i) : argNodes.get(i).text, line, file);
            sourceRef.resolvedType = paramType;
            Token assign = new Token(TokenType.OPERATOR, "=", line, file);
            assign.left = targetRef;
            assign.right = sourceRef;
            assign.resolvedType = paramType;
            out.add(wrapAsCheckedLine(assign));
        }
        return out;
    }

    /**
     * "let static" is only allowed as a leading run at the top of the function body (never in a nested block, which this
     * scan cannot see; checkAssign re-checks staticApproved for that reason). See docs/TypeChecker.md, "Static let placement".
     */
    private void validateStaticLetPlacement(List<Token> lines) {
        boolean stillLeading = true;
        for (Token lineTok : lines) {
            Token stmt = lineTok.childs.get(0);
            Token letTok = staticLetTokenOf(stmt);
            if (letTok != null) {
                if (!stillLeading) {
                    throw new CompilerException("type", stmt.file, stmt.line,
                            "'let static' is only allowed at the top of a function body, before any "
                                    + "other statement");
                }
                letTok.staticApproved = true;
            } else {
                stillLeading = false;
            }
        }
    }

    /** Returns the 'let' token if `stmt` is a "let static ... = ..." declaration, else null. */
    private Token staticLetTokenOf(Token stmt) {
        if (stmt.type == TokenType.OPERATOR && stmt.text.equals("=")
                && stmt.left.type == TokenType.KEYWORD && stmt.left.text.equals("let") && stmt.left.isStatic) {
            return stmt.left;
        }
        return null;
    }

    private Scope checkLines(List<Token> lines, Scope parentScope, FuncInfo func, boolean insideLoop) {
        Scope scope = new Scope(parentScope);
        checkLinesInScope(lines, scope, func, insideLoop);
        return scope;
    }

    /** Used only for a loop's own body ("loop"/desugared "for") -- this scope becomes the new innermost loop boundary 'break' destructs up to. */
    private Scope checkLinesAsLoopBody(List<Token> lines, Scope parentScope, FuncInfo func) {
        return checkLinesAsLoopBody(lines, parentScope, func, false);
    }

    /** `isDynArrayForLoopBody` is true only for a "for i in dynarrayExpr{...}" loop's own body specifically -- see Scope.insideDynArrayForLoopBody's own comment. */
    private Scope checkLinesAsLoopBody(List<Token> lines, Scope parentScope, FuncInfo func, boolean isDynArrayForLoopBody) {
        Scope scope = new Scope(parentScope, true, isDynArrayForLoopBody);
        userLoopScopes.add(scope);
        ProofKills.enterLoop();
        try {
            checkLinesInScope(lines, scope, func, true);
        } finally {
            ProofKills.exitLoop();
        }
        return scope;
    }

    /** Lock-release calls owed from `from` up to (not past) `boundary`, in LIFO order (activeLockReleases is extended by copy, so the span is a suffix). */
    private List<Token> collectLockReleasesToBoundary(Scope from, Scope boundary) {
        List<Token> slice = new ArrayList<>(from.activeLockReleases.subList(
                boundary.activeLockReleases.size(), from.activeLockReleases.size()));
        Collections.reverse(slice);
        return slice;
    }


    /** Slots moved on only SOME paths into a merge point: still "moved" for use-after-move, but their scope-exit destruct is kept (null-safe). See docs/TypeChecker.md, "Branch merge and partly-moved slots". */
    private final Set<String> partlyMovedSlots = new HashSet<>();

    private boolean chainHasElse(Token first) {
        Token last = first;
        while (last.right != null) {
            last = last.right;
        }
        return last.sub.isEmpty();
    }

    /** Replaces the pre-branch moved set with the union of the branches and records the partly-moved slots; `survivors` are the moved sets of branches that fall through, `implicitPath` adds the no-branch-taken path of a chain without else. */
    private void finishBranchMerge(Set<String> preState, Set<String> union, List<Set<String>> survivors,
            boolean implicitPath) {
        List<Set<String>> paths = new ArrayList<>(survivors);
        if (implicitPath) {
            paths.add(new HashSet<>(preState));
        }
        preState.clear();
        preState.addAll(union);
        if (paths.isEmpty()) {
            return;
        }
        Set<String> all = new HashSet<>(paths.get(0));
        for (Set<String> p : paths) {
            all.retainAll(p);
        }
        for (String m : union) {
            if (!all.contains(m)) {
                partlyMovedSlots.add(m);
            }
        }
    }

    /**
     * Owns slots to GT_DESTRUCT, innermost scope and most recent declaration first, up to `boundary`; skips moved slots and
     * `preservedSlotKey` (a return statement's returned slot). One flat entry per slot. See docs/TypeChecker.md, "Scope exit: destruct and unlock lists".
     */
    private List<String> collectOwnsToDestruct(Scope from, Scope boundary, String preservedSlotKey) {
        List<String> result = new ArrayList<>();
        for (Scope s = from; s != null; s = s.parent) {
            for (int i = s.ownsDeclaredHere.size() - 1; i >= 0; i--) {
                String name = s.ownsDeclaredHere.get(i);
                if ((s.movedSlots.contains(name) && !partlyMovedSlots.contains(name)) || name.equals(preservedSlotKey)) {
                    continue;
                }
                result.add(name);
            }
            if (s == boundary) {
                break;
            }
        }
        return result;
    }

    /** Natural-end destruct list; empty when the last statement is a literal return/break (the list would be dead code). Deliberately no full reachability analysis. */
    private List<String> collectNaturalEndDestruct(List<Token> lines, Scope from, Scope boundary) {
        if (!lines.isEmpty()) {
            Token last = lines.get(lines.size() - 1).childs.get(0);
            if (last.type == TokenType.KEYWORD && (last.text.equals("return") || last.text.equals("break"))) {
                return new ArrayList<>();
            }
        }
        return collectOwnsToDestruct(from, boundary, null);
    }

    private void checkLinesInScope(List<Token> lines, Scope scope, FuncInfo func, boolean insideLoop) {
        checkNoCodeAfterTerminalStatement(lines);
        UnsafeBlockRecord blockRec = scope.unsafeBlock;      // set only for the scope of a written `unsafe` block: its direct children are attributed one by one
        int childIdx = 0;
        for (Token lineTok : lines) {
            Token stmt = lineTok.childs.get(0);
            if (blockRec != null) {
                blockRec.noteChild(childIdx++, stmt);
            }
            if (stmt.type == TokenType.KEYWORD && stmt.text.equals("assume") && !stmt.hasBlock) {
                // "assume match expr;" without a block holds for the rest of the enclosing block, so it swaps `scope` for a nested
                // Scope with the extra proofs here instead of going through checkStatement.
                List<Token.MatchPattern> assumePatterns = checkAssumeMatchCondition(stmt, scope, func);
                List<Token.MatchPattern> assumeExtended = new ArrayList<>(scope.activeMatchPatterns);
                assumeExtended.addAll(assumePatterns);
                scope = new Scope(scope, assumeExtended, false);
                continue;
            }
            checkStatement(stmt, scope, func, insideLoop);
        }
        if (blockRec != null) {
            blockRec.cur = -1;
        }
    }

    /**
     * Rejects any statement following one that always ends the block (statementDefinitelyReturns: return/throw/continue,
     * exhaustive if chains, break-free loops). A partly-returning "if" does not count. See docs/TypeChecker.md, "Unreachable code".
     */
    private void checkNoCodeAfterTerminalStatement(List<Token> lines) {
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).childs.isEmpty()) {
                continue; // a genuinely blank line -- see definitelyReturns' own identical guard
            }
            Token stmt = lines.get(i).childs.get(0);
            if (statementDefinitelyReturns(stmt)) {
                // The *next* real (non-blank) line is the actual
                // unreachable statement to point the error at.
                Token next = null;
                for (int j = i + 1; j < lines.size(); j++) {
                    if (!lines.get(j).childs.isEmpty()) {
                        next = lines.get(j).childs.get(0);
                        break;
                    }
                }
                if (next == null) {
                    continue; // everything remaining is blank lines -- nothing to actually flag
                }
                throw new CompilerException("type", next.file, next.line,
                        "unreachable code: nothing may follow a statement that always ends this "
                                + "block's own execution ('return'/'throw'/'continue', or an exhaustive "
                                + "if/else chain or break-free loop that always does)");
            }
        }
    }

    /**
     * Cheap structural pre-scan of a loop body: seeds the moved set with every in-scope owns slot the body moves anywhere, so
     * one real pass sees cross-iteration use-after-move. Call arguments only count for a name with exactly one overload.
     * See docs/TypeChecker.md, "Loop move pre-scan".
     */
    private void collectLoopMoves(List<Token> lines, Scope scope, Set<String> out) {
        for (Token lineTok : lines) {
            collectMovesInNode(lineTok.childs.get(0), scope, out);
        }
    }

    private void collectMovesInNode(Token node, Scope scope, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("=")) {
            Token destTok = node.left;
            if (destTok.type == TokenType.KEYWORD && destTok.text.equals("let")) {
                TypeInfo rhsDeclared = lookupDeclaredTypeStructurally(node.right, scope);
                if (rhsDeclared != null && "owns".equals(rhsDeclared.storage)) {
                    String key = slotKeyOf(node.right);
                    if (key != null) {
                        out.add(key);
                    }
                }
            } else {
                TypeInfo declared = lookupDeclaredTypeStructurally(destTok, scope);
                if (declared != null && "owns".equals(declared.storage)) {
                    String key = slotKeyOf(node.right);
                    if (key != null) {
                        out.add(key);
                    }
                }
            }
        } else if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left.type == TokenType.VARREF) {
            List<FuncInfo> overloads = functions.get(node.left.text);
            if (overloads != null && overloads.size() == 1 && !node.right.childs.isEmpty()) {
                List<Token> argNodes = new ArrayList<>();
                collectCommaArgs(node.right.childs.get(0), argNodes);
                FuncInfo only = overloads.get(0);
                for (int i = 0; i < argNodes.size() && i < only.paramTypes.size(); i++) {
                    if ("owns".equals(only.paramTypes.get(i).storage)) {
                        String key = slotKeyOf(argNodes.get(i));
                        if (key != null) {
                            out.add(key);
                        }
                    }
                }
            }
        }
        collectMovesInNode(node.left, scope, out);
        collectMovesInNode(node.right, scope, out);
        if (node.childs != null) {
            for (Token c : node.childs) {
                collectMovesInNode(c, scope, out);
            }
        }
        if (node.sub != null) {
            for (Token s : node.sub) {
                collectMovesInNode(s, scope, out);
            }
        }
    }

    /** Structural-only type lookup (Scope/StructInfo, no expression resolution) -- used solely by collectLoopMoves's cheap pre-scan and checkAddressOf's 'ref' validation. */
    private TypeInfo lookupDeclaredTypeStructurally(Token node, Scope scope) {
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
            return lookupDeclaredTypeStructurally(node.childs.get(0), scope);
        }
        if (node.type == TokenType.VARREF) {
            return scope.lookup(node.text);
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && node.right.type == TokenType.VARREF) {
            TypeInfo leftType = lookupDeclaredTypeStructurally(node.left, scope);
            if (leftType == null) {
                return null;
            }
            StructInfo si = structs.get(leftType.baseType);
            return si == null ? null : si.members.get(node.right.text);
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("LOOKUP") && node.left != null) {
            // "x[i]": the element type of a safe dynarray or fixed array, so `ref x[i].member` can see an `owns` member of an element
            TypeInfo arrType = lookupDeclaredTypeStructurally(node.left, scope);
            if (arrType == null) {
                return null;
            }
            if (arrType.dynArrayElementType != null) {
                return arrType.dynArrayElementType;
            }
            return arrType.arrayElementType;
        }
        return null;
    }

    private void checkStatement(Token stmt, Scope scope, FuncInfo func, boolean insideLoop) {
        if (stmt.type == TokenType.KEYWORD) {
            switch (stmt.text) {
                case "if":
                    checkIfChain(stmt, scope, func, insideLoop);
                    return;
                case "loop":
                    // A bare loop{} needs unsafe, except once in an @event_loop function. See docs/TypeChecker.md, "Statement dispatch".
                    boolean alreadyUnsafe = unsafeBypass(scope, "loop");
                    boolean eventLoopExempt = !alreadyUnsafe && func != null
                            && func.eventLoopSafeLoopCount == 0
                            && hasDecorator(func.funcToken.decorators, "event_loop");
                    if (!alreadyUnsafe && !eventLoopExempt) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'loop' can only be used from within 'unsafe' code");
                    }
                    if (eventLoopExempt) {
                        // Spent only when the exemption was the reason the check passed.
                        func.eventLoopSafeLoopCount++;
                    }
                    validateDecorators(stmt.decorators, LOOP_DECORATORS, "a 'loop' statement");
                    collectLoopMoves(stmt.childs, scope, scope.movedSlots);
                    Scope loopBodyScope = checkLinesAsLoopBody(stmt.childs, scope, func);
                    // Every iteration is its own scope exit: owns locals are destructed at the natural bottom of the body.
                    stmt.destructOnExit = collectNaturalEndDestruct(stmt.childs, loopBodyScope, loopBodyScope);
                    return;
                case "for":
                    validateDecorators(stmt.decorators, LOOP_DECORATORS, "a 'for' statement");
                    if (getDecorator(stmt.decorators, "par") != null) {
                        requireNotPure(func, stmt, "a '@par' loop", "it runs iterations on other threads");
                    }
                    checkForLoop(stmt, scope, func);
                    return;
                case "match":
                    if (stmt.isCaseMatch) {
                        checkCaseMatch(stmt, scope, func, insideLoop);
                    } else {
                        checkMatchChain(stmt, scope, func, insideLoop);
                    }
                    return;
                case "lock":
                    if (stmt.isCaseMatch) {
                        // "match @lock EXPR{ OPEN:.. CLOSED:.. }": validated, then lowered in place into a loop (checkLockMatchStatement).
                        checkLockMatchStatement(stmt, scope, func, insideLoop);
                        return;
                    }
                    throw new CompilerException("type", stmt.file, stmt.line, "internal error: a 'lock' statement that is not 'match @lock'");
                case "assume":
                    // Block form only; the no-block form is handled in checkLinesInScope.
                    if (!stmt.hasBlock) {
                        throw new IllegalStateException(
                                "no-block 'assume match' must be handled by checkLinesInScope, never reach here");
                    }
                    List<Token.MatchPattern> assumePatterns = checkAssumeMatchCondition(stmt, scope, func);
                    List<Token.MatchPattern> assumeExtended = new ArrayList<>(scope.activeMatchPatterns);
                    assumeExtended.addAll(assumePatterns);
                    Scope assumeBlockScope = new Scope(scope, assumeExtended, false);
                    checkLinesInScope(stmt.childs, assumeBlockScope, func, insideLoop);
                    stmt.destructOnExit = collectNaturalEndDestruct(stmt.childs, assumeBlockScope, assumeBlockScope);
                    return;
                case "unsafe":
                case "safe":
                    // unsafe inside safe is an error, safe inside unsafe is fine (root level: flattenRootLines).
                    if (stmt.text.equals("unsafe") && scope.currentSafety.equals("safe")) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'unsafe' cannot be nested inside 'safe'");
                    }
                    Scope safetyBlockScope = new Scope(scope, stmt.text);
                    safetyBlockScope.transparent = true;
                    if (stmt.text.equals("unsafe")) {
                        if (!stmt.synthesizedUnsafe) {
                            requireNotPure(func, stmt, "an 'unsafe' block", "unsafe code can do anything the compiler cannot check, so purity could not be guaranteed");
                        }
                        safetyBlockScope.unsafeBlock = recordUnsafeBlock(stmt);
                    }
                    checkLinesInScope(stmt.childs, safetyBlockScope, func, insideLoop);
                    if (stmt.text.equals("unsafe")) {
                        // Ends the proofs active before the block unless all its tags are in NON_FREEING_UNSAFE_TAGS (they cannot free by themselves).
                        // Uses inside the block are the programmer's responsibility.
                        boolean assumeOnly = stmt.unsafeTags != null && !stmt.unsafeTags.isEmpty()
                                && NON_FREEING_UNSAFE_TAGS.containsAll(stmt.unsafeTags);
                        if (!stmt.synthesizedUnsafe && !assumeOnly) {
                            ProofKills.kill(scope.activeMatchPatterns, stmt, "an 'unsafe' block");
                        }
                    }
                    // Natural end of a block is not a function/loop boundary.
                    stmt.destructOnExit = collectNaturalEndDestruct(stmt.childs, safetyBlockScope, safetyBlockScope);
                    return;
                case "return":
                    checkReturn(stmt, scope, func);
                    return;
                case "throw":
                    checkThrow(stmt, scope, func);
                    return;
                case "try":
                    // Two shapes share "try" (Token.isTryBlock): the "try { .. }" block and the bare "try foo() catch(e){..}" statement.
                    // The expression form goes through resolveExprTypeInner instead.
                    if (stmt.isTryBlock) {
                        checkTryBlock(stmt, scope, func, insideLoop);
                        return;
                    }
                    checkTry(stmt, scope, func, insideLoop);
                    return;
                case "break":
                    if (!insideLoop) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'break' used outside of a loop");
                    }
                    stmt.destructOnExit = collectOwnsToDestruct(scope, scope.loopBoundaryScope, null);
                    stmt.unlockOnExit = collectLockReleasesToBoundary(scope, scope.loopBoundaryScope);
                    return;
                case "continue":
                    // Same as "break", targeting an enclosing try block. Order: a user for/loop (next iteration), the CLOSED case of a
                    // match @lock (retry the acquire; no lock is held), then a catch body (needs an enclosing try block).
                    // See docs/TypeChecker.md, "Statement dispatch".
                    stmt.isLoopContinue = false;
                    stmt.isLockRetryContinue = false;
                    boolean continueBelongsToCatch = insideCatchBody
                            && scope.loopBoundaryScope == catchEnclosingLoopBoundary;
                    if (!continueBelongsToCatch && scope.loopBoundaryScope != null
                            && userLoopScopes.contains(scope.loopBoundaryScope)) {
                        stmt.isLoopContinue = true;
                        stmt.destructOnExit = collectOwnsToDestruct(scope, scope.loopBoundaryScope, null);
                        stmt.unlockOnExit = collectLockReleasesToBoundary(scope, scope.loopBoundaryScope);
                        return;
                    }
                    if (!insideCatchBody && lockClosedLoopScope != null
                            && scope.loopBoundaryScope == lockClosedLoopScope) {
                        stmt.isLockRetryContinue = true;
                        lockRetryContinuesSeen++;
                        stmt.destructOnExit = collectOwnsToDestruct(scope, lockClosedLoopScope, null);
                        stmt.unlockOnExit = collectLockReleasesToBoundary(scope, lockClosedLoopScope);
                        return;
                    }
                    if (!insideCatchBody) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'continue' can only be used inside a 'for' or 'loop' (next iteration), inside a "
                                        + "'catch(e) { ... }' block, or directly in the 'CLOSED' case of a "
                                        + "'match @lock' (where it retries the acquire)");
                    }
                    if (scope.tryBlockBoundaryScope == null) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'continue' requires this 'catch' to be inside an enclosing 'try { ... }' "
                                        + "block -- there is nowhere well-defined for it to skip to "
                                        + "otherwise (the fallthrough it would produce is not allowed)");
                    }
                    stmt.destructOnExit = collectOwnsToDestruct(scope, scope.tryBlockBoundaryScope, null);
                    stmt.unlockOnExit = collectLockReleasesToBoundary(scope, scope.tryBlockBoundaryScope);
                    return;
                case "yield":
                    // Bare keyword, no restrictions (not even @async); emits YIELD.
                    return;
                case "ASM":
                    checkAsmDeclaration(stmt, scope);
                    return;
                default:
                    throw new CompilerException("type", stmt.file, stmt.line,
                            "'" + stmt.text + "' is not valid inside a function body");
            }
        }
        // A bare identifier statement naming a known ASM invokes it (takes priority over variable resolution).
        if (stmt.type == TokenType.VARREF) {
            AsmInfo info = lookupAsmName(stmt.text, scope);
            if (info != null) {
                if (!unsafeBypass(scope, "asm")) {
                    throw new CompilerException("type", stmt.file, stmt.line,
                            "invoking '" + stmt.text + "' requires 'unsafe' code");
                }
                stmt.resolvedAsmContent = info.rawText;
                return;
            }
        }
        // a plain expression statement, or a 'let' declaration surfaced as its '=' root
        resolveExprType(stmt, scope, func);
    }

    /**
     * "ASM name {..}" / "ASM name \"path\"" as a statement. Unnamed: content is stashed on the token and emitted in place.
     * Named: registered in the current scope only. See docs/TypeChecker.md, "ASM declarations".
     */
    private void checkAsmDeclaration(Token stmt, Scope scope) {
        if (!unsafeBypass(scope, "asm")) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "declaring 'ASM' requires 'unsafe' code");
        }
        String rawText = resolveAsmRawText(stmt);
        if (stmt.sub.isEmpty()) {
            stmt.resolvedAsmContent = rawText;
            return;
        }
        String name = stmt.sub.get(0).text;
        // Redeclaring in the same scope is an error; shadowing an outer scope is allowed (as for let).
        if (scope.localAsmNames.containsKey(name)) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "'ASM " + name + "' is already declared in this scope");
        }
        AsmInfo info = new AsmInfo();
        info.name = name;
        info.rawText = rawText;
        info.declTok = stmt;
        scope.declScope().localAsmNames.put(name, info);
    }

    /**
     * Block form text is already validated by Lexer.scanAsmBlock; the file form is read relative to the declaring file and
     * scanned for a stray ASM_END.
     */
    private String resolveAsmRawText(Token t) {
        if (t.asmBlockText != null) {
            return t.asmBlockText;
        }
        String rawPath = t.asmFilePath;
        Path dir = Paths.get(t.file).toAbsolutePath().getParent();
        Path resolvedPath = (dir != null ? dir : Paths.get(".")).resolve(rawPath).normalize();
        String content;
        DepsLog.record(resolvedPath);
        try {
            content = new String(Files.readAllBytes(resolvedPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CompilerException("type", t.file, t.line,
                    "could not read 'ASM' file '" + rawPath + "' (resolved to " + resolvedPath + "): "
                            + e.getMessage());
        }
        validateNoAsmEndMarker(content, resolvedPath.toString());
        return content;
    }

    /** Simplified copy of Lexer.scanAsmBlock's string/comment-aware ASM_END scan, without brace balancing (a whole file has no braces to balance). */
    private void validateNoAsmEndMarker(String content, String path) {
        int i = 0;
        int lineNo = 1;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '\n') {
                lineNo++;
                i++;
                continue;
            }
            if (c == '"' || c == '`' || c == '\'') {
                char quote = c;
                i++;
                while (i < content.length() && content.charAt(i) != quote) {
                    if (content.charAt(i) == '\\' && i + 1 < content.length()) {
                        i += 2;
                        continue;
                    }
                    if (content.charAt(i) == '\n') {
                        lineNo++;
                    }
                    i++;
                }
                i++; // consume the closing quote (or harmlessly run past EOF -- not this pass's concern)
                continue;
            }
            if (c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '/') {
                while (i < content.length() && content.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < content.length() && !(content.charAt(i) == '*' && content.charAt(i + 1) == '/')) {
                    if (content.charAt(i) == '\n') {
                        lineNo++;
                    }
                    i++;
                }
                i += 2;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < content.length() && (Character.isLetterOrDigit(content.charAt(i)) || content.charAt(i) == '_')) {
                    i++;
                }
                if (content.substring(start, i).equals("ASM_END")) {
                    throw new CompilerException("type", path, lineNo,
                            "illegal use of the reserved identifier 'ASM_END' inside an 'ASM' file");
                }
                continue;
            }
            i++;
        }
    }

    /** Registers (or finds) the record of a statement-level `unsafe` block and validates its tag words. */
    private UnsafeBlockRecord recordUnsafeBlock(Token stmt) {
        if (stmt.synthesizedUnsafe) {
            return null;
        }
        String key = stmt.file + ":" + stmt.line + ":" + (stmt.unsafeTags == null ? "" : String.join(",", stmt.unsafeTags));
        UnsafeBlockRecord rec = unsafeBlockRecords.get(key);
        if (rec == null) {
            rec = new UnsafeBlockRecord(stmt);
            if (stmt.unsafeTags != null) {
                if (stmt.unsafeTags.contains("unaudited")) {
                    if (stmt.unsafeTags.size() != 1) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'unsafe unaudited' stands for every other tag; write it alone (or name the real reasons instead)");
                    }
                    if (isStdlibFile(stmt.file)) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'unsafe unaudited' is not allowed in the standard library: every unsafe block there must name its reasons");
                    }
                }
                for (String tag : stmt.unsafeTags) {
                    if (!UNSAFE_TAGS.contains(tag) && System.getenv("CASPIEN_UNSAFE_REPORT") == null) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'" + tag + "' is not an unsafe tag -- the tags are " + String.join(" ", UNSAFE_TAGS));
                    }
                    if (!rec.declared.add(tag)) {
                        throw new CompilerException("type", stmt.file, stmt.line, "unsafe tag '" + tag + "' is written twice");
                    }
                }
            }
            unsafeBlockRecords.put(key, rec);
        }
        return rec;
    }

    /**
     * After the program is checked: each statement-level unsafe block must name exactly the reasons it needs. With env
     * CASPIEN_UNSAFE_REPORT set, the needed tags are appended to that file (file TAB line TAB tags) for tests/unsafe_tags_migrate.py
     * and nothing is reported. See docs/TypeChecker.md, "Unsafe block tags".
     */
    private void validateUnsafeBlockTags() {
        String reportPath = System.getenv("CASPIEN_UNSAFE_REPORT");
        if (reportPath != null && !reportPath.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (UnsafeBlockRecord rec : unsafeBlockRecords.values()) {
                sb.append(rec.tok.file).append('\t').append(rec.tok.line).append('\t').append(String.join(" ", rec.used)).append('\n');
            }
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get(reportPath), sb.toString().getBytes(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot write " + reportPath, e);
            }
            return;
        }
        for (UnsafeBlockRecord rec : unsafeBlockRecords.values()) {
            if (rec.declared.contains("unaudited")) {
                continue;   // vouches for everything, needed or not
            }
            Set<String> missing = new java.util.TreeSet<>(rec.used);
            missing.removeAll(rec.declared);
            Set<String> unused = new java.util.TreeSet<>(rec.declared);
            unused.removeAll(rec.used);
            if (!rec.declared.isEmpty() && missing.isEmpty() && unused.isEmpty()) {
                continue;
            }
            String need = rec.used.isEmpty() ? "nothing (remove the 'unsafe' block)" : "'unsafe " + String.join(" ", rec.used) + "{'";
            if (rec.declared.isEmpty()) {
                throw new CompilerException("type", rec.tok.file, rec.tok.line,
                        "a bare 'unsafe' block must say why it is unsafe; this one needs " + need);
            }
            throw new CompilerException("type", rec.tok.file, rec.tok.line,
                    "this 'unsafe " + String.join(" ", rec.declared) + "' block " + (missing.isEmpty() ? "" : "also needs " + missing + (unused.isEmpty() ? "" : " and "))
                            + (unused.isEmpty() ? "" : "does not need " + unused) + " -- it needs exactly " + need);
        }
    }

    /** "assume match EXPR": needs unsafe; reuses checkMatchCondition, the enum-field form is checkAssumeFieldVariant. See docs/TypeChecker.md, "Assume match". */
    private List<Token.MatchPattern> checkAssumeMatchCondition(Token stmt, Scope scope, FuncInfo func) {
        if (!unsafeBypass(scope, "assume")) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "'assume match' is only allowed inside 'unsafe' code");
        }
        List<Token.MatchPattern> patterns = new ArrayList<>();
        if (stmt.assumeFieldVariants != null) {
            checkAssumeFieldVariant(stmt, scope, func, patterns);
        } else {
            boolean savedAssumeKeyMode = assumeKeyMode;
            assumeKeyMode = true;
            try {
                checkMatchCondition(stmt.sub.get(0), scope, func, patterns);
            } finally {
                assumeKeyMode = savedAssumeKeyMode;
            }
        }
        return patterns;
    }

    /** "assume match x.field : V1|V2" and the float-state form; `stmt.sub.get(0)` is the built x.field tree, assumeFieldVariants the parsed variant names. */
    private void checkAssumeFieldVariant(Token stmt, Scope scope, FuncInfo func, List<Token.MatchPattern> out) {
        Token fieldAccess = stmt.sub.get(0);
        {
            // float-state form: records the same "float_state" proof as a real match case body, and never runs.
            if (fieldAccess.type == TokenType.OPERATOR && fieldAccess.text.equals(".")) {
                fieldAccess.isCompilerSynthesizedSwapAccess = true;
            }
            TypeInfo floatType = resolveExprType(fieldAccess, scope, func);
            if (isFloatType(floatType.baseType)) {
                Set<String> allowed = floatType.floatStates != null ? floatType.floatStates : FLOAT_STATE_NAMES;
                for (String state : stmt.assumeFieldVariants) {
                    if (!isFloatStateName(state)) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'" + state + "' isn't a valid float state -- expected 'finite', 'infinite', or 'nan'");
                    }
                    if (!allowed.contains(state)) {
                        throw new CompilerException("type", stmt.file, stmt.line,
                                "'" + state + "' is impossible here -- '" + floatType.canonical()
                                        + "'s own state modifier already rules it out");
                    }
                }
                String floatSlot = exprKeyOf(fieldAccess);
                if (floatSlot != null) {
                    out.add(new Token.MatchPattern(floatSlot, "float_state", null));
                }
                return;
            }
        }
        if (fieldAccess.type != TokenType.OPERATOR || !fieldAccess.text.equals(".")) {
            throw new CompilerException("type", fieldAccess.file, fieldAccess.line,
                    "'assume match x.field : VARIANT' requires a '.' member access on the left");
        }
        // 'assume match r.status : LIVE' only names the field for the checker; nothing is read or written at run time,
        // so it is not an access to a 'swap' field that would need the 'swap' tag.
        fieldAccess.isCompilerSynthesizedSwapAccess = true;
        TypeInfo fieldType = resolveExprType(fieldAccess, scope, func);
        EnumInfo enumInfo = enums.get(fieldType.baseType);
        if (enumInfo == null) {
            throw new CompilerException("type", fieldAccess.file, fieldAccess.line,
                    "'assume match x.field : VARIANT' requires an enum-typed field, got '"
                            + fieldType.canonical() + "'");
        }
        for (String variant : stmt.assumeFieldVariants) {
            if (!enumInfo.variants.contains(variant)) {
                throw new CompilerException("type", stmt.file, stmt.line,
                        "'" + enumInfo.name + "' has no variant named '" + variant + "'");
            }
        }
        String fieldSlot = slotKeyOf(fieldAccess.left);
        if (fieldSlot != null) {
            out.add(new Token.MatchPattern(fieldSlot, "enum_field_variants", fieldAccess.right.text,
                    stmt.assumeFieldVariants));
        }
    }

    /**
     * Safe code: a match/elsematch branch must add at least one proof not already active (an alive proof with kill events
     * since it was made counts as stale). Never called for a bare else or for case matches. See docs/TypeChecker.md, "Match chains".
     */
    private void requireNewTypeAssertion(List<Token.MatchPattern> newPatterns, Scope scope, Token at) {
        if (scope.currentSafety.equals("unsafe") || newPatterns.isEmpty()) {
            return;
        }
        for (Token.MatchPattern p : newPatterns) {
            // an alive proof that has seen a possibly destroying event since it was made may be stale: matching again is how it is renewed
            boolean alreadyActive = scope.activeMatchPatterns.stream().anyMatch(existing -> matchPatternsEqual(p, existing) && !ProofKills.hasEvents(existing));
            if (!alreadyActive) {
                return; // at least one genuinely new assertion -- not redundant
            }
        }
        throw new CompilerException("type", at.file, at.line,
                "this 'match' asserts nothing new to the type checker -- every one of its own proofs is "
                        + "already active here");
    }

    /** Field-by-field equality; MatchPattern has no equals(). */
    private boolean matchPatternsEqual(Token.MatchPattern a, Token.MatchPattern b) {
        return java.util.Objects.equals(a.slotKey, b.slotKey) && java.util.Objects.equals(a.kind, b.kind)
                && java.util.Objects.equals(a.targetName, b.targetName)
                && java.util.Objects.equals(a.rightSlotKey, b.rightSlotKey)
                && java.util.Objects.equals(a.provenVariants, b.provenVariants);
    }

    /** match / elsematch / else chain: union move-tracking like checkIfChain, per-branch proof scoping. See docs/TypeChecker.md, "Match chains". */
    private void checkMatchChain(Token matchTok, Scope scope, FuncInfo func, boolean insideLoop) {
        Set<String> preState = scope.movedSlots;
        Set<String> union = new HashSet<>();
        List<Set<String>> survivorMoves = new ArrayList<>();
        for (Token branch = matchTok; branch != null; branch = branch.right) {
            List<Token.MatchPattern> patterns = new ArrayList<>();
            boolean isBaseCaseMatch = false;
            if (!branch.sub.isEmpty()) {
                Token condition = branch.sub.get(0);
                checkMatchCondition(condition, scope, func, patterns);
                branch.matchPatterns = patterns;
                isBaseCaseMatch = patterns.size() == 1 && patterns.get(0).kind.equals("is_base");
                requireNewTypeAssertion(patterns, scope, condition);
            }
            List<Token.MatchPattern> extended = new ArrayList<>(scope.activeMatchPatterns);
            extended.addAll(patterns);
            Set<String> branchMoved = new HashSet<>(preState);
            Scope branchScope = new Scope(scope, branchMoved, extended, isBaseCaseMatch);
            narrowInstanceofSlots(patterns, scope, branchScope);
            checkLinesInScope(branch.childs, branchScope, func, insideLoop);
            // Natural end of a branch is not a function/loop boundary.
            branch.destructOnExit = collectNaturalEndDestruct(branch.childs, branchScope, branchScope);
            union.addAll(branchMoved);
            if (!closedCaseTerminates(branch.childs)) { survivorMoves.add(branchMoved); }
        }
        finishBranchMerge(preState, union, survivorMoves, !chainHasElse(matchTok));
    }

    /** Shadows a plain local variable with the concrete type proven by an "instanceof" proof for the branch (not for dotted/indexed slots). See docs/TypeChecker.md, "Match chains". */
    private void narrowInstanceofSlots(List<Token.MatchPattern> patterns, Scope outerScope, Scope branchScope) {
        for (Token.MatchPattern p : patterns) {
            if (!"instanceof".equals(p.kind) || p.slotKey == null || p.slotKey.contains(".") || p.slotKey.contains("[")) {
                continue;
            }
            TypeInfo currentType = outerScope.lookup(p.slotKey);
            if (currentType == null) {
                continue;
            }
            branchScope.vars.put(p.slotKey, new TypeInfo(currentType.storage, currentType.mutability, p.targetName));
        }
    }

    /** "match e{ A:{..} B:{..} }" on an enum: records no proof; each case body is an ordinary child scope. See docs/TypeChecker.md, "Float and enum case matches". */
    private void checkCaseMatch(Token matchTok, Scope scope, FuncInfo func, boolean insideLoop) {
        Token conditionExpr = matchTok.sub.get(0);
        // "match x instanceof P.enum{..}" / "implements I.enum{..}" is detected structurally before resolveExprType rejects "P.enum".
        if (isEnumForMatchCondition(conditionExpr)) {
            checkEnumForMatch(matchTok, conditionExpr, scope, func, insideLoop);
            return;
        }
        TypeInfo conditionType = resolveExprType(conditionExpr, scope, func);
        EnumInfo enumInfo = enums.get(conditionType.baseType);
        if (enumInfo != null) {
            checkEnumMatch(matchTok, conditionExpr, conditionType, enumInfo, scope, func, insideLoop);
            return;
        }
        if (isFloatType(conditionType.baseType)) {
            checkFloatMatch(matchTok, conditionExpr, conditionType, scope, func, insideLoop);
            return;
        }
        throw new CompilerException("type", conditionExpr.file, conditionExpr.line,
                "'match " + describeConditionForError(conditionExpr) + "{ LABEL:{...} ... }' requires an "
                        + "enum-typed or 'f32'/'f64' condition, got '" + conditionType.canonical() + "'");
    }

    /** "match f{ finite:.. infinite:.. nan:.. }" on a float: exhaustive over the type's allowed states, records a "float_state" proof per case, then lowers to an if-chain. See docs/TypeChecker.md, "Float and enum case matches". */
    private void checkFloatMatch(Token matchTok, Token conditionExpr, TypeInfo conditionType, Scope scope,
            FuncInfo func, boolean insideLoop) {
        // Exhaustive over the type-level state set if it has one, else all three states.
        Set<String> required = conditionType.floatStates != null
                ? conditionType.floatStates : FLOAT_STATE_NAMES;
        Set<String> covered = new HashSet<>();
        Set<String> preState = scope.movedSlots;
        Set<String> union = new HashSet<>();
        List<Set<String>> survivorMoves = new ArrayList<>();
        String conditionSlotKey = slotKeyOf(conditionExpr);
        for (Token caseTok : matchTok.childs) {
            List<Token> labels = caseTok.sub;
            Set<String> seenInThisCase = new HashSet<>();
            for (Token label : labels) {
                String labelName = label.text;
                if (!isFloatStateName(labelName)) {
                    // No default here: anything but a float state name is rejected.
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' isn't a valid float state -- expected 'finite', "
                                    + "'infinite', or 'nan'");
                }
                if (!required.contains(labelName)) {
                    // A state the type's modifier rules out is an error.
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' is impossible here -- '" + conditionType.canonical()
                                    + "'s own state modifier already rules it out");
                }
                if (!seenInThisCase.add(labelName)) {
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' repeated in the same '|'-chain");
                }
                if (!covered.add(labelName)) {
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' is already covered by an earlier case in this match");
                }
            }
            Set<String> branchMoved = new HashSet<>(preState);
            List<Token.MatchPattern> branchPatterns = new ArrayList<>(scope.activeMatchPatterns);
            if (conditionSlotKey != null) {
                branchPatterns.add(new Token.MatchPattern(conditionSlotKey, "float_state", null));
            }
            Scope branchScope = new Scope(scope, branchMoved, branchPatterns, false);
            checkLinesInScope(caseTok.childs, branchScope, func, insideLoop);
            caseTok.destructOnExit = collectNaturalEndDestruct(caseTok.childs, branchScope, branchScope);
            union.addAll(branchMoved);
            if (!closedCaseTerminates(caseTok.childs)) { survivorMoves.add(branchMoved); }
        }
        List<String> missing = new ArrayList<>();
        for (String state : required) {
            if (!covered.contains(state)) {
                missing.add(state);
            }
        }
        if (!missing.isEmpty()) {
            throw new CompilerException("type", matchTok.file, matchTok.line,
                    "match on '" + conditionType.canonical() + "' isn't exhaustive -- missing: "
                            + String.join(", ", missing));
        }
        finishBranchMerge(preState, union, survivorMoves, false);
        lowerFloatMatchToIfChain(matchTok, conditionExpr, scope, func);
    }

    /**
     * Float counterpart of lowerEnumMatchToIfChain: each non-last case becomes an if on a compiler-internal "FLOAT_CHECK"
     * node (result type set by hand; no resolveExprType case) whose right text is the alphabetically sorted chain
     * ("finite|nan"), built from a TreeSet so source order never matters. See docs/TypeChecker.md, "Float and enum case matches".
     */
    private void lowerFloatMatchToIfChain(Token matchTok, Token conditionExpr, Scope scope, FuncInfo func) {
        List<Token> cases = matchTok.childs;
        List<Token> branches = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Token caseTok = cases.get(i);
            boolean isLast = i == cases.size() - 1;
            Token branch = new Token(TokenType.KEYWORD, isLast ? "else" : "if", caseTok.line, caseTok.file);
            branch.childs = caseTok.childs;
            branch.destructOnExit = caseTok.destructOnExit;
            if (isLast) {
                branch.sub = new ArrayList<>();
            } else {
                java.util.TreeSet<String> chain = new java.util.TreeSet<>();
                for (Token label : caseTok.sub) {
                    chain.add(label.text);
                }
                // conditionExpr is reused, not cloned, in every case: it is fully checked and never mutated again.
                Token chainTok = new Token(TokenType.VARREF, String.join("|", chain), caseTok.line, caseTok.file);
                Token checkOp = new Token(TokenType.OPERATOR, "FLOAT_CHECK", caseTok.line, caseTok.file);
                checkOp.left = conditionExpr;
                checkOp.right = chainTok;
                checkOp.resolvedType = "indeterminate_bool";
                branch.sub = new ArrayList<>();
                branch.sub.add(checkOp);
            }
            branches.add(branch);
        }
        for (int i = 0; i + 1 < branches.size(); i++) {
            branches.get(i).right = branches.get(i + 1);
        }
        Token first = branches.get(0);
        matchTok.text = "if";
        matchTok.isCaseMatch = false;
        matchTok.sub = first.sub;
        matchTok.childs = first.childs;
        matchTok.destructOnExit = first.destructOnExit;
        matchTok.right = first.right;
    }

    private String labelsText(List<Token> labels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(labels.get(i).text);
        }
        return sb.toString();
    }

    private void checkEnumMatch(Token matchTok, Token conditionExpr, TypeInfo conditionType, EnumInfo enumInfo,
            Scope scope, FuncInfo func, boolean insideLoop) {
        Set<String> covered = new HashSet<>();
        boolean sawDefault = false;
        Set<String> preState = scope.movedSlots;
        Set<String> union = new HashSet<>();
        List<Set<String>> survivorMoves = new ArrayList<>();
        for (int i = 0; i < matchTok.childs.size(); i++) {
            Token caseTok = matchTok.childs.get(i);
            List<Token> labels = caseTok.sub;
            boolean isDefault = labels.size() == 1 && labels.get(0).text.equals("default");
            if (sawDefault) {
                throw new CompilerException("type", caseTok.file, caseTok.line,
                        "'default' must be the last case in the match -- '" + labelsText(labels)
                                + "' comes after it");
            }
            if (isDefault) {
                if (!enumInfo.isNonExhaustive) {
                    throw new CompilerException("type", caseTok.file, caseTok.line,
                            "'default' is only allowed matching an enum decorated '@non_exhaustive' -- '"
                                    + enumInfo.name + "' isn't; every variant must be covered by name "
                                    + "instead");
                }
                if (i != matchTok.childs.size() - 1) {
                    throw new CompilerException("type", caseTok.file, caseTok.line,
                            "'default' must be the last case in the match");
                }
                sawDefault = true;
            } else {
                for (Token label : labels) {
                    String labelName = label.text;
                    if (labelName.equals("default")) {
                        // "default" cannot share a "|"-chain with a variant name; it must stand alone as the final case.
                        throw new CompilerException("type", label.file, label.line,
                                "'default' can't be combined with another label in a '|'-chain -- it must "
                                        + "stand alone as its own, final case");
                    }
                    if (!enumInfo.variants.contains(labelName)) {
                        throw new CompilerException("type", label.file, label.line,
                                "'" + enumInfo.name + "' has no variant named '" + labelName + "'");
                    }
                    if (!covered.add(labelName)) {
                        throw new CompilerException("type", label.file, label.line,
                                "'" + labelName + "' is already covered by an earlier case in this match");
                    }
                }
            }
            Set<String> branchMoved = new HashSet<>(preState);
            List<Token.MatchPattern> branchPatterns = scope.activeMatchPatterns;
            List<String> caseVariants = isDefault
                    ? enumInfo.variants.stream().filter(v -> !covered.contains(v))
                            .collect(java.util.stream.Collectors.toList())
                    : labels.stream().map(l -> l.text).collect(java.util.stream.Collectors.toList());
            // Every "receiver.field" condition records the enum_field_variants proof that @lock(match self.x : X) qualifiers read.
            if (conditionExpr.type == TokenType.OPERATOR && conditionExpr.text.equals(".")) {
                String fieldSlot = slotKeyOf(conditionExpr.left);
                if (fieldSlot != null) {
                    branchPatterns = new ArrayList<>(branchPatterns);
                    branchPatterns.add(new Token.MatchPattern(fieldSlot, "enum_field_variants",
                            conditionExpr.right.text, caseVariants));
                }
            }
            Scope branchScope = new Scope(scope, branchMoved, branchPatterns, false);
            checkLinesInScope(caseTok.childs, branchScope, func, insideLoop);
            caseTok.destructOnExit = collectNaturalEndDestruct(caseTok.childs, branchScope, branchScope);
            union.addAll(branchMoved);
            if (!closedCaseTerminates(caseTok.childs)) { survivorMoves.add(branchMoved); }
        }
        List<String> missing = new ArrayList<>();
        for (String variant : enumInfo.variants) {
            if (!variant.equals("default") && !covered.contains(variant)) {
                missing.add(variant);
            }
        }
        if (enumInfo.isNonExhaustive) {
            if (sawDefault && missing.isEmpty()) {
                throw new CompilerException("type", matchTok.file, matchTok.line,
                        "'default' is unreachable: every variant of '" + enumInfo.name
                                + "' is already covered by name");
            }
            if (!sawDefault && !missing.isEmpty()) {
                throw new CompilerException("type", matchTok.file, matchTok.line,
                        "match on '@non_exhaustive' enum '" + enumInfo.name + "' needs a 'default' case "
                                + "as the last one -- uncovered: " + String.join(", ", missing));
            }
        } else if (!missing.isEmpty()) {
            throw new CompilerException("type", matchTok.file, matchTok.line,
                    "match on '" + enumInfo.name + "' isn't exhaustive -- missing: "
                            + String.join(", ", missing));
        }
        finishBranchMerge(preState, union, survivorMoves, false);
        // Lowered to an if/else-if/else chain only now, after all validation ran on the real construct; the last case gets no
        // comparison. matchTok is mutated in place; emitIfChain ignores .text, only emitStatement needs "if". See docs/TypeChecker.md, "Float and enum case matches".
        lowerEnumMatchToIfChain(matchTok, conditionExpr, enumInfo, scope, func);
    }

    /** Lowering step of checkEnumMatch: one type-checked "conditionExpr == Enum.Variant" per case except the last, via the ordinary "==" dispatch. */
    private void lowerEnumMatchToIfChain(Token matchTok, Token conditionExpr, EnumInfo enumInfo, Scope scope,
            FuncInfo func) {
        List<Token> cases = matchTok.childs;
        List<Token> branches = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Token caseTok = cases.get(i);
            boolean isLast = i == cases.size() - 1;
            Token branch = new Token(TokenType.KEYWORD, isLast ? "else" : "if", caseTok.line, caseTok.file);
            branch.childs = caseTok.childs;
            branch.destructOnExit = caseTok.destructOnExit;
            if (isLast) {
                branch.sub = new ArrayList<>();
            } else {
                // One "conditionExpr == Enum.Label" per label, OR-combined for "A|B:" chains.
                // conditionExpr is reused, not cloned (it is fully checked and never mutated again; re-resolving it is idempotent).
                Token combined = null;
                for (Token labelTok : caseTok.sub) {
                    Token enumNameTok = new Token(TokenType.VARREF, enumInfo.name, labelTok.line, labelTok.file);
                    Token variantTok = new Token(TokenType.VARREF, labelTok.text, labelTok.line, labelTok.file);
                    Token dotOp = new Token(TokenType.OPERATOR, ".", labelTok.line, labelTok.file);
                    dotOp.left = enumNameTok;
                    dotOp.right = variantTok;
                    Token eqOp = new Token(TokenType.OPERATOR, "==", labelTok.line, labelTok.file);
                    eqOp.left = conditionExpr;
                    eqOp.right = dotOp;
                    resolveExprType(eqOp, scope, func);
                    if (combined == null) {
                        combined = eqOp;
                    } else {
                        Token orOp = new Token(TokenType.OPERATOR, "||", labelTok.line, labelTok.file);
                        orOp.left = combined;
                        orOp.right = eqOp;
                        resolveExprType(orOp, scope, func);
                        combined = orOp;
                    }
                }
                branch.sub = new ArrayList<>();
                branch.sub.add(combined);
            }
            branches.add(branch);
        }
        for (int i = 0; i + 1 < branches.size(); i++) {
            branches.get(i).right = branches.get(i + 1);
        }
        Token first = branches.get(0);
        matchTok.text = "if";
        matchTok.isCaseMatch = false;
        matchTok.sub = first.sub;
        matchTok.childs = first.childs;
        matchTok.destructOnExit = first.destructOnExit;
        matchTok.right = first.right;
    }

    /** True for exactly "x instanceof Owner.enum" / "x implements Owner.enum" ("enum" lexes as a VARREF after "."; Lexer.justSawDotOperator). */
    private boolean isEnumForMatchCondition(Token conditionExpr) {
        if (conditionExpr.type != TokenType.OPERATOR
                || !(conditionExpr.text.equals("instanceof") || conditionExpr.text.equals("implements"))) {
            return false;
        }
        Token right = conditionExpr.right;
        return right.type == TokenType.OPERATOR && right.text.equals(".")
                && right.left.type == TokenType.VARREF && right.right.type == TokenType.VARREF
                && right.right.text.equals("enum");
    }

    /**
     * "match x implements Iface.enum{ Impl1:{..} .. }": labels are the members of the synthesized "$enum_for_Iface" enum
     * (generateEnumForEnums) and must cover it exactly, no default. "instanceof Owner.enum" is always an error (structs have no
     * subclasses). Each case gets an "instanceof" proof. See docs/TypeChecker.md, "Float and enum case matches".
     */
    private void checkEnumForMatch(Token matchTok, Token conditionExpr, Scope scope, FuncInfo func,
            boolean insideLoop) {
        boolean isInstanceof = conditionExpr.text.equals("instanceof");
        Token xExpr = conditionExpr.left;
        String ownerName = conditionExpr.right.left.text;
        List<String> memberNames;
        if (isInstanceof) {
            if (interfaces.containsKey(ownerName)) {
                throw new CompilerException("type", conditionExpr.right.file, conditionExpr.right.line,
                        "'instanceof " + ownerName + ".enum' is not a form: 'instanceof' takes a struct name. To match over "
                                + "the implementers of the interface '" + ownerName + "', write 'match x implements " + ownerName
                                + ".enum{ ... }'");
            }
            throw new CompilerException("type", conditionExpr.right.file, conditionExpr.right.line,
                    "'" + ownerName + ".enum' no longer exists: structs have no subclasses. "
                            + "Only an interface has an '.enum' (use 'x implements " + ownerName + "')");
        } else {
            if (!interfaces.containsKey(ownerName)) {
                throw new CompilerException("type", conditionExpr.right.file, conditionExpr.right.line,
                        "'" + ownerName + "' is not a known interface");
            }
            EnumInfo enumForInfo = enums.get("$enum_for_" + ownerName);
            if (enumForInfo == null) {
                throw new CompilerException("type", conditionExpr.right.file, conditionExpr.right.line,
                        "'" + ownerName + "' has no known implementers -- '.enum' requires at least one");
            }
            memberNames = enumForInfo.variants;
        }
        // A throwaway "x instanceof FirstMember" runs checkInstanceof once on x (always instanceof: members are concrete structs).
        Token probe = new Token(TokenType.OPERATOR, "instanceof", conditionExpr.line, conditionExpr.file);
        probe.left = xExpr;
        probe.right = new Token(TokenType.VARREF, memberNames.get(0), conditionExpr.line, conditionExpr.file);
        resolveExprType(probe, scope, func);
        conditionExpr.resolvedType = "imut_bool";
        Set<String> required = new LinkedHashSet<>(memberNames);
        Set<String> covered = new HashSet<>();
        Set<String> preState = scope.movedSlots;
        Set<String> union = new HashSet<>();
        List<Set<String>> survivorMoves = new ArrayList<>();
        String xSlotKey = slotKeyOf(xExpr);
        for (Token caseTok : matchTok.childs) {
            List<Token> labels = caseTok.sub;
            for (Token label : labels) {
                String labelName = label.text;
                if (labelName.equals("default")) {
                    throw new CompilerException("type", label.file, label.line,
                            "'default' is not valid here -- an enum-style match on '." + ownerName
                                    + ".enum' must be exhaustive over its exact membership");
                }
                if (!required.contains(labelName)) {
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' is not a known implementer of '" + ownerName + "'");
                }
                if (!covered.add(labelName)) {
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' is already covered by an earlier case in this match");
                }
            }
            Set<String> branchMoved = new HashSet<>(preState);
            List<Token.MatchPattern> branchPatterns = scope.activeMatchPatterns;
            if (xSlotKey != null && labels.size() == 1) {
                // Always an "instanceof" proof, even for the "implements" form: a label names a concrete implementer.
                branchPatterns = new ArrayList<>(branchPatterns);
                branchPatterns.add(new Token.MatchPattern(xSlotKey, "instanceof", labels.get(0).text));
            }
            Scope branchScope = new Scope(scope, branchMoved, branchPatterns, false);
            // Same instanceof shadowing as checkMatchChain (narrowInstanceofSlots), so "c.foo()" in an "A:" case resolves as A's method.
            narrowInstanceofSlots(branchPatterns, scope, branchScope);
            checkLinesInScope(caseTok.childs, branchScope, func, insideLoop);
            caseTok.destructOnExit = collectNaturalEndDestruct(caseTok.childs, branchScope, branchScope);
            union.addAll(branchMoved);
            if (!closedCaseTerminates(caseTok.childs)) { survivorMoves.add(branchMoved); }
        }
        List<String> missing = new ArrayList<>();
        for (String member : required) {
            if (!covered.contains(member)) {
                missing.add(member);
            }
        }
        if (!missing.isEmpty()) {
            throw new CompilerException("type", matchTok.file, matchTok.line,
                    "match on '" + ownerName + ".enum' isn't exhaustive -- missing: " + String.join(", ", missing));
        }
        finishBranchMerge(preState, union, survivorMoves, false);
        lowerEnumForMatchToIfChain(matchTok, xExpr, scope, func);
    }

    /** Lowering step of checkEnumForMatch: like lowerEnumMatchToIfChain but each comparison is an INSTANCEOF node (OR-combined for "|" chains). */
    private void lowerEnumForMatchToIfChain(Token matchTok, Token xExpr, Scope scope, FuncInfo func) {
        List<Token> cases = matchTok.childs;
        List<Token> branches = new ArrayList<>();
        // Always "instanceof": a case label is a concrete struct, even for the "implements" form.
        String opText = "instanceof";
        for (int i = 0; i < cases.size(); i++) {
            Token caseTok = cases.get(i);
            boolean isLast = i == cases.size() - 1;
            Token branch = new Token(TokenType.KEYWORD, isLast ? "else" : "if", caseTok.line, caseTok.file);
            branch.childs = caseTok.childs;
            branch.destructOnExit = caseTok.destructOnExit;
            if (isLast) {
                branch.sub = new ArrayList<>();
            } else {
                Token combined = null;
                for (Token labelTok : caseTok.sub) {
                    Token targetTok = new Token(TokenType.VARREF, labelTok.text, labelTok.line, labelTok.file);
                    Token checkOp = new Token(TokenType.OPERATOR, opText, labelTok.line, labelTok.file);
                    checkOp.left = xExpr;
                    checkOp.right = targetTok;
                    resolveExprType(checkOp, scope, func);
                    if (combined == null) {
                        combined = checkOp;
                    } else {
                        Token orOp = new Token(TokenType.OPERATOR, "||", labelTok.line, labelTok.file);
                        orOp.left = combined;
                        orOp.right = checkOp;
                        resolveExprType(orOp, scope, func);
                        combined = orOp;
                    }
                }
                branch.sub = new ArrayList<>();
                branch.sub.add(combined);
            }
            branches.add(branch);
        }
        for (int i = 0; i + 1 < branches.size(); i++) {
            branches.get(i).right = branches.get(i + 1);
        }
        Token first = branches.get(0);
        matchTok.text = "if";
        matchTok.isCaseMatch = false;
        matchTok.sub = first.sub;
        matchTok.childs = first.childs;
        matchTok.destructOnExit = first.destructOnExit;
        matchTok.right = first.right;
    }

    /**
     * Match condition grammar: left instanceof right, left implements right, left in right, a bare expression, chained with
     * "and". Each leaf is checked through resolveExprType and records one MatchPattern. See docs/TypeChecker.md, "Match chains".
     */
    private void checkMatchCondition(Token node, Scope scope, FuncInfo func, List<Token.MatchPattern> out) {
        if (node.type == TokenType.OPERATOR && node.text.equals("and")) {
            checkMatchCondition(node.left, scope, func, out);
            checkMatchCondition(node.right, scope, func, out);
            // "and" has no resolveExprType case; its result type is set here (always bool).
            node.resolvedType = "imut_bool";
            return;
        }
        if (node.type == TokenType.OPERATOR && (node.text.equals("!=") || node.text.equals(">"))
                && (node.left.type == TokenType.VARREF || (assumeKeyMode && exprKeyOf(node.left) != null))
                && node.right.type == TokenType.INTEGER
                && node.right.text.equals("0")) {
            // "match rht != 0 and lft != 0{ .. }": proves an integer nonzero, which makes / % /= %= legal in safe code.
            // Never falls through to the bare-expression "alive" case (an alive check on the comparison's bool result is meaningless).
            TypeInfo leftType = resolveExprType(node.left, scope, func);
            boolean isInteger = INTEGER_TYPES.contains(leftType.baseType);
            if (!isInteger) {
                // Not an integer after all: let the ordinary comparison check report its own error.
                resolveExprType(node, scope, func);
                out.add(new Token.MatchPattern(slotKeyOf(node), "alive", null));
                return;
            }
            // A literal 0 fits every integer type, but resolveExprType types it u64 and checkComparison needs equal base types, so
            // the comparison's types are set by hand here (literal coerced to the left side's type).
            node.right.resolvedType = leftType.canonical();
            node.resolvedType = "imut_bool";
            boolean isUnsigned = UNSIGNED_TO_SIGNED.containsKey(leftType.baseType);
            // "!= 0" suffices only for unsigned types (a signed divisor could still be -1: TYPE_MIN / -1 traps); "> 0" always suffices.
            boolean sufficientForDivision = node.text.equals(">") || (node.text.equals("!=") && isUnsigned);
            if (sufficientForDivision) {
                out.add(new Token.MatchPattern(proofKeyOf(node.left), "nonzero", null));
            }
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("instanceof")) {
            resolveExprType(node, scope, func);
            out.add(new Token.MatchPattern(slotKeyOf(node.left), "instanceof", node.right.resolvedType));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("implements")) {
            resolveExprType(node, scope, func);
            out.add(new Token.MatchPattern(slotKeyOf(node.left), "implements", node.right.resolvedType));
            return;
        }
        if (node.type == TokenType.OPERATOR && (node.text.equals("in") || node.text.equals("into"))
                && node.left.type == TokenType.OPERATOR && node.left.text.equals("CALL")
                && node.left.left.type == TokenType.VARREF && node.left.left.text.equals("Some")) {
            // "match Some(i) in x" / "into x": sugar form handled separately (the ordinary in/into cases would reject "Some(i)").
            checkSomeIndexBoundsCondition(node, node.text, scope, func, out);
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("in")) {
            resolveExprType(node, scope, func);
            // "u64 in GuaranteedEnum" is never a valid match condition: the right side is a type name, so no bounds proof can target it.
            if (node.isGuaranteedEnumMembershipCheck) {
                throw new CompilerException("type", node.file, node.line,
                        "'u64 in " + node.right.resolvedType + "' is not valid as a match condition -- "
                                + "it's only usable as an ordinary bool expression");
            }
            // "u64 in string" is a byte-membership scan, not a bounds check: likewise never a match condition.
            if (node.isStringMembershipCheck) {
                throw new CompilerException("type", node.file, node.line,
                        "'u64 in <string>' is not valid as a match condition -- "
                                + "it's only usable as an ordinary bool expression");
            }
            out.add(new Token.MatchPattern(boundsSlotKeyOf(node.left), "in", null, boundsTargetSlotOf(node.right)));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("into")) {
            // "match i into arr{..}": like "in" (checkIn, called by hand), but recorded as an "into" proof, which also
            // legalizes writes at that exact index (isProvenWritable). See docs/TypeChecker.md, "Match chains".
            TypeInfo intoCondType = checkIn(node, scope, func);
            node.resolvedType = intoCondType.canonical();
            if (node.isGuaranteedEnumMembershipCheck) {
                throw new CompilerException("type", node.file, node.line,
                        "'u64 into " + node.right.resolvedType + "' is not valid -- 'into' only makes "
                                + "sense against a range/dynarray target, never a guaranteed enum");
            }
            if (node.isStringMembershipCheck) {
                throw new CompilerException("type", node.file, node.line,
                        "'u64 into <string>' is not valid -- 'into' only makes sense against a "
                                + "range/dynarray target, never a string");
            }
            out.add(new Token.MatchPattern(boundsSlotKeyOf(node.left), "into", null, boundsTargetSlotOf(node.right)));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("within")) {
            // "within" is checked like "in" and recorded under the same "in" proof kind, so every consumer of an "in" proof treats it alike.
            resolveExprType(node, scope, func);
            out.add(new Token.MatchPattern(boundsSlotKeyOf(node.left), "in", null, boundsTargetSlotOf(node.right)));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("fits")) {
            // "match x fits u8{..}": inside the branch x is proven representable in u8, so "x as u8" is legal; a later write to x kills it.
            TypeInfo fitsLeft = resolveExprType(node.left, scope, func);
            if (fitsLeft.storage != null || !INTEGER_TYPES.contains(fitsLeft.baseType)) {
                throw new CompilerException("type", node.left.file, node.left.line,
                        "'fits' requires a plain integer value on the left, got '" + fitsLeft.canonical() + "'");
            }
            if (node.right.type != TokenType.VARREF || !INTEGER_TYPES.contains(node.right.text)) {
                throw new CompilerException("type", node.right.file, node.right.line,
                        "'fits' requires an integer type name on the right (u8, s32, ...)");
            }
            String fitsKey = slotKeyOf(node.left);
            if (fitsKey == null) {
                throw new CompilerException("type", node.left.file, node.left.line,
                        "'fits' needs a variable or a field on the left, so the proof has a slot to attach to");
            }
            node.fitsSourceBase = fitsLeft.baseType;
            node.fitsTargetBase = node.right.text;
            node.resolvedType = "imut_bool";
            out.add(new Token.MatchPattern(fitsKey, "fits", node.right.text));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("is")) {
            // "match RANGE_PARAM is base{..}": reserved form, left must be a range, right literally "base" (range empty, tested at run time by
            // IS_BASE). Like "and", it has no resolveExprType case, so its result type is set here.
            TypeInfo leftType = resolveExprType(node.left, scope, func);
            if (!isRangeType(leftType)) {
                throw new CompilerException("type", node.left.file, node.left.line,
                        "'is' requires a 'range' on the left, got '" + leftType.canonical() + "'");
            }
            if (node.right.type != TokenType.VARREF || !node.right.text.equals("base")) {
                throw new CompilerException("type", node.right.file, node.right.line,
                        "'is' only supports 'base' on the right");
            }
            node.resolvedType = "imut_bool";
            out.add(new Token.MatchPattern(slotKeyOf(node.left), "is_base", null));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left.type == TokenType.VARREF
                && node.left.text.equals("Some")) {
            // "match Some(x){..}": the bytecode is a plain GT_ALIVE_CHECK; resolveExprType dispatches to checkSomeBuiltin and this adds
            // the "alive" proof for x's slot.
            ProofKills.suppressReads++;       // the alive check itself reads the pointer; that read is what renews the proof
            try {
                resolveExprType(node, scope, func);
            } finally {
                ProofKills.suppressReads--;
            }
            Token argExpr = collectSingleBuiltinArg(node, "Some", scope, func).get(0);
            node.someProofKey = proofKeyOf(argExpr);
            Token.MatchPattern someProof = new Token.MatchPattern(proofKeyOf(argExpr), "alive", null);
            someProof.onId = node.someOnRef;
            out.add(someProof);
            return;
        }
        // A bare value expression is no longer a match condition; "match Some(x)" is the only way to prove a pointer alive.
        throw new CompilerException("type", node.file, node.line,
                "a 'match' condition must be 'Some(x)', 'x in y', 'x within y', 'x into y', 'x instanceof Y', "
                        + "'x implements Y', 'x fits T' (for an integer x), 'x != 0'/'x > 0' (for an integer x), 'x is base' (for a range x), or "
                        + "an 'and'-chain of these -- a bare value expression is no longer accepted on "
                        + "its own; write 'match Some(" + describeConditionForError(node) + ")' if you "
                        + "meant to prove it isn't null");
    }

    /** Best-effort text for the "did you mean Some(..)" error: the variable name, else "...". */
    private String describeConditionForError(Token node) {
        return node.type == TokenType.VARREF ? node.text : "...";
    }

    /**
     * "match Some(i) in/into x{..}" = "match i in/into x{ match Some(x[i]){..} }": grants the index bounds proof and the
     * element-alive proof for x[i] at once. `i` must be a variable or a "."-chain. Also used by the "for match Some(i)" header
     * (checkForLoop) and the @lock(match Some(i) in self.field) qualifier. See docs/TypeChecker.md, "Match chains".
     */
    private void checkSomeIndexBoundsCondition(Token node, String kind, Scope scope, FuncInfo func,
            List<Token.MatchPattern> out) {
        Token someCall = node.left;
        Token indexExpr = collectSingleBuiltinArg(someCall, "Some", scope, func).get(0);
        if (indexExpr.type != TokenType.VARREF
                && !(indexExpr.type == TokenType.OPERATOR && indexExpr.text.equals(".")
                        && indexExpr.right.type == TokenType.VARREF)) {
            throw new CompilerException("type", indexExpr.file, indexExpr.line,
                    "'Some(...) " + kind + "' requires a bare variable or a '.'-chain inside 'Some(...)', "
                            + "not an arbitrary expression");
        }
        // The bounds check reuses checkIn on a synthetic node.
        Token boundsOp = new Token(TokenType.OPERATOR, kind, node.line, node.file);
        boundsOp.left = indexExpr;
        boundsOp.right = node.right;
        TypeInfo condType = checkIn(boundsOp, scope, func);
        node.resolvedType = condType.canonical();
        someCall.resolvedType = "imut_bool";
        if (boundsOp.isGuaranteedEnumMembershipCheck) {
            throw new CompilerException("type", node.file, node.line,
                    "'Some(...) " + kind + " " + boundsOp.right.resolvedType + "' is not valid -- "
                            + "'Some(...) " + kind + "' only makes sense against a range/dynarray "
                            + "target, never a guaranteed enum");
        }
        String indexSlot = slotKeyOf(indexExpr);
        String targetSlot = boundsTargetSlotOf(node.right);
        Token.MatchPattern boundsPattern = new Token.MatchPattern(indexSlot, kind, null, targetSlot);
        out.add(boundsPattern);

        // Element-alive proof for x[i]: a throwaway LOOKUP on the "range(origin)"-unwrapped target (boundsTargetExprOf), checked
        // under a scope that already has the bounds proof.
        Token originExpr = boundsTargetExprOf(node.right);
        Token bracket = new Token(TokenType.DELINEATOR, "[", node.line, node.file);
        bracket.childs.add(indexExpr);
        Token lookupNode = new Token(TokenType.OPERATOR, "LOOKUP", node.line, node.file);
        lookupNode.left = originExpr;
        lookupNode.right = bracket;
        List<Token.MatchPattern> withBounds = new ArrayList<>(scope.activeMatchPatterns);
        withBounds.add(boundsPattern);
        Scope probeScope = new Scope(scope, withBounds, false);
        TypeInfo elemType = resolveExprType(lookupNode, probeScope, func);
        if (elemType.storage == null) {
            throw new CompilerException("type", indexExpr.file, indexExpr.line,
                    "'Some(...)' requires the element type to be a pointer, got '" + elemType.canonical()
                            + "'");
        }
        Token.MatchPattern elemProof = new Token.MatchPattern(slotKeyOf(lookupNode), "alive", null);
        out.add(elemProof);
        lookupNode.someOnRef = "ref".equals(elemType.storage) && !elemType.isSome;
        elemProof.onId = lookupNode.someOnRef;
        lookupNode.someProofKey = slotKeyOf(lookupNode);
        // Lets BytecodeEmitter emit the GT_ALIVE_CHECK for this resolved lookup directly.
        node.someIndexElementExpr = lookupNode;
    }

    /** Slot a bounds proof applies to: the range variable itself, or the origin argument of "range(origin)". Null if untrackable. */
    private String boundsTargetSlotOf(Token rightExpr) {
        if (rightExpr.type == TokenType.OPERATOR && rightExpr.text.equals("CALL")
                && rightExpr.left.type == TokenType.VARREF && rightExpr.left.text.equals("range")
                && !rightExpr.right.childs.isEmpty()) {
            List<Token> args = new ArrayList<>();
            collectCommaArgs(rightExpr.right.childs.get(0), args);
            if (args.size() == 1) {
                return boundsSlotKeyOf(args.get(0));
            }
        }
        return boundsSlotKeyOf(rightExpr);
    }

    /** Like boundsTargetSlotOf but returns the unwrapped expression Token, so a synthetic "origin[i]" LOOKUP can be built. */
    private Token boundsTargetExprOf(Token rightExpr) {
        if (rightExpr.type == TokenType.OPERATOR && rightExpr.text.equals("CALL")
                && rightExpr.left.type == TokenType.VARREF && rightExpr.left.text.equals("range")
                && !rightExpr.right.childs.isEmpty()) {
            List<Token> args = new ArrayList<>();
            collectCommaArgs(rightExpr.right.childs.get(0), args);
            if (args.size() == 1) {
                return args.get(0);
            }
        }
        return rightExpr;
    }


    /**
     * Enforces generic bounds ("func f<T: Iface>") at each instantiation site only (templates are never checked unbound).
     * Runs right after collectDeclarations so structImplementsInterface is reliable; GenericsExpander recorded the checks in
     * Token.pendingGenericBoundChecks. See docs/TypeChecker.md, "Generic bounds".
     */
    private void validateGenericBounds(List<Token> rootLines) {
        for (Token lineTok : rootLines) {
            Token entry = lineTok.childs.get(0);
            List<Token.GenericBoundCheck> checks = entry.pendingGenericBoundChecks;
            if (checks == null) {
                continue;
            }
            for (Token.GenericBoundCheck check : checks) {
                if (!structImplementsInterface(check.concreteTypeName, check.interfaceName)) {
                    throw new CompilerException("type", entry.file, entry.line,
                            "'" + check.concreteTypeName + "' does not implement '" + check.interfaceName
                                    + "' -- required by the bound on type parameter '" + check.typeParamName
                                    + "'");
                }
            }
        }
    }

    /** True if the struct implements the interface directly or through an interface that extends it, including standalone "impl X for Y" blocks (implsByConcreteType). */
    private boolean structImplementsInterface(String structName, String interfaceName) {
        StructInfo info = structs.get(structName);
        if (info == null) {
            return false;
        }
        for (String directIface : info.implementsNames) {
            if (interfaceExtendsOrIs(directIface, interfaceName)) {
                return true;
            }
        }
        List<ImplInfo> impls = implsByConcreteType.get(structName);
        if (impls != null) {
            for (ImplInfo impl : impls) {
                if (impl.interfaceName != null && interfaceExtendsOrIs(impl.interfaceName, interfaceName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean interfaceExtendsOrIs(String actualInterfaceName, String targetInterfaceName) {
        if (actualInterfaceName.equals(targetInterfaceName)) {
            return true;
        }
        InterfaceInfo info = interfaces.get(actualInterfaceName);
        if (info == null) {
            return false;
        }
        for (String parent : info.extendsNames) {
            if (interfaceExtendsOrIs(parent, targetInterfaceName)) {
                return true;
            }
        }
        return false;
    }

    /** If chain: each branch is checked against its own copy of the pre-if moved set; the shared set becomes their union. See docs/TypeChecker.md, "Branch merge and partly-moved slots". */
    private void checkIfChain(Token ifTok, Scope scope, FuncInfo func, boolean insideLoop) {
        Set<String> preState = scope.movedSlots;
        Set<String> union = new HashSet<>();
        List<Set<String>> survivorMoves = new ArrayList<>();
        for (Token branch = ifTok; branch != null; branch = branch.right) {
            if (!branch.sub.isEmpty()) {
                Token condNode = branch.sub.get(0);
                // The one place insideShortCircuitEligiblePosition is seeded true: a branch condition is the top level where &&then / ||then is legal.
                boolean previousEligible = insideShortCircuitEligiblePosition;
                insideShortCircuitEligiblePosition = true;
                TypeInfo condType;
                try {
                    condType = resolveExprType(condNode, scope, func);
                } finally {
                    insideShortCircuitEligiblePosition = previousEligible;
                }
                requireBoolOrNumericCondition(condType, condNode);
            }
            Set<String> branchMoved = new HashSet<>(preState);
            Scope branchScope = new Scope(scope, branchMoved);
            // if / else do not change loop context.
            Scope branchBodyScope = checkLines(branch.childs, branchScope, func, insideLoop);
            // A branch's natural end is not a function/loop boundary: only its own locals are destructed.
            branch.destructOnExit = collectNaturalEndDestruct(branch.childs, branchBodyScope, branchBodyScope);
            union.addAll(branchMoved);
            if (!closedCaseTerminates(branch.childs)) { survivorMoves.add(branchMoved); }
        }
        finishBranchMerge(preState, union, survivorMoves, !chainHasElse(ifTok));
    }

    /** An if/elseif condition must be bool or a numeric type (integer, f32, char; C-style "zero is false"); no truthy pointers or collections. See docs/TypeChecker.md, "Conditions". */
    private void requireBoolOrNumericCondition(TypeInfo t, Token at) {
        // Storage-less only: a pointer to a bool/number is not a condition (null checks use "match Some(x)").
        if (t.storage == null && (t.baseType.equals("bool") || t.baseType.equals("char")
                || NUMERIC_TYPES.contains(t.baseType))) {
            return;
        }
        throw new CompilerException("type", at.file, at.line,
                "an 'if'/'elseif' condition must be 'bool' or a numeric type (an integer, 'f32', or 'char') "
                        + "-- matching C's own \"any scalar, zero is false\" convention -- but this one resolved "
                        + "to '" + t.canonical() + "'");
    }

    /**
     * "for x in range{..}" desugars into real AST nodes: let $range = ..; let x = $range.start; condition "x in $range"; step x++.
     * The "in" condition is byte-for-byte what an ordinary "in" expression emits. See docs/TypeChecker.md, "For loop desugaring".
     */
    private void checkForLoop(Token forTok, Scope scope, FuncInfo func) {
        boolean rootIsIn = !forTok.sub.isEmpty() && forTok.sub.get(0).type == TokenType.OPERATOR
                && forTok.sub.get(0).text.equals("in");
        boolean rootIsInto = !forTok.sub.isEmpty() && forTok.sub.get(0).type == TokenType.OPERATOR
                && forTok.sub.get(0).text.equals("into");
        if (!rootIsIn && !rootIsInto) {
            throw new CompilerException("type", forTok.file, forTok.line,
                    "'for' requires 'varname in range { ... }'");
        }
        if (rootIsInto && !forTok.isForMatch) {
            // A plain "for i into arr" is rejected: only "for match i into arr" grants the write proof.
            throw new CompilerException("type", forTok.file, forTok.line,
                    "'into' in a 'for' header requires 'for match', not a plain 'for' -- write 'for match "
                            + forTok.sub.get(0).left.text + " into ...{...}', or use plain 'in' instead");
        }
        Token originalIn = forTok.sub.get(0);
        Token loopVarTok = originalIn.left;
        Token rangeExpr = originalIn.right;
        // "for match Some(i) in/into x{..}": detected before the plain-variable check; the body is wrapped in a synthetic
        // "match Some(x[i])" further down.
        boolean isSomeIndexForm = loopVarTok.type == TokenType.OPERATOR && loopVarTok.text.equals("CALL")
                && loopVarTok.left.type == TokenType.VARREF && loopVarTok.left.text.equals("Some");
        if (isSomeIndexForm) {
            // Needs "for match", otherwise the proofs have nowhere to go.
            if (!forTok.isForMatch) {
                throw new CompilerException("type", originalIn.file, originalIn.line,
                        "'for Some(...) " + originalIn.text + " ...{...}' requires 'for match', not a "
                                + "plain 'for'");
            }
            Token indexExpr = collectSingleBuiltinArg(loopVarTok, "Some", scope, func).get(0);
            if (indexExpr.type != TokenType.VARREF) {
                throw new CompilerException("type", indexExpr.file, indexExpr.line,
                        "'for match Some(...) " + originalIn.text + "' requires a bare, new variable name "
                                + "inside 'Some(...)', not an arbitrary expression");
            }
            loopVarTok = indexExpr;
        }
        if (loopVarTok.type != TokenType.VARREF) {
            throw new CompilerException("type", originalIn.file, originalIn.line,
                    "'for' requires a plain, new variable name on the left of 'in', not an existing "
                            + "expression");
        }

        TypeInfo rangeType = resolveExprType(rangeExpr, scope, func);
        if (isUnsafeDynArrayType(rangeType)) {
            // Banned: an unsafe dynarray has no compiler-known length; iterate by hand with loop{} in unsafe code.
            throw new CompilerException("type", rangeExpr.file, rangeExpr.line,
                    "'for ... in/into' cannot be used on an 'unsafe dynarray' -- it has no compiler-known "
                            + "length to loop against; iterate by hand with a bare 'loop{}' inside 'unsafe' "
                            + "code instead");
        }
        if (isArrayType(rangeType.baseType)) {
            // "for i in arr" / "for match i in arr" on a fixed array: sugar for
            // "for i in range(arr)" (see checkIn's same rewrite).
            rangeExpr = wrapFixedArrayAsRange(rangeExpr);
            rangeType = resolveExprType(rangeExpr, scope, func);
        }
        boolean isDynArraySource = isDynArrayType(rangeType);
        if (!isRangeType(rangeType) && !isDynArraySource) {
            throw new CompilerException("type", rangeExpr.file, rangeExpr.line,
                    "'for ... in' requires a range or a dynarray on the right, got '"
                            + rangeType.canonical() + "'");
        }
        // Dynarray source: the loop variable starts at a literal 0 (no .start); the body gets no proof about dynArrExpr[x] itself
        // (it needs its own "match x in .."; entries may be null).

        forVarCounter++;
        String rangeVarName = "$for_range_" + forVarCounter;
        Scope forScope = new Scope(scope);

        // let $range = <rangeExpr>; for a dynarray "let $range = mut ref <rangeExpr>" so the owns source is borrowed, not moved.
        Token rangeSourceExpr = rangeExpr;
        if (isDynArraySource) {
            Token refWrap = new Token(TokenType.OPERATOR, "ref", forTok.line, forTok.file);
            refWrap.left = rangeExpr;
            // "ref X" is indeterminate-mutability, so it needs a mut wrapper to bind to a let.
            Token mutRefWrap = new Token(TokenType.OPERATOR, "mut", forTok.line, forTok.file);
            mutRefWrap.left = refWrap;
            rangeSourceExpr = mutRefWrap;
        }
        Token rangeNameTok = new Token(TokenType.VARREF, rangeVarName, forTok.line, forTok.file);
        Token rangeLetTok = new Token(TokenType.KEYWORD, "let", forTok.line, forTok.file);
        rangeLetTok.childs = new ArrayList<>();
        rangeLetTok.childs.add(rangeNameTok);
        Token rangeAssign = new Token(TokenType.OPERATOR, "=", forTok.line, forTok.file);
        rangeAssign.left = rangeLetTok;
        rangeAssign.right = rangeSourceExpr;
        TypeInfo rangeAssignType = checkAssign(rangeAssign, forScope, func);
        rangeAssign.resolvedType = rangeAssignType.canonical();

        // let x = $range.start (a dynarray starts at literal 0).
        Token loopVarLetTok = new Token(TokenType.KEYWORD, "let", forTok.line, forTok.file);
        loopVarLetTok.childs = new ArrayList<>();
        loopVarLetTok.childs.add(loopVarTok);
        Token loopVarAssign = new Token(TokenType.OPERATOR, "=", forTok.line, forTok.file);
        loopVarAssign.left = loopVarLetTok;
        Token loopVarInitialValue;
        if (isDynArraySource) {
            loopVarInitialValue = new Token(TokenType.INTEGER, "0", forTok.line, forTok.file);
        } else {
            Token rangeRefForStart = new Token(TokenType.VARREF, rangeVarName, forTok.line, forTok.file);
            Token startNameTok = new Token(TokenType.VARREF, "start", forTok.line, forTok.file);
            Token startDot = new Token(TokenType.OPERATOR, ".", forTok.line, forTok.file);
            startDot.left = rangeRefForStart;
            startDot.right = startNameTok;
            loopVarInitialValue = startDot;
        }
        // The loop variable is mut (the synthesized ++ writes it); .start / literal 0 are indeterminate, so wrapping in mut is legal.
        Token mutStartDot = new Token(TokenType.OPERATOR, "mut", forTok.line, forTok.file);
        mutStartDot.left = loopVarInitialValue;
        loopVarAssign.right = mutStartDot;
        TypeInfo loopVarAssignType = checkAssign(loopVarAssign, forScope, func);
        loopVarAssign.resolvedType = loopVarAssignType.canonical();

        // per-iteration condition: x in $range (a real 'in' node)
        Token loopVarRefForCond = new Token(TokenType.VARREF, loopVarTok.text, forTok.line, forTok.file);
        Token rangeRefForCond = new Token(TokenType.VARREF, rangeVarName, forTok.line, forTok.file);
        Token conditionIn = new Token(TokenType.OPERATOR, "in", forTok.line, forTok.file);
        conditionIn.left = loopVarRefForCond;
        conditionIn.right = rangeRefForCond;
        TypeInfo condType = checkIn(conditionIn, forScope, func);
        conditionIn.resolvedType = condType.canonical();

        // increment: x++ (a real postfix '++' node)
        Token loopVarRefForInc = new Token(TokenType.VARREF, loopVarTok.text, forTok.line, forTok.file);
        Token increment = new Token(TokenType.OPERATOR, "++", forTok.line, forTok.file);
        increment.unary = true;
        increment.left = loopVarRefForInc;
        TypeInfo incType = checkIncrement(increment, forScope, func);
        increment.resolvedType = incType.canonical();

        // "for match i in x{..}": same bytecode, but the body scope also carries the "i in x" proof, built against the ORIGINAL
        // rangeExpr (not $range) so indexing in the body names the same slot; wrapped in a fresh Scope, forScope stays untouched.
        Scope forScopeForBody = forScope;
        if (forTok.isForMatch) {
            String iSlotKey = slotKeyOf(loopVarTok);
            if (iSlotKey != null) {
                List<Token.MatchPattern> extended = new ArrayList<>(forScope.activeMatchPatterns);
                // "into" also grants everything "in" does; only the recorded kind differs.
                String kind = rootIsInto ? "into" : "in";
                extended.add(new Token.MatchPattern(iSlotKey, kind, null, boundsTargetSlotOf(rangeExpr)));
                forScopeForBody = new Scope(forScope, forScope.movedSlots, extended, false);
            }
        }
        // The loop variable and the source slot are immutable inside the body (all for forms). A fresh scope is wrapped even without
        // for-match so the synthesized pieces above, which do reassign the variable, are unaffected.
        if (forScopeForBody == forScope) {
            forScopeForBody = new Scope(forScope);
        }
        Set<String> immutableHere = new HashSet<>();
        immutableHere.add(loopVarTok.text);
        String arrSlotKey = slotKeyOf(rangeExpr);
        if (arrSlotKey != null) {
            immutableHere.add(arrSlotKey);
        }
        forScopeForBody.ownLoopImmutableSlots = immutableHere;
        if (isSomeIndexForm) {
            // Wrap the body in a real "match Some(origin[i]){..}" statement so existing match checking and emission handle it unchanged.
            Token originExpr = boundsTargetExprOf(rangeExpr);
            Token idxRef = new Token(TokenType.VARREF, loopVarTok.text, forTok.line, forTok.file);
            Token bracket = new Token(TokenType.DELINEATOR, "[", forTok.line, forTok.file);
            bracket.childs.add(idxRef);
            Token lookupNode = new Token(TokenType.OPERATOR, "LOOKUP", forTok.line, forTok.file);
            lookupNode.left = originExpr;
            lookupNode.right = bracket;
            Token someArgs = new Token(TokenType.DELINEATOR, "(", forTok.line, forTok.file);
            someArgs.childs.add(lookupNode);
            Token someVarRef = new Token(TokenType.VARREF, "Some", forTok.line, forTok.file);
            Token someCall = new Token(TokenType.OPERATOR, "CALL", forTok.line, forTok.file);
            someCall.left = someVarRef;
            someCall.right = someArgs;
            Token syntheticMatch = new Token(TokenType.KEYWORD, "match", forTok.line, forTok.file);
            syntheticMatch.sub = new ArrayList<>();
            syntheticMatch.sub.add(someCall);
            syntheticMatch.childs = forTok.childs;
            // LINE wrapper shape (Parser.wrapAsLine): statement lists hold LINE tokens with the statement in childs.get(0).
            Token lineTok = new Token(TokenType.LINE, "line", forTok.line, forTok.file);
            lineTok.childs.add(syntheticMatch);
            forTok.childs = new ArrayList<>();
            forTok.childs.add(lineTok);
        }
        collectLoopMoves(forTok.childs, forScope, forScope.movedSlots);
        Scope forBodyScope = checkLinesAsLoopBody(forTok.childs, forScopeForBody, func, isDynArraySource);
        // Per-iteration scope exit, as for "loop".
        forTok.destructOnExit = collectNaturalEndDestruct(forTok.childs, forBodyScope, forBodyScope);
        // Desugared pieces for BytecodeEmitter, stored in forTok.sub: [rangeAssign, loopVarAssign, conditionIn, increment].
        forTok.sub = new ArrayList<>();
        forTok.sub.add(rangeAssign);
        forTok.sub.add(loopVarAssign);
        forTok.sub.add(conditionIn);
        forTok.sub.add(increment);
    }

    private void checkReturn(Token returnTok, Scope scope, FuncInfo func) {
        if (returnTok.sub.isEmpty()) {
            if (!func.returnType.baseType.equals("void")) {
                throw new CompilerException("type", returnTok.file, returnTok.line,
                        "empty 'return' in a function declared to return '"
                                + func.returnType.canonical() + "'");
            }
            returnTok.resolvedType = func.returnType.canonical();
            returnTok.destructOnExit = collectOwnsToDestruct(scope, scope.functionRootScope, null);
            returnTok.unlockOnExit = collectLockReleasesToBoundary(scope, scope.functionRootScope);
            return;
        }
        Token previousDirectReturnExprNode = directReturnExprNode;
        directReturnExprNode = returnTok.sub.get(0);
        // 'return foo()' forwards this function's $ret_dest into a struct-returning callee (third legal RVO position).
        // See docs/TypeChecker.md, "Struct return values (RVO)".
        boolean previousRvoEligible = insideStructRvoDestinationPosition;
        insideStructRvoDestinationPosition = isStructByValueReturn(func.returnType);
        TypeInfo exprType;
        try {
            exprType = resolveExprType(returnTok.sub.get(0), scope, func);
        } finally {
            directReturnExprNode = previousDirectReturnExprNode;
            insideStructRvoDestinationPosition = previousRvoEligible;
        }
        if (func.returnType.isSome && !exprType.isSome && isProvenAlive(returnTok.sub.get(0), scope)) {
            // A return expression proven alive by an active `match Some` counts as 'some' for a 'some' return type.
            // A bare `new` never does (malloc can fail). See docs/TypeChecker.md, "Returning owns some".
            exprType = exprType.withSome(true);
            returnTok.sub.get(0).resolvedType = exprType.canonical();
        }
        if (!acceptsAdaptingLiteral(func.returnType, exprType, returnTok.sub.get(0))) {
            throw new CompilerException("type", returnTok.file, returnTok.line,
                    "return type mismatch: function returns '" + func.returnType.canonical()
                            + "' but this statement returns '" + exprType.canonical() + "'");
        }
        if (func.isAsync && containsPointerAnywhere(func.returnType) && !unsafeBypass(scope, "async")) {
            throw new CompilerException("type", returnTok.file, returnTok.line,
                    "returning '" + func.returnType.canonical() + "' (a pointer, or a struct with one "
                            + "nested somewhere inside it) from an '@async' function requires 'unsafe' "
                            + "code");
        }
        // Struct-by-value return: only a fresh struct literal, a readable value chain of the same struct, or a tail call.
        // See docs/TypeChecker.md, "Struct return values (RVO)".
        if (isStructByValueReturn(func.returnType)) {
            Token retExpr = returnTok.sub.get(0);
            boolean isFreshLiteral = retExpr.type == TokenType.OPERATOR && retExpr.text.equals("INSTANTIATE");
            boolean isReadableExpr = isReadableValueChain(retExpr);
            // checkCall sets isStructRvoCall only for an eligible position, so testing the flag is enough.
            Token unwrapped = retExpr;
            while (unwrapped.type == TokenType.OPERATOR
                    && (unwrapped.text.equals("mut") || unwrapped.text.equals("imut"))) {
                unwrapped = unwrapped.left;
            }
            boolean isTailForwardCall = unwrapped.isStructRvoCall;
            if (!isFreshLiteral && !isReadableExpr && !isTailForwardCall) {
                throw new CompilerException("type", returnTok.file, returnTok.line,
                        "a function returning '" + func.returnType.baseType + "' by value may only "
                                + "'return' a fresh struct-literal expression (e.g. 'return "
                                + func.returnType.baseType + "{...}'), an existing variable, field, or "
                                + "array/dynarray/string element (or any chain of those, e.g. 'return "
                                + func.returnType.baseType.toLowerCase() + "Var', 'return self.field', "
                                + "'return arr[i]', 'return self.arr[i]', 'return arr[i].field'), or a "
                                + "direct call to another function returning the same struct type by "
                                + "value (e.g. 'return otherFunc()') -- not any other kind of computed "
                                + "expression");
            }
        }
        // Annotate with the declared return type (differs from the expression's when returning 'null' for a pointer type).
        returnTok.resolvedType = func.returnType.canonical();
        // The slot named directly in the return expression passes to the caller and is exempt from destruction.
        String preservedSlotKey = slotKeyOf(returnTok.sub.get(0));
        if (preservedSlotKey != null) {
            fnEverMoved.add(preservedSlotKey);
        }
        returnTok.destructOnExit = collectOwnsToDestruct(scope, scope.functionRootScope, preservedSlotKey);
        returnTok.unlockOnExit = collectLockReleasesToBoundary(scope, scope.functionRootScope);
    }

    /**
         * `throw <string>`: unconditional termination that unwinds to the nearest `try`/`catch`, destructing the owns locals
         * alive at this throw site (no preserved slot). See docs/TypeChecker.md, "throw, try and catch".
         */
    private void checkThrow(Token throwTok, Scope scope, FuncInfo func) {
        if (throwTok.sub.isEmpty()) {
            throw new CompilerException("type", throwTok.file, throwTok.line,
                    "'throw' requires a string expression, e.g. 'throw \"message\"'");
        }
        // 'throw' is safe code but needs '@throws' on the function; checked first so the message is never masked.
        if (getDecorator(func.funcToken.decorators, "throws") == null) {
            throw new CompilerException("type", throwTok.file, throwTok.line,
                    "'" + func.name + "' uses 'throw' but is not decorated '@throws' -- add '@throws' to "
                            + "this function's declaration");
        }
        // Ghost-table functions have no gt_routine of their own, so 'throw' would name a label that is never emitted.
        for (String decoratorName : Arrays.asList("gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve")) {
            if (getDecorator(func.funcToken.decorators, decoratorName) != null) {
                throw new CompilerException("type", throwTok.file, throwTok.line,
                        "'throw' cannot be used inside '@" + decoratorName + "' -- a ghost-table function "
                                + "has no 'gt_routine' of its own to unwind into");
            }
        }
        if (isPureFunc(func)) {
            throw new CompilerException("type", throwTok.file, throwTok.line,
                    "'" + func.name + "' is '@pure' and cannot 'throw' -- an unconditional program "
                            + "termination can never be verified at compile time");
        }
        rethrowOperand = throwTok.sub.get(0);   // `throw e` of a catch parameter is the one bare use that stays legal
        TypeInfo exprType;
        try {
            exprType = resolveExprType(throwTok.sub.get(0), scope, func);
        } finally {
            rethrowOperand = null;
        }
        TypeInfo requiredType = new TypeInfo("static", "imut", "string");
        if (!typesCompatible(requiredType, exprType)) {
            throw new CompilerException("type", throwTok.file, throwTok.line,
                    "'throw' requires a 'static imut string' -- got '" + exprType.canonical() + "'");
        }
        // Any valid `throw` needs the per-throw-site destruct-and-unwind sequence to exist in the bytecode.
        usesThrow = true;
        func.sawThrow = true;
        throwSites.add(new ThrowSite(func, throwTok));
        // Per-throw-site destruct list and held-lock releases (a throw out of an OPEN case must not leave the lock held).
        throwTok.destructOnExit = collectOwnsToDestruct(scope, scope.functionRootScope, null);
        throwTok.unlockOnExit = collectLockReleasesToBoundary(scope, scope.functionRootScope);
        // THROW takes one operand: a string literal (templates are already reduced) or a bare variable.
        Token expr = throwTok.sub.get(0);
        if (expr.type != TokenType.STRING && expr.type != TokenType.VARREF) {
            throw new CompilerException("type", throwTok.file, throwTok.line,
                    "'throw' requires a string literal or a bare variable -- not a more complex "
                            + "expression");
        }
        throwTok.resolvedType = exprType.canonical();
    }

    /**
         * `try CALL catch(e){..}`: wraps exactly one call, `new`, `par`/`await` or `dyn`/`resize`/`clone`, type-checks it as usual
         * and checks the catch body as a nested block. See docs/TypeChecker.md, "throw, try and catch".
         */
    private TypeInfo checkTry(Token tryTok, Scope scope, FuncInfo func, boolean insideLoop) {
        if (tryTok.sub == null || tryTok.sub.isEmpty()) {
            throw new CompilerException("type", tryTok.file, tryTok.line,
                    "'try' requires an expression (a call to a '@throws' function) before 'catch'");
        }
        Token callNode = tryTok.sub.get(0);
        // Shapes `try` may wrap: a CALL (including the dyn/resize/clone builtins), `new`, `par`/`await`.
        // `new` and the builtins enforce their own wrapping rule; a CALL is enforced by requireThrowsWrapping.
        boolean isAsyncNode = callNode.type == TokenType.OPERATOR
                && (callNode.text.equals("par") || callNode.text.equals("await"));
        boolean isNewNode = callNode.type == TokenType.OPERATOR && callNode.text.equals("new");
        boolean isThrowingBuiltinCall = callNode.type == TokenType.OPERATOR && callNode.text.equals("CALL")
                && callNode.left.type == TokenType.VARREF
                && (callNode.left.text.equals("dyn") || callNode.left.text.equals("resize")
                        || callNode.left.text.equals("clone"));
        if (!(callNode.type == TokenType.OPERATOR && callNode.text.equals("CALL")) && !isNewNode && !isAsyncNode) {
            throw new CompilerException("type", callNode.file, callNode.line,
                    "'try' can only wrap a direct call to a '@throws' function, a 'new' expression, a "
                            + "'par'/'await' call, or a 'dyn'/'resize'/'clone' builtin call, not a more general expression");
        }
        callNode.insideTry = true;
        // A nested allocation cannot get its own `try`, so coverage is tracked by the insideThrowingAllocContext flag
        // for the whole descent, not by the per-token insideTry.
        boolean previousInsideThrowingAllocContext = insideThrowingAllocContext;
        if (isNewNode || isThrowingBuiltinCall || isAsyncNode) {
            insideThrowingAllocContext = true;
        }
        TypeInfo resultType;
        try {
            resultType = resolveExprType(callNode, scope, func);
        } finally {
            insideThrowingAllocContext = previousInsideThrowingAllocContext;
        }
        // Declare the catch parameter in a scope around the catch body. A bare use is rejected in the VARREF case
        // (`e.msg`, `e.stack_trace`, or `throw e`).
        Scope catchBodyScope = new Scope(scope);
        catchBodyScope.vars.put(tryTok.catchParamName, new TypeInfo("static", "imut", "string"));
        Token previousActiveCatchTry = activeCatchTries.put(tryTok.catchParamName, tryTok);
        // insideCatchBody / catchEnclosingLoopBoundary are saved and restored around the catch body, so a `continue`
        // at any depth inside it is recognised, and a nested catch restores the outer values.
        boolean previousInsideCatchBody = insideCatchBody;
        Scope previousCatchLoopBoundary = catchEnclosingLoopBoundary;
        insideCatchBody = true;
        catchEnclosingLoopBoundary = scope.loopBoundaryScope;
        try {
            checkLines(tryTok.childs, catchBodyScope, func, insideLoop);
        } finally {
            insideCatchBody = previousInsideCatchBody;
            catchEnclosingLoopBoundary = previousCatchLoopBoundary;
            if (previousActiveCatchTry == null) {
                activeCatchTries.remove(tryTok.catchParamName);
            } else {
                activeCatchTries.put(tryTok.catchParamName, previousActiveCatchTry);
            }
        }
        // No destructOnExit: a catch body never reaches its natural end (it must terminate, checked below).
        tryTok.resolvedType = resultType.canonical();
        // A catch body must end every path in return, throw or continue: a caught throw jumps straight into it,
        // never back through the call's return path. See docs/TypeChecker.md, "throw, try and catch".
        if (!definitelyReturns(tryTok.childs)) {
            throw new CompilerException("type", tryTok.file, tryTok.line,
                    "'catch' must be terminating -- every path through its body must end in a 'return', "
                            + "a 'throw', or a 'continue' (a caught throw is redirected straight into this "
                            + "block, never back through the wrapped call's own normal return path, so "
                            + "there is no well-defined way to fall out of 'catch' and resume normal "
                            + "execution -- 'continue' is only legal here when this 'catch' is itself "
                            + "inside an enclosing 'try { ... }' block, which is exactly where control "
                            + "would resume)");
        }
        return resultType;
    }

    /** The two-way @throws contract, checked at each call-resolution site once the callee is known: an '@throws' callee must be in try/`?`, a non-throwing one must not be. */
    private void requireThrowsWrapping(FuncInfo candidate, Token op) {
        if (candidate.isThrows && !op.insideTry && !op.coveredByThrowingNew) {
            throw new CompilerException("type", op.file, op.line,
                    "call to '" + candidate.name + "', which is decorated '@throws', must be wrapped in "
                            + "'try ... catch { ... }'");
        }
        if (!candidate.isThrows && op.insideTry) {
            throw new CompilerException("type", op.file, op.line,
                    "'try' wraps a call to '" + candidate.name + "', which is not decorated '@throws' -- "
                            + "'try'/'catch' is only needed (and only allowed) around a call to a "
                            + "'@throws' function");
        }
    }

    // ---- expression type resolution -----------------------------------

    private TypeInfo resolveExprType(Token node, Scope scope, FuncInfo func) {
        TypeInfo result = resolveExprTypeInner(node, scope, func);
        if (node.resolvedCallTarget != null && node.type == TokenType.OPERATOR && "CALL".equals(node.text)) {
            ProofKills.call(scope.activeMatchPatterns, node, node.resolvedCallTarget);
        }
        node.resolvedType = result.canonical();
        if (func != null && isPureRtFunc(func)) {
            requirePureRtNoPointer(result, node, func, "in an expression");
        }
        return result;
    }

    private TypeInfo resolveExprTypeInner(Token node, Scope scope, FuncInfo func) {
        switch (node.type) {
            case STRING:
                // String literals are 'static imut string' and 'some' (always present); 'some' coerces one-way to plain.
                return new TypeInfo("static", "imut", "string").withSome(true);
            case CHAR:
                return new TypeInfo(null, "indeterminate", "char");
            case INTEGER:
                // Built-in limit constants (u64Max, ...) carry a forced concrete type; other integer literals are indeterminate u64.
                if (node.forcedLiteralType != null) {
                    return new TypeInfo(null, "imut", node.forcedLiteralType);
                }
                {
                    TypeInfo literalType = new TypeInfo(null, "indeterminate", "u64");
                    try {
                        literalType.literalValue = new java.math.BigInteger(node.text);
                    } catch (NumberFormatException notADecimalLiteral) {
                        literalType.literalValue = null;
                    }
                    if (literalType.literalValue != null && literalType.literalValue.bitLength() > 64) {
                        // (decimal, hexadecimal and binary spellings alike: the lexer hands over decimal text) -- no integer type is wider than 64 bits
                        throw new CompilerException("type", node.file, node.line,
                                "integer literal " + node.text + " does not fit in any integer type (the largest, u64, ends at 18446744073709551615)");
                    }
                    return literalType;
                }
            case FLOAT:
                if (node.forcedLiteralType != null) {
                    return new TypeInfo(null, "imut", node.forcedLiteralType);
                }
                {
                    TypeInfo floatLit = new TypeInfo(null, "indeterminate", "f32");
                    floatLit.floatLiteral = true;
                    return floatLit;
                }
            case BOOL:
                return new TypeInfo(null, "indeterminate", "bool");
            case NULL:
                return new TypeInfo(null, null, "null");
            case TEMPLATE_STRING:
                return resolveTemplateString(node);
            case VARREF: {
                if (lookupAsmName(node.text, scope) != null) {
                    throw new CompilerException("type", node.file, node.line,
                            "'" + node.text + "' is an 'ASM' name -- it can only be used as a bare, "
                                    + "whole statement ('" + node.text + ";'), never as part of an "
                                    + "expression, argument list, struct literal, or anything else");
                }
                if (scope.movedSlots.contains(node.text)) {
                    throw new CompilerException("type", node.file, node.line,
                            "use of '" + node.text + "' after its ownership was moved");
                }
                TypeInfo t = scope.lookup(node.text);
                if (t != null && activeCatchTries.containsKey(node.text) && node != rethrowOperand) {
                    // stack_trace.md, 3.6: the catch parameter is the caught error (message + trace), not the message string
                    throw new CompilerException("type", node.file, node.line,
                            "'" + node.text + "' is the caught error -- use '" + node.text + ".msg' for the message, '" + node.text
                                    + ".stack_trace' for the trace, or 'throw " + node.text + "' to re-throw it");
                }
                if (t != null) {
                    if ("ref".equals(t.storage) && t.isSome && node != assignTargetVar) {
                        ProofKills.refSomeRead(node, scope.activeMatchPatterns);
                    }
                    if (scope.isStatic(node.text)) {
                        requireSharedStateOrUnsafe(t, node.text, scope, node);
                        requirePureDoesNotReadMutableStatic(t, node.text, func, node);
                    }
                    if (t.literalValue != null || t.literalElements != null || t.floatLiteral || t.floatArrayLiteral) {
                        // A variable is never a literal: `let x = mut 5` binds a plain u64 slot whose value can
                        // change, so reads of it must not adapt to narrower slots the way the literal 5 does.
                        t = t.withSome(t.isSome);
                        t.floatLiteral = false;
                        t.floatArrayLiteral = false;
                        t.literalValue = null;
                        t.literalElements = null;
                    }
                    return t;
                }
                if (globals.containsKey(node.text)) {
                    // Globals already exist in `globals` while bodies are checked, so use-before-declaration needs an explicit declOrder comparison.
                    Integer declaredAt = globalDeclOrder.get(node.text);
                    if (func != null && declaredAt != null && declaredAt >= func.declOrder) {
                        throw new CompilerException("type", node.file, node.line,
                                "'" + node.text + "' is used before its declaration");
                    }
                    if (!publicGlobals.contains(node.text)) {
                        // Private globals are visible only in their declaring file (let/static are exempt).
                        String declFile = globalRhsByName.get(node.text).file;
                        if (!declFile.equals(node.file)) {
                            throw new CompilerException("type", node.file, node.line,
                                    "'" + node.text + "' is private -- not accessible outside the file "
                                            + "it's declared in");
                        }
                    }
                    TypeInfo globalType = globals.get(node.text);
                    requireSharedStateOrUnsafe(globalType, node.text, scope, node);
                    requirePureDoesNotReadMutableStatic(globalType, node.text, func, node);
                    return globalType;
                }
                if (constExprs.containsKey(node.text)) {
                    // Consts are substituted: the VARREF token is mutated in place into the literal, so the emitter needs no const logic.
                    Token literal = resolveConstValue(node.text, node);
                    node.type = literal.type;
                    node.text = literal.text;
                    node.literalValue = literal.literalValue;
                    node.quoteDelimiter = literal.quoteDelimiter;
                    node.forcedLiteralType = literal.forcedLiteralType;
                    return resolveExprTypeInner(node, scope, func);
                }
                // A bare function name used as a value is a function pointer with its own signature and decorators.
                List<FuncInfo> asFunc = functions.get(node.text);
                if (asFunc != null) {
                    if (asFunc.size() > 1) {
                        throw new CompilerException("type", node.file, node.line,
                                "'" + node.text + "' has multiple overloads -- a bare function reference "
                                        + "must resolve to exactly one signature");
                    }
                    FuncInfo target = asFunc.get(0);
                    if (hasDecorator(target.funcToken.decorators, "event_loop")) {
                        // '@event_loop' cannot be referenced anywhere, same as being called.
                        throw new CompilerException("type", node.file, node.line,
                                "'" + node.text + "' is '@event_loop' -- it cannot be called or "
                                        + "referenced anywhere in the compilation unit");
                    }
                    Set<String> decorators = new HashSet<>();
                    if (target.funcToken.decorators != null) {
                        for (Token.Decorator d : target.funcToken.decorators) {
                            decorators.add(d.name);
                        }
                    }
                    return TypeInfo.funcPointer("imut", target.paramTypes, target.returnType, decorators,
                            target.callConvention);
                }
                throw new CompilerException("type", node.file, node.line,
                        "use of undeclared variable '" + node.text + "'");
            }
            case KEYWORD:
                if (node.text.equals("let")) {
                    throw new CompilerException("type", node.file, node.line,
                            "internal error: bare 'let' token in expression position");
                }
                if (node.text.equals("try")) {
                    // Expression-position `try`; insideLoop is passed as false (see checkTry).
                    return checkTry(node, scope, func, false);
                }
                break;
            case DELINEATOR:
                if (node.text.equals("(")) {
                    if (node.childs.isEmpty()) {
                        throw new CompilerException("type", node.file, node.line, "empty '()' expression");
                    }
                    return resolveExprType(node.childs.get(0), scope, func);
                }
                if (node.text.equals("[")) {
                    return checkArrayLiteral(node, scope, func);
                }
                break;
            case OPERATOR:
                return resolveOperatorType(node, scope, func);
            default:
                break;
        }
        throw new CompilerException("type", node.file, node.line,
                "internal error: don't know how to type '" + node.type + "(" + node.text + ")'");
    }

    private TypeInfo resolveOperatorType(Token op, Scope scope, FuncInfo func) {
        // Captured once and reset before dispatch, so a stale 'true' from an enclosing context cannot reach other operators;
        // only checkLogical sees it. See docs/TypeChecker.md, "Operator dispatch context flags".
        boolean eligibleHere = insideShortCircuitEligiblePosition;
        insideShortCircuitEligiblePosition = false;
        // Same capture-and-reset for the struct-RVO destination flag; only checkCall consults it.
        boolean eligibleForRvoHere = insideStructRvoDestinationPosition;
        insideStructRvoDestinationPosition = false;
        switch (op.text) {
            case "=":
                return checkAssign(op, scope, func);
            case "+=": case "-=": case "*=": case "/=": case "%=":
                return checkCompoundAssign(op, scope, func);
            case "+": case "-": case "*": case "/": case "%":
                if (op.unary) {
                    return checkUnaryMinus(op, scope, func);
                }
                return checkArithmetic(op, scope, func);
            case "!":
                return checkLogicalNot(op, scope, func);
            case "++": case "--":
                return checkIncrement(op, scope, func);
            case "&&": case "||": case "&&then": case "||then":
                return checkLogical(op, scope, func, eligibleHere);
            case "<": case ">": case "<=": case ">=": case "==": case "!=":
                return checkComparison(op, scope, func);
            case "CALL":
                return checkCall(op, scope, func, eligibleForRvoHere);
            case "LOOKUP":
                return checkLookup(op, scope, func);
            case "as":
                return checkAs(op, scope, func);
            case ".":
                return checkDot(op, scope, func);
            case "INSTANTIATE":
                return checkInstantiate(op, scope, func);
            case "owns": case "ref": case "auto": case "raw": case "static":
                return checkAddressOf(op, scope, func);
            case "mut": case "imut":
                // mut/imut are transparent for the RVO flag: re-armed one level down so the wrapped CALL still sees it.
                insideStructRvoDestinationPosition = eligibleForRvoHere;
                return checkMutabilityOf(op, scope, func);
            case "new":
                return checkNew(op, scope, func);
            case "await":
                return checkAwait(op, scope, func);
            case "par":
                return checkPar(op, scope, func);
            case "unsafe":
                return checkUnsafeDynWrapper(op, scope, func);
            case "atomic":
                return checkAtomicWrapper(op, scope, func);
            case "swap":
                return checkSwapOperator(op, scope, func);
            case "..":
                return checkRange(op, scope, func);
            case "in":
                return checkIn(op, scope, func);
            case "within":
                return checkWithin(op, scope, func);
            case "instanceof":
                return checkInstanceof(op, scope, func);
            case "implements":
                return checkImplementsOperator(op, scope, func);
            case "dup":
                // 'dup' is expanded earlier (DupExpander); reaching here means it was used outside an array literal.
                throw new CompilerException("type", op.file, op.line,
                        "'dup' can only be used as an element of an array literal");
            case ",":
                resolveExprType(op.left, scope, func);
                return resolveExprType(op.right, scope, func);
            case "and":
                // 'and' is only valid inside a match condition (checkMatchCondition walks the chain itself).
                throw new CompilerException("type", op.file, op.line,
                        "'and' can only be used to chain conditions inside a 'match' statement");
            case "into":
                // 'into' is legal only in match, for match and @lock(...), which call checkIn directly; any other use lands here.
                throw new CompilerException("type", op.file, op.line,
                        "'into' has no meaning as an ordinary expression -- it can only be used as a "
                                + "match condition ('match i into arr{...}'), a 'for match' loop header "
                                + "('for match i into arr{...}'), or inside an '@lock(...)' qualifier");
            case "fits":
                throw new CompilerException("type", op.file, op.line,
                        "'fits' has no meaning as an ordinary expression -- it can only be used as a match "
                                + "condition ('match x fits u8{...}')");
            default:
                throw new CompilerException("type", op.file, op.line,
                        "internal error: unhandled operator '" + op.text + "' in type checker");
        }
    }

    /** The tracked slot key of a bare name, an `a.b` member path or `x[i]` (parens transparent); null for untrackable shapes, which are never moved. */
    private String slotKeyOf(Token node) {
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
            return slotKeyOf(node.childs.get(0));
        }
        if (node.type == TokenType.VARREF) {
            return node.text;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && node.right.type == TokenType.VARREF) {
            String leftKey = slotKeyOf(node.left);
            return leftKey == null ? null : leftKey + "." + node.right.text;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("LOOKUP") && node.right != null
                && !node.right.childs.isEmpty()) {
            // `x[i]` as a slot, so a proof granted for a specific element can be matched by a later access.
            // Both target and index must themselves be slots.
            String leftKey = slotKeyOf(node.left);
            String idxKey = slotKeyOf(node.right.childs.get(0));
            if (leftKey == null || idxKey == null) {
                return null;
            }
            return leftKey + "[" + idxKey + "]";
        }
        return null;
    }

    /**
         * Invalidates every active match proof whose slotKey or rightSlotKey is `target`'s slot (or an assumed expression
         * key mentioning it); called from every write. See docs/TypeChecker.md, "Match-proof invalidation".
         */
    private void invalidateMatchPatternsForWrite(Token target, Scope scope) {
        String key = slotKeyOf(target);
        if (key == null) {
            return;
        }
        java.util.regex.Pattern word = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_.])" + java.util.regex.Pattern.quote(key) + "(?![A-Za-z0-9_])");
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            if (key.equals(p.slotKey) || key.equals(p.rightSlotKey)) {
                p.invalidated = true;
            } else if (p.slotKey != null && p.slotKey.startsWith("(") && word.matcher(p.slotKey).find()) {
                p.invalidated = true; // an assumed fact about an expression ("p+i") dies when any variable in it is written
            }
        }
    }

    /** True while the condition of an 'assume match' is being checked: a proof there is an assertion to the checker that never runs, so it may name an arbitrary expression, not only a variable. */
    private boolean assumeKeyMode = false;

    /**
     * A canonical key for an arithmetic expression over variables and integer literals ("(p+i)"), used only to record
     * and look up facts vouched for with 'assume match'; null when the expression has any other shape. Always starts with
     * "(" so it can never collide with a variable/field slot key.
     */
    private String exprKeyOf(Token node) {
        if (node == null) {
            return null;
        }
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && node.childs != null && node.childs.size() == 1) {
            return exprKeyOf(node.childs.get(0));
        }
        if (node.type == TokenType.INTEGER || node.type == TokenType.FLOAT) {
            return node.text;
        }
        String slot = slotKeyOf(node);
        if (slot != null) {
            return slot;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("-") && node.right == null && node.left != null) {
            String uk = exprKeyOf(node.left);
            return uk == null ? null : "(-" + uk + ")";
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && node.left != null && node.right != null
                && node.right.type == TokenType.VARREF) {
            String dk = exprKeyOf(node.left);
            return dk == null ? null : dk + "." + node.right.text;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("LOOKUP") && node.left != null && node.right != null
                && !node.right.childs.isEmpty()) {
            String lk = exprKeyOf(node.left);
            String ik = exprKeyOf(node.right.childs.get(0));
            return lk == null || ik == null ? null : lk + "[" + ik + "]";
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left != null
                && node.left.type == TokenType.VARREF && node.right != null) {
            // a plain call `f(a, b)` keyed by name and argument keys (for 'assume match f(x) : finite')
            List<Token> callArgs = new ArrayList<>();
            if (!node.right.childs.isEmpty()) {
                collectCommaArgs(node.right.childs.get(0), callArgs);
            }
            StringBuilder sb = new StringBuilder(node.left.text).append("(");
            for (int ai = 0; ai < callArgs.size(); ai++) {
                String ak = exprKeyOf(callArgs.get(ai));
                if (ak == null) {
                    return null;
                }
                sb.append(ai > 0 ? "," : "").append(ak);
            }
            return sb.append(")").toString();
        }
        if (node.type == TokenType.OPERATOR && node.left != null && node.right != null
                && java.util.Arrays.asList("+", "-", "*", "/", "%").contains(node.text)) {
            String l = exprKeyOf(node.left);
            String r = exprKeyOf(node.right);
            return l == null || r == null ? null : "(" + l + node.text + r + ")";
        }
        return null;
    }

    /** The key a proof is recorded under: the slot key, or (in an 'assume match' condition only) the expression key. */
    private String proofKeyOf(Token node) {
        String k = slotKeyOf(node);
        return (k == null && assumeKeyMode) ? exprKeyOf(node) : k;
    }

    /** The key a proof is looked up under: the slot key, else the expression key. */
    private String lookupKeyOf(Token node) {
        String k = slotKeyOf(node);
        return k != null ? k : exprKeyOf(node);
    }

    /** Source-like text of an expression, for hints in error messages. */
    private String exprHint(Token node) {
        String k = exprKeyOf(node);
        if (k == null) {
            return node.text;
        }
        return k.startsWith("(") && k.endsWith(")") ? k.substring(1, k.length() - 1) : k;
    }

    /**
     * Operands of one call, array literal or `dyn([..])` are all checked before any of them is marked moved, so `f(a, a)` or
     * `dyn([a, a])` passes the read check. A slot that is already moved when its own move is being recorded was moved a moment ago
     * by a sibling operand (a later read of an earlier-moved slot is rejected when it is read): two owners of one block.
     */
    private void requireNotMovedAlready(String key, Token valueExpr, Scope scope) {
        if (scope.movedSlots.contains(key)) {
            throw new CompilerException("type", valueExpr.file, valueExpr.line,
                    "'" + key + "' is moved more than once in the same expression -- two owners of one block");
        }
    }

    /** > 0 while the operands of a `new` / `dyn` are being checked: an owner moved in there is freed if the allocation fails, so it ends alive proofs. */
    private int newDynDepth = 0;

    /** Marks the source slot of a value bound to an `owns` type as moved (let, assignment, call argument, struct field). See docs/TypeChecker.md, "Ownership moves". */
    private void markMovedIfOwned(TypeInfo declaredType, Token valueExpr, Scope scope) {
        markMovedIfOwnedInner(declaredType, valueExpr, scope);
        if (newDynDepth > 0 && valueExpr.isOwnershipMoveSource) {
            ProofKills.kill(scope.activeMatchPatterns, valueExpr, "moving an owner into 'new' or 'dyn' (it is freed if the allocation fails)");
        }
    }

    private void markMovedIfOwnedInner(TypeInfo declaredType, Token valueExpr, Scope scope) {
        if (isInlineOwningStruct(declaredType)) {
            // Copying a struct that owns memory would leave two owners: the source slot gives its owned members up
            // (nulled at run time, see BytecodeEmitter.emitOwnershipMoveNullOut) and can not be read afterwards.
            Token inner = valueExpr;
            while (true) {
                if (inner.type == TokenType.DELINEATOR && inner.text.equals("(") && !inner.childs.isEmpty()) {
                    inner = inner.childs.get(0);
                } else if (inner.type == TokenType.OPERATOR && inner.unary && (inner.text.equals("mut") || inner.text.equals("imut"))
                        && inner.left != null) {
                    inner = inner.left; // `let h2 = mut h`: the value is under the mutability wrapper
                } else {
                    break;
                }
            }
            if (inner.type == TokenType.OPERATOR && "CALL".equals(inner.text) && inner.left != null
                    && inner.left.type == TokenType.VARREF && "deref".equals(inner.left.text)) {
                throw new CompilerException("type", valueExpr.file, valueExpr.line,
                        "'deref(...)' would copy a '" + declaredType.baseType + "', which owns memory -- two owners of one block. "
                                + "Use 'clone(...)', or move the owned members out one by one");
            }
            Token chain = inner;
            while (chain != null && chain.type == TokenType.OPERATOR && ".".equals(chain.text)) {
                chain = chain.left; // `d[i].h`: still a read out of an indexed element
            }
            if (chain != null && chain.type == TokenType.OPERATOR && "LOOKUP".equals(chain.text)) {
                throw new CompilerException("type", valueExpr.file, valueExpr.line,
                        "reading '" + declaredType.baseType + "' out of an array element would copy a value that owns memory -- two owners "
                                + "of one block. Use 'clone(...)' on the element, or move the owned members out one by one");
            }
            String key = slotKeyOf(inner);
            if (key != null) {
                requireNotMovedAlready(key, valueExpr, scope);
                scope.movedSlots.add(key);
                valueExpr.isOwnershipMoveSource = true;
                valueExpr.inlineOwnsStruct = declaredType.baseType;
            }
            return;
        }
        if ("owns".equals(declaredType.storage)) {
            // `a = mut b`, `take(mut b)`, `let c = mut b`: the mutability wrapper is transparent -- b is what moves.
            Token source = valueExpr;
            while (true) {
                if (source.type == TokenType.DELINEATOR && source.text.equals("(") && !source.childs.isEmpty()) {
                    source = source.childs.get(0);
                } else if (source.type == TokenType.OPERATOR && source.unary
                        && (source.text.equals("mut") || source.text.equals("imut")) && source.left != null) {
                    source = source.left;
                } else {
                    break;
                }
            }
            String key = slotKeyOf(source);
            if (key != null) {
                requireNotMovedAlready(key, valueExpr, scope);
                scope.movedSlots.add(key);
                fnEverMoved.add(key);
                valueExpr.isOwnershipMoveSource = true;
            }
        }
    }

    /** 'deref(p)' yields a copy, never a place: rejected as the target of =, compound assignment, ++/-- and swap. */
    private void rejectDerefAsWriteTarget(Token target, String what) {
        Token t = target;
        if (t != null && "CALL".equals(t.text) && t.left != null && t.left.type == TokenType.VARREF
                && "deref".equals(t.left.text)) {
            throw new CompilerException("type", t.file, t.line,
                    "'deref(...)' cannot be the target of " + what + " -- it yields a copy of the value, not "
                            + "a place to write; write through a pointer with '.' member assignment on a "
                            + "proven-alive struct pointer, or with 'memcopy' in 'unsafe' code");
        }
    }

    /** `v.f`, `v.f[i].g` ...: the variable v when `t` is a member/index path rooted at a bare variable (at least one step), else null. */
    private static String memberPathRoot(Token t) {
        Token cur = t;
        int steps = 0;
        while (cur != null && cur.type == TokenType.OPERATOR && (".".equals(cur.text) || "LOOKUP".equals(cur.text)) && cur.left != null) {
            cur = cur.left;
            steps++;
        }
        return steps > 0 && cur != null && cur.type == TokenType.VARREF ? cur.text : null;
    }

    /** The bare variable on the left of the assignment being checked: assigning to it is not a read of it. */
    private Token assignTargetVar = null;

    private TypeInfo checkAssign(Token op, Scope scope, FuncInfo func) {
        rejectDerefAsWriteTarget(op.left, "an assignment");
        if (op.left.type == TokenType.KEYWORD && op.left.text.equals("let")) {
            Token nameTok = op.left.childs.get(0);
            if (op.left.isStatic) {
                if (!op.left.staticApproved) {
                    throw new CompilerException("type", op.left.file, op.left.line,
                            "'let static' is only allowed at the top of a function body, before any "
                                    + "other statement");
                }
                checkNoRuntimeConstructs(op.right);
                // Compile-time constant: resolved in an empty Scope, so any variable reference fails as undeclared.
                TypeInfo rhsType = resolveExprType(op.right, new Scope(null), func);
                TypeInfo bound = resolveAndValidateTypeBound(op.left, rhsType, op.right);
                if (bound != null) {
                    rhsType = bound;
                } else {
                    requireStaticMutable(rhsType, op.right);
                }
                // A direct atomic declaration is legal for 'let static'; an atomic merely read from elsewhere is stripped.
                if (rhsType.isAtomic && !isDirectAtomicDeclaration(op.right)) {
                    rhsType = rhsType.withAtomic(false);
                }
                scope.declare(nameTok.text, rhsType, nameTok);
                scope.declScope().staticNames.add(nameTok.text);
                if (op.left.isConst) {
                    scope.declScope().constNames.add(nameTok.text);
                }
                scope.movedSlots.remove(nameTok.text);
            partlyMovedSlots.remove(nameTok.text);
                op.left.resolvedType = rhsType.canonical();
                return rhsType;
            }
            // A plain 'let' RHS is always an eligible struct-RVO destination; checkCall decides whether it is such a call.
            boolean previousRvoEligible = insideStructRvoDestinationPosition;
            insideStructRvoDestinationPosition = true;
            TypeInfo rhsType;
            try {
                rhsType = resolveExprType(op.right, scope, func);
            } finally {
                insideStructRvoDestinationPosition = previousRvoEligible;
            }
            TypeInfo bound = resolveAndValidateTypeBound(op.left, rhsType, op.right);
            if (bound != null) {
                rhsType = bound;
            } else {
                requireConcreteMutability(rhsType, op.right);
            }
            // A direct atomic declaration is legal only for a global or 'let static'; an expression that merely resolves to an
            // atomic value is stripped to non-atomic. See docs/TypeChecker.md, "Atomic write rules".
            if (rhsType.isAtomic) {
                if (isDirectAtomicDeclaration(op.right)) {
                    throw new CompilerException("type", op.left.file, op.left.line,
                            "an 'atomic' value can only ever be the type of a global or 'let static' "
                                    + "local, never a plain 'let'");
                }
                rhsType = rhsType.withAtomic(false);
            }
            scope.declare(nameTok.text, rhsType, nameTok);
            if ("ref".equals(rhsType.storage) && rhsType.isSome) {
                ProofKills.implicit(nameTok.text, pointeeOf(rhsType));
            }
            if (op.left.isConst) {
                scope.declScope().constNames.add(nameTok.text);
            }
            // Recorded in declaration order so this scope drops the owning local at every exit (natural end, return, break).
            if ("owns".equals(rhsType.storage) || isInlineOwningStruct(rhsType)) {
                scope.declScope().ownsDeclaredHere.add(nameTok.text);
                func.ownsLocalTypes.put(nameTok.text, rhsType);
                if (isLockedAsyncHandle(rhsType) && !synthesizingGlueBody) {
                    fnHandleDecls.putIfAbsent(nameTok.text, nameTok);
                }
            }
            // A fresh 'let' is a new binding: clear any stale moved-flag from an earlier use of the same name (e.g. a loop body redeclaring it).
            scope.movedSlots.remove(nameTok.text);
            partlyMovedSlots.remove(nameTok.text);
            op.left.resolvedType = rhsType.canonical();
            requireNoAutoContainer(rhsType, nameTok, "'" + nameTok.text + "'");
            markMovedIfOwned(rhsType, op.right, scope);
            return rhsType;
        }
        // A fresh assignment revives a moved slot; cleared before the left side is resolved so the move check does not reject it.
        String leftSlotKeyForRevival = slotKeyOf(op.left);
        if (leftSlotKeyForRevival != null) {
            scope.movedSlots.remove(leftSlotKeyForRevival);
            partlyMovedSlots.remove(leftSlotKeyForRevival);
        }
        assignTargetVar = op.left;
        TypeInfo leftType;
        try {
            leftType = resolveExprType(op.left, scope, func);
        } finally {
            assignTargetVar = null;
        }
        // A bare EnumName.Variant is always 'imut' and would not coerce to a 'mut' target; allowed as in checkInstantiate and
        // checkSwapOperator (needed by the synthesized lock-release write).
        boolean isBareEnumVariantRight = enums.containsKey(leftType.baseType)
                && isBareEnumVariantRefNode(op.right, leftType.baseType);
        // '=' on an atomic target is allowed only for a bare variable, literal or enum variant (isSwapRhsAllowedShape); compound
        // assignment and ++/-- are rejected outright. See docs/TypeChecker.md, "Atomic write rules".
        if (leftType.isAtomic && !isBareEnumVariantRight && !isSwapRhsAllowedShape(op.right)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "an 'atomic' target can only be assigned a bare variable, a literal, or an "
                            + "'EnumName.Variant' reference with '=' -- not a computed expression. Use "
                            + "'swap' instead if the new value needs to be computed");
        }
        // A struct-RVO call is eligible here only when the assignment target is a bare variable.
        boolean previousRvoEligible = insideStructRvoDestinationPosition;
        insideStructRvoDestinationPosition = op.left.type == TokenType.VARREF;
        TypeInfo rightType;
        try {
            rightType = resolveExprType(op.right, scope, func);
        } finally {
            insideStructRvoDestinationPosition = previousRvoEligible;
        }
        if (!isBareEnumVariantRight && !acceptsAdaptingLiteral(leftType, rightType, op.right)) {
            throw new CompilerException("type", op.file, op.line,
                    "cannot assign '" + rightType.canonical() + "' to '" + leftType.canonical() + "'");
        }
        requireMutable(op.left, leftType, scope, func, op);
        requireNotConst(op.left, leftType, scope, op);
        requireNotLoopImmutable(op.left, scope, op);
        if (op.left.type == TokenType.OPERATOR && op.left.text.equals("LOOKUP")) {
            requireLookupWriteAuthorized(op.left, scope);
        }
        // Revive the target again: `arr = resize(arr, ..)` moves the slot while the RHS is resolved, and this assignment supersedes that move.
        if (leftSlotKeyForRevival != null) {
            scope.movedSlots.remove(leftSlotKeyForRevival);
            partlyMovedSlots.remove(leftSlotKeyForRevival);
        }
        invalidateMatchPatternsForWrite(op.left, scope);
        if ("ref".equals(leftType.storage) && leftType.isSome && op.left.type == TokenType.VARREF) {
            ProofKills.implicit(op.left.text, pointeeOf(leftType));
        }
        if (typeOwnsMemory(leftType, new HashSet<>())) {
            ProofKills.kill(scope.activeMatchPatterns, op, "an assignment over a slot that owns memory (the old value is freed)", memberPathRoot(op.left), destroyedTypes(leftType));
        }
        if (isInlineOwningStruct(leftType)) {
            op.left.inlineOwnsStruct = leftType.baseType;
        }
        markMovedIfOwned(leftType, op.right, scope);
        if (typeOwnsMemory(leftType, new HashSet<>()) && assignsInsideSwapLockStruct(op.left, scope)) {
            requireFreshIntoSwapLock(op.right, "assigned into", String.valueOf(memberPathRoot(op.left)));
        }
        // Return the target's type (it differs from the value's when assigning 'null').
        return leftType;
    }

    /** Rejects compound assignment and ++/-- on an atomic target outright (they are read-then-write; use `swap`). See docs/TypeChecker.md, "Atomic write rules". */
    private void requireNotAtomicOrdinaryWriteTarget(TypeInfo targetType, Token at, String opText) {
        if (targetType.isAtomic) {
            throw new CompilerException("type", at.file, at.line,
                    "an 'atomic' value can't be written with '" + opText + "' -- every write to an "
                            + "already-declared atomic must go through 'swap' instead, which is the only "
                            + "primitive here that reads and writes it as one genuinely atomic step");
        }
    }

    /** Rejects assignment to a `let const` local, a const parameter or a const struct member. Struct-literal initialisation goes through checkInstantiate and never reaches here. */
    private void requireNotConst(Token target, TypeInfo targetType, Scope scope, Token at) {
        if (target.type == TokenType.VARREF && scope.isConstName(target.text)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + target.text + "' is 'const' -- it can never be assigned outside of its own "
                            + "declaration");
        }
        if (targetType.isConst) {
            String name = slotKeyOf(target);
            throw new CompilerException("type", at.file, at.line,
                    "'" + (name != null ? name : describeConditionForError(target)) + "' is 'const' -- it "
                            + "can never be assigned");
        }
    }

    /** Rejects reassigning a for/for-match loop variable or its source expression inside the loop body (`arr[i] = ..` is governed by requireLookupWriteAuthorized). */
    private void requireNotLoopImmutable(Token target, Scope scope, Token at) {
        String key = slotKeyOf(target);
        if (key != null && scope.isLoopImmutable(key)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + key + "' is immutable for the duration of this 'for'/'for match' loop -- it's "
                            + "either the loop's own loop variable or its own source expression, and "
                            + "neither can be reassigned inside the loop body");
        }
    }

    /** A 'static' initializer may contain no CALL (except sizeof) and no `new`; variable references fail by resolving in an empty Scope. */
    private void checkNoRuntimeConstructs(Token node) {
        if (node == null) {
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL")) {
            if (node.left != null && node.left.type == TokenType.VARREF && node.left.text.equals("sizeof")) {
                // sizeof(T) is a compile-time constant (a struct's size is folded by the emitter from
                // its real layout); its argument is a type, so there is nothing further to check.
                return;
            }
            throw new CompilerException("type", node.file, node.line,
                    "a 'static' initializer must be a compile-time constant -- function calls are not "
                            + "allowed");
        }
        if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("new")) {
            // `new` is a runtime allocation and can never be folded to a constant.
            throw new CompilerException("type", node.file, node.line,
                    "a 'static' initializer must be a compile-time constant -- 'new' (a runtime heap "
                            + "allocation) is not allowed");
        }
        checkNoRuntimeConstructs(node.left);
        checkNoRuntimeConstructs(node.right);
        if (node.childs != null) {
            for (Token c : node.childs) {
                checkNoRuntimeConstructs(c);
            }
        }
    }

    /**
         * Looks up a `const`, evaluating lazily and memoizing (cycle-checked via constsBeingEvaluated); returns a literal Token that
         * the VARREF case substitutes in place. See docs/TypeChecker.md, "Compile-time constants".
         */
    private Token resolveConstValue(String name, Token referencingNode) {
        Token cached = constResolvedValues.get(name);
        if (cached != null) {
            return cached;
        }
        if (constsBeingEvaluated.contains(name)) {
            throw new CompilerException("type", referencingNode.file, referencingNode.line,
                    "'const " + name + "' is circularly defined (directly or indirectly references "
                            + "its own value)");
        }
        constsBeingEvaluated.add(name);
        Token result;
        try {
            result = evaluateConstant(constExprs.get(name));
        } finally {
            constsBeingEvaluated.remove(name);
        }
        constResolvedValues.put(name, result);
        return result;
    }

    /** Evaluates a const expression (literals, other consts, operators) to a literal Token, mirroring resolveOperatorType's dispatch; anything else throws. */
    private Token evaluateConstant(Token node) {
        switch (node.type) {
            case INTEGER:
            case FLOAT:
            case BOOL:
            case CHAR:
            case STRING:
            case NULL:
                return node;
            case VARREF:
                if (!constExprs.containsKey(node.text)) {
                    throw new CompilerException("type", node.file, node.line,
                            "'" + node.text + "' is not a known const -- a compile-time constant "
                                    + "expression can only reference literals and other consts, never a "
                                    + "variable");
                }
                return resolveConstValue(node.text, node);
            case OPERATOR:
                return evaluateConstantOperator(node);
            default:
                throw new CompilerException("type", node.file, node.line,
                        "'" + node.text + "' cannot appear in a compile-time constant expression");
        }
    }

    /** Folds a template literal into a plain STRING token in place; each `${..}` is evaluated by evaluateConstant. See docs/TypeChecker.md, "Compile-time constants". */
    private TypeInfo resolveTemplateString(Token node) {
        StringBuilder sb = new StringBuilder();
        for (Token seg : node.childs) {
            if (seg.type == TokenType.OPERATOR && seg.text.equals("TEMPLATE_STRING_PART")) {
                sb.append(seg.literalValue);
                continue;
            }
            // The synthesized '(' group wraps one `${..}` segment (the Lexer emits '(' rather than '{').
            if (seg.childs.isEmpty()) {
                throw new CompilerException("type", seg.file, seg.line, "empty '${}' in template literal");
            }
            Token value = evaluateConstant(seg.childs.get(0));
            sb.append(stringifyTemplateSegmentValue(value));
        }
        node.type = TokenType.STRING;
        node.text = sb.toString();
        node.literalValue = sb.toString();
        node.quoteDelimiter = '`';
        node.childs = new ArrayList<>();
        return new TypeInfo("static", "imut", "string").withSome(true);
    }

    /** Stringifies one evaluated `${..}` segment; floats are rejected because no formatting convention is settled. */
    private String stringifyTemplateSegmentValue(Token value) {
        switch (value.type) {
            case STRING:
                return value.literalValue;
            case INTEGER:
                return value.text;
            case CHAR:
                return value.literalValue;
            case BOOL:
                return value.text;
            case FLOAT:
                throw new CompilerException("type", value.file, value.line,
                        "a 'float' value cannot appear in a template literal's '${...}' segment yet -- "
                                + "no formatting convention has been settled on; pass an already-"
                                + "stringified value instead");
            default:
                throw new CompilerException("type", value.file, value.line,
                        "'" + value.text + "' cannot appear in a template literal's '${...}' segment");
        }
    }

    private Token evaluateConstantOperator(Token op) {
        if (op.text.equals("CALL")) {
            throw new CompilerException("type", op.file, op.line,
                    "a compile-time constant expression cannot call a function");
        }
        if (op.text.equals("mut") || op.text.equals("imut")) {
            // Mutability never affects a literal's own value -- pass
            // straight through to whatever's wrapped.
            return evaluateConstant(op.left);
        }
        if (op.text.equals("+") || op.text.equals("-") || op.text.equals("*") || op.text.equals("/")
                || op.text.equals("%")) {
            if (op.unary) {
                return evaluateConstantUnaryMinus(op);
            }
            return evaluateConstantArithmetic(op);
        }
        if (op.text.equals("!")) {
            Token operand = evaluateConstant(op.left);
            requireConstBool(operand, op);
            return new Token(TokenType.BOOL, String.valueOf(!Boolean.parseBoolean(operand.text)),
                    op.line, op.file);
        }
        if (op.text.equals("&&") || op.text.equals("||")) {
            Token left = evaluateConstant(op.left);
            Token right = evaluateConstant(op.right);
            requireConstBool(left, op);
            requireConstBool(right, op);
            boolean l = Boolean.parseBoolean(left.text);
            boolean r = Boolean.parseBoolean(right.text);
            boolean result = op.text.equals("&&") ? (l && r) : (l || r);
            return new Token(TokenType.BOOL, String.valueOf(result), op.line, op.file);
        }
        if (op.text.equals("<") || op.text.equals(">") || op.text.equals("<=") || op.text.equals(">=")
                || op.text.equals("==") || op.text.equals("!=")) {
            return evaluateConstantComparison(op);
        }
        throw new CompilerException("type", op.file, op.line,
                "'" + op.text + "' cannot appear in a compile-time constant expression");
    }

    private void requireConstBool(Token t, Token op) {
        if (t.type != TokenType.BOOL) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' requires a 'bool' operand in a compile-time constant expression, "
                            + "got '" + t.type + "'");
        }
    }

    private boolean isConstNumeric(Token t) {
        return t.type == TokenType.INTEGER || t.type == TokenType.FLOAT;
    }

    private Token evaluateConstantUnaryMinus(Token op) {
        Token operand = evaluateConstant(op.left);
        if (!isConstNumeric(operand)) {
            throw new CompilerException("type", op.file, op.line,
                    "unary '-' requires a numeric operand in a compile-time constant expression, got '"
                            + operand.type + "'");
        }
        if (operand.type == TokenType.FLOAT) {
            return new Token(TokenType.FLOAT, formatConstFloat(-Double.parseDouble(operand.text)),
                    op.line, op.file);
        }
        return new Token(TokenType.INTEGER, Long.toString(-Long.parseLong(operand.text)), op.line, op.file);
    }

    private Token evaluateConstantArithmetic(Token op) {
        Token leftTok = evaluateConstant(op.left);
        Token rightTok = evaluateConstant(op.right);
        if (!isConstNumeric(leftTok) || !isConstNumeric(rightTok)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' requires numeric operands in a compile-time constant expression, "
                            + "got '" + leftTok.type + "' and '" + rightTok.type + "'");
        }
        if (leftTok.type == TokenType.FLOAT || rightTok.type == TokenType.FLOAT) {
            double l = Double.parseDouble(leftTok.text);
            double r = Double.parseDouble(rightTok.text);
            double result;
            switch (op.text) {
                case "+": result = l + r; break;
                case "-": result = l - r; break;
                case "*": result = l * r; break;
                case "/": result = l / r; break;
                case "%": result = l % r; break;
                default: throw new CompilerException("type", op.file, op.line,
                        "internal error: unhandled constant arithmetic operator '" + op.text + "'");
            }
            return new Token(TokenType.FLOAT, formatConstFloat(result), op.line, op.file);
        }
        long l = Long.parseLong(leftTok.text);
        long r = Long.parseLong(rightTok.text);
        if ((op.text.equals("/") || op.text.equals("%")) && r == 0) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' by a literal '0' always fails, even in a compile-time constant "
                            + "expression -- this isn't a matter of needing a proof or 'unsafe', the "
                            + "divisor is simply wrong");
        }
        long result;
        switch (op.text) {
            case "+": result = l + r; break;
            case "-": result = l - r; break;
            case "*": result = l * r; break;
            case "/": result = l / r; break;
            case "%": result = l % r; break;
            default: throw new CompilerException("type", op.file, op.line,
                    "internal error: unhandled constant arithmetic operator '" + op.text + "'");
        }
        return new Token(TokenType.INTEGER, Long.toString(result), op.line, op.file);
    }

    private Token evaluateConstantComparison(Token op) {
        Token leftTok = evaluateConstant(op.left);
        Token rightTok = evaluateConstant(op.right);
        boolean result;
        if (isConstNumeric(leftTok) && isConstNumeric(rightTok)) {
            double l = Double.parseDouble(leftTok.text);
            double r = Double.parseDouble(rightTok.text);
            switch (op.text) {
                case "<": result = l < r; break;
                case ">": result = l > r; break;
                case "<=": result = l <= r; break;
                case ">=": result = l >= r; break;
                case "==": result = l == r; break;
                case "!=": result = l != r; break;
                default: throw new CompilerException("type", op.file, op.line,
                        "internal error: unhandled constant comparison operator '" + op.text + "'");
            }
        } else if (leftTok.type == rightTok.type
                && (op.text.equals("==") || op.text.equals("!="))) {
            // Non-numeric equality compares values (literalValue holds the escape-processed form for char/string).
            String l = leftTok.literalValue != null ? leftTok.literalValue : leftTok.text;
            String r = rightTok.literalValue != null ? rightTok.literalValue : rightTok.text;
            boolean eq = l.equals(r);
            result = op.text.equals("==") ? eq : !eq;
        } else {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' can't compare '" + leftTok.type + "' and '" + rightTok.type
                            + "' in a compile-time constant expression");
        }
        return new Token(TokenType.BOOL, String.valueOf(result), op.line, op.file);
    }

    /** Matches this language's own float-literal text convention (always a decimal point present) without ever emitting redundant trailing zeros/scientific notation Double.toString() would otherwise produce. */
    private String formatConstFloat(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.format("%.1f", value);
        }
        return Double.toString(value);
    }

    /** A static/global scalar needs a concrete mutability ('mut' or 'imut'); a struct is already governed by its member declarations. */
    private void requireStaticMutable(TypeInfo type, Token at) {
        if (structs.containsKey(type.baseType)) {
            return;
        }
        if (!"mut".equals(type.mutability) && !"imut".equals(type.mutability)) {
            throw new CompilerException("type", at.file, at.line,
                    "a 'static' variable must have a concrete mutability, got '" + type.canonical()
                            + "' -- write 'mut' or 'imut' before the value");
        }
    }

    /** A variable only accepts a value with concrete mutability; indeterminate values (literals, arithmetic results) need a `mut`/`imut` prefix. */
    private void requireConcreteMutability(TypeInfo type, Token at) {
        if (!"mut".equals(type.mutability) && !"imut".equals(type.mutability)) {
            throw new CompilerException("type", at.file, at.line,
                    "this value's mutability is indeterminate -- write 'mut' or 'imut' before it to "
                            + "store it in a variable");
        }
    }

    /**
         * Checks `let:<FullType> name = expr`: the RHS must match the bound exactly, be 'null' for a pointer bound, or implement an
         * interface bound; no other implicit casting. Returns null when no bound is written. See docs/TypeChecker.md, "Let type bounds".
         */
    private TypeInfo resolveAndValidateTypeBound(Token letTok, TypeInfo rhsType, Token rhsExpr) {
        if (letTok.typeBound == null) {
            return null;
        }
        TypeInfo bound = resolveTypeAnnotationRaw(letTok.typeBound, letTok);
        TypeInfo result;
        if (rhsType.baseType.equals("null")) {
            // Only pointers (owns/ref/raw, not auto/static) can be null.
            if (!isPointerType(bound)) {
                throw new CompilerException("type", rhsExpr.file, rhsExpr.line,
                        "'null' can only satisfy a pointer-typed bound ('owns'/'ref'/'raw'), got '"
                                + bound.canonical() + "'");
            }
            if (bound.isSome) {
                // A 'null' RHS is checked against a 'some' bound by hand here; this path does not go through typesCompatible.
                throw new CompilerException("type", rhsExpr.file, rhsExpr.line,
                        "'null' cannot satisfy a 'some'-tagged bound ('" + bound.canonical()
                                + "') -- a 'some' type guarantees a real value is present");
            }
            result = bound;
        } else {
            requireConcreteMutability(rhsType, rhsExpr);
            boolean sameOrAncestor = rhsType.baseType.equals(bound.baseType)
                    || (interfaces.containsKey(bound.baseType) && structs.containsKey(rhsType.baseType)
                            && structImplementsInterface(rhsType.baseType, bound.baseType));
            boolean literalOk = literalAdapts(bound, rhsType);
            sameOrAncestor = sameOrAncestor || literalOk;
            boolean storageOk = (bound.storage == null && rhsType.storage == null)
                    || (bound.storage != null && rhsType.storage != null
                            && storageCompatible(bound.storage, rhsType.storage));
            if (!sameOrAncestor || !storageOk || !mutabilityCompatible(bound.mutability, rhsType.mutability)) {
                throw new CompilerException("type", rhsExpr.file, rhsExpr.line,
                        "the initializer's type '" + rhsType.canonical() + "' doesn't satisfy the "
                                + "declared bound '" + bound.canonical() + "'");
            }
            if (literalOk) {
                adaptLiteral(rhsExpr, bound);
            }
            result = bound;
        }
        // The declared type is the bound, so the 'let static' mutability rule applies to it.
        if (letTok.isStatic) {
            requireStaticMutable(result, rhsExpr);
        }
        return result;
    }

    private TypeInfo checkCompoundAssign(Token op, Scope scope, FuncInfo func) {
        rejectDerefAsWriteTarget(op.left, "a compound assignment");
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        requireNotAtomicOrdinaryWriteTarget(leftType, op, op.text);
        TypeInfo rightType = resolveExprType(op.right, scope, func);
        // C-style: "p += n" / "p -= n" on a raw pointer moves by n elements (n * pointee size bytes).
        boolean pointerCompound = isRawPointerType(leftType) && (op.text.equals("+=") || op.text.equals("-="))
                && !(pointeeSize(leftType, op) == 1 && leftType.sameBaseType(rightType));
        if (pointerCompound) {
            requireEightByteInteger(rightType, op);
            long scale = pointeeSize(leftType, op);
            op.pointerScale = scale > 1 ? scale : 0;
            op.pointerScaleStruct = op.pointerScale > 0 ? pointeeStructName(leftType) : null;
        } else {
            requireNumeric(leftType, op);
            requireNumeric(rightType, op);
            rightType = unifyLiteralOperands(op, leftType, rightType)[1];
            if (!leftType.sameBaseType(rightType)) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + op.text + "' requires both sides to be the same numeric type, got '"
                                + leftType.canonical() + "' and '" + rightType.canonical() + "'");
            }
        }
        if (op.text.equals("/=") || op.text.equals("%=")) {
            requireProvenNonzeroDivisor(op.right, rightType, scope, op);
        }
        requireMutable(op.left, leftType, scope, func, op);
        requireNotLoopImmutable(op.left, scope, op);
        if (op.left.type == TokenType.OPERATOR && op.left.text.equals("LOOKUP")) {
            requireLookupWriteAuthorized(op.left, scope);
        }
        invalidateMatchPatternsForWrite(op.left, scope);
        return leftType;
    }

    private TypeInfo checkArithmetic(Token op, Scope scope, FuncInfo func) {
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        TypeInfo rightType = resolveExprType(op.right, scope, func);
        // C-style pointer arithmetic on 'raw' pointers: pointer +/- integer and
        // integer + pointer give a pointer moved by n elements; pointer - pointer
        // gives the signed element count between them. Any other operator on a
        // pointer keeps its previous (plain numeric) meaning.
        boolean leftPtr = isRawPointerType(leftType);
        boolean rightPtr = isRawPointerType(rightType);
        if ((leftPtr || rightPtr) && (op.text.equals("+") || op.text.equals("-"))) {
            if (leftPtr && rightPtr) {
                if (!op.text.equals("-")) {
                    throw new CompilerException("type", op.file, op.line, "cannot add two 'raw' pointers");
                }
                if (!leftType.baseType.equals(rightType.baseType)) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot subtract 'raw' pointers to different types ('" + leftType.canonical()
                                    + "' and '" + rightType.canonical() + "')");
                }
                op.pointerArithKind = 3;
                op.pointerScale = pointeeSize(leftType, op);
                op.pointerScaleStruct = pointeeStructName(leftType);
                return new TypeInfo(null, "indeterminate", "s64");
            }
            if (leftPtr) {
                requireEightByteInteger(rightType, op);
                op.pointerArithKind = 1;
                op.pointerScale = pointeeSize(leftType, op);
                op.pointerScaleStruct = pointeeStructName(leftType);
                return pointerResultType(leftType);
            }
            if (op.text.equals("-")) {
                throw new CompilerException("type", op.file, op.line, "cannot subtract a 'raw' pointer from an integer");
            }
            requireEightByteInteger(leftType, op);
            op.pointerArithKind = 2;
            op.pointerScale = pointeeSize(rightType, op);
            op.pointerScaleStruct = pointeeStructName(rightType);
            return pointerResultType(rightType);
        }
        requireNumeric(leftType, op);
        requireNumeric(rightType, op);
        // arithmetic between two float literals is itself a float literal (it adapts, as a whole, to f32 or f64)
        boolean bothFloatLiterals = leftType.floatLiteral && rightType.floatLiteral;
        TypeInfo[] unified = unifyLiteralOperands(op, leftType, rightType);
        leftType = unified[0];
        rightType = unified[1];
        if (!leftType.sameBaseType(rightType)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' requires both sides to be the same numeric type, got '"
                            + leftType.canonical() + "' and '" + rightType.canonical() + "'");
        }
        requireProvenFloatState(op.left, leftType, scope, op);
        requireProvenFloatState(op.right, rightType, scope, op);
        if (op.text.equals("/") || op.text.equals("%")) {
            requireProvenNonzeroDivisor(op.right, rightType, scope, op);
        }
        TypeInfo arith = new TypeInfo(null, "indeterminate", leftType.baseType);
        if (bothFloatLiterals) {
            arith.floatLiteral = true;
        }
        return arith;
    }

    /**
         * Integer '/' and '%' need a literal nonzero divisor, a live nonzero match proof, or `assume match`; a literal 0 is always an error.
         * Floats are exempt (IEEE gives inf/NaN, no trap). See docs/TypeChecker.md, "Division proofs".
         */
    private void requireProvenNonzeroDivisor(Token divisorExpr, TypeInfo divisorType, Scope scope, Token op) {
        if (!INTEGER_TYPES.contains(divisorType.baseType)) {
            return;
        }
        if (divisorExpr.type == TokenType.INTEGER) {
            if (divisorExpr.text.equals("0")) {
                throw new CompilerException("type", divisorExpr.file, divisorExpr.line,
                        "'" + op.text + "' by a literal '0' always fails -- this isn't a matter of "
                                + "needing a proof or 'unsafe', the divisor is simply wrong");
            }
            return;
        }
        if (!isProvenNonzero(divisorExpr, scope)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' requires the divisor to be proven nonzero first (`match "
                            + divisorExpr.text + " != 0{...}` for an unsigned type, or `match "
                            + divisorExpr.text + " > 0{...}` for a signed one -- `!=0` alone "
                            + "isn't enough for a signed divisor, since it doesn't rule out the type's own "
                            + "minimum value divided by -1, which overflows), or vouched for with 'assume match' in an 'unsafe assume' block [needs: assume match " + exprHint(divisorExpr) + " != 0]");
        }
    }

    private TypeInfo checkUnaryMinus(Token op, Scope scope, FuncInfo func) {
        TypeInfo operand = resolveExprType(op.left, scope, func);
        requireNumeric(operand, op);
        requireProvenFloatState(op.left, operand, scope, op);
        String resultBase = UNSIGNED_TO_SIGNED.getOrDefault(operand.baseType, operand.baseType);
        TypeInfo negated = new TypeInfo(null, "indeterminate", resultBase);
        if (operand.literalValue != null && operand.baseType.equals("u64")) {
            // "-5" is still a literal (see TypeInfo.literalValue): its value is -5, its default
            // type s64, and it may adapt to any signed type that holds it.
            negated.literalValue = operand.literalValue.negate();
        }
        if (operand.floatLiteral) {
            negated.floatLiteral = true; // "-0.5" is still a float literal that adapts to f32 or f64
        }
        return negated;
    }

    private TypeInfo checkLogicalNot(Token op, Scope scope, FuncInfo func) {
        TypeInfo operand = resolveExprType(op.left, scope, func);
        requireBoolOrInteger(operand, op);
        return new TypeInfo(null, "indeterminate", "bool");
    }

    /** Shared by both "++" and "--" -- identical rules either way (numeric, mutable, not the loop's own immutable slot); only the emitted mnemonic differs, decided later by BytecodeEmitter from op.text alone. */
    private TypeInfo checkIncrement(Token op, Scope scope, FuncInfo func) {
        rejectDerefAsWriteTarget(op.left, "'++'/'--'");
        TypeInfo operand = resolveExprType(op.left, scope, func);
        // C-style: "p++" on a raw pointer advances by the pointee size. A
        // one-byte pointee keeps the ordinary path below, byte-identical to before.
        if (isRawPointerType(operand)) {
            long scale = pointeeSize(operand, op);
            if (scale > 1) {
                requireNotAtomicOrdinaryWriteTarget(operand, op, op.text);
                requireMutable(op.left, operand, scope, func, op);
                requireNotLoopImmutable(op.left, scope, op);
                invalidateMatchPatternsForWrite(op.left, scope);
                op.pointerScale = scale;
                op.pointerScaleStruct = pointeeStructName(operand);
                return operand;
            }
        }
        requireNotAtomicOrdinaryWriteTarget(operand, op, op.text);
        requireNumeric(operand, op);
        requireMutable(op.left, operand, scope, func, op);
        requireNotLoopImmutable(op.left, scope, op);
        invalidateMatchPatternsForWrite(op.left, scope);
        return new TypeInfo(null, "indeterminate", operand.baseType);
    }

    /** Modifying a value needs 'mut' on the target and on every prefix of a dotted/indexed path. */
    private void requireMutable(Token targetExpr, TypeInfo targetType, Scope scope, FuncInfo func, Token at) {
        if (!"mut".equals(targetType.mutability)) {
            throw new CompilerException("type", at.file, at.line,
                    "cannot modify this value -- its mutability is '"
                            + (targetType.mutability == null ? "indeterminate" : targetType.mutability)
                            + "', not 'mut'");
        }
        // '@pure' functions may not mutate through any pointer anywhere in the chain; unsafe gives no exemption.
        if (isPureFunc(func) && targetType.storage != null) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + func.name + "' is '@pure' and cannot mutate through a pointer -- '"
                            + targetType.canonical() + "' has 'raw'/'owns'/'ref'/'auto'/'static' storage");
        }
        if (targetExpr.type == TokenType.OPERATOR && targetExpr.text.equals(".")) {
            TypeInfo prefixType = resolveExprType(targetExpr.left, scope, func);
            requireMutable(targetExpr.left, prefixType, scope, func, at);
        } else if (targetExpr.type == TokenType.OPERATOR && targetExpr.text.equals("LOOKUP")) {
            // LOOKUP is walked for the '@pure' pointer check, recursing so `p.arr[0]` reaches `p`.
            TypeInfo prefixType = resolveExprType(targetExpr.left, scope, func);
            requireMutable(targetExpr.left, prefixType, scope, func, at);
        }
    }

    /**
         * `&&`/`||` (eager) and `&&then`/`||then` (lazy) share this check; the then-variants are legal only at the top of an
         * if/elseif condition (eligibleHere). See docs/TypeChecker.md, "Operator dispatch context flags".
         */
    private TypeInfo checkLogical(Token op, Scope scope, FuncInfo func, boolean eligibleHere) {
        boolean isThenVariant = op.text.equals("&&then") || op.text.equals("||then");
        if (isThenVariant && !eligibleHere) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' is only legal at the top level of an 'if'/'elseif' condition -- as "
                            + "that condition's own root operator, or nested only under further "
                            + "'&&then'/'||then' operators reached from it -- not used as a function argument, "
                            + "inside a '!' or comparison, as an operand of a plain '&&'/'||', inside a 'match', "
                            + "or anywhere else an ordinary expression is expected");
        }
        // Only a then-variant re-arms eligibility for its operands; a plain &&/|| leaves it consumed.
        if (isThenVariant) {
            insideShortCircuitEligiblePosition = true;
        }
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        insideShortCircuitEligiblePosition = false;
        if (isThenVariant) {
            insideShortCircuitEligiblePosition = true;
        }
        TypeInfo rightType = resolveExprType(op.right, scope, func);
        insideShortCircuitEligiblePosition = false;
        requireBoolOrInteger(leftType, op);
        requireBoolOrInteger(rightType, op);
        return new TypeInfo(null, "indeterminate", "bool");
    }

    private TypeInfo checkComparison(Token op, Scope scope, FuncInfo func) {
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        TypeInfo rightType = resolveExprType(op.right, scope, func);
        TypeInfo[] unified = unifyLiteralOperands(op, leftType, rightType);
        leftType = unified[0];
        rightType = unified[1];
        boolean sameType = leftType.sameBaseType(rightType);
        // '==' and '!=' also allow comparing a pointer with 'null' (either side); ordering comparisons do not.
        boolean nullVsPointer = (op.text.equals("==") || op.text.equals("!="))
                && ((rightType.baseType.equals("null") && isPointerType(leftType))
                    || (leftType.baseType.equals("null") && isPointerType(rightType)));
        if (!sameType && !nullVsPointer) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + op.text + "' requires both sides to be the same type, got '"
                            + leftType.canonical() + "' and '" + rightType.canonical() + "'");
        }
        requireProvenFloatState(op.left, leftType, scope, op);
        requireProvenFloatState(op.right, rightType, scope, op);
        if (op.text.equals("==") || op.text.equals("!=")) {
            normalizeRefComparison(op, leftType, rightType);
        }
        return new TypeInfo(null, "indeterminate", "bool");
    }

    /**
     * A nullable `ref` is an id and every other pointer an address, so `==`/`!=` between the two kinds first converts one side: an address
     * compared with a nullable `ref` becomes an id (GT_REF_ID, equal objects have equal ids); a `raw` pointer compared with one makes the id
     * an address (GT_REF_RESOLVE). A `null` literal is 0 in both forms and needs nothing.
     */
    private void normalizeRefComparison(Token op, TypeInfo leftType, TypeInfo rightType) {
        boolean leftId = "ref".equals(leftType.storage) && !leftType.isSome;
        boolean rightId = "ref".equals(rightType.storage) && !rightType.isSome;
        if (leftId == rightId || leftType.baseType.equals("null") || rightType.baseType.equals("null")) {
            return;
        }
        Token idSide = leftId ? op.left : op.right;
        Token addrSide = leftId ? op.right : op.left;
        TypeInfo addrType = leftId ? rightType : leftType;
        if (addrType.storage == null) {
            return;
        }
        if ("raw".equals(addrType.storage)) {
            idSide.refToRaw = true;
        } else {
            addrSide.refToId = true;
        }
    }

    private TypeInfo checkCall(Token op, Scope scope, FuncInfo func, boolean eligibleForRvoHere) {
        if (op.left.type == TokenType.KEYWORD && op.left.text.equals("sleep")) {
            // 'sleep' is a Lexer keyword, so it resolves to whichever function carries '@sleep'.
            return checkSleepCall(op, scope, func);
        }
        if (op.left.type == TokenType.OPERATOR && op.left.text.equals(".")) {
            return checkMethodCall(op, scope, func, eligibleForRvoHere);
        }
        if (op.left.type != TokenType.VARREF) {
            throw new CompilerException("type", op.file, op.line,
                    "calling through a function-pointer expression is not yet supported by the type checker");
        }
        String funcName = op.left.text;
        boolean wasEventLoopRedirect = false;
        if (funcName.equals("tick") && func != null && hasDecorator(func.funcToken.decorators, "event_loop")) {
            // Inside '@event_loop', a literal `tick(..)` redirects to the function decorated '@tick' (neither has a fixed name).
            List<FuncInfo> tickFuncs = findFuncsWithDecorator("tick");
            if (!tickFuncs.isEmpty()) {
                funcName = tickFuncs.get(0).name;
                wasEventLoopRedirect = true;
                // The stdlib loop writes `? tick(state)`; the wrap is kept only if tick is '@throws', so the two-way rule still holds.
                if (!tickFuncs.get(0).isThrows) {
                    op.insideTry = false;
                }
            }
        }
        if (BUILTIN_NAMES.contains(funcName)) {
            return checkBuiltinCall(funcName, op, scope, func);
        }
        ExternInfo externInfo = externs.get(funcName);
        if (externInfo != null) {
            return checkExternCall(externInfo, op, scope, func);
        }
        List<FuncInfo> overloads = functions.get(funcName);
        boolean isConstructorCallShape = false;
        if (overloads == null && structs.containsKey(funcName)) {
            // `MyClass(..)` resolves against the struct's constructors (CONSTRUCTOR_NAME_PREFIX + name) via the normal overload loop.
            overloads = functions.get(Parser.CONSTRUCTOR_NAME_PREFIX + funcName);
            isConstructorCallShape = true;
        }
        if (overloads == null) {
            throw new CompilerException("type", op.file, op.line,
                    isConstructorCallShape
                            ? "'" + funcName + "' declares no constructor at all"
                            : "call to undeclared function '" + funcName + "'");
        }

        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        List<TypeInfo> argTypes = new ArrayList<>();
        for (Token argNode : argNodes) {
            argTypes.add(resolveExprType(argNode, scope, func));
        }

        for (FuncInfo candidate : orderByLiteralExactness(overloads, argTypes)) {
            if (candidate.paramTypes.size() != argTypes.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < argTypes.size(); i++) {
                if (!argSatisfiesParam(candidate.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                retagLiteralArgs(candidate.paramTypes, argTypes, argNodes);
                if (hasDecorator(candidate.funcToken.decorators, "event_loop")) {
                    // '@event_loop' cannot be called or referenced.
                    throw new CompilerException("type", op.file, op.line,
                            "'" + funcName + "' is '@event_loop' -- it cannot be called or referenced "
                                    + "anywhere in the compilation unit");
                }
                boolean callerIsEventLoop = func != null && hasDecorator(func.funcToken.decorators, "event_loop");
                boolean mainIsWithTick = functions.containsKey("main")
                        && !functions.get("main").isEmpty()
                        && hasDecorator(functions.get("main").get(0).funcToken.decorators, "with_tick");
                boolean isMainOrTick = candidate.name.equals("main")
                        || hasDecorator(candidate.funcToken.decorators, "tick");
                if (mainIsWithTick && isMainOrTick && !callerIsEventLoop) {
                    // With a '@with_tick' main, only the '@event_loop' function may call main or the '@tick' function. Read from `functions`,
                    // because eventLoopFunc is not set until validateTickEventLoop runs at the end of check().
                    throw new CompilerException("type", op.file, op.line,
                            "'" + funcName + "' can only be called from within the '@event_loop' "
                                    + "function");
                }
                boolean privilegedEventLoopCall = wasEventLoopRedirect
                        || (funcName.equals("main") && callerIsEventLoop);
                if (!candidate.isPublic && !candidate.funcToken.file.equals(op.file)
                        && !privilegedEventLoopCall) {
                    // Private function: same file only; main and the '@tick' function are exempt when called from the '@event_loop' body.
                    throw new CompilerException("type", op.file, op.line,
                            "'" + funcName + "' is private -- not accessible outside the file it's "
                                    + "declared in");
                }
                if (candidate.isAsync && op.asyncCallKind == null && !op.isTrampolineOwnCall) {
                    throw new CompilerException("type", op.file, op.line,
                            "'" + funcName + "' is '@async' and can only be called through 'par' or "
                                    + "'await' -- never plainly");
                }
                if (!candidate.isAsync && op.asyncCallKind != null) {
                    throw new CompilerException("type", op.file, op.line,
                            "'" + op.asyncCallKind.toLowerCase() + "' can only be used on an '@async' "
                                    + "function -- '" + funcName + "' isn't one");
                }
                if (op.asyncCallKind != null) {
                    // Pointers (even nested in structs) cannot cross par/await without 'unsafe async'.
                    for (int i = 0; i < argNodes.size(); i++) {
                        if (containsPointerAnywhere(candidate.paramTypes.get(i)) && !unsafeBypass(scope, "async")) {
                            throw new CompilerException("type", argNodes.get(i).file, argNodes.get(i).line,
                                    "passing '" + candidate.paramTypes.get(i).canonical() + "' (a pointer, "
                                            + "or a struct with one nested somewhere inside it) across "
                                            + "'" + op.asyncCallKind.toLowerCase() + "' requires 'unsafe' "
                                            + "code");
                        }
                    }
                }
                op.resolvedCallTarget = candidate.mangledName;
                op.resolvedCallConvention = candidate.callConvention;
                requireThrowsWrapping(candidate, op);
                for (int i = 0; i < argNodes.size(); i++) {
                    markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
                }
                // Per-call-site destruct list, used if the callee throws and unwinds back here (emitted only when the program uses throw).
                op.destructOnExit = collectOwnsToDestruct(scope, scope.functionRootScope, null);
                op.unlockOnExit = collectLockReleasesToBoundary(scope, scope.functionRootScope);
                if (candidate == func && scope.suspendsSelfRecursion) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot call '" + func.name + "' from within its own base-case match "
                                    + "block -- the func loses its recursive privileges there");
                }
                if (candidate == func && getDecorator(func.funcToken.decorators, "recursive") != null) {
                    requireRecursiveCallReturnSiteOnly(func, op);
                    requireRecursiveCallWithinProof(func, op, argNodes, argTypes, scope);
                }
                requirePureCalleeIfPure(func, candidate, op);
                callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
                // Struct-by-value return: legal only at a 'let' RHS or a bare-variable assignment RHS (insideStructRvoDestinationPosition).
                // See docs/TypeChecker.md, "Struct return values (RVO)".
                if (isStructByValueReturn(candidate.returnType)) {
                    if (!eligibleForRvoHere) {
                        throw new CompilerException("type", op.file, op.line,
                                "a call to '" + funcName + "' (which returns '"
                                        + candidate.returnType.baseType + "' by value) may only be used as a "
                                        + "'let' binding's own right-hand side, or as a plain assignment's "
                                        + "right-hand side with a bare-variable target -- not as a function "
                                        + "argument, inside a larger expression, as a 'return' statement's own "
                                        + "expression, or anywhere else a value is otherwise expected; bind it "
                                        + "to a 'let' first");
                    }
                    op.isStructRvoCall = true;
                }
                return candidate.returnType;
            }
        }
        throw new CompilerException("type", op.file, op.line,
                "no overload of '" + funcName + "' matches argument types (" + describeTypes(argTypes) + ")"
                        + unavailableNote(isConstructorCallShape ? Parser.CONSTRUCTOR_NAME_PREFIX + funcName : funcName));
    }

    /** Calling an extern needs `unsafe extern` and is banned in '@pure'. Fixed parameters are checked like an ordinary call; varargs only need a concrete (non-indeterminate) type. */
    private TypeInfo checkExternCall(ExternInfo info, Token op, Scope scope, FuncInfo func) {
        if (!unsafeBypass(scope, "extern")) {
            throw new CompilerException("type", op.file, op.line,
                    "calling extern '" + info.name + "' requires 'unsafe' code");
        }
        if (isPureFunc(func)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + func.name + "' is '@pure' and cannot call extern '" + info.name
                            + "' -- an extern's behavior can never be verified at compile time");
        }

        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        int fixedCount = info.paramTypes.size();
        if (info.hasVarargs ? argNodes.size() < fixedCount : argNodes.size() != fixedCount) {
            throw new CompilerException("type", op.file, op.line,
                    "call to extern '" + info.name + "' requires " + (info.hasVarargs ? "at least " : "exactly ")
                            + fixedCount + " argument(s), got " + argNodes.size());
        }

        for (int i = 0; i < argNodes.size(); i++) {
            Token argNode = argNodes.get(i);
            TypeInfo argType = resolveExprType(argNode, scope, func);
            if (i < fixedCount) {
                TypeInfo expected = info.paramTypes.get(i);
                if (!acceptsAdaptingLiteral(expected, argType, argNode)) {
                    throw new CompilerException("type", argNode.file, argNode.line,
                            "argument " + (i + 1) + " to extern '" + info.name + "' expects '"
                                    + expected.canonical() + "', got '" + argType.canonical() + "'");
                }
                markMovedIfOwned(expected, argNode, scope);
            }
            // Varargs past the fixed parameters: C ignores mutability, so an
            // indeterminate-mutability value (literal, expression) is accepted as is.
        }
        op.resolvedCallTarget = info.linkName; // the real linked symbol -- info.name unless "@link_name" overrides it (see ExternInfo.linkName's own doc comment)
        op.resolvedCallConvention = info.callConvention;
        return info.returnType;
    }

    /** `sleep(...)`: always resolves to the function carrying `@sleep` (ghost-table function registry); fixed arity and types, no overloads. See docs/TypeChecker.md, "Builtin calls". */
    private TypeInfo checkSleepCall(Token op, Scope scope, FuncInfo func) {
        FuncInfo sleepFunc = getGhostTableFunction("sleep");
        if (sleepFunc == null) {
            throw new CompilerException("type", op.file, op.line,
                    "using 'sleep' requires a function decorated '@sleep' to exist somewhere in the "
                            + "compilation unit (none was found)");
        }
        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        int expectedCount = sleepFunc.paramTypes.size();
        if (argNodes.size() != expectedCount) {
            throw new CompilerException("type", op.file, op.line,
                    "'sleep' requires exactly " + expectedCount + " argument(s) (matching the '@sleep'"
                            + "-decorated function's own signature), got " + argNodes.size());
        }
        for (int i = 0; i < argNodes.size(); i++) {
            Token argNode = argNodes.get(i);
            TypeInfo argType = resolveExprType(argNode, scope, func);
            TypeInfo expected = sleepFunc.paramTypes.get(i);
            if (!acceptsAdaptingLiteral(expected, argType, argNode)) {
                throw new CompilerException("type", argNode.file, argNode.line,
                        "argument " + (i + 1) + " to 'sleep' expects '" + expected.canonical() + "', got '"
                                + argType.canonical() + "'");
            }
            markMovedIfOwned(expected, argNode, scope);
        }
        op.resolvedCallTarget = sleepFunc.mangledName;
        op.resolvedCallConvention = sleepFunc.callConvention;
        // Emitted as SLEEP, not CALL; BytecodeEmitter.emitCallOrBuiltin reads the flag.
        op.isSleepCall = true;
        return sleepFunc.returnType;
    }

    /** Dispatches a reserved builtin name to its checker; builtins never use the user-function overload table. */
    private TypeInfo checkBuiltinCall(String name, Token op, Scope scope, FuncInfo func) {
        switch (name) {
            case "sizeof": return checkSizeofBuiltin(op, scope, func);
            case "len": return checkLenBuiltin(op, scope, func);
            case "range": return checkRangeBuiltin(op, scope, func);
            case "deref": return checkDerefBuiltin(op, scope, func);
            case "clone": return checkCloneBuiltin(op, scope, func);
            case "call": return checkCallBuiltin(op, scope, func);
            case "bits_left": case "bits_right": case "bits_rotl": case "bits_rotr": case "bits_or": case "bits_and": case "bits_xor": case "bits_not":
                return checkBitsBuiltin(name, op, scope, func);
            case "memcopy": return checkMemcopyBuiltin(op, scope, func);
            case "dyn": return checkDynBuiltin(op, scope, func);
            case "resize": return checkResizeBuiltin(op, scope, func);
            case "Some": return checkSomeBuiltin(op, scope, func);
            case "insecure_rand": requireNotPureRt(func, op, "'insecure_rand'", "it is non-deterministic"); return checkInsecureRandBuiltin(op);
            case "wrap": case "sat": return checkConvertBuiltin(name, op, scope, func);
            default: throw new IllegalStateException("unreachable: '" + name + "' not in BUILTIN_NAMES");
        }
    }

    /** `Some(x)`: an ordinary bool builtin; checkMatchCondition adds the alive proof when it is a match condition. */
    private TypeInfo checkSomeBuiltin(Token op, Scope scope, FuncInfo func) {
        Token argExpr = collectSingleBuiltinArg(op, "Some", scope, func).get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (argType.storage == null) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'Some' requires a pointer, got '" + argType.canonical() + "'");
        }
        op.someOnRef = "ref".equals(argType.storage) && !argType.isSome;
        return new TypeInfo(null, "indeterminate", "bool");
    }


    /**
     * `wrap:<T>(x)` and `sat:<T>(x)` -- the two TOTAL integer conversions (no `unsafe`, they cannot
     * fail): `wrap` keeps the low bits (modular, like a C cast); `sat` clamps to T's range. Both
     * accept an integer of any width/signedness and yield T (indeterminate mutability).
     * `sat` evaluates its argument more than once in the emitted code, so the argument must be
     * side-effect free (a variable, field chain, or literal, optionally negated).
     */
    private TypeInfo checkConvertBuiltin(String name, Token op, Scope scope, FuncInfo func) {
        List<List<Token>> targs = op.left == null ? null : op.left.genericArgs;
        if (name.equals("wrap") && targs != null && targs.size() == 1 && targs.get(0).size() == 1
                && isFloatType(targs.get(0).get(0).text)) {
            // wrap:<f32>(x) / wrap:<f64>(x): float conversion (f64 -> f32 rounds to nearest; f32 -> f64 is exact).
            String floatTarget = targs.get(0).get(0).text;
            Token floatArg = collectSingleBuiltinArg(op, name, scope, func).get(0);
            TypeInfo floatArgType = resolveExprType(floatArg, scope, func);
            if (floatArgType.storage != null || !isFloatType(floatArgType.baseType)) {
                throw new CompilerException("type", floatArg.file, floatArg.line,
                        "'wrap:<" + floatTarget + ">' converts a float, got '" + floatArgType.canonical() + "'");
            }
            if (floatArgType.floatLiteral) {
                adaptLiteral(floatArg, new TypeInfo(null, floatArgType.mutability, floatTarget));
            } else {
                op.isFloatConvert = true;
            }
            return new TypeInfo(null, "indeterminate", floatTarget);
        }
        if (targs == null || targs.size() != 1 || targs.get(0).size() != 1
                || !INTEGER_TYPES.contains(targs.get(0).get(0).text)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + name + "' needs an integer target type, e.g. '" + name + ":<u8>(x)'");
        }
        String target = targs.get(0).get(0).text;
        Token argExpr = collectSingleBuiltinArg(op, name, scope, func).get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (argType.storage != null || !INTEGER_TYPES.contains(argType.baseType)) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'" + name + "' converts an integer, got '" + argType.canonical() + "'");
        }
        if (name.equals("sat") && !isPureSimpleOperand(argExpr)) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'sat' evaluates its argument several times, so it must be a plain variable, field "
                            + "or literal -- bind a computed value to a 'let' first");
        }
        return new TypeInfo(null, "indeterminate", target);
    }

    private boolean isPureSimpleOperand(Token t) {
        if (t.type == TokenType.VARREF || t.type == TokenType.INTEGER) {
            return true;
        }
        if (t.type == TokenType.OPERATOR && t.text.equals(".") && t.left != null && t.right != null) {
            return isPureSimpleOperand(t.left) && t.right.type == TokenType.VARREF;
        }
        if (t.type == TokenType.OPERATOR && t.text.equals("-") && t.unary && t.left != null) {
            return isPureSimpleOperand(t.left);
        }
        if (t.type == TokenType.OPERATOR && (t.text.equals("mut") || t.text.equals("imut")) && t.left != null) {
            return isPureSimpleOperand(t.left);
        }
        return false;
    }

    private List<Token> collectSingleBuiltinArg(Token op, String name, Scope scope, FuncInfo func) {
        List<Token> args = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        if (args.size() != 1) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + name + "' takes exactly one argument, got " + args.size());
        }
        return args;
    }

    /** `insecure_rand()`: non-crypto u64 (C `rand()`). A compiler builtin, so it needs no `unsafe` and is allowed in `@pure`. See docs/TypeChecker.md, "Builtin calls". */
    private TypeInfo checkInsecureRandBuiltin(Token op) {
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            List<Token> args = new ArrayList<>();
            collectCommaArgs(argsDelineator.childs.get(0), args);
            if (!args.isEmpty()) {
                throw new CompilerException("type", op.file, op.line,
                        "'insecure_rand' takes no arguments, got " + args.size());
            }
        }
        return new TypeInfo(null, "indeterminate", "u64");
    }

    /** `sizeof(T)` or `sizeof(expr)`: for an array, the base element type's size; the result is an indeterminate u64. */
    private TypeInfo checkSizeofBuiltin(Token op, Scope scope, FuncInfo func) {
        Token argExpr = collectSingleBuiltinArg(op, "sizeof", scope, func).get(0);
        TypeInfo argType = tryResolveAsTypeDescriptor(argExpr);
        if (argType == null) {
            argType = resolveExprType(argExpr, scope, func);
        }
        long size = computeSizeOf(argType, op);
        op.builtinConstantValue = size;
        op.sizeofStruct = sizedStructName(argType);
        return new TypeInfo(null, "indeterminate", "u64");
    }

    /**
     * The struct whose laid-out size a `sizeof` of this type means, or null when the size is fully known
     * here (a primitive, a pointer, a range). An array gives its element's struct (sizeof an array is its
     * element's size). Such a size is left symbolic (`SIZEOF name`) for the Optimizer to resolve.
     */
    private String sizedStructName(TypeInfo t) {
        if (t == null || t.storage != null || isRangeType(t)) {
            return null;
        }
        String base = t.baseType;
        while (isArrayType(base)) {
            base = arrayElementType(base);
        }
        return structs.containsKey(base) ? base : null;
    }

    /** Reads a (storage/mutability-prefixed) bare type name as a TypeInfo; null if it is not a known type, so the caller treats it as an expression. */
    private TypeInfo tryResolveAsTypeDescriptor(Token node) {
        String storage = null;
        Token cur = node;
        while (cur.type == TokenType.OPERATOR
                && (cur.text.equals("raw") || cur.text.equals("owns") || cur.text.equals("ref")
                        || cur.text.equals("auto") || cur.text.equals("static")
                        || cur.text.equals("mut") || cur.text.equals("imut"))) {
            if (!cur.text.equals("mut") && !cur.text.equals("imut")) {
                if (storage != null) {
                    return null;
                }
                storage = cur.text;
            }
            cur = cur.left;
        }
        if (cur.type != TokenType.VARREF || !isKnownType(cur.text)) {
            return null;
        }
        return new TypeInfo(storage, "imut", cur.text);
    }

    /** Size of a type: any pointer = POINTER_SIZE, range = 16, array = its base element. See docs/TypeChecker.md, "Builtin calls". */
    private long computeSizeOf(TypeInfo t, Token at) {
        if (t.storage != null) {
            return POINTER_SIZE;
        }
        if (isRangeType(t)) {
            return 16;
        }
        if (isArrayType(t.baseType)) {
            String elem = t.baseType;
            while (isArrayType(elem)) {
                elem = arrayElementType(elem);
            }
            return computeSizeOf(new TypeInfo(null, "imut", elem), at);
        }
        StructInfo si = structs.get(t.baseType);
        if (si != null) {
            // A rough estimate only (the real size, with padding and the layout the Optimizer finally
            // settles on, is resolved by SizeofResolutionPass from the symbolic SIZEOF): the hidden
            // ___type word plus the members, so it is never below 8 and never claims a one-byte stride.
            long sum = 8;
            for (TypeInfo memberType : si.members.values()) {
                sum += computeSizeOf(memberType, at);
            }
            return sum;
        }
        Long primitiveSize = PRIMITIVE_SIZE.get(t.baseType);
        if (primitiveSize != null) {
            return primitiveSize;
        }
        throw new CompilerException("type", at.file, at.line,
                "'sizeof' doesn't know the size of '" + t.canonical() + "'");
    }

    /** `len(x)`: constant for arrays and direct string literals, run-time for dynarrays; the 2-arg form scans an unsafe dynarray. See docs/TypeChecker.md, "len and range". */
    private TypeInfo checkLenBuiltin(Token op, Scope scope, FuncInfo func) {
        List<Token> args = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), args);
        }
        if (args.size() == 2) {
            // `len(unsafe_dynarray, terminator)`: unsafe, scans for the terminator; its type must equal the element type (as resize's fill).
            if (!"unsafe".equals(scope.currentSafety)) {
                throw new CompilerException("type", op.file, op.line,
                        "'len' with a terminator argument can only be used from within 'unsafe' code");
            }
            Token targetExpr = args.get(0);
            Token termExpr = args.get(1);
            TypeInfo targetType = resolveExprType(targetExpr, scope, func);
            if (!isUnsafeDynArrayType(targetType)) {
                throw new CompilerException("type", targetExpr.file, targetExpr.line,
                        "'len' with a terminator argument requires an 'unsafe dynarray', got '"
                                + targetType.canonical() + "'");
            }
            unsafeBypass(scope, udynTag(targetType));
            TypeInfo termType = resolveExprType(termExpr, scope, func);
            if (!termType.baseType.equals(targetType.unsafeDynArrayElementType.baseType)) {
                throw new CompilerException("type", termExpr.file, termExpr.line,
                        "'len' terminator must match the unsafe dynarray's own element type ('"
                                + targetType.unsafeDynArrayElementType.baseType + "'), got '"
                                + termType.baseType + "'");
            }
            // builtinConstantValue stays unset: BytecodeEmitter then emits a real scan.
            return new TypeInfo(null, "indeterminate", "u64");
        }
        if (args.size() != 1) {
            throw new CompilerException("type", op.file, op.line,
                    "'len' takes one argument, or two (a terminator, for an 'unsafe dynarray'), got "
                            + args.size());
        }
        Token argExpr = args.get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (isUnsafeDynArrayType(argType)) {
            // A bare 1-arg `len` on an unsafe dynarray has no length to read; a specific error beats the generic one below.
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'len' on an 'unsafe dynarray' requires a terminator argument, e.g. "
                            + "'len(arr, '\\0')' -- it has no hidden length to read otherwise");
        }
        if (isDynArrayType(argType)) {
            // Run-time length: builtinConstantValue stays unset so BytecodeEmitter emits LEN.
            return new TypeInfo(null, "indeterminate", "u64");
        }
        long length;
        if (isArrayType(argType.baseType)) {
            length = arrayLength(argType.baseType);
        } else if (argType.baseType.equals("string")) {
            if (argExpr.type != TokenType.STRING) {
                throw new CompilerException("type", argExpr.file, argExpr.line,
                        "'len' on a string requires a direct string literal -- its length can't be "
                                + "confirmed at compile time otherwise");
            }
            length = argExpr.literalValue.length();
        } else {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'len' requires a string, an array, or a dynarray, got '" + argType.canonical() + "'");
        }
        op.builtinConstantValue = length;
        return new TypeInfo(null, "indeterminate", "u64");
    }

    /** `range(x)`: 0..len for a string literal / fixed array (bounds baked into the type), plain `range` for a safe dynarray, error for an unsafe one. See docs/TypeChecker.md, "len and range". */
    private TypeInfo checkRangeBuiltin(Token op, Scope scope, FuncInfo func) {
        Token argExpr = collectSingleBuiltinArg(op, "range", scope, func).get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (isUnsafeDynArrayType(argType)) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'range' on an 'unsafe dynarray' has no hidden length to read -- it has no "
                            + "terminator argument to scan for one either, unlike 'len'");
        }
        if (isDynArrayType(argType)) {
            // Run-time length: no builtinConstantValue, no baked-in bounds.
            return new TypeInfo(null, "imut", "range");
        }
        long length;
        if (isArrayType(argType.baseType)) {
            length = arrayLength(argType.baseType);
        } else if (argType.baseType.equals("string")) {
            if (argExpr.type != TokenType.STRING) {
                throw new CompilerException("type", argExpr.file, argExpr.line,
                        "'range' on a string requires a direct string literal -- its length can't be "
                                + "confirmed at compile time otherwise");
            }
            length = argExpr.literalValue.length();
        } else {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'range' requires a string, an array, or a dynarray, got '" + argType.canonical() + "'");
        }
        op.builtinConstantValue = length;
        return new TypeInfo(null, "imut", "range(0," + length + ")");
    }

    /** `deref(p)`: the pointee value with storage stripped (usable with `.` directly). */
    private TypeInfo checkDerefBuiltin(Token op, Scope scope, FuncInfo func) {
        requireNotPureRt(func, op, "'deref'", "it reads memory through a pointer");
        Token argExpr = collectSingleBuiltinArg(op, "deref", scope, func).get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (argType.storage == null) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'deref' requires a pointer, got '" + argType.canonical() + "'");
        }
        // Safe code may dereference only a pointer proven non-null and alive:
        // an 'auto' pointer, a 'some'-tagged pointer, or one inside a
        // 'match Some(p)' (the same rule '.' member access uses).
        if (requiresAliveProof(argType, argExpr, scope)) {
            throw new CompilerException("type", op.file, op.line,
                    "'deref' of a pointer requires the pointer to be proven alive -- "
                            + "'auto', 'some'-tagged, inside 'match Some(...)', or vouched for with "
                            + "'assume match Some(...)' in an 'unsafe assume' block [needs: assume match Some(" + exprHint(argExpr) + ")]");
        }
        if ("raw".equals(argType.storage) && !unsafeBypass(scope, "deref")) {
            // A 'raw' pointer may point anywhere (C memory, the stack, the middle of a block), so even a proven one
            // is only as good as the programmer's word: reading through it is 'unsafe' on top of the alive proof.
            throw new CompilerException("type", op.file, op.line,
                    "'deref' of a 'raw' pointer is inherently unsafe -- it needs an 'unsafe deref' block (and the "
                            + "alive proof as well: 'match Some(...)', or 'assume match Some(...)' in an 'unsafe assume' block)");
        }
        return new TypeInfo(null, argType.mutability, argType.baseType, argType.aliasName);
    }

    /** `clone(p)`: any storage accepted; the result is `owns some`, pointee type and mutability kept. */
    private TypeInfo checkCloneBuiltin(Token op, Scope scope, FuncInfo func) {
        requireNotPureRt(func, op, "'clone'", "allocation depends on heap state (it can fail), so the result is not a function of the arguments");
        // Allocates and registers like `new`: same try/catch discipline and same program-wide flags.
        usesOwnsRefDynNew = true;
        usesThrow = true;
        if (!op.insideTry && !insideThrowingAllocContext) {
            throw new CompilerException("type", op.file, op.line,
                    "'clone' can fail (it throws when the underlying allocation fails) and must be "
                            + "wrapped in 'try ... catch { ... }'");
        }
        Token argExpr = collectSingleBuiltinArg(op, "clone", scope, func).get(0);
        TypeInfo argType = resolveExprType(argExpr, scope, func);
        if (argType.storage == null) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'clone' requires a pointer, got '" + argType.canonical() + "'");
        }
        // The source is read, so it is held to the same rule as `deref`: safe code may clone only a pointer proven
        // non-null and alive ('auto', 'some'-tagged, or inside 'match Some'). `clone` itself does no null check
        // on its source.
        if (requiresAliveProof(argType, argExpr, scope)) {
            throw new CompilerException("type", op.file, op.line,
                    "'clone' of a pointer requires the pointer to be proven alive -- "
                            + "'auto', 'some'-tagged, inside 'match Some(...)', or vouched for with "
                            + "'assume match Some(...)' in an 'unsafe assume' block [needs: assume match Some(" + exprHint(argExpr) + ")]");
        }
        if ("raw".equals(argType.storage) && !unsafeBypass(scope, "clone")) {
            // Same as 'deref': a 'raw' pointer's pointee is only as good as the programmer's word, so cloning from
            // one is 'unsafe' on top of the alive proof.
            throw new CompilerException("type", op.file, op.line,
                    "'clone' of a 'raw' pointer is inherently unsafe -- it needs an 'unsafe clone' block (and the "
                            + "alive proof as well: 'match Some(...)', or 'assume match Some(...)' in an 'unsafe assume' block)");
        }
        if (typeReachesDropHook(argType.baseType, new HashSet<>())) {
            throw new CompilerException("type", op.file, op.line,
                    "'clone' of '" + argType.baseType + "' is rejected: it holds a '@drop' resource "
                            + "(a handle cannot be duplicated)");
        }
        // A failed allocation throws, so what comes back is never null: `owns some`, like `new`.
        return argType.withStorage("owns").withSome(true); // keeps dynarray/array shape, mutability and alias
    }

    /** `call(fp, args...)`: variable arity, unsafe only; arity, argument types and result type come from the pointer's recorded signature. */
    private TypeInfo checkCallBuiltin(Token op, Scope scope, FuncInfo func) {
        if (!unsafeBypass(scope, "call")) {
            throw new CompilerException("type", op.file, op.line,
                    "'call' can only be used from within 'unsafe' code");
        }
        // Banned in @pure: the callee's purity cannot be verified at compile time.
        if (isPureFunc(func)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + func.name + "' is '@pure' and cannot use 'call' -- the target's purity can "
                            + "never be verified at compile time");
        }
        List<Token> args = new ArrayList<>();
        if (!op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), args);
        }
        if (args.isEmpty()) {
            throw new CompilerException("type", op.file, op.line,
                    "'call' requires at least a function-pointer argument");
        }
        Token funcPtrExpr = args.get(0);
        TypeInfo funcPtrType = resolveExprType(funcPtrExpr, scope, func);
        if (funcPtrType.funcPtrParamTypes == null) {
            throw new CompilerException("type", funcPtrExpr.file, funcPtrExpr.line,
                    "'call''s first argument must be a function pointer, got '"
                            + funcPtrType.canonical() + "'");
        }
        List<Token> callArgs = args.subList(1, args.size());
        if (callArgs.size() != funcPtrType.funcPtrParamTypes.size()) {
            throw new CompilerException("type", op.file, op.line,
                    "'call' passes " + callArgs.size() + " argument(s), but the function pointer's "
                            + "signature declares " + funcPtrType.funcPtrParamTypes.size());
        }
        for (int i = 0; i < callArgs.size(); i++) {
            TypeInfo argType = resolveExprType(callArgs.get(i), scope, func);
            TypeInfo paramType = funcPtrType.funcPtrParamTypes.get(i);
            if (!acceptsAdaptingLiteral(paramType, argType, callArgs.get(i))) {
                throw new CompilerException("type", callArgs.get(i).file, callArgs.get(i).line,
                        "'call' argument " + (i + 1) + " expects '" + paramType.canonical() + "', got '"
                                + argType.canonical() + "'");
            }
            markMovedIfOwned(paramType, callArgs.get(i), scope);
        }
        op.invokeArgCount = callArgs.size();
        op.resolvedCallConvention = funcPtrType.funcPtrCallConvention;
        return funcPtrType.funcPtrReturnType;
    }

    /** `bits_*` builtins: integer operands only, both sides one type (literals adapt), result that type; `bits_not` is unary. */
    private TypeInfo checkBitsBuiltin(String name, Token op, Scope scope, FuncInfo func) {
        List<Token> args = new ArrayList<>();
        if (!op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), args);
        }
        if (name.equals("bits_not")) {
            // bits_not(a): the complement of `a`, truncated to a's own width (u8: bits_not(0) == 255; signed: two's complement).
            if (args.size() != 1) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + name + "' takes exactly one argument, got " + args.size());
            }
            TypeInfo only = resolveExprType(args.get(0), scope, func);
            if (!INTEGER_TYPES.contains(only.baseType)) {
                throw new CompilerException("type", args.get(0).file, args.get(0).line,
                        "'" + name + "' requires an integer type, got '" + only.canonical() + "'");
            }
            if (only.literalValue != null && !literalFits(only.literalValue, only.baseType)) {
                throw new CompilerException("type", args.get(0).file, args.get(0).line,
                        "'" + name + "': literal " + only.literalValue + " does not fit in '" + only.baseType + "'");
            }
            return new TypeInfo(null, "indeterminate", only.baseType);
        }
        if (args.size() != 2) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + name + "' takes exactly two arguments, got " + args.size());
        }
        TypeInfo leftType = resolveExprType(args.get(0), scope, func);
        TypeInfo rightType = resolveExprType(args.get(1), scope, func);
        if (!INTEGER_TYPES.contains(leftType.baseType)) {
            throw new CompilerException("type", args.get(0).file, args.get(0).line,
                    "'" + name + "' requires an integer type, got '" + leftType.canonical() + "'");
        }
        if (!INTEGER_TYPES.contains(rightType.baseType)) {
            throw new CompilerException("type", args.get(1).file, args.get(1).line,
                    "'" + name + "' requires an integer type, got '" + rightType.canonical() + "'");
        }
        // Literal adaptation, exactly as in arithmetic: an integer literal on one side takes the other side's integer type
        // when its value fits (`bits_and(x_u8, 255)`, `bits_left(x_u8, 1)`, `bits_or(1, x_u8)`). Two literals of different
        // default types (`bits_and(5, -1)`: u64 and s64) unify the same way, whichever one fits adapting to the other.
        // Two typed operands must still be the same type.
        if (!leftType.sameBaseType(rightType)) {
            boolean leftLit = leftType.literalValue != null;
            boolean rightLit = rightType.literalValue != null;
            if (rightLit && literalFits(rightType.literalValue, leftType.baseType)) {
                adaptLiteral(args.get(1), leftType);
                rightType = leftType;
            } else if (leftLit && literalFits(leftType.literalValue, rightType.baseType)) {
                adaptLiteral(args.get(0), rightType);
                leftType = rightType;
            } else if (rightLit) {
                throw new CompilerException("type", args.get(1).file, args.get(1).line,
                        "'" + name + "': literal " + rightType.literalValue + " does not fit in '" + leftType.baseType + "'");
            } else if (leftLit) {
                throw new CompilerException("type", args.get(0).file, args.get(0).line,
                        "'" + name + "': literal " + leftType.literalValue + " does not fit in '" + rightType.baseType + "'");
            }
        }
        if (!leftType.sameBaseType(rightType)) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + name + "' requires both sides to be the same integer type, got '"
                            + leftType.canonical() + "' and '" + rightType.canonical() + "'");
        }
        return new TypeInfo(null, "indeterminate", leftType.baseType);
    }

    /** `memcopy(raw dest, u64 bytes, src)`: unsafe only; returns the destination's type. Constructing a `raw` is what is gated, not holding one. */
    private TypeInfo checkMemcopyBuiltin(Token op, Scope scope, FuncInfo func) {
        if (!unsafeBypass(scope, "memcopy")) {
            throw new CompilerException("type", op.file, op.line,
                    "'memcopy' can only be used from within 'unsafe' code");
        }
        List<Token> args = new ArrayList<>();
        if (!op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), args);
        }
        if (args.size() != 3) {
            throw new CompilerException("type", op.file, op.line,
                    "'memcopy' takes exactly three arguments (destination, byte count, source), got "
                            + args.size());
        }
        TypeInfo destType = resolveExprType(args.get(0), scope, func);
        if (!"raw".equals(destType.storage)) {
            throw new CompilerException("type", args.get(0).file, args.get(0).line,
                    "'memcopy''s destination must be a 'raw' pointer, got '" + destType.canonical() + "'");
        }
        TypeInfo countType = resolveExprType(args.get(1), scope, func);
        if (!isPlainU64(countType)) {
            throw new CompilerException("type", args.get(1).file, args.get(1).line,
                    "'memcopy''s byte count must be a plain 'u64', got '" + countType.canonical() + "'");
        }
        TypeInfo sourceType = resolveExprType(args.get(2), scope, func);
        if (sourceType.storage == null) {
            throw new CompilerException("type", args.get(2).file, args.get(2).line,
                    "'memcopy''s source must be a pointer (any storage), got '" + sourceType.canonical() + "'");
        }
        return destType;
    }

    /** `dyn([..])`: a fresh owns dynarray from an array literal (checkArrayLiteral validates it); builtinConstantValue carries the element count to BytecodeEmitter. */
    private TypeInfo checkDynBuiltin(Token op, Scope scope, FuncInfo func) {
        requireNotPureRt(func, op, "'dyn'", "allocation depends on heap state (it can fail), so the result is not a function of the arguments");
        return checkDynBuiltinCore(op, scope, func, false);
    }

    /**
     * Shared by `dyn([..])` and `unsafe dyn([..])` (checkUnsafeDynWrapper cannot delegate to the ordinary dispatch). The safe form allocates, so it needs
     * try/catch like `new` (op.insideTry or insideThrowingAllocContext) and sets usesThrow; the unsafe form is exempt. See docs/TypeChecker.md, "dyn and resize".
     */
    private TypeInfo checkDynBuiltinCore(Token op, Scope scope, FuncInfo func, boolean isUnsafe) {
        newDynDepth++;
        try {
            return checkDynBuiltinCoreInner(op, scope, func, isUnsafe);
        } finally {
            newDynDepth--;
        }
    }

    private TypeInfo checkDynBuiltinCoreInner(Token op, Scope scope, FuncInfo func, boolean isUnsafe) {
        usesOwnsRefDynNew = true;
        if (!isUnsafe) {
            usesThrow = true;
            if (!op.insideTry && !insideThrowingAllocContext) {
                throw new CompilerException("type", op.file, op.line,
                        "'dyn' can fail (it throws when the underlying allocation fails) and must be "
                                + "wrapped in 'try ... catch { ... }'");
            }
        }
        Token argExpr = collectSingleBuiltinArg(op, "dyn", scope, func).get(0);
        boolean argIsArrayLiteral = argExpr.type == TokenType.DELINEATOR && argExpr.text.equals("[");
        // `dyn:<T>([])`: the only way to write an empty dynarray (an empty literal has no element type); the element is `mut T`.
        List<List<Token>> dynTypeArgs = op.left == null ? null : op.left.genericArgs;
        if (dynTypeArgs != null) {
            if (dynTypeArgs.size() != 1 || dynTypeArgs.get(0).size() != 1) {
                throw new CompilerException("type", op.file, op.line,
                        "'dyn:<T>' takes exactly one element type, e.g. 'dyn:<char>([])'");
            }
            if (!argIsArrayLiteral || !argExpr.childs.isEmpty()) {
                throw new CompilerException("type", op.file, op.line,
                        "a type argument on 'dyn' is only for the empty array literal, 'dyn:<T>([])' -- a "
                                + "non-empty literal or a string already determines its own element type");
            }
            Token elemTok = dynTypeArgs.get(0).get(0);
            if (!isKnownType(elemTok.text)) {
                throw new CompilerException("type", elemTok.file, elemTok.line,
                        "'dyn:<" + elemTok.text + ">': '" + elemTok.text + "' is not a known type");
            }
            TypeInfo emptyElementType = new TypeInfo(null, "mut", elemTok.text);
            op.builtinConstantValue = 0L;
            return isUnsafe ? TypeInfo.unsafeDynArray("indeterminate", emptyElementType)
                    : TypeInfo.dynArray("indeterminate", emptyElementType).withSome(true);
        }
        if (argIsArrayLiteral && argExpr.childs.isEmpty()) {
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "an empty array literal has no element type -- write 'dyn:<T>([])' (e.g. "
                            + "'dyn:<char>([])') to construct an empty dynarray");
        }
        if (!argIsArrayLiteral) {
            // `dyn(text)`: a dynarray of chars; the element is `imut char`, like string indexing.
            TypeInfo argType = resolveExprType(argExpr, scope, func);
            if (argType.baseType.equals("string")) {
                op.dynFromStringSource = true;
                TypeInfo elementType = new TypeInfo(null, "imut", "char");
                return isUnsafe ? TypeInfo.unsafeDynArray("indeterminate", elementType)
                        : TypeInfo.dynArray("indeterminate", elementType).withSome(true);
            }
            throw new CompilerException("type", argExpr.file, argExpr.line,
                    "'dyn' requires an array literal or a string argument, e.g. 'dyn([0,1,2,3])' or 'dyn(text)'");
        }
        TypeInfo fixedArrayType = checkArrayLiteral(argExpr, scope, func);
        int count = arrayLength(fixedArrayType.baseType);
        // Reuse the element type checkArrayLiteral already computed: resolving the elements again re-ran the move check and wrongly reported `dyn([a, b])`.
        TypeInfo elementType = fixedArrayType.arrayElementType;
        op.builtinConstantValue = (long) count;
        return isUnsafe ? TypeInfo.unsafeDynArray("indeterminate", elementType)
                : TypeInfo.dynArray("indeterminate", elementType).withSome(true);
    }

    /** `unsafe dyn(..)`: calls checkDynBuiltinCore directly (the inner CALL must resolve to the unsafe type) and sets op.left.resolvedType by hand. */
    private TypeInfo checkUnsafeDynWrapper(Token op, Scope scope, FuncInfo func) {
        requireNotPure(func, op, "'unsafe dyn(...)'", "unsafe code can do anything the compiler cannot check, so purity could not be guaranteed");
        if (!"unsafe".equals(scope.currentSafety)) {
            throw new CompilerException("type", op.file, op.line,
                    "'unsafe dyn(...)' can only be used from within 'unsafe' code");
        }
        if (op.left.type != TokenType.OPERATOR || !op.left.text.equals("CALL")
                || op.left.left.type != TokenType.VARREF || !op.left.left.text.equals("dyn")) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'unsafe' must directly wrap a 'dyn(...)' call, e.g. 'unsafe dyn([0,1,2,3])'");
        }
        TypeInfo result = checkDynBuiltinCore(op.left, scope, func, true);
        unsafeBypass(scope, udynTag(result));
        op.left.resolvedType = result.canonical();
        return result;
    }

    /** `resize(d, n, fill)`: needs a `mut` dynarray, allowed in safe code; growing needs an explicit fill. Moves the source. See docs/TypeChecker.md, "dyn and resize". */
    private TypeInfo checkResizeBuiltin(Token op, Scope scope, FuncInfo func) {
        TypeInfo result;
        result = checkResizeBuiltinInner(op, scope, func);
        List<Token> resizeArgs = new ArrayList<>();
        if (op.right != null && op.right.childs != null && !op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), resizeArgs);
        }
        ProofKills.kill(scope.activeMatchPatterns, op, "'resize' (the block may move or shrink, freeing what was in it)",
                resizeArgs.isEmpty() ? null : memberPathRoot(resizeArgs.get(0)), destroyedTypes(result));
        return result;
    }

    private TypeInfo checkResizeBuiltinInner(Token op, Scope scope, FuncInfo func) {
        requireNotPureRt(func, op, "'resize'", "allocation depends on heap state (it can fail), so the result is not a function of the arguments");
        // A direct `resize` in a dynarray `for` body is rejected here; indirect ones by checkDynArrayForLoopResizeSafety.
        if (scope.insideDynArrayForLoopBody) {
            throw new CompilerException("type", op.file, op.line,
                    "'resize' cannot be called inside a \"for i in dynarrayExpr{...}\" loop's own body -- "
                            + "resizing the dynarray being iterated (even a different one, conservatively) "
                            + "while iterating it is not allowed");
        }
        funcsCallingResizeDirectly.add(func);
        List<Token> args = new ArrayList<>();
        if (!op.right.childs.isEmpty()) {
            collectCommaArgs(op.right.childs.get(0), args);
        }
        if (args.size() != 2 && args.size() != 3) {
            throw new CompilerException("type", op.file, op.line,
                    "'resize' takes two arguments (an unsafe dynarray, the new count) or three (a safe "
                            + "dynarray, the new count, a fill value for any newly-created slots), got "
                            + args.size());
        }
        TypeInfo dynArrType = resolveExprType(args.get(0), scope, func);
        boolean isUnsafeTarget = isUnsafeDynArrayType(dynArrType);
        if (!isUnsafeTarget && !isDynArrayType(dynArrType)) {
            throw new CompilerException("type", args.get(0).file, args.get(0).line,
                    "'resize''s first argument must be a dynarray, got '" + dynArrType.canonical() + "'");
        }
        if (!"mut".equals(dynArrType.mutability)) {
            throw new CompilerException("type", args.get(0).file, args.get(0).line,
                    "'resize' requires a 'mut' dynarray, got '" + dynArrType.canonical() + "'");
        }
        // Unsafe dynarray: a bare realloc with no fill (no hidden length, no initialisation guarantee).
        if (isUnsafeTarget) {
            if (!unsafeBypass(scope, udynTag(dynArrType))) {
                throw new CompilerException("type", op.file, op.line,
                        "'resize' on an 'unsafe dynarray' can only be used from within 'unsafe' code");
            }
            if (args.size() != 2) {
                throw new CompilerException("type", op.file, op.line,
                        "'resize' on an 'unsafe dynarray' takes exactly two arguments (the dynarray, "
                                + "the new count) -- no fill value; growing may leave newly-created "
                                + "memory uninitialized, permitted here only because the code is "
                                + "'unsafe', got " + args.size());
            }
            // An unsafe `resize` cannot throw, so `try` around it is rejected (same two-way contract as requireThrowsWrapping).
            if (op.insideTry) {
                throw new CompilerException("type", op.file, op.line,
                        "'try' wraps a call to 'resize' on an 'unsafe dynarray', which cannot throw -- "
                                + "'try'/'catch' is only needed (and only allowed) around 'resize' on a "
                                + "safe dynarray");
            }
        } else if (args.size() != 3) {
            throw new CompilerException("type", op.file, op.line,
                    "'resize' on a dynarray takes exactly three arguments (the dynarray, the new count, "
                            + "a fill value for any newly-created slots), got " + args.size());
        }
        // A safe `resize` can fail like `new`/`dyn`: it needs try/catch (op.insideTry or insideThrowingAllocContext) and sets usesThrow.
        if (!isUnsafeTarget) {
            usesThrow = true;
            if (!op.insideTry && !insideThrowingAllocContext) {
                throw new CompilerException("type", op.file, op.line,
                        "'resize' can fail (it throws when the underlying reallocation fails) and must be "
                                + "wrapped in 'try ... catch { ... }'");
            }
        }
        TypeInfo countType = resolveExprType(args.get(1), scope, func);
        if (!isPlainU64(countType)) {
            throw new CompilerException("type", args.get(1).file, args.get(1).line,
                    "'resize''s new count must be a plain 'u64', got '" + countType.canonical() + "'");
        }
        TypeInfo elementType;
        if (isUnsafeTarget) {
            elementType = dynArrType.unsafeDynArrayElementType;
        } else {
            elementType = dynArrType.dynArrayElementType;
            TypeInfo fillType = resolveExprType(args.get(2), scope, func);
            if (!fillType.baseType.equals(elementType.baseType)) {
                throw new CompilerException("type", args.get(2).file, args.get(2).line,
                        "'resize''s fill value must match the dynarray's own element type ('"
                                + elementType.baseType + "'), got '" + fillType.baseType + "'");
            }
        }
        // `resize` takes ownership and returns a new owns (a realloc may move): the source is moved by hand via markMovedIfOwned, both variants.
        markMovedIfOwned(dynArrType, args.get(0), scope);
        return isUnsafeTarget ? TypeInfo.unsafeDynArray("indeterminate", elementType)
                : TypeInfo.dynArray("indeterminate", elementType).withSome(true);
    }

    /** Visibility: the impl-level gate (impl must be `@pub` outside its file) comes first; a private method is callable only from the same impl block (identity, since a struct can have several). */
    private void checkImplMethodVisibility(FuncInfo candidate, ImplInfo impl, String methodName, Token at,
            FuncInfo callerFunc) {
        String declFile = candidate.funcToken.file;
        boolean sameFile = declFile.equals(at.file);
        if (!impl.isPublic && !sameFile) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + impl.concreteName + "'s impl (containing '" + methodName + "') is not '@pub' -- "
                            + "not accessible outside the file it's declared in");
        }
        if (candidate.isPublic) {
            return;
        }
        if (callerFunc == null || callerFunc.enclosingImpl != impl) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + methodName + "' is private -- it can only be referenced by another func "
                            + "within that same impl");
        }
    }

    /** RVO for a method call returning a struct: legal only as a `let` RHS or bare-variable assignment RHS (same rule and message as `checkCall`). */
    private void markStructRvoMethodCall(Token op, FuncInfo candidate, boolean eligibleForRvoHere) {
        if (!isStructByValueReturn(candidate.returnType)) {
            return;
        }
        if (!eligibleForRvoHere) {
            throw new CompilerException("type", op.file, op.line,
                    "a call to '" + candidate.name + "' (which returns '" + candidate.returnType.baseType
                            + "' by value) may only be used as a 'let' binding's own right-hand side, or as a plain "
                            + "assignment's right-hand side with a bare-variable target -- not as a function argument, "
                            + "inside a larger expression, as a 'return' statement's own expression, or anywhere else a "
                            + "value is otherwise expected; bind it to a 'let' first");
        }
        op.isStructRvoCall = true;
    }

    /** `receiver.method(args)`: the receiver only selects the impls to search and is not injected as an argument; resolved at compile time, no run-time dispatch. See docs/TypeChecker.md, "Method calls". */
    private TypeInfo checkMethodCall(Token op, Scope scope, FuncInfo func, boolean eligibleForRvoHere) {
        Token dotNode = op.left;
        Token methodNameTok = dotNode.right;
        if (methodNameTok.type != TokenType.VARREF) {
            throw new CompilerException("type", dotNode.file, dotNode.line,
                    "expected a method name after '.'");
        }
        String methodName = methodNameTok.text;

        // `TypeName.method(..)` (static call): a bare non-variable name that is a struct/enum goes to checkStaticMethodCall; generic receivers arrive already monomorphized.
        if (dotNode.left.type == TokenType.VARREF && scope.lookup(dotNode.left.text) == null
                && (structs.containsKey(dotNode.left.text) || enums.containsKey(dotNode.left.text))) {
            return checkStaticMethodCall(op, dotNode.left.text, methodName, scope, func, eligibleForRvoHere);
        }

        // `Lib.fn(..)`: a bare library name.
        // the library counterpart of the static-method case just above.
        if (dotNode.left.type == TokenType.VARREF && scope.lookup(dotNode.left.text) == null
                && libraries.containsKey(dotNode.left.text)) {
            return checkLibraryCall(op, dotNode.left.text, methodName, scope, func);
        }

        // Resolved as an expression, not via checkDot (a method is not a member).
        TypeInfo receiverType = resolveExprType(dotNode.left, scope, func);

        if (interfaces.containsKey(receiverType.baseType)) {
            return checkInterfaceMethodDispatch(op, dotNode, receiverType.baseType, methodName, scope, func);
        }

        List<ImplInfo> impls = implsByConcreteType.get(receiverType.baseType);
        if (impls == null) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + receiverType.baseType + "' has no method named '" + methodName + "'");
        }

        // `recv.method:<U>(..)`: explicit generic-method type args survive GenericsExpander and are dispatched separately (never in impl.methods).
        if (methodNameTok.genericArgs != null) {
            return checkGenericMethodCall(op, dotNode, receiverType, impls, methodName, methodNameTok, scope, func, eligibleForRvoHere);
        }

        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        List<TypeInfo> argTypes = new ArrayList<>();
        for (Token argNode : argNodes) {
            argTypes.add(resolveExprType(argNode, scope, func));
        }

        boolean nameExistsAsStatic = false;
        for (ImplInfo impl : impls) {
            List<FuncInfo> overloadCandidates = impl.candidates(methodName, false);
            if (overloadCandidates.isEmpty()) {
                if (impl.staticMethods.containsKey(methodName)) {
                    nameExistsAsStatic = true;
                }
                continue;
            }
            for (FuncInfo candidate : orderByLiteralExactness(overloadCandidates, argTypes)) {
            if (candidate.paramTypes.size() != argTypes.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < argTypes.size(); i++) {
                if (!argSatisfiesParam(candidate.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                retagLiteralArgs(candidate.paramTypes, argTypes, argNodes);
                checkImplMethodVisibility(candidate, impl, methodName, op, func);
                op.resolvedCallTarget = candidate.mangledName;
                op.resolvedCallConvention = candidate.callConvention;
                requireThrowsWrapping(candidate, op);
                for (int i = 0; i < argNodes.size(); i++) {
                    markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
                }
                // Per-call-site unwind lists; see docs/TypeChecker.md, "Method calls".
                op.destructOnExit = collectOwnsToDestruct(scope, scope.functionRootScope, null);
                op.unlockOnExit = collectLockReleasesToBoundary(scope, scope.functionRootScope);
                if (candidate == func && scope.suspendsSelfRecursion) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot call '" + func.name + "' from within its own base-case match "
                                    + "block -- the func loses its recursive privileges there");
                }
                if (candidate == func && getDecorator(func.funcToken.decorators, "recursive") != null) {
                    requireRecursiveCallReturnSiteOnly(func, op);
                    requireRecursiveCallWithinProof(func, op, argNodes, argTypes, scope);
                }
                requireMethodLockProofs(candidate, dotNode.left, argNodes, scope, op);
                requirePureCalleeIfPure(func, candidate, op);
                callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
                markStructRvoMethodCall(op, candidate, eligibleForRvoHere);
                return candidate.returnType;
            }
            }
        }
        // A specific error when the name exists only as a static method.
        if (nameExistsAsStatic) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + methodName + "' is a static method on '" + receiverType.baseType + "', not an "
                            + "instance method -- call it as '" + receiverType.baseType + "." + methodName
                            + "(...)', not on an instance");
        }
        throw new CompilerException("type", op.file, op.line,
                "'" + receiverType.baseType + "' has no method '" + methodName
                        + "' matching argument types (" + describeTypes(argTypes) + ")"
                        + unavailableNote(receiverType.baseType + "." + methodName));
    }

    /**
    /** `recv.method:<U>(..)`: instantiates (memoized) the residual-generic method and matches the arguments against that single candidate. */
    private TypeInfo checkGenericMethodCall(Token op, Token dotNode, TypeInfo receiverType, List<ImplInfo> impls,
            String methodName, Token methodNameTok, Scope scope, FuncInfo func, boolean eligibleForRvoHere) {
        ImplInfo owningImpl = null;
        Token template = null;
        for (ImplInfo impl : impls) {
            Token t = impl.genericMethodTemplates.get(methodName);
            if (t != null) {
                owningImpl = impl;
                template = t;
                break;
            }
        }
        if (template == null) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + receiverType.baseType + "' has no generic method named '" + methodName + "'");
        }

        List<String> argNames = new ArrayList<>();
        for (List<Token> group : methodNameTok.genericArgs) {
            if (group.size() != 1) {
                throw new CompilerException("type", methodNameTok.file, methodNameTok.line,
                        "generic method type argument must be a single type name, found a compound "
                                + "expression");
            }
            argNames.add(group.get(0).text);
        }
        if (template.typeParams.size() != argNames.size()) {
            throw new CompilerException("type", methodNameTok.file, methodNameTok.line,
                    "'" + methodName + "' expects " + template.typeParams.size() + " type argument(s), got "
                            + argNames.size());
        }

        FuncInfo candidate = instantiateGenericMethod(owningImpl, template, argNames, methodNameTok);
        methodNameTok.genericArgs = null;

        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        List<TypeInfo> argTypes = new ArrayList<>();
        for (Token argNode : argNodes) {
            argTypes.add(resolveExprType(argNode, scope, func));
        }
        if (candidate.paramTypes.size() != argTypes.size()) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + methodName + "' expects " + candidate.paramTypes.size() + " argument(s), got "
                            + argTypes.size());
        }
        for (int i = 0; i < argTypes.size(); i++) {
            if (!argSatisfiesParam(candidate.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + methodName + "' argument " + (i + 1) + " type mismatch: expected '"
                                + candidate.paramTypes.get(i).canonical() + "', got '"
                                + argTypes.get(i).canonical() + "'");
            }
        }
        retagLiteralArgs(candidate.paramTypes, argTypes, argNodes);

        checkImplMethodVisibility(candidate, owningImpl, methodName, op, func);
        op.resolvedCallTarget = candidate.mangledName;
        op.resolvedCallConvention = candidate.callConvention;
        requireThrowsWrapping(candidate, op);
        for (int i = 0; i < argNodes.size(); i++) {
            markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
        }
        // Per-call-site unwind lists; see docs/TypeChecker.md, "Method calls".
        op.destructOnExit = collectOwnsToDestruct(scope, scope.functionRootScope, null);
        op.unlockOnExit = collectLockReleasesToBoundary(scope, scope.functionRootScope);
        requireMethodLockProofs(candidate, dotNode.left, argNodes, scope, op);
        requirePureCalleeIfPure(func, candidate, op);
        callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
        markStructRvoMethodCall(op, candidate, eligibleForRvoHere);
        return candidate.returnType;
    }

    /** Materializes (memoized per type-argument list) a callable FuncInfo for a residual-generic method template; the template is already tree-built, so deepCloneCheckedTree is used. */
    private FuncInfo instantiateGenericMethod(ImplInfo impl, Token template, List<String> argNames, Token usageTok) {
        String key = template.sub.get(0).text + "<" + String.join(",", argNames) + ">";
        FuncInfo existing = impl.genericMethodInstances.get(key);
        if (existing != null) {
            return existing;
        }

        if (template.typeParamBounds != null) {
            for (int i = 0; i < template.typeParamBounds.size(); i++) {
                String bound = template.typeParamBounds.get(i);
                if (bound != null && !structImplementsInterface(argNames.get(i), bound)) {
                    throw new CompilerException("type", usageTok.file, usageTok.line,
                            "'" + argNames.get(i) + "' does not implement '" + bound
                                    + "' -- required by the bound on type parameter '"
                                    + template.typeParams.get(i) + "'");
                }
            }
        }

        Map<String, String> subMap = new HashMap<>();
        for (int i = 0; i < template.typeParams.size(); i++) {
            subMap.put(template.typeParams.get(i), argNames.get(i));
        }

        Token clone = deepCloneCheckedTree(template);
        // `self` is substituted here for residual-generic templates (collectImpl skips them).
        substituteSelfInPlace(clone.sub.get(1).childs, impl.concreteName);
        substituteSelfInPlace(clone.sub.subList(2, clone.sub.size()), impl.concreteName);
        substituteGenericMethodParams(clone, subMap);
        clone.typeParams = null;
        clone.typeParamBounds = null;

        FuncInfo info = new FuncInfo();
        info.funcToken = clone;
        buildFuncInfo(clone, info);
        info.enclosingImpl = impl;
        info.declOrder = pendingDeclOrder.getOrDefault(impl.declTok, Integer.MAX_VALUE);
        info.safetyTag = impl.safetyTag;
        // Forced public: reached only through an instance call, whose impl-level @pub check follows.
        info.isPublic = true;
        String methodNamePart = info.name;
        info.mangledName = (impl.interfaceName != null
                ? impl.concreteName + "__" + impl.interfaceName + "_" + methodNamePart
                : impl.concreteName + "_" + methodNamePart) + "__" + String.join("_", argNames);
        validateMethodLocks(clone.decorators, info.paramNames, info.paramTypes, impl.concreteName);

        // Memoized before the body is checked: guards recursive self-instantiation and avoids mutating implInfo.methods while it is iterated.
        impl.genericMethodInstances.put(key, info);
        // BytecodeEmitter walks the impl root's childs, not genericMethodInstances.
        impl.declTok.childs.add(clone);

        checkFunctionBody(info);

        return info;
    }

    /** Blanket VARREF-text substitution of a residual-generic method's type parameters (post-GenericsExpander counterpart of its substituteParams). */
    private void substituteGenericMethodParams(Token node, Map<String, String> subMap) {
        if (node == null) {
            return;
        }
        if (node.type == TokenType.VARREF && subMap.containsKey(node.text)) {
            node.text = subMap.get(node.text);
        }
        substituteGenericMethodParams(node.left, subMap);
        substituteGenericMethodParams(node.right, subMap);
        if (node.childs != null) {
            for (Token c : node.childs) {
                substituteGenericMethodParams(c, subMap);
            }
        }
        if (node.sub != null) {
            for (Token s : node.sub) {
                substituteGenericMethodParams(s, subMap);
            }
        }
        if (node.genericArgs != null) {
            for (List<Token> group : node.genericArgs) {
                for (Token g : group) {
                    substituteGenericMethodParams(g, subMap);
                }
            }
        }
        if (node.typeBound != null) {
            for (Token t : node.typeBound) {
                substituteGenericMethodParams(t, subMap);
            }
        }
    }

    /** `c.foo(args)` on a bare interface type: uses the synthesized dispatcher for (interface, method); one candidate, no overloads, no impl bookkeeping. See docs/TypeChecker.md, "Method calls". */
    private TypeInfo checkInterfaceMethodDispatch(Token op, Token dotNode, String interfaceName, String methodName,
            Scope scope, FuncInfo func) {
        List<FuncInfo> overloads = functions.get(interfaceDispatcherName(interfaceName, methodName));
        FuncInfo dispatcher = (overloads == null || overloads.isEmpty()) ? null : overloads.get(0);
        if (dispatcher == null) {
            throw new CompilerException("type", op.file, op.line,
                    "could not resolve implementation: the receiver is only known by its interface "
                            + "type ('" + interfaceName + "'), and no dispatcher could be generated for '"
                            + methodName + "' -- either it has no explicit 'self'-typed parameter "
                            + "(nothing in its own signature identifies which concrete implementer to "
                            + "call), it's '@lock'/'@unlock'-decorated (a synthesized call site can't yet "
                            + "establish the required lock proof), or no '@gt_alive_check' function is "
                            + "in scope (import the ghost-table stdlib to enable interface dispatch)");
        }
        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        List<TypeInfo> argTypes = new ArrayList<>();
        for (Token argNode : argNodes) {
            argTypes.add(resolveExprType(argNode, scope, func));
        }
        if (dispatcher.paramTypes.size() != argTypes.size()) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + methodName + "' on interface '" + interfaceName + "' expects "
                            + dispatcher.paramTypes.size() + " argument(s), got " + argTypes.size());
        }
        for (int i = 0; i < argTypes.size(); i++) {
            if (!argSatisfiesParam(dispatcher.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                throw new CompilerException("type", op.file, op.line,
                        "'" + methodName + "' on interface '" + interfaceName + "' argument " + (i + 1)
                                + ": expected '" + dispatcher.paramTypes.get(i).canonical() + "', got '"
                                + argTypes.get(i).canonical() + "'");
            }
        }
        retagLiteralArgs(dispatcher.paramTypes, argTypes, argNodes);
        op.resolvedCallTarget = dispatcher.mangledName;
        op.resolvedCallConvention = dispatcher.callConvention;
        for (int i = 0; i < argNodes.size(); i++) {
            markMovedIfOwned(dispatcher.paramTypes.get(i), argNodes.get(i), scope);
        }
        requirePureCalleeIfPure(func, dispatcher, op);
        callEdges.add(new CallEdge(func, dispatcher, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
        return dispatcher.returnType;
    }

    /** `TypeName.method(..)`: static twin of checkMethodCall; the concrete name comes straight from the type name. */
    private TypeInfo checkStaticMethodCall(Token op, String concreteName, String methodName, Scope scope,
            FuncInfo func, boolean eligibleForRvoHere) {
        List<ImplInfo> impls = implsByConcreteType.get(concreteName);
        if (impls == null) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + concreteName + "' has no static method named '" + methodName + "'");
        }

        List<Token> argNodes = new ArrayList<>();
        Token argsDelineator = op.right;
        if (!argsDelineator.childs.isEmpty()) {
            collectCommaArgs(argsDelineator.childs.get(0), argNodes);
        }
        List<TypeInfo> argTypes = new ArrayList<>();
        for (Token argNode : argNodes) {
            argTypes.add(resolveExprType(argNode, scope, func));
        }

        boolean nameExistsAsInstance = false;
        for (ImplInfo impl : impls) {
            List<FuncInfo> overloadCandidates = impl.candidates(methodName, true);
            if (overloadCandidates.isEmpty()) {
                if (impl.methods.containsKey(methodName)) {
                    nameExistsAsInstance = true;
                }
                continue;
            }
            for (FuncInfo candidate : orderByLiteralExactness(overloadCandidates, argTypes)) {
            if (candidate.paramTypes.size() != argTypes.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < argTypes.size(); i++) {
                if (!argSatisfiesParam(candidate.paramTypes.get(i), argTypes.get(i), argNodes.get(i), scope)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                retagLiteralArgs(candidate.paramTypes, argTypes, argNodes);
                checkImplMethodVisibility(candidate, impl, methodName, op, func);
                op.resolvedCallTarget = candidate.mangledName;
                op.resolvedCallConvention = candidate.callConvention;
                requireThrowsWrapping(candidate, op);
                checkFsPolicyAtCall(candidate, op, argNodes, scope);
                for (int i = 0; i < argNodes.size(); i++) {
                    markMovedIfOwned(candidate.paramTypes.get(i), argNodes.get(i), scope);
                }
                // Per-call-site unwind lists; see docs/TypeChecker.md, "Method calls".
                op.destructOnExit = collectOwnsToDestruct(scope, scope.functionRootScope, null);
                op.unlockOnExit = collectLockReleasesToBoundary(scope, scope.functionRootScope);
                if (candidate == func && scope.suspendsSelfRecursion) {
                    throw new CompilerException("type", op.file, op.line,
                            "cannot call '" + func.name + "' from within its own base-case match "
                                    + "block -- the func loses its recursive privileges there");
                }
                if (candidate == func && getDecorator(func.funcToken.decorators, "recursive") != null) {
                    requireRecursiveCallReturnSiteOnly(func, op);
                    requireRecursiveCallWithinProof(func, op, argNodes, argTypes, scope);
                }
                requirePureCalleeIfPure(func, candidate, op);
                callEdges.add(new CallEdge(func, candidate, scope.currentSafety, op, scope.insideDynArrayForLoopBody));
                markStructRvoMethodCall(op, candidate, eligibleForRvoHere);
                return candidate.returnType;
            }
            }
        }
        // A specific error when the name exists only as an instance method.
        if (nameExistsAsInstance) {
            throw new CompilerException("type", op.file, op.line,
                    "'" + methodName + "' is an instance method on '" + concreteName + "', not a static "
                            + "method -- call it on an instance instead");
        }
        throw new CompilerException("type", op.file, op.line,
                "'" + concreteName + "' has no static method '" + methodName
                        + "' matching argument types (" + describeTypes(argTypes) + ")"
                        + unavailableNote(concreteName + "." + methodName));
    }

    /** Builds a raw `receiver.field` node for checkLockMatchStatement; marked compiler-synthesized so checkDot's swap-field restriction exempts it. */
    private Token buildFieldDot(Token receiver, String fieldName, Token at) {
        Token dot = new Token(TokenType.OPERATOR, ".", at.line, at.file);
        dot.left = receiver;
        dot.right = new Token(TokenType.VARREF, fieldName, at.line, at.file);
        dot.isCompilerSynthesizedSwapAccess = true;
        return dot;
    }

    /** Builds an ordinary "EnumName.Variant" '.' node -- raw, unresolved, recognized by checkDot's own enum-variant branch exactly as if it had been written by hand. */
    private Token buildEnumVariantRef(String enumName, String variantName, Token at) {
        Token dot = new Token(TokenType.OPERATOR, ".", at.line, at.file);
        dot.left = new Token(TokenType.VARREF, enumName, at.line, at.file);
        dot.right = new Token(TokenType.VARREF, variantName, at.line, at.file);
        return dot;
    }

    /**
     * `match @lock EXPR{ OPEN:{..} CLOSED:{..} }`: validates real Token trees (swap, ==, release assign) through the ordinary machinery, then rewrites
     * `stmt` in place into a `loop` (spin on ATOMIC_SWAP). See docs/TypeChecker.md, "match @lock lowering".
     */
    private void checkLockMatchStatement(Token stmt, Scope scope, FuncInfo func, boolean insideLoop) {
        requireNotPure(func, stmt, "'match @lock'", "acquiring the lock writes the mutex and blocks (a retry loop on shared state)");
        Token receiver = stmt.sub.get(0);
        TypeInfo receiverType = resolveExprType(receiver, scope, func);
        String matchedSlotKey = slotKeyOf(receiver);
        if (matchedSlotKey != null) {
            fnLockMatched.add(matchedSlotKey);
        }
        StructInfo structInfo = structs.get(receiverType.baseType);
        if (structInfo == null || structInfo.swapFieldName == null) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "'match @lock' requires a struct value with a 'swap'-tagged atomic mutex-state field, "
                            + "found '" + receiverType.canonical() + "'");
        }
        String swapFieldName = structInfo.swapFieldName;
        TypeInfo swapFieldType = structInfo.members.get(swapFieldName);
        String stateEnumName = swapFieldType.baseType;

        Token openCase = null;
        Token closedCase = null;
        for (Token caseTok : stmt.childs) {
            List<Token> labels = caseTok.sub;
            if (labels.size() != 1) {
                throw new CompilerException("type", caseTok.file, caseTok.line,
                        "'match @lock' case labels can't be '|'-chained -- 'OPEN' and 'CLOSED' each need "
                                + "their own, separate case");
            }
            String label = labels.get(0).text;
            if (label.equals("OPEN")) {
                if (openCase != null) {
                    throw new CompilerException("type", caseTok.file, caseTok.line,
                            "'OPEN' is already covered by an earlier case");
                }
                openCase = caseTok;
            } else if (label.equals("CLOSED")) {
                if (closedCase != null) {
                    throw new CompilerException("type", caseTok.file, caseTok.line,
                            "'CLOSED' is already covered by an earlier case");
                }
                closedCase = caseTok;
            } else {
                throw new CompilerException("type", caseTok.file, caseTok.line,
                        "'match @lock' case labels must be exactly 'OPEN' and 'CLOSED', found '" + label + "'");
            }
        }
        if (openCase == null || closedCase == null) {
            throw new CompilerException("type", stmt.file, stmt.line,
                    "'match @lock' must be exhaustive -- both 'OPEN' and 'CLOSED' cases are required");
        }

        // Acquire attempt: `recv.field swap Enum.CLOSED`.
        Token swapOp = new Token(TokenType.OPERATOR, "swap", stmt.line, stmt.file);
        swapOp.left = buildFieldDot(receiver, swapFieldName, stmt);
        swapOp.right = buildEnumVariantRef(stateEnumName, "CLOSED", stmt);
        resolveExprType(swapOp, scope, func);

        // Was it OPEN: `== Enum.OPEN` on the swap result.
        Token condOp = new Token(TokenType.OPERATOR, "==", stmt.line, stmt.file);
        condOp.left = swapOp;
        condOp.right = buildEnumVariantRef(stateEnumName, "OPEN", stmt);
        resolveExprType(condOp, scope, func);

        // Release: `recv.field = Enum.OPEN`, checked once and replayed at every OPEN-case exit. Not wrapped in `mut`: an enum-variant reference is always imut and checkAssign has the matching exception.
        Token releaseValue = buildEnumVariantRef(stateEnumName, "OPEN", stmt);
        Token releaseAssign = new Token(TokenType.OPERATOR, "=", stmt.line, stmt.file);
        releaseAssign.left = buildFieldDot(receiver, swapFieldName, stmt);
        releaseAssign.right = releaseValue;
        resolveExprType(releaseAssign, scope, func);

        // The OPEN body owes `releaseAssign` on every exit (Scope.activeLockReleases). Both case bodies sit under one extra loop-boundary Scope so a user `break` targets this statement's own spin loop.
        Scope loopBodyScope = new Scope(scope, true);

        Set<String> preState = scope.movedSlots;
        Set<String> openBranchMoved = new HashSet<>(preState);
        // Inside the OPEN case this thread holds the lock, so the struct's
        // own "@lock(match self.<swap field> : OPEN)" proof is satisfied
        // for this receiver -- the same proof an ordinary
        // "match recv.<field>{ OPEN:{...} }" plants (hasEnumFieldProof).
        List<Token.MatchPattern> openProofPatterns = new ArrayList<>(loopBodyScope.activeMatchPatterns);
        String openReceiverSlot = slotKeyOf(receiver);
        if (openReceiverSlot != null) {
            openProofPatterns.add(new Token.MatchPattern(openReceiverSlot, "enum_field_variants", swapFieldName,
                    new ArrayList<>(List.of("OPEN"))));
        }
        Scope openProofScope = new Scope(loopBodyScope, new HashSet<>(preState), openProofPatterns, false);
        Scope openBodyScope = new Scope(openProofScope, openBranchMoved, releaseAssign);
        Scope savedLockClosedScope = lockClosedLoopScope;
        lockClosedLoopScope = null; // 'continue' (retry) is meaningless while holding the lock
        try {
            checkLinesInScope(openCase.childs, openBodyScope, func, true);
        } finally {
            lockClosedLoopScope = savedLockClosedScope;
        }
        openCase.destructOnExit = collectNaturalEndDestruct(openCase.childs, openBodyScope, openBodyScope);

        // `CLOSED:default` / `CLOSED:default(args)`: detected on the tree-built CLOSED body and substituted before use; an ordinary body is untouched.
        Scope closedParentScope = loopBodyScope;
        DefaultPolicySubstitution substitution = substituteDefaultLockMatchPolicyIfPresent(closedCase, structInfo,
                scope, loopBodyScope, func);
        if (substitution != null) {
            closedParentScope = substitution.preLoopScope;
            stmt.preLoopInit = substitution.preLoopInit;
            stmt.lockSpinBound = substitution.spinBound;
        }

        Set<String> closedBranchMoved = new HashSet<>(preState);
        Scope closedBodyScope = new Scope(closedParentScope, closedBranchMoved, scope.activeMatchPatterns, false);
        lockClosedLoopScope = loopBodyScope;
        int retriesBefore = lockRetryContinuesSeen;
        try {
            checkLinesInScope(closedCase.childs, closedBodyScope, func, true);
        } finally {
            lockClosedLoopScope = savedLockClosedScope;
        }
        if (substitution == null && lockRetryContinuesSeen == retriesBefore) {
            stmt.lockSpinBound = 1;         // a try-lock: no CLOSED path retries, so the loop body runs once
        }
        if (substitution == null && lockRetryContinuesSeen > retriesBefore && !isGhostTableFunction(func) && !synthesizingGlueBody) {
            throw new CompilerException("type", closedCase.file, closedCase.line,
                    "a 'match @lock' that retries must say how many times: a bare 'CLOSED:{ ... continue }' would spin without a limit, which safe code does not allow -- "
                            + "write 'CLOSED:default(n)' (retry up to n times, then give up with a 'break'), or give the type an 'impl default match @lock' policy, or give up at once "
                            + "with 'break'/'return'/'throw'");
        }
        if (!closedCaseTerminates(closedCase.childs)) {
            throw new CompilerException("type", closedCase.file, closedCase.line,
                    "the 'CLOSED' case of 'match @lock' must end, on every path, in 'continue' (retry the "
                            + "acquire), 'break' (give up without the lock), 'return' or 'throw' -- it "
                            + "cannot fall off its end");
        }
        closedCase.destructOnExit = collectNaturalEndDestruct(closedCase.childs, closedBodyScope, closedBodyScope);

        preState.clear();
        preState.addAll(openBranchMoved);
        preState.addAll(closedBranchMoved);

        // Synthesized "got it, stop spinning" exit after the OPEN body (dead code if the body already ends in return/break); its unlockOnExit includes `releaseAssign`.
        Token breakTok = new Token(TokenType.KEYWORD, "break", stmt.line, stmt.file);
        // Only the owns locals declared INSIDE the OPEN body die at this exit (stop at the spin loop's own boundary scope, like a
        // user-written `break` does). Walking on to `scope` also destroyed the enclosing function's owns locals (e.g. the matched
        // handle itself) before the lock release below wrote through them.
        breakTok.destructOnExit = collectOwnsToDestruct(openBodyScope, loopBodyScope, null);
        breakTok.unlockOnExit = collectLockReleasesToBoundary(openBodyScope, scope);
        List<Token> openBranchChilds = new ArrayList<>(openCase.childs);
        openBranchChilds.add(wrapAsCheckedLine(breakTok));

        Token ifTok = new Token(TokenType.KEYWORD, "if", stmt.line, stmt.file);
        ifTok.sub = new ArrayList<>();
        ifTok.sub.add(condOp);
        ifTok.childs = openBranchChilds;
        ifTok.destructOnExit = openCase.destructOnExit;

        Token elseTok = new Token(TokenType.KEYWORD, "else", stmt.line, stmt.file);
        elseTok.sub = new ArrayList<>();
        elseTok.childs = closedCase.childs;
        elseTok.destructOnExit = closedCase.destructOnExit;
        ifTok.right = elseTok;

        // Mutate `stmt` in place into a real `loop`; BytecodeEmitter's ordinary loop/if/break/=/swap emission does the rest.
        stmt.text = "loop";
        stmt.isLockSpinLoop = true;
        stmt.isCaseMatch = false;
        stmt.sub = new ArrayList<>();
        stmt.childs = new ArrayList<>();
        stmt.childs.add(wrapAsCheckedLine(ifTok));
        stmt.destructOnExit = new ArrayList<>();
    }

    /** What `substituteDefaultLockMatchPolicyIfPresent` returns: the pre-loop `let` assigns (Token.preLoopInit) and the Scope they were declared in. */
    private static class DefaultPolicySubstitution {
        final List<Token> preLoopInit;
        final Scope preLoopScope;
        /** Most retries of the spin: > 0 a literal limit, -1 a run-time value (0 = not known). */
        long spinBound;
        DefaultPolicySubstitution(List<Token> preLoopInit, Scope preLoopScope) {
            this.preLoopInit = preLoopInit;
            this.preLoopScope = preLoopScope;
        }
        DefaultPolicySubstitution withBound(long b) {
            this.spinBound = b;
            return this;
        }
    }

    /** The value of an integer literal token (underscores, 0x/0b allowed), or -1 when it is not a literal or does not fit. */
    private static long literalLimit(Token t) {
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

    /** Returns the lone expression of a `CLOSED:default` / `CLOSED:default(args)` body (bare VARREF `default` or a CALL on it), or null for an ordinary body. */
    private Token detectDefaultPolicyRef(Token closedCase) {
        if (closedCase.childs.size() != 1) {
            return null;
        }
        Token lineTok = closedCase.childs.get(0);
        if (lineTok.type != TokenType.LINE || lineTok.childs.size() != 1) {
            return null;
        }
        Token expr = lineTok.childs.get(0);
        if (expr.type == TokenType.VARREF && expr.text.equals("default")) {
            return expr;
        }
        if (expr.type == TokenType.OPERATOR && expr.text.equals("CALL") && expr.left != null
                && expr.left.type == TokenType.VARREF && expr.left.text.equals("default")) {
            return expr;
        }
        return null;
    }

    /**
     * Replaces a `CLOSED:default[(args)]` body with a real one built from the struct's registered `impl default match @lock` policy (or the built-in
     * attempt-limit form when none is registered); returns the pre-loop-init pieces, or null if the body is ordinary.
     * The new body is left unchecked: the caller's checkLinesInScope checks it. See docs/TypeChecker.md, "CLOSED:default substitution".
     */
    private DefaultPolicySubstitution substituteDefaultLockMatchPolicyIfPresent(Token closedCase,
            StructInfo structInfo, Scope scope, Scope loopBodyScope, FuncInfo func) {
        Token defaultRef = detectDefaultPolicyRef(closedCase);
        if (defaultRef == null) {
            return null;
        }
        DefaultLockMatchPolicy policy = structInfo.defaultLockMatchPolicy;
        if (policy == null) {
            // No policy registered: the built-in one. `CLOSED:default(n)` retries up to n times (attempt numbers 1..n), then gives up with a `break`.
            if (defaultRef.type != TokenType.OPERATOR || !defaultRef.text.equals("CALL")) {
                throw new CompilerException("type", defaultRef.file, defaultRef.line,
                        "'" + structInfo.name + "' has no registered 'impl default match @lock' policy, so 'CLOSED:default' needs its attempt limit: "
                                + "write 'CLOSED:default(n)' (retry up to n times, then give up)");
            }
            List<Token> builtinArgs = new ArrayList<>();
            Token builtinArgsDelineator = defaultRef.right;
            if (!builtinArgsDelineator.childs.isEmpty()) {
                collectCommaArgs(builtinArgsDelineator.childs.get(0), builtinArgs);
            }
            if (builtinArgs.size() != 1) {
                throw new CompilerException("type", defaultRef.file, defaultRef.line,
                        "'CLOSED:default(...)' with no registered 'impl default match @lock' policy takes exactly one argument, the attempt limit -- got " + builtinArgs.size());
            }
            TypeInfo limitType = resolveExprType(builtinArgs.get(0), scope, func);
            if (!typesCompatible(new TypeInfo(null, "imut", "u64"), limitType)) {
                throw new CompilerException("type", builtinArgs.get(0).file, builtinArgs.get(0).line,
                        "the attempt limit of 'CLOSED:default(...)' must be a u64, got '" + limitType.canonical() + "'");
            }
            Scope builtinScope = new Scope(loopBodyScope);
            List<Token> builtinInit = new ArrayList<>();
            builtinInit.add(buildCheckedLetAssign("$lm_tries",
                    wrapWithMutability(new Token(TokenType.INTEGER, "0", defaultRef.line, defaultRef.file), "mut"), builtinScope, func));
            builtinInit.add(buildCheckedLetAssign("$lm_limit", wrapWithMutability(builtinArgs.get(0), "imut"), builtinScope, func));
            List<Token> builtinBody = new ArrayList<>();
            builtinBody.add(wrapAsCheckedLine(buildTriesIncrement(defaultRef)));
            builtinBody.add(wrapAsCheckedLine(buildGiveUpCheck(defaultRef, new Token(TokenType.VARREF, "$lm_limit", defaultRef.line, defaultRef.file))));
            builtinBody.add(wrapAsCheckedLine(new Token(TokenType.KEYWORD, "continue", defaultRef.line, defaultRef.file)));
            closedCase.childs = builtinBody;
            return new DefaultPolicySubstitution(builtinInit, builtinScope).withBound(literalLimit(builtinArgs.get(0)));
        }
        List<Token> callArgNodes = new ArrayList<>();
        if (defaultRef.type == TokenType.OPERATOR && defaultRef.text.equals("CALL")) {
            Token argsDelineator = defaultRef.right;
            if (!argsDelineator.childs.isEmpty()) {
                collectCommaArgs(argsDelineator.childs.get(0), callArgNodes);
            }
        }
        List<NameType> policyParams = parseParamList(policy.rawParamsTok.childs);
        int requiredArgCount = policyParams.size() - 1;
        if (callArgNodes.size() != requiredArgCount) {
            throw new CompilerException("type", defaultRef.file, defaultRef.line,
                    "'CLOSED:default(...)' for '" + policy.structName + "' requires exactly " + requiredArgCount
                            + " argument(s) -- its own first parameter ('" + policyParams.get(0).name
                            + "') is always the automatic, per-iteration counter, never supplied here -- got "
                            + callArgNodes.size());
        }

        Scope preLoopScope = new Scope(loopBodyScope);
        List<Token> preLoopInit = new ArrayList<>();
        String triesLocalName = "$lm_tries";
        Token triesInitAssign = buildCheckedLetAssign(triesLocalName,
                wrapWithMutability(new Token(TokenType.INTEGER, "0", defaultRef.line, defaultRef.file), "mut"),
                preLoopScope, func);
        preLoopInit.add(triesInitAssign);

        for (int p = 1; p < policyParams.size(); p++) {
            NameType param = policyParams.get(p);
            Token argExpr = callArgNodes.get(p - 1);
            TypeInfo argType = resolveExprType(argExpr, scope, func);
            if (!typesCompatible(param.type, argType)) {
                throw new CompilerException("type", argExpr.file, argExpr.line,
                        "'CLOSED:default(...)' argument " + p + " for '" + policy.structName + "' must be '"
                                + param.type.canonical() + "', got '" + argType.canonical() + "'");
            }
            // Bound once before the loop under its declared name; the cloned policy body refers to it by that name.
            Token wrappedArg = wrapWithMutability(argExpr, param.type.mutability);
            Token configAssign = buildCheckedLetAssign(param.name, wrappedArg, preLoopScope, func);
            preLoopInit.add(configAssign);
        }

        List<Token> clonedBodyLines = new ArrayList<>();
        for (Token lineTok : policy.rawBodyLines) {
            clonedBodyLines.add(lineTok.deepCloneRaw());
        }
        clonedBodyLines = new Parser().gatherStatementLines(clonedBodyLines);
        new RpnConverter().convertLines(clonedBodyLines);
        new TreeBuilder().buildLines(clonedBodyLines);

        List<Token> substitutedChilds = new ArrayList<>();
        Token triesIncrement = new Token(TokenType.OPERATOR, "++", defaultRef.line, defaultRef.file);
        triesIncrement.unary = true;
        triesIncrement.left = new Token(TokenType.VARREF, triesLocalName, defaultRef.line, defaultRef.file);
        substitutedChilds.add(wrapAsCheckedLine(triesIncrement));

        Token triesRebindLet = new Token(TokenType.KEYWORD, "let", defaultRef.line, defaultRef.file);
        triesRebindLet.childs = new ArrayList<>();
        triesRebindLet.childs.add(new Token(TokenType.VARREF, policyParams.get(0).name, defaultRef.line,
                defaultRef.file));
        Token triesRebindAssign = new Token(TokenType.OPERATOR, "=", defaultRef.line, defaultRef.file);
        triesRebindAssign.left = triesRebindLet;
        triesRebindAssign.right = wrapWithMutability(
                new Token(TokenType.VARREF, triesLocalName, defaultRef.line, defaultRef.file), "imut");
        substitutedChilds.add(wrapAsCheckedLine(triesRebindAssign));
        // The compiler, not the policy, enforces the bound: after the counter is bumped, `if $lm_tries > <limit>{ break }` gives up. The limit is the
        // policy's second parameter when that is a u64 ("tries, limit"), else LOCK_SPIN_LIMIT. A policy that gives up earlier (`if tries >= limit{ break }`)
        // still does; the body runs for attempts 1..limit.
        Token limitExpr = policyParams.size() >= 2 && "u64".equals(policyParams.get(1).type.baseType)
                ? new Token(TokenType.VARREF, policyParams.get(1).name, defaultRef.line, defaultRef.file)
                : new Token(TokenType.INTEGER, String.valueOf(LOCK_SPIN_LIMIT), defaultRef.line, defaultRef.file);
        substitutedChilds.add(wrapAsCheckedLine(buildGiveUpCheck(defaultRef, limitExpr)));

        substitutedChilds.addAll(clonedBodyLines);
        // The policy body keeps its old meaning "falling off the end
        // retries": add the explicit 'continue' the CLOSED case now needs
        // unless the policy already ends every path itself.
        if (!closedCaseTerminates(substitutedChilds)) {
            Token retry = new Token(TokenType.KEYWORD, "continue", defaultRef.line, defaultRef.file);
            substitutedChilds.add(wrapAsCheckedLine(retry));
        }
        closedCase.childs = substitutedChilds;

        long bound = LOCK_SPIN_LIMIT;       // a policy without a u64 `limit` parameter is stopped at LOCK_SPIN_LIMIT
        if (policyParams.size() >= 2 && "u64".equals(policyParams.get(1).type.baseType)) {
            bound = literalLimit(callArgNodes.get(0));
        }
        return new DefaultPolicySubstitution(preLoopInit, preLoopScope).withBound(bound);
    }

    /** The attempt limit used when a registered `impl default match @lock` policy has no u64 `limit` parameter. */
    static final long LOCK_SPIN_LIMIT = 1_000_000_000L;

    /** True for the `@gt_*` functions (the ghost table's own lock code), which keep an unbounded spin. */
    private boolean isGhostTableFunction(FuncInfo func) {
        if (func == null || func.funcToken == null || func.funcToken.decorators == null) {
            return false;
        }
        for (String d : new String[] { "gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve" }) {
            if (getDecorator(func.funcToken.decorators, d) != null) {
                return true;
            }
        }
        return false;
    }

    /** `$lm_tries++` */
    private Token buildTriesIncrement(Token at) {
        Token inc = new Token(TokenType.OPERATOR, "++", at.line, at.file);
        inc.unary = true;
        inc.left = new Token(TokenType.VARREF, "$lm_tries", at.line, at.file);
        return inc;
    }

    /** `if $lm_tries > limit{ break }` -- raw, checked later with the rest of the CLOSED body. */
    private Token buildGiveUpCheck(Token at, Token limit) {
        Token cmp = new Token(TokenType.OPERATOR, ">", at.line, at.file);
        cmp.left = new Token(TokenType.VARREF, "$lm_tries", at.line, at.file);
        cmp.right = limit;
        Token ifTok = new Token(TokenType.KEYWORD, "if", at.line, at.file);
        ifTok.sub = new ArrayList<>();
        ifTok.sub.add(cmp);
        ifTok.childs = new ArrayList<>();
        ifTok.childs.add(wrapAsCheckedLine(new Token(TokenType.KEYWORD, "break", at.line, at.file)));
        return ifTok;
    }

    /** Wraps `expr` in a raw `mut`/`imut` prefix operator to make a synthesized value's mutability concrete before a `let`. */
    private Token wrapWithMutability(Token expr, String mutability) {
        Token wrapper = new Token(TokenType.OPERATOR, mutability, expr.line, expr.file);
        wrapper.left = expr;
        return wrapper;
    }

    /**
     * The proofs a `@lock(..)` method may assume inside its own body (so it needs no `match` of its own). Only for non-static impl methods: the first
     * parameter is the receiver, whatever it is called. See docs/TypeChecker.md, "Method lock proofs".
     */
    private List<Token.MatchPattern> assumedLockPatterns(FuncInfo func) {
        List<Token.MatchPattern> result = new ArrayList<>();
        if (func.funcToken.decorators == null || func.enclosingImpl == null || func.funcToken.isStaticMethod
                || func.paramNames.isEmpty()) {
            return result;
        }
        String selfParamName = func.paramNames.get(0);
        for (Token.Decorator d : func.funcToken.decorators) {
            if (!d.name.equals("lock")) {
                continue;
            }
            if (d.lockFieldName != null) {
                result.add(new Token.MatchPattern(selfParamName, "enum_field_variants", d.lockFieldName,
                        d.lockVariants));
            } else if (d.lockInParams != null) {
                for (int j = 0; j < d.lockInParams.size(); j++) {
                    // `in`/`within` grant the read-only "in" kind, `into` the write-capable "into" kind (see checkMatchCondition).
                    String kind = d.lockInOperators.get(j).equals("into") ? "into" : "in";
                    String paramName = d.lockInParams.get(j);
                    String fieldName = d.lockInFields.get(j);
                    String targetSlot = selfParamName + "." + fieldName;
                    result.add(new Token.MatchPattern(paramName, kind, null, targetSlot));
                    // `Some(..)`-wrapped: also grant the element-alive proof for `self.field[param]` (as checkSomeIndexBoundsCondition does).
                    if (d.lockInSomeWrapped != null && Boolean.TRUE.equals(d.lockInSomeWrapped.get(j))) {
                        result.add(new Token.MatchPattern(targetSlot + "[" + paramName + "]", "alive", null));
                    }
                }
            }
        }
        return result;
    }

    /** Runs a pure proof check; in unsafe context a failure is not an error but records that the enclosing block needed `tag`. */
    private void checkOrBypass(Scope scope, String tag, Runnable check) {
        try {
            check.run();
        } catch (CompilerException e) {
            if (unsafeBypass(scope, tag)) {
                return;
            }
            throw e;
        }
    }

    /** At a call to a `@lock(..)` method: requires the matching proofs at the call site (skipped in unsafe). `argNodes` includes the receiver as its first element, like candidate.paramTypes. See docs/TypeChecker.md, "Method lock proofs". */
    private void requireMethodLockProofs(FuncInfo candidate, Token receiverNode, List<Token> argNodes, Scope scope,
            Token at) {
        if (candidate.funcToken.decorators == null) {
            return;
        }
        String receiverSlot = slotKeyOf(receiverNode);
        for (Token.Decorator d : candidate.funcToken.decorators) {
            if (!d.name.equals("lock")) {
                continue;
            }
            if (d.lockFieldName != null) {
                if (!hasEnumFieldProof(scope, receiverSlot, d.lockFieldName, d.lockVariants)) {
                    throw new CompilerException("type", at.file, at.line,
                            "calling '" + candidate.name + "' requires a 'match "
                                    + (receiverSlot != null ? receiverSlot : "...") + "." + d.lockFieldName
                                    + "{...}' proof first (it's decorated '@lock(match self."
                                    + d.lockFieldName + " : ...)')");
                }
            } else if (d.lockInParams != null) {
                for (int j = 0; j < d.lockInParams.size(); j++) {
                    String paramName = d.lockInParams.get(j);
                    String fieldName = d.lockInFields.get(j);
                    // The written operator only changes the message. An `in`/`within` clause accepts an "in" or "into" proof; an `into` clause needs an "into" proof (exact match).
                    String operatorText = d.lockInOperators.get(j);
                    String requiredKind = operatorText.equals("into") ? "into" : "in";
                    int paramIdx = candidate.paramNames.indexOf(paramName);
                    Token argExpr = paramIdx >= 0 && paramIdx < argNodes.size() ? argNodes.get(paramIdx) : null;
                    // boundsSlotKeyOf, not slotKeyOf: a literal index ("match 0 into thr.backing{ thr.set(thr, 0, v) }") is provable too
                    String argSlot = argExpr != null ? boundsSlotKeyOf(argExpr) : null;
                    String targetSlot = receiverSlot != null ? receiverSlot + "." + fieldName : null;
                    boolean proven = argSlot != null && targetSlot != null && scope.activeMatchPatterns.stream()
                            .anyMatch(p -> !p.invalidated && (p.kind.equals(requiredKind)
                                    || (requiredKind.equals("in") && p.kind.equals("into")))
                                    && argSlot.equals(p.slotKey) && targetSlot.equals(p.rightSlotKey));
                    if (!proven) {
                        throw new CompilerException("type", at.file, at.line,
                                "calling '" + candidate.name + "' requires a 'match "
                                        + (argSlot != null ? argSlot : "...") + " " + operatorText + " "
                                        + (targetSlot != null ? targetSlot : "...") + "{...}' proof first "
                                        + "(it's decorated '@lock(match " + paramName + " " + operatorText
                                        + " self." + fieldName + ")')");
                    }
                    // A `Some(..)`-wrapped clause also needs the element-alive proof for `self.field[param]`.
                    if (d.lockInSomeWrapped != null && Boolean.TRUE.equals(d.lockInSomeWrapped.get(j))) {
                        String elemSlot = targetSlot != null && argSlot != null
                                ? targetSlot + "[" + argSlot + "]" : null;
                        boolean elemProven = elemSlot != null && scope.activeMatchPatterns.stream()
                                .anyMatch(p -> !p.invalidated && p.kind.equals("alive") && elemSlot.equals(p.slotKey));
                        if (!elemProven) {
                            throw new CompilerException("type", at.file, at.line,
                                    "calling '" + candidate.name + "' requires a 'match Some("
                                            + (argSlot != null ? argSlot : "...") + ") " + operatorText + " "
                                            + (targetSlot != null ? targetSlot : "...") + "{...}' proof first "
                                            + "(it's decorated '@lock(match Some(" + paramName + ") "
                                            + operatorText + " self." + fieldName + ")')");
                        }
                    }
                }
            }
        }
    }

    /** True if `scope` has an active enum_field_variants proof for `slotKey.fieldName` whose proven variants are all within `requiredVariants`. */
    private boolean hasEnumFieldProof(Scope scope, String slotKey, String fieldName, List<String> requiredVariants) {
        if (slotKey == null) {
            return false;
        }
        return scope.activeMatchPatterns.stream()
                .anyMatch(p -> !p.invalidated && p.kind.equals("enum_field_variants") && p.slotKey.equals(slotKey)
                        && p.targetName.equals(fieldName) && requiredVariants.containsAll(p.provenVariants));
    }

    /** A member other than the lock discriminant field needs an active enum-field proof for this receiver (planted by checkEnumMatch); the discriminant is freely readable; unsafe bypasses. */
    private void requireStructLockProof(StructInfo structInfo, String memberName, Token receiverNode, Scope scope,
            Token at) {
        if (structInfo.lockFieldName == null || memberName.equals(structInfo.lockFieldName) || synthesizingGlueBody) {
            return;
        }
        String slotKey = slotKeyOf(receiverNode);
        boolean proven = hasEnumFieldProof(scope, slotKey, structInfo.lockFieldName, structInfo.lockVariants);
        if (!proven) {
            String slotDisplay = slotKey != null ? slotKey : "...";
            throw new CompilerException("type", at.file, at.line,
                    "'" + memberName + "' requires a 'match " + slotDisplay + "." + structInfo.lockFieldName
                            + "{...}' proof first (this struct is decorated '@lock(match self."
                            + structInfo.lockFieldName + " : ...)')"
                            + (structInfo.swapFieldName != null && structInfo.swapFieldName.equals(structInfo.lockFieldName)
                                    ? " -- or take the lock: 'match @lock " + slotDisplay + "{ OPEN:{...} CLOSED:{...} }'" : ""));
        }
    }

    private void collectCommaArgs(Token node, List<Token> out) {
        if (node.type == TokenType.OPERATOR && node.text.equals(",")) {
            collectCommaArgs(node.left, out);
            collectCommaArgs(node.right, out);
        } else {
            out.add(node);
        }
    }

    /** `[a, b, ..]`: flattens the comma chain and requires one element base type; the type string embeds the length (`T[n]`). `dup` is already expanded by DupExpander. */
    private TypeInfo checkArrayLiteral(Token node, Scope scope, FuncInfo func) {
        if (node.childs.isEmpty()) {
            throw new CompilerException("type", node.file, node.line,
                    "array literal must have at least one element");
        }
        List<Token> elements = new ArrayList<>();
        collectCommaArgs(node.childs.get(0), elements);
        if (elements.isEmpty()) {
            throw new CompilerException("type", node.file, node.line,
                    "array literal must have at least one element");
        }

        // null alone gives `null[n]`; next to a non-null element the type comes from that element and null is accepted wherever a pointer is.
        List<TypeInfo> elementTypes = new ArrayList<>();
        TypeInfo establishedType = null;
        for (Token element : elements) {
            TypeInfo t = resolveExprType(element, scope, func);
            elementTypes.add(t);
            if (establishedType == null && !t.baseType.equals("null")) {
                establishedType = t;
            }
        }
        // A literal alongside a typed (non-literal) integer element takes that element's type when its value fits: `[c, c + 1, 7]`
        // with `c: u16` is a `u16[3]`, exactly as `c + 7` would be. (Two typed elements of different types still mismatch below.)
        TypeInfo typedInt = null;
        for (TypeInfo t : elementTypes) {
            if (t.literalValue == null && t.storage == null && INTEGER_WIDTH.containsKey(t.baseType)) {
                typedInt = t;
                break;
            }
        }
        if (typedInt != null) {
            boolean adaptable = true;
            for (TypeInfo t : elementTypes) {
                if (t.literalValue != null && (t.storage != null || !literalFits(t.literalValue, typedInt.baseType))) {
                    adaptable = false;
                }
            }
            if (adaptable) {
                TypeInfo slot = new TypeInfo(null, "indeterminate", typedInt.baseType);
                for (int i = 0; i < elements.size(); i++) {
                    if (elementTypes.get(i).literalValue != null) {
                        adaptLiteral(elements.get(i), slot);
                        elementTypes.set(i, new TypeInfo(null, elementTypes.get(i).mutability, typedInt.baseType));
                    }
                }
                establishedType = typedInt;
            }
        }
        // All-integer-literal arrays may mix `u64` and negated (`s64`) literals ([-5, 300]): they settle on
        // s64 when any element is negative, and each element node is retagged to match.
        boolean allIntLiterals = !elementTypes.isEmpty();
        boolean anyNegative = false;
        for (TypeInfo t : elementTypes) {
            if (t.literalValue == null || t.storage != null) {
                allIntLiterals = false;
                break;
            }
            anyNegative |= t.literalValue.signum() < 0;
        }
        if (allIntLiterals && anyNegative) {
            TypeInfo unified = new TypeInfo(null, "indeterminate", "s64");
            for (int i = 0; i < elements.size(); i++) {
                if (!elementTypes.get(i).baseType.equals("s64") && literalFits(elementTypes.get(i).literalValue, "s64")) {
                    adaptLiteral(elements.get(i), unified);
                    TypeInfo retyped = new TypeInfo(null, elementTypes.get(i).mutability, "s64");
                    retyped.literalValue = elementTypes.get(i).literalValue;
                    elementTypes.set(i, retyped);
                }
            }
            establishedType = elementTypes.get(0);
        }
        if (establishedType == null) {
            // every element is null: the poisoned case, valid.
            establishedType = elementTypes.get(0);
        } else if (!isPointerType(establishedType)) {
            // A non-pointer element type needs every element to match; null cannot stand in.
            for (int i = 0; i < elementTypes.size(); i++) {
                TypeInfo t = elementTypes.get(i);
                if (t.baseType.equals("null")) {
                    throw new CompilerException("type", elements.get(i).file, elements.get(i).line,
                            "'null' cannot be used as an array element alongside a non-pointer element "
                                    + "of type '" + establishedType.canonical() + "'");
                }
                if (!t.baseType.equals(establishedType.baseType)) {
                    throw new CompilerException("type", elements.get(i).file, elements.get(i).line,
                            "array elements must all be the same type, got '" + establishedType.baseType
                                    + "' and '" + t.baseType + "'");
                }
            }
        } else {
            for (int i = 0; i < elementTypes.size(); i++) {
                TypeInfo t = elementTypes.get(i);
                if (t.baseType.equals("null")) {
                    if (establishedType.isSome) {
                        // A null literal cannot satisfy a `some` element type (third site of this rule, besides typesCompatible and the bound-type `let` path).
                        throw new CompilerException("type", elements.get(i).file, elements.get(i).line,
                                "'null' cannot satisfy a 'some'-tagged array element type ('"
                                        + establishedType.canonical() + "')");
                    }
                    continue; // null satisfies any (non-'some') pointer-typed slot
                }
                if (!t.baseType.equals(establishedType.baseType)) {
                    throw new CompilerException("type", elements.get(i).file, elements.get(i).line,
                            "array elements must all be the same type, got '" + establishedType.baseType
                                    + "' and '" + t.baseType + "'");
                }
            }
        }

        if ("owns".equals(establishedType.storage) || isInlineOwningStruct(establishedType)) {
            for (int i = 0; i < elements.size(); i++) {
                markMovedIfOwned(establishedType, elements.get(i), scope);
            }
        }

        // TypeInfo.array keeps the real element type (storage included) in arrayElementType so a later `[]` can hand back a pointer element.
        TypeInfo arrayType = TypeInfo.array(establishedType, elements.size());
        boolean allLiteral = establishedType.storage == null && INTEGER_WIDTH.containsKey(establishedType.baseType);
        List<java.math.BigInteger> values = new ArrayList<>();
        for (TypeInfo t : elementTypes) {
            if (t.literalValue == null) {
                allLiteral = false;
                break;
            }
            values.add(t.literalValue);
        }
        if (allLiteral) {
            arrayType.literalElements = values;
        }
        // A nested all-integer-literal array ([[1,2,3],[4,5,6]]): the rows' values, flattened, so the whole literal adapts to a `T[n][m]` slot.
        if (!allLiteral && !elementTypes.isEmpty() && establishedType.storage == null && establishedType.baseType.indexOf('[') > 0) {
            boolean nested = true;
            List<java.math.BigInteger> flat = new ArrayList<>();
            for (TypeInfo t : elementTypes) {
                if (t.literalElements == null) {
                    nested = false;
                    break;
                }
                flat.addAll(t.literalElements);
            }
            if (nested) {
                arrayType.literalElements = flat;
            }
        }
        if (!elementTypes.isEmpty() && establishedType.storage == null && establishedType.baseType.indexOf('[') > 0) {
            boolean nestedF = true;
            for (TypeInfo t : elementTypes) {
                if (!t.floatArrayLiteral) {
                    nestedF = false;
                    break;
                }
            }
            if (nestedF) {
                arrayType.floatArrayLiteral = true;
            }
        }
        boolean allFloatLits = !elementTypes.isEmpty() && establishedType.storage == null && isFloatType(establishedType.baseType);
        for (TypeInfo t : elementTypes) {
            if (!t.floatLiteral) {
                allFloatLits = false;
                break;
            }
        }
        if (allFloatLits) {
            arrayType.floatArrayLiteral = true;
        }
        return arrayType;
    }

    /** True if `structName` is decorated `@untyped` (no hidden class id, so no runtime type information). */
    private boolean isUntyped(String structName) {
        StructInfo si = structs.get(structName);
        return si != null && getDecorator(si.declTok.decorators, "untyped") != null;
    }

    /**
     * "ifaceValue instanceof StructName": runtime class-id (`___type`) compare. Left side must be
     * interface-typed, right side a struct name. See docs/TypeChecker.md, "instanceof and implements".
     */
    private TypeInfo checkInstanceof(Token op, Scope scope, FuncInfo func) {
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        // Interface-typed left side only: narrows to one of the interface's implementers. @untyped is
        // skipped safely (interfaces are absent from 'structs'); the alive-proof gate below treats
        // interface-typed values as pointer-like already.
        if (!interfaces.containsKey(leftType.baseType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'instanceof' can only be used on an interface-typed value ('x instanceof SomeStruct'), got '"
                            + leftType.canonical() + "'");
        }
        // Same alive-proof gate as '.' access (checkDot); applies whether this is an expression or a
        // match condition. 'unsafe' does not bypass it (known quirk, left as is).
        if (requiresAliveProof(leftType, op.left, scope)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'instanceof' on a pointer-typed struct value is not permitted -- the type system "
                            + "can't yet confirm it isn't null ('match Some(...)' will be able to prove "
                            + "this) -- only an inline (no-storage), 'auto', or 'some'-tagged struct value "
                            + "supports 'instanceof' without one");
        }
        if (isUntyped(leftType.baseType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'" + leftType.baseType + "' is '@untyped' -- it carries no runtime type "
                            + "information, so it can never be the left side of 'instanceof'");
        }
        if (op.right.type != TokenType.VARREF) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "expected a struct name on the right of 'instanceof'");
        }
        String targetName = op.right.text;
        if (!structs.containsKey(targetName)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'" + targetName + "' is not a known struct");
        }
        if (isUntyped(targetName)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'" + targetName + "' is '@untyped' -- it carries no runtime type information, so "
                            + "nothing can ever be proven to be an instance of it");
        }
        if (!structImplements(targetName, leftType.baseType)) {
            // Only a struct that implements the left side's interface can ever be behind that interface-typed value.
            warn(op.right.file, op.right.line,
                    "'instanceof " + targetName + "' can never be true -- '" + targetName + "' does not implement '"
                            + leftType.baseType + "'");
        }
        op.right.resolvedType = targetName;
        return new TypeInfo(null, "imut", "bool");
    }

    /**
     * "structValue implements InterfaceName" -- the left side must be struct-typed,
     * the right side must name an interface (a statically decidable question).
     */
    private TypeInfo checkImplementsOperator(Token op, Scope scope, FuncInfo func) {
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        // Interface-typed left operands are legal too (mirror of checkInstanceof).
        if (!structs.containsKey(leftType.baseType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'implements' can only be used on a struct-typed value ('x implements SomeInterface'), got '"
                            + leftType.canonical() + "'");
        }
        // Same alive-proof gate as checkInstanceof.
        if (requiresAliveProof(leftType, op.left, scope)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'implements' on a pointer-typed struct value is not permitted -- the type system "
                            + "can't yet confirm it isn't null ('match Some(...)' will be able to prove "
                            + "this) -- only an inline (no-storage), 'auto', or 'some'-tagged struct value "
                            + "supports 'implements' without one");
        }
        if (isUntyped(leftType.baseType)) {
            throw new CompilerException("type", op.left.file, op.left.line,
                    "'" + leftType.baseType + "' is '@untyped' -- it carries no runtime type "
                            + "information, so it can never be the left side of 'implements'");
        }
        if (op.right.type != TokenType.VARREF) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "expected an interface name on the right of 'implements'");
        }
        String targetName = op.right.text;
        if (!interfaces.containsKey(targetName)) {
            throw new CompilerException("type", op.right.file, op.right.line,
                    "'" + targetName + "' is not a known interface");
        }
        // Warning, not error: an interface nothing implements. `implsByConcreteType` is complete before
        // bodies are checked, so this is a whole-program fact. See docs/TypeChecker.md, "instanceof and implements".
        if (!hasAnyImplementer(targetName)) {
            warn(op.right.file, op.right.line,
                    "'implements " + targetName + "' can never be true -- no struct anywhere in this "
                            + "program implements '" + targetName + "'");
        } else if (!structImplements(leftType.baseType, targetName)) {
            // The left side is a struct-typed value, so its type is known: some other struct implements the interface, this one does not.
            warn(op.right.file, op.right.line,
                    "'implements " + targetName + "' can never be true -- '" + leftType.baseType + "' does not implement '"
                            + targetName + "'");
        }
        op.right.resolvedType = targetName;
        return new TypeInfo(null, "imut", "bool");
    }

    /** Whether `structName` has an `impl interfaceName for structName` block or declares `implements interfaceName`. */
    private boolean structImplements(String structName, String interfaceName) {
        List<ImplInfo> impls = implsByConcreteType.get(structName);
        if (impls != null) {
            for (ImplInfo impl : impls) {
                if (interfaceName.equals(impl.interfaceName)) {
                    return true;
                }
            }
        }
        StructInfo si = structs.get(structName);
        return si != null && si.implementsNames.contains(interfaceName);
    }

    /** Whether any `impl interfaceName for X` exists in the program (same scan as `generateEnumForEnums`). */
    private boolean hasAnyImplementer(String interfaceName) {
        for (List<ImplInfo> implList : implsByConcreteType.values()) {
            for (ImplInfo impl : implList) {
                if (interfaceName.equals(impl.interfaceName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Non-fatal diagnostic: prints "[warning] file:line - message" to stderr and checking continues.
     * No collection/counting machinery on purpose.
     */
    private static void warn(String file, int line, String message) {
        StringBuilder sb = new StringBuilder("[warning] ");
        if (file != null) {
            sb.append(file);
            if (line > 0) {
                sb.append(":").append(line);
            }
            sb.append(" - ");
        }
        sb.append(message);
        System.err.println(sb);
    }

    private String describeTypes(List<TypeInfo> types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            TypeInfo t = types.get(i);
            if (t.literalValue != null && t.storage == null) {
                sb.append("integer literal ").append(t.literalValue).append(" (fits only ")
                        .append(fittingTypes(t.literalValue)).append(")");
            } else {
                sb.append(t.baseType);
            }
        }
        return sb.toString();
    }

    /** Names the integer types that can hold `v`, for "no overload matches" messages about a literal argument. */
    private static String fittingTypes(java.math.BigInteger v) {
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"u8", "s8", "u16", "s16", "u32", "s32", "u64", "s64"}) {
            if (literalFits(v, name)) {
                sb.append(sb.length() == 0 ? "" : ", ").append(name);
            }
        }
        return sb.length() == 0 ? "no integer type" : sb.toString();
    }

    /**
     * "[]" default rules: a lookup needs a compile-time-known target length and a literal in-range index,
     * or a live `match i in` proof. See docs/TypeChecker.md, "Lookup".
     */
    private TypeInfo checkLookup(Token op, Scope scope, FuncInfo func) {
        TypeInfo targetType = resolveExprType(op.left, scope, func);
        if (isUnsafeDynArrayType(targetType)) {
            // Unsafe dynarray: needs 'unsafe', unchecked (no hidden length). Never sets
            // `op.isLookupProofReliant`, so writes need no "into" proof. See docs/TypeChecker.md, "Lookup".
            if (!unsafeBypass(scope, udynTag(targetType))) {
                throw new CompilerException("type", op.file, op.line,
                        "'[]' on an 'unsafe dynarray' can only be used from within 'unsafe' code -- it "
                                + "has no bounds-proof mechanism at all, safe or otherwise");
            }
            Token indexDelineatorUnsafe = op.right;
            if (indexDelineatorUnsafe.childs.isEmpty()) {
                throw new CompilerException("type", op.file, op.line, "'[]' is missing an index expression");
            }
            Token indexExprUnsafe = indexDelineatorUnsafe.childs.get(0);
            TypeInfo indexTypeUnsafe = resolveExprType(indexExprUnsafe, scope, func);
            if (!INTEGER_TYPES.contains(indexTypeUnsafe.baseType)) {
                throw new CompilerException("type", op.file, op.line,
                        "'unsafe dynarray' index must be an integer type, got '"
                                + indexTypeUnsafe.canonical() + "'");
            }
            // Keep element storage (owns/ref) as the safe cases below do.
            TypeInfo unsafeElem = targetType.unsafeDynArrayElementType;
            if (unsafeElem.storage != null) {
                return unsafeElem.withMutability(targetType.mutability);
            }
            return new TypeInfo(null, targetType.mutability, unsafeElem.baseType);
        }
        boolean isArray = isArrayType(targetType.baseType);
        boolean isString = targetType.baseType.equals("string");
        boolean isDynArray = isDynArrayType(targetType);
        if (!isString && !isArray && !isDynArray) {
            throw new CompilerException("type", op.file, op.line,
                    "'[]' (LOOKUP) is only supported on strings, arrays, and dynarrays, got '"
                            + targetType.canonical() + "'");
        }

        Token indexDelineator = op.right;
        if (indexDelineator.childs.isEmpty()) {
            throw new CompilerException("type", op.file, op.line, "'[]' is missing an index expression");
        }
        Token indexExpr = indexDelineator.childs.get(0);

        // A dynarray length is never compile-time known: even a literal index needs a `match i in` proof.
        Long knownLength = null;
        if (isArray) {
            knownLength = (long) arrayLength(targetType.baseType);
        } else if (isString) {
            if (op.left.type == TokenType.STRING) {
                knownLength = (long) op.left.literalValue.length();
            }
        }
        // A live `match i in range(target)` proof for this exact target lifts the known-length/literal
        // index restrictions (the match already emitted the runtime check).
        boolean provenInBounds = isProvenInBounds(op.left, indexExpr, scope);
        // A literal index into a type-known length needs no proof (see Token.isLookupProofReliant).
        op.isLookupProofReliant = !(indexExpr.type == TokenType.INTEGER && knownLength != null);

        // Resolve and reject a range-typed index (slicing was removed) BEFORE the knownLength bounds
        // error below, or dynarray operands would always hit that generic error first.
        TypeInfo indexType = resolveExprType(indexExpr, scope, func);
        if (isRangeType(indexType)) {
            throw new CompilerException("type", indexExpr.file, indexExpr.line,
                    "slicing ('arr[range]') is no longer supported -- Caspien has removed the slice "
                            + "type; use a scalar index or a range/pointer pair explicitly");
        }

        if (knownLength == null && !provenInBounds) {
            throw new CompilerException("type", op.file, op.line,
                    "'[]' is not permitted here -- the compiler has no way to confirm this access is "
                            + "within bounds yet (a future 'match' statement will be able to prove "
                            + "this; for now, only a target whose length is fully known at compile "
                            + "time can be indexed at all)");
        }

        if (!INTEGER_TYPES.contains(indexType.baseType)) {
            throw new CompilerException("type", op.file, op.line,
                    (isArray ? "array" : isDynArray ? "dynarray" : "string")
                            + " index must be an integer type, got '" + indexType.canonical() + "'");
        }
        if (indexExpr.type != TokenType.INTEGER && !provenInBounds) {
            throw new CompilerException("type", op.file, op.line,
                    "'[]' is not permitted here -- the index must be a literal integer, provably "
                            + "within bounds (no mechanism to confirm this for a variable/computed "
                            + "index yet)");
        }
        if (indexExpr.type == TokenType.INTEGER) {
            long indexValue = Long.parseLong(indexExpr.text);
            if (knownLength != null && indexValue >= knownLength) {
                throw new CompilerException("type", indexExpr.file, indexExpr.line,
                        "index " + indexValue + " is out of bounds for a target of length " + knownLength);
            }
        }
        if (isString) {
            // Default copy behavior, per spec: LOOKUP on a string yields
            // an imut char, not a raw imut char.
            return new TypeInfo(null, "imut", "char");
        }
        if (isDynArray) {
            // Container mutability governs the result; element storage (owns/ref) is preserved.
            TypeInfo dynElem = targetType.dynArrayElementType;
            if (dynElem.storage != null) {
                return dynElem.withMutability(targetType.mutability);
            }
            return new TypeInfo(null, targetType.mutability, dynElem.baseType);
        }
        // Fixed array: storage-bearing element type is only available for array-literal types;
        // annotation-declared arrays ("Box[5]") fall through to the storage-less path.
        if (targetType.arrayElementType != null && targetType.arrayElementType.storage != null) {
            return targetType.arrayElementType.withMutability(targetType.mutability);
        }
        String elementBaseType = arrayElementType(targetType.baseType);
        // Element mutability follows the array's (string case above stays imut by spec).
        if (elementBaseType.equals("string")) {
            // Annotation-declared string array elements must be static, like every other string value
            // (codegen's isPointerTypeString checks storage).
            return new TypeInfo("static", targetType.mutability, "string");
        }
        if (elementBaseType.startsWith("dynarray(") && elementBaseType.endsWith(")")) {
            // Annotation-declared array of dynarrays: rebuild a real dynarray TypeInfo via
            // TypeInfo.dynArray instead of a bare baseType string.
            String innerElementText = elementBaseType.substring(
                    "dynarray(".length(), elementBaseType.length() - 1);
            // innerElementText may carry a storage prefix (e.g. "owns_Leaf"): split it off.
            TypeInfo innerElementType = TypeInfo.parseDynArrayElementText(innerElementText);
            return TypeInfo.dynArray(targetType.mutability, innerElementType);
        }
        return new TypeInfo(null, targetType.mutability, elementBaseType);
    }

    /**
     * Reads literal bounds off a `range(a,b)` base-type string; null unless both are all digits
     * (identifiers never start with a digit). See docs/TypeChecker.md, "Lookup".
     */
    private long[] parseLiteralRangeBounds(String rangeBaseType) {
        if (!rangeBaseType.startsWith("range(") || !rangeBaseType.endsWith(")")) {
            return null;
        }
        String inner = rangeBaseType.substring("range(".length(), rangeBaseType.length() - 1);
        int comma = inner.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String left = inner.substring(0, comma);
        String right = inner.substring(comma + 1);
        if (!isAllDigits(left) || !isAllDigits(right)) {
            return null;
        }
        return new long[]{Long.parseLong(left), Long.parseLong(right)};
    }

    private boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a bare `as` target name for structural comparison only (lighter than
     * resolveTypeAnnotation: no pointer-required validation).
     */
    private TypeInfo resolveCastTargetBare(String name, Token at) {
        if (aliasRawTargets.containsKey(name)) {
            String[] aliasNameHolder = new String[1];
            List<Token> synthetic = new ArrayList<>();
            synthetic.add(new Token(TokenType.VARREF, name, at.line, at.file));
            List<Token> expanded = expandAliases(synthetic, new HashSet<>(), aliasNameHolder, at);
            // resolveTypeAnnotationRaw needs exactly one mutability modifier; the synthetic 'imut' is
            // never read by checkAs (mutability is untouched by a cast).
            boolean alreadyHasMutability = expanded.stream().anyMatch(t ->
                    t.type == TokenType.MODIFIER && (t.text.equals("mut") || t.text.equals("imut")));
            if (!alreadyHasMutability) {
                expanded.add(0, new Token(TokenType.MODIFIER, "imut", at.line, at.file));
            }
            TypeInfo resolved = resolveTypeAnnotationRaw(expanded, at);
            return new TypeInfo(resolved.storage, resolved.mutability, resolved.baseType, aliasNameHolder[0]);
        }
        if (!isKnownType(name)) {
            throw new CompilerException("type", at.file, at.line, "unknown type '" + name + "'");
        }
        return new TypeInfo(null, "imut", name);
    }

    /** True when an active, un-invalidated 'match x fits T' proof covers this exact slot and target type. */
    private boolean isProvenFits(Token expr, String targetBase, Scope scope) {
        String key = slotKeyOf(expr);
        if (key == null) {
            return false;
        }
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            if (p.kind.equals("fits") && !p.invalidated && key.equals(p.slotKey) && targetBase.equals(p.targetName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * "value as TypeName": an alias relabel (same structure underneath), a same-signedness widening
     * (u8->u16->u32->u64, s8->..->s64), or a `match x fits T` proven tightening. The right side is read
     * off its token, not typed as an expression. See docs/TypeChecker.md, "as casts".
     */
    private TypeInfo checkAs(Token op, Scope scope, FuncInfo func) {
        TypeInfo leftType = resolveExprType(op.left, scope, func);
        // `x as f64` for an f32 x: always exact, never needs a float-state proof. A bare float literal
        // simply adapts to f64 (keeping its full decimal text) instead of being widened from f32.
        if (op.right.type == TokenType.VARREF && op.right.text.equals("f64") && leftType.storage == null
                && leftType.baseType.equals("f32") && !aliasRawTargets.containsKey("f64")) {
            if (leftType.floatLiteral) {
                TypeInfo f64Slot = new TypeInfo(null, leftType.mutability, "f64");
                adaptLiteral(op.left, f64Slot);
                op.isAliasCast = true;
                return new TypeInfo(null, leftType.mutability, "f64");
            }
            op.isFloatConvert = true;
            return new TypeInfo(null, leftType.mutability, "f64");
        }
        requireProvenFloatState(op.left, leftType, scope, op);

        if (op.right.type == TokenType.VARREF) {
            String targetBase = op.right.text;

            // Alias casts: legal when storage and base type match structurally (mutability untouched).
            if (aliasRawTargets.containsKey(targetBase) || leftType.aliasName != null) {
                TypeInfo resolvedTarget = resolveCastTargetBare(targetBase, op.right);
                boolean structuralMatch = java.util.Objects.equals(leftType.storage, resolvedTarget.storage)
                        && leftType.baseType.equals(resolvedTarget.baseType);
                if (!structuralMatch) {
                    throw new CompilerException("type", op.right.file, op.right.line,
                            "cannot cast '" + leftType.canonical() + "' as '" + targetBase + "' -- they "
                                    + "don't share the same underlying representation");
                }
                op.isAliasCast = true;
                return new TypeInfo(leftType.storage, leftType.mutability, resolvedTarget.baseType,
                        resolvedTarget.aliasName);
            }

            if (!INTEGER_TYPES.contains(leftType.baseType) || !INTEGER_TYPES.contains(targetBase)) {
                throw new CompilerException("type", op.right.file, op.right.line,
                        "'as' can only widen between integer types, got '" + leftType.canonical()
                                + "' as '" + targetBase + "'");
            }
            char leftFamily = leftType.baseType.charAt(0);
            char targetFamily = targetBase.charAt(0);
            if (isProvenFits(op.left, targetBase, scope)) {
                // Integer tightening: an active 'match x fits T' proof makes this cast lossless,
                // so it may narrow or change sign. Same width = pure relabel; narrower = TRUNC;
                // wider = SEXT/ZEXT by the source's signedness (the value is proven representable).
                int pw = INTEGER_WIDTH.get(leftType.baseType);
                int tw = INTEGER_WIDTH.get(targetBase);
                if (tw < pw) {
                    op.isTruncCast = true;
                } else if (tw == pw) {
                    op.isAliasCast = true;
                } else {
                    op.isSignedWideningCast = (leftFamily == 's');
                }
                return new TypeInfo(leftType.storage, leftType.mutability, targetBase);
            }
            if (leftFamily != targetFamily) {
                throw new CompilerException("type", op.right.file, op.right.line,
                        "'as' cannot cast across signedness ('" + leftType.baseType + "' as '"
                                + targetBase + "'); cast within the same signed/unsigned family only, prove the "
                                + "value fits with 'match x fits " + targetBase + "{ ... }', or convert with "
                                + "'wrap:<" + targetBase + ">(x)' / 'sat:<" + targetBase + ">(x)'");
            }
            int leftWidth = INTEGER_WIDTH.get(leftType.baseType);
            int targetWidth = INTEGER_WIDTH.get(targetBase);
            if (targetWidth <= leftWidth) {
                throw new CompilerException("type", op.right.file, op.right.line,
                        "'as' only widens ('" + leftType.baseType + "' as '" + targetBase
                                + "' is not wider) -- prove the value fits with 'match x fits " + targetBase
                                + "{ ... }', or convert with 'wrap:<" + targetBase + ">(x)' / 'sat:<" + targetBase
                                + ">(x)'");
            }
            op.isSignedWideningCast = (leftFamily == 's');
            return new TypeInfo(leftType.storage, leftType.mutability, targetBase);
        }

        throw new CompilerException("type", op.right.file, op.right.line,
                "'as' requires an integer type name (for widening) or a type/alias name on the right "
                        + "-- mutability is never affected by 'as' (use the 'mut'/'imut' prefix "
                        + "operator instead)");
    }

    /**
     * The single gate for "needs a `match Some(x)` proof before '.field'": real storage, not `auto`
     * (non-null by construction), not `some`-tagged, and no active proof for this slot. Shared by
     * checkDot's range/struct sites so they cannot drift.
     */
    private boolean requiresAliveProof(TypeInfo t, Token targetExpr, Scope scope) {
        if (t.storage == null || t.storage.equals("auto") || t.isSome) {
            return false;
        }
        return !isProvenAlive(targetExpr, scope);
    }

    private boolean isProvenFloatState(Token targetExpr, Scope scope) {
        String key = lookupKeyOf(targetExpr);
        if (key == null) {
            return false;
        }
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            if (!p.invalidated && p.kind.equals("float_state") && key.equals(p.slotKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Float "access" (arithmetic, comparisons, unary minus, `as`) needs a known state: a type-level
     * `floatStates`, an active float_state proof for the slot, a literal, or 'unsafe'. Passing or
     * returning a float needs no proof. See docs/TypeChecker.md, "Proof lookups".
     */
    private void requireProvenFloatState(Token expr, TypeInfo type, Scope scope, Token op) {
        if (!isFloatType(type.baseType)) {
            return;
        }
        Token unwrapped = expr;
        while (unwrapped.type == TokenType.OPERATOR
                && (unwrapped.text.equals("mut") || unwrapped.text.equals("imut")) && unwrapped.left != null) {
            // See through mut/imut wrappers: "mut 0.0" is a literal too.
            unwrapped = unwrapped.left;
        }
        if (unwrapped.type == TokenType.OPERATOR && unwrapped.right == null && unwrapped.left != null
                && unwrapped.text.equals("-") && unwrapped.left.type == TokenType.FLOAT) {
            return; // a negated float literal is as known as the literal
        }
        if (unwrapped.type == TokenType.FLOAT) {
            // No NaN/Infinity literal syntax: every float literal is finite.
            return;
        }
        if (type.floatStates != null || type.floatLiteral) {
            return;
        }
        if (isProvenFloatState(unwrapped, scope)) {
            return;
        }
        throw new CompilerException("type", op.file, op.line,
                "'" + op.text + "' on an unproven '" + type.baseType + "' isn't permitted -- prove it first with "
                        + "'match " + describeConditionForError(unwrapped) + "{ finite:{...} infinite:{...} "
                        + "nan:{...} }' (or a narrower set), or vouch for it with 'assume match "
                        + describeConditionForError(unwrapped) + " : finite' in an 'unsafe assume' block"
                        + (exprKeyOf(unwrapped) != null ? " [needs: assume match " + exprHint(unwrapped) + " : finite]" : ""));
    }

    /**
     * True for a type with real storage, or a struct with such a member (recursively; struct cycles are
     * already a hard error, so this terminates). Used to ban passing/returning pointers to async.
     */
    private boolean containsPointerAnywhere(TypeInfo type) {
        if (type.storage != null) {
            return true;
        }
        StructInfo si = structs.get(type.baseType);
        if (si == null) {
            return false;
        }
        for (TypeInfo memberType : si.members.values()) {
            if (containsPointerAnywhere(memberType)) {
                return true;
            }
        }
        return false;
    }

    /** True if a live "alive" proof covers this slot (`match x{..}` / `match Some(x)`); the match does not change x's type. */
    private boolean isProvenAlive(Token targetExpr, Scope scope) {
        String key = lookupKeyOf(targetExpr);
        if (key == null) {
            return false;
        }
        List<Token.MatchPattern> cands = null;
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            if (!p.invalidated && p.kind.equals("alive") && key.equals(p.slotKey)) {
                if (cands == null) {
                    cands = new ArrayList<>();
                }
                cands.add(p);
            }
        }
        if (cands == null) {
            return false;
        }
        ProofKills.use(targetExpr, cands, key);
        for (Token.MatchPattern c : cands) {
            if (c.onId) {
                targetExpr.refProofIsId = true;   // the proof is on a nullable `ref` (an id): the emitter reads the proof's address
                break;
            }
        }
        targetExpr.refProofKey = key;   // a nullable `ref` subject is read from the proof's address slot (BytecodeEmitter)
        return true;
    }

    /** Division-safety counterpart of isProvenAlive ("match rht!=0 and lft!=0{ .. }"). */
    private boolean isProvenNonzero(Token targetExpr, Scope scope) {
        String key = lookupKeyOf(targetExpr);
        if (key == null) {
            return false;
        }
        for (Token.MatchPattern p : scope.activeMatchPatterns) {
            if (!p.invalidated && p.kind.equals("nonzero") && key.equals(p.slotKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if a live "in" (or "into") proof covers this exact index slot AND this exact lookup target
     * (the target named in the match's `in` clause, not just any array in the block).
     */
    private boolean isProvenInBounds(Token lookupTarget, Token indexExpr, Scope scope) {
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
    private boolean isProvenWritable(Token lookupTarget, Token indexExpr, Scope scope) {
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
    private void requireLookupWriteAuthorized(Token lookupOp, Scope scope) {
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
    private String boundsSlotKeyOf(Token node) {
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
    private void checkStructMemberVisibility(StructInfo structInfo, String receiverStructName, String memberName,
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
    private void requireSwapFieldAccessAuthorized(StructInfo structInfo, String memberName, Scope scope, Token op) {
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

    /** Catch parameters of the catch bodies being checked (name -> the try token that binds it). */
    private final Map<String, Token> activeCatchTries = new HashMap<>();
    /** The operand of the `throw` being checked (a bare catch parameter there is a re-throw, not a use of the message). */
    private Token rethrowOperand = null;

    /**
     * '.' means either EnumName.Variant (left is a bare name that is not a declared variable but is an
     * enum) or instance.member (left resolved as an expression; chains work as '.' is left-associative).
     * A declared local shadows an enum of the same name.
     */
    private TypeInfo checkDot(Token op, Scope scope, FuncInfo func) {
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
    private TypeInfo checkInstantiate(Token op, Scope scope, FuncInfo func) {
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
    private void checkSwapFieldNotConstructedClosed(StructInfo structInfo, String structName,
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
    private boolean isBareEnumVariantRefNode(Token node, String enumName) {
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
    private boolean isSwapRhsAllowedShape(Token node) {
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
    private void checkOtcLockViolation(Token op, StructInfo structInfo, String structName,
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
    private TypeInfo checkNew(Token op, Scope scope, FuncInfo func) {
        TypeInfo result;
        newDynDepth++;
        try {
            result = checkNewInner(op, scope, func);
        } finally {
            newDynDepth--;
        }
        return result;
    }

    private TypeInfo checkNewInner(Token op, Scope scope, FuncInfo func) {
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

    /** Base types backed by a real atomic CPU instruction: integers, bool, char. */
    private static final Set<String> ATOMIC_ELIGIBLE_BASE_TYPES = new HashSet<>(Arrays.asList(
            "u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "bool", "char"));

    /**
     * "atomic x": tags a plain scalar's type as atomic (TypeInfo.isAtomic). Mutability is preserved
     * (still needs an explicit mut/imut wrap to bind); only integer/bool/char, no storage.
     */
    private TypeInfo checkAtomicWrapper(Token op, Scope scope, FuncInfo func) {
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
    private TypeInfo checkSwapOperator(Token op, Scope scope, FuncInfo func) {
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
    private TypeInfo checkAwait(Token op, Scope scope, FuncInfo func) {
        TypeInfo result;
        result = checkAwaitInner(op, scope, func);
        ProofKills.kill(scope.activeMatchPatterns, op, "'await' (another thread runs)");
        return result;
    }

    private TypeInfo checkAwaitInner(Token op, Scope scope, FuncInfo func) {
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
    private TypeInfo prepareAsyncCallSite(Token callTok, TypeInfo calleeReturnType) {
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
    private FuncInfo findTopLevelAsyncFuncInfo(Token callTok) {
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

    /** Memoization guard for ensureAsyncStateEnum. */
    private boolean asyncStateEnumEnsured;

    /**
     * Synthesizes the `AsyncState` enum (PENDING, RUNNING, READY) once, lazily, as EnumInfo plus a root
     * token appended to `rootLinesRef`, so it is available without any import.
     */
    private void ensureAsyncStateEnum() {
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

    private boolean asyncGateEnumEnsured;

    /** The two-variant (OPEN, CLOSED) enum behind the swap-mutex `gate` member of a `par` handle (see getOrCreateAsyncHandleType). */
    private void ensureAsyncGateEnum() {
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
    private void appendSynthesizedRootToken(Token t) {
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
    private void generateClassHierarchyEnums() {
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


    private void appendSynthesizedEnumRootToken(String enumName) {
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
    private void generateEnumForEnums() {
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

    private void registerEnumForEnum(String ownerName, List<String> memberNames) {
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
    private void generateInterfaceDispatchers() {
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
    private boolean hasGtAliveCheckFunction() {
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
    private String interfaceDispatcherName(String interfaceName, String methodName) {
        return "__iface_dispatch__" + interfaceName + "__" + methodName;
    }

    private void generateOneInterfaceDispatcher(String interfaceName, String methodName, RawMethodSig sig,
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
    private Token buildDefaultLiteralFor(TypeInfo t, Token at) {
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
    private Token mutabilityWrap(TypeInfo t, Token literal) {
        String mutability = "mut".equals(t.mutability) ? "mut" : "imut";
        Token op = new Token(TokenType.OPERATOR, mutability, literal.line, literal.file);
        op.unary = true;
        op.left = literal;
        return op;
    }

    /** One AsyncHandle struct/impl per distinct (result, param, locked) key. */
    private final Map<String, TypeInfo> asyncHandleTypesByResultCanonical = new HashMap<>();

    /**
     * Synthesizes the handle struct for an @async call as compiler-internal data (GenericsExpander runs
     * too early to infer T): non-void `par` handles get `state`, `result`, `value`, `threadId` plus a
     * `resolve()` method (raw token tree, type-checked later by the ordinary impl loop) and are returned
     * as `owns some` so `.state`/`:resolve()` need no `match Some`. `resultType == null` (void) gives the
     * hidden internal shape (`value`, `threadId` only). `locked` makes a `par` handle a swap-lock struct.
     * Memoized by (result|void, param|none[, par]). See docs/TypeChecker.md, "Async: await, par, handles".
     */
    private TypeInfo getOrCreateAsyncHandleType(TypeInfo resultType, TypeInfo paramType, Token at, boolean locked) {
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
    private TypeInfo checkPar(Token op, Scope scope, FuncInfo func) {
        TypeInfo result;
        result = checkParInner(op, scope, func);
        ProofKills.kill(scope.activeMatchPatterns, op, "'par' (another thread starts)");
        return result;
    }

    private TypeInfo checkParInner(Token op, Scope scope, FuncInfo func) {
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

    /** True while the body of a compiler-written par/await glue function (trampoline, drop hook) is being checked: such code is trusted, so it may touch a locked handle's members outside `match @lock` and retry the writer's lock without a bound. */
    private boolean synthesizingGlueBody;

    /** Memoizes which "@async" functions already have a synthesized trampoline (one per function, never per call site or per handle type -- see synthesizeAsyncTrampoline's own doc comment). */
    private final Set<String> asyncTrampolinesSynthesized = new HashSet<>();

    /**
     * Synthesizes (once per function) the pthread-shaped trampoline `raw mut u8 __trampoline_<name>(raw mut u8)` as Caspien source text run through the front end,
     * and checks its body eagerly (check()'s sweep worklist is already fixed). See docs/TypeChecker.md, "par / await and trampolines".
     */
    private void synthesizeAsyncTrampoline(FuncInfo asyncFuncInfo, TypeInfo handleType, TypeInfo paramType,
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

    private final Set<String> asyncDropHooksSynthesized = new HashSet<>();

    /**
     * The `@drop` hook of a `par` handle: when the handle is dropped (scope end, throw unwind, a destruct after a failed start), wait for
     * its thread by joining it, so the handle's memory never goes away under a running thread. `threadId` is 0 when no thread was started
     * (pthread_create failed), then there is nothing to join. Written as Caspien source like the trampoline; trusted glue code.
     */
    private void synthesizeAsyncDropHook(String handleStructName, String file) {
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
    private void markSynthesizedUnsafe(Token node) {
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
    private Token findCallNodeByCalleeName(Token node, String calleeName) {
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

    private boolean isValueConstructingExpr(Token node) {
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

    private TypeInfo checkAddressOf(Token op, Scope scope, FuncInfo func) {
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
    private boolean isAddressableLvalue(Token node) {
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
    private boolean isReadableValueChain(Token node) {
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
    private TypeInfo checkMutabilityOf(Token op, Scope scope, FuncInfo func) {
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
    private TypeInfo checkRange(Token op, Scope scope, FuncInfo func) {
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

    private boolean isPlainU64(TypeInfo t) {
        return t.storage == null && t.baseType.equals("u64");
    }

    private String rangeBoundDescriptor(Token boundExpr, TypeInfo boundType) {
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
    private boolean isDynArrayType(TypeInfo t) {
        return t.dynArrayElementType != null;
    }

    /** True for "unsafe dynarray(T)"; never true together with isDynArrayType. */
    private boolean isUnsafeDynArrayType(TypeInfo t) {
        return t.unsafeDynArrayElementType != null;
    }

    /** True for any range type, complete or incomplete (bare "range" behind a pointer). */
    private boolean isRangeType(TypeInfo t) {
        return t.baseType.equals("range") || t.baseType.startsWith("range(");
    }

    /** True only for a fully-specified range -- false for a bare, incomplete "range" pointer. */
    private boolean isCompleteRange(TypeInfo t) {
        return t.baseType.startsWith("range(");
    }

    /** Builds the synthesized call "range(arr)" around an already-parsed fixed-array expression (same CALL shape the parser produces for a written-out `range(arr)`). */
    private Token wrapFixedArrayAsRange(Token arr) {
        Token args = new Token(TokenType.DELINEATOR, "(", arr.line, arr.file);
        args.childs = new ArrayList<>();
        args.childs.add(arr);
        Token call = new Token(TokenType.OPERATOR, "CALL", arr.line, arr.file);
        call.left = new Token(TokenType.VARREF, "range", arr.line, arr.file);
        call.right = args;
        return call;
    }

    /** `x in R`: u64 or range on the left; range, dynarray, fixed array, string or guaranteed enum on the right. See docs/TypeChecker.md, "Ranges and membership operators". */
    private TypeInfo checkIn(Token op, Scope scope, FuncInfo func) {
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
    private TypeInfo checkGuaranteedEnumMembership(Token op, Scope scope, FuncInfo func) {
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
    private TypeInfo checkWithin(Token op, Scope scope, FuncInfo func) {
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
    private boolean isArrayType(String baseType) {
        return baseType.endsWith("]") && baseType.contains("[");
    }

    /** True for a storage-less struct-typed value (the Return Value Optimization shape). */
    private boolean isStructByValueReturn(TypeInfo t) {
        return t.storage == null && structs.containsKey(t.baseType);
    }

    /** Everything before the last top-level '[' -- the element type, one dimension down for a nested array. */
    private String arrayElementType(String baseType) {
        return baseType.substring(0, baseType.lastIndexOf('['));
    }

    private int arrayLength(String baseType) {
        int idx = baseType.lastIndexOf('[');
        return Integer.parseInt(baseType.substring(idx + 1, baseType.length() - 1));
    }


    /** True for any type with a storage modifier. */
    private boolean isPointerType(TypeInfo t) {
        return t.storage != null;
    }

    /** Argument matching: like typesCompatible, but a `some` parameter also accepts a not-`some` argument proven alive by `match Some`. See docs/TypeChecker.md, "Type compatibility". */
    private boolean argSatisfiesParam(TypeInfo paramType, TypeInfo argType, Token argExpr, Scope scope) {
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
    private TypeInfo[] unifyLiteralOperands(Token op, TypeInfo leftType, TypeInfo rightType) {
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
    private void retagLiteralArgs(List<TypeInfo> paramTypes, List<TypeInfo> argTypes, List<Token> argNodes) {
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
    private List<FuncInfo> orderByLiteralExactness(List<FuncInfo> candidates, List<TypeInfo> argTypes) {
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

    /** Smallest and largest value an integer base type ("u8".."s64") holds, or null for a non-integer type. */
    private static java.math.BigInteger[] integerBounds(String baseType) {
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
    private static boolean literalFits(java.math.BigInteger v, String baseType) {
        java.math.BigInteger[] b = integerBounds(baseType);
        return b != null && v.compareTo(b[0]) >= 0 && v.compareTo(b[1]) <= 0;
    }

    /**
     * Integer tightening, part 1: an integer literal (or negated literal) satisfies any plain integer
     * slot whose type holds its value, with a mutability the literal's own mutability satisfies -- a
     * literal has no width of its own, so the slot decides. The caller must follow a successful match
     * with `adaptLiteral` on the literal's expression so its node is retagged with the slot's type.
     */
    private boolean literalAdapts(TypeInfo expected, TypeInfo actual) {
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
    private boolean arrayLiteralAdapts(TypeInfo expected, TypeInfo actual) {
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
    private boolean floatArrayLiteralAdapts(TypeInfo expected, TypeInfo actual) {
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

    private static String unsignedCounterpart(String signedBase) {
        for (Map.Entry<String, String> e : UNSIGNED_TO_SIGNED.entrySet()) {
            if (e.getValue().equals(signedBase)) {
                return e.getKey();
            }
        }
        return signedBase;
    }

    /**
     * Retags an integer-literal expression (through `mut`/`imut` wrappers and one unary minus) with the
     * type of the slot it was just accepted into, so the emitted `PUSH`/`NEG` carry the slot's width and
     * signedness instead of the literal's `u64`/`s64` default. Safe to call on an expression whose type
     * was already the slot's type. A no-op unless `actualType.literalValue` was set.
     */
    private void adaptLiteral(Token expr, TypeInfo expected) {
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
    private void markRefConversion(TypeInfo expected, TypeInfo actual, Token expr) {
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
    private boolean acceptsAdaptingLiteral(TypeInfo expected, TypeInfo actual, Token expr) {
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
    private boolean typesCompatible(TypeInfo expected, TypeInfo actual) {
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
    private boolean mutabilityCompatible(String expected, String actual) {
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

    private boolean storageCompatible(String expectedStorage, String actualStorage) {
        if (expectedStorage.equals(actualStorage)) {
            return true;
        }
        if (expectedStorage.equals("raw")) {
            return true;
        }
        return expectedStorage.equals("ref") && actualStorage.equals("owns");
    }

    /** The type of `ptr +/- n`: the same raw pointer type, with indeterminate mutability (a fresh value). */
    private TypeInfo pointerResultType(TypeInfo ptr) {
        return ptr.rawPointeeType != null
                ? TypeInfo.rawPointer("indeterminate", ptr.rawPointeeType)
                : new TypeInfo("raw", "indeterminate", ptr.baseType);
    }

    /** True for a 'raw' pointer value -- the only type C-style pointer arithmetic (scaled by the pointee size) applies to. */
    private static boolean isRawPointerType(TypeInfo t) {
        return t != null && "raw".equals(t.storage) && t.funcPtrParamTypes == null;
    }

    /** Pointee size of a 'raw' pointer for C-style arithmetic (void and unknown sizes step by one byte). */
    private long pointeeSize(TypeInfo ptr, Token at) {
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
    private String pointeeStructName(TypeInfo ptr) {
        TypeInfo pointee = ptr.rawPointeeType != null ? ptr.rawPointeeType : new TypeInfo(null, "imut", ptr.baseType);
        return sizedStructName(pointee);
    }

    /** The integer half of pointer +/- integer: an 8-byte integer (u64/s64), so it can be added straight to the 8-byte address; narrower integers need an explicit 'as u64' first. */
    private void requireEightByteInteger(TypeInfo t, Token at) {
        if (!t.baseType.equals("u64") && !t.baseType.equals("s64")) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + at.text + "' on a 'raw' pointer requires a 'u64' or 's64' integer on the other side, got '"
                            + t.canonical() + "' (widen it with 'as u64' first)");
        }
    }

    private void requireNumeric(TypeInfo t, Token at) {
        if (!NUMERIC_TYPES.contains(t.baseType)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + at.text + "' requires a numeric type, got '" + t.canonical() + "'");
        }
    }

    private void requireBoolOrInteger(TypeInfo t, Token at) {
        if (!t.baseType.equals("bool") && !INTEGER_TYPES.contains(t.baseType)) {
            throw new CompilerException("type", at.file, at.line,
                    "'" + at.text + "' requires a bool or integer type, got '" + t.canonical() + "'");
        }
    }
}

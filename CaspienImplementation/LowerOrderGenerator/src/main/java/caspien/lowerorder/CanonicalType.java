package caspien.lowerorder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Parses a bytecode canonical type string ("storage_mutability_basetype",
 * e.g. "owns_mut_Box", "imut_u64", "raw_mut_u8") the same way the
 * compiler's own TypeChecker.TypeInfo.canonical() builds one -- see that
 * class's own doc comment for the format this mirrors exactly. Storage
 * is optional (absent for a plain stack-resident value); when present
 * it's always one of the five keywords below, stripped straight off the
 * front -- never present as part of an ordinary base type name, since
 * none of the five is a legal identifier prefix in real source.
 *
 * baseType itself is left otherwise unparsed here -- it can be a plain
 * primitive/struct name, or a composite shape written the same way the
 * rest of this bytecode already writes one: "elem[N]" for a fixed-size
 * array, "dynarray(elem)" for a dynarray, and so on, arbitrarily nested
 * (e.g. "dynarray(mut_u64)[2]"). The static helpers below peel off one
 * such wrapper at a time; callers that need to walk all the way down
 * recurse by re-parsing whatever text a helper hands back.
 *
 * This parser needs nothing from the compiler project -- every type a
 * downstream, standalone stage ever needs to reason about already
 * arrives as plain canonical text on the relevant bytecode line (a
 * STRUCT_MEMBER's own operand, an ALLOC/PUSH's own type operand),
 * matching this project's "no dependency on the compiler or codegen
 * stages" rule.
 */
public class CanonicalType {

    private static final Set<String> MUTABILITY_KEYWORDS = Set.of("mut", "imut", "indeterminate");
    private static final Set<String> STORAGE_KEYWORDS =
            new HashSet<>(Arrays.asList("owns", "ref", "raw", "auto", "static"));

    public final String storage;    // nullable
    public final boolean isSome;    // "storage, then 'some', then mutability, then baseType" -- TypeChecker.TypeInfo.canonical()'s own emission order for a value explicitly annotated "some" (a definitely-present pointer, or -- the shape that surfaced this -- every string literal, e.g. "static_some_imut_string")
    public final String mutability; // nullable only if the input didn't match the expected shape at all
    public final boolean isAtomic;  // "storage?_some?_mutability_atomic?_floatStates?_baseType" -- TypeChecker.TypeInfo.canonical()'s own emission order puts "atomic" right after mutability, before baseType (and before any floatStates segment, which never co-occurs with atomic since ATOMIC_ELIGIBLE_BASE_TYPES excludes every float type). Previously unhandled here: this parser silently folded "atomic_X" straight into baseType as one string (e.g. baseType="atomic_char" instead of baseType="char", isAtomic=true), which meant sizes.sizeOf(...) never recognized the real base type and silently fell back to its generic 8-byte default for every atomic type narrower than 8 bytes (char/bool/u8/u16/u32) at every call site except ATOMIC_SWAP's own narrow, already-existing workaround (see AddressLoweringPass's ATOMIC_SWAP rewrite, which sidesteps this by reading size from a freshly-constructed plain TypeInfo instead of the atomic-tagged one). Fixed generally here instead, mirroring isSome's own exact stripping pattern.
    public final String baseType;
    public final String raw;

    private CanonicalType(String storage, boolean isSome, String mutability, boolean isAtomic, String baseType, String raw) { }

    /**
     * Format mirrors `TypeChecker.TypeInfo.canonical()` exactly:
     * "storage?_some?_mutability_baseType" -- storage and the "some" tag
     * are each independently optional, but when both are present, storage
     * always comes first (`TypeInfo.canonical()`'s own comment: "str:owns
     * some mut String" -- storage, then "some", then mutability").
     *
     * Found and fixed directly while lowering `IN_SCAN` (`MembershipLoweringPass`):
     * a real compiled string literal's own canonical type is always
     * "static_some_imut_string" (every string value in this language is
     * canonically "static" storage, and a literal additionally carries
     * "some" -- confirmed directly, "static_some" is not a two-word
     * storage keyword, "some" is its own separate, real segment). The
     * original two-underscore-split version of this parser had no idea
     * "some" could appear there at all, so it silently misparsed this
     * exact shape -- "some_imut_string" was split at *its own* first
     * underscore, yielding `mutability="some"` and `baseType="imut_string"`
     * (never "string") -- wrong for every caller here, and, confirmed
     * separately, for `AddressLoweringPass.lookupMnemonicFor` too (a
     * `LOOKUP` directly into a string literal was silently failing to
     * ever resolve to `LOOKUP_ARRAY` at all, a real, pre-existing,
     * previously-unnoticed gap this uncovered -- nothing before `IN_SCAN`
     * ever exercised a string literal as a `LOOKUP`/`IN`-family operand's
     * *right* side closely enough to hit it). Fixed generally, not just
     * for the one call site that surfaced it: an optional "some" segment
     * is now stripped, independently of storage, right after storage and
     * before the mutability/baseType split, mirroring `canonical()`'s own
     * emission order exactly.
     */
    public static CanonicalType parse(String canonical) { return null; }

    public boolean isOwnsStorage() { return false; }

    public String dynArrayElementType() { return null; }

    public String fixedArrayElementType() { return null; }

    public int fixedArrayLength() { return 0; }

    /** "dynarray(elem)" -- elem's own canonical text, or null if `baseType` isn't a (safe) dynarray shape. */
    public static String dynArrayElementTypeOf(String baseType) { return null; }

    /**
     * "unsafe_dynarray(elem)" -- elem's own canonical text, or null if
     * `baseType` isn't an unsafe dynarray shape. Deliberately a separate
     * helper from `dynArrayElementTypeOf`, not folded into it -- an
     * unsafe dynarray's own text is a genuinely distinct prefix
     * ("unsafe_dynarray(", never "dynarray(" -- see `TypeInfo.
     * unsafeDynArray`'s own comment in the compiler, "a distinct string
     * from the safe variant's own 'dynarray(...)', deliberately so the
     * two are never mutually coercible"), and, since the fix to
     * `lookupMnemonicFor` this accompanies, the two shapes now also need
     * genuinely different *addressing* treatment (see that method's own
     * doc comment) -- not just a different label on the same shape.
     */
    public static String unsafeDynArrayElementTypeOf(String baseType) { return null; }

    /** "elem[N]" -- elem's own canonical text, or null if `baseType` isn't a fixed-size array shape. */
    public static String fixedArrayElementTypeOf(String baseType) { return null; }

    /** The declared length of a fixed-size array base type, or -1 if `baseType` isn't one (or the length isn't a plain literal). */
    public static int fixedArrayLengthOf(String baseType) { return 0; }

    private static int[] fixedArrayBracketOf(String baseType) { return null; }

    /** A version of `text` safe to splice into a generated label/function name -- every non-alphanumeric character becomes '_'. */
    public static String sanitizeForLabel(String text) { return null; }
}

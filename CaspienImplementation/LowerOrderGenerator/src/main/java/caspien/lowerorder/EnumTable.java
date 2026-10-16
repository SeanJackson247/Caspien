package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every "ENUM" line's own variant data, read directly out of the
 * program's own already-emitted "ENUM Name variant1 ... variantN ..."
 * lines -- the same self-describing-bytecode approach StructTable
 * already takes for struct layout. Only the two shapes
 * MembershipLoweringPass actually needs are kept:
 *
 *   - range-valued ("ENUM Name variant1 lo1..hi1 ...") -- ordinary range enums.
 *   - value-valued ("ENUM Name variant1 val1 variant2 val2 ...") -- used
 *     for ordinary user "guaranteed" enums, for the compiler-synthesized
 *     flat "Class" enum (every non-@untyped struct and its class id),
 *     and for every "$enum_for_InterfaceName" enum
 *     (TypeChecker.registerEnumForEnum): each implementer's class id.
 *
 * A plain enum (no values at all, "ENUM Name variant1 variant2 ...")
 * used to carry nothing any pass here needed, and was left untracked
 * entirely -- true right up until address lowering itself needed to
 * resolve an "EnumName.Variant" reference down to its own concrete
 * value (see AddressLoweringPass's own "literal enum values resolve to
 * their actual values" rewrite): a plain enum's variants are never
 * explicitly numbered in source, but they still each get one, "as in
 * C" -- 0, 1, 2, ... in declaration order -- the moment anything
 * downstream actually needs a number rather than a name. `variantValue`
 * below is the one place that number is computed, for either shape
 * (explicit or implicit) uniformly.
 *
 * Every shape here (range-valued, explicit-value-valued, and now plain)
 * is distinguished purely by inspecting the token immediately after the
 * first variant name (see isRangeLiteral/isPlainU64Literal below); "no
 * partial mixing" (an enum is always uniformly one shape, confirmed
 * directly against TypeChecker's own collectEnum invariant) means
 * checking just the first pair is always enough -- a plain enum simply
 * has no second token to check at all for its first variant (or, for a
 * two-variant plain enum, a second token that's neither a plain u64 nor
 * a range literal -- either way, "not value/range-shaped" is exactly
 * "plain," so every remaining token is a bare variant name, one per
 * variant, in declaration order). No regex, anywhere -- manual
 * character-by-character checks throughout, matching this project's own
 * coding convention.
 */
public class EnumTable {

    private final Map<String, Map<String, EnumTableRangeInfo>> rangeEnums = new LinkedHashMap<>();
    private final Map<String, Map<String, Long>> valueEnums = new LinkedHashMap<>();
    private final Map<String, List<String>> valueVariantOrder = new LinkedHashMap<>();
    private final Map<String, List<String>> plainEnumVariantOrder = new LinkedHashMap<>();

    public static EnumTable read(BytecodeProgram lines) { return null; }

    /**
     * The variant's own concrete scalar integer value -- explicit for a
     * value-valued enum (including every compiler-synthesized
     * "$enum_for_..." and "Class" enums), or implicit/sequential ("as in C",
     * 0/1/2/... in declaration order) for a plain one. Returns null for
     * a range-valued enum (no single scalar value exists for one of
     * those -- see `classRangeOf` instead) or when `enumName`/
     * `variantName` isn't a real variant of any tracked enum at all
     * (most commonly because `enumName` isn't an enum, e.g. an ordinary
     * struct/instance name that merely happens to precede a '.' too --
     * callers resolving an arbitrary dotted name should treat null here
     * as "not an enum reference," not as an error).
     */
    public Long variantValue(String enumName, String variantName) { return null; }

    /**
     * Every direct implementer's own class id, in
     * declaration order, from the compiler-synthesized
     * "$enum_for_<ownerName>" enum (TypeChecker.registerEnumForEnum) --
     * or an empty list when there's no such enum (no implementers were
     * ever registered for that interface, or `ownerName` was
     * never one of the compiler's own synthesized enum owners at all).
     */
    public List<Long> implementerClassIdsOf(String ownerName) { return null; }

    /** Every explicit (variant,value) pair of an ordinary user-written value-valued (a.k.a. "guaranteed") enum, in declaration order -- empty if `enumName` isn't a value-valued enum at all. */
    public List<Map.Entry<String, Long>> valueVariantsOf(String enumName) { return null; }

    private static boolean isPlainU64Literal(String s) { return false; }

    private static Long parseU64Literal(String s) { return null; }

    private static boolean isRangeLiteral(String s) { return false; }

    /** "lo..hi" -- both sides plain u64 literals, exactly one ".." separator -- or null if `s` doesn't match that shape at all. */
    private static long[] parseRangeLiteral(String s) { return null; }
}

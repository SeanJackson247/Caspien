package caspien.lowerorder;

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
 *   - range-valued ("ENUM Name variant1 lo1..hi1 variant2 lo2..hi2 ...")
 *     -- used for exactly one compiler-synthesized enum, "Class"
 *     (TypeChecker.generateClassHierarchyEnums): every struct/abstract's
 *     own pre-order-DFS subtree range, keyed by that struct/abstract's
 *     own name.
 *   - value-valued ("ENUM Name variant1 val1 variant2 val2 ...") -- used
 *     both for ordinary user "guaranteed" enums and for every
 *     compiler-synthesized "$enum_for_OwnerName" enum
 *     (TypeChecker.registerEnumForEnum): each direct implementer's/
 *     child's own ClassID.
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

    private static final class RangeInfo {
        final long lo;
        final long hi;
        RangeInfo(long lo, long hi) {
            this.lo = lo;
            this.hi = hi;
        }
    }

    private final Map<String, Map<String, RangeInfo>> rangeEnums = new LinkedHashMap<>();
    private final Map<String, Map<String, Long>> valueEnums = new LinkedHashMap<>();
    private final Map<String, List<String>> valueVariantOrder = new LinkedHashMap<>();
    private final Map<String, List<String>> plainEnumVariantOrder = new LinkedHashMap<>();

    public static EnumTable read(List<List<BytecodeToken>> lines) {
        EnumTable table = new EnumTable();
        for (List<BytecodeToken> line : lines) {
            if (line.isEmpty() || !line.get(0).text.equals("ENUM") || line.size() < 2) {
                continue;
            }
            String enumName = line.get(1).text;
            List<String> rest = new ArrayList<>();
            for (int i = 2; i < line.size(); i++) {
                rest.add(line.get(i).text);
            }
            if (rest.isEmpty()) {
                continue; // malformed -- "ENUM Name" with no variants at all, shouldn't happen
            }
            // Value-carrying and range-carrying shapes are always exactly
            // two tokens per variant (name, value); tested by inspecting
            // just the token right after the very first variant name --
            // "no partial mixing" (TypeChecker's own collectEnum
            // invariant) means every remaining pair is guaranteed the
            // same shape. Anything else (including an odd-sized
            // remainder, which a value/range enum can never produce) is
            // a plain enum -- see this class's own header.
            boolean pairShaped = rest.size() >= 2 && rest.size() % 2 == 0
                    && (isRangeLiteral(rest.get(1)) || isPlainU64Literal(rest.get(1)));
            if (pairShaped && isRangeLiteral(rest.get(1))) {
                Map<String, RangeInfo> variants = new LinkedHashMap<>();
                for (int i = 0; i + 1 < rest.size(); i += 2) {
                    long[] bounds = parseRangeLiteral(rest.get(i + 1));
                    if (bounds != null) {
                        variants.put(rest.get(i), new RangeInfo(bounds[0], bounds[1]));
                    }
                }
                table.rangeEnums.put(enumName, variants);
            } else if (pairShaped) {
                Map<String, Long> variants = new LinkedHashMap<>();
                List<String> order = new ArrayList<>();
                for (int i = 0; i + 1 < rest.size(); i += 2) {
                    Long v = parseU64Literal(rest.get(i + 1));
                    if (v != null) {
                        variants.put(rest.get(i), v);
                        order.add(rest.get(i));
                    }
                }
                table.valueEnums.put(enumName, variants);
                table.valueVariantOrder.put(enumName, order);
            } else {
                // Plain enum -- one bare variant name per token, in
                // declaration order; "as in C," each gets an implicit
                // 0, 1, 2, ... value the moment anything actually asks
                // for one (see `variantValue`).
                table.plainEnumVariantOrder.put(enumName, new ArrayList<>(rest));
            }
        }
        return table;
    }

    /**
     * The variant's own concrete scalar integer value -- explicit for a
     * value-valued enum (including every compiler-synthesized
     * "$enum_for_..." ClassID enum), or implicit/sequential ("as in C",
     * 0/1/2/... in declaration order) for a plain one. Returns null for
     * a range-valued enum (no single scalar value exists for one of
     * those -- see `classRangeOf` instead) or when `enumName`/
     * `variantName` isn't a real variant of any tracked enum at all
     * (most commonly because `enumName` isn't an enum, e.g. an ordinary
     * struct/instance name that merely happens to precede a '.' too --
     * callers resolving an arbitrary dotted name should treat null here
     * as "not an enum reference," not as an error).
     */
    public Long variantValue(String enumName, String variantName) {
        Map<String, Long> values = valueEnums.get(enumName);
        if (values != null) {
            return values.get(variantName);
        }
        List<String> order = plainEnumVariantOrder.get(enumName);
        if (order != null) {
            int idx = order.indexOf(variantName);
            return idx < 0 ? null : (long) idx;
        }
        return null;
    }

    /**
     * The "Class" enum's own [lo,hi] pre-order-DFS subtree range for a
     * struct/abstract name -- confirmed by TypeChecker.walkClassHierarchy
     * to always be exactly one contiguous range per name -- or null when
     * there's no synthesized "Class" enum at all (no eligible struct in
     * the program) or `name` isn't one of its variants (most commonly
     * because it's an interface, which never gets a "Class" entry --
     * only "$enum_for_" ones, see implementerClassIdsOf).
     */
    public long[] classRangeOf(String structOrAbstractName) {
        Map<String, RangeInfo> classEnum = rangeEnums.get("Class");
        if (classEnum == null) {
            return null;
        }
        RangeInfo info = classEnum.get(structOrAbstractName);
        return info == null ? null : new long[]{info.lo, info.hi};
    }

    /**
     * Every direct implementer's/child's own ClassID value, in
     * declaration order, from the compiler-synthesized
     * "$enum_for_<ownerName>" enum (TypeChecker.registerEnumForEnum) --
     * or an empty list when there's no such enum (no implementers were
     * ever registered for that interface/parent, or `ownerName` was
     * never one of the compiler's own synthesized enum owners at all).
     */
    public List<Long> implementerClassIdsOf(String ownerName) {
        String syntheticName = "$enum_for_" + ownerName;
        Map<String, Long> variants = valueEnums.get(syntheticName);
        List<String> order = valueVariantOrder.get(syntheticName);
        List<Long> result = new ArrayList<>();
        if (variants == null || order == null) {
            return result;
        }
        for (String name : order) {
            result.add(variants.get(name));
        }
        return result;
    }

    /** Every explicit (variant,value) pair of an ordinary user-written value-valued (a.k.a. "guaranteed") enum, in declaration order -- empty if `enumName` isn't a value-valued enum at all. */
    public List<Map.Entry<String, Long>> valueVariantsOf(String enumName) {
        Map<String, Long> variants = valueEnums.get(enumName);
        List<String> order = valueVariantOrder.get(enumName);
        List<Map.Entry<String, Long>> result = new ArrayList<>();
        if (variants == null || order == null) {
            return result;
        }
        for (String name : order) {
            result.add(new java.util.AbstractMap.SimpleEntry<>(name, variants.get(name)));
        }
        return result;
    }

    private static boolean isPlainU64Literal(String s) {
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

    private static Long parseU64Literal(String s) {
        return isPlainU64Literal(s) ? Long.parseLong(s) : null;
    }

    private static boolean isRangeLiteral(String s) {
        return parseRangeLiteral(s) != null;
    }

    /** "lo..hi" -- both sides plain u64 literals, exactly one ".." separator -- or null if `s` doesn't match that shape at all. */
    private static long[] parseRangeLiteral(String s) {
        int dotDot = -1;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) == '.' && s.charAt(i + 1) == '.') {
                dotDot = i;
                break;
            }
        }
        if (dotDot < 0) {
            return null;
        }
        String left = s.substring(0, dotDot);
        String right = s.substring(dotDot + 2);
        if (!isPlainU64Literal(left) || !isPlainU64Literal(right)) {
            return null;
        }
        return new long[]{Long.parseLong(left), Long.parseLong(right)};
    }
}

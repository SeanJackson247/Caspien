package caspien.lowerorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Value sizes in bytes from canonical type text and the struct table; any pointer is 8 bytes. See docs/AddressLoweringPass.md, "SizeCalculator". */
final class AddressLoweringSizeCalculator {
    private final StructTable structTable;
    private final Map<String, Long> structSizeCache = new HashMap<>();
    private final Map<String, Long> structAlignCache = new HashMap<>();

    AddressLoweringSizeCalculator(StructTable structTable) {
        this.structTable = structTable;
    }

    /** Set by sizeNoRecurse/alignNoRecurse to the struct whose size/alignment is not cached yet (its value is then not final). */
    private String missing;

    long sizeOf(String canonical) {
        missing = null;
        long v = sizeNoRecurse(canonical);
        if (missing == null) {
            return v;
        }
        resolveStructSizes(missing);
        missing = null;
        return sizeNoRecurse(canonical);
    }

    /** Natural alignment; equals size for leaves, max member alignment for structs. Mirrors the compiler side's layoutSizeAndAlignOf. */
    long alignOf(String canonical) {
        missing = null;
        long v = alignNoRecurse(canonical);
        if (missing == null) {
            return v;
        }
        resolveStructAligns(missing);
        missing = null;
        return alignNoRecurse(canonical);
    }

    /** Size of `canonical` given the cached struct sizes; the first struct size still missing is left in `missing`. */
    private long sizeNoRecurse(String canonical) {
        long mult = 1;
        String cur = canonical;
        while (true) {
            String baseType;
            // A bare struct name is used whole (a generic mangled name like HashMapEntry_u64 would be mis-parsed).
            if (structTable.hasStruct(cur)) {
                baseType = cur;
            } else {
                CanonicalType t = CanonicalType.parse(cur);
                if (t.storage != null) {
                    return mult * 8;
                }
                baseType = t.baseType;
            }
            switch (baseType) {
                // bool is 1 byte (matches TypeChecker.PRIMITIVE_SIZE); may need revisiting if codegen needs a wider ABI size.
                case "u8": case "s8": case "bool": case "char":
                    return mult;
                case "u16": case "s16":
                    return mult * 2;
                case "u32": case "s32": case "f32":
                    return mult * 4;
                case "u64": case "s64": case "f64": case "code_addr": case "string":
                    return mult * 8;
                case "void":
                    return 0;
                default:
                    break;
            }
            if (CanonicalType.dynArrayElementTypeOf(baseType) != null) {
                return mult * 8; // a dynarray value is a single handle/pointer to its own heap buffer
            }
            String arrElem = CanonicalType.fixedArrayElementTypeOf(baseType);
            if (arrElem != null) {
                int length = CanonicalType.fixedArrayLengthOf(baseType);
                if (length < 0) {
                    return mult * 8;
                }
                mult *= length;
                cur = arrElem;
                continue;
            }
            if (baseType.equals("range") || baseType.startsWith("range(")) {
                return mult * 16; // two plain u64 bounds, always -- TypeChecker.checkRange
            }
            if (structTable.hasStruct(baseType)) {
                Long cached = structSizeCache.get(baseType);
                if (cached == null) {
                    missing = baseType;
                    return 0;
                }
                return mult * cached;
            }
            return mult * 8; // unknown type: conservative one-word fallback
        }
    }

    /**
     * Fills structSizeCache for `root` and every struct below it, depth first with an explicit stack. A struct is seeded 0 while
     * it is on the stack (struct types form a DAG, never a cycle -- see StructTable.isOwnsBearing's identical guard); its size is
     * summed over its padded layout (members plus STRUCT_PADDING gaps) once all member structs are cached.
     */
    private void resolveStructSizes(String root) {
        List<String> stack = new ArrayList<>();
        structSizeCache.put(root, 0L);
        stack.add(root);
        while (!stack.isEmpty()) {
            String name = stack.get(stack.size() - 1);
            List<String> memberTypes = new ArrayList<>();
            List<Long> paddings = new ArrayList<>();
            List<StructTableLayoutEntry> layout = structTable.layoutOf(name);
            if (layout != null) {
                for (StructTableLayoutEntry entry : layout) {
                    memberTypes.add(entry.member != null ? entry.member.canonicalType : null);
                    paddings.add(entry.paddingBytes);
                }
            } else {
                // Defensive fallback: plain member sum if no layout exists.
                for (StructTableMember m : structTable.membersOf(name)) {
                    memberTypes.add(m.canonicalType);
                    paddings.add(0L);
                }
            }
            long total = 0;
            boolean blocked = false;
            for (int i = 0; i < memberTypes.size(); i++) {
                if (memberTypes.get(i) == null) {
                    total += paddings.get(i);
                    continue;
                }
                missing = null;
                long v = sizeNoRecurse(memberTypes.get(i));
                if (missing != null) {
                    structSizeCache.put(missing, 0L);
                    stack.add(missing);
                    missing = null;
                    blocked = true;
                    break;
                }
                total += v;
            }
            if (!blocked) {
                structSizeCache.put(name, total);
                stack.remove(stack.size() - 1);
            }
        }
    }

    /** Alignment of `canonical` given the cached struct alignments; the first one still missing is left in `missing`. */
    private long alignNoRecurse(String canonical) {
        String cur = canonical;
        while (true) {
            CanonicalType t = CanonicalType.parse(cur);
            if (t.storage != null) {
                return 8;
            }
            String baseType = t.baseType;
            switch (baseType) {
                case "u8": case "s8": case "bool": case "char":
                    return 1;
                case "u16": case "s16":
                    return 2;
                case "u32": case "s32": case "f32":
                    return 4;
                case "u64": case "s64": case "f64": case "code_addr": case "string":
                    return 8;
                case "void":
                    return 1;
                default:
                    break;
            }
            if (CanonicalType.dynArrayElementTypeOf(baseType) != null) {
                return 8; // a dynarray value is a single handle/pointer -- same reasoning as sizeNoRecurse's own identical case
            }
            String arrElem = CanonicalType.fixedArrayElementTypeOf(baseType);
            if (arrElem != null) {
                cur = arrElem; // an array's own alignment is its element's own -- never its own total size
                continue;
            }
            if (baseType.equals("range") || baseType.startsWith("range(")) {
                return 8; // two plain u64 bounds -- 8-aligned, same as either bound alone
            }
            if (structTable.hasStruct(baseType)) {
                Long cached = structAlignCache.get(baseType);
                if (cached == null) {
                    missing = baseType;
                    return 1;
                }
                return cached;
            }
            return 8; // unknown type: same one-word fallback
        }
    }

    /** Fills structAlignCache for `root` and every struct below it: max of its members' alignments (hidden classId included), explicit stack, seeded 1 while on it. */
    private void resolveStructAligns(String root) {
        List<String> stack = new ArrayList<>();
        structAlignCache.put(root, 1L);
        stack.add(root);
        while (!stack.isEmpty()) {
            String name = stack.get(stack.size() - 1);
            long maxAlign = 1;
            boolean blocked = false;
            List<StructTableMember> members = structTable.membersOf(name);
            if (members != null) {
                for (StructTableMember m : members) {
                    missing = null;
                    long align = alignNoRecurse(m.canonicalType);
                    if (missing != null) {
                        structAlignCache.put(missing, 1L);
                        stack.add(missing);
                        missing = null;
                        blocked = true;
                        break;
                    }
                    if (align > maxAlign) {
                        maxAlign = align;
                    }
                }
            }
            if (!blocked) {
                structAlignCache.put(name, maxAlign);
                stack.remove(stack.size() - 1);
            }
        }
    }
}

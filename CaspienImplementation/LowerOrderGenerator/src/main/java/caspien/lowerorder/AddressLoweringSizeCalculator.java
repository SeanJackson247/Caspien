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

    AddressLoweringSizeCalculator(StructTable structTable) { }

    /** Set by sizeNoRecurse/alignNoRecurse to the struct whose size/alignment is not cached yet (its value is then not final). */
    private String missing;

    long sizeOf(String canonical) { return 0L; }

    /** Natural alignment; equals size for leaves, max member alignment for structs. Mirrors the compiler side's layoutSizeAndAlignOf. */
    long alignOf(String canonical) { return 0L; }

    /** Size of `canonical` given the cached struct sizes; the first struct size still missing is left in `missing`. */
    private long sizeNoRecurse(String canonical) { return 0L; }

    /**
     * Fills structSizeCache for `root` and every struct below it, depth first with an explicit stack. A struct is seeded 0 while
     * it is on the stack (struct types form a DAG, never a cycle -- see StructTable.isOwnsBearing's identical guard); its size is
     * summed over its padded layout (members plus STRUCT_PADDING gaps) once all member structs are cached.
     */
    private void resolveStructSizes(String root) { }

    /** Alignment of `canonical` given the cached struct alignments; the first one still missing is left in `missing`. */
    private long alignNoRecurse(String canonical) { return 0L; }

    /** Fills structAlignCache for `root` and every struct below it: max of its members' alignments (hidden classId included), explicit stack, seeded 1 while on it. */
    private void resolveStructAligns(String root) { }
}

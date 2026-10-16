package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Alignment of a canonical type text; mirrors the LowerOrderGenerator's own table (see the class comment). */
final class VariableAllocationReorderingAlign {
    private final Map<String, List<String>> structMembers = new HashMap<>();
    private final Map<String, Long> structCache = new HashMap<>();

    VariableAllocationReorderingAlign(BytecodeProgram lines) { }

    /** Set by ofNoRecurse to the struct whose alignment is not cached yet (the returned value is then not final). */
    private String missing;

    long of(String canonical) { return 0L; }

    /** Alignment of `canonical` given the cached struct alignments; the first one still missing is left in `missing`. */
    private long ofNoRecurse(String canonical) { return 0L; }

    /** Fills structCache for `root` and every struct below it: max over member alignments, explicit stack, a struct seeded 1 while on it. */
    private void resolveStructs(String root) { }
}

package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;

import java.util.Map;
import java.util.Set;
import caspien.bytecode.Text;

/**
 * Copy forwarding (`copy-forward: on`). A temporary that only holds a copy of a promoted variable,
 *
 *   R_MOV 8 %t0 %v1 ; R_BIN ADD 8 %t0 %t0 #3 ; R_ST 8 %t2 %t0       (%t0 redefined afterwards)
 *
 * has its later reads replaced by the variable itself and the copy deleted:
 *
 *   R_BIN ADD 8 %t0 %v1 #3 ; R_ST 8 %t2 %t0
 *
 * (the 3-operand `add`/`lea`/`imul` forms then save the `mov`). Applied only when, scanning forward in straight-line code (at most
 * {@code WINDOW} lines), every mention of the temp is a read in a position the backend takes a variable register in, nothing
 * writes the variable or calls anything, and the scan ends at a pure redefinition of the temp (or a label / unconditional jump, where
 * temporaries are dead, as for {@link DestForwardingPass}). Only the variables %v0..%v5 are used (no implicit clobbers by division,
 * shifts or calls). Also deleted: the no-op `R_MOV 8 %vK %vK`.
 */
public class CopyForwardPass {

    private static final int WINDOW = 14;
    private static final Map<String, Integer> DEST = Map.ofEntries(
            Map.entry("R_MOV", 2), Map.entry("R_LD", 2), Map.entry("R_LDI", 2), Map.entry("R_LDD", 2), Map.entry("R_LEA", 1),
            Map.entry("R_BIN", 3), Map.entry("R_UN", 3), Map.entry("R_SETV", 2), Map.entry("R_DIVC", 3),
            Map.entry("R_FBIN", 3), Map.entry("R_FCMP", 3), Map.entry("R_XTOG", 2));
    /** lines that write no general register and are neither a branch nor a call */
    private static final Set<String> STRAIGHT = Set.of("R_ST", "R_STI", "R_STD", "R_LDX", "R_STX", "R_XMOV", "R_GTOX",
            "R_FSQRT", "R_LDXI", "R_STXI", "R_LDXD", "R_STXD");
    private static final Set<String> BIN_OPS = Set.of("ADD", "SUB", "MUL", "AND", "OR", "BAND", "BOR", "BXOR", "SHL", "SHR", "SAR");

    private final boolean enabled;

    public CopyForwardPass(boolean enabled) { }

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    /** the rewritten lines after `from` (null entries = unchanged), or null when the copy cannot be forwarded */
    private static BytecodeProgram plan(BytecodeProgram cur, boolean[] gone, int from, String x, BytecodeToken v) { return null; }

    /** operand k of line l is a plain value read of a general register that the backend also accepts a variable register for */
    private static boolean readSlot(BytecodeStatement l, int k) { return false; }

    private static String t(BytecodeStatement l, int i) { return null; }
}

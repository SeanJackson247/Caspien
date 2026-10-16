package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;

import java.util.Map;
import java.util.Set;

/**
 * Two register-form peepholes on straight-line code, run last (after the fusion passes, so the indexed and displacement
 * loads are already in their final shape; only the backend reads the result). No switch: a function without variable
 * registers ({@code %v}) has nothing to match.
 *
 * <p><b>Destination forwarding.</b> A value computed into a temporary and copied into a variable register on the very next
 * line is computed into the variable register instead:
 * <pre>
 *   R_LDI 8 %t0 %t0 %v3 8 ; R_MOV 8 %v5 %t0     ->   R_LDI 8 %v5 %t0 %v3 8
 *   R_MOV 8 %t0 %v1       ; R_MOV 8 %v3 %t0     ->   R_MOV 8 %v3 %v1
 *   R_BIN SHR 8 %t0 %t0 #1 ; R_MOV 8 %v3 %t0    ->   R_BIN SHR 8 %v3 %t0 #1
 * </pre>
 * Producers: loads ({@code R_LD R_LDI R_LDD}: one instruction, the destination may equal an input), {@code R_MOV 8} from a
 * slot, an immediate or a register, and {@code R_BIN} ADD SUB MUL AND OR BAND BOR BXOR and SHL/SHR/SAR by an immediate. The temporary must be dead
 * after the copy (register-form temporaries are never live across a label or an unconditional jump; the next mention must
 * be a pure redefinition). The copy moved a full 64-bit register, so the value in the variable is the same as before.
 *
 * <p><b>Dead initialisation.</b> {@code R_MOV 8 %vJ <src>} (a constant, a register or a slot read) whose next mention of
 * {@code %vJ}, in straight-line code with only known register-form lines in between, is a pure redefinition that does not read
 * it, is deleted (the {@code let x = mut 0} followed by the real assignment that the source writes).
 */
public class DestForwardingPass {

    private static final Map<String, Integer> DEST = Map.ofEntries(
            Map.entry("R_MOV", 2), Map.entry("R_LD", 2), Map.entry("R_LDI", 2), Map.entry("R_LDD", 2), Map.entry("R_LEA", 1),
            Map.entry("R_BIN", 3), Map.entry("R_UN", 3), Map.entry("R_FBIN", 3), Map.entry("R_FCMP", 3), Map.entry("R_XTOG", 2));
    /** known lines that write no general register (or only the one DEST names) and are neither a branch nor a call */
    private static final Set<String> STRAIGHT = Set.of("R_ST", "R_STI", "R_STD", "R_LDX", "R_STX", "R_FBINX", "R_XMOV", "R_GTOX",
            "R_FSQRT", "R_RMW", "R_LDXI", "R_STXI", "R_LDXD", "R_STXD");
    private static final Set<String> BIN_OPS = Set.of("ADD", "SUB", "MUL", "AND", "OR", "BAND", "BOR", "BXOR");
    private static final Set<String> SHIFT_OPS = Set.of("SHL", "SHR", "SAR", "ROTL", "ROTR");

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private static String t(BytecodeStatement l, int i) { return null; }

    private static int destIndex(BytecodeStatement l) { return 0; }

    /** a producer whose destination register may be a variable register */
    private static boolean forwardable(BytecodeStatement a, String tmp) { return false; }

    /** x (a temporary) is not read from line `from` on: the next mention is a pure redefinition, or a label / unconditional jump comes first */
    private static boolean deadAfter(BytecodeProgram cur, boolean[] gone, int from, String x) { return false; }

    static boolean deadAfterPublic(BytecodeProgram cur, boolean[] gone, int from, String x) { return false; }

    private static boolean pureRedefinition(BytecodeStatement l, String x) { return false; }

    /** straight-line scan: the next line that mentions the variable x redefines it without reading it */
    private static boolean overwrittenBeforeRead(BytecodeProgram cur, boolean[] gone, int from, String x) { return false; }
}

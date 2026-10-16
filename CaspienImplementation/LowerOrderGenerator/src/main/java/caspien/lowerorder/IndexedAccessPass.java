package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;

import java.util.Map;
import java.util.Set;

/**
 * Indexed array access on the final register-form text (always on; a pure code-quality rewrite, runs after JumpCleanupPass).
 * A global array element used to cost two address instructions:
 *
 *   R_LEA %tX &sym %vK scale ; ... ; R_LD n %tD %tX        ->  R_LDI n %tD &sym %vK scale
 *   R_LEA %tX &sym %vK scale ; ... ; R_ST n %tX src        ->  R_STI n &sym %vK scale src
 *
 * (`leaq sym(%rip),%rax; leaq (%rax,%r13,8),%rax; movq (%rax),...` becomes `leaq sym(%rip),%rax; movq (%rax,%r13,8),...`:
 * RIP-relative addressing cannot carry an index register, so the base address still takes one lea.)
 * The float forms fuse the same way (`R_LDX n %xK|%yK %tX` -> `R_LDXI n %xK|%yK &sym %vK n`, `R_STX n %tX %xK|%yK` -> `R_STXI n &sym %vK n %xK|%yK`) with scale = access width (4 for f32, 8 for f64).
 * An R_LEA with a displacement (6 tokens, e.g. the safe dynarray header offset 16) fuses the same way; the displacement becomes the last token of the fused line.
 * Only a base that is a global, a frame slot or a register holding a pointer, an index held in a variable register (%v), and an access whose width equals the element size (scale 1, 2, 4 or 8; tests/indexed_narrow_test covers 1, 2 and 4, tests/indexed_bases_test the frame and pointer bases). The lines
 * between the lea and its consumer must be known register-form mnemonics that neither mention %tX nor write the index register
 * (the index is read later than before), and %tX must be dead after the consumer (never read again before a pure redefinition,
 * a label or a jump; register-form temps are never live across those).
 */
public class IndexedAccessPass {

    private static final int MAX_GAP = 40;

    /** position of the register a mnemonic writes (-1: writes none) */
    static final Map<String, Integer> DEST = Map.ofEntries(
            Map.entry("R_MOV", 2), Map.entry("R_LD", 2), Map.entry("R_LDI", 2), Map.entry("R_BIN", 3), Map.entry("R_UN", 3),
            Map.entry("R_LEA", 1), Map.entry("R_SETV", 2), Map.entry("R_RMW", 3), Map.entry("R_ST", -1), Map.entry("R_STI", -1),
            // float forms: only R_FBIN, R_FCMP and R_XTOG write a general register (a temp); the rest write xmm registers or memory
            Map.entry("R_FBIN", 3), Map.entry("R_FCMP", 3), Map.entry("R_XTOG", 2), Map.entry("R_GTOX", -1), Map.entry("R_LDX", -1),
            Map.entry("R_STX", -1), Map.entry("R_FBINX", -1), Map.entry("R_XMOV", -1), Map.entry("R_LDXI", -1), Map.entry("R_STXI", -1));

    static final Set<String> PURE_DEF = Set.of("R_MOV", "R_LD", "R_LDI", "R_LEA", "R_BIN", "R_UN", "R_FBIN", "R_FCMP", "R_XTOG");

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private static boolean isIndexedLea(BytecodeStatement l) { return false; }

    private static boolean isHwScale(String s) { return false; }

    private static boolean isPositiveInt(String s) { return false; }

    /** the fused line for consumer l of the address in x, or null */
    private static BytecodeStatement fuse(BytecodeStatement lea, BytecodeStatement l, String x) { return null; }

    /** the lea's displacement (6th token, e.g. the 16-byte safe dynarray header) rides along as the fused line's last token */
    private static void addDisp(BytecodeStatement n, BytecodeStatement lea) { }

    /** float access of width w (4 or 8) at a stride: hardware strides keep the old rule (width == stride); other strides take either width */
    private static boolean floatWidthOk(String w, String scale) { return false; }

    private static boolean isXmm(String tok) { return false; }

    private static boolean okSize(String s) { return false; }

    /** a line that may sit between the lea and its consumer: known mnemonic, does not write the index register */
    static boolean passable(BytecodeStatement l, String idx) { return false; }

    static boolean mentions(BytecodeStatement l, String tok) { return false; }

    /** x is dead from line `from` on; selfDefined: the consumer itself redefines x */
    static boolean deadAfter(BytecodeProgram cur, boolean[] gone, int from, String x, boolean selfDefined) { return false; }

    private static String t(BytecodeStatement l, int i) { return null; }
}

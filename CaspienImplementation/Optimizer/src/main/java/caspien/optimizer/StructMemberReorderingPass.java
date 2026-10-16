package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.bytecode.BytecodeTokenKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reorders a struct's own members, largest alignment first, so the padding the front end put between
 * them (and after the last one) disappears and the struct gets smaller
 * (switch: {@code struct-member-reordering: on|off}, default off).
 *
 * Runs first in the Optimizer's outer loop, ahead of SizeofResolutionPass and StructUnpackingPass, so the layout is settled before
 * anything reads it. Everything that reads a member does it by NAME (member reads/writes, offsets worked out later by the Lower Order
 * Generator from the declaration), so only two things are positional and must be rewritten together with the declaration: a
 * construction site (the hidden class id, then one value per member in declaration order, with {@code STACK_LOCK n} for each padding gap)
 * and static sizes folded by the front end (see the pin below).
 *
 * A struct is reordered only when ALL of these hold (otherwise it is left exactly as it was):
 *   - it has at least two members, all plain scalars (u8..u64, s8..s64, bool, char, f32, f64; no pointer, no atomic, no nested struct,
 *     no array), and no decorator other than {@code @pub} (so not {@code @lock});
 *   - no other struct has it as a member type (by value, or as an array of it), and no static/global declaration uses an array of it
 *     or an {@code ALLOC_STATIC} of it;
 *   - no {@code raw} pointer to it appears anywhere, except the hidden destination pointer of a struct-returning call
 *     (an {@code ARG/PUSH $ret_dest}, a {@code RET} of it, and the address handed to a callee that declares one): unsafe code can
 *     reach a struct through a raw pointer by byte offset, and C interop sees the memory layout;
 *   - the front end did not fold its size into a static ({@code STRUCT_PIN Name}, emitted for {@code let static n = sizeof(Name)});
 *   - EVERY construction site of it was understood: a {@code PUSH <classId> imut_u64} followed by exactly the member units and
 *     padding locks its declaration predicts (a unit is pure: PUSH, arithmetic, compare and cast lines only, with the member's type),
 *     ended by an ASSIGN, a NEW or the next class-id push (array literal). A site that does not parse, or parses two ways, blocks the
 *     struct (a literal that happens to equal a class id can do this too; that only costs the optimisation);
 *   - the new layout is strictly smaller.
 *
 * Effect: the STRUCT_MEMBER / STRUCT_PADDING lines of the declaration are replaced by the new order (largest member first, equal sizes
 * keep their relative order, padding only at the end); each construction site is rewritten the same way (units reordered, padding locks
 * re-derived). Member expressions are evaluated in the new order; they are pure, so this only matters for the order of loads.
 * Static initialisers ({@code GLOBAL g.member ...}) name their members and need no change.
 *
 * Always, even with the switch off, the pass strips every {@code STRUCT_PIN} line (it must never reach the Lower Order Generator).
 */
public class StructMemberReorderingPass {

    private final boolean enabled;

    public StructMemberReorderingPass() { }

    public StructMemberReorderingPass(boolean enabled) { }

    // ---------------------------------------------------------------- model

    // ------------------------------------------------------------- helpers

    private final Set<String> pinnedEver = new HashSet<>();

    private static boolean isMn(BytecodeStatement l, String m) { return false; }

    private static BytecodeToken tok(BytecodeToken near, String text) { return null; }

    private static String stripMut(String t) { return null; }

    /** Byte size of a plain scalar base type, or -1 when the type is anything else. */
    private static int scalarSize(String type) { return 0; }

    private static final Set<String> BINARY = new HashSet<>(java.util.List.of(
            "ADD", "SUB", "MUL", "DIV", "MOD", "SHL", "SHR", "SAR", "ROTL", "ROTR", "BITS_OR", "BITS_AND", "BITS_XOR", "AND", "OR",
            "EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ"));
    private static final Set<String> UNARY = new HashSet<>(java.util.List.of(
            "NEG", "NOT", "BITS_NOT", "TRUNC", "SEXT", "ZEXT", "FCONV", "NEG_FLOAT"));

    /** Stack effect of one member-expression line, or Integer.MIN_VALUE when the line is not allowed in one. */
    private static int effect(BytecodeStatement l) { return 0; }

    private static String resultType(BytecodeStatement l) { return null; }

    // ------------------------------------------------------------------ run

    public PassResult run(BytecodeProgram lines) { return null; }

    private PassResult reorder(BytecodeProgram L, Set<String> pinned) { return null; }

    // ---------------------------------------------------------- declarations

    private static Map<String, StructMemberReorderingDecl> readDecls(BytecodeProgram L) { return null; }

    /** Class id of each struct, from the flat "Class" enum. */
    private static void readClassIds(BytecodeProgram L, Map<String, StructMemberReorderingDecl> decls) { }

    // ---------------------------------------------------------------- blockers

    /** Does the type text name struct s as its base, through an array suffix, mutability and storage prefixes? */
    private static boolean namesStruct(String text, String s) { return false; }

    private static boolean isRawTo(String text, String s) { return false; }

    private static void blockByUses(BytecodeProgram L, Map<String, StructMemberReorderingDecl> decls) { }

    private static boolean lineNames(BytecodeStatement l, String s) { return false; }

    private static boolean lineHasArrayOf(BytecodeStatement l, String s) { return false; }

    // ------------------------------------------------------ construction sites

    private static StructMemberReorderingDecl classIdPush(BytecodeStatement l, Map<Long, StructMemberReorderingDecl> byId) { return null; }

    private static final int MAX_UNIT = 64;

    private StructMemberReorderingSite parseSite(BytecodeProgram L, int i, StructMemberReorderingDecl d, Map<Long, StructMemberReorderingDecl> byId) { return null; }

    /**
     * Backtracking search for the ways the literal's pushed values can be cut into the members of `order`, depth first, stopping
     * as soon as a second way is found. The choice points (where each member's value ends) are frames on an explicit stack.
     */
    private void walk(BytecodeProgram L, int j0, List<StructMemberReorderingEntry> order, StructMemberReorderingDecl d, Map<Long, StructMemberReorderingDecl> byId, int start, List<StructMemberReorderingSite> found) { }

    private static boolean typeMatches(String pushed, String member) { return false; }

    private static int compareBySizeDesc(StructMemberReorderingEntry a, StructMemberReorderingEntry b) { return 0; }

    /** Stable insertion sort of the list by compareBySizeDesc. */
    private static void sortBySizeDesc(List<StructMemberReorderingEntry> list) { }
}

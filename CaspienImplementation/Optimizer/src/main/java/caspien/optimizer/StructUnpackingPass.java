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
import caspien.bytecode.Text;

/**
 * Rewrites a struct instance living in a local variable to act as independent variables, one per member, instead of one opaque
 * struct-shaped slot (switch: {@code struct-unpacking: on|off}, default off).
 *
 * A local {@code ALLOC v T} whose type T is {@code mut_S} or {@code imut_S}, where S is a struct declared in the program with only
 * scalar members (integers, f32/f64, bool), qualifies when EVERY mention of v (or of v.member) is one of:
 *   - its own ALLOC;
 *   - a construction   ADDR v T ; PUSH lit imut_u64 (the hidden ___type slot) ; one expression per member, in declaration order ;
 *                      STACK_LOCK n (padding lines may also sit between members) ; ASSIGN T T T     -- each member expression may only use PUSH, arithmetic/compare/cast operators (a known
 *                      stack effect) and must not mention v;
 *   - a member read    PUSH v.m MT   (not followed by ADDR_OF);
 *   - a member write   ADDR v T ; PUSH_FIELDNAME m MT ; DOT_LHS T MT MT   (the left-hand side of an assignment or compound assignment).
 * Anything else -- the whole struct pushed or copied, passed by address (ADDR_OF), a nested or ___type access, an unknown line inside a
 * construction -- leaves v alone. Functions containing ASM_START are skipped.
 *
 * Effect: the ALLOC becomes one ALLOC per member ({@code v__m}, type from the struct's member list); every read/write is redirected to
 * {@code v__m}; a construction becomes one {@code ADDR v__m MT ; expr ; ASSIGN MT MT MT} per member. The hidden ___type slot and the padding
 * are dropped (a program that reads ___type is not touched). The new variables are ordinary scalars, so elision, shifting and the register
 * hints see them.
 */
public class StructUnpackingPass {

    private final boolean enabled;

    public StructUnpackingPass() { }

    public StructUnpackingPass(boolean enabled) { }

    private static final Set<String> BINARY = new HashSet<>(List.of(
            "ADD", "SUB", "MUL", "DIV", "MOD", "SHL", "SHR", "ROTL", "ROTR", "BITS_OR", "BITS_AND", "BITS_XOR", "AND", "OR",
            "EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ"));
    private static final Set<String> UNARY = new HashSet<>(List.of("NEG", "NOT", "BITS_NOT", "TRUNC", "SEXT", "ZEXT", "FCONV"));

    /** struct name -> ordered (member name, member type), excluding ___type; only structs whose members are all scalars. */
    private static Map<String, List<String[]>> structs(BytecodeProgram L) { return null; }

    private static boolean mentions(BytecodeStatement l, String v) { return false; }

    /** Stack effect of one expression line, or Integer.MIN_VALUE when the line is not understood. */
    private static int effect(BytecodeStatement l) { return 0; }

    public PassResult run(BytecodeProgram lines) { return null; }

    private static BytecodeStatement mk(BytecodeToken like, String... toks) { return null; }

    /** Returns the rewrite for variable v, or null when some mention is not one of the understood shapes. */
    private static StructUnpackingPlan plan(BytecodeProgram L, int s, int e, int allocIdx, String v, String T,
                             List<String[]> members, Set<String> allNames) { return null; }
}

package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every struct's own member layout, read directly out of the program's
 * own STRUCT_START/STRUCT_MEMBER/STRUCT_END blocks -- the same
 * self-describing bytecode shape the codegen stage's own (now-removed,
 * see DropGlueGenerationPass's own header) recursive GT_DESTRUCT walk
 * used to depend on: "the assembly generator needs to check the struct
 * definition for the type to generate the appropriate routines," per
 * that stage's own gt_destruct_recursive_nested_test.caspien fixture
 * comment. Building this table needs nothing from the compiler project
 * itself -- struct shape is entirely present in the bytecode text
 * already, matching this project's own "no dependency on the compiler
 * or codegen stages" rule (README.md/CLAUDE.md).
 */
public class StructTable {

    private final Map<String, List<StructTableMember>> structs = new LinkedHashMap<>();
    /** Same structs, same declared order, but including every "STRUCT_PADDING n" gap alongside the real members -- what a real offset/size walk (AddressLoweringPass's memberLocOf/structSizeOf) needs; `structs`/`membersOf` above stays real-members-only for every existing caller that only ever wanted named fields (drop-glue generation, clone generation, dotted-name type resolution, ...), none of which need to see a nameless padding gap at all. */
    private final Map<String, List<StructTableLayoutEntry>> layouts = new LinkedHashMap<>();
    private final Map<String, Boolean> ownsBearingCache = new HashMap<>();

    public static StructTable read(BytecodeProgram lines) { return null; }

    private final Map<String, String> dropHooks = new HashMap<>();

    /** The `@drop` cleanup function of struct `structName`, or null. */
    public String dropHookOf(String structName) { return null; }

    public boolean hasStruct(String name) { return false; }

    public List<StructTableMember> membersOf(String structName) { return null; }

    /** `structName`'s own real physical layout, real members interspersed with any real "STRUCT_PADDING" gaps, in declared order -- see `LayoutEntry`'s own doc comment. Null for the same cases `membersOf` returns null for. */
    public List<StructTableLayoutEntry> layoutOf(String structName) { return null; }

    /**
     * True when a value of this base type (a struct name, a
     * "dynarray(elem)", or an "elem[N]" fixed array) either is itself
     * owns-storage somewhere inside it, or wraps/contains one -- the
     * exact test that decides whether this shape needs a generated
     * drop-glue routine at all (DropGlueGenerationPass) or can be left
     * as the bare, single GT_DESTRUCT the compiler already emits, with
     * nothing further to walk.
     *
     * Memoized, walked with an explicit stack (no recursion). Struct types
     * form a DAG, never a cycle (self-referential struct shapes are illegal
     * in this language); the "seed false while on the stack" step is a
     * purely defensive guard against that assumption ever being wrong.
     */
    public boolean isOwnsBearing(String baseType) { return false; }

    /** What decides whether `baseType` is owns-bearing, in the order they are asked: types to query, or null for an immediate yes. */
    private List<String> ownsPlan(String baseType) { return null; }
}

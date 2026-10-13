package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public static class Member {
        public final String name;
        public final String canonicalType;

        public Member(String name, String canonicalType) {
            this.name = name;
            this.canonicalType = canonicalType;
        }
    }

    /**
     * One entry of a struct's own real, physical layout, in declared
     * order: either a real named member (`member` set, `paddingBytes`
     * 0), or a pure alignment gap the compiler itself already baked in
     * as a "STRUCT_PADDING n" line (`member` null, `paddingBytes` the
     * gap's own byte count) -- see BytecodeEmitter.emitStruct
     * (compiler project), which now computes and emits real natural-
     * alignment padding directly into a struct's own STRUCT_START/
     * STRUCT_MEMBER/STRUCT_END declaration, rather than leaving this
     * pass to (wrongly) assume a flat, gap-free member sum the way
     * `membersOf`'s own plain `Member` list always has. Never matched
     * against a member name and never itself sized by `SizeCalculator`
     * (its own byte count is already known outright) -- purely
     * something for an offset/size walk to add to its running total and
     * skip over.
     */
    public static final class LayoutEntry {
        public final Member member;     // null for a pure padding gap
        public final long paddingBytes; // 0 unless member == null

        private LayoutEntry(Member member, long paddingBytes) {
            this.member = member;
            this.paddingBytes = paddingBytes;
        }

        static LayoutEntry ofMember(Member m) {
            return new LayoutEntry(m, 0);
        }

        static LayoutEntry ofPadding(long bytes) {
            return new LayoutEntry(null, bytes);
        }
    }

    private final Map<String, List<Member>> structs = new LinkedHashMap<>();
    /** Same structs, same declared order, but including every "STRUCT_PADDING n" gap alongside the real members -- what a real offset/size walk (AddressLoweringPass's memberLocOf/structSizeOf) needs; `structs`/`membersOf` above stays real-members-only for every existing caller that only ever wanted named fields (drop-glue generation, clone generation, dotted-name type resolution, ...), none of which need to see a nameless padding gap at all. */
    private final Map<String, List<LayoutEntry>> layouts = new LinkedHashMap<>();
    private final Map<String, Boolean> ownsBearingCache = new HashMap<>();

    public static StructTable read(BytecodeProgram lines) {
        StructTable table = new StructTable();
        String currentStruct = null;
        String currentFunc = null;
        boolean currentFuncIsDrop = false;
        List<Member> currentMembers = null;
        List<LayoutEntry> currentLayout = null;
        for (BytecodeStatement line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String head = line.get(0).text;
            if (head.equals("FUNC_START") && line.size() >= 2) {
                currentFunc = line.get(1).text;
                currentFuncIsDrop = false;
            } else if (head.equals("FUNC_DECORATE") && line.size() >= 2 && line.get(1).text.equals("@drop")) {
                currentFuncIsDrop = true;
            } else if (head.equals("ARG") && currentFuncIsDrop && currentFunc != null && line.size() >= 3) {
                // The first (only) parameter of a `@drop` function is `ref some mut S`: S's cleanup hook is this function.
                table.dropHooks.put(CanonicalType.parse(line.get(2).text).baseType, currentFunc);
                currentFuncIsDrop = false;
            } else if (head.equals("STRUCT_START") && line.size() >= 2) {
                currentStruct = line.get(1).text;
                currentMembers = new ArrayList<>();
                currentLayout = new ArrayList<>();
            } else if (head.equals("STRUCT_MEMBER") && currentMembers != null && line.size() >= 3) {
                Member m = new Member(line.get(1).text, line.get(2).text);
                currentMembers.add(m);
                currentLayout.add(LayoutEntry.ofMember(m));
            } else if (head.equals("STRUCT_PADDING") && currentLayout != null && line.size() >= 2) {
                currentLayout.add(LayoutEntry.ofPadding(Long.parseLong(line.get(1).text)));
            } else if (head.equals("STRUCT_END") && currentStruct != null) {
                table.structs.put(currentStruct, currentMembers);
                table.layouts.put(currentStruct, currentLayout);
                currentStruct = null;
                currentMembers = null;
                currentLayout = null;
            }
        }
        return table;
    }

    private final Map<String, String> dropHooks = new HashMap<>();

    /** The `@drop` cleanup function of struct `structName`, or null. */
    public String dropHookOf(String structName) {
        return dropHooks.get(structName);
    }

    public boolean hasStruct(String name) {
        return structs.containsKey(name);
    }

    public List<Member> membersOf(String structName) {
        return structs.get(structName);
    }

    /** `structName`'s own real physical layout, real members interspersed with any real "STRUCT_PADDING" gaps, in declared order -- see `LayoutEntry`'s own doc comment. Null for the same cases `membersOf` returns null for. */
    public List<LayoutEntry> layoutOf(String structName) {
        return layouts.get(structName);
    }

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
    public boolean isOwnsBearing(String baseType) {
        Boolean cached = ownsBearingCache.get(baseType);
        if (cached != null) {
            return cached;
        }
        // Depth-first walk with an explicit stack (parallel lists: type, its plan, next step). A plan entry is a type to ask
        // about, or null = "owns-bearing for sure". A type is seeded false while it is on the stack, as the recursive form did.
        List<String> types = new ArrayList<>();
        List<List<String>> plans = new ArrayList<>();
        List<Integer> nextStep = new ArrayList<>();
        ownsBearingCache.put(baseType, false);
        types.add(baseType);
        plans.add(ownsPlan(baseType));
        nextStep.add(0);
        boolean childOwns = false;
        while (!types.isEmpty()) {
            int top = types.size() - 1;
            List<String> plan = plans.get(top);
            int pos = nextStep.get(top);
            boolean done = childOwns;
            boolean result = childOwns;
            childOwns = false;
            boolean descend = false;
            while (!done && pos < plan.size()) {
                String step = plan.get(pos++);
                if (step == null) {
                    done = true;
                    result = true;
                } else {
                    Boolean known = ownsBearingCache.get(step);
                    if (known == null) {
                        nextStep.set(top, pos);
                        ownsBearingCache.put(step, false);
                        types.add(step);
                        plans.add(ownsPlan(step));
                        nextStep.add(0);
                        descend = true;
                        break;
                    }
                    if (known) {
                        done = true;
                        result = true;
                    }
                }
            }
            if (descend) {
                continue;
            }
            ownsBearingCache.put(types.get(top), result);
            types.remove(top);
            plans.remove(top);
            nextStep.remove(top);
            childOwns = result;
        }
        return childOwns;
    }

    /** What decides whether `baseType` is owns-bearing, in the order they are asked: types to query, or null for an immediate yes. */
    private List<String> ownsPlan(String baseType) {
        List<String> plan = new ArrayList<>();
        String elem = CanonicalType.dynArrayElementTypeOf(baseType);
        if (elem == null) {
            elem = CanonicalType.fixedArrayElementTypeOf(baseType);
        }
        if (elem != null) {
            CanonicalType elemType = CanonicalType.parse(elem);
            plan.add(elemType.isOwnsStorage() ? null : elemType.baseType);
            return plan;
        }
        if (dropHooks.containsKey(baseType)) {
            plan.add(null); // a struct with a cleanup hook needs its drop routine even when it owns no memory itself
            return plan;
        }
        List<Member> members = structs.get(baseType);
        if (members == null) {
            return plan; // a primitive, or any other type this table has no members for
        }
        for (Member m : members) {
            CanonicalType t = CanonicalType.parse(m.canonicalType);
            if (t.isOwnsStorage()) {
                plan.add(null); // this member alone means the struct needs a routine, regardless of what it itself points to
                return plan;
            }
            if (t.storage == null) {
                plan.add(t.baseType); // an inline member with owns descendants further down
            }
        }
        return plan;
    }
}

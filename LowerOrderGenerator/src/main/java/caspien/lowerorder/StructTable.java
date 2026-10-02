package caspien.lowerorder;

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

    public static StructTable read(List<List<BytecodeToken>> lines) {
        StructTable table = new StructTable();
        String currentStruct = null;
        List<Member> currentMembers = null;
        List<LayoutEntry> currentLayout = null;
        for (List<BytecodeToken> line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String head = line.get(0).text;
            if (head.equals("STRUCT_START") && line.size() >= 2) {
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
     * Memoized -- struct types form a DAG, never a cycle
     * (self-referential/recursive struct shapes are illegal in this
     * language), so plain recursion with a cache is safe and always
     * terminates. The "seed false before descending" step below is a
     * purely defensive belt-and-braces guard against that assumption
     * ever being wrong; it should never actually be exercised.
     */
    public boolean isOwnsBearing(String baseType) {
        Boolean cached = ownsBearingCache.get(baseType);
        if (cached != null) {
            return cached;
        }
        ownsBearingCache.put(baseType, false);
        boolean result = computeOwnsBearing(baseType);
        ownsBearingCache.put(baseType, result);
        return result;
    }

    private boolean computeOwnsBearing(String baseType) {
        String dynElem = CanonicalType.dynArrayElementTypeOf(baseType);
        if (dynElem != null) {
            CanonicalType elemType = CanonicalType.parse(dynElem);
            return elemType.isOwnsStorage() || isOwnsBearing(elemType.baseType);
        }
        String arrElem = CanonicalType.fixedArrayElementTypeOf(baseType);
        if (arrElem != null) {
            CanonicalType elemType = CanonicalType.parse(arrElem);
            return elemType.isOwnsStorage() || isOwnsBearing(elemType.baseType);
        }
        List<Member> members = structs.get(baseType);
        if (members == null) {
            return false; // a primitive, or any other type this table has no members for
        }
        for (Member m : members) {
            CanonicalType t = CanonicalType.parse(m.canonicalType);
            if (t.isOwnsStorage()) {
                return true; // this member alone means the struct needs a routine, regardless of what it itself points to
            }
            if (t.storage == null && isOwnsBearing(t.baseType)) {
                return true; // an inline member with owns descendants further down
            }
        }
        return false;
    }
}

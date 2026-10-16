package caspien.lowerorder;

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
public final class StructTableLayoutEntry {
    public final StructTableMember member;     // null for a pure padding gap
    public final long paddingBytes; // 0 unless member == null

    private StructTableLayoutEntry(StructTableMember member, long paddingBytes) {
        this.member = member;
        this.paddingBytes = paddingBytes;
    }

    static StructTableLayoutEntry ofMember(StructTableMember m) {
        return new StructTableLayoutEntry(m, 0);
    }

    static StructTableLayoutEntry ofPadding(long bytes) {
        return new StructTableLayoutEntry(null, bytes);
    }
}

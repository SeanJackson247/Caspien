package caspien.bytecodeemitter;

/** One real STRUCT_MEMBER line (memberName/canonicalType set, paddingBytes 0) or one pure STRUCT_PADDING gap (memberName/canonicalType null, paddingBytes > 0) -- computeStructLayout's own output, in final declared-plus-padding order. */
public final class StructLayoutEntry {
    public final String memberName;
    public final String canonicalType;
    public final long paddingBytes;

    public StructLayoutEntry(String memberName, String canonicalType, long paddingBytes) {
        this.memberName = memberName;
        this.canonicalType = canonicalType;
        this.paddingBytes = paddingBytes;
    }

    public static StructLayoutEntry member(String name, String canonicalType) {
        return new StructLayoutEntry(name, canonicalType, 0);
    }

    public static StructLayoutEntry padding(long bytes) {
        return new StructLayoutEntry(null, null, bytes);
    }
}

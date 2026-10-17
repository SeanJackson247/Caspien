package caspien.bytecodeemitter;

import java.util.List;

public final class StructLayout {
    public final List<StructLayoutEntry> entries;
    public final long size;
    public final long align;

    public StructLayout(List<StructLayoutEntry> entries, long size, long align) {
        this.entries = entries;
        this.size = size;
        this.align = align;
    }
}

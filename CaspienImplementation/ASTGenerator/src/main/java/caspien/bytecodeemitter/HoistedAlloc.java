package caspien.bytecodeemitter;

import caspien.token.Token;

/** One local's hoisted allocation, collected by collectHoistedAllocs: a plain `ALLOC` line, or a constant `let static` whose value is folded into ALLOC_STATIC lines. */
public final class HoistedAlloc {
    public final String name;
    public final String type;
    public final Token staticValue;   // null for a plain ALLOC line

    public HoistedAlloc(String name, String type, Token staticValue) { }
}

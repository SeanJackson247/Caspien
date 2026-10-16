package caspien.typechecker;

public class NameType {
    public final String name;
    public final TypeInfo type;
    /** True only for a struct member declared with `swap`. */
    public final boolean isSwap;
    public NameType(String name, TypeInfo type, boolean isSwap) { }
}

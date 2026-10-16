package caspien.parser;

import java.util.List;

public final class TypeParamParseResult {
    public final List<String> names; // null if there was no '<' at all
    public final List<String> bounds; // parallel to names; null entries mean "no bound"; whole list null if no bound anywhere
    public final int nextIndex;
    public TypeParamParseResult(List<String> names, List<String> bounds, int nextIndex) { }
}

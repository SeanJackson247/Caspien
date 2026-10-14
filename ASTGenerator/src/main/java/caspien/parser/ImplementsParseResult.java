package caspien.parser;

import java.util.List;

public final class ImplementsParseResult {
    public final List<String> names; // null if there was no 'implements' clause at all
    public final int nextIndex;
    public ImplementsParseResult(List<String> names, int nextIndex) {
        this.names = names;
        this.nextIndex = nextIndex;
    }
}

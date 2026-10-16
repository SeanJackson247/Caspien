package caspien.parser;

import caspien.token.Token;
import java.util.List;

public final class TypeArgParseResult {
    public final List<List<Token>> groups; // null if there was no '<' at all
    public final int nextIndex;
    public TypeArgParseResult(List<List<Token>> groups, int nextIndex) { }
}

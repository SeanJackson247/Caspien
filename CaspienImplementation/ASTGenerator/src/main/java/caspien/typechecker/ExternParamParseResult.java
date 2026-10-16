package caspien.typechecker;

import java.util.List;

public final class ExternParamParseResult {
    public final List<TypeInfo> fixedTypes;
    public final boolean hasVarargs;
    public ExternParamParseResult(List<TypeInfo> fixedTypes, boolean hasVarargs) { }
}

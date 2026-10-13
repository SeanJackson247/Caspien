package caspien.typechecker;

import java.util.*;

public final class ProofKillsGraph {
    public final Map<String, FuncInfo> known = new HashMap<>();
    public final Set<String> externs;
    public final java.util.function.Function<TypeInfo, Set<String>> closure;
    public ProofKillsGraph(Set<String> externs, java.util.function.Function<TypeInfo, Set<String>> closure) {
        this.externs = externs;
        this.closure = closure;
    }
}

package caspien.typechecker;

import java.util.*;

public final class ProofKillsGraph {
    public final Map<String, FuncInfo> known = new HashMap<>();
    public final Set<String> externs;
    public final TypeCheckerDeclarations closure;
    public ProofKillsGraph(Set<String> externs, TypeCheckerDeclarations closure) {
        this.externs = externs;
        this.closure = closure;
    }
}

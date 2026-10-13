package caspien.typechecker;

import caspien.token.Token;

/** One validated `throw` statement; read by validateNoThrowReachableFromGhostTableFunctions. */
public class ThrowSite {
    public final FuncInfo func;
    public final Token at;
    public ThrowSite(FuncInfo func, Token at) {
        this.func = func;
        this.at = at;
    }
}

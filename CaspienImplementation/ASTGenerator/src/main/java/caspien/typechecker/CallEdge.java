package caspien.typechecker;

import caspien.token.Token;

/** One recorded function call, tagged with the call *site's* effective safety context (from Scope.currentSafety at the moment it was checked). */
public class CallEdge {
    public final FuncInfo caller;
    public final FuncInfo callee;
    public final String safety;
    public final Token at;
    /** True only when this exact call site sits inside a "for i in dynarrayExpr{...}" loop's own body -- see Scope.insideDynArrayForLoopBody's own comment, and checkDynArrayForLoopResizeSafety below. */
    public final boolean insideDynArrayForLoop;
    public CallEdge(FuncInfo caller, FuncInfo callee, String safety, Token at, boolean insideDynArrayForLoop) { }
}

package caspien.bytecodeemitter;

import caspien.token.Token;
import java.util.List;

/** One step of a SCRIPT frame (see EmitFrame): a line to write, or a nested emission to run, in the order the old recursive code did them. */
final class EmitOp {
    static final int LINE = 0;        // text
    static final int EXPR = 1;        // tok: emitExpr
    static final int TARGET = 2;      // tok: emitAssignTarget
    static final int NULLOUT = 3;     // tok: emitOwnershipMoveNullOut
    static final int REQUIRE = 4;     // text = ghost-table decorator, tok = error position
    static final int DESTRUCT = 5;    // names, tok: emitDestructList
    static final int UNLOCK = 6;      // toks, tok: emitUnlockList
    static final int BLOCK = 7;       // toks = statement lines, flag = entry-point body
    static final int RVO_FORWARD = 8; // tok = call, text = destination pointer type: `return f()` tail forwarding

    final int kind;
    final String text;
    final Token tok;
    final List<String> names;
    final List<Token> toks;
    final boolean flag;

    EmitOp(int kind, String text, Token tok, List<String> names, List<Token> toks, boolean flag) { }
}

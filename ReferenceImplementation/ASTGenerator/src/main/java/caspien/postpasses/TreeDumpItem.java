package caspien.postpasses;

import caspien.token.Token;
import java.util.List;

/** One pending piece of output: a token, a LINE list, a flat token list, or a literal label. */
final class TreeDumpItem {
    final int kind;   // 0 token, 1 lines, 2 flat, 3 label
    final Token tok;
    final List<Token> list;
    final String label;
    final int indent;

    TreeDumpItem(int kind, Token tok, List<Token> list, String label, int indent) {
        this.kind = kind;
        this.tok = tok;
        this.list = list;
        this.label = label;
        this.indent = indent;
    }
}

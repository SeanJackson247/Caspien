package caspien.treebuilder;

import caspien.token.Token;
import java.util.List;

/** One work item of TreeBuilder's explicit task stack; the kind constants live in TreeBuilder. */
final class TreeTask {
    final int kind;
    final Token tok;
    final Token tok2;
    final List<Token> list;
    TreeTask(int kind, Token tok, Token tok2, List<Token> list) {
        this.kind = kind;
        this.tok = tok;
        this.tok2 = tok2;
        this.list = list;
    }
}

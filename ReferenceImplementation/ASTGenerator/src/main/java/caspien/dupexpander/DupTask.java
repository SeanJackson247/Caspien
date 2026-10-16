package caspien.dupexpander;

import caspien.token.Token;
import java.util.List;

/** One work item of DupExpander's explicit task stack. */
final class DupTask {
    static final int WALK = 1;           // node: visit it (expand an array literal first)
    static final int CHILDREN = 2;       // node: visit left, right, next, childs, sub
    static final int ELEMENT = 3;        // node = a dup element whose lhs was walked: add its copies to `elements`
    static final int PLAIN_ELEMENT = 4;  // node: an ordinary array element, added to `elements`
    static final int FINISH = 5;         // node = the array literal: rebuild its comma chain if a dup was seen

    final int kind;
    final Token node;
    List<Token> elements;
    boolean sawDup;

    DupTask(int kind, Token node) {
        this.kind = kind;
        this.node = node;
    }
}

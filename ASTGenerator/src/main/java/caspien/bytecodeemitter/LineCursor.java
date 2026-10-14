package caspien.bytecodeemitter;

import caspien.token.Token;
import java.util.List;

/** A position in a statement list on the explicit stack of the three function-body walkers below. */
final class LineCursor {
    final List<Token> lines;
    int index;
    final boolean popsTryBlock;

    LineCursor(List<Token> lines, boolean popsTryBlock) {
        this.lines = lines;
        this.popsTryBlock = popsTryBlock;
    }
}

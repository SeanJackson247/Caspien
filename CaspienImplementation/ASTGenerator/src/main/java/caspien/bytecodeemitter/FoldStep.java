package caspien.bytecodeemitter;

import caspien.token.Token;

/** One pending node of foldStaticConstant: state 0 = not looked at, 1 = waiting for the left operand, 2 = for the right one, 3 = for the operand of a unary minus. */
final class FoldStep {
    Token node;
    int state;
    long left;

    FoldStep(Token node) { }
}

package caspien.typechecker;

import caspien.token.Token;

/** The values of the `for` check that live from one phase to the next. */
final class ForState {
    Token loopVarTok;
    Token rangeExpr;
    boolean rootIsInto;
    boolean isSomeIndexForm;
    boolean isDynArraySource;
    Scope forScope;
    Token rangeAssign;
    Token loopVarAssign;
    Token conditionIn;
    Token increment;
    TypeInfo rangeType;
}

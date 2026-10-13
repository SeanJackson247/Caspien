package caspien.typechecker;

import caspien.token.Token;
import java.util.Set;

/** Locals of one `match @lock` check, carried between phases. */
final class LockState {
    Token receiver;
    StructInfo structInfo;
    String swapFieldName;
    String stateEnumName;
    Token openCase;
    Token closedCase;
    Token swapOp;
    Token condOp;
    Token releaseAssign;
    Scope loopBodyScope;
    Scope openBodyScope;
    Scope closedBodyScope;
    Scope savedLockClosedScope;
    Set<String> preState;
    Set<String> openBranchMoved;
    Set<String> closedBranchMoved;
    DefaultPolicySubstitution substitution;
    int retriesBefore;
}

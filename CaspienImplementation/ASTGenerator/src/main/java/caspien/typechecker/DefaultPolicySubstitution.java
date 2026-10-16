package caspien.typechecker;

import caspien.token.Token;
import java.util.List;

/** What `substituteDefaultLockMatchPolicyIfPresent` returns: the pre-loop `let` assigns (Token.preLoopInit) and the Scope they were declared in. */
public class DefaultPolicySubstitution {
    public final List<Token> preLoopInit;
    public final Scope preLoopScope;
    /** Most retries of the spin: > 0 a literal limit, -1 a run-time value (0 = not known). */
    public long spinBound;
    public DefaultPolicySubstitution(List<Token> preLoopInit, Scope preLoopScope) { }
    public DefaultPolicySubstitution withBound(long b) { return null; }
}

package caspien.typechecker;

import caspien.token.Token;
import java.util.*;
import caspien.token.MatchPattern;

public final class ProofKillsUse {
    public final int seq;
    public final int[] loops;
    public final Token at;
    public final List<MatchPattern> cands;
    public final String name;       // what to call the reference in the error (a member path like "h.p"), null = the token's text
    public ProofKillsUse(int seq, int[] loops, Token at, List<MatchPattern> cands, String name) { }
}

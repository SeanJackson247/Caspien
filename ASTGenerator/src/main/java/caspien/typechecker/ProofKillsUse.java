package caspien.typechecker;

import caspien.token.Token;
import java.util.*;

public final class ProofKillsUse {
    public final int seq;
    public final int[] loops;
    public final Token at;
    public final List<Token.MatchPattern> cands;
    public final String name;       // what to call the reference in the error (a member path like "h.p"), null = the token's text
    public ProofKillsUse(int seq, int[] loops, Token at, List<Token.MatchPattern> cands, String name) {
        this.name = name;
        this.seq = seq;
        this.loops = loops;
        this.at = at;
        this.cands = cands;
    }
}

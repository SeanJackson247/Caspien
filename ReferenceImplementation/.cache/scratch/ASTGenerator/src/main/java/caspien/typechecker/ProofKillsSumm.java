package caspien.typechecker;

import java.util.*;

/** What a call can destroy: the chain "f > g" ending in the reason, and the types involved (all = anything). */
public final class ProofKillsSumm {
    public String reason;
    public boolean all;
    public final Set<String> types = new HashSet<>();
}

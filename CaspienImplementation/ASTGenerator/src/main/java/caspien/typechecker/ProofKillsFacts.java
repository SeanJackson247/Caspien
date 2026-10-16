package caspien.typechecker;

import java.util.*;

public final class ProofKillsFacts {
    public String directKill;                       // first direct kill description, null if none
    public boolean directAll;                       // some direct kill may destroy anything
    public final Set<String> directTypes = new HashSet<>();   // otherwise the union of the types the direct kills can destroy
    public final Set<String> callees = new LinkedHashSet<>();
}

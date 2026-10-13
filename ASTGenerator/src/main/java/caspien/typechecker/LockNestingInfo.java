package caspien.typechecker;

import caspien.token.Token;
import java.util.*;

public final class LockNestingInfo {
    public final FuncInfo fn;
    public boolean acquires;                                         // takes a lock directly
    public String waitKind;                                          // first waiting operation in the body ("yield", "sleep", "par", "await"), null if none
    public final Set<String> callees = new LinkedHashSet<>();         // every resolved call target in the body
    public final List<Token> heldCalls = new ArrayList<>();           // calls made while a lock is held
    public LockNestingInfo(FuncInfo fn) {
        this.fn = fn;
    }
}

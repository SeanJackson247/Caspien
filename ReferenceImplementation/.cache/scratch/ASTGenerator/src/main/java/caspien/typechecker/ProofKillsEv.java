package caspien.typechecker;

import caspien.token.Token;
import java.util.*;

public final class ProofKillsEv {
    public final int seq;
    public final int[] loops;
    public final Token at;
    public final String what;       // description of a direct kill, or null for a call
    public final String callee;     // mangled name for a call, else null
    public final String skipSlot;   // the destroyed object lies inside what this variable refers to (a member of it): the proof for the variable itself survives
    public final Set<String> types; // struct type names the destroyed object can contain (ownership closure); null = anything
    public ProofKillsEv(int seq, int[] loops, Token at, String what, String callee, String skipSlot, Set<String> types) {
        this.types = types;
        this.seq = seq;
        this.loops = loops;
        this.at = at;
        this.what = what;
        this.callee = callee;
        this.skipSlot = skipSlot;
    }
}

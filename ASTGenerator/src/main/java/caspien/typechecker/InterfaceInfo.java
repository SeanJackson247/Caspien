package caspien.typechecker;

import caspien.token.Token;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** An interface's raw (unresolved -- 'self' isn't a concrete type yet) method signatures. */
public class InterfaceInfo {
    public String name;
    /** Final, flattened method set (own + every extended interface's, name-collision-checked). Populated by the flatten pass. */
    public final LinkedHashMap<String, RawMethodSig> methods = new LinkedHashMap<>();
    public List<String> extendsNames = new ArrayList<>();
    /** This interface's own declared methods only, kept until the flatten pass consumes them. */
    public LinkedHashMap<String, RawMethodSig> ownMethods;
    public boolean flattened;
    public Token declTok;
    /** Gates the interface itself; its methods are always public. */
    public boolean isPublic;
}

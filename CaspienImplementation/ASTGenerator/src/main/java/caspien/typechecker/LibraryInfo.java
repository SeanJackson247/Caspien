package caspien.typechecker;

import caspien.token.Token;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * "library Name { ... }": functions dispatched by bare library name ("Math.sin(x)"); no instance, no self.
 * Same privacy rules as an impl; unlimited 'extends'.
 */
public class LibraryInfo {
    public String name;
    public List<String> extendsNames = new ArrayList<>();
    /** Own functions only, name to overloads (overloaded by full signature, unlike an impl). */
    public LinkedHashMap<String, List<FuncInfo>> ownFunctions;
    /** Final, flattened function set (own + every extended library's, full-signature-collision-checked). Populated by flattenLibrary. */
    public final LinkedHashMap<String, List<FuncInfo>> functions = new LinkedHashMap<>();
    public boolean flattened;
    public Token declTok;
    /** Gates the library itself, not its own functions -- exactly mirrors ImplInfo.isPublic (see requireLibraryVisibility). */
    public boolean isPublic;
}

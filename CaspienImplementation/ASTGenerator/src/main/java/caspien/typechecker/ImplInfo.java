package caspien.typechecker;

import caspien.token.Token;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** "impl InterfaceName for ConcreteName{...}" (interfaceName set) or "impl ConcreteName{...}" (interfaceName null). */
public class ImplInfo {
    public String interfaceName;
    public String concreteName;
    public final LinkedHashMap<String, FuncInfo> methods = new LinkedHashMap<>();
    /** Static methods, in a separate map so instance/static dispatch stay structurally apart. */
    public final LinkedHashMap<String, FuncInfo> staticMethods = new LinkedHashMap<>();
    /**
     * Overload lists by bare name (includes the first method). A second same-named method with a different signature is stored in
     * `methods`/`staticMethods` under the key "name#signature". Only a bare `impl` may overload. Look up through candidates().
     */
    public final LinkedHashMap<String, List<FuncInfo>> methodOverloads = new LinkedHashMap<>();
    public final LinkedHashMap<String, List<FuncInfo>> staticMethodOverloads = new LinkedHashMap<>();
    /** Every overload of `name` in declaration order; empty if none. */
    public List<FuncInfo> candidates(String name, boolean isStatic) { return null; }
    public Token declTok;
    /** Outer visibility gate, checked in addition to each method's own isPublic. */
    public boolean isPublic;
    /** The impl's safety tag, used when checking a generic method instantiated at a call site. */
    public String safetyTag;
    /** Generic methods (own impl-level type params) held back by name; materialized per call site by instantiateGenericMethod. See docs/TypeChecker.md, "Generic impl methods". */
    public final LinkedHashMap<String, Token> genericMethodTemplates = new LinkedHashMap<>();
    /** Memoized instantiations of genericMethodTemplates, keyed "method<arg1,arg2>". */
    public final LinkedHashMap<String, FuncInfo> genericMethodInstances = new LinkedHashMap<>();
}

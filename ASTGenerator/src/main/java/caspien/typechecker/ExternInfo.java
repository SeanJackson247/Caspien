package caspien.typechecker;

import caspien.token.Token;
import java.util.ArrayList;
import java.util.List;

/** "extern name(types...) ret": a signature the linked assembly provides. No body, no mangling, no overloading; globally unique by name. */
public class ExternInfo {
    public String name;
    /** Literal link symbol: "@link_name(x)" if present, else `name`. */
    public String linkName;
    /** Parameter types only; extern parameters are unnamed. */
    public final List<TypeInfo> paramTypes = new ArrayList<>();
    /** Signature ended in '...': extra unchecked arguments allowed at call sites. */
    public boolean hasVarargs;
    public TypeInfo returnType;
    public Token declTok;
    /** Resolved calling convention (decorator or config default). */
    public String callConvention;
}

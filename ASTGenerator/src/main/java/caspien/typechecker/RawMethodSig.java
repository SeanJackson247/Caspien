package caspien.typechecker;

import caspien.token.Token;
import java.util.List;

public class RawMethodSig {
    public Token nameTok;
    public Token paramsTok;
    public List<Token> returnTypeTokens;
    /** True if this came from a "@default func ...{...}" (a body-bearing method) rather than a plain signature-only declaration. */
    public boolean isDefault;
    /** Only for isDefault: the interface's gathered "func" token, deep-cloned for each impl that omits the method (see materializeDefaultMethod). */
    public Token defaultBodyTok;
    /** Decorators of the interface method declaration (e.g. @lock/@unlock on @guard interfaces), checked against the implementing method. */
    public List<Token.Decorator> decorators;
    /** "static" signature; kept in the same maps as instance signatures (a name clash is still an error), this flag keeps the two kinds apart. */
    public boolean isStatic;
}

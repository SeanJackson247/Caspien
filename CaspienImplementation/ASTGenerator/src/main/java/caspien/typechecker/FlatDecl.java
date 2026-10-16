package caspien.typechecker;

import caspien.token.Token;

/** One root-level declaration line, tagged with its enclosing unsafe/safe context (if any). */
public class FlatDecl {
    public final Token lineTok;
    public final String safety;
    public FlatDecl(Token lineTok, String safety) { }
}

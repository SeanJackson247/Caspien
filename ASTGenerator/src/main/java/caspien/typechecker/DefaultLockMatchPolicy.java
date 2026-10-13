package caspien.typechecker;

import caspien.token.Token;
import java.util.List;

/**
 * "impl default match @lock" policy template for a match @lock CLOSED case ("CLOSED:default" / "CLOSED:default(args)").
 * Kept as raw tokens, never checked itself; a fresh clone is checked per use site. See docs/TypeChecker.md, "Default lock-match policy".
 */
public class DefaultLockMatchPolicy {
    /** The struct this policy is declared for (StructInfo.defaultLockMatchPolicy's own owner). */
    public final String structName;
    /** Raw "(" token of the parameter list; the first parameter is the automatic per-iteration "tries" counter, never supplied by the call site. */
    public final Token rawParamsTok;
    /** Raw body lines, exactly the parser's `blockTok.childs`; never run through gatherKeywordBlocks/RPN/tree-build. */
    public final List<Token> rawBodyLines;
    public final Token declTok;
    public DefaultLockMatchPolicy(String structName, Token rawParamsTok, List<Token> rawBodyLines, Token declTok) {
        this.structName = structName;
        this.rawParamsTok = rawParamsTok;
        this.rawBodyLines = rawBodyLines;
        this.declTok = declTok;
    }
}

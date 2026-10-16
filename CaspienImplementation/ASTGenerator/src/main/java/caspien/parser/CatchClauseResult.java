package caspien.parser;

import caspien.token.Token;
import java.util.List;

/** The result of parsing one "catch(e) { ... }" clause -- shared by a real "try EXPR catch(e){...}" (`gatherTryCatchSpan`) and a standalone "?catch(e){...}" declaration (see `gatherKeywordBlocks`'s own "?" handling). */
public final class CatchClauseResult {
    public final String paramName;
    public final List<Token> body;

    public CatchClauseResult(String paramName, List<Token> body) { }
}

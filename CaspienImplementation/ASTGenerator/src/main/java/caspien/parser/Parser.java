package caspien.parser;
import caspien.token.Lexeme;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import caspien.token.Decorator;

/**
 * Stage 2 of the front end (public hub; the work is in ParserLines and ParserBlocks, see README.md): nests the Lexer's flat tokens, splits them into LINE tokens,
 * strips decorators, merges let/import/generic syntax and gathers keyword blocks.
 * Pass order is in parse(); see docs/Parser.md, "Pipeline and scoping".
 */

public final class Parser {
    final ParserLines lines = new ParserLines(this);
    final ParserBlocks blocks = new ParserBlocks(this);

    /** The public entry points: the pipeline and the line gatherer live in ParserLines. */
    public static final String CONSTRUCTOR_NAME_PREFIX = ParserLines.CONSTRUCTOR_NAME_PREFIX;

    public List<Token> parse(List<Lexeme> lexerOutput) { return null; }

    public List<Token> gatherStatementLines(List<Token> statementLines) { return null; }


    /** True for "impl default match @lock ..." ("default" and "lock" are ordinary VARREFs, so this is shape-based). */
    public boolean isDefaultLockMatchImplStart(List<Token> tokens) { return false; }

    /**
     * "impl default match @lock T{ CLOSED:(params)=>{...} }" -- a policy template kept raw (TypeChecker deep-clones it per use site).
     * See docs/Parser.md, "Default lock policy".
     */
    public Token gatherDefaultLockMatchImpl(List<Token> tokens, Token implTok) { return null; }


}

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

    public List<Token> parse(List<Lexeme> lexerOutput) {
        return lines.parse(lexerOutput);
    }

    public List<Token> gatherStatementLines(List<Token> statementLines) {
        return lines.gatherStatementLines(statementLines);
    }


    /** True for "impl default match @lock ..." ("default" and "lock" are ordinary VARREFs, so this is shape-based). */
    public boolean isDefaultLockMatchImplStart(List<Token> tokens) {
        return tokens.size() > 4
                && tokens.get(1).type == TokenType.VARREF && tokens.get(1).text.equals("default")
                && tokens.get(2).type == TokenType.KEYWORD && tokens.get(2).text.equals("match")
                && tokens.get(3).type == TokenType.VARREF && tokens.get(3).text.equals("lock");
    }

    /**
     * "impl default match @lock T{ CLOSED:(params)=>{...} }" -- a policy template kept raw (TypeChecker deep-clones it per use site).
     * See docs/Parser.md, "Default lock policy".
     */
    public Token gatherDefaultLockMatchImpl(List<Token> tokens, Token implTok) {
        if (tokens.size() < 6 || tokens.get(4).type != TokenType.VARREF) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "expected a struct name after 'impl default match @lock'");
        }
        Token typeNameTok = tokens.get(4);
        Token blockTok = blocks.requireTrailingBlock(tokens, implTok);
        if (tokens.indexOf(blockTok) != 5) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "unexpected tokens between 'impl default match @lock " + typeNameTok.text + "' and '{'");
        }

        List<Token> flat = new ArrayList<>();
        for (Token line : blockTok.childs) {
            flat.addAll(line.childs);
        }
        if (flat.isEmpty() || flat.get(0).type != TokenType.VARREF || !flat.get(0).text.equals("CLOSED")) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "'impl default match @lock' requires exactly one case, 'CLOSED:(params)=>{...}'");
        }
        Token closedLabel = flat.get(0);
        int idx = 1;
        if (idx >= flat.size() || flat.get(idx).type != TokenType.OPERATOR || !flat.get(idx).text.equals(":")) {
            throw new CompilerException("parse", closedLabel.file, closedLabel.line,
                    "expected ':' after 'CLOSED' in 'impl default match @lock'");
        }
        idx++;
        if (idx >= flat.size() || flat.get(idx).type != TokenType.DELINEATOR || !flat.get(idx).text.equals("(")) {
            throw new CompilerException("parse", closedLabel.file, closedLabel.line,
                    "expected a parameter list '(...)' after 'CLOSED:' in 'impl default match @lock'");
        }
        Token paramsTok = flat.get(idx);
        idx++;
        if (idx >= flat.size() || flat.get(idx).type != TokenType.OPERATOR || !flat.get(idx).text.equals("=>")) {
            throw new CompilerException("parse", closedLabel.file, closedLabel.line,
                    "expected '=>' after the parameter list in 'impl default match @lock'");
        }
        idx++;
        if (idx >= flat.size() || flat.get(idx).type != TokenType.DELINEATOR || !flat.get(idx).text.equals("{")) {
            throw new CompilerException("parse", closedLabel.file, closedLabel.line,
                    "expected a '{...}' body after '=>' in 'impl default match @lock'");
        }
        Token policyBodyBlock = flat.get(idx);
        idx++;
        if (idx != flat.size()) {
            throw new CompilerException("parse", closedLabel.file, closedLabel.line,
                    "unexpected tokens after 'impl default match @lock's own 'CLOSED' case");
        }

        Token gathered = new Token(TokenType.KEYWORD, "impl_default_lock_match", implTok.line, implTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(typeNameTok);
        gathered.sub.add(paramsTok);
        gathered.childs = policyBodyBlock.childs; // raw, ungathered LINE tokens -- see the doc comment above
        gathered.decorators = implTok.decorators;
        gathered.pinnedComments = implTok.pinnedComments;
        return gathered;
    }


}

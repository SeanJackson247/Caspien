package caspien.parser;
import caspien.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    public List<Token> parse(List<Token> lexerOutput) {
        return lines.parse(lexerOutput);
    }

    public List<Token> gatherStatementLines(List<Token> statementLines) {
        return lines.gatherStatementLines(statementLines);
    }

    /**
     * Gathers a constructor declaration into a "func" token named CONSTRUCTOR_NAME_PREFIX + struct name (generic ones are wrapped in an "impl" token).
     * The return type must be written "self" and is replaced by the struct name. See docs/Parser.md, "Constructors".
     */
    public Token gatherConstructorImpl(List<Token> tokens, Token implTok) {
        ParserLines.TypeParamParseResult implTypeParams = lines.parseOptionalTypeParams(tokens, 1);
        int idx = implTypeParams.nextIndex;
        // isConstructorImplStart already confirmed tokens[idx]=="constructor",
        // tokens[idx+1]=="for" using this identical parse.
        idx += 2;

        if (idx >= tokens.size() || tokens.get(idx).type != TokenType.VARREF) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "expected a struct name after 'impl"
                            + (implTypeParams.names != null ? "<...>" : "") + " constructor for'");
        }
        Token concreteNameTok = tokens.get(idx);
        idx++;
        ParserLines.TypeArgParseResult concreteArgsResult = lines.parseOptionalTypeArgsRaw(tokens, idx);
        idx = concreteArgsResult.nextIndex;
        List<List<Token>> concreteArgs = concreteArgsResult.groups;

        if (implTypeParams.names == null && concreteArgs != null) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "'impl constructor for " + concreteNameTok.text + "<...>' -- a constructor for a generic "
                            + "struct needs an 'impl<...>' header (write 'impl<...> constructor for "
                            + concreteNameTok.text + "<...>(...) self {...}')");
        }
        if (implTypeParams.names != null && concreteArgs == null) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "'impl<...> constructor for " + concreteNameTok.text + "' -- a generic constructor's target "
                            + "struct must itself be written with type arguments (e.g. '" + concreteNameTok.text
                            + "<" + String.join(",", implTypeParams.names) + ">')");
        }

        if (idx >= tokens.size() || tokens.get(idx).type != TokenType.DELINEATOR || !tokens.get(idx).text.equals("(")) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "expected a parameter list '(...)' after 'impl constructor for " + concreteNameTok.text + "'");
        }
        Token paramsTok = tokens.get(idx);
        idx++;
        Token blockTok = blocks.requireTrailingBlock(tokens, implTok);
        int blockIdx = tokens.indexOf(blockTok);
        List<Token> returnTypeTokens = tokens.subList(idx, blockIdx);
        if (returnTypeTokens.size() != 1 || returnTypeTokens.get(0).type != TokenType.VARREF
                || !returnTypeTokens.get(0).text.equals("self")) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "a constructor's return type must be 'self' (got "
                            + (returnTypeTokens.isEmpty() ? "nothing" : "'" + returnTypeTokens.get(0).text + "'")
                            + ")");
        }
        Token selfTok = returnTypeTokens.get(0);

        Token funcKw = new Token(TokenType.KEYWORD, "func", implTok.line, implTok.file);
        funcKw.decorators = implTok.decorators;
        funcKw.pinnedComments = implTok.pinnedComments;
        Token nameTok = new Token(TokenType.VARREF, ParserLines.CONSTRUCTOR_NAME_PREFIX + concreteNameTok.text,
                implTok.line, implTok.file);
        Token returnTypeTok = new Token(TokenType.VARREF, concreteNameTok.text, selfTok.line, selfTok.file);
            // Set genericArgs directly: a one-token return span gives GenericsExpander.scanTypeTokenSpan nothing to match.
        if (concreteArgs != null) {
            returnTypeTok.genericArgs = concreteArgs;
        }

        List<Token> synth = new ArrayList<>();
        synth.add(funcKw);
        synth.add(nameTok);
        synth.add(paramsTok);
        synth.add(returnTypeTok);
        synth.add(blockTok);

        Token gathered = blocks.gatherFunc(synth, funcKw);
        gathered.isConstructorDecl = true;
        gathered.constructorConcreteName = concreteNameTok.text;

        if (implTypeParams.names == null) {
            return gathered;
        }

            // Generic case: wrap in an impl-shaped token so the impl-template machinery handles it (see doc comment).
        Token implWrapperConcreteNameTok = new Token(TokenType.VARREF, concreteNameTok.text,
                concreteNameTok.line, concreteNameTok.file);
        implWrapperConcreteNameTok.genericArgs = concreteArgs;

        Token implWrapper = new Token(TokenType.KEYWORD, "impl", implTok.line, implTok.file);
        implWrapper.sub = new ArrayList<>();
        implWrapper.sub.add(implWrapperConcreteNameTok);
        implWrapper.typeParams = implTypeParams.names;
        implWrapper.typeParamBounds = implTypeParams.bounds;
        // The wrapper "impl" only accepts impl-level decorators (@pub);
        // function-level ones such as @throws belong on the constructor
        // function itself, which keeps the full list (funcKw above).
        if (implTok.decorators != null) {
            List<Token.Decorator> implLevel = new ArrayList<>();
            for (Token.Decorator d : implTok.decorators) {
                if (d.name.equals("pub")) {
                    implLevel.add(d);
                }
            }
            implWrapper.decorators = implLevel.isEmpty() ? null : implLevel;
        }
        implWrapper.pinnedComments = implTok.pinnedComments;
        implWrapper.childs = new ArrayList<>();
        implWrapper.childs.add(gathered);
        return implWrapper;
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

    /** "library NAME<T?>{ func...; extends T; }" -- functions dispatched by library name, never through an instance. */
    public Token gatherLibrary(List<Token> tokens, Token libraryTok) {
        if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", libraryTok.file, libraryTok.line,
                    "expected a name after 'library'");
        }
        Token nameTok = tokens.get(1);
        ParserLines.TypeParamParseResult typeParams = lines.parseOptionalTypeParams(tokens, 2);
        Token blockTok = blocks.requireTrailingBlock(tokens, libraryTok);
        if (tokens.indexOf(blockTok) != typeParams.nextIndex) {
            throw new CompilerException("parse", libraryTok.file, libraryTok.line,
                    "unexpected tokens between 'library " + nameTok.text + "' and '{'");
        }

        List<Token> functions = new ArrayList<>();
        List<String> extendsNames = new ArrayList<>();
        for (Token line : blockTok.childs) {
            List<Token> lineTokens = line.childs;
            if (!lineTokens.isEmpty() && lineTokens.get(0).type == TokenType.KEYWORD
                    && lineTokens.get(0).text.equals("extends")) {
                if (lineTokens.size() != 2 || lineTokens.get(1).type != TokenType.VARREF) {
                    throw new CompilerException("parse", lineTokens.get(0).file, lineTokens.get(0).line,
                            "expected 'extends TypeName'");
                }
                extendsNames.add(lineTokens.get(1).text);
                continue;
            }
            if (lineTokens.isEmpty() || lineTokens.get(0).type != TokenType.KEYWORD
                    || !lineTokens.get(0).text.equals("func")) {
                throw new CompilerException("parse", line.file, line.line,
                        "only 'func' declarations and 'extends' statements are allowed directly inside a "
                                + "'library' block");
            }
            functions.add(blocks.gatherFunc(lineTokens, lineTokens.get(0)));
        }

        Token gathered = new Token(TokenType.KEYWORD, "library", libraryTok.line, libraryTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(nameTok);
        gathered.typeParams = typeParams.names; // null if not generic
        gathered.typeParamBounds = typeParams.bounds;
        gathered.extendsNames = extendsNames;
        gathered.decorators = libraryTok.decorators;
        gathered.childs = functions;
        gathered.pinnedComments = libraryTok.pinnedComments;
        return gathered;
    }
}

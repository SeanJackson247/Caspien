package caspien.parser;
import caspien.config.CompilerException;
import caspien.token.Decorator;
import caspien.token.Token;
import caspien.token.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 2 of the front end: nests the Lexer's flat tokens, splits them into LINE tokens,
 * strips decorators, merges let/import/generic syntax and gathers keyword blocks.
 * Pass order is in parse(); see docs/Parser.md, "Pipeline and scoping".
 */

/** ParserBlocks: keyword-block gathering: statements, structs, enums, interfaces, impls (part 2 of 3; see README.md in this folder). */
final class ParserBlocks {
    private final Parser hub;

    ParserBlocks(Parser hub) {
        this.hub = hub;
    }










    /** True when `tokens` open a "match @lock EXPR{...}" statement; rejects the removed "lock x{...}" and "lock match" spellings. See docs/Parser.md, "match @lock". */
    public boolean isLockStatementStart(List<Token> tokens) {
        if (tokens.size() < 3) {
            return false;
        }
        Token first = tokens.get(0);
        Token second = tokens.get(1);
            // "match @lock EXPR{...}": the "@" operator keeps "match lock{...}" / "match lock(x){...}" ordinary matches.
        if (first.type == TokenType.KEYWORD && first.text.equals("match")) {
            if (tokens.size() >= 4 && second.type == TokenType.OPERATOR && second.text.equals("@")
                    && tokens.get(2).type == TokenType.VARREF && tokens.get(2).text.equals("lock")) {
                Token last = tokens.get(tokens.size() - 1);
                if (!(last.type == TokenType.DELINEATOR && last.text.equals("{"))) {
                    return false;
                }
                if (tokens.size() < 5) {
                    throw new CompilerException("parse", first.file, first.line,
                            "'match @lock' requires an expression before its '{'");
                }
                return true;
            }
            if (tokens.size() >= 4 && second.type == TokenType.VARREF && second.text.equals("lock")
                    && tokens.get(2).type == TokenType.VARREF
                    && tokens.get(tokens.size() - 1).type == TokenType.DELINEATOR
                    && tokens.get(tokens.size() - 1).text.equals("{")) {
                throw new CompilerException("parse", first.file, first.line,
                        "'match lock EXPR{...}' is now spelled 'match @lock EXPR{...}'");
            }
            return false;
        }
        if (first.type != TokenType.VARREF || !first.text.equals("lock")) {
            return false;
        }
        if (second.type == TokenType.DELINEATOR && second.text.equals("(")) {
            return false;
        }
        if (second.type == TokenType.KEYWORD && second.text.equals("match")) {
            throw new CompilerException("parse", first.file, first.line,
                    "'lock match' has been renamed -- write 'match @lock EXPR{...}'");
        }
        Token last = tokens.get(tokens.size() - 1);
        if (last.type == TokenType.DELINEATOR && last.text.equals("{")) {
            throw new CompilerException("parse", first.file, first.line,
                    "the 'lock x{...}' statement has been removed (with '@guard'); use 'match @lock x{...}', "
                            + "or an owned value whose '@drop' hook unlocks");
        }
        return false;
    }


    /** True when a match body's own first non-empty line starts with a valid case-label shape -- "VARREF ':' '{'" or, for the `|`-chained form, "VARREF ('|' VARREF)* ':' '{'" -- the case-block form's own distinguishing shape, checked purely structurally (no type information exists yet at parse time). */
    public boolean isCaseMatchBody(List<Token> bodyLines) {
        for (Token lineTok : bodyLines) {
            List<Token> flat = lineTok.childs;
            if (flat.isEmpty()) {
                continue;
            }
            if (flat.isEmpty() || flat.get(0).type != TokenType.VARREF) {
                return false;
            }
            int i = 1;
            while (i + 1 < flat.size() && flat.get(i).type == TokenType.OPERATOR && flat.get(i).text.equals("|")
                    && flat.get(i + 1).type == TokenType.VARREF) {
                i += 2;
            }
            return i + 1 < flat.size() && flat.get(i).type == TokenType.OPERATOR && flat.get(i).text.equals(":")
                    && flat.get(i + 1).type == TokenType.DELINEATOR && flat.get(i + 1).text.equals("{");
        }
        return false;
    }


    public String labelChainText(List<Token> labels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(labels.get(i).text);
        }
        return sb.toString();
    }


    /** "extern name(params) ret" -- header-only form of gatherFunc; decorators (e.g. @call_convention) are validated by TypeChecker.collectExtern. See docs/Parser.md, "Declaration gatherers". */
    public Token gatherExtern(List<Token> tokens, Token externTok) {
        if (tokens.size() < 3 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", externTok.file, externTok.line,
                    "expected a function name after 'extern'");
        }
        Token nameTok = tokens.get(1);
        if (tokens.get(2).type != TokenType.DELINEATOR || !tokens.get(2).text.equals("(")) {
            throw new CompilerException("parse", externTok.file, externTok.line,
                    "expected '(' after 'extern " + nameTok.text + "'");
        }
        Token paramsTok = tokens.get(2);
        List<Token> returnTypeTokens = new ArrayList<>(tokens.subList(3, tokens.size()));

        Token gathered = new Token(TokenType.KEYWORD, "extern", externTok.line, externTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(nameTok);
        gathered.sub.add(paramsTok);
        gathered.sub.addAll(returnTypeTokens);
        gathered.decorators = externTok.decorators;
        gathered.pinnedComments = externTok.pinnedComments;
        return gathered;
    }

    /** "export foo" -- a bare name; everything else is checked in TypeChecker.collectExport. */
    public Token gatherExport(List<Token> tokens, Token exportTok) {
        if (tokens.size() != 2 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", exportTok.file, exportTok.line,
                    "expected exactly one function name after 'export'");
        }
        Token nameTok = tokens.get(1);
        Token gathered = new Token(TokenType.KEYWORD, "export", exportTok.line, exportTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(nameTok);
        gathered.pinnedComments = exportTok.pinnedComments;
        return gathered;
    }

    /** "ASM [name] { block }" or "ASM [name] \"path\"": the block arrives pre-scanned as an ASM_BLOCK token (Lexer.scanAsmBlock); the file form is validated by TypeChecker. */
    public Token gatherAsm(List<Token> tokens, Token asmTok) {
        int idx = 1;
        Token nameTok = null;
        if (idx < tokens.size() && tokens.get(idx).type == TokenType.VARREF) {
            nameTok = tokens.get(idx);
            idx++;
        }
        if (idx >= tokens.size()) {
            throw new CompilerException("parse", asmTok.file, asmTok.line,
                    "expected an 'ASM' block ('{...}') or a file path (a string literal) after 'ASM"
                            + (nameTok != null ? " " + nameTok.text : "") + "'");
        }
        Token bodyTok = tokens.get(idx);
        Token gathered = new Token(TokenType.KEYWORD, "ASM", asmTok.line, asmTok.file);
        gathered.sub = new ArrayList<>();
        if (nameTok != null) {
            gathered.sub.add(nameTok);
        }
        if (bodyTok.type == TokenType.ASM_BLOCK) {
            gathered.asmBlockText = bodyTok.text;
        } else if (bodyTok.type == TokenType.STRING) {
            gathered.asmFilePath = bodyTok.literalValue != null ? bodyTok.literalValue : bodyTok.text;
        } else {
            throw new CompilerException("parse", asmTok.file, asmTok.line,
                    "expected an 'ASM' block ('{...}') or a file path (a string literal) after 'ASM"
                            + (nameTok != null ? " " + nameTok.text : "") + "'");
        }
        if (idx + 1 != tokens.size()) {
            throw new CompilerException("parse", asmTok.file, asmTok.line,
                    "unexpected tokens after 'ASM" + (nameTok != null ? " " + nameTok.text : "")
                            + " ...'s body");
        }
        gathered.pinnedComments = asmTok.pinnedComments;
        return gathered;
    }

    /** Parses an optional "implements A, B, ..." clause (a contract checked in TypeChecker). */
    public ImplementsParseResult parseOptionalImplementsClause(List<Token> tokens, int idx) {
        if (idx >= tokens.size() || tokens.get(idx).type != TokenType.OPERATOR
                || !tokens.get(idx).text.equals("implements")) {
            return new ImplementsParseResult(null, idx);
        }
        List<String> names = new ArrayList<>();
        int j = idx + 1;
        boolean expectName = true;
        while (true) {
            if (j >= tokens.size()) {
                throw new CompilerException("parse", tokens.get(idx).file, tokens.get(idx).line,
                        "expected an interface name after 'implements'");
            }
            Token t = tokens.get(j);
            if (expectName) {
                if (t.type != TokenType.VARREF) {
                    throw new CompilerException("parse", t.file, t.line,
                            "expected an interface name in 'implements' clause, found '" + t.text + "'");
                }
                names.add(t.text);
                j++;
                expectName = false;
            } else if (t.type == TokenType.OPERATOR && t.text.equals(",")) {
                j++;
                expectName = true;
            } else {
                break; // end of clause; whatever follows (the '{') is the caller's concern
            }
        }
        return new ImplementsParseResult(names, j);
    }

    /** Body for "struct"; a member line starting with "extends" is a parse error (inheritance was removed). */
    public Token gatherStructLike(List<Token> tokens, Token kwTok, String kindText) {
        if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", kwTok.file, kwTok.line,
                    "expected a name after '" + kindText + "'");
        }
        Token nameTok = tokens.get(1);
        TypeParamParseResult typeParams = hub.lines.parseOptionalTypeParams(tokens, 2);
        ImplementsParseResult implementsClause = parseOptionalImplementsClause(tokens, typeParams.nextIndex);
        Token blockTok = requireTrailingBlock(tokens, kwTok);
        if (tokens.indexOf(blockTok) != implementsClause.nextIndex) {
            throw new CompilerException("parse", kwTok.file, kwTok.line,
                    "unexpected tokens between '" + kindText + " " + nameTok.text + "' and '{'");
        }

        List<Token> memberLines = new ArrayList<>();
        for (Token memberLine : blockTok.childs) {
            List<Token> lineTokens = memberLine.childs;
            if (!lineTokens.isEmpty() && lineTokens.get(0).type == TokenType.KEYWORD
                    && lineTokens.get(0).text.equals("extends")) {
                throw new CompilerException("parse", lineTokens.get(0).file, lineTokens.get(0).line,
                        "struct 'extends' has been removed; use composition and interfaces");
            }
            memberLines.add(memberLine);
        }

        Token gathered = new Token(TokenType.KEYWORD, kindText, kwTok.line, kwTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(nameTok);
        gathered.typeParams = typeParams.names; // null if not generic
        gathered.typeParamBounds = typeParams.bounds;
        gathered.implementsNames = implementsClause.names;
        gathered.decorators = kwTok.decorators;
        gathered.childs = memberLines; // member declaration lines, untouched
        gathered.pinnedComments = kwTok.pinnedComments;
        return gathered;
    }

    public Token gatherStruct(List<Token> tokens, Token structTok) {
        return gatherStructLike(tokens, structTok, "struct");
    }

    /** "type NAME <target tokens>" -- a one-liner; the raw target type tokens are read by TypeChecker. */
    public Token gatherType(List<Token> tokens, Token typeTok) {
        if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", typeTok.file, typeTok.line,
                    "expected an alias name after 'type'");
        }
        Token nameTok = tokens.get(1);
        List<Token> targetTokens = new ArrayList<>(tokens.subList(2, tokens.size()));
        if (targetTokens.isEmpty()) {
            throw new CompilerException("parse", typeTok.file, typeTok.line,
                    "expected a target type after 'type " + nameTok.text + "'");
        }
        Token gathered = new Token(TokenType.KEYWORD, "type", typeTok.line, typeTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(nameTok);
        gathered.sub.addAll(targetTokens);
        gathered.decorators = typeTok.decorators;
        gathered.pinnedComments = typeTok.pinnedComments;
        return gathered;
    }

    public Token gatherConst(List<Token> tokens, Token constTok) {
        if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", constTok.file, constTok.line,
                    "expected a name after 'const'");
        }
        Token nameTok = tokens.get(1);
        List<Token> exprTokens = new ArrayList<>(tokens.subList(2, tokens.size()));
        if (exprTokens.isEmpty()) {
            throw new CompilerException("parse", constTok.file, constTok.line,
                    "expected an expression after 'const " + nameTok.text + "'");
        }
            // Like "if": .sub holds the name, .childs the raw expression tokens (processed like an if condition).
        Token gathered = new Token(TokenType.KEYWORD, "const", constTok.line, constTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(nameTok);
        gathered.childs = exprTokens;
        gathered.decorators = constTok.decorators;
        gathered.pinnedComments = constTok.pinnedComments;
        return gathered;
    }

    public Token gatherEnum(List<Token> tokens, Token enumTok) {
        if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", enumTok.file, enumTok.line,
                    "expected enum name after 'enum'");
        }
        Token nameTok = tokens.get(1);
        Token blockTok = requireTrailingBlock(tokens, enumTok);
        if (tokens.indexOf(blockTok) != 2) {
            throw new CompilerException("parse", enumTok.file, enumTok.line,
                    "unexpected tokens between enum name and '{'");
        }
        List<Token> variants = new ArrayList<>();
            // Two grammars, chosen once per enum: nested ("NAME{...}") if any variant line has a "{" second token, else flat.
            // See docs/Parser.md, "Enums".
        if (hasNestedEnumVariant(blockTok.childs)) {
            gatherNestedEnumVariants(blockTok.childs, variants);
        } else {
            for (Token variantLine : blockTok.childs) {
                List<Token> lineTokens = variantLine.childs;
                for (int vi = 0; vi < lineTokens.size(); vi++) {
                    Token t = lineTokens.get(vi);
                    if (t.type == TokenType.OPERATOR && t.text.equals(",")) {
                        continue; // separator between variants, carries no meaning once flattened
                    }
                    if (t.type != TokenType.VARREF) {
                        throw new CompilerException("parse", t.file, t.line,
                                "unexpected token in enum body: '" + t.text + "'");
                    }
                    variants.add(t);
            // Optional ":" or "=" then INTEGER or INTEGER..INTEGER, attached to the variant token; TypeChecker.collectEnum validates.
                    if (vi + 1 < lineTokens.size() && lineTokens.get(vi + 1).type == TokenType.OPERATOR
                            && (lineTokens.get(vi + 1).text.equals(":") || lineTokens.get(vi + 1).text.equals("="))) {
                        Token sepTok = lineTokens.get(vi + 1);
                        if (vi + 2 >= lineTokens.size() || lineTokens.get(vi + 2).type != TokenType.INTEGER) {
                            throw new CompilerException("parse", sepTok.file, sepTok.line,
                                    "'" + t.text + "" + sepTok.text + "' expects an integer literal "
                                            + "(positive only), or a range literal (e.g. '100..2000'), "
                                            + "after it");
                        }
            // Range value "NAME: A..B": recognized on raw tokens, since enum bodies never go through the expression pipeline.
                        if (vi + 4 < lineTokens.size() && lineTokens.get(vi + 3).type == TokenType.OPERATOR
                                && lineTokens.get(vi + 3).text.equals("..")
                                && lineTokens.get(vi + 4).type == TokenType.INTEGER) {
                            t.explicitRangeStart = ParserLines.enumLiteralValue(lineTokens.get(vi + 2));
                            t.explicitRangeEnd = ParserLines.enumLiteralValue(lineTokens.get(vi + 4));
                            vi += 4;
                        } else {
                            t.explicitEnumValue = ParserLines.enumLiteralValue(lineTokens.get(vi + 2));
                            vi += 2;
                        }
                    }
                }
            }
        }
        Token gathered = new Token(TokenType.KEYWORD, "enum", enumTok.line, enumTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(nameTok);
        gathered.childs = variants;
        gathered.decorators = enumTok.decorators;
        gathered.pinnedComments = enumTok.pinnedComments;
        return gathered;
    }

    /** True if any of an enum body's own variant lines uses the nested-hierarchy shape ("NAME{...}"). */
    public boolean hasNestedEnumVariant(List<Token> variantLines) {
        for (Token variantLine : variantLines) {
            List<Token> lineTokens = variantLine.childs;
            if (lineTokens.size() >= 2 && lineTokens.get(1).type == TokenType.DELINEATOR
                    && lineTokens.get(1).text.equals("{")) {
                return true;
            }
        }
        return false;
    }



    public Token gatherInterfaceMethod(List<Token> tokens, Token interfaceTok) {
        if (tokens.isEmpty() || tokens.get(0).type != TokenType.VARREF) {
            throw new CompilerException("parse", interfaceTok.file, interfaceTok.line,
                    "expected a method signature (e.g. 'name(params) returnType;') in interface body");
        }
        Token nameTok = tokens.get(0);
        if (tokens.size() < 2 || tokens.get(1).type != TokenType.DELINEATOR || !tokens.get(1).text.equals("(")) {
            throw new CompilerException("parse", nameTok.file, nameTok.line,
                    "expected '(' after method name '" + nameTok.text + "'");
        }
        Token paramsTok = tokens.get(1);
        List<Token> returnTypeTokens = new ArrayList<>(tokens.subList(2, tokens.size()));

        Token gathered = new Token(TokenType.KEYWORD, "interface_method", nameTok.line, nameTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(nameTok);
        gathered.sub.add(paramsTok);
        gathered.sub.addAll(returnTypeTokens);
        gathered.decorators = nameTok.decorators;
        gathered.pinnedComments = nameTok.pinnedComments;
        return gathered;
    }


    /** True right after "impl" for "impl [<T>] constructor for Name(...) self {...}" (an interface literally named "constructor" is the accepted collision). */
    public boolean isConstructorImplStart(List<Token> tokens) {
            // Peek only: parseOptionalTypeParams has no side effects.
        TypeParamParseResult implTypeParams = hub.lines.parseOptionalTypeParams(tokens, 1);
        int idx = implTypeParams.nextIndex;
        return tokens.size() > idx + 1
                && tokens.get(idx).type == TokenType.VARREF && tokens.get(idx).text.equals("constructor")
                && tokens.get(idx + 1).type == TokenType.KEYWORD && tokens.get(idx + 1).text.equals("for");
    }
    // ---- the gatherers' explicit stack ---------------------------------------------------------------------

    /** Gathers a statement list (bottom-up), see docs/Parser.md, "Keyword-block token shapes". */
    @SuppressWarnings("unchecked")
    public List<Token> gatherKeywordBlocks(List<Token> lines, boolean isRoot) {
        return (List<Token>) runGather(keywordBlocksFrame(lines, isRoot));
    }

    public Token gatherFunc(List<Token> tokens, Token funcTok) {
        return (Token) runGather(funcFrame(tokens, funcTok));
    }

    public Token gatherConstructorImpl(List<Token> tokens, Token implTok) {
        GatherFrame f = new GatherFrame(GatherFrame.CONSTRUCTOR);
        f.tokens = tokens;
        f.tok = implTok;
        return (Token) runGather(f);
    }

    public Token gatherLibrary(List<Token> tokens, Token libraryTok) {
        GatherFrame f = new GatherFrame(GatherFrame.LIBRARY);
        f.tokens = tokens;
        f.tok = libraryTok;
        return (Token) runGather(f);
    }

    private GatherFrame keywordBlocksFrame(List<Token> lines, boolean isRoot) {
        GatherFrame f = new GatherFrame(GatherFrame.KEYWORD_BLOCKS);
        f.lines = lines;
        f.isRoot = isRoot;
        return f;
    }

    private GatherFrame funcFrame(List<Token> tokens, Token funcTok) {
        GatherFrame f = new GatherFrame(GatherFrame.FUNC);
        f.tokens = tokens;
        f.tok = funcTok;
        return f;
    }

    /** Runs gatherer frames depth-first: a step returns a child frame to run first (its answer arrives in `got`), or null when it has finished. */
    private Object runGather(GatherFrame root) {
        Deque<GatherFrame> stack = new ArrayDeque<>();
        stack.push(root);
        while (true) {
            GatherFrame fr = stack.peek();
            GatherFrame child = stepGather(fr);
            if (child != null) {
                stack.push(child);
                continue;
            }
            stack.pop();
            if (stack.isEmpty()) {
                return fr.ret;
            }
            GatherFrame parent = stack.peek();
            parent.got = fr.ret;
            parent.gotConsumed = fr.consumed;
        }
    }

    private GatherFrame stepGather(GatherFrame f) {
        switch (f.kind) {
            case GatherFrame.KEYWORD_BLOCKS: return stepKeywordBlocks(f);
            case GatherFrame.FUNC: return stepFunc(f);
            case GatherFrame.IF_CHAIN: return stepChain(f);
            case GatherFrame.MATCH_CHAIN: return stepChain(f);
            case GatherFrame.MATCH_CASES: return stepMatchCases(f);
            case GatherFrame.LOCK: return stepLock(f);
            case GatherFrame.TRY_SPAN: return stepTrySpan(f);
            case GatherFrame.CATCH: return stepCatch(f);
            case GatherFrame.BLOCK_STMT: return stepBlockStmt(f);
            case GatherFrame.INTERFACE: return stepInterface(f);
            case GatherFrame.IMPL: return stepImpl(f);
            case GatherFrame.LIBRARY: return stepLibrary(f);
            default: return stepConstructor(f);
        }
    }

    // resume points of stepKeywordBlocks
    private static final int AT_START = 0, AT_AFTER_QEXPR = 1, AT_AFTER_TRY_BLOCK = 2, AT_AFTER_TRY_SPAN = 3, AT_AFTER_STATEMENT = 4;
    private static final int AT_TRY_SCAN = 5, AT_DISPATCH = 6;

    /**
     * Collapses each "keyword [tokens] { block }" construct into one token, bottom-up. Resumable: every nested
     * statement list or construct is a child frame, so the order of work and of errors is the recursive one.
     */
    @SuppressWarnings("unchecked")
    private GatherFrame stepKeywordBlocks(GatherFrame f) {
        List<Token> lines = f.lines;
        boolean isRoot = f.isRoot;
        if (f.result == null) {
            f.result = new ArrayList<>();
        }
        List<Token> result = f.result;
        int at = f.stage;
        f.stage = 0;
        while (true) {
            Token line;
            List<Token> tokens;
            Token first;
            boolean expandedOptionalHere = f.expanded;
            if (at == AT_START) {
                if (f.i >= lines.size()) {
                    return f.finish(result);
                }
                line = lines.get(f.i);
                tokens = line.childs;
                first = tokens.get(0);

                // "?catch(e){..}" declaration: checked first (a lone 'catch' is rejected further down); emits no code.
                // See docs/Parser.md, "?catch and ?EXPR sugar"
                if (tokens.size() > 1 && first.type == TokenType.OPERATOR && first.text.equals("?")
                        && tokens.get(1).type == TokenType.KEYWORD && tokens.get(1).text.equals("catch")) {
                    CatchClauseResult decl = catchClauseHeader(tokens, 1, "?catch(...) { ... }");
                    hub.lines.pendingOptionalCatchParamName = decl.paramName;
                    hub.lines.pendingOptionalCatchRawBody = decl.body;
                    f.i++;
                    continue;
                }

                // "?EXPR": sugar for "try EXPR catch(e){BLOCK}" with the most recent ?catch block; only the first '?'
                // of a line is expanded and the wrapped expression runs to the end of the line.
                // See docs/Parser.md, "?catch and ?EXPR sugar"
                int qIdx = -1;
                for (int k = 0; k < tokens.size(); k++) {
                    if (tokens.get(k).type == TokenType.OPERATOR && tokens.get(k).text.equals("?")) {
                        qIdx = k;
                        break;
                    }
                }
                // set once "?EXPR" has built the synthetic try token: the try scan below must skip this line
                expandedOptionalHere = false;
                f.expanded = false;
                if (qIdx >= 0) {
                    Token qTok = tokens.get(qIdx);
                    if (hub.lines.pendingOptionalCatchRawBody == null) {
                        throw new CompilerException("parse", qTok.file, qTok.line,
                                "'?' requires a preceding '?catch(e) { ... }' declaration, earlier in this same "
                                        + "function, to supply its catch block");
                    }
                    List<Token> exprTokens = new ArrayList<>(tokens.subList(qIdx + 1, tokens.size()));
                    if (exprTokens.isEmpty()) {
                        throw new CompilerException("parse", qTok.file, qTok.line,
                                "'?' requires an expression (a call to a '@throws' function, or 'new') "
                                        + "immediately after it");
                    }
                    List<Token> clonedRawBody = new ArrayList<>();
                    for (Token bodyLine : hub.lines.pendingOptionalCatchRawBody) {
                        clonedRawBody.add(bodyLine.deepCloneRaw());
                    }
                    Token tryGathered = new Token(TokenType.KEYWORD, "try", qTok.line, qTok.file);
                    tryGathered.sub = exprTokens;
                    f.line = line;
                    f.tokens = tokens;
                    f.first = first;
                    f.tryGathered = tryGathered;
                    f.tok = qTok;
                    f.index = qIdx;
                    f.stage = AT_AFTER_QEXPR;
                    return keywordBlocksFrame(clonedRawBody, false);
                }
                at = AT_TRY_SCAN;
            } else {
                line = f.line;
                tokens = f.tokens;
                first = f.first;
            }

            if (at == AT_AFTER_QEXPR) {
                Token tryGathered = f.tryGathered;
                Token qTok = f.tok;
                tryGathered.childs = (List<Token>) f.got;
                tryGathered.catchParamName = hub.lines.pendingOptionalCatchParamName;
                tryGathered.decorators = qTok.decorators;
                tryGathered.pinnedComments = qTok.pinnedComments;
                List<Token> replaced = new ArrayList<>(tokens.subList(0, f.index));
                replaced.add(tryGathered);
                tokens = replaced;
                line.childs = tokens;
                first = tokens.get(0);
                expandedOptionalHere = true;
                f.expanded = true;
                // no `continue`: falls through to the ordinary per-line dispatch below
                at = AT_TRY_SCAN;
            }

            if (at == AT_AFTER_STATEMENT) {
                result.add(wrapAsLine((Token) f.got));
                f.i += f.gotConsumed;
                at = AT_START;
                continue;
            }

            if (at == AT_AFTER_TRY_BLOCK) {
                Token blockTok = f.blockTok;
                blockTok.childs = (List<Token>) f.got;
                Token gathered = new Token(TokenType.KEYWORD, "try", first.line, first.file);
                gathered.childs = blockTok.childs;
                gathered.isTryBlock = true;
                gathered.decorators = first.decorators;
                gathered.pinnedComments = first.pinnedComments;
                result.add(wrapAsLine(gathered));
                f.i++;
                at = AT_START;
                continue;
            }

            if (at == AT_AFTER_TRY_SPAN) {
                Token tryGathered = (Token) f.got;
                List<Token> replaced = new ArrayList<>(tokens.subList(0, f.index));
                replaced.add(tryGathered);
                tokens = replaced;
                line.childs = tokens;
                first = tokens.get(0);
                at = AT_DISPATCH;
            }

            // "try EXPR catch{..}" can sit anywhere in a line (e.g. "let x = try f() catch{..}"): the whole span
            // collapses to one KEYWORD token (.sub = call tokens, .childs = catch body).
            // See docs/Parser.md, "try / catch span"
            if (at == AT_TRY_SCAN) {
                if (!expandedOptionalHere) {
                    int tryIdx = -1;
                    for (int k = 0; k < tokens.size(); k++) {
                        if (tokens.get(k).type == TokenType.KEYWORD && tokens.get(k).text.equals("try")) {
                            tryIdx = k;
                            break;
                        }
                    }
                    // "try { ... }" is a plain scope block (isTryBlock); told apart from the throwing form by the '{'
                    // lookahead, before gatherTryCatchSpan would hunt for a 'catch'
                    if (tryIdx == 0 && tokens.size() > 1 && tokens.get(1).type == TokenType.DELINEATOR
                            && tokens.get(1).text.equals("{")) {
                        Token blockTok = requireTrailingBlock(tokens, first);
                        if (tokens.indexOf(blockTok) != 1) {
                            throw new CompilerException("parse", first.file, first.line,
                                    "'try' takes no condition");
                        }
                        f.line = line;
                        f.tokens = tokens;
                        f.first = first;
                        f.blockTok = blockTok;
                        f.stage = AT_AFTER_TRY_BLOCK;
                        return keywordBlocksFrame(blockTok.childs, isRoot);
                    }
                    if (tryIdx >= 0) {
                        GatherFrame span = new GatherFrame(GatherFrame.TRY_SPAN);
                        span.tokens = tokens;
                        span.index = tryIdx;
                        f.line = line;
                        f.tokens = tokens;
                        f.first = first;
                        f.index = tryIdx;
                        f.stage = AT_AFTER_TRY_SPAN;
                        return span;
                    } else if (first.type == TokenType.KEYWORD && first.text.equals("catch")) {
                        throw new CompilerException("parse", first.file, first.line,
                                "'catch' without a preceding 'try'");
                    }
                }
                at = AT_DISPATCH;
            }

            // at == AT_DISPATCH: one statement, by its first token
            f.line = line;
            f.tokens = tokens;
            f.first = first;
            if (isLockStatementStart(tokens)) {
                // "match @lock EXPR{...}" (a plain "lock EXPR{...}" is rejected inside isLockStatementStart). "lock" is
                // deliberately never a reserved KEYWORD at the lexer level; the form is detected structurally.
                return statementChild(f, GatherFrame.LOCK, lines);
            }

            if (first.type == TokenType.KEYWORD) {
                switch (first.text) {
                    case "if":
                        return statementChild(f, GatherFrame.IF_CHAIN, lines);
                    case "elseif":
                    case "else":
                        throw new CompilerException("parse", first.file, first.line,
                                "'" + first.text + "' without a preceding 'if'");
                    case "loop":
                    case "unsafe":
                    case "safe":
                    case "for":
                    case "assume": {
                        // unsafe/safe recurse with the current isRoot (the block may hold declarations at root);
                        // unsafe-in-safe nesting rules are the TypeChecker's
                        GatherFrame child = new GatherFrame(GatherFrame.BLOCK_STMT);
                        child.tokens = tokens;
                        child.tok = first;
                        child.text = first.text;
                        child.isRoot = isRoot;
                        f.stage = AT_AFTER_STATEMENT;
                        return child;
                    }
                    case "match":
                        return statementChild(f, GatherFrame.MATCH_CHAIN, lines);
                    case "elsematch":
                        throw new CompilerException("parse", first.file, first.line,
                                "'elsematch' without a preceding 'match'");
                    case "func": {
                        requireRoot(isRoot, first, "func");
                        f.stage = AT_AFTER_STATEMENT;
                        return funcFrame(tokens, first);
                    }
                    case "extern": {
                        requireRoot(isRoot, first, "extern");
                        result.add(wrapAsLine(gatherExtern(tokens, first)));
                        f.i++;
                        at = AT_START;
                        continue;
                    }
                    case "export": {
                        requireRoot(isRoot, first, "export");
                        result.add(wrapAsLine(gatherExport(tokens, first)));
                        f.i++;
                        at = AT_START;
                        continue;
                    }
                    case "ASM": {
                        // no requireRoot: ASM is legal at root and inside functions
                        result.add(wrapAsLine(gatherAsm(tokens, first)));
                        f.i++;
                        at = AT_START;
                        continue;
                    }
                    case "type": {
                        requireRoot(isRoot, first, "type");
                        result.add(wrapAsLine(gatherType(tokens, first)));
                        f.i++;
                        at = AT_START;
                        continue;
                    }
                    case "const": {
                        requireRoot(isRoot, first, "const");
                        result.add(wrapAsLine(gatherConst(tokens, first)));
                        f.i++;
                        at = AT_START;
                        continue;
                    }
                    case "struct": {
                        requireRoot(isRoot, first, "struct");
                        result.add(wrapAsLine(gatherStruct(tokens, first)));
                        f.i++;
                        at = AT_START;
                        continue;
                    }
                    case "enum": {
                        requireRoot(isRoot, first, "enum");
                        result.add(wrapAsLine(gatherEnum(tokens, first)));
                        f.i++;
                        at = AT_START;
                        continue;
                    }
                    case "interface": {
                        requireRoot(isRoot, first, "interface");
                        GatherFrame child = new GatherFrame(GatherFrame.INTERFACE);
                        child.tokens = tokens;
                        child.tok = first;
                        f.stage = AT_AFTER_STATEMENT;
                        return child;
                    }
                    case "impl": {
                        requireRoot(isRoot, first, "impl");
                        if (tokens.size() > 3 && tokens.get(1).type == TokenType.VARREF
                                && tokens.get(1).text.equals("default")
                                && tokens.get(2).type == TokenType.VARREF && tokens.get(2).text.equals("lock")
                                && tokens.get(3).type == TokenType.KEYWORD && tokens.get(3).text.equals("match")) {
                            throw new CompilerException("parse", first.file, first.line,
                                    "'impl default lock match' has been renamed -- write 'impl default match @lock'");
                        }
                        if (tokens.size() > 5 && tokens.get(1).type == TokenType.VARREF
                                && tokens.get(1).text.equals("default")
                                && tokens.get(2).type == TokenType.KEYWORD && tokens.get(2).text.equals("match")
                                && tokens.get(3).type == TokenType.VARREF && tokens.get(3).text.equals("lock")) {
                            throw new CompilerException("parse", first.file, first.line,
                                    "'impl default match lock' is now spelled 'impl default match @lock'");
                        }
                        if (tokens.size() > 5 && tokens.get(3).type == TokenType.OPERATOR
                                && tokens.get(3).text.equals("@")
                                && tokens.get(2).type == TokenType.KEYWORD && tokens.get(2).text.equals("match")
                                && tokens.get(4).type == TokenType.VARREF && tokens.get(4).text.equals("lock")) {
                            tokens = new ArrayList<>(tokens);
                            tokens.remove(3);
                        }
                        if (hub.isDefaultLockMatchImplStart(tokens)) {
                            result.add(wrapAsLine(hub.gatherDefaultLockMatchImpl(tokens, first)));
                            f.i++;
                            at = AT_START;
                            continue;
                        }
                        GatherFrame child;
                        if (isConstructorImplStart(tokens)) {
                            child = new GatherFrame(GatherFrame.CONSTRUCTOR);
                        } else {
                            child = new GatherFrame(GatherFrame.IMPL);
                        }
                        child.tokens = tokens;
                        child.tok = first;
                        f.stage = AT_AFTER_STATEMENT;
                        return child;
                    }
                    case "library": {
                        requireRoot(isRoot, first, "library");
                        GatherFrame child = new GatherFrame(GatherFrame.LIBRARY);
                        child.tokens = tokens;
                        child.tok = first;
                        f.stage = AT_AFTER_STATEMENT;
                        return child;
                    }
                    case "import":
                        requireRoot(isRoot, first, "import");
                        break; // already collapsed to one token by the parser's earlier pass; pass through
                    default:
                        break; // let / return / etc: pass through unchanged
                }
            }
            result.add(line);
            f.i++;
            at = AT_START;
        }
    }

    /** A chain or lock statement frame over `lines` starting at the current line; its answer is added as a line when it returns. */
    private GatherFrame statementChild(GatherFrame f, int kind, List<Token> lines) {
        GatherFrame child = new GatherFrame(kind);
        child.lines = lines;
        child.index = f.i;
        f.stage = AT_AFTER_STATEMENT;
        return child;
    }

    /** loop / unsafe / safe / for / assume: one block statement. */
    @SuppressWarnings("unchecked")
    private GatherFrame stepBlockStmt(GatherFrame f) {
        Token first = f.tok;
        List<Token> tokens = f.tokens;
        if (f.stage == 0) {
            switch (f.text) {
                case "loop": {
                    Token blockTok = requireTrailingBlock(tokens, first);
                    if (tokens.indexOf(blockTok) != 1) {
                        throw new CompilerException("parse", first.file, first.line,
                                "'loop' takes no condition");
                    }
                    f.blockTok = blockTok;
                    f.stage = 1;
                    return keywordBlocksFrame(blockTok.childs, false);
                }
                case "unsafe":
                case "safe": {
                    Token blockTok = requireTrailingBlock(tokens, first);
                    int unsafeBlockIdx = tokens.indexOf(blockTok);
                    List<String> unsafeTagList = null;
                    if (first.text.equals("unsafe") && unsafeBlockIdx > 1) {
                        // `unsafe deref extern{`: the words between the keyword and the block are the tags the
                        // block declares (the TypeChecker validates them against what the block really needs).
                        unsafeTagList = new ArrayList<>();
                        for (int ti = 1; ti < unsafeBlockIdx; ti++) {
                            if (tokens.get(ti).text.equals(":") && !unsafeTagList.isEmpty() && ti + 1 < unsafeBlockIdx) {
                                // `udyn:owns`: a tag with a qualifier is one tag
                                int last = unsafeTagList.size() - 1;
                                unsafeTagList.set(last, unsafeTagList.get(last) + ":" + tokens.get(ti + 1).text);
                                ti++;
                            } else {
                                unsafeTagList.add(tokens.get(ti).text);
                            }
                        }
                    } else if (unsafeBlockIdx != 1) {
                        throw new CompilerException("parse", first.file, first.line,
                                "'" + first.text + "' takes no condition");
                    }
                    f.blockTok = blockTok;
                    f.names = unsafeTagList;
                    f.stage = 1;
                    return keywordBlocksFrame(blockTok.childs, f.isRoot);
                }
                case "for": {
                    // "for [match] i in x{..}": condition goes to .sub; an optional 'match' after 'for' is
                    // stripped and recorded as isForMatch (TypeChecker.checkForLoop). See docs/Parser.md, "for match"
                    Token blockTok = requireTrailingBlock(tokens, first);
                    boolean isForMatch = tokens.size() > 1 && tokens.get(1).type == TokenType.KEYWORD
                            && tokens.get(1).text.equals("match");
                    int conditionStart = isForMatch ? 2 : 1;
                    f.condition = new ArrayList<>(tokens.subList(conditionStart, tokens.indexOf(blockTok)));
                    f.flag = isForMatch;
                    f.blockTok = blockTok;
                    f.stage = 1;
                    return keywordBlocksFrame(blockTok.childs, false);
                }
                default: {
                    // "assume match EXPR" with an optional block (no block = holds for the rest of the scope)
                    if (tokens.size() < 2 || tokens.get(1).type != TokenType.KEYWORD
                            || !tokens.get(1).text.equals("match")) {
                        throw new CompilerException("parse", first.file, first.line,
                                "expected 'match' after 'assume'");
                    }
                    Token last = tokens.get(tokens.size() - 1);
                    boolean hasBlock = last.type == TokenType.DELINEATOR && last.text.equals("{");
                    f.gathered = new Token(TokenType.KEYWORD, "assume", first.line, first.file);
                    f.flag = hasBlock;
                    f.blockTok = last;
                    if (hasBlock) {
                        f.condition = new ArrayList<>(tokens.subList(2, tokens.indexOf(last)));
                        f.stage = 1;
                        return keywordBlocksFrame(last.childs, false);
                    }
                    f.condition = new ArrayList<>(tokens.subList(2, tokens.size()));
                    f.got = null;
                    break;
                }
            }
        }
        // the block (if any) has been gathered
        switch (f.text) {
            case "loop": {
                f.blockTok.childs = (List<Token>) f.got;
                Token gathered = new Token(TokenType.KEYWORD, "loop", first.line, first.file);
                gathered.childs = f.blockTok.childs;
                gathered.decorators = first.decorators;
                gathered.pinnedComments = first.pinnedComments;
                return f.finish(gathered);
            }
            case "unsafe":
            case "safe": {
                f.blockTok.childs = (List<Token>) f.got;
                Token gathered = new Token(TokenType.KEYWORD, first.text, first.line, first.file);
                gathered.unsafeTags = f.names;
                gathered.childs = f.blockTok.childs;
                gathered.decorators = first.decorators;
                gathered.pinnedComments = first.pinnedComments;
                return f.finish(gathered);
            }
            case "for": {
                f.blockTok.childs = (List<Token>) f.got;
                Token gathered = new Token(TokenType.KEYWORD, "for", first.line, first.file);
                gathered.sub = f.condition;
                gathered.childs = f.blockTok.childs;
                gathered.decorators = first.decorators;
                gathered.pinnedComments = first.pinnedComments;
                gathered.isForMatch = f.flag;
                return f.finish(gathered);
            }
            default:
                return finishAssume(f, first);
        }
    }

    @SuppressWarnings("unchecked")
    private GatherFrame finishAssume(GatherFrame f, Token first) {
        Token gathered = f.gathered;
        List<Token> condition = f.condition;
        if (f.flag) {
            f.blockTok.childs = (List<Token>) f.got;
            gathered.childs = f.blockTok.childs;
            gathered.hasBlock = true;
        } else {
            gathered.childs = new ArrayList<>();
            gathered.hasBlock = false;
        }
        if (condition.isEmpty()) {
            throw new CompilerException("parse", first.file, first.line,
                    "'assume match' requires a condition");
        }
        int colonIdx = -1;
        for (int ci = 0; ci < condition.size(); ci++) {
            if (condition.get(ci).type == TokenType.OPERATOR && condition.get(ci).text.equals(":")) {
                colonIdx = ci;
                break;
            }
        }
        if (colonIdx >= 0) {
            // "assume match x.f : A|B": split at ':' and parse the variant chain here, before ':'
            // reaches the expression pipeline (see Token.assumeFieldVariants)
            List<Token> leftTokens = new ArrayList<>(condition.subList(0, colonIdx));
            if (leftTokens.isEmpty()) {
                throw new CompilerException("parse", first.file, first.line,
                        "'assume match x.field : VARIANT' requires an expression before ':'");
            }
            List<String> variants = new ArrayList<>();
            int vi = colonIdx + 1;
            while (true) {
                if (vi >= condition.size() || condition.get(vi).type != TokenType.VARREF) {
                    throw new CompilerException("parse", first.file, first.line,
                            "'assume match x.field : VARIANT' expects a variant name after "
                                    + "':'");
                }
                variants.add(condition.get(vi).text);
                vi++;
                if (vi < condition.size() && condition.get(vi).type == TokenType.OPERATOR
                        && condition.get(vi).text.equals("|")) {
                    vi++;
                    continue;
                }
                break;
            }
            if (vi != condition.size()) {
                throw new CompilerException("parse", condition.get(vi).file,
                        condition.get(vi).line,
                        "unexpected token '" + condition.get(vi).text
                                + "' in 'assume match x.field : VARIANT'");
            }
            condition = leftTokens;
            gathered.assumeFieldVariants = variants;
        }
        gathered.sub = condition;
        gathered.decorators = first.decorators;
        gathered.pinnedComments = first.pinnedComments;
        return f.finish(gathered);
    }

    void requireRoot(boolean isRoot, Token kwTok, String what) {
        if (!isRoot) {
            throw new CompilerException("parse", kwTok.file, kwTok.line,
                    "'" + what + "' is only allowed at the top level, not nested inside a block");
        }
    }

    Token wrapAsLine(Token gathered) {
        Token lineTok = new Token(TokenType.LINE, "line", gathered.line, gathered.file);
        lineTok.childs.add(gathered);
        return lineTok;
    }

    /**
     * Gathers "try EXPR catch(e) { BLOCK }" into one synthetic "try" KEYWORD token (.sub = raw expr tokens, .childs = gathered catch body).
     * The span must run to the end of the line. See docs/Parser.md, "Try/catch span and catch clauses".
     */
    private GatherFrame stepTrySpan(GatherFrame f) {
        List<Token> tokens = f.tokens;
        int tryIdx = f.index;
        if (f.stage == 0) {
            Token tryTok = tokens.get(tryIdx);
            int catchIdx = -1;
            for (int k = tryIdx + 1; k < tokens.size(); k++) {
                if (tokens.get(k).type == TokenType.KEYWORD && tokens.get(k).text.equals("catch")) {
                    catchIdx = k;
                    break;
                }
            }
            if (catchIdx < 0) {
                throw new CompilerException("parse", tryTok.file, tryTok.line,
                        "'try' requires a matching 'catch(e) { ... }'");
            }
            f.condition = new ArrayList<>(tokens.subList(tryIdx + 1, catchIdx));
            if (f.condition.isEmpty()) {
                throw new CompilerException("parse", tryTok.file, tryTok.line,
                        "'try' requires an expression (a call to a '@throws' function) before 'catch'");
            }
            GatherFrame catchFrame = new GatherFrame(GatherFrame.CATCH);
            catchFrame.tokens = tokens;
            catchFrame.index = catchIdx;
            catchFrame.text = "try ... catch(...) { ... }";
            catchFrame.flag = true;
            f.stage = 1;
            return catchFrame;
        }
        Token tryTok = tokens.get(tryIdx);
        CatchClauseResult catchClause = (CatchClauseResult) f.got;
        Token gathered = new Token(TokenType.KEYWORD, "try", tryTok.line, tryTok.file);
        gathered.sub = f.condition;
        gathered.childs = catchClause.body;
        gathered.catchParamName = catchClause.paramName;
        gathered.decorators = tryTok.decorators;
        gathered.pinnedComments = tryTok.pinnedComments;
        return f.finish(gathered);
    }

    /**
     * Parses one "catch(e) { ... }" clause that must end the line. With flag (gatherBodyNow) false the body stays raw
     * (a "?catch" body is deep-cloned per use site). See docs/Parser.md, "Try/catch span and catch clauses".
     */
    @SuppressWarnings("unchecked")
    private GatherFrame stepCatch(GatherFrame f) {
        if (f.stage == 0) {
            CatchClauseResult header = catchClauseHeader(f.tokens, f.index, f.text);
            f.names = new ArrayList<>();
            f.names.add(header.paramName);
            f.stage = 1;
            return keywordBlocksFrame(header.body, false);
        }
        return f.finish(new CatchClauseResult(f.names.get(0), (List<Token>) f.got));
    }

    /** The checks of a catch clause; returns its parameter name and the raw (ungathered) body. */
    private CatchClauseResult catchClauseHeader(List<Token> tokens, int catchIdx, String trailingContextLabel) {
        Token catchTok = tokens.get(catchIdx);
        if (catchIdx + 1 >= tokens.size() || tokens.get(catchIdx + 1).type != TokenType.DELINEATOR
                || !tokens.get(catchIdx + 1).text.equals("(")) {
            throw new CompilerException("parse", catchTok.file, catchTok.line,
                    "'catch' requires exactly one parameter in parentheses, e.g. 'catch(e) { ... }'");
        }
        Token parenTok = tokens.get(catchIdx + 1);
        List<Token> paramTokens = parenTok.childs;
        if (paramTokens.size() != 1 || paramTokens.get(0).type != TokenType.VARREF) {
            throw new CompilerException("parse", parenTok.file, parenTok.line,
                    "'catch' takes exactly one plain, untyped parameter name -- got "
                            + (paramTokens.isEmpty() ? "none" : paramTokens.size() + " tokens")
                            + " inside '(...)'");
        }
        String catchParamName = paramTokens.get(0).text;
        int blockIdx = catchIdx + 2;
        if (blockIdx >= tokens.size()) {
            throw new CompilerException("parse", catchTok.file, catchTok.line,
                    "'catch(...)' requires a '{ ... }' block");
        }
        Token blockTok = tokens.get(blockIdx);
        if (blockTok.type != TokenType.DELINEATOR || !blockTok.text.equals("{")) {
            throw new CompilerException("parse", catchTok.file, catchTok.line,
                    "'catch(...)' requires a '{ ... }' block");
        }
        int afterBlockIdx = blockIdx + 1;
        if (afterBlockIdx != tokens.size()) {
            Token trailing = tokens.get(afterBlockIdx);
            throw new CompilerException("parse", trailing.file, trailing.line,
                    "unexpected token '" + trailing.text + "' after '" + trailingContextLabel + "'");
        }
        return new CatchClauseResult(catchParamName, blockTok.childs);
    }

    /** The trailing '{' block token is always the last token of a gathered construct's line (used by loop/func/struct/enum, which never chain). */
    public Token requireTrailingBlock(List<Token> tokens, Token kwTok) {
        Token last = tokens.get(tokens.size() - 1);
        if (last.type != TokenType.DELINEATOR || !last.text.equals("{")) {
            throw new CompilerException("parse", kwTok.file, kwTok.line,
                    "expected '{' block after '" + kwTok.text + "'");
        }
        return last;
    }

    /**
     * Gathers an if/elseif/else chain (linked through .next) or a match/elsematch/else chain (plus the case-block form of match);
     * f.consumed = lines consumed. See docs/Parser.md, "if and match chains".
     * Stages: 0 start, 1 first block gathered, 2 branch loop, 3 a branch block gathered, 4 case-block form gathered.
     */
    @SuppressWarnings("unchecked")
    private GatherFrame stepChain(GatherFrame f) {
        boolean isIf = f.kind == GatherFrame.IF_CHAIN;
        List<Token> lines = f.lines;
        switch (f.stage) {
            case 0: {
                List<Token> kwTokens = lines.get(f.index).childs;
                Token kwTok = kwTokens.get(0);
                Token blockTok = requireTrailingBlock(kwTokens, kwTok);
                f.condition = new ArrayList<>(kwTokens.subList(1, kwTokens.indexOf(blockTok)));
                f.tok = kwTok;
                f.blockTok = blockTok;
                if (!isIf && isCaseMatchBody(blockTok.childs)) {
                    // Case-block form "match b{ LABEL:{...} }": decided structurally; no elsematch/else chain is looked for.
                    GatherFrame cases = new GatherFrame(GatherFrame.MATCH_CASES);
                    cases.lines = blockTok.childs;
                    cases.allowDefault = false;
                    f.stage = 4;
                    return cases;
                }
                f.stage = 1;
                return keywordBlocksFrame(blockTok.childs, false);
            }
            case 4: {
                Token matchTok = f.tok;
                Token enumHead = new Token(TokenType.KEYWORD, "match", matchTok.line, matchTok.file);
                enumHead.isCaseMatch = true;
                enumHead.sub = f.condition;
                enumHead.childs = (List<Token>) f.got;
                enumHead.decorators = matchTok.decorators;
                enumHead.pinnedComments = matchTok.pinnedComments;
                f.consumed = 1;
                return f.finish(enumHead);
            }
            case 1: {
                Token kwTok = f.tok;
                f.blockTok.childs = (List<Token>) f.got;
                Token head = new Token(TokenType.KEYWORD, isIf ? "if" : "match", kwTok.line, kwTok.file);
                head.sub = f.condition;
                head.childs = f.blockTok.childs;
                if (!isIf) {
                    head.decorators = kwTok.decorators;
                }
                head.pinnedComments = kwTok.pinnedComments;
                f.head = head;
                f.chainTail = head;
                f.consumed = 1;
                f.j = f.index + 1;
                f.stage = 2;
                break;
            }
            case 3: {
                Token branchKw = f.branchKw;
                f.branchBlockTok.childs = (List<Token>) f.got;
                Token branch = new Token(TokenType.KEYWORD, branchKw.text, branchKw.line, branchKw.file);
                branch.sub = f.condition;
                branch.childs = f.branchBlockTok.childs;
                branch.pinnedComments = branchKw.pinnedComments;
                f.chainTail.next = branch;
                f.chainTail = branch;
                f.consumed++;
                f.j++;
                f.stage = 2;
                if (branchKw.text.equals("else")) {
                    return f.finish(f.head); // 'else' always terminates the chain
                }
                break;
            }
            default:
                break;
        }
        String elseKw = isIf ? "elseif" : "elsematch";
        if (f.j < lines.size()) {
            List<Token> branchTokens = lines.get(f.j).childs;
            Token branchKw = branchTokens.get(0);
            if (branchKw.type == TokenType.KEYWORD
                    && (branchKw.text.equals(elseKw) || branchKw.text.equals("else"))) {
                Token branchBlockTok = requireTrailingBlock(branchTokens, branchKw);
                List<Token> branchCondition;
                if (branchKw.text.equals(elseKw)) {
                    branchCondition = new ArrayList<>(branchTokens.subList(1, branchTokens.indexOf(branchBlockTok)));
                } else {
                    if (branchTokens.indexOf(branchBlockTok) != 1) {
                        throw new CompilerException("parse", branchKw.file, branchKw.line,
                                "'else' takes no condition");
                    }
                    branchCondition = new ArrayList<>();
                }
                f.branchKw = branchKw;
                f.branchBlockTok = branchBlockTok;
                f.condition = branchCondition;
                f.stage = 3;
                return keywordBlocksFrame(branchBlockTok.childs, false);
            }
        }
        return f.finish(f.head);
    }

    /** Gathers "match @lock EXPR{...}" as a "lock" token with isCaseMatch set; the body goes through the case gatherer. See docs/Parser.md, "match @lock". */
    @SuppressWarnings("unchecked")
    private GatherFrame stepLock(GatherFrame f) {
        if (f.stage == 0) {
            List<Token> lockTokens = f.lines.get(f.index).childs;
            Token lockTok = lockTokens.get(0);
            Token blockTok = requireTrailingBlock(lockTokens, lockTok);
            int conditionStart = 3;
            List<Token> condition = new ArrayList<>(lockTokens.subList(conditionStart, lockTokens.indexOf(blockTok)));

            Token gathered = new Token(TokenType.KEYWORD, "lock", lockTok.line, lockTok.file);
            gathered.sub = condition;
            gathered.decorators = lockTok.decorators;
            gathered.pinnedComments = lockTok.pinnedComments;
            gathered.isCaseMatch = true;
            f.gathered = gathered;
            GatherFrame cases = new GatherFrame(GatherFrame.MATCH_CASES);
            cases.lines = blockTok.childs;
            cases.allowDefault = true;
            f.stage = 1;
            return cases;
        }
        f.gathered.childs = (List<Token>) f.got;
        f.consumed = 1;
        return f.finish(f.gathered);
    }

    /**
     * Extracts one Token per case ("LABEL:{...}" or "A|B:{...}") from a case-block body, flattening the lines first;
     * each case block is a child statement-list frame. See docs/Parser.md, "Case-match bodies".
     */
    @SuppressWarnings("unchecked")
    private GatherFrame stepMatchCases(GatherFrame f) {
        if (f.stage == 0) {
            f.flat = new ArrayList<>();
            for (Token lineTok : f.lines) {
                f.flat.addAll(lineTok.childs);
            }
            f.cases = new ArrayList<>();
            f.i = 0;
        } else {
            f.caseTok.childs = (List<Token>) f.got;
            f.cases.add(f.caseTok);
        }
        f.stage = 1;
        List<Token> flat = f.flat;
        List<Token> cases = f.cases;
        boolean allowDefaultPolicyRef = f.allowDefault;
        int i = f.i;
        while (i < flat.size()) {
            Token sep = flat.get(i);
            if (sep.type == TokenType.OPERATOR && (sep.text.equals(",") || sep.text.equals(";"))) {
                i++; // an optional separator between two cases -- skipped, never itself part of one
                continue;
            }
            if (flat.get(i).type != TokenType.VARREF) {
                throw new CompilerException("parse", flat.get(i).file, flat.get(i).line,
                        "expected a case label (a variant name, 'default', or a float state) to start a "
                                + "match case, got '" + flat.get(i).text + "'");
            }
            List<Token> labels = new ArrayList<>();
            labels.add(flat.get(i));
            i++;
            while (i + 1 < flat.size() && flat.get(i).type == TokenType.OPERATOR && flat.get(i).text.equals("|")
                    && flat.get(i + 1).type == TokenType.VARREF) {
                labels.add(flat.get(i + 1));
                i += 2;
            }
            Token firstLabel = labels.get(0);
            if (i >= flat.size() || flat.get(i).type != TokenType.OPERATOR || !flat.get(i).text.equals(":")) {
                throw new CompilerException("parse", firstLabel.file, firstLabel.line,
                        "expected ':' after '" + labelChainText(labels) + "' in a match case");
            }
            i++;
            // "CLOSED:default" / "CLOSED:default(args)" (match @lock only): wrapped as a plain expression statement;
            // TypeChecker.checkLockMatchStatement detects the marker. See docs/Parser.md, "match @lock".
            if (allowDefaultPolicyRef && i < flat.size() && flat.get(i).type == TokenType.VARREF
                    && flat.get(i).text.equals("default")) {
                Token defaultTok = flat.get(i);
                i++;
                List<Token> refTokens = new ArrayList<>();
                refTokens.add(defaultTok);
                if (i < flat.size() && flat.get(i).type == TokenType.OPERATOR && flat.get(i).text.equals("CALL")) {
                    // Parenthesized form: insertCallLookupInFlat already put a "CALL" marker after "default"; carry it and the "(" group through.
                    refTokens.add(flat.get(i));
                    i++;
                    if (i >= flat.size() || flat.get(i).type != TokenType.DELINEATOR
                            || !flat.get(i).text.equals("(")) {
                        throw new CompilerException("parse", defaultTok.file, defaultTok.line,
                                "expected '(' after 'default' in a 'match @lock' case");
                    }
                    refTokens.add(flat.get(i));
                    i++;
                }
                Token defaultLineTok = new Token(TokenType.LINE, "line", defaultTok.line, defaultTok.file);
                defaultLineTok.childs = refTokens;
                Token defaultCaseTok = new Token(TokenType.VARREF, firstLabel.text, firstLabel.line, firstLabel.file);
                defaultCaseTok.sub = labels;
                defaultCaseTok.childs = new ArrayList<>();
                defaultCaseTok.childs.add(defaultLineTok);
                cases.add(defaultCaseTok);
                continue;
            }
            if (i >= flat.size() || flat.get(i).type != TokenType.DELINEATOR || !flat.get(i).text.equals("{")) {
                throw new CompilerException("parse", firstLabel.file, firstLabel.line,
                        "expected '{' after '" + labelChainText(labels) + ":' in a match case");
            }
            Token blockTok = flat.get(i);
            i++;
            Token caseTok = new Token(TokenType.VARREF, firstLabel.text, firstLabel.line, firstLabel.file);
            caseTok.sub = labels;
            f.caseTok = caseTok;
            f.i = i;
            return keywordBlocksFrame(blockTok.childs, false);
        }
        return f.finish(cases);
    }

    /** "func name<T>(params) ret { body }": the checks, then the body as a child statement list, then the "func" token. */
    @SuppressWarnings("unchecked")
    private GatherFrame stepFunc(GatherFrame f) {
        Token funcTok = f.tok;
        if (f.stage == 0) {
            List<Token> tokens = f.tokens;
            if (tokens.size() < 3 || tokens.get(1).type != TokenType.VARREF) {
                throw new CompilerException("parse", funcTok.file, funcTok.line,
                        "expected function name after 'func'");
            }
            f.nameTok = tokens.get(1);
            f.typeParams = hub.lines.parseOptionalTypeParams(tokens, 2);
            int parenIdx = f.typeParams.nextIndex;
            if (parenIdx >= tokens.size() || tokens.get(parenIdx).type != TokenType.DELINEATOR
                    || !tokens.get(parenIdx).text.equals("(")) {
                throw new CompilerException("parse", funcTok.file, funcTok.line,
                        "expected '(' after function name"
                                + (f.typeParams.names != null ? "'s type-parameter list" : ""));
            }
            f.paramsTok = tokens.get(parenIdx);
            f.blockTok = requireTrailingBlock(tokens, funcTok);
            int blockIdx = tokens.indexOf(f.blockTok);
            f.returnTypeTokens = new ArrayList<>(tokens.subList(parenIdx + 1, blockIdx));
            // Reset the pending "?catch" state here, the only reset site (impl methods and constructors route through gatherFunc).
            hub.lines.pendingOptionalCatchParamName = null;
            hub.lines.pendingOptionalCatchRawBody = null;
            f.stage = 1;
            return keywordBlocksFrame(f.blockTok.childs, false);
        }
        f.blockTok.childs = (List<Token>) f.got;
        Token gathered = new Token(TokenType.KEYWORD, "func", funcTok.line, funcTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(f.nameTok);
        gathered.sub.add(f.paramsTok);
        gathered.sub.addAll(f.returnTypeTokens);
        gathered.typeParams = f.typeParams.names;
        gathered.typeParamBounds = f.typeParams.bounds;
        gathered.decorators = funcTok.decorators;
        gathered.childs = f.blockTok.childs;
        gathered.pinnedComments = funcTok.pinnedComments;
        return f.finish(gathered);
    }

    /** "interface Name{ sig; ... }" -- one "interface_method" token per signature; also "extends", "static" and default-method lines. See docs/Parser.md, "Interfaces". */
    private GatherFrame stepInterface(GatherFrame f) {
        Token interfaceTok = f.tok;
        if (f.stage == 0) {
            List<Token> tokens = f.tokens;
            if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
                throw new CompilerException("parse", interfaceTok.file, interfaceTok.line,
                        "expected interface name after 'interface'");
            }
            f.nameTok = tokens.get(1);
            f.typeParams = hub.lines.parseOptionalTypeParams(tokens, 2);
            f.blockTok = requireTrailingBlock(tokens, interfaceTok);
            if (tokens.indexOf(f.blockTok) != f.typeParams.nextIndex) {
                throw new CompilerException("parse", interfaceTok.file, interfaceTok.line,
                        "unexpected tokens between interface name and '{'");
            }
            f.members = new ArrayList<>();
            f.names = new ArrayList<>();
            f.i = 0;
        } else {
            Token method = (Token) f.got;
            if (f.lineIsStatic) {
                method.isStaticMethod = true;
            }
            f.members.add(method);
            f.i++;
        }
        f.stage = 1;
        List<Token> methods = f.members;
        List<Token> lines = f.blockTok.childs;
        while (f.i < lines.size()) {
            List<Token> lineTokens = lines.get(f.i).childs;
            if (!lineTokens.isEmpty() && lineTokens.get(0).type == TokenType.KEYWORD
                    && lineTokens.get(0).text.equals("extends")) {
                if (lineTokens.size() != 2 || lineTokens.get(1).type != TokenType.VARREF) {
                    throw new CompilerException("parse", lineTokens.get(0).file, lineTokens.get(0).line,
                            "expected 'extends InterfaceName'");
                }
                f.names.add(lineTokens.get(1).text);
                f.i++;
                continue;
            }
            // "static func ..." (a @default static method with body) or "static NAME(...) Ret;" (plain static signature).
            if (!lineTokens.isEmpty() && lineTokens.get(0).type == TokenType.MODIFIER
                    && lineTokens.get(0).text.equals("static")) {
                List<Token> afterStatic = lineTokens.subList(1, lineTokens.size());
                if (!afterStatic.isEmpty() && afterStatic.get(0).type == TokenType.KEYWORD
                        && afterStatic.get(0).text.equals("func")) {
                    f.lineIsStatic = true;
                    return funcFrame(afterStatic, afterStatic.get(0));
                }
                Token gatheredStatic = gatherInterfaceMethod(afterStatic, interfaceTok);
                gatheredStatic.isStaticMethod = true;
                methods.add(gatheredStatic);
                f.i++;
                continue;
            }
            if (!lineTokens.isEmpty() && lineTokens.get(0).type == TokenType.KEYWORD
                    && lineTokens.get(0).text.equals("func")) {
                // Default method: full func syntax via the func gatherer; whether "@default" is present is TypeChecker.registerInterface's concern.
                f.lineIsStatic = false;
                return funcFrame(lineTokens, lineTokens.get(0));
            }
            methods.add(gatherInterfaceMethod(lineTokens, interfaceTok));
            f.i++;
        }
        Token gathered = new Token(TokenType.KEYWORD, "interface", interfaceTok.line, interfaceTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(f.nameTok);
        gathered.typeParams = f.typeParams.names;
        gathered.typeParamBounds = f.typeParams.bounds;
        gathered.extendsNames = f.names;
        gathered.decorators = interfaceTok.decorators;
        gathered.childs = methods;
        gathered.pinnedComments = interfaceTok.pinnedComments;
        return f.finish(gathered);
    }

    /** "impl Iface for Concrete{...}" or "impl Concrete{...}"; methods go through the func gatherer (impl bodies are exempt from the isRoot check). .sub has 1 or 2 name tokens. */
    private GatherFrame stepImpl(GatherFrame f) {
        Token implTok = f.tok;
        if (f.stage == 0) {
            List<Token> tokens = f.tokens;
            f.implTypeParams = hub.lines.parseOptionalTypeParams(tokens, 1);
            int idx = f.implTypeParams.nextIndex;

            if (idx >= tokens.size() || tokens.get(idx).type != TokenType.VARREF) {
                throw new CompilerException("parse", implTok.file, implTok.line,
                        "expected a name after 'impl'" + (f.implTypeParams.names != null ? "'s type-parameter list" : ""));
            }
            Token firstNameTok = tokens.get(idx);
            idx++;
            TypeArgParseResult firstArgs = hub.lines.parseOptionalTypeArgsRaw(tokens, idx);
            idx = firstArgs.nextIndex;

            if (idx < tokens.size() && tokens.get(idx).type == TokenType.KEYWORD && tokens.get(idx).text.equals("for")) {
                idx++;
                if (idx >= tokens.size() || tokens.get(idx).type != TokenType.VARREF) {
                    throw new CompilerException("parse", implTok.file, implTok.line,
                            "expected a concrete type name after 'for'");
                }
                f.interfaceNameTok = firstNameTok;
                f.interfaceArgs = firstArgs.groups;
                f.concreteNameTok = tokens.get(idx);
                idx++;
                TypeArgParseResult cArgs = hub.lines.parseOptionalTypeArgsRaw(tokens, idx);
                idx = cArgs.nextIndex;
                f.concreteArgs = cArgs.groups;
            } else {
                f.concreteNameTok = firstNameTok;
                f.concreteArgs = firstArgs.groups;
            }

            f.blockTok = requireTrailingBlock(tokens, implTok);
            if (tokens.indexOf(f.blockTok) != idx) {
                throw new CompilerException("parse", implTok.file, implTok.line,
                        "unexpected tokens before 'impl' block's '{'");
            }
            f.members = new ArrayList<>();
            f.i = 0;
        } else {
            Token gatheredMethod = (Token) f.got;
            gatheredMethod.isStaticMethod = f.lineIsStatic;
            if (f.lineIsStatic) {
                // Decorators attach to the line's first token ("static"), not to "func": copy them over or they are lost.
                gatheredMethod.decorators = f.blockTok.childs.get(f.i).childs.get(0).decorators;
            }
            f.members.add(gatheredMethod);
            f.i++;
        }
        f.stage = 1;
        List<Token> lines = f.blockTok.childs;
        if (f.i < lines.size()) {
            Token line = lines.get(f.i);
            List<Token> lineTokens = line.childs;
            boolean lineIsStaticMethod = !lineTokens.isEmpty() && lineTokens.get(0).type == TokenType.MODIFIER
                    && lineTokens.get(0).text.equals("static");
            int funcKeywordIdx = lineIsStaticMethod ? 1 : 0;
            if (lineTokens.size() <= funcKeywordIdx || lineTokens.get(funcKeywordIdx).type != TokenType.KEYWORD
                    || !lineTokens.get(funcKeywordIdx).text.equals("func")) {
                throw new CompilerException("parse", line.file, line.line,
                        "only 'func'/'static func' declarations are allowed directly inside an 'impl' block");
            }
            f.lineIsStatic = lineIsStaticMethod;
            return funcFrame(lineTokens.subList(funcKeywordIdx, lineTokens.size()), lineTokens.get(funcKeywordIdx));
        }

        Token gathered = new Token(TokenType.KEYWORD, "impl", implTok.line, implTok.file);
        gathered.sub = new ArrayList<>();
        if (f.interfaceNameTok != null) {
            f.interfaceNameTok.genericArgs = f.interfaceArgs;
            gathered.sub.add(f.interfaceNameTok);
        }
        f.concreteNameTok.genericArgs = f.concreteArgs;
        gathered.sub.add(f.concreteNameTok);
        gathered.typeParams = f.implTypeParams.names; // the impl block's own <T,...>, if any
        gathered.typeParamBounds = f.implTypeParams.bounds;
        gathered.decorators = implTok.decorators;
        gathered.childs = f.members;
        gathered.pinnedComments = implTok.pinnedComments;
        return f.finish(gathered);
    }

    /** "library NAME<T?>{ func...; extends T; }" -- functions dispatched by library name, never through an instance. */
    private GatherFrame stepLibrary(GatherFrame f) {
        Token libraryTok = f.tok;
        if (f.stage == 0) {
            List<Token> tokens = f.tokens;
            if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
                throw new CompilerException("parse", libraryTok.file, libraryTok.line,
                        "expected a name after 'library'");
            }
            f.nameTok = tokens.get(1);
            f.typeParams = hub.lines.parseOptionalTypeParams(tokens, 2);
            f.blockTok = requireTrailingBlock(tokens, libraryTok);
            if (tokens.indexOf(f.blockTok) != f.typeParams.nextIndex) {
                throw new CompilerException("parse", libraryTok.file, libraryTok.line,
                        "unexpected tokens between 'library " + f.nameTok.text + "' and '{'");
            }
            f.members = new ArrayList<>();
            f.names = new ArrayList<>();
            f.i = 0;
        } else {
            f.members.add((Token) f.got);
            f.i++;
        }
        f.stage = 1;
        List<Token> lines = f.blockTok.childs;
        while (f.i < lines.size()) {
            Token line = lines.get(f.i);
            List<Token> lineTokens = line.childs;
            if (!lineTokens.isEmpty() && lineTokens.get(0).type == TokenType.KEYWORD
                    && lineTokens.get(0).text.equals("extends")) {
                if (lineTokens.size() != 2 || lineTokens.get(1).type != TokenType.VARREF) {
                    throw new CompilerException("parse", lineTokens.get(0).file, lineTokens.get(0).line,
                            "expected 'extends TypeName'");
                }
                f.names.add(lineTokens.get(1).text);
                f.i++;
                continue;
            }
            if (lineTokens.isEmpty() || lineTokens.get(0).type != TokenType.KEYWORD
                    || !lineTokens.get(0).text.equals("func")) {
                throw new CompilerException("parse", line.file, line.line,
                        "only 'func' declarations and 'extends' statements are allowed directly inside a "
                                + "'library' block");
            }
            return funcFrame(lineTokens, lineTokens.get(0));
        }
        Token gathered = new Token(TokenType.KEYWORD, "library", libraryTok.line, libraryTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(f.nameTok);
        gathered.typeParams = f.typeParams.names; // null if not generic
        gathered.typeParamBounds = f.typeParams.bounds;
        gathered.extendsNames = f.names;
        gathered.decorators = libraryTok.decorators;
        gathered.childs = f.members;
        gathered.pinnedComments = libraryTok.pinnedComments;
        return f.finish(gathered);
    }

    /**
     * Gathers a constructor declaration into a "func" token named CONSTRUCTOR_NAME_PREFIX + struct name (generic ones are wrapped in an "impl" token).
     * The return type must be written "self" and is replaced by the struct name. See docs/Parser.md, "Constructors".
     */
    private GatherFrame stepConstructor(GatherFrame f) {
        Token implTok = f.tok;
        if (f.stage == 0) {
            List<Token> tokens = f.tokens;
            f.implTypeParams = hub.lines.parseOptionalTypeParams(tokens, 1);
            int idx = f.implTypeParams.nextIndex;
            // isConstructorImplStart already confirmed tokens[idx]=="constructor", tokens[idx+1]=="for" using this identical parse.
            idx += 2;

            if (idx >= tokens.size() || tokens.get(idx).type != TokenType.VARREF) {
                throw new CompilerException("parse", implTok.file, implTok.line,
                        "expected a struct name after 'impl"
                                + (f.implTypeParams.names != null ? "<...>" : "") + " constructor for'");
            }
            Token concreteNameTok = tokens.get(idx);
            f.concreteNameTok = concreteNameTok;
            idx++;
            TypeArgParseResult concreteArgsResult = hub.lines.parseOptionalTypeArgsRaw(tokens, idx);
            idx = concreteArgsResult.nextIndex;
            f.concreteArgs = concreteArgsResult.groups;

            if (f.implTypeParams.names == null && f.concreteArgs != null) {
                throw new CompilerException("parse", implTok.file, implTok.line,
                        "'impl constructor for " + concreteNameTok.text + "<...>' -- a constructor for a generic "
                                + "struct needs an 'impl<...>' header (write 'impl<...> constructor for "
                                + concreteNameTok.text + "<...>(...) self {...}')");
            }
            if (f.implTypeParams.names != null && f.concreteArgs == null) {
                throw new CompilerException("parse", implTok.file, implTok.line,
                        "'impl<...> constructor for " + concreteNameTok.text + "' -- a generic constructor's target "
                                + "struct must itself be written with type arguments (e.g. '" + concreteNameTok.text
                                + "<" + String.join(",", f.implTypeParams.names) + ">')");
            }

            if (idx >= tokens.size() || tokens.get(idx).type != TokenType.DELINEATOR || !tokens.get(idx).text.equals("(")) {
                throw new CompilerException("parse", implTok.file, implTok.line,
                        "expected a parameter list '(...)' after 'impl constructor for " + concreteNameTok.text + "'");
            }
            Token paramsTok = tokens.get(idx);
            idx++;
            Token blockTok = requireTrailingBlock(tokens, implTok);
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
            if (f.concreteArgs != null) {
                returnTypeTok.genericArgs = f.concreteArgs;
            }

            List<Token> synth = new ArrayList<>();
            synth.add(funcKw);
            synth.add(nameTok);
            synth.add(paramsTok);
            synth.add(returnTypeTok);
            synth.add(blockTok);
            f.stage = 1;
            return funcFrame(synth, funcKw);
        }
        Token gathered = (Token) f.got;
        gathered.isConstructorDecl = true;
        gathered.constructorConcreteName = f.concreteNameTok.text;

        if (f.implTypeParams.names == null) {
            return f.finish(gathered);
        }

        // Generic case: wrap in an impl-shaped token so the impl-template machinery handles it (see doc comment).
        Token implWrapperConcreteNameTok = new Token(TokenType.VARREF, f.concreteNameTok.text,
                f.concreteNameTok.line, f.concreteNameTok.file);
        implWrapperConcreteNameTok.genericArgs = f.concreteArgs;

        Token implWrapper = new Token(TokenType.KEYWORD, "impl", implTok.line, implTok.file);
        implWrapper.sub = new ArrayList<>();
        implWrapper.sub.add(implWrapperConcreteNameTok);
        implWrapper.typeParams = f.implTypeParams.names;
        implWrapper.typeParamBounds = f.implTypeParams.bounds;
        // The wrapper "impl" only accepts impl-level decorators (@pub); function-level ones such as @throws belong on
        // the constructor function itself, which keeps the full list.
        if (implTok.decorators != null) {
            List<Decorator> implLevel = new ArrayList<>();
            for (Decorator d : implTok.decorators) {
                if (d.name.equals("pub")) {
                    implLevel.add(d);
                }
            }
            implWrapper.decorators = implLevel.isEmpty() ? null : implLevel;
        }
        implWrapper.pinnedComments = implTok.pinnedComments;
        implWrapper.childs = new ArrayList<>();
        implWrapper.childs.add(gathered);
        return f.finish(implWrapper);
    }

    /** Enum body of the nested-hierarchy grammar, pre-order: each variant gets start = running index, end = last index in its block. */
    private void gatherNestedEnumVariants(List<Token> topLines, List<Token> outVariants) {
        long counter = 0;
        // frames: {List<Token> lines, int[] position, Token owner (null at the top), long start}
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{topLines, new int[]{0}, null, 0L});
        while (!stack.isEmpty()) {
            Object[] fr = stack.peek();
            @SuppressWarnings("unchecked")
            List<Token> levelLines = (List<Token>) fr[0];
            int[] pos = (int[]) fr[1];
            if (pos[0] >= levelLines.size()) {
                stack.pop();
                if (fr[2] != null) {
                    ((Token) fr[2]).explicitRangeStart = (Long) fr[3];
                    ((Token) fr[2]).explicitRangeEnd = counter - 1;
                }
                continue;
            }
            Token variantLine = levelLines.get(pos[0]++);
            List<Token> lineTokens = variantLine.childs;
            if (lineTokens.isEmpty() || lineTokens.get(0).type != TokenType.VARREF) {
                throw new CompilerException("parse", variantLine.file, variantLine.line,
                        "expected a bare variant name in this nested-hierarchy enum body");
            }
            Token nameTok = lineTokens.get(0);
            Token blockTok = null;
            if (lineTokens.size() > 1) {
                if (lineTokens.get(1).type == TokenType.DELINEATOR && lineTokens.get(1).text.equals("{")) {
                    blockTok = lineTokens.get(1);
                    if (lineTokens.size() > 2) {
                        throw new CompilerException("parse", lineTokens.get(2).file, lineTokens.get(2).line,
                                "unexpected tokens after '" + nameTok.text + "{...}' in a nested-hierarchy "
                                        + "enum body");
                    }
                } else {
                    throw new CompilerException("parse", lineTokens.get(1).file, lineTokens.get(1).line,
                            "a nested-hierarchy enum variant is either a bare name or a bare name followed "
                                    + "by '{...}' -- no explicit value can be written here, it's computed "
                                    + "automatically from its position");
                }
            }
            long startIdx = counter++;
            outVariants.add(nameTok);
            if (blockTok != null) {
                stack.push(new Object[]{blockTok.childs, new int[]{0}, nameTok, startIdx});
            } else {
                nameTok.explicitRangeStart = startIdx;
                nameTok.explicitRangeEnd = startIdx;
            }
        }
    }
}

package caspien.parser;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;

import java.util.ArrayList;
import java.util.Arrays;
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

    public List<Token> gatherKeywordBlocks(List<Token> lines, boolean isRoot) {
        List<Token> result = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            Token line = lines.get(i);
            List<Token> tokens = line.childs;
            Token first = tokens.get(0);

            // "?catch(e){..}" declaration: checked first (a lone 'catch' is rejected further down); emits no code.
            // See docs/Parser.md, "?catch and ?EXPR sugar"
            if (tokens.size() > 1 && first.type == TokenType.OPERATOR && first.text.equals("?")
                    && tokens.get(1).type == TokenType.KEYWORD && tokens.get(1).text.equals("catch")) {
                ParserLines.CatchClauseResult decl = gatherCatchClause(tokens, 1, "?catch(...) { ... }",
                        /* gatherBodyNow */ false);
                hub.lines.pendingOptionalCatchParamName = decl.paramName;
                hub.lines.pendingOptionalCatchRawBody = decl.body;
                i++;
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
            boolean expandedOptionalHere = false;
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
                tryGathered.childs = gatherKeywordBlocks(clonedRawBody, false);
                tryGathered.catchParamName = hub.lines.pendingOptionalCatchParamName;
                tryGathered.decorators = qTok.decorators;
                tryGathered.pinnedComments = qTok.pinnedComments;
                List<Token> replaced = new ArrayList<>(tokens.subList(0, qIdx));
                replaced.add(tryGathered);
                tokens = replaced;
                line.childs = tokens;
                first = tokens.get(0);
                expandedOptionalHere = true;
                // no `continue`: falls through to the ordinary per-line dispatch below
            }

            // "try EXPR catch{..}" can sit anywhere in a line (e.g. "let x = try f() catch{..}"): the whole span
            // collapses to one KEYWORD token (.sub = call tokens, .childs = catch body).
            // See docs/Parser.md, "try / catch span"
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
                    blockTok.childs = gatherKeywordBlocks(blockTok.childs, isRoot);
                    Token gathered = new Token(TokenType.KEYWORD, "try", first.line, first.file);
                    gathered.childs = blockTok.childs;
                    gathered.isTryBlock = true;
                    gathered.decorators = first.decorators;
                    gathered.pinnedComments = first.pinnedComments;
                    result.add(wrapAsLine(gathered));
                    i++;
                    continue;
                }
                if (tryIdx >= 0) {
                    Token tryGathered = gatherTryCatchSpan(tokens, tryIdx);
                    List<Token> replaced = new ArrayList<>(tokens.subList(0, tryIdx));
                    replaced.add(tryGathered);
                    tokens = replaced;
                    line.childs = tokens;
                    first = tokens.get(0);
                } else if (first.type == TokenType.KEYWORD && first.text.equals("catch")) {
                    throw new CompilerException("parse", first.file, first.line,
                            "'catch' without a preceding 'try'");
                }
            }

            if (isLockStatementStart(tokens)) {
                // "match @lock EXPR{...}" (a plain "lock EXPR{...}" is rejected inside isLockStatementStart). "lock" is
                // deliberately never a reserved KEYWORD at the lexer level; the form is detected structurally.
                int[] consumed = new int[1];
                Token gathered = gatherLockStatement(lines, i, consumed);
                result.add(wrapAsLine(gathered));
                i += consumed[0];
                continue;
            }

            if (first.type == TokenType.KEYWORD) {
                switch (first.text) {
                    case "if": {
                        int[] consumed = new int[1];
                        Token gathered = gatherIfChain(lines, i, consumed);
                        result.add(wrapAsLine(gathered));
                        i += consumed[0];
                        continue;
                    }
                    case "elseif":
                    case "else":
                        throw new CompilerException("parse", first.file, first.line,
                                "'" + first.text + "' without a preceding 'if'");
                    case "loop": {
                        Token blockTok = requireTrailingBlock(tokens, first);
                        if (tokens.indexOf(blockTok) != 1) {
                            throw new CompilerException("parse", first.file, first.line,
                                    "'loop' takes no condition");
                        }
                        blockTok.childs = gatherKeywordBlocks(blockTok.childs, false);
                        Token gathered = new Token(TokenType.KEYWORD, "loop", first.line, first.file);
                        gathered.childs = blockTok.childs;
                        gathered.decorators = first.decorators;
                        gathered.pinnedComments = first.pinnedComments;
                        result.add(wrapAsLine(gathered));
                        i++;
                        continue;
                    }
                    case "unsafe":
                    case "safe": {
                        // recurses with the current isRoot (the block may hold declarations at root);
                        // unsafe-in-safe nesting rules are the TypeChecker's
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
                        blockTok.childs = gatherKeywordBlocks(blockTok.childs, isRoot);
                        Token gathered = new Token(TokenType.KEYWORD, first.text, first.line, first.file);
                        gathered.unsafeTags = unsafeTagList;
                        gathered.childs = blockTok.childs;
                        gathered.decorators = first.decorators;
                        gathered.pinnedComments = first.pinnedComments;
                        result.add(wrapAsLine(gathered));
                        i++;
                        continue;
                    }
                    case "for": {
                        // "for [match] i in x{..}": condition goes to .sub; an optional 'match' after 'for' is
                        // stripped and recorded as isForMatch (TypeChecker.checkForLoop). See docs/Parser.md, "for match"
                        Token blockTok = requireTrailingBlock(tokens, first);
                        boolean isForMatch = tokens.size() > 1 && tokens.get(1).type == TokenType.KEYWORD
                                && tokens.get(1).text.equals("match");
                        int conditionStart = isForMatch ? 2 : 1;
                        List<Token> condition = new ArrayList<>(tokens.subList(conditionStart, tokens.indexOf(blockTok)));
                        blockTok.childs = gatherKeywordBlocks(blockTok.childs, false);
                        Token gathered = new Token(TokenType.KEYWORD, "for", first.line, first.file);
                        gathered.sub = condition;
                        gathered.childs = blockTok.childs;
                        gathered.decorators = first.decorators;
                        gathered.pinnedComments = first.pinnedComments;
                        gathered.isForMatch = isForMatch;
                        result.add(wrapAsLine(gathered));
                        i++;
                        continue;
                    }
                    case "assume": {
                        // "assume match EXPR" with an optional block (no block = holds for the rest of the scope)
                        if (tokens.size() < 2 || tokens.get(1).type != TokenType.KEYWORD
                                || !tokens.get(1).text.equals("match")) {
                            throw new CompilerException("parse", first.file, first.line,
                                    "expected 'match' after 'assume'");
                        }
                        Token last = tokens.get(tokens.size() - 1);
                        boolean hasBlock = last.type == TokenType.DELINEATOR && last.text.equals("{");
                        List<Token> condition;
                        Token gathered = new Token(TokenType.KEYWORD, "assume", first.line, first.file);
                        if (hasBlock) {
                            condition = new ArrayList<>(tokens.subList(2, tokens.indexOf(last)));
                            last.childs = gatherKeywordBlocks(last.childs, false);
                            gathered.childs = last.childs;
                            gathered.hasBlock = true;
                        } else {
                            condition = new ArrayList<>(tokens.subList(2, tokens.size()));
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
                        result.add(wrapAsLine(gathered));
                        i++;
                        continue;
                    }
                    case "match": {
                        int[] consumed = new int[1];
                        Token gathered = gatherMatchChain(lines, i, consumed);
                        result.add(wrapAsLine(gathered));
                        i += consumed[0];
                        continue;
                    }
                    case "elsematch":
                        throw new CompilerException("parse", first.file, first.line,
                                "'elsematch' without a preceding 'match'");
                    case "func": {
                        requireRoot(isRoot, first, "func");
                        result.add(wrapAsLine(gatherFunc(tokens, first)));
                        i++;
                        continue;
                    }
                    case "extern": {
                        requireRoot(isRoot, first, "extern");
                        result.add(wrapAsLine(gatherExtern(tokens, first)));
                        i++;
                        continue;
                    }
                    case "export": {
                        requireRoot(isRoot, first, "export");
                        result.add(wrapAsLine(gatherExport(tokens, first)));
                        i++;
                        continue;
                    }
                    case "ASM": {
                        // no requireRoot: ASM is legal at root and inside functions
                        result.add(wrapAsLine(gatherAsm(tokens, first)));
                        i++;
                        continue;
                    }
                    case "type": {
                        requireRoot(isRoot, first, "type");
                        result.add(wrapAsLine(gatherType(tokens, first)));
                        i++;
                        continue;
                    }
                    case "const": {
                        requireRoot(isRoot, first, "const");
                        result.add(wrapAsLine(gatherConst(tokens, first)));
                        i++;
                        continue;
                    }
                    case "struct": {
                        requireRoot(isRoot, first, "struct");
                        result.add(wrapAsLine(gatherStruct(tokens, first)));
                        i++;
                        continue;
                    }
                    case "enum": {
                        requireRoot(isRoot, first, "enum");
                        result.add(wrapAsLine(gatherEnum(tokens, first)));
                        i++;
                        continue;
                    }
                    case "interface": {
                        requireRoot(isRoot, first, "interface");
                        result.add(wrapAsLine(gatherInterface(tokens, first)));
                        i++;
                        continue;
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
                            i++;
                            continue;
                        }
                        if (isConstructorImplStart(tokens)) {
                            result.add(wrapAsLine(hub.gatherConstructorImpl(tokens, first)));
                            i++;
                            continue;
                        }
                        result.add(wrapAsLine(gatherImpl(tokens, first)));
                        i++;
                        continue;
                    }
                    case "library": {
                        requireRoot(isRoot, first, "library");
                        result.add(wrapAsLine(hub.gatherLibrary(tokens, first)));
                        i++;
                        continue;
                    }
                    case "import":
                        requireRoot(isRoot, first, "import");
                        break; // already collapsed to one token by the parser's earlier pass; pass through
                    default:
                        break; // let / return / etc: pass through unchanged
                }
            }
            result.add(line);
            i++;
        }
        return result;
    }

    public void requireRoot(boolean isRoot, Token kwTok, String what) {
        if (!isRoot) {
            throw new CompilerException("parse", kwTok.file, kwTok.line,
                    "'" + what + "' is only allowed at the top level, not nested inside a block");
        }
    }

    public Token wrapAsLine(Token gathered) {
        Token lineTok = new Token(TokenType.LINE, "line", gathered.line, gathered.file);
        lineTok.childs.add(gathered);
        return lineTok;
    }

    /**
     * Gathers "try EXPR catch(e) { BLOCK }" into one synthetic "try" KEYWORD token (.sub = raw expr tokens, .childs = gathered catch body).
     * The span must run to the end of the line. See docs/Parser.md, "Try/catch span and catch clauses".
     */
    public Token gatherTryCatchSpan(List<Token> tokens, int tryIdx) {
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
        List<Token> exprTokens = new ArrayList<>(tokens.subList(tryIdx + 1, catchIdx));
        if (exprTokens.isEmpty()) {
            throw new CompilerException("parse", tryTok.file, tryTok.line,
                    "'try' requires an expression (a call to a '@throws' function) before 'catch'");
        }
        ParserLines.CatchClauseResult catchClause = gatherCatchClause(tokens, catchIdx,
                "try ... catch(...) { ... }", /* gatherBodyNow */ true);
        Token gathered = new Token(TokenType.KEYWORD, "try", tryTok.line, tryTok.file);
        gathered.sub = exprTokens;
        gathered.childs = catchClause.body;
        gathered.catchParamName = catchClause.paramName;
        gathered.decorators = tryTok.decorators;
        gathered.pinnedComments = tryTok.pinnedComments;
        return gathered;
    }

    /**
     * Parses one "catch(e) { ... }" clause that must end the line. With gatherBodyNow=false the body stays raw
     * (a "?catch" body is deep-cloned per use site). See docs/Parser.md, "Try/catch span and catch clauses".
     */
    public ParserLines.CatchClauseResult gatherCatchClause(List<Token> tokens, int catchIdx,
            String trailingContextLabel, boolean gatherBodyNow) {
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
        List<Token> body = gatherBodyNow ? gatherKeywordBlocks(blockTok.childs, false) : blockTok.childs;
        return new ParserLines.CatchClauseResult(catchParamName, body);
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
     * Gathers an if/elseif/else chain (linked through .right); consumedOut[0] = lines consumed.
     * See docs/Parser.md, "if and match chains".
     */
    public Token gatherIfChain(List<Token> lines, int startIndex, int[] consumedOut) {
        List<Token> ifTokens = lines.get(startIndex).childs;
        Token ifTok = ifTokens.get(0);
        Token blockTok = requireTrailingBlock(ifTokens, ifTok);
        List<Token> condition = new ArrayList<>(ifTokens.subList(1, ifTokens.indexOf(blockTok)));
        blockTok.childs = gatherKeywordBlocks(blockTok.childs, false);

        Token head = new Token(TokenType.KEYWORD, "if", ifTok.line, ifTok.file);
        head.sub = condition;
        head.childs = blockTok.childs;
        head.pinnedComments = ifTok.pinnedComments;

        Token chainTail = head;
        int consumed = 1;
        int j = startIndex + 1;
        while (j < lines.size()) {
            List<Token> branchTokens = lines.get(j).childs;
            Token branchKw = branchTokens.get(0);
            if (branchKw.type != TokenType.KEYWORD
                    || !(branchKw.text.equals("elseif") || branchKw.text.equals("else"))) {
                break;
            }
            Token branchBlockTok = requireTrailingBlock(branchTokens, branchKw);
            List<Token> branchCondition;
            if (branchKw.text.equals("elseif")) {
                branchCondition = new ArrayList<>(branchTokens.subList(1, branchTokens.indexOf(branchBlockTok)));
            } else {
                if (branchTokens.indexOf(branchBlockTok) != 1) {
                    throw new CompilerException("parse", branchKw.file, branchKw.line,
                            "'else' takes no condition");
                }
                branchCondition = new ArrayList<>();
            }
            branchBlockTok.childs = gatherKeywordBlocks(branchBlockTok.childs, false);

            Token branch = new Token(TokenType.KEYWORD, branchKw.text, branchKw.line, branchKw.file);
            branch.sub = branchCondition;
            branch.childs = branchBlockTok.childs;
            branch.pinnedComments = branchKw.pinnedComments;

            chainTail.next = branch;
            chainTail = branch;
            consumed++;
            j++;
            if (branchKw.text.equals("else")) {
                break; // 'else' always terminates the chain
            }
        }
        consumedOut[0] = consumed;
        return head;
    }

    /** Same as gatherIfChain for match/elsematch/else, plus the case-block form. See docs/Parser.md, "if and match chains". */
    public Token gatherMatchChain(List<Token> lines, int startIndex, int[] consumedOut) {
        List<Token> matchTokens = lines.get(startIndex).childs;
        Token matchTok = matchTokens.get(0);
        Token blockTok = requireTrailingBlock(matchTokens, matchTok);
        List<Token> condition = new ArrayList<>(matchTokens.subList(1, matchTokens.indexOf(blockTok)));

        if (isCaseMatchBody(blockTok.childs)) {
            // Case-block form "match b{ LABEL:{...} }": decided structurally; no elsematch/else chain is looked for.
            Token enumHead = new Token(TokenType.KEYWORD, "match", matchTok.line, matchTok.file);
            enumHead.isCaseMatch = true;
            enumHead.sub = condition;
            enumHead.childs = gatherMatchCases(blockTok.childs, false);
            enumHead.decorators = matchTok.decorators;
            enumHead.pinnedComments = matchTok.pinnedComments;
            consumedOut[0] = 1;
            return enumHead;
        }

        blockTok.childs = gatherKeywordBlocks(blockTok.childs, false);

        Token head = new Token(TokenType.KEYWORD, "match", matchTok.line, matchTok.file);
        head.sub = condition;
        head.childs = blockTok.childs;
        head.decorators = matchTok.decorators;
        head.pinnedComments = matchTok.pinnedComments;

        Token chainTail = head;
        int consumed = 1;
        int j = startIndex + 1;
        while (j < lines.size()) {
            List<Token> branchTokens = lines.get(j).childs;
            Token branchKw = branchTokens.get(0);
            if (branchKw.type != TokenType.KEYWORD
                    || !(branchKw.text.equals("elsematch") || branchKw.text.equals("else"))) {
                break;
            }
            Token branchBlockTok = requireTrailingBlock(branchTokens, branchKw);
            List<Token> branchCondition;
            if (branchKw.text.equals("elsematch")) {
                branchCondition = new ArrayList<>(branchTokens.subList(1, branchTokens.indexOf(branchBlockTok)));
            } else {
                if (branchTokens.indexOf(branchBlockTok) != 1) {
                    throw new CompilerException("parse", branchKw.file, branchKw.line,
                            "'else' takes no condition");
                }
                branchCondition = new ArrayList<>();
            }
            branchBlockTok.childs = gatherKeywordBlocks(branchBlockTok.childs, false);

            Token branch = new Token(TokenType.KEYWORD, branchKw.text, branchKw.line, branchKw.file);
            branch.sub = branchCondition;
            branch.childs = branchBlockTok.childs;
            branch.pinnedComments = branchKw.pinnedComments;

            chainTail.next = branch;
            chainTail = branch;
            consumed++;
            j++;
            if (branchKw.text.equals("else")) {
                break; // 'else' always terminates the chain
            }
        }
        consumedOut[0] = consumed;
        return head;
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

    /** Gathers "match @lock EXPR{...}" as a "lock" token with isCaseMatch set; the body goes through gatherMatchCases. See docs/Parser.md, "match @lock". */
    public Token gatherLockStatement(List<Token> lines, int startIndex, int[] consumedOut) {
        List<Token> lockTokens = lines.get(startIndex).childs;
        Token lockTok = lockTokens.get(0);
        Token blockTok = requireTrailingBlock(lockTokens, lockTok);
        int conditionStart = 3;
        List<Token> condition = new ArrayList<>(lockTokens.subList(conditionStart, lockTokens.indexOf(blockTok)));

        Token gathered = new Token(TokenType.KEYWORD, "lock", lockTok.line, lockTok.file);
        gathered.sub = condition;
        gathered.decorators = lockTok.decorators;
        gathered.pinnedComments = lockTok.pinnedComments;
        gathered.isCaseMatch = true;
        gathered.childs = gatherMatchCases(blockTok.childs, true);
        consumedOut[0] = 1;
        return gathered;
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

    /**
     * Extracts one Token per case ("LABEL:{...}" or "A|B:{...}") from a case-block body, flattening the lines first.
     * See docs/Parser.md, "Case-match bodies".
     */
    public List<Token> gatherMatchCases(List<Token> bodyLines, boolean allowDefaultPolicyRef) {
        List<Token> flat = new ArrayList<>();
        for (Token lineTok : bodyLines) {
            flat.addAll(lineTok.childs);
        }
        List<Token> cases = new ArrayList<>();
        int i = 0;
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
            caseTok.childs = gatherKeywordBlocks(blockTok.childs, false);
            cases.add(caseTok);
        }
        return cases;
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

    public Token gatherFunc(List<Token> tokens, Token funcTok) {
        if (tokens.size() < 3 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", funcTok.file, funcTok.line,
                    "expected function name after 'func'");
        }
        Token nameTok = tokens.get(1);
        ParserLines.TypeParamParseResult typeParams = hub.lines.parseOptionalTypeParams(tokens, 2);
        int parenIdx = typeParams.nextIndex;
        if (parenIdx >= tokens.size() || tokens.get(parenIdx).type != TokenType.DELINEATOR
                || !tokens.get(parenIdx).text.equals("(")) {
            throw new CompilerException("parse", funcTok.file, funcTok.line,
                    "expected '(' after function name"
                            + (typeParams.names != null ? "'s type-parameter list" : ""));
        }
        Token paramsTok = tokens.get(parenIdx);
        Token blockTok = requireTrailingBlock(tokens, funcTok);
        int blockIdx = tokens.indexOf(blockTok);
        List<Token> returnTypeTokens = new ArrayList<>(tokens.subList(parenIdx + 1, blockIdx));
            // Reset the pending "?catch" state here, the only reset site (gatherImpl and constructors route through gatherFunc).
        hub.lines.pendingOptionalCatchParamName = null;
        hub.lines.pendingOptionalCatchRawBody = null;
        blockTok.childs = gatherKeywordBlocks(blockTok.childs, false);

        Token gathered = new Token(TokenType.KEYWORD, "func", funcTok.line, funcTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(nameTok);
        gathered.sub.add(paramsTok);
        gathered.sub.addAll(returnTypeTokens);
        gathered.typeParams = typeParams.names;
        gathered.typeParamBounds = typeParams.bounds;
        gathered.decorators = funcTok.decorators;
        gathered.childs = blockTok.childs;
        gathered.pinnedComments = funcTok.pinnedComments;
        return gathered;
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
    public ParserLines.ImplementsParseResult parseOptionalImplementsClause(List<Token> tokens, int idx) {
        if (idx >= tokens.size() || tokens.get(idx).type != TokenType.OPERATOR
                || !tokens.get(idx).text.equals("implements")) {
            return new ParserLines.ImplementsParseResult(null, idx);
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
        return new ParserLines.ImplementsParseResult(names, j);
    }

    /** Body for "struct"; a member line starting with "extends" is a parse error (inheritance was removed). */
    public Token gatherStructLike(List<Token> tokens, Token kwTok, String kindText) {
        if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", kwTok.file, kwTok.line,
                    "expected a name after '" + kindText + "'");
        }
        Token nameTok = tokens.get(1);
        ParserLines.TypeParamParseResult typeParams = hub.lines.parseOptionalTypeParams(tokens, 2);
        ParserLines.ImplementsParseResult implementsClause = parseOptionalImplementsClause(tokens, typeParams.nextIndex);
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
            long[] counter = {0};
            for (Token variantLine : blockTok.childs) {
                parseNestedEnumVariant(variantLine, variants, counter);
            }
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

    /** Pre-order walk of a nested enum body: each variant gets start = running index, end = last index in its block (counter threaded by reference). */
    public void parseNestedEnumVariant(Token variantLine, List<Token> outVariants, long[] counter) {
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
        long startIdx = counter[0]++;
        outVariants.add(nameTok);
        long endIdx = startIdx;
        if (blockTok != null) {
            for (Token childLine : blockTok.childs) {
                parseNestedEnumVariant(childLine, outVariants, counter);
            }
            endIdx = counter[0] - 1;
        }
        nameTok.explicitRangeStart = startIdx;
        nameTok.explicitRangeEnd = endIdx;
    }

    /** "interface Name{ sig; ... }" -- one "interface_method" token per signature; also "extends", "static" and default-method lines. See docs/Parser.md, "Interfaces". */
    public Token gatherInterface(List<Token> tokens, Token interfaceTok) {
        if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", interfaceTok.file, interfaceTok.line,
                    "expected interface name after 'interface'");
        }
        Token nameTok = tokens.get(1);
        ParserLines.TypeParamParseResult typeParams = hub.lines.parseOptionalTypeParams(tokens, 2);
        Token blockTok = requireTrailingBlock(tokens, interfaceTok);
        if (tokens.indexOf(blockTok) != typeParams.nextIndex) {
            throw new CompilerException("parse", interfaceTok.file, interfaceTok.line,
                    "unexpected tokens between interface name and '{'");
        }
        List<Token> methods = new ArrayList<>();
        List<String> extendsNames = new ArrayList<>();
        for (Token methodLine : blockTok.childs) {
            List<Token> lineTokens = methodLine.childs;
            if (!lineTokens.isEmpty() && lineTokens.get(0).type == TokenType.KEYWORD
                    && lineTokens.get(0).text.equals("extends")) {
                if (lineTokens.size() != 2 || lineTokens.get(1).type != TokenType.VARREF) {
                    throw new CompilerException("parse", lineTokens.get(0).file, lineTokens.get(0).line,
                            "expected 'extends InterfaceName'");
                }
                extendsNames.add(lineTokens.get(1).text);
                continue;
            }
            // "static func ..." (a @default static method with body) or "static NAME(...) Ret;" (plain static signature).
            if (!lineTokens.isEmpty() && lineTokens.get(0).type == TokenType.MODIFIER
                    && lineTokens.get(0).text.equals("static")) {
                List<Token> afterStatic = lineTokens.subList(1, lineTokens.size());
                Token gatheredStatic;
                if (!afterStatic.isEmpty() && afterStatic.get(0).type == TokenType.KEYWORD
                        && afterStatic.get(0).text.equals("func")) {
                    gatheredStatic = gatherFunc(afterStatic, afterStatic.get(0));
                } else {
                    gatheredStatic = gatherInterfaceMethod(afterStatic, interfaceTok);
                }
                gatheredStatic.isStaticMethod = true;
                methods.add(gatheredStatic);
                continue;
            }
            if (!lineTokens.isEmpty() && lineTokens.get(0).type == TokenType.KEYWORD
                    && lineTokens.get(0).text.equals("func")) {
            // Default method: full func syntax via gatherFunc; whether "@default" is present is TypeChecker.registerInterface's concern.
                methods.add(gatherFunc(lineTokens, lineTokens.get(0)));
                continue;
            }
            methods.add(gatherInterfaceMethod(lineTokens, interfaceTok));
        }
        Token gathered = new Token(TokenType.KEYWORD, "interface", interfaceTok.line, interfaceTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(nameTok);
        gathered.typeParams = typeParams.names;
        gathered.typeParamBounds = typeParams.bounds;
        gathered.extendsNames = extendsNames;
        gathered.decorators = interfaceTok.decorators;
        gathered.childs = methods;
        gathered.pinnedComments = interfaceTok.pinnedComments;
        return gathered;
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

    /** "impl Iface for Concrete{...}" or "impl Concrete{...}"; methods go through gatherFunc directly (impl bodies are exempt from the isRoot check). .sub has 1 or 2 name tokens. */
    public Token gatherImpl(List<Token> tokens, Token implTok) {
        ParserLines.TypeParamParseResult implTypeParams = hub.lines.parseOptionalTypeParams(tokens, 1);
        int idx = implTypeParams.nextIndex;

        if (idx >= tokens.size() || tokens.get(idx).type != TokenType.VARREF) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "expected a name after 'impl'" + (implTypeParams.names != null ? "'s type-parameter list" : ""));
        }
        Token firstNameTok = tokens.get(idx);
        idx++;
        ParserLines.TypeArgParseResult firstArgs = hub.lines.parseOptionalTypeArgsRaw(tokens, idx);
        idx = firstArgs.nextIndex;

        Token interfaceNameTok = null;
        List<List<Token>> interfaceArgs = null;
        Token concreteNameTok;
        List<List<Token>> concreteArgs;

        if (idx < tokens.size() && tokens.get(idx).type == TokenType.KEYWORD && tokens.get(idx).text.equals("for")) {
            idx++;
            if (idx >= tokens.size() || tokens.get(idx).type != TokenType.VARREF) {
                throw new CompilerException("parse", implTok.file, implTok.line,
                        "expected a concrete type name after 'for'");
            }
            interfaceNameTok = firstNameTok;
            interfaceArgs = firstArgs.groups;
            concreteNameTok = tokens.get(idx);
            idx++;
            ParserLines.TypeArgParseResult cArgs = hub.lines.parseOptionalTypeArgsRaw(tokens, idx);
            idx = cArgs.nextIndex;
            concreteArgs = cArgs.groups;
        } else {
            concreteNameTok = firstNameTok;
            concreteArgs = firstArgs.groups;
        }

        Token blockTok = requireTrailingBlock(tokens, implTok);
        if (tokens.indexOf(blockTok) != idx) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "unexpected tokens before 'impl' block's '{'");
        }

        List<Token> methods = new ArrayList<>();
        for (Token line : blockTok.childs) {
            List<Token> lineTokens = line.childs;
            boolean lineIsStaticMethod = !lineTokens.isEmpty() && lineTokens.get(0).type == TokenType.MODIFIER
                    && lineTokens.get(0).text.equals("static");
            int funcKeywordIdx = lineIsStaticMethod ? 1 : 0;
            if (lineTokens.size() <= funcKeywordIdx || lineTokens.get(funcKeywordIdx).type != TokenType.KEYWORD
                    || !lineTokens.get(funcKeywordIdx).text.equals("func")) {
                throw new CompilerException("parse", line.file, line.line,
                        "only 'func'/'static func' declarations are allowed directly inside an 'impl' block");
            }
            Token gatheredMethod = gatherFunc(lineTokens.subList(funcKeywordIdx, lineTokens.size()),
                    lineTokens.get(funcKeywordIdx));
            gatheredMethod.isStaticMethod = lineIsStaticMethod;
            if (lineIsStaticMethod) {
            // Decorators attach to the line's first token ("static"), not to "func": copy them over or they are lost.
                gatheredMethod.decorators = lineTokens.get(0).decorators;
            }
            methods.add(gatheredMethod);
        }

        Token gathered = new Token(TokenType.KEYWORD, "impl", implTok.line, implTok.file);
        gathered.sub = new ArrayList<>();
        if (interfaceNameTok != null) {
            interfaceNameTok.genericArgs = interfaceArgs;
            gathered.sub.add(interfaceNameTok);
        }
        concreteNameTok.genericArgs = concreteArgs;
        gathered.sub.add(concreteNameTok);
        gathered.typeParams = implTypeParams.names; // the impl block's own <T,...>, if any
        gathered.typeParamBounds = implTypeParams.bounds;
        gathered.decorators = implTok.decorators;
        gathered.childs = methods;
        gathered.pinnedComments = implTok.pinnedComments;
        return gathered;
    }

    /** True right after "impl" for "impl [<T>] constructor for Name(...) self {...}" (an interface literally named "constructor" is the accepted collision). */
    public boolean isConstructorImplStart(List<Token> tokens) {
            // Peek only: parseOptionalTypeParams has no side effects.
        ParserLines.TypeParamParseResult implTypeParams = hub.lines.parseOptionalTypeParams(tokens, 1);
        int idx = implTypeParams.nextIndex;
        return tokens.size() > idx + 1
                && tokens.get(idx).type == TokenType.VARREF && tokens.get(idx).text.equals("constructor")
                && tokens.get(idx + 1).type == TokenType.KEYWORD && tokens.get(idx + 1).text.equals("for");
    }
}

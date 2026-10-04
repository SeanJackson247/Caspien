package caspien;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 2: consumes the flat token list produced by Lexer and, in the
 * order given by the project spec, performs:
 *
 *   1. nest()            - build the childs of every delineator token,
 *                           consuming and discarding matching close tokens
 *   2. splitIntoLines()   - group each statement-sequence scope (the file
 *                           root, and every '{' block) into LINE tokens,
 *                           split on TERMINATOR; TERMINATOR tokens are
 *                           discarded in the process
 *   3. mergeLet()         - "let" absorbs its nearest non-whitespace
 *                           right-hand token (the variable name) into
 *                           its own childs
 *   4. collapseImports()  - an import line collapses to a single token,
 *                           preserving all comments pinned within that line
 *   5. removeWhitespace() - strips WHITESPACE tokens everywhere
 *   6. insertCallLookup() - VARREF immediately followed by '(' or '['
 *                           gets a synthetic CALL/LOOKUP operator token
 *                           inserted between them
 *   7. gatherKeywordBlocks() - if/elseif/else, loop, func, struct, enum
 *                           each collapse to a single token. See the
 *                           method's own doc comment for the exact
 *                           shape each produces (sub/childs/right usage
 *                           differs per construct, documented there).
 *
 * NOT yet implemented (left for a later pass): shunting-yard RPN
 * conversion, tree building, import resolution, type/logic checking.
 *
 * Scoping rule used throughout: '{' delineators are statement-sequence
 * scopes (their childs become a list of LINE tokens); '(' and '['
 * delineators are expression scopes (their childs stay a flat token
 * list -- there is no such thing as "a line" inside an argument list or
 * a subscript, so terminators found there are just noise to discard,
 * not statement separators).
 */
public class Parser {

    private static final String OPEN_CHARS = "([{";
    private static final String CLOSE_CHARS = ")]}";

    /**
     * The "?catch(e){...}" pseudo-operator's own current declared block,
     * as seen so far by `gatherKeywordBlocks`'s single, sequential,
     * depth-first walk of the function body currently being gathered --
     * null whenever no "?catch(...)" has been encountered yet (or none is
     * currently in effect; see `gatherFunc`'s own reset). A bare "?EXPR"
     * (see `gatherKeywordBlocks`'s own handling) expands using whichever
     * "?catch(...)" was *most recently encountered* in program order --
     * "the block from the most recently encountered ?catch statement,"
     * confirmed directly -- which this single pair of instance fields,
     * updated in place as the walk proceeds and never explicitly
     * saved/restored around a *nested* block (if/loop/match/.../a real
     * try/catch's own catch body/a "try { ... }" block's own body), is
     * exactly built to track: entering a nested block inherits whatever is
     * currently pending, and a "?catch(...)" declared *inside* that nested
     * block remains in effect after the walk returns from it too -- there
     * is deliberately no block-scoped save/restore here at all, matching
     * the literal, unscoped, "most recently encountered" reading of the
     * spec as closely as possible, not a lexically-scoped declaration.
     * Reset to null only where a genuinely new function/method body starts
     * (`gatherFunc`) -- functions never nest, so a single unconditional
     * reset there, with no save/restore, is enough to stop one function's
     * own "?catch" declarations from leaking into a sibling function.
     *
     * The declared body itself is kept genuinely raw (never gathered) --
     * see `gatherCatchClause`'s own doc comment for why: a fresh,
     * independent `Token.deepCloneRaw()`'d copy is gathered anew at every
     * "?EXPR" use site, the same "store raw, clone-and-gather per use"
     * shape `TypeChecker`'s own "impl default match @lock" policy template
     * already established.
     *
     * Known, deliberately unaddressed gap: if a "?catch(...)"'s own
     * declared block itself contains further "?catch"/"?EXPR" sugar, that
     * inner sugar is only ever expanded lazily, once per use site, as part
     * of gathering that specific clone -- and, since gathering a clone
     * runs through this exact same unscoped, shared-state mechanism, any
     * further "?catch" encountered *while* gathering one use site's own
     * clone remains in effect afterward, for whatever code lexically
     * follows that "?EXPR" in the *enclosing* scope too. Not fixed --
     * nested "?" sugar inside a "?catch"'s own block is a genuinely exotic
     * shape the feature's own spec never exercises; flagged here rather
     * than silently doing something unconsidered.
     */
    private String pendingOptionalCatchParamName = null;
    private List<Token> pendingOptionalCatchRawBody = null;

    /** Returns the matching open character for a given close character, e.g. ')' -> '('. */
    private static char matchingOpen(char close) {
        return OPEN_CHARS.charAt(CLOSE_CHARS.indexOf(close));
    }

    /**
     * Runs the whole implemented pipeline and returns the file's top
     * level as a list of LINE tokens, fully processed through
     * keyword-block gathering.
     */
    public List<Token> parse(List<Token> lexerOutput) {
        List<Token> nested = nest(lexerOutput);
        gatherTemplateStringsDeep(nested);
        List<Token> lines = splitIntoLines(nested);

        forEachLineDeep(lines, this::mergeLetInLine);
        forEachLineDeep(lines, this::collapseImportInLine);
        stripDecoratorsDeep(lines);

        removeWhitespaceInLines(lines);
        mergeThenOperators(lines);
        collapseGenericInstantiationsInLines(lines);
        insertCallLookupInLines(lines);

        return gatherKeywordBlocks(lines, true);
    }

    // ---- 1. nesting -------------------------------------------------

    private List<Token> nest(List<Token> flat) {
        List<Token> root = new ArrayList<>();
        List<List<Token>> targetStack = new ArrayList<>();
        List<Token> openStack = new ArrayList<>();
        targetStack.add(root);

        for (Token t : flat) {
            if (t.type == TokenType.DELINEATOR && OPEN_CHARS.indexOf(t.text.charAt(0)) >= 0) {
                targetStack.get(targetStack.size() - 1).add(t);
                targetStack.add(t.childs);
                openStack.add(t);
            } else if (t.type == TokenType.DELINEATOR && CLOSE_CHARS.indexOf(t.text.charAt(0)) >= 0) {
                if (openStack.isEmpty()) {
                    throw new CompilerException("parse", t.file, t.line,
                            "unmatched closing '" + t.text + "'");
                }
                Token open = openStack.remove(openStack.size() - 1);
                targetStack.remove(targetStack.size() - 1);
                char expectedOpen = matchingOpen(t.text.charAt(0));
                if (open.text.charAt(0) != expectedOpen) {
                    throw new CompilerException("parse", t.file, t.line,
                            "mismatched delineator: '" + open.text + "' opened at line "
                                    + open.line + " but closed with '" + t.text + "'");
                }
                // the close token itself is discarded, per spec
            } else {
                targetStack.get(targetStack.size() - 1).add(t);
            }
        }

        if (!openStack.isEmpty()) {
            Token unclosed = openStack.get(openStack.size() - 1);
            throw new CompilerException("parse", unclosed.file, unclosed.line,
                    "unmatched opening '" + unclosed.text + "'");
        }
        return root;
    }

    /**
     * "let my_error_string = `Important Config file(${CONFIG_FILE})
     * not found on ${__FILENAME}:${__LINE}`," confirmed directly.
     * Collapses the lexer's own flat TEMPLATE_STRING_START /
     * TEMPLATE_STRING_PART / TEMPLATE_EXPR_START / "{...}" (already
     * grouped by `nest()` just above, exactly the same as any other
     * "{...}") / TEMPLATE_STRING_END marker sequence into a single
     * TEMPLATE_STRING Token, `.childs` alternating literal-text pieces
     * and one fully-nested expression group per "${...}" segment --
     * nothing downstream of this point (splitIntoLines, RpnConverter,
     * TreeBuilder) ever needs to know these markers existed at all.
     * Runs recursively over the whole tree (any nested "{"/"("/"["
     * group's own `.childs`), since a template string can appear
     * anywhere an ordinary expression can, not just at a statement's
     * own top level.
     */
    private void gatherTemplateStringsDeep(List<Token> flat) {
        List<Token> result = new ArrayList<>();
        int i = 0;
        while (i < flat.size()) {
            Token t = flat.get(i);
            if (t.type == TokenType.OPERATOR && t.text.equals("TEMPLATE_STRING_START")) {
                List<Token> segments = new ArrayList<>();
                int j = i + 1;
                while (j < flat.size()
                        && !(flat.get(j).type == TokenType.OPERATOR
                                && flat.get(j).text.equals("TEMPLATE_STRING_END"))) {
                    Token seg = flat.get(j);
                    if (seg.type == TokenType.OPERATOR && seg.text.equals("TEMPLATE_STRING_PART")) {
                        segments.add(seg);
                        j++;
                    } else if (seg.type == TokenType.OPERATOR && seg.text.equals("TEMPLATE_EXPR_START")) {
                        j++; // consume the marker itself
                        Token braceGroup = flat.get(j); // the already-nested "{...}" token
                        gatherTemplateStringsDeep(braceGroup.childs); // a template string can nest inside one
                        // Every later pass that strips whitespace,
                        // collapses generic-instantiation syntax, or
                        // inserts a CALL marker only ever recurses into
                        // a plain '('/'['/'{' DELINEATOR -- never into
                        // a TEMPLATE_STRING's own nested segments, since
                        // none of them know this token type exists.
                        // Rather than patch each of those passes
                        // individually (most of which could never
                        // matter for a compile-time-only expression
                        // anyway -- there's nothing to call, nothing
                        // generic to instantiate, inside a "${...}"),
                        // whitespace is stripped right here, the one
                        // concrete gap actually hit by testing --
                        // "${A + B}" otherwise reached RpnConverter/
                        // TreeBuilder still carrying its own interior
                        // WHITESPACE tokens, which neither expects.
                        removeWhitespaceFromExprGroup(braceGroup.childs);
                        segments.add(braceGroup);
                        j++;
                    } else {
                        throw new CompilerException("parse", seg.file, seg.line,
                                "internal error: unexpected token '" + seg.text
                                        + "' inside a template literal");
                    }
                }
                if (j >= flat.size()) {
                    throw new CompilerException("parse", t.file, t.line,
                            "internal error: unterminated template literal reached end of input");
                }
                Token templateTok = new Token(TokenType.TEMPLATE_STRING, "`...`", t.line, t.file);
                templateTok.childs = segments;
                result.add(templateTok);
                i = j + 1; // skip past TEMPLATE_STRING_END
                continue;
            }
            if (t.childs != null) {
                gatherTemplateStringsDeep(t.childs);
            }
            result.add(t);
            i++;
        }
        flat.clear();
        flat.addAll(result);
    }

    /** Recursively strips WHITESPACE (and any leftover TERMINATOR) from a "${...}" segment's own token span -- the general removeWhitespaceInFlat pass never reaches here, since it only ever recurses into a plain DELINEATOR, and TEMPLATE_STRING isn't one. Only ever needs to recurse into '('/'[' (an ordinary group the expression itself might contain, e.g. "${(A+B)*C}") -- a '{' (a struct literal) could never appear in a compile-time-only expression at all, so there's nothing to specially handle for it here. */
    private void removeWhitespaceFromExprGroup(List<Token> flat) {
        for (int i = flat.size() - 1; i >= 0; i--) {
            TokenType t = flat.get(i).type;
            if (t == TokenType.WHITESPACE || t == TokenType.TERMINATOR) {
                flat.remove(i);
            }
        }
        for (Token t : flat) {
            if (t.type == TokenType.DELINEATOR && (t.text.equals("(") || t.text.equals("["))) {
                removeWhitespaceFromExprGroup(t.childs);
            }
        }
    }

    // ---- 2. line splitting -------------------------------------------

    /**
     * Groups a flat statement-sequence token list into LINE tokens.
     * Splits on TERMINATOR, and *also* forces a line break immediately
     * after any '{' delineator token: a block, once closed, always ends
     * its containing line, regardless of what follows on the same
     * physical source line (so "if x { ret } else { ret }" splits into
     * two lines exactly like it would if 'else' were on its own line).
     * '(' and '[' don't trigger this -- they're expression scopes, and
     * whatever follows a call/subscript on the same line is still part
     * of the same statement (e.g. "foo() + 1").
     */
    /**
     * Keywords whose own '{' block genuinely ends the statement header
     * it belongs to. A '{' found on a line NOT starting with one of
     * these is presumed to be something else entirely -- specifically,
     * a struct-literal instantiation ("Point{ x= 1, y= 2 }") used as an
     * expression, which must NOT force a line break here or the
     * containing statement (e.g. "let point = Point{...}") would be
     * truncated right after the literal's opening brace. See "struct
     * instantiation" in the Parser class doc comment / CLAUDE.md for
     * the fuller reasoning.
     */
    private static final Set<String> BLOCK_HEADER_KEYWORDS = new HashSet<>(Arrays.asList(
            "if", "elseif", "else", "loop", "func", "struct", "enum", "interface", "impl", "for", "library"
    ));

    private boolean lineStartsWithBlockHeaderKeyword(List<Token> current) {
        for (Token t : current) {
            if (t.type == TokenType.WHITESPACE) {
                continue;
            }
            return t.type == TokenType.KEYWORD && BLOCK_HEADER_KEYWORDS.contains(t.text);
        }
        return false;
    }

    private List<Token> splitIntoLines(List<Token> flat) {
        List<Token> lines = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int currentLine = 1;
        boolean haveStart = false;

        for (Token t : flat) {
            if (t.type == TokenType.TERMINATOR) {
                if (hasNonWhitespace(current)) {
                    lines.add(makeLine(current, currentLine));
                }
                current = new ArrayList<>();
                haveStart = false;
                continue;
            }
            if (!haveStart) {
                currentLine = t.line;
                haveStart = true;
            }
            current.add(t);
            if (t.type == TokenType.DELINEATOR && t.text.equals("{")
                    && lineStartsWithBlockHeaderKeyword(current)) {
                lines.add(makeLine(current, currentLine));
                current = new ArrayList<>();
                haveStart = false;
            }
        }
        if (hasNonWhitespace(current)) {
            lines.add(makeLine(current, currentLine));
        }

        // Recurse into every '{' block found in each line, splitting its
        // childs into lines too. '(' and '[' childs stay flat but are
        // still walked, in case a '{' somehow appears nested inside one.
        for (Token lineTok : lines) {
            splitNestedBlocks(lineTok.childs);
        }
        return lines;
    }

    private void splitNestedBlocks(List<Token> flat) {
        for (Token t : flat) {
            if (t.type != TokenType.DELINEATOR) {
                continue;
            }
            if (t.text.equals("{")) {
                t.childs = splitIntoLines(t.childs);
            } else {
                splitNestedBlocks(t.childs);
            }
        }
    }

    private boolean hasNonWhitespace(List<Token> toks) {
        for (Token t : toks) {
            if (t.type != TokenType.WHITESPACE) {
                return true;
            }
        }
        return false;
    }

    private Token makeLine(List<Token> content, int line) {
        Token lineTok = new Token(TokenType.LINE, "line", line, content.get(0).file);
        lineTok.childs = content;
        return lineTok;
    }

    // ---- generic "visit every line, anywhere in the tree" walker -----

    private interface LineVisitor {
        void visit(Token lineToken);
    }

    private void forEachLineDeep(List<Token> lines, LineVisitor visitor) {
        for (Token lineTok : lines) {
            visitor.visit(lineTok);
            scanForBlockLines(lineTok.childs, visitor);
        }
    }

    private void scanForBlockLines(List<Token> flat, LineVisitor visitor) {
        for (Token t : flat) {
            if (t.type != TokenType.DELINEATOR) {
                continue;
            }
            if (t.text.equals("{")) {
                forEachLineDeep(t.childs, visitor);
            } else {
                scanForBlockLines(t.childs, visitor);
            }
        }
    }

    // ---- decorator lines ("@name" / "@name(args)") ---------------------

    /**
     * "parsing should be easy - if the line starts with '@' it's a
     * decorator, and it's a decorator till the end of the line,"
     * confirmed directly. Runs deep (recursing into every nested '{'
     * block, mirroring forEachLineDeep/scanForBlockLines' own
     * traversal) so a decorator can precede *any* construct anywhere --
     * a struct/func/interface/impl/type/global at the root, or a
     * for/loop/cast statement nested inside a function body. Each run
     * of consecutive decorator lines is removed entirely from `lines`
     * and attached (as parsed Decorator objects, stacking order
     * preserved) to the raw first token of whichever line follows --
     * every gather* method that later wraps that raw token into a new
     * "gathered" Token copies `.decorators` across, so they survive.
     * Which decorators are actually *valid* on which kind of
     * construct is a TypeChecker concern entirely, not Parser's --
     * this pass only recognizes the two shapes ("@name" and
     * "@name(arg,arg)"), nothing about their meaning.
     */
    private void stripDecoratorsDeep(List<Token> lines) {
        List<Token.Decorator> pending = new ArrayList<>();
        List<Token> result = new ArrayList<>();
        for (Token lineTok : lines) {
            List<Token> tokens = lineTok.childs;
            int idx = firstNonWhitespaceIndex(tokens);
            if (idx >= 0 && tokens.get(idx).type == TokenType.OPERATOR && tokens.get(idx).text.equals("@")) {
                if (isPubBlockShape(tokens, idx)) {
                    // "I want a @pub{} block for being able to group
                    // multiple fields as public in a struct," confirmed
                    // directly -- a genuinely separate shape from an
                    // ordinary decorator line, handled entirely here
                    // rather than by extending parseDecoratorLine (which
                    // expects nothing after a decorator's own name/args
                    // on the same line, and would reject a trailing '{'
                    // outright). The block is fully unwrapped at this
                    // point: its own contents (already their own
                    // properly line-split sub-structure, since
                    // splitIntoLines' own recursive pass -- run earlier,
                    // over the whole file -- already descended into
                    // every '{' it found, decorator or not) are spliced
                    // directly into `result` in place of the "@pub{...}"
                    // line itself, each one's own first token marked
                    // `isPubBlockMember`, with each such line's own
                    // *contents* still separately run back through this
                    // exact same pass (so an ordinary decorator on one
                    // specific field inside the block, if this language
                    // ever needed that, would still work correctly, and
                    // any decorator immediately preceding the "@pub{"
                    // line itself is correctly required to be pending
                    // for whatever the *block* produces, not silently
                    // dropped).
                    int nameIdx = idx + 1;
                    while (nameIdx < tokens.size() && tokens.get(nameIdx).type == TokenType.WHITESPACE) {
                        nameIdx++;
                    }
                    int braceIdx = nameIdx + 1;
                    while (braceIdx < tokens.size() && tokens.get(braceIdx).type == TokenType.WHITESPACE) {
                        braceIdx++;
                    }
                    Token braceTok = tokens.get(braceIdx);
                    List<Token> innerLines = new ArrayList<>(braceTok.childs);
                    stripDecoratorsDeep(innerLines);
                    for (Token innerLine : innerLines) {
                        int innerIdx = firstNonWhitespaceIndex(innerLine.childs);
                        if (innerIdx >= 0) {
                            innerLine.childs.get(innerIdx).isPubBlockMember = true;
                            if (!pending.isEmpty()) {
                                innerLine.childs.get(innerIdx).decorators = pending;
                                pending = new ArrayList<>();
                            }
                        }
                        result.add(innerLine);
                    }
                    continue;
                }
                pending.add(parseDecoratorLine(tokens, idx, lineTok));
                continue; // the whole decorator line is dropped, not kept in `result`
            }
            if (idx >= 0 && !pending.isEmpty()) {
                tokens.get(idx).decorators = pending;
                pending = new ArrayList<>();
            }
            result.add(lineTok);
            scanForBlockLinesDecorators(tokens);
        }
        if (!pending.isEmpty()) {
            Token.Decorator last = pending.get(pending.size() - 1);
            throw new CompilerException("parse", last.file, last.line,
                    "'@" + last.name + "' decorates nothing -- no statement follows it in this block");
        }
        lines.clear();
        lines.addAll(result);
    }

    /** True when `tokens`, starting at its own "@" (index `idx`), is exactly "@pub" immediately followed by "{" -- the one, narrow shape that gets the special @pub{} block treatment, never any other decorator name (even one also named "pub" but with args, "@pub(...)", falls through to the ordinary decorator path instead, which will itself reject a trailing '{' the normal way if one somehow followed). */
    private boolean isPubBlockShape(List<Token> tokens, int idx) {
        int nameIdx = idx + 1;
        while (nameIdx < tokens.size() && tokens.get(nameIdx).type == TokenType.WHITESPACE) {
            nameIdx++;
        }
        if (nameIdx >= tokens.size() || !tokens.get(nameIdx).text.equals("pub")) {
            return false;
        }
        int braceIdx = nameIdx + 1;
        while (braceIdx < tokens.size() && tokens.get(braceIdx).type == TokenType.WHITESPACE) {
            braceIdx++;
        }
        return braceIdx < tokens.size() && tokens.get(braceIdx).type == TokenType.DELINEATOR
                && tokens.get(braceIdx).text.equals("{") && braceIdx == tokens.size() - 1;
    }

    private void scanForBlockLinesDecorators(List<Token> flat) {
        for (Token t : flat) {
            if (t.type != TokenType.DELINEATOR) {
                continue;
            }
            if (t.text.equals("{")) {
                stripDecoratorsDeep(t.childs);
            } else {
                scanForBlockLinesDecorators(t.childs);
            }
        }
    }

    /** tokens.get(idx) is the '@' token; parses "name" or "name(arg,arg,...)" starting right after it. */
    private Token.Decorator parseDecoratorLine(List<Token> tokens, int idx, Token lineTok) {
        int nameIdx = idx + 1;
        while (nameIdx < tokens.size() && tokens.get(nameIdx).type == TokenType.WHITESPACE) {
            nameIdx++;
        }
        if (nameIdx >= tokens.size() || !isDecoratorWord(tokens.get(nameIdx))) {
            throw new CompilerException("parse", lineTok.file, lineTok.line, "expected a name after '@'");
        }
        Token nameTok = tokens.get(nameIdx);
        List<String> args = new ArrayList<>();
        List<Boolean> argIsString = new ArrayList<>();
        int next = nameIdx + 1;
        while (next < tokens.size() && tokens.get(next).type == TokenType.WHITESPACE) {
            next++;
        }
        if (next < tokens.size() && tokens.get(next).type == TokenType.DELINEATOR && tokens.get(next).text.equals("(")) {
            List<Token> parenChilds = tokens.get(next).childs;
            int firstReal = 0;
            while (firstReal < parenChilds.size() && parenChilds.get(firstReal).type == TokenType.WHITESPACE) {
                firstReal++;
            }
            if (nameTok.text.equals("lock") && firstReal < parenChilds.size()
                    && parenChilds.get(firstReal).type == TokenType.KEYWORD
                    && parenChilds.get(firstReal).text.equals("match")) {
                Token.Decorator d = parseLockMatchQualifier(parenChilds, firstReal, nameTok, lineTok);
                next++;
                while (next < tokens.size() && tokens.get(next).type == TokenType.WHITESPACE) {
                    next++;
                }
                if (next < tokens.size()) {
                    throw new CompilerException("parse", tokens.get(next).file, tokens.get(next).line,
                            "unexpected token '" + tokens.get(next).text + "' after '@" + nameTok.text + "'");
                }
                return d;
            }
            for (Token argTok : tokens.get(next).childs) {
                if (argTok.type == TokenType.WHITESPACE
                        || (argTok.type == TokenType.OPERATOR && argTok.text.equals(","))) {
                    continue;
                }
                if (argTok.type == TokenType.STRING) {
                    args.add(argTok.literalValue);
                    argIsString.add(true);
                } else if (isDecoratorWord(argTok) || argTok.type == TokenType.INTEGER) {
                    args.add(argTok.text);   // a bare word, or a whole number (`@unroll(4)`)
                    argIsString.add(false);
                } else {
                    throw new CompilerException("parse", argTok.file, argTok.line,
                            "decorator arguments must be a string literal, a bare identifier or a whole number, found '"
                                    + argTok.text + "'");
                }
            }
            next++;
            while (next < tokens.size() && tokens.get(next).type == TokenType.WHITESPACE) {
                next++;
            }
        }
        if (next < tokens.size()) {
            throw new CompilerException("parse", tokens.get(next).file, tokens.get(next).line,
                    "unexpected token '" + tokens.get(next).text + "' after '@" + nameTok.text + "'");
        }
        return new Token.Decorator(nameTok.text, args, argIsString, lineTok.line, lineTok.file);
    }

    /**
     * "@lock(match self.x : X)," confirmed directly -- parses this
     * specific, custom grammar directly from the raw tokens inside the
     * decorator's own parens ("match", then "self", then '.', then the
     * field name, then ':', then one or more '|'-chained variant
     * names), entirely separate from the ordinary bare-identifier/
     * string decorator-argument path just above, since nothing about
     * this shape fits that grammar at all. `X` "may also be a variant
     * chain, like: TRUE|FALSE," confirmed directly -- each name in the
     * chain collected into `lockVariants`, in the order written.
     */
    /**
     * "@lock(match self.x : X)" or "@lock(match i in self.x2 [and i2 in
     * self.x3]*)," confirmed directly -- two genuinely different
     * qualifier shapes sharing the same "@lock(match ...)" entry point,
     * distinguished by what immediately follows "match": literally
     * "self" (enum-style, the original, struct-level feature, now also
     * usable on a method) or any other bare name (the parameter of an
     * "in"-style clause). "it doesnt mix and match with the enum
     * style," confirmed directly -- once either shape is detected, only
     * that shape's own grammar is accepted for the rest of this
     * decorator's own parens.
     */
    private Token.Decorator parseLockMatchQualifier(List<Token> t, int i, Token nameTok, Token lineTok) {
        i = skipWs(t, i + 1); // past "match"
        if (i >= t.size() || t.get(i).type != TokenType.VARREF && t.get(i).type != TokenType.KEYWORD) {
            throw new CompilerException("parse", lineTok.file, lineTok.line,
                    "'@lock(match ...)' expects 'self' or a parameter name after 'match'");
        }
        if (t.get(i).text.equals("self")) {
            return parseLockEnumQualifier(t, i, lineTok);
        }
        return parseLockInQualifier(t, i, lineTok);
    }

    private Token.Decorator parseLockEnumQualifier(List<Token> t, int i, Token lineTok) {
        i = skipWs(t, i + 1); // past "self"
        if (i >= t.size() || t.get(i).type != TokenType.OPERATOR || !t.get(i).text.equals(".")) {
            throw new CompilerException("parse", lineTok.file, lineTok.line,
                    "'@lock(match self...)' expects '.' after 'self'");
        }
        i = skipWs(t, i + 1);
        if (i >= t.size() || t.get(i).type != TokenType.VARREF) {
            throw new CompilerException("parse", lineTok.file, lineTok.line,
                    "'@lock(match self....)' expects a field name after 'self.'");
        }
        String fieldName = t.get(i).text;
        i = skipWs(t, i + 1);
        if (i >= t.size() || t.get(i).type != TokenType.OPERATOR || !t.get(i).text.equals(":")) {
            throw new CompilerException("parse", lineTok.file, lineTok.line,
                    "'@lock(match self." + fieldName + "...)' expects ':' after the field name");
        }
        i = skipWs(t, i + 1);
        List<String> variants = new ArrayList<>();
        while (true) {
            if (i >= t.size() || t.get(i).type != TokenType.VARREF) {
                throw new CompilerException("parse", lineTok.file, lineTok.line,
                        "'@lock(match self." + fieldName + " : ...)' expects a variant name");
            }
            variants.add(t.get(i).text);
            i = skipWs(t, i + 1);
            if (i < t.size() && t.get(i).type == TokenType.OPERATOR && t.get(i).text.equals("|")) {
                i = skipWs(t, i + 1);
                continue;
            }
            break;
        }
        if (i < t.size()) {
            throw new CompilerException("parse", t.get(i).file, t.get(i).line,
                    "unexpected token '" + t.get(i).text + "' in '@lock(match ...)'");
        }
        Token.Decorator d = new Token.Decorator("lock", new ArrayList<>(), new ArrayList<>(), lineTok.line,
                lineTok.file);
        d.lockFieldName = fieldName;
        d.lockVariants = variants;
        return d;
    }

    /**
     * "match i in self.backing and i2 in self.backing," confirmed
     * directly -- one or more 'and'-chained "PARAM in self.FIELD"
     * clauses, `i` already known (by the caller) to be a bare name,
     * not "self" itself. Each clause's own operator may independently
     * be written "in" or "within" -- "I want the within operator to be
     * able to be used everywhere the in can... in a @lock(match)
     * decorator," confirmed directly -- recorded per-clause in the
     * parallel `operators` list (`Token.Decorator.lockInOperators`) so
     * later validation can apply the operator actually written, not
     * assume "in" unconditionally.
     */
    private Token.Decorator parseLockInQualifier(List<Token> t, int i, Token lineTok) {
        List<String> params = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        List<Boolean> someWrapped = new ArrayList<>();
        while (true) {
            if (i >= t.size() || t.get(i).type != TokenType.VARREF) {
                throw new CompilerException("parse", lineTok.file, lineTok.line,
                        "'@lock(match ...)' expects a parameter name");
            }
            // "@lock(match Some(i) in self.x)," confirmed directly --
            // the same index-bounds-plus-element-alive sugar the
            // bare-match/for-match forms already support, extended to
            // this qualifier: "Some(" immediately here means the real
            // parameter name is wrapped one token deeper, not that a
            // parameter is literally named "Some".
            boolean wrapped = false;
            String paramName;
            if (t.get(i).text.equals("Some")) {
                int probe = skipWs(t, i + 1);
                if (probe < t.size() && t.get(probe).type == TokenType.DELINEATOR && t.get(probe).text.equals("(")) {
                    // "nest()" already collapsed the parenthesized group
                    // into this "(" token's own `.childs` (its own
                    // matching ")" is discarded entirely, per spec) --
                    // so the real inner content is read from there, not
                    // by continuing to walk the outer, flat `t` list.
                    wrapped = true;
                    List<Token> inner = t.get(probe).childs;
                    int ii = skipWs(inner, 0);
                    if (ii >= inner.size() || inner.get(ii).type != TokenType.VARREF) {
                        throw new CompilerException("parse", lineTok.file, lineTok.line,
                                "'@lock(match Some(...)...)' expects a bare parameter name inside 'Some(...)'");
                    }
                    paramName = inner.get(ii).text;
                    ii = skipWs(inner, ii + 1);
                    if (ii < inner.size()) {
                        throw new CompilerException("parse", inner.get(ii).file, inner.get(ii).line,
                                "'@lock(match Some(" + paramName + " ...)...)' expects only a bare "
                                        + "parameter name inside 'Some(...)'");
                    }
                    i = skipWs(t, probe + 1);
                } else {
                    paramName = t.get(i).text;
                    i = skipWs(t, i + 1);
                }
            } else {
                paramName = t.get(i).text;
                i = skipWs(t, i + 1);
            }
            if (i >= t.size() || t.get(i).type != TokenType.OPERATOR
                    || !(t.get(i).text.equals("in") || t.get(i).text.equals("within")
                            || t.get(i).text.equals("into"))) {
                throw new CompilerException("parse", lineTok.file, lineTok.line,
                        "'@lock(match " + paramName + " ...)' expects 'in', 'within', or 'into' after '"
                                + paramName + "'");
            }
            String operatorText = t.get(i).text;
            i = skipWs(t, i + 1);
            if (i >= t.size() || !(t.get(i).type == TokenType.VARREF || t.get(i).type == TokenType.KEYWORD)
                    || !t.get(i).text.equals("self")) {
                throw new CompilerException("parse", lineTok.file, lineTok.line,
                        "'@lock(match " + paramName + " " + operatorText + " ...)' expects 'self' after '"
                                + operatorText + "'");
            }
            i = skipWs(t, i + 1);
            if (i >= t.size() || t.get(i).type != TokenType.OPERATOR || !t.get(i).text.equals(".")) {
                throw new CompilerException("parse", lineTok.file, lineTok.line,
                        "'@lock(match " + paramName + " " + operatorText + " self...)' expects '.' after "
                                + "'self'");
            }
            i = skipWs(t, i + 1);
            if (i >= t.size() || t.get(i).type != TokenType.VARREF) {
                throw new CompilerException("parse", lineTok.file, lineTok.line,
                        "'@lock(match " + paramName + " " + operatorText + " self....)' expects a field "
                                + "name after 'self.'");
            }
            params.add(paramName);
            fields.add(t.get(i).text);
            operators.add(operatorText);
            someWrapped.add(wrapped);
            i = skipWs(t, i + 1);
            if (i < t.size() && t.get(i).type == TokenType.OPERATOR && t.get(i).text.equals("and")) {
                i = skipWs(t, i + 1);
                continue;
            }
            break;
        }
        if (i < t.size()) {
            throw new CompilerException("parse", t.get(i).file, t.get(i).line,
                    "unexpected token '" + t.get(i).text + "' in '@lock(match ...)'");
        }
        Token.Decorator d = new Token.Decorator("lock", new ArrayList<>(), new ArrayList<>(), lineTok.line,
                lineTok.file);
        d.lockInParams = params;
        d.lockInFields = fields;
        d.lockInSomeWrapped = someWrapped;
        d.lockInOperators = operators;
        return d;
    }

    private int skipWs(List<Token> t, int i) {
        while (i < t.size() && t.get(i).type == TokenType.WHITESPACE) {
            i++;
        }
        return i;
    }

    /** A decorator name/argument is just a bare word following '@' (or, for an argument, sitting inside its parens) -- accepted regardless of whether that same word also happens to be a reserved keyword elsewhere in the language (VARREF or KEYWORD token type), since decorator names live in a completely separate namespace from ordinary code. Found and fixed directly: adding "par" as a new statement-level keyword broke the pre-existing "@par" loop decorator and "@dont(par)" decorator argument, both of which only ever expected a plain VARREF at this position. */
    private boolean isDecoratorWord(Token t) {
        return t.type == TokenType.VARREF || t.type == TokenType.KEYWORD;
    }

    // ---- 3. let merge -------------------------------------------------

    /** let x = ... : "let" absorbs the variable name token into its own childs. */
    private void mergeLetInLine(Token lineTok) {
        List<Token> tokens = lineTok.childs;
        int letIdx = firstNonWhitespaceIndex(tokens);
        if (letIdx < 0 || tokens.get(letIdx).type != TokenType.KEYWORD
                || !tokens.get(letIdx).text.equals("let")) {
            return;
        }
        int j = letIdx + 1;
        while (j < tokens.size() && tokens.get(j).type == TokenType.WHITESPACE) {
            j++;
        }
        Token letTok = tokens.get(letIdx);
        boolean isStatic = false;
        if (j < tokens.size() && tokens.get(j).type == TokenType.MODIFIER && tokens.get(j).text.equals("static")) {
            isStatic = true;
            j++;
            while (j < tokens.size() && tokens.get(j).type == TokenType.WHITESPACE) {
                j++;
            }
        }
        // "let const"/"let static const" -- confirmed directly, always
        // right after 'static' (if present) and always before the
        // ":<FullType>" bound just below -- "const can go next to let
        // for variables, before any <> type setting," confirmed
        // directly. 'const' stays a KEYWORD token everywhere (never
        // retyped to MODIFIER), since the pre-existing, unrelated
        // top-level "const NAME EXPR" declaration already owns that
        // same keyword text -- the two are disambiguated purely by
        // position (a statement's own first token vs. nested here,
        // right after 'let'/'static'), the same way 'raw'/'ref'/'owns'/
        // 'static' already coexist as both statement- and type-
        // annotation-level words without a second keyword needed.
        boolean isConst = false;
        if (j < tokens.size() && tokens.get(j).type == TokenType.KEYWORD && tokens.get(j).text.equals("const")) {
            isConst = true;
            j++;
            while (j < tokens.size() && tokens.get(j).type == TokenType.WHITESPACE) {
                j++;
            }
        }
        // "let:<FullType> name = expr" / "let static:<FullType> name =
        // expr" -- confirmed directly. Runs before removeWhitespaceInLines,
        // so whitespace is still skipped manually here, matching the
        // 'static' handling just above.
        int boundStart = -1;
        int boundEnd = -1;
        if (j < tokens.size() && tokens.get(j).type == TokenType.OPERATOR && tokens.get(j).text.equals(":<")) {
            int depth = 1;
            int k = j + 1;
            for (; k < tokens.size(); k++) {
                Token t = tokens.get(k);
                if (t.type == TokenType.OPERATOR && (t.text.equals("<") || t.text.equals(":<"))) {
                    depth++;
                } else if (t.type == TokenType.OPERATOR && t.text.equals(">")) {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            if (k >= tokens.size()) {
                throw new CompilerException("parse", tokens.get(j).file, tokens.get(j).line,
                        "unterminated type bound, expected a matching '>'");
            }
            boundStart = j;
            boundEnd = k;
            j = k + 1;
            while (j < tokens.size() && tokens.get(j).type == TokenType.WHITESPACE) {
                j++;
            }
        }
        if (j >= tokens.size()) {
            throw new CompilerException("parse", tokens.get(letIdx).file, tokens.get(letIdx).line,
                    "'let' with no following variable name");
        }
        Token nameTok = tokens.get(j);
        letTok.childs.add(nameTok);
        letTok.isStatic = isStatic;
        letTok.isConst = isConst;
        if (boundStart >= 0) {
            List<Token> rawBound = tokens.subList(boundStart + 1, boundEnd);
            List<Token> cleanBound = new ArrayList<>();
            for (Token t : rawBound) {
                if (t.type != TokenType.WHITESPACE) {
                    cleanBound.add(t);
                }
            }
            letTok.typeBound = cleanBound;
        }
        for (int k = j; k >= letIdx + 1; k--) {
            tokens.remove(k);
        }
    }

    private int firstNonWhitespaceIndex(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).type != TokenType.WHITESPACE) {
                return i;
            }
        }
        return -1;
    }

    // ---- 4. import collapse -------------------------------------------

    /** import "path" : whole line collapses to a single token, comments preserved. */
    private void collapseImportInLine(Token lineTok) {
        List<Token> tokens = lineTok.childs;
        int importIdx = firstNonWhitespaceIndex(tokens);
        if (importIdx < 0 || tokens.get(importIdx).type != TokenType.KEYWORD
                || !tokens.get(importIdx).text.equals("import")) {
            return;
        }
        Token importTok = tokens.get(importIdx);
        Token pathTok = null;
        for (Token t : tokens) {
            if (t.type == TokenType.STRING) {
                pathTok = t;
            }
            if (t != importTok) {
                importTok.pinnedComments.addAll(t.pinnedComments);
            }
        }
        if (pathTok == null) {
            throw new CompilerException("parse", importTok.file, importTok.line,
                    "'import' with no string path");
        }
        importTok.childs.add(pathTok);
        lineTok.childs = new ArrayList<>();
        lineTok.childs.add(importTok);
    }

    // ---- 5. whitespace removal -----------------------------------------

    private void removeWhitespaceInLines(List<Token> lines) {
        for (Token lineTok : lines) {
            removeWhitespaceInFlat(lineTok.childs);
        }
    }

    private void removeWhitespaceInFlat(List<Token> flat) {
        for (int i = flat.size() - 1; i >= 0; i--) {
            if (flat.get(i).type == TokenType.WHITESPACE) {
                flat.remove(i);
            }
        }
        for (Token t : flat) {
            if (t.type != TokenType.DELINEATOR) {
                continue;
            }
            if (t.text.equals("{")) {
                removeWhitespaceInLines(t.childs);
            } else {
                // '(' and '[' bodies are a single continuous expression,
                // never split into separate lines the way '{' bodies
                // are (see splitIntoLines: "'(' and '[' childs stay
                // flat") -- so any TERMINATOR left over from an internal
                // newline carries no meaning here at all and must be
                // stripped exactly like whitespace, or a multi-line
                // call/array-literal/etc chokes downstream expecting an
                // operator where a leftover TERMINATOR sits instead.
                removeTerminatorsInFlat(t.childs);
                removeWhitespaceInFlat(t.childs);
            }
        }
    }

    private void removeTerminatorsInFlat(List<Token> flat) {
        for (int i = flat.size() - 1; i >= 0; i--) {
            if (flat.get(i).type == TokenType.TERMINATOR) {
                flat.remove(i);
            }
        }
    }

    // ---- 5.1 "then" pseudo-operator merging ("&&then"/"||then") -----------

    /**
     * "Ok I need lazy chain evaluation, achieved with the then pseudo
     * operator... So early on compilation && then or !! then just
     * becomes &&then and ||then are just operators... the whitespace
     * isnt even necessary for the user: &&then and ||then should work
     * just as well as && then and || then," confirmed directly. Unlike
     * the "?" pseudo-operator (pure Parser-level sugar, fully expanded
     * into ordinary `try`/`catch` tokens before this method even runs --
     * see `gatherKeywordBlocks`'s own "?EXPR" handling), "&&then"/
     * "||then" are genuine binary OPERATOR tokens that survive all the
     * way through RpnConverter/TreeBuilder/TypeChecker as first-class
     * nodes, and are only ever given their real short-circuit
     * instruction shape at the very end, by BytecodeEmitter -- "they are
     * just operators until they are parsed out to their instructions in
     * the hob," confirmed directly. So this pass doesn't expand or
     * desugar anything; it only *unifies spelling*: "&&then" (glued) and
     * "&& then" (spaced) already tokenize identically once whitespace is
     * stripped -- both are an OPERATOR "&&"/"||" immediately followed by
     * a bare VARREF "then" -- so the one thing needed is merging that
     * two-token sequence into a single synthetic OPERATOR token with text
     * "&&then"/"||then", giving RpnConverter/TreeBuilder exactly one
     * token to deal with instead of two.
     *
     * "then" is deliberately NOT reserved as a global keyword -- reusing
     * the exact same structural, non-reserved-word precedent
     * `isLockStatementStart` already established for "lock" in this
     * codebase: a bare VARREF "then" remains completely legal as an
     * ordinary variable/function name everywhere else; only the specific
     * two-token adjacency "&&"/"||" immediately followed by VARREF
     * "then" is ever merged. This does mean "a && then" where `then`
     * happens to be a real boolean variable the user intended as the
     * eager right-hand operand (with nothing following it) is merged
     * into a valueless "&&then" operator missing its own right operand,
     * and fails downstream (RpnConverter/TreeBuilder) with a generic
     * "missing operand" error rather than something naming this
     * ambiguity directly -- an accepted, documented trade-off, not a
     * bug: the identical shape of trade-off "lock" itself already
     * accepts for a variable actually named "lock".
     *
     * Runs once, right after `removeWhitespaceInLines` and before
     * `insertCallLookupInLines` specifically -- ordering matters:
     * running after `insertCallLookupInLines` would let "&&then(x)" (no
     * space before the parenthesized right operand) first get "then("
     * misread as a CALL to a function literally named "then", corrupting
     * the merge target before this pass ever saw the plain adjacent
     * VARREF it needs.
     */
    private void mergeThenOperators(List<Token> lines) {
        for (Token lineTok : lines) {
            mergeThenInFlat(lineTok.childs);
        }
    }

    private void mergeThenInFlat(List<Token> flat) {
        for (int i = 0; i < flat.size() - 1; i++) {
            Token t = flat.get(i);
            Token next = flat.get(i + 1);
            if (t.type == TokenType.OPERATOR && (t.text.equals("&&") || t.text.equals("||"))
                    && next.type == TokenType.VARREF && next.text.equals("then")) {
                t.text = t.text + "then";
                flat.remove(i + 1);
            }
        }
        for (Token t : flat) {
            if (t.type != TokenType.DELINEATOR) {
                continue;
            }
            if (t.text.equals("{")) {
                mergeThenOperators(t.childs);
            } else {
                mergeThenInFlat(t.childs);
            }
        }
    }

    // ---- 5.5 generic-instantiation collapsing (":<...>") ------------------

    private void collapseGenericInstantiationsInLines(List<Token> lines) {
        for (Token lineTok : lines) {
            collapseGenericInstantiationsInFlat(lineTok.childs);
        }
    }

    /**
     * Finds "VARREF ':<' ... '>'" spans in a flat, expression-shaped token
     * list and collapses each into a single VARREF-typed token carrying
     * `.genericArgs` (a List<List<Token>>, one raw token group per
     * comma-separated type argument). The collapsed token keeps type
     * VARREF and sits exactly where the base name was, so every later
     * stage that only knows how to look for a plain VARREF (RpnConverter's
     * atomic-operand handling, insertCallLookupInFlat's "VARREF followed by
     * '('" check, and whatever struct-literal-brace detection TreeBuilder
     * already does for "VARREF followed by '{'") keeps working completely
     * unmodified.
     *
     * Skips struct/enum/interface bodies and a func/impl's own header
     * entirely, jumping straight to a func/impl's trailing '{' body --
     * identical shape to insertCallLookupInFlat, and for the identical
     * reason: those positions are declared-type contexts, never expression
     * contexts, so ':<' can never legally appear there (generic type
     * annotations in those positions use the bare, colon-less form, parsed
     * separately by the gather* methods below and by GenericsExpander).
     */
    private void collapseGenericInstantiationsInFlat(List<Token> flat) {
        if (flat.isEmpty()) {
            return;
        }
        Token first = flat.get(0);
        if (first.type == TokenType.KEYWORD
                && (first.text.equals("struct") || first.text.equals("enum") || first.text.equals("interface") || first.text.equals("type") || first.text.equals("extern") || first.text.equals("export") || first.text.equals("ASM"))) {
            return;
        }
        if (first.type == TokenType.KEYWORD
                && (first.text.equals("func") || first.text.equals("impl") || first.text.equals("library"))) {
            Token last = flat.get(flat.size() - 1);
            if (last.type == TokenType.DELINEATOR && last.text.equals("{")) {
                collapseGenericInstantiationsInLines(last.childs);
            }
            return;
        }

        for (int i = 0; i < flat.size(); i++) {
            Token t = flat.get(i);
            if (t.type == TokenType.VARREF && i + 1 < flat.size()) {
                Token next = flat.get(i + 1);
                if (next.type == TokenType.OPERATOR && next.text.equals(":<")) {
                    collapseOneGenericSpan(flat, i);
                }
            }
        }
        for (Token t : flat) {
            if (t.type != TokenType.DELINEATOR) {
                continue;
            }
            if (t.text.equals("{")) {
                collapseGenericInstantiationsInLines(t.childs);
            } else {
                collapseGenericInstantiationsInFlat(t.childs);
            }
        }
    }

    /**
     * flat.get(i) is the base-name VARREF, flat.get(i+1) is the merged
     * ":<" token. Scans forward tracking nesting depth (each further ':<'
     * seen while scanning opens one more level, since a nested generic
     * argument -- e.g. the inner List:<u64> in List:<List:<u64>>{...} --
     * is itself still lexically "in a function body" and therefore also
     * required to use the colon form, so a bare '<' can never legitimately
     * appear inside this span at all), splits the raw argument tokens on
     * top-level commas, recursively collapses any further ':<...>' spans
     * within each argument group, and replaces the whole matched span in
     * `flat` (in place) with one new collapsed VARREF token.
     */
    private void collapseOneGenericSpan(List<Token> flat, int i) {
        Token baseTok = flat.get(i);
        int depth = 1;
        int j = i + 2; // first token after ':<'
        int closeIdx = -1;
        for (; j < flat.size(); j++) {
            Token t = flat.get(j);
            if (t.type == TokenType.OPERATOR && t.text.equals(":<")) {
                depth++;
            } else if (t.type == TokenType.OPERATOR && t.text.equals(">")) {
                depth--;
                if (depth == 0) {
                    closeIdx = j;
                    break;
                }
            }
        }
        if (closeIdx < 0) {
            throw new CompilerException("parse", baseTok.file, baseTok.line,
                    "unterminated generic instantiation, expected a matching '>' for '"
                            + baseTok.text + ":<'");
        }
        List<Token> argTokens = new ArrayList<>(flat.subList(i + 2, closeIdx));
        List<List<Token>> argGroups = splitTopLevelCommaGroups(argTokens);
        for (List<Token> group : argGroups) {
            collapseGenericInstantiationsInFlat(group);
        }

        Token collapsed = new Token(TokenType.VARREF, baseTok.text, baseTok.line, baseTok.file);
        collapsed.genericArgs = argGroups;

        for (int k = closeIdx; k >= i; k--) {
            flat.remove(k);
        }
        flat.add(i, collapsed);
    }

    /**
     * Splits a raw token list on top-level ',' only -- tracking ':<'/'>'
     * depth so a comma belonging to a *nested* generic's own argument list
     * isn't mistaken for a separator of the outer list.
     */
    private List<List<Token>> splitTopLevelCommaGroups(List<Token> tokens) {
        List<List<Token>> groups = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (Token t : tokens) {
            if (t.type == TokenType.OPERATOR && t.text.equals(":<")) {
                depth++;
            } else if (t.type == TokenType.OPERATOR && t.text.equals(">")) {
                depth--;
            }
            if (depth == 0 && t.type == TokenType.OPERATOR && t.text.equals(",")) {
                groups.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(t);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        if (groups.isEmpty()) {
            throw new CompilerException("parse", tokens.isEmpty() ? null : tokens.get(0).file,
                    tokens.isEmpty() ? 0 : tokens.get(0).line,
                    "generic instantiation requires at least one type argument");
        }
        return groups;
    }

    private static final class TypeParamParseResult {
        final List<String> names; // null if there was no '<' at all
        final List<String> bounds; // parallel to names; null entries mean "no bound"; whole list null if no bound anywhere
        final int nextIndex;
        TypeParamParseResult(List<String> names, List<String> bounds, int nextIndex) {
            this.names = names;
            this.bounds = bounds;
            this.nextIndex = nextIndex;
        }
    }

    /**
     * Parses an optional bare "<Name[: Bound], Name[: Bound], ...>"
     * declaration-site type-parameter list starting at tokens.get(idx). No
     * colon before the '<' itself: this is only ever called from header
     * positions (struct/func/interface/impl names), which never go through
     * RpnConverter, so a bare '<' is never ambiguous with the comparison
     * operator here -- these positions can never contain an expression at
     * all. A ':' immediately after a type-parameter name, however, is a
     * genuine, new grammar addition here -- "func f<T: SomeInterface>(...)",
     * the chosen syntax for a generic bound/constraint (see "Generics" in
     * CLAUDE.md). The bound name itself is a single bare VARREF -- no
     * "SomeInterface<X>" generic-interface bound, no '|'-chained multiple
     * bounds -- only ever asked for, and only ever needed, as one interface
     * name per type parameter.
     */
    private TypeParamParseResult parseOptionalTypeParams(List<Token> tokens, int idx) {
        if (idx >= tokens.size() || tokens.get(idx).type != TokenType.OPERATOR
                || !tokens.get(idx).text.equals("<")) {
            return new TypeParamParseResult(null, null, idx);
        }
        List<String> names = new ArrayList<>();
        List<String> bounds = new ArrayList<>();
        boolean anyBound = false;
        int j = idx + 1;
        boolean expectName = true;
        while (true) {
            if (j >= tokens.size()) {
                throw new CompilerException("parse", tokens.get(idx).file, tokens.get(idx).line,
                        "unterminated type-parameter list, expected a matching '>'");
            }
            Token t = tokens.get(j);
            if (expectName) {
                if (t.type != TokenType.VARREF) {
                    throw new CompilerException("parse", t.file, t.line,
                            "expected a type-parameter name, found '" + t.text + "'");
                }
                names.add(t.text);
                j++;
                expectName = false;
                // optional ": BoundInterfaceName" immediately after the name
                if (j < tokens.size() && tokens.get(j).type == TokenType.OPERATOR && tokens.get(j).text.equals(":")) {
                    j++;
                    if (j >= tokens.size() || tokens.get(j).type != TokenType.VARREF) {
                        throw new CompilerException("parse", t.file, t.line,
                                "expected an interface name after ':' in type-parameter bound");
                    }
                    bounds.add(tokens.get(j).text);
                    anyBound = true;
                    j++;
                } else {
                    bounds.add(null);
                }
            } else if (t.type == TokenType.OPERATOR && t.text.equals(",")) {
                j++;
                expectName = true;
            } else if (t.type == TokenType.OPERATOR && t.text.equals(">")) {
                j++;
                break;
            } else {
                throw new CompilerException("parse", t.file, t.line,
                        "expected ',' or '>' in type-parameter list, found '" + t.text + "'");
            }
        }
        if (names.isEmpty()) {
            throw new CompilerException("parse", tokens.get(idx).file, tokens.get(idx).line,
                    "type-parameter list must name at least one parameter");
        }
        return new TypeParamParseResult(names, anyBound ? bounds : null, j);
    }

    private static final class TypeArgParseResult {
        final List<List<Token>> groups; // null if there was no '<' at all
        final int nextIndex;
        TypeArgParseResult(List<List<Token>> groups, int nextIndex) {
            this.groups = groups;
            this.nextIndex = nextIndex;
        }
    }

    /**
     * Parses an optional bare "<TypeExpr, TypeExpr, ...>" *usage* (not
     * declaration) of type arguments right after a name in a header
     * position -- e.g. the "<T>" in "impl<T> List<T>{...}"'s own
     * "List<T>" half. Same bracket-matching shape as the ':<'-collapsing
     * helpers above, minus the colon (headers never go through
     * RpnConverter, so there's no ambiguity to guard against here either).
     */
    private TypeArgParseResult parseOptionalTypeArgsRaw(List<Token> tokens, int idx) {
        if (idx >= tokens.size() || tokens.get(idx).type != TokenType.OPERATOR
                || !tokens.get(idx).text.equals("<")) {
            return new TypeArgParseResult(null, idx);
        }
        int depth = 1;
        int j = idx + 1;
        int closeIdx = -1;
        for (; j < tokens.size(); j++) {
            Token t = tokens.get(j);
            if (t.type == TokenType.OPERATOR && t.text.equals("<")) {
                depth++;
            } else if (t.type == TokenType.OPERATOR && t.text.equals(">")) {
                depth--;
                if (depth == 0) {
                    closeIdx = j;
                    break;
                }
            }
        }
        if (closeIdx < 0) {
            throw new CompilerException("parse", tokens.get(idx).file, tokens.get(idx).line,
                    "unterminated type-argument list, expected a matching '>'");
        }
        List<Token> raw = new ArrayList<>(tokens.subList(idx + 1, closeIdx));
        List<List<Token>> groups = splitTopLevelCommaGroupsBare(raw);
        return new TypeArgParseResult(groups, closeIdx + 1);
    }

    /** Same as splitTopLevelCommaGroups but tracks bare '<'/'>' depth (header context, no ':<'). */
    private List<List<Token>> splitTopLevelCommaGroupsBare(List<Token> tokens) {
        List<List<Token>> groups = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (Token t : tokens) {
            if (t.type == TokenType.OPERATOR && t.text.equals("<")) {
                depth++;
            } else if (t.type == TokenType.OPERATOR && t.text.equals(">")) {
                depth--;
            }
            if (depth == 0 && t.type == TokenType.OPERATOR && t.text.equals(",")) {
                groups.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(t);
        }
        groups.add(current);
        return groups;
    }

    // ---- 6. CALL / LOOKUP insertion -------------------------------------

    private void insertCallLookupInLines(List<Token> lines) {
        for (Token lineTok : lines) {
            insertCallLookupInFlat(lineTok.childs);
        }
    }

    /**
     * "VARREF followed by '['" is genuinely ambiguous in raw token
     * shape alone -- it's indistinguishable between a real subscript
     * ("x[5]" in an expression) and an array-length type annotation
     * ("field: u64[50]" in a struct member, func param, or return type).
     * The two can only be told apart by context: struct/enum bodies and
     * a func's own header (name, param list, return-type tokens) are
     * never expression-shaped anywhere within them -- they're declared
     * types, parsed directly from raw tokens by
     * TypeChecker.resolveTypeAnnotation, never by RpnConverter/
     * TreeBuilder -- so this deliberately never scans them at all, and
     * for a func line, jumps straight to its trailing '{' body (which
     * *does* need full CALL/LOOKUP processing) rather than trying to
     * guard each problematic spot (param list contents, return-type
     * tokens) individually.
     */
    private void insertCallLookupInFlat(List<Token> flat) {
        if (flat.isEmpty()) {
            return;
        }
        Token first = flat.get(0);
        if (first.type == TokenType.KEYWORD
                && (first.text.equals("struct") || first.text.equals("enum") || first.text.equals("interface") || first.text.equals("type") || first.text.equals("extern") || first.text.equals("export") || first.text.equals("ASM"))) {
            return;
        }
        if (first.type == TokenType.KEYWORD
                && (first.text.equals("func") || first.text.equals("impl") || first.text.equals("library"))) {
            Token last = flat.get(flat.size() - 1);
            if (last.type == TokenType.DELINEATOR && last.text.equals("{")) {
                insertCallLookupInLines(last.childs);
            }
            return;
        }
        // "static func NAME(...) ReturnType{...}" inside an impl/
        // interface block -- the exact same "never expression-shaped"
        // header as a bare "func" declaration just above, just with a
        // "static" modifier in front of the keyword; without this,
        // "origin(" in "static func origin() mut u64{...}" gets
        // mistaken for an ordinary call expression and a spurious
        // "CALL" marker gets inserted into the param-list header,
        // which then breaks gatherFunc's own index-based parsing of
        // it downstream.
        if (first.type == TokenType.MODIFIER && first.text.equals("static") && flat.size() > 1
                && flat.get(1).type == TokenType.KEYWORD && flat.get(1).text.equals("func")) {
            Token last = flat.get(flat.size() - 1);
            if (last.type == TokenType.DELINEATOR && last.text.equals("{")) {
                insertCallLookupInLines(last.childs);
            }
            return;
        }

        for (int i = 0; i < flat.size(); i++) {
            Token t = flat.get(i);
            // "const NAME EXPR" -- NAME itself is never a call target,
            // regardless of what EXPR happens to start with ("const X
            // (1+2)" is "X := (1+2)", not "call X with argument
            // (1+2)"); skipped here rather than restructuring the loop,
            // since everything else about EXPR still needs completely
            // ordinary CALL/LOOKUP processing (it's a real expression,
            // just one restricted elsewhere -- checkNoRuntimeConstructs
            // -- to compile-time-only constructs).
            if (i == 1 && flat.get(0).type == TokenType.KEYWORD && flat.get(0).text.equals("const")
                    && t.type == TokenType.VARREF) {
                continue;
            }
            // "sleep(...)" -- a real KEYWORD (never a VARREF), but still
            // needs the identical "CALL" marker an ordinary VARREF call
            // target gets, so it reaches TypeChecker.checkCall exactly
            // the same shape as any other call site (checkCall's own
            // dedicated "op.left.type == KEYWORD && op.left.text.equals
            // ('sleep')" branch, right at its very top, then resolves it
            // to whichever function actually carries "@sleep" -- see
            // checkSleepCall's own doc comment). "yield"/"par"/"await"
            // don't need this: "yield"/"join" never took real arguments
            // at all (RpnConverter's own dedicated bare-keyword handling
            // covers them), and "par"/"await" are prefix operators
            // wrapping a *separate* call expression, never a call
            // target themselves.
            boolean isSleepKeyword = t.type == TokenType.KEYWORD && t.text.equals("sleep");
            if (isSleepKeyword && i + 1 < flat.size() && flat.get(i + 1).type == TokenType.DELINEATOR
                    && flat.get(i + 1).text.equals("(")) {
                flat.add(i + 1, new Token(TokenType.OPERATOR, "CALL", t.line, t.file));
                i++;
                continue;
            }
            // A `[...]` DELINEATOR group is itself a legal LOOKUP target
            // too, not just a bare VARREF/STRING -- needed for chained
            // indexing ("grid[1][1]"): once the first "[1]" is parsed,
            // it's represented as one DELINEATOR token (text "[",
            // carrying its own index expression as `childs`) sitting
            // directly in this same flat list, immediately followed by
            // the second "[1]"'s own DELINEATOR token, with no VARREF or
            // STRING token anywhere in between for the original condition
            // below to ever match against. Without this, a second (or
            // later) "[" in a chain is never preceded by a LOOKUP marker
            // at all, and `RpnConverter` fails outright with "unexpected
            // token '[', expected an operator" -- confirmed directly to
            // be a pure parse-error gap, not a type-check or codegen one:
            // `TypeChecker.checkLookup` already resolves `op.left`
            // through the fully generic `resolveExprType`, which already
            // recurses into a nested "LOOKUP" node exactly like any other
            // operator (no dedicated chained-lookup logic needed there at
            // all), and the read side of a >1-word array element is
            // already confirmed correct by this project's own 2D-array
            // addressing fix (`arrays_2d_row_index_wrong_value_full_cg_test`,
            // documented in the sibling `caspien-codegen` project's own
            // CLAUDE.md) -- so a previously-required workaround ("bind
            // the intermediate row to a `let` first") was only ever
            // covering for this one missing marker-insertion case, not a
            // deeper gap.
            boolean tIsLookupTarget = t.type == TokenType.VARREF || t.type == TokenType.STRING
                    || (t.type == TokenType.DELINEATOR && t.text.equals("["));
            if (tIsLookupTarget && i + 1 < flat.size()) {
                Token next = flat.get(i + 1);
                if (t.type == TokenType.VARREF && next.type == TokenType.DELINEATOR && next.text.equals("(")) {
                    flat.add(i + 1, new Token(TokenType.OPERATOR, "CALL", t.line, t.file));
                    i++;
                } else if (next.type == TokenType.DELINEATOR && next.text.equals("[")) {
                    // A string *literal* directly followed by '[' is now
                    // a legal LOOKUP target too (not just a variable) --
                    // needed so a literal string's compile-time-known
                    // length can actually be indexed at all under the
                    // new bounds-checking default (see TypeChecker.
                    // checkLookup): only a direct string literal, never
                    // a variable holding one, has a length the compiler
                    // can confirm without a 'match' statement.
                    flat.add(i + 1, new Token(TokenType.OPERATOR, "LOOKUP", t.line, t.file));
                    i++;
                }
            }
        }
        for (Token t : flat) {
            if (t.type == TokenType.TEMPLATE_STRING) {
                // Same reasoning as removeWhitespaceFromExprGroup --
                // this pass only ever recurses into a plain DELINEATOR
                // on its own, never into a TEMPLATE_STRING's own nested
                // "${...}" segments. Needed so a call inside one (e.g.
                // "${f()}") gets a real CALL marker inserted and fails
                // with a clear, dedicated "a compile-time constant
                // expression cannot call a function" from
                // evaluateConstantOperator, rather than a confusing raw
                // parse error from toRpn seeing "VARREF (" with no
                // marker between them.
                for (Token seg : t.childs) {
                    if (seg.type == TokenType.DELINEATOR && seg.text.equals("(")) {
                        insertCallLookupInFlat(seg.childs);
                    }
                }
                continue;
            }
            if (t.type != TokenType.DELINEATOR) {
                continue;
            }
            if (t.text.equals("{")) {
                insertCallLookupInLines(t.childs);
            } else {
                insertCallLookupInFlat(t.childs);
            }
        }
    }

    // ---- 7. keyword-block gathering ---------------------------------------

    /**
     * Collapses each recognized "keyword [tokens] { block }" shape into a
     * single token, recursively, bottom-up (a block's own contents are
     * fully gathered before the construct that owns that block is built).
     *
     * Token shape produced per construct (all fields not mentioned are
     * left at their default/empty state):
     *
     *   if / elseif / else:
     *     .sub    = the raw (not yet RPN-converted) condition tokens,
     *               empty for 'else'
     *     .childs = the block's body, as a list of LINE tokens
     *     .right  = the next branch in the chain (elseif/else), or null
     *               if this is the last branch. NOTE: this reuses the
     *               'right' field, which elsewhere means "right operand
     *               of a binary RPN node" -- if/elseif/else tokens never
     *               participate in an expression tree, so there's no
     *               collision, but it's still an overload worth calling
     *               out explicitly. An if/elseif/else chain is
     *               represented purely by its head 'if' token; the
     *               chained branches never appear as separate top-level
     *               statements.
     *
     *   loop:
     *     .childs = the block's body, as a list of LINE tokens
     *     (.sub stays empty -- loop takes no condition)
     *
     *   func:
     *     .sub    = [ nameToken, paramsToken, ...returnTypeTokens ]
     *               where paramsToken is the original '(' DELINEATOR
     *               token (its own .childs holds the flat, comma
     *               separated parameter list, untouched -- parameter
     *               parsing happens at type-check time), and
     *               returnTypeTokens is whatever token(s) appeared
     *               between ')' and '{' (empty means void)
     *     .childs = the function body, as a list of LINE tokens
     *
     *   struct (root-level only):
     *     .sub    = [ nameToken ]
     *     .childs = the member declarations, as a list of LINE tokens
     *               (each member line is "name : type" tokens, untouched
     *               -- interpreted at type-check time)
     *
     *   enum (root-level only):
     *     .sub    = [ nameToken ]
     *     .childs = the flat list of variant name VARREF tokens (comma
     *               operators between them are discarded here, since
     *               they carry no meaning once the variants are a list)
     *
     * Lines whose first token isn't one of these keywords (return, let,
     * import, or any bare expression statement) pass through unchanged;
     * their remaining flat expression tokens are handled by the RPN
     * conversion stage, not this one.
     */
    /**
     * Public entry point for TypeChecker to run this parser's own
     * structural "gather" pass over a deep-cloned, still-fully-raw list
     * of statement lines -- used only by "impl default match @lock"'s
     * own per-use-site template instantiation (see TypeChecker.
     * DefaultLockMatchPolicy): that declaration deliberately keeps its
     * own body completely raw (never gathered) at declaration time,
     * since it's a template, not a real statement -- so a fresh,
     * deep-cloned copy of it is gathered here, once per real
     * "CLOSED:default"/"CLOSED:default(args)" use site, exactly the way
     * an ordinary function body is gathered once at its own declaration
     * (never at root, so `isRoot` is always false here).
     */
    public List<Token> gatherStatementLines(List<Token> lines) {
        return gatherKeywordBlocks(lines, false);
    }

    private List<Token> gatherKeywordBlocks(List<Token> lines, boolean isRoot) {
        List<Token> result = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            Token line = lines.get(i);
            List<Token> tokens = line.childs;
            Token first = tokens.get(0);

            // "?catch(e){ ... }" -- a standalone declaration statement,
            // never embedded mid-expression (unlike "?EXPR" just below):
            // "?" must be this line's own first token, immediately
            // followed by the "catch" KEYWORD. Checked before anything
            // else in this loop, since "catch" alone would otherwise be
            // rejected a few lines down ("'catch' without a preceding
            // 'try'") the moment the ordinary "try"-scan below finds no
            // "try" on this line at all. Declares (or replaces) the
            // pseudo-operator's own currently pending catch block --
            // itself producing no code, no line of any kind added to
            // `result` -- consumed by every "?EXPR" use site encountered
            // afterward, until the next "?catch(...)" declaration
            // (anywhere in this function, not scoped to this block --
            // see `pendingOptionalCatchParamName`'s own doc comment).
            if (tokens.size() > 1 && first.type == TokenType.OPERATOR && first.text.equals("?")
                    && tokens.get(1).type == TokenType.KEYWORD && tokens.get(1).text.equals("catch")) {
                CatchClauseResult decl = gatherCatchClause(tokens, 1, "?catch(...) { ... }",
                        /* gatherBodyNow */ false);
                pendingOptionalCatchParamName = decl.paramName;
                pendingOptionalCatchRawBody = decl.body;
                i++;
                continue;
            }

            // "?EXPR" -- sugar for "try EXPR catch(e){ BLOCK }", where
            // BLOCK is whichever "?catch(...)" was most recently declared
            // (see `pendingOptionalCatchParamName`'s own doc comment).
            // Legal in exactly the same two positions the real "try EXPR
            // catch(...){...}" span is: a bare statement ("?foo();") or
            // embedded as a "let"/assignment's own right-hand side ("let
            // x = ?foo()") -- so, like that real span, found anywhere in
            // the line rather than only at `first`, and (like it) taken to
            // run all the way to the end of the line -- there is no
            // trailing "catch(...)" of its own to mark where the wrapped
            // expression ends, so the whole remainder of the line *is* the
            // wrapped expression, exactly mirroring the real span's own
            // "never has anything legally following its own closing '}'"
            // restriction from the opposite end. Only the *first* "?" in a
            // line is ever recognized this way -- a second, nested "?"
            // inside the wrapped expression's own arguments is a shape
            // this feature's own spec never exercises, deliberately left
            // unsupported (it survives into the wrapped expression's own
            // raw tokens untouched, and fails downstream with an ordinary,
            // if unhelpfully generic, parse/RPN error rather than being
            // silently mishandled).
            int qIdx = -1;
            for (int k = 0; k < tokens.size(); k++) {
                if (tokens.get(k).type == TokenType.OPERATOR && tokens.get(k).text.equals("?")) {
                    qIdx = k;
                    break;
                }
            }
            // True once the "?EXPR" branch just below has already spliced
            // a fully-formed synthetic "try" token into `tokens` in place
            // of "? ... " -- when so, the ordinary "try"-scan right after
            // this (which finds a real "try" KEYWORD *anywhere* in the
            // line) must be skipped entirely for this line: it would
            // otherwise find this very token and wrongly try to re-gather
            // it as if it were raw, unprocessed source (scanning forward
            // for a "catch" that will never come, since this token is
            // already fully built).
            boolean expandedOptionalHere = false;
            if (qIdx >= 0) {
                Token qTok = tokens.get(qIdx);
                if (pendingOptionalCatchRawBody == null) {
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
                for (Token bodyLine : pendingOptionalCatchRawBody) {
                    clonedRawBody.add(bodyLine.deepCloneRaw());
                }
                Token tryGathered = new Token(TokenType.KEYWORD, "try", qTok.line, qTok.file);
                tryGathered.sub = exprTokens;
                tryGathered.childs = gatherKeywordBlocks(clonedRawBody, false);
                tryGathered.catchParamName = pendingOptionalCatchParamName;
                tryGathered.decorators = qTok.decorators;
                tryGathered.pinnedComments = qTok.pinnedComments;
                List<Token> replaced = new ArrayList<>(tokens.subList(0, qIdx));
                replaced.add(tryGathered);
                tokens = replaced;
                line.childs = tokens;
                first = tokens.get(0);
                expandedOptionalHere = true;
                // Deliberately no `continue` here -- `tokens`/`line`/
                // `first` now carry the exact same shape a real "try EXPR
                // catch(...){...}" span's own substitution produces, so
                // this line falls through to the identical ordinary
                // per-line dispatch that span's own substitution already
                // falls through to just below (see its own comment) --
                // the "try"-scan itself is skipped via `expandedOptionalHere`,
                // everything after it runs completely unmodified.
            }

            // "try foo() catch{ ... }" -- unlike every other gathered
            // construct in this switch (if/loop/match/assume/...),
            // 'try' isn't necessarily this line's own first token: "let
            // x = try foo() catch{ ... }" is exactly as legal as a bare
            // "try foo() catch{ ... }" statement, since the whole
            // construct is itself an expression (its value being
            // whatever the wrapped call returns on ordinary completion)
            // -- so it's found by scanning the *whole* line, not by
            // dispatching on `first` the way the switch below does.
            // Found anywhere in the line, the entire "try EXPR catch {
            // BLOCK }" span collapses to one synthetic KEYWORD token
            // (mirroring "for"/"match"/"assume": the wrapped call's own
            // raw tokens go in `.sub`, to be RPN-converted/tree-built
            // later exactly like any other condition/target expression
            // is; the catch block's gathered statement lines go in
            // `.childs`, recursed into right here via this same method,
            // just like every other block-bearing construct's own
            // trailing block already is), and that one token replaces
            // the whole span in place. Everything before 'try' (e.g.
            // the merged 'let x =' prefix) is left completely alone --
            // RpnConverter/TreeBuilder need only learn to treat this one
            // new KEYWORD shape as an ordinary operand (see their own
            // "try" handling) for the rest of the line to keep working
            // completely unmodified.
            if (!expandedOptionalHere) {
                int tryIdx = -1;
                for (int k = 0; k < tokens.size(); k++) {
                    if (tokens.get(k).type == TokenType.KEYWORD && tokens.get(k).text.equals("try")) {
                        tryIdx = k;
                        break;
                    }
                }
                // "try { ... }" -- a second, unrelated construct sharing the
                // same "try" keyword: a plain lexical-scope block (no
                // wrapped expression, no "catch" at all), legal only as a
                // bare statement -- "try" must be this line's own first
                // token, immediately followed by "{". Told apart from the
                // throwing "try EXPR catch(e){ ... }" span just below by
                // this lookahead alone, checked *before* handing off to
                // `gatherTryCatchSpan` (which would otherwise scan forward
                // for a "catch" that will never come, and fail with "'try'
                // requires a matching 'catch(e) { ... }'" -- a real parse
                // error, not a silent misparse, but the wrong one for this
                // shape). Structurally identical to the "unsafe"/"safe"
                // case in the switch below (no condition, just a trailing
                // block, recursed into with the same `isRoot` the current
                // invocation has) -- the only difference is the synthesized
                // token's own `isTryBlock` flag, which is what every
                // downstream stage reads to tell the two "try" shapes apart.
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
                // "lock EXPR{...}" / "match @lock EXPR{...}" -- "lock"
                // is deliberately never a reserved KEYWORD at the lexer
                // level (unlike "atomic"), since it already collides
                // heavily with the pre-existing, ordinary "@guard"
                // method-call spelling (".lock()"/".unlock()", a real
                // function named "lock" declared via "func lock(...)",
                // an interface's own bare "lock(params) returnType;"
                // signature) -- reserving it outright broke every one
                // of those. Detected purely structurally instead, the
                // identical "no type information needed" reasoning
                // "match"'s own case-match form already relies on: a
                // bare VARREF "lock" immediately followed by anything
                // other than "(" (which would mean an ordinary call to
                // a variable/function actually named "lock"), ending in
                // a trailing "{" block.
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
                        // "an unsafe or safe block can be used in a
                        // func or at the root," confirmed directly --
                        // structurally identical to 'loop' (no
                        // condition, just a trailing block), but
                        // recurses with the *same* isRoot the current
                        // invocation has, rather than always false: at
                        // root this can contain further declarations
                        // (func/struct/etc), inside a function body it
                        // contains ordinary statements, matching
                        // whichever context it was written in. The
                        // "unsafe inside safe is a compiler error"
                        // nesting restriction is a TypeChecker concern
                        // (it needs the current safety-context stack,
                        // not just parse-time shape), not enforced here.
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
                        // "for i in 0..n{ ... }" -- structurally just
                        // like 'if': a condition (here, always an 'in'
                        // expression) followed by a trailing block whose
                        // body is gathered exactly like any other block.
                        // The condition's *meaning* is special (the
                        // left of 'in' introduces a fresh loop variable
                        // rather than referencing an existing one) --
                        // but that's a TypeChecker concern
                        // (checkForLoop), not a parsing-shape one.
                        //
                        // "for match i in x{...}" -- a real, distinct
                        // sugar ("for match i in x{...} is exactly the
                        // same as: for i in x{...} except within the for
                        // loop, it gets the same type assertion it
                        // would from: match i in x{...}," confirmed
                        // directly), detected purely structurally here:
                        // an optional "match" keyword immediately after
                        // "for" is stripped out of the condition's own
                        // raw tokens before RPN conversion ever sees it
                        // (so `.sub` ends up holding exactly the same
                        // "i in x" shape an ordinary "for" already has,
                        // and RpnConverter/TreeBuilder need zero new
                        // logic for this), with `Token.isForMatch` set
                        // instead to carry the distinction forward to
                        // TypeChecker.checkForLoop.
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
                        // "assume match expression;" or "assume match
                        // expression{ ... }," confirmed directly --
                        // "wherever a match statement is valid," so
                        // parsed right alongside "match" itself, with
                        // one real difference: the trailing block is
                        // optional here (the no-block form just ends at
                        // the line's own terminator, "the assumptions
                        // of the expression... are a given for the rest
                        // of the scope" instead of only a nested one).
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
                            // "@lock(match self.x : X)... X may also be
                            // a variant chain," confirmed directly --
                            // ':' is already fully committed to self-
                            // bolt syntax everywhere in the ordinary
                            // expression pipeline ("x:method()"), so
                            // this shape is split and the variant chain
                            // parsed directly, right here, at the raw-
                            // token level -- before ':' ever reaches
                            // that pipeline at all (see Token.
                            // assumeFieldVariants' own doc for why).
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
                        // Deliberately no requireRoot -- "both roots and
                        // functions," confirmed directly, unlike every
                        // other declaration keyword here.
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
                        if (isDefaultLockMatchImplStart(tokens)) {
                            result.add(wrapAsLine(gatherDefaultLockMatchImpl(tokens, first)));
                            i++;
                            continue;
                        }
                        if (isConstructorImplStart(tokens)) {
                            result.add(wrapAsLine(gatherConstructorImpl(tokens, first)));
                            i++;
                            continue;
                        }
                        result.add(wrapAsLine(gatherImpl(tokens, first)));
                        i++;
                        continue;
                    }
                    case "library": {
                        requireRoot(isRoot, first, "library");
                        result.add(wrapAsLine(gatherLibrary(tokens, first)));
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

    private void requireRoot(boolean isRoot, Token kwTok, String what) {
        if (!isRoot) {
            throw new CompilerException("parse", kwTok.file, kwTok.line,
                    "'" + what + "' is only allowed at the top level, not nested inside a block");
        }
    }

    private Token wrapAsLine(Token gathered) {
        Token lineTok = new Token(TokenType.LINE, "line", gathered.line, gathered.file);
        lineTok.childs.add(gathered);
        return lineTok;
    }

    /**
     * Gathers one "try EXPR catch { BLOCK }" span found starting at
     * `tokens.get(tryIdx)`, returning a single synthetic "try" KEYWORD
     * token to replace that whole span with. `.sub` holds the wrapped
     * expression's own raw tokens (between 'try' and 'catch', RPN-
     * converted/tree-built later exactly like an "if"/"for"/"match"
     * condition already is -- TypeChecker.checkTry then requires the
     * resulting tree root be a direct call, since only a call can ever
     * throw). `.childs` holds the catch block's own gathered statement
     * lines, recursed into right here via `gatherKeywordBlocks` the
     * same way every other block-bearing construct's trailing block
     * already is.
     *
     * Deliberately requires the whole span to run to the end of
     * `tokens` (nothing may follow the catch block's closing '}' on
     * this same line) -- "try foo() catch(e){...} + 1" or similar isn't
     * supported yet; keeping this restriction explicit now avoids
     * silently mis-parsing a shape nothing downstream has been taught
     * to handle.
     *
     * "catch(e){}" -- "Its always one argument untyped - doesnt have to
     * be called e, but anything other one plain argument is an error,"
     * confirmed directly: 'catch' now mandatorily takes exactly one
     * bare, untyped parameter name in parentheses, read here (never a
     * type annotation, never zero or more than one name -- "catch{...}"
     * with no parens at all is now a parse error too, the direct
     * grammar mirror of a func's own required "(...)" param list). The
     * name itself is stored on the gathered "try" token's own new
     * `catchParamName` field; nothing here gives it any real runtime
     * meaning yet -- "it still doesnt do anything with it in the
     * lowering, but it needs to work at the higher levels," confirmed
     * directly, the identical "parse and check it correctly first, wire
     * up the real semantics later" spirit a 'throw' message string's
     * own still-inert runtime value already established.
     */
    private Token gatherTryCatchSpan(List<Token> tokens, int tryIdx) {
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
        CatchClauseResult catchClause = gatherCatchClause(tokens, catchIdx,
                "try ... catch(...) { ... }", /* gatherBodyNow */ true);
        Token gathered = new Token(TokenType.KEYWORD, "try", tryTok.line, tryTok.file);
        gathered.sub = exprTokens;
        gathered.childs = catchClause.body;
        gathered.catchParamName = catchClause.paramName;
        gathered.decorators = tryTok.decorators;
        gathered.pinnedComments = tryTok.pinnedComments;
        return gathered;
    }

    /** The result of parsing one "catch(e) { ... }" clause -- shared by a real "try EXPR catch(e){...}" (`gatherTryCatchSpan`) and a standalone "?catch(e){...}" declaration (see `gatherKeywordBlocks`'s own "?" handling). */
    private static final class CatchClauseResult {
        final String paramName;
        final List<Token> body;

        CatchClauseResult(String paramName, List<Token> body) {
            this.paramName = paramName;
            this.body = body;
        }
    }

    /**
     * Parses one "catch(e) { ... }" clause starting at `tokens.get(catchIdx)`
     * (the "catch" KEYWORD token itself), requiring it to run to the exact
     * end of `tokens` -- "catch(...)" never has anything legally following
     * its own closing '}' on the same line, whether it's the tail of a real
     * "try EXPR catch(...){...}" span or a standalone "?catch(...){...}"
     * declaration. `trailingContextLabel` is only used to word the
     * "unexpected token after ..." error to match whichever of those two
     * callers is asking.
     *
     * `gatherBodyNow` controls whether the block's own body is gathered
     * (via `gatherKeywordBlocks`) right here, or left completely raw for
     * the caller to gather later: a real try/catch's own body is only ever
     * used once (at this exact node), so gathering it immediately is both
     * correct and simplest -- the identical shape every other
     * block-bearing construct's own trailing block already gets. A
     * "?catch(...){...}" declaration's own body, by contrast, may be
     * spliced into *many* different "?EXPR" use sites later on (see
     * `gatherKeywordBlocks`'s own "?" handling) -- each one needs its own,
     * completely independent copy, since gathering/RPN-conversion/tree-
     * building/type-checking all mutate tokens in place, and a first use
     * site's own check would otherwise permanently corrupt the shape every
     * later, independent use site would need to start from. So a
     * "?catch"'s own body is kept genuinely raw here (`gatherBodyNow =
     * false`) -- the exact same "store raw, deep-clone-and-gather fresh at
     * every use site" precedent `TypeChecker`'s own "impl default lock
     * match" policy template already established for the identical
     * problem shape (see `Token.deepCloneRaw`'s own doc comment).
     */
    private CatchClauseResult gatherCatchClause(List<Token> tokens, int catchIdx,
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
        return new CatchClauseResult(catchParamName, body);
    }

    /** The trailing '{' block token is always the last token of a gathered construct's line (used by loop/func/struct/enum, which never chain). */
    private Token requireTrailingBlock(List<Token> tokens, Token kwTok) {
        Token last = tokens.get(tokens.size() - 1);
        if (last.type != TokenType.DELINEATOR || !last.text.equals("{")) {
            throw new CompilerException("parse", kwTok.file, kwTok.line,
                    "expected '{' block after '" + kwTok.text + "'");
        }
        return last;
    }

    /**
     * Gathers an if/elseif/else chain starting at lines.get(startIndex),
     * consuming as many subsequent sibling lines as belong to the chain.
     * consumedOut[0] receives how many lines (including the 'if' line
     * itself) were consumed, so the caller can advance past all of them.
     *
     * Every branch always ends up as the *last* token of its own LINE,
     * regardless of how it was written in the source ("} else {" on one
     * physical line, or 'else' starting a fresh line) -- splitIntoLines
     * forces a line break immediately after any '{' block closes, so a
     * chain is always exactly one LINE per branch. That's why
     * requireTrailingBlock (last-token-based) is safe to use here, same
     * as for loop/func/struct/enum.
     */
    private Token gatherIfChain(List<Token> lines, int startIndex, int[] consumedOut) {
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

            chainTail.right = branch;
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

    /**
     * "I want chains, else match and elsematch," confirmed directly --
     * a direct mirror of gatherIfChain: 'match' as the head, 'elsematch'
     * for a chained condition, bare 'else' as the terminal catch-all,
     * same linked-list structure via '.right'. Kept as its own,
     * separate function rather than generalizing gatherIfChain to cover
     * both, since a genuinely shared implementation would need "if" vs
     * "match"/"elseif" vs "elsematch" as parameters threaded through
     * every branch -- more indirection than the ~15 lines this actually
     * duplicates justifies.
     */
    private Token gatherMatchChain(List<Token> lines, int startIndex, int[] consumedOut) {
        List<Token> matchTokens = lines.get(startIndex).childs;
        Token matchTok = matchTokens.get(0);
        Token blockTok = requireTrailingBlock(matchTokens, matchTok);
        List<Token> condition = new ArrayList<>(matchTokens.subList(1, matchTokens.indexOf(blockTok)));

        if (isCaseMatchBody(blockTok.childs)) {
            // "match b{ TRUE:{...} FALSE:{...} }" -- confirmed directly
            // a genuinely different statement shape, detected purely
            // from its own body's shape (a bare "VARREF ':' '{'" first
            // case) -- whether `condition` (here, "b") is actually
            // enum-typed isn't known until type-checking, so this can't
            // be decided any earlier than this. No 'elsematch'/'else'
            // chain is ever looked for here at all (unlike the ordinary
            // form just below) -- exhaustiveness/'default' already
            // guarantees every value is covered, so there's nothing left
            // for a trailing fallback branch to meaningfully do; a
            // stray 'elsematch'/'else' written after one of these is
            // left for the outer gathering loop's own existing
            // "'elsematch' without a preceding 'match'" check to catch.
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

            chainTail.right = branch;
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

    /**
     * "lock EXPR{...}" (guarantees EXPR.unlock() on every exit path) and
     * "match @lock EXPR{...}" (the swap-mutex spin-lock form, case-
     * labeled OPEN/CLOSED exactly like an ordinary case-match) --
     * structurally close cousins of "match"/"for" (a condition/target
     * expression followed by a trailing block), gathered the identical
     * way, but never chained ("lock" has no "elselock"/"else" of its
     * own). Which of the two shapes this is is detected purely
     * structurally, the same "no type information needed yet" way
     * "match"'s own case-match form already is: an optional "match"
     * keyword immediately after "lock" selects the spin-lock form --
     * stripped out of the condition's own raw tokens before RPN
     * conversion ever sees it, the identical "for match i in x{...}"
     * treatment (Token.isForMatch) already established, just reusing
     * Token.isCaseMatch here instead of a dedicated new flag, since the
     * spin-lock form's own case-labeled body is byte-for-byte the same
     * shape an ordinary case-match's body already has (gatherMatchCases,
     * unchanged) -- CLOSED being mandatory falls out for free from the
     * exact same case-match exhaustiveness checking every other
     * case-match already gets, needing no dedicated parse-time check
     * here at all.
     */
    /**
     * True when `tokens` structurally opens a "lock EXPR{...}"/"lock
     * match EXPR{...}" statement -- a bare VARREF "lock" (never a
     * KEYWORD -- see the caller's own doc comment for why) immediately
     * followed by anything other than "(" (an ordinary call to a
     * variable/function actually named "lock" instead), the whole line
     * ending in a trailing "{" block.
     */
    private boolean isLockStatementStart(List<Token> tokens) {
        if (tokens.size() < 3) {
            return false;
        }
        Token first = tokens.get(0);
        Token second = tokens.get(1);
        // "match @lock EXPR{...}" -- the case-match spin-lock form. The
        // "@" (an OPERATOR token anywhere but at a line's start, where it
        // would already have been stripped as a decorator) is what keeps
        // this unambiguous: "match lock{...}" and "match lock(x){...}"
        // stay ordinary matches on a variable / call named lock.
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
        return last.type == TokenType.DELINEATOR && last.text.equals("{");
    }

    private Token gatherLockStatement(List<Token> lines, int startIndex, int[] consumedOut) {
        List<Token> lockTokens = lines.get(startIndex).childs;
        Token lockTok = lockTokens.get(0);
        Token blockTok = requireTrailingBlock(lockTokens, lockTok);
        boolean isLockMatch = lockTok.type == TokenType.KEYWORD && lockTok.text.equals("match");
        int conditionStart = isLockMatch ? 3 : 1;
        List<Token> condition = new ArrayList<>(lockTokens.subList(conditionStart, lockTokens.indexOf(blockTok)));

        Token gathered = new Token(TokenType.KEYWORD, "lock", lockTok.line, lockTok.file);
        gathered.sub = condition;
        gathered.decorators = lockTok.decorators;
        gathered.pinnedComments = lockTok.pinnedComments;

        if (isLockMatch) {
            gathered.isCaseMatch = true;
            gathered.childs = gatherMatchCases(blockTok.childs, true);
        } else {
            blockTok.childs = gatherKeywordBlocks(blockTok.childs, false);
            gathered.childs = blockTok.childs;
        }
        consumedOut[0] = 1;
        return gathered;
    }

    /** True when a match body's own first non-empty line starts with "VARREF ':' '{'" -- the enum-case form's own distinguishing shape, checked purely structurally (no type information exists yet at parse time). */
    /** True when a match body's own first non-empty line starts with a valid case-label shape -- "VARREF ':' '{'" or, for the `|`-chained form, "VARREF ('|' VARREF)* ':' '{'" -- the case-block form's own distinguishing shape, checked purely structurally (no type information exists yet at parse time). */
    private boolean isCaseMatchBody(List<Token> bodyLines) {
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
     * Walks a "match b{...}" body already confirmed (isCaseMatchBody)
     * to be the case-block form, extracting one Token per case --
     * "LABEL:{...}" or "LABEL1|LABEL2|...:{...}" (`default` included,
     * for an enum). Flattens every already-newline/semicolon-split line
     * in `bodyLines` back into one continuous stream first, rather than
     * relying on that existing per-line split at all -- "delineated by
     * commas, semi-colons, or ... with neither," confirmed directly, so
     * a comma/semicolon-separated pair of cases sharing one *physical*
     * source line (which splitIntoLines' own newline/semicolon-
     * triggered splitting would never separate on its own, since
     * neither a bare VARREF nor a comma trigger it) still needs walking
     * case-by-case by hand here. The `|`-chained form -- "unlike other
     * modifiers this one can be chained with |," confirmed directly,
     * though only ever actually described for `f32`'s own state
     * modifier -- is handled generically here rather than kept
     * float-only, since nothing about grouping several labels under
     * one shared case body is inherently f32-specific; an enum's own
     * case label is simply the one-element case of the exact same
     * shape.
     */
    private List<Token> gatherMatchCases(List<Token> bodyLines, boolean allowDefaultPolicyRef) {
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
            // "CLOSED:default" / "CLOSED:default(args)" -- a 'lock
            // match'-only shorthand (never recognized for an ordinary
            // enum/float case-match, where 'default' already has its
            // own, unrelated meaning as the '@non_exhaustive' catch-all
            // case label) invoking a registered
            // "impl default match @lock TypeName{...}" policy instead of
            // writing a literal '{...}' body. Deliberately auto-wrapped
            // as an ordinary, already-fully-supported expression-
            // statement shape (a bare VARREF, or -- for the
            // parenthesized form -- a CALL node, exactly the shape
            // "default(500,2000)" would already have as any other
            // ordinary call expression) rather than inventing any new
            // Token field the rest of the pipeline would need teaching
            // about: RpnConverter/TreeBuilder need zero new code for
            // this, since a bare VARREF or a CALL-node-as-statement are
            // already completely ordinary, already-tested shapes.
            // TypeChecker.checkLockMatchStatement inspects the raw
            // shape directly (never resolving "default" as a real
            // variable/function reference at all) to detect and
            // substitute this specific marker.
            if (allowDefaultPolicyRef && i < flat.size() && flat.get(i).type == TokenType.VARREF
                    && flat.get(i).text.equals("default")) {
                Token defaultTok = flat.get(i);
                i++;
                List<Token> refTokens = new ArrayList<>();
                refTokens.add(defaultTok);
                if (i < flat.size() && flat.get(i).type == TokenType.OPERATOR && flat.get(i).text.equals("CALL")) {
                    // The parenthesized "default(args)" form -- stage 6
                    // (insertCallLookupInFlat), which already ran over
                    // this whole body before gatherKeywordBlocks/
                    // gatherMatchCases ever see it, already inserted
                    // this "CALL" marker between "default" and its
                    // trailing '(' group, exactly as it would for any
                    // ordinary "name(args)" call expression -- nothing
                    // further is needed here beyond carrying both
                    // tokens (and the already-nested '(' group) through
                    // unchanged.
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

    private String labelChainText(List<Token> labels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(labels.get(i).text);
        }
        return sb.toString();
    }

    private Token gatherFunc(List<Token> tokens, Token funcTok) {
        if (tokens.size() < 3 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", funcTok.file, funcTok.line,
                    "expected function name after 'func'");
        }
        Token nameTok = tokens.get(1);
        TypeParamParseResult typeParams = parseOptionalTypeParams(tokens, 2);
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
        // A brand-new function/method body: no "?catch(...)" declared
        // anywhere in a *previous* sibling function may still be pending
        // here -- reset unconditionally (no save/restore needed; functions
        // never nest, so there's no outer call this could ever need to be
        // restored back to). This is the ONLY reset site: `gatherImpl`
        // routes every one of its own methods through this exact same
        // method (see its own doc comment), so a single reset here already
        // covers both plain top-level functions and impl methods alike.
        pendingOptionalCatchParamName = null;
        pendingOptionalCatchRawBody = null;
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

    /**
     * "extern printf(static imut string,...) void" -- declares a
     * signature the final linked assembly is expected to already
     * provide (a C function, typically), never a body of its own.
     * Structurally the header-only half of gatherFunc: same "name (
     * params ) returnType" shape, no type parameters (externs are
     * never generic -- there's no C equivalent of monomorphization),
     * and no trailing '{' block at all -- the line simply ends after
     * the return type. Does now accept decorators -- "@call_convention"
     * specifically ("when I extern a func I should be able to put the
     * calling convention decorator in the extern statement," confirmed
     * directly -- this is the actual C/OS ABI boundary); any other
     * decorator is rejected by TypeChecker.collectExtern's own
     * narrower allowed-set (none of this language's other decorators
     * have any C-linkage meaning), not here -- gatherExtern only
     * carries whatever decorators the general stripDecoratorsDeep pass
     * already attached to `externTok`, same as every other gather*
     * method.
     */
    private Token gatherExtern(List<Token> tokens, Token externTok) {
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

    /**
     * "export foo" -- marks an already-declared, ordinary top-level
     * 'func' to be emitted under its own bare (never mangled) name for
     * external linkage, so a C program linking this compilation unit's
     * output can declare "extern void foo();" and call it. Just a bare
     * name reference, nothing more -- the referenced function's own
     * existence, signature shape, and every other restriction (no
     * generics, no ambiguity, at most once) is a TypeChecker concern
     * (TypeChecker.collectExport), not checked here.
     */
    private Token gatherExport(List<Token> tokens, Token exportTok) {
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

    /**
     * "ASM optional_name { [block] }" or "ASM optional_name
     * \"path/to/filename\"" -- the block form's body already arrives
     * here as one pre-scanned, pre-validated ASM_BLOCK token (see
     * Lexer.scanAsmBlock -- balance and 'ASM_END' already checked at
     * the character level, well before ordinary tokenization could
     * ever have misinterpreted any of it); the file form's path is
     * just an ordinary string literal, validated (file existence,
     * 'ASM_END' scan) later by TypeChecker, the same way any other
     * real filesystem work in this compiler happens outside the parser.
     */
    private Token gatherAsm(List<Token> tokens, Token asmTok) {
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

    private static final class ImplementsParseResult {
        final List<String> names; // null if there was no 'implements' clause at all
        final int nextIndex;
        ImplementsParseResult(List<String> names, int nextIndex) {
            this.names = names;
            this.nextIndex = nextIndex;
        }
    }

    /**
     * Parses an optional "implements A, B, ..." header clause. Purely a
     * compile-time contract declaration (checked in TypeChecker against
     * actual "impl X for Y{...}" blocks found anywhere in the compilation
     * unit) -- never itself a source of method bodies, since struct
     * bodies can't contain funcs.
     */
    private ImplementsParseResult parseOptionalImplementsClause(List<Token> tokens, int idx) {
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

    /**
     * Body for "struct". A member line starting with "extends" is a parse
     * error: struct inheritance has been removed (use composition and
     * interfaces). Everything else in the body passes through untouched
     * (TypeChecker's collectStruct interprets each line via
     * parseNameTypeGroup).
     */
    private Token gatherStructLike(List<Token> tokens, Token kwTok, String kindText) {
        if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", kwTok.file, kwTok.line,
                    "expected a name after '" + kindText + "'");
        }
        Token nameTok = tokens.get(1);
        TypeParamParseResult typeParams = parseOptionalTypeParams(tokens, 2);
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

    private Token gatherStruct(List<Token> tokens, Token structTok) {
        return gatherStructLike(tokens, structTok, "struct");
    }

    /**
     * "type NAME <target type tokens>" -- a one-liner, no trailing '{'
     * block at all (unlike every other root-level construct): whatever
     * follows the name, to the end of the line, is the alias's raw
     * target type-token span, kept completely unparsed here and read
     * directly by TypeChecker (the same "just hand TypeChecker the raw
     * tokens" treatment a struct member's or func param's type
     * annotation already gets).
     */
    private Token gatherType(List<Token> tokens, Token typeTok) {
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

    /** An enum variant's integer value (decimal text; hexadecimal/binary literals arrive already normalised to decimal): enum values are 63-bit. */
    private static long enumLiteralValue(Token t) {
        try {
            return Long.parseLong(t.text);
        } catch (NumberFormatException e) {
            throw new CompilerException("parse", t.file, t.line,
                    "enum value " + t.text + " is too large (the largest an enum variant can hold is 9223372036854775807)");
        }
    }

    private Token gatherConst(List<Token> tokens, Token constTok) {
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
        // Mirrors 'if''s own condition -- .sub holds only the (non-
        // expression) name, .childs holds the raw expression tokens
        // RpnConverter/TreeBuilder will process exactly the way they
        // already process an 'if' node's own .sub (resolveNestedGroups,
        // toRpn, buildTree), just parked in a different field here since
        // .sub is needed for the name.
        Token gathered = new Token(TokenType.KEYWORD, "const", constTok.line, constTok.file);
        gathered.sub = new ArrayList<>();
        gathered.sub.add(nameTok);
        gathered.childs = exprTokens;
        gathered.decorators = constTok.decorators;
        gathered.pinnedComments = constTok.pinnedComments;
        return gathered;
    }

    private Token gatherEnum(List<Token> tokens, Token enumTok) {
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
        // "we will store unique ranges (pairs of u64s) as variants...
        // In addition this enum supports a nested syntax of bare
        // identifiers," confirmed directly -- a genuinely different
        // grammar for the whole enum body, detected structurally
        // (any variant line whose own second token is a '{' block) the
        // same way a case-match body is told apart from an ordinary
        // one (Token.isCaseMatch) -- decided once, for the whole enum,
        // never mixed with the ordinary flat "NAME[: VALUE]" form in
        // the same enum.
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
                    // "enum COLOR{BLACK:0,WHITE:1}... or... BLACK=0,
                    // WHITE=1," confirmed directly -- an optional ":" or
                    // "=" immediately after a variant's own name, followed
                    // either by an integer literal (a plain, u64-valued
                    // enum) or by "INTEGER..INTEGER" (a range-valued
                    // enum -- "Test{ HIGH:100..2000 LOW:0..100 }",
                    // confirmed directly) -- attached directly to that
                    // variant's own token (validated -- kind consistency,
                    // uniqueness, "no partial mixing" -- once the whole
                    // enum is known, in TypeChecker.collectEnum).
                    if (vi + 1 < lineTokens.size() && lineTokens.get(vi + 1).type == TokenType.OPERATOR
                            && (lineTokens.get(vi + 1).text.equals(":") || lineTokens.get(vi + 1).text.equals("="))) {
                        Token sepTok = lineTokens.get(vi + 1);
                        if (vi + 2 >= lineTokens.size() || lineTokens.get(vi + 2).type != TokenType.INTEGER) {
                            throw new CompilerException("parse", sepTok.file, sepTok.line,
                                    "'" + t.text + "" + sepTok.text + "' expects an integer literal "
                                            + "(positive only), or a range literal (e.g. '100..2000'), "
                                            + "after it");
                        }
                        // A range-valued variant: "NAME: A..B" -- the
                        // ".." itself never reaches the ordinary
                        // expression pipeline here (enum bodies are
                        // never passed through resolveNestedGroups/toRpn
                        // at all, the same "never anything but a single
                        // literal digit sequence" reasoning the plain
                        // int form already relies on), so it's
                        // recognized directly, at the raw-token level,
                        // by shape: "INTEGER '..' INTEGER".
                        if (vi + 4 < lineTokens.size() && lineTokens.get(vi + 3).type == TokenType.OPERATOR
                                && lineTokens.get(vi + 3).text.equals("..")
                                && lineTokens.get(vi + 4).type == TokenType.INTEGER) {
                            t.explicitRangeStart = enumLiteralValue(lineTokens.get(vi + 2));
                            t.explicitRangeEnd = enumLiteralValue(lineTokens.get(vi + 4));
                            vi += 4;
                        } else {
                            t.explicitEnumValue = enumLiteralValue(lineTokens.get(vi + 2));
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
    private boolean hasNestedEnumVariant(List<Token> variantLines) {
        for (Token variantLine : variantLines) {
            List<Token> lineTokens = variantLine.childs;
            if (lineTokens.size() >= 2 && lineTokens.get(1).type == TokenType.DELINEATOR
                    && lineTokens.get(1).text.equals("{")) {
                return true;
            }
        }
        return false;
    }

    /**
     * "the starts are just the index of the current variant if it were
     * read without regard for nesting, and the end is either the same
     * index, if that variant has no block, or to the last entry in
     * their block if they have one," confirmed directly -- a plain
     * pre-order (parent before children, in declaration order) walk of
     * the enum body's own nested block structure, numbering every
     * variant (leaf or internal) as it's first visited. `counter[0]` is
     * threaded through by reference (a one-element array, the same
     * "no return-value plumbing needed" trick used elsewhere for a
     * single mutable int) since it has to keep incrementing across
     * every recursive call, not just within one level.
     */
    private void parseNestedEnumVariant(Token variantLine, List<Token> outVariants, long[] counter) {
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

    /**
     * "interface Name{ method(params) returnType; ... }" -- each method
     * is a bare signature (no 'func' keyword, no body, semicolon- or
     * newline-terminated -- splitIntoLines already separates them into
     * one LINE per signature). Gathered into a "interface_method"
     * token per method (sub = [nameTok, paramsTok, ...returnTypeTokens],
     * mirroring 'func's own sub shape minus the body/childs, since
     * there is no body). Validating "self must be a pointer" and
     * resolving each method's actual types happens later, in
     * TypeChecker -- self isn't a concrete type yet at this stage.
     */
    private Token gatherInterface(List<Token> tokens, Token interfaceTok) {
        if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", interfaceTok.file, interfaceTok.line,
                    "expected interface name after 'interface'");
        }
        Token nameTok = tokens.get(1);
        TypeParamParseResult typeParams = parseOptionalTypeParams(tokens, 2);
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
            // "static" -- either "static func NAME(...) ReturnType{...}"
            // (a @default static method, body and all) or
            // "static NAME(...) ReturnType;" (a plain static signature,
            // the exact same bare shape an ordinary interface method
            // signature already uses, just with this one token in
            // front) -- mirrors the plain-signature/@default split just
            // below it, one level up.
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
                // A default method -- full func syntax (name, params,
                // return type, *and* a real body), reusing gatherFunc
                // exactly the way an impl method already does (bypassing
                // the isRoot check the same deliberate way). Whether
                // "@default" was actually present is TypeChecker's
                // concern (registerInterface), not Parser's -- this is
                // purely a shape distinction (has a body vs. signature
                // only).
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

    private Token gatherInterfaceMethod(List<Token> tokens, Token interfaceTok) {
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

    /**
     * "impl InterfaceName for ConcreteName{ func ... }" or
     * "impl ConcreteName{ func ... }" -- distinguished by whether a
     * 'for' keyword appears after the first name. Only 'func'
     * declarations are allowed directly inside the block; each is
     * gathered via the *exact same* gatherFunc used for a top-level
     * func, called directly (bypassing the isRoot check entirely,
     * since impl bodies are a deliberate, narrow exception to
     * "no funcs within funcs" -- these aren't nested inside another
     * function, they're nested inside a top-level impl block).
     * gathered.sub is [concreteNameTok] for a bare impl, or
     * [interfaceNameTok, concreteNameTok] for an interface impl --
     * callers distinguish by checking sub.size().
     */
    private Token gatherImpl(List<Token> tokens, Token implTok) {
        TypeParamParseResult implTypeParams = parseOptionalTypeParams(tokens, 1);
        int idx = implTypeParams.nextIndex;

        if (idx >= tokens.size() || tokens.get(idx).type != TokenType.VARREF) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "expected a name after 'impl'" + (implTypeParams.names != null ? "'s type-parameter list" : ""));
        }
        Token firstNameTok = tokens.get(idx);
        idx++;
        TypeArgParseResult firstArgs = parseOptionalTypeArgsRaw(tokens, idx);
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
            TypeArgParseResult cArgs = parseOptionalTypeArgsRaw(tokens, idx);
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
                // Decorators (e.g. "@realizes") attach to a line's own
                // *first* token -- "static" here, not the "func" token
                // one further along that gatherFunc itself was given as
                // its "funcTok" and reads `.decorators` off of directly.
                // Without this, a static method's own decorators are
                // silently lost the same way a plain instance method's
                // never are (it's always given the line's true first
                // token as "funcTok").
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

    /**
     * True when `tokens` structurally opens an "impl default lock
     * match TypeName{...}" declaration -- "default" and "lock" are
     * both, deliberately, ordinary VARREF tokens at the lexer level
     * (never reserved -- "default" stays contextual the same way it
     * already is for an enum match's own catch-all case, and "lock" is
     * never a keyword at all, per its own doc comment on
     * isLockStatementStart), so this is detected purely structurally,
     * the identical "no type information needed" precedent every other
     * shape-based detection in this parser already uses. Checked only
     * right after "impl" (an ordinary interface/struct name can never
     * itself be followed by a bare "lock" VARREF then the real KEYWORD
     * "match" -- gatherImpl's own grammar has no room for that shape at
     * all), so a real interface genuinely named "default" collides with
     * nothing here.
     */
    /**
     * The per-struct overload-list key every constructor for
     * "ConcreteName" is registered under in `TypeChecker.functions`,
     * both as the Java-side map key and as the function's own real,
     * emitted bytecode/assembly name -- so, unlike "$ret_dest" (a plain
     * *variable* name, resolved to a frame offset, never itself written
     * out as a real assembly symbol), this can't use a "$" prefix: a
     * function's own mangled name IS emitted verbatim as a real
     * assembly label/call target (confirmed the hard way -- "$" there
     * assembles as an AT&T-syntax immediate-operand sigil, not a valid
     * symbol character, and fails with "operand size mismatch for
     * `call'"). Uses this project's own existing "__"-prefixed dundered-
     * name convention instead (already used for "__main"/
     * "__trampoline_<name>"), accepting the same narrow, already-
     * precedented collision risk those already do (a real top-level
     * function named exactly "__ctor__MyClass" would collide) rather
     * than inventing a new safety mechanism neither of those needed
     * either. Shared with `TypeChecker` (which needs the identical
     * prefix to look candidates up at a call site) -- kept as one
     * literal string constant here since `Parser` is the only place
     * that *builds* the name, `TypeChecker` only ever concatenates the
     * identical prefix itself.
     */
    static final String CONSTRUCTOR_NAME_PREFIX = "__ctor__";

    /**
     * True right after "impl" when this is "impl constructor for
     * ConcreteName(...) self {...}" -- a constructor declaration --
     * rather than an ordinary "impl InterfaceName for ConcreteName {...}"
     * (a real interface literally named "constructor" is the one,
     * accepted, deliberately unhandled collision here, matching the
     * identical tradeoff "impl default match @lock" already accepts for
     * an interface literally named "default"/"lock").
     */
    private boolean isConstructorImplStart(List<Token> tokens) {
        // Tolerates an optional leading "<T,...>" impl-level type-
        // parameter list (for "impl<T> constructor for X<T>(...) self
        // {...}", a generic constructor) exactly the way gatherImpl's own
        // "impl<T> Name<T>{...}" grammar does -- parseOptionalTypeParams
        // is side-effect-free/read-only, so it's safe to call here purely
        // as a peek, before committing to this being a constructor decl
        // at all.
        TypeParamParseResult implTypeParams = parseOptionalTypeParams(tokens, 1);
        int idx = implTypeParams.nextIndex;
        return tokens.size() > idx + 1
                && tokens.get(idx).type == TokenType.VARREF && tokens.get(idx).text.equals("constructor")
                && tokens.get(idx + 1).type == TokenType.KEYWORD && tokens.get(idx + 1).text.equals("for");
    }

    /**
     * "impl constructor for MyClass(x:u64,y:u64) self { return MyClass{
     * x:x,y:y } }" -- a constructor: callable as a plain "MyClass(...)"
     * (stack-constructed, by value, via the exact same Return Value
     * Optimization mechanism an ordinary struct-by-value-returning
     * function already uses) or as "new MyClass(...)" (heap-constructed).
     * More than one may be declared for the same struct, overload-
     * resolved by parameter shape exactly like an ordinary top-level
     * function -- achieved by registering every constructor for one
     * struct as an ordinary `FuncInfo` overload under one shared,
     * synthetic, "$"-prefixed name (`CONSTRUCTOR_NAME_PREFIX +
     * concreteName`) in `TypeChecker.functions`, the exact same map (and
     * exact same duplicate-signature/mangled-name-disambiguation
     * machinery) an ordinary overloaded top-level function already uses
     * -- not a new mechanism of its own.
     *
     * Supports two shapes, mirroring exactly how `gatherImpl` itself
     * tells a plain "impl Name{...}" apart from a generic
     * "impl<T> Name<T>{...}": a non-generic "impl constructor for
     * MyClass(...) self {...}" produces the same bare "func" token this
     * always has (an ordinary `TypeChecker.collectFunc` registration,
     * unchanged, no wrapper needed); a generic
     * "impl<T> constructor for Slice<T>(...) self {...}" instead
     * produces an "impl"-shaped wrapper token, structurally identical to
     * `gatherImpl`'s own output (`.sub=[concreteNameTok]` with
     * `.genericArgs` set, `.typeParams`/`.typeParamBounds` from the
     * header, `.childs=[the constructor's own func token]`) -- this lets
     * `GenericsExpander`'s existing impl-template machinery
     * (`registerImplTemplate`/`generateImplInstantiation`, already used
     * for "impl<T> Name<T>{...}") register and instantiate it per
     * concrete `Slice<...>` with zero new top-level-dispatch cases of
     * its own needed there; only `generateImplInstantiation` itself
     * needed a small, explicit patch (see its own doc comment) to rename
     * the cloned constructor method's own callable name and
     * `constructorConcreteName` to the mangled struct name, since neither
     * is reachable by that method's ordinary VARREF-substitution walk.
     *
     * The return type must still be written exactly as the bare word
     * "self" -- substituted here, at parse time, for the concrete
     * struct's own real name (a VARREF token, mirroring exactly what an
     * ordinary "func foo(...) MyClass {...}" declaration's own
     * return-type token already looks like, carrying the same
     * `.genericArgs` the concrete name's own usage carries when generic),
     * so nothing downstream needs to know "self" was ever written at
     * all -- by the time `gatherFunc` sees this declaration, it's
     * indistinguishable from an ordinary function returning that struct
     * by value.
     *
     * Delegates the header's own parameter-list parsing, and the whole
     * body's own statement gathering (including nested "?catch"/
     * "try"/etc. state), entirely to the existing `gatherFunc` -- a
     * constructor's body is checked exactly like any other function
     * body, no special-casing needed there at all.
     */
    private Token gatherConstructorImpl(List<Token> tokens, Token implTok) {
        TypeParamParseResult implTypeParams = parseOptionalTypeParams(tokens, 1);
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
        TypeArgParseResult concreteArgsResult = parseOptionalTypeArgsRaw(tokens, idx);
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
        Token nameTok = new Token(TokenType.VARREF, CONSTRUCTOR_NAME_PREFIX + concreteNameTok.text,
                implTok.line, implTok.file);
        Token returnTypeTok = new Token(TokenType.VARREF, concreteNameTok.text, selfTok.line, selfTok.file);
        // For a generic constructor, the return type's own "<...>" usage
        // is set directly here (exactly like gatherImpl already does for
        // its own concrete-name token) rather than left as raw flat
        // tokens for GenericsExpander's scanTypeTokenSpan to find -- a
        // single-token return-type span never has a following "<" token
        // for that scan to match against, so this is the only way this
        // return annotation could ever become generic-args-bearing at
        // all. `substituteParams` already recurses into `.genericArgs`
        // (confirmed for List<T>'s own "T"), so per-instantiation this
        // still gets rewritten correctly like any other generic usage.
        if (concreteArgs != null) {
            returnTypeTok.genericArgs = concreteArgs;
        }

        List<Token> synth = new ArrayList<>();
        synth.add(funcKw);
        synth.add(nameTok);
        synth.add(paramsTok);
        synth.add(returnTypeTok);
        synth.add(blockTok);

        Token gathered = gatherFunc(synth, funcKw);
        gathered.isConstructorDecl = true;
        gathered.constructorConcreteName = concreteNameTok.text;

        if (implTypeParams.names == null) {
            return gathered;
        }

        // Generic case: wrap in an "impl"-shaped token structurally
        // identical to gatherImpl's own output, so GenericsExpander's
        // existing impl-template registration/instantiation machinery
        // handles this with no new cases of its own -- see this method's
        // own doc comment.
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

    private boolean isDefaultLockMatchImplStart(List<Token> tokens) {
        return tokens.size() > 4
                && tokens.get(1).type == TokenType.VARREF && tokens.get(1).text.equals("default")
                && tokens.get(2).type == TokenType.KEYWORD && tokens.get(2).text.equals("match")
                && tokens.get(3).type == TokenType.VARREF && tokens.get(3).text.equals("lock");
    }

    /**
     * "impl default match @lock MyMutex{ CLOSED:(tries:imut u64,...)
     * =>{...} }" -- a reusable, parameterized backoff/retry policy
     * template for a "match @lock" statement's own CLOSED case (see
     * TypeChecker.DefaultLockMatchPolicy). Deliberately gathers nothing
     * beyond this declaration's own outer shape -- the parameter list
     * ("(" paramsTok, exactly the same raw, ungathered token holder
     * gatherFunc itself stashes for an ordinary function) and the
     * policy body's own raw lines are kept completely raw, never
     * gathered/RPN-converted/tree-built here at all, since the whole
     * point of this declaration is a *template*: its own "break" has to
     * target whichever spin loop a real "CLOSED:default"/
     * "CLOSED:default(args)" use site generates, which doesn't exist
     * yet at declaration time. TypeChecker deep-clones a fresh copy of
     * both, once per use site, and only *that* copy is ever gathered,
     * converted, built, and checked.
     */
    private Token gatherDefaultLockMatchImpl(List<Token> tokens, Token implTok) {
        if (tokens.size() < 6 || tokens.get(4).type != TokenType.VARREF) {
            throw new CompilerException("parse", implTok.file, implTok.line,
                    "expected a struct name after 'impl default match @lock'");
        }
        Token typeNameTok = tokens.get(4);
        Token blockTok = requireTrailingBlock(tokens, implTok);
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

    /**
     * "library NAME<TypeParams?>{...}" -- a collection of functions,
     * dispatched purely by bare library name ("Math.sin(10.2)"), never
     * through an instance. Header shape (name, optional type params)
     * mirrors gatherStructLike's; the body is a mix of "extends
     * TypeName;" statements (pulled out into `extendsNames`, reusing
     * exactly the same statement-list extraction gatherStructLike
     * already established for interface) and plain "func"
     * declarations (gathered the same, non-static way gatherImpl
     * gathers a bare, non-static method -- a library function never
     * takes an implicit "self" at all, so there's no static/instance
     * distinction to make here the way an impl has).
     */
    private Token gatherLibrary(List<Token> tokens, Token libraryTok) {
        if (tokens.size() < 2 || tokens.get(1).type != TokenType.VARREF) {
            throw new CompilerException("parse", libraryTok.file, libraryTok.line,
                    "expected a name after 'library'");
        }
        Token nameTok = tokens.get(1);
        TypeParamParseResult typeParams = parseOptionalTypeParams(tokens, 2);
        Token blockTok = requireTrailingBlock(tokens, libraryTok);
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
            functions.add(gatherFunc(lineTokens, lineTokens.get(0)));
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

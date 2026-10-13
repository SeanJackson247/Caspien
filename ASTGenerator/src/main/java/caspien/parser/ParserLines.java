package caspien.parser;
import caspien.lexer.Lexeme;
import caspien.CompilerException;
import caspien.Token;
import caspien.TokenType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Stage 2 of the front end: nests the Lexer's flat tokens, splits them into LINE tokens,
 * strips decorators, merges let/import/generic syntax and gathers keyword blocks.
 * Pass order is in parse(); see docs/Parser.md, "Pipeline and scoping".
 */

/** ParserLines: fields, nested types and static helpers of Parser (part 1 of 3; see README.md in this folder). */
final class ParserLines {
    private final Parser hub;

    ParserLines(Parser hub) {
        this.hub = hub;
    }

    public static final String OPEN_CHARS = "([{";
    public static final String CLOSE_CHARS = ")]}";

    /** Most recent "?catch(e){..}" declaration (raw body); unscoped, reset per function in gatherFunc.
     *  See docs/Parser.md, "?catch and ?EXPR sugar". */
    public String pendingOptionalCatchParamName = null;
    public List<Token> pendingOptionalCatchRawBody = null;

    /** Returns the matching open character for a given close character, e.g. ')' -> '('. */
    public static char matchingOpen(char close) {
        return OPEN_CHARS.charAt(CLOSE_CHARS.indexOf(close));
    }

    // ---- 2. line splitting -------------------------------------------

    /** Keywords whose '{' ends the statement header; any other '{' (e.g. a struct literal) must not split the line. See docs/Parser.md, "Line splitting". */
    public static final Set<String> BLOCK_HEADER_KEYWORDS = new HashSet<>(Arrays.asList(
            "if", "elseif", "else", "loop", "func", "struct", "enum", "interface", "impl", "for", "library"
    ));

    // ---- generic "visit every line, anywhere in the tree" walker -----

    public interface LineVisitor {
        void visit(Token lineToken);
    }

    public static final class TypeParamParseResult {
        public final List<String> names; // null if there was no '<' at all
        public final List<String> bounds; // parallel to names; null entries mean "no bound"; whole list null if no bound anywhere
        public final int nextIndex;
        public TypeParamParseResult(List<String> names, List<String> bounds, int nextIndex) {
            this.names = names;
            this.bounds = bounds;
            this.nextIndex = nextIndex;
        }
    }

    public static final class TypeArgParseResult {
        public final List<List<Token>> groups; // null if there was no '<' at all
        public final int nextIndex;
        public TypeArgParseResult(List<List<Token>> groups, int nextIndex) {
            this.groups = groups;
            this.nextIndex = nextIndex;
        }
    }

    /** The result of parsing one "catch(e) { ... }" clause -- shared by a real "try EXPR catch(e){...}" (`gatherTryCatchSpan`) and a standalone "?catch(e){...}" declaration (see `gatherKeywordBlocks`'s own "?" handling). */
    public static final class CatchClauseResult {
        public final String paramName;
        public final List<Token> body;

        public CatchClauseResult(String paramName, List<Token> body) {
            this.paramName = paramName;
            this.body = body;
        }
    }

    public static final class ImplementsParseResult {
        public final List<String> names; // null if there was no 'implements' clause at all
        public final int nextIndex;
        public ImplementsParseResult(List<String> names, int nextIndex) {
            this.names = names;
            this.nextIndex = nextIndex;
        }
    }

    /** An enum variant's integer value (decimal text; hexadecimal/binary literals arrive already normalised to decimal): enum values are 63-bit. */
    public static long enumLiteralValue(Token t) {
        try {
            return Long.parseLong(t.text);
        } catch (NumberFormatException e) {
            throw new CompilerException("parse", t.file, t.line,
                    "enum value " + t.text + " is too large (the largest an enum variant can hold is 9223372036854775807)");
        }
    }

    /** Overload-list key prefix for constructors in TypeChecker.functions; also an emitted symbol, so no "$" (invalid in assembly labels). Shared with TypeChecker. See docs/Parser.md, "Constructors". */
    public static final String CONSTRUCTOR_NAME_PREFIX = "__ctor__";

    /** Runs the whole parse pipeline; returns the file's top level as gathered LINE tokens. */
    public List<Token> parse(List<Lexeme> lexerOutput) {
        List<Token> nested = nest(toTokens(lexerOutput));
        gatherTemplateStringsDeep(nested);
        List<Token> lines = splitIntoLines(nested);

        forEachLineDeep(lines, this::mergeLetInLine);
        forEachLineDeep(lines, this::collapseImportInLine);
        stripDecoratorsDeep(lines);

        removeWhitespaceInLines(lines);
        mergeThenOperators(lines);
        collapseGenericInstantiationsInLines(lines);
        insertCallLookupInLines(lines);

        return hub.blocks.gatherKeywordBlocks(lines, true);
    }

    // ---- 1. nesting -------------------------------------------------

    /** Each Lexeme becomes the Token that will carry it through the tree stages (pinned comments keep their identity). */
    public List<Token> toTokens(List<Lexeme> lexemes) {
        IdentityHashMap<Lexeme, Token> made = new IdentityHashMap<>();
        List<Token> out = new ArrayList<>(lexemes.size());
        for (Lexeme l : lexemes) {
            out.add(tokenOf(l, made));
        }
        return out;
    }

    private Token tokenOf(Lexeme l, IdentityHashMap<Lexeme, Token> made) {
        Token t = made.get(l);
        if (t != null) {
            return t;
        }
        t = new Token(l.type, l.text, l.line, l.file);
        made.put(l, t);
        t.literalValue = l.literalValue;
        t.forcedLiteralType = l.forcedLiteralType;
        for (Lexeme c : l.pinnedComments) {
            t.pinnedComments.add(tokenOf(c, made));
        }
        return t;
    }

    public List<Token> nest(List<Token> flat) {
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

    /** Collapses the lexer's template-string marker sequence into one TEMPLATE_STRING token (recursive).
     *  See docs/Parser.md, "Template strings". */
    public void gatherTemplateStringsDeep(List<Token> flat) {
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
                        // whitespace must go here: later passes never recurse into TEMPLATE_STRING segments
                        // (see docs/Parser.md, "Template strings")
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

    /** Strips WHITESPACE/TERMINATOR from a "${...}" segment, recursing into '(' and '[' only. */
    public void removeWhitespaceFromExprGroup(List<Token> flat) {
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

    public boolean lineStartsWithBlockHeaderKeyword(List<Token> current) {
        for (Token t : current) {
            if (t.type == TokenType.WHITESPACE) {
                continue;
            }
            return t.type == TokenType.KEYWORD && BLOCK_HEADER_KEYWORDS.contains(t.text);
        }
        return false;
    }

    /** Groups a statement-sequence token list into LINE tokens (split on TERMINATOR and after a header-keyword '{'). */
    public List<Token> splitIntoLines(List<Token> flat) {
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

    public void splitNestedBlocks(List<Token> flat) {
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

    public boolean hasNonWhitespace(List<Token> toks) {
        for (Token t : toks) {
            if (t.type != TokenType.WHITESPACE) {
                return true;
            }
        }
        return false;
    }

    public Token makeLine(List<Token> content, int line) {
        Token lineTok = new Token(TokenType.LINE, "line", line, content.get(0).file);
        lineTok.childs = content;
        return lineTok;
    }

    public void forEachLineDeep(List<Token> lines, LineVisitor visitor) {
        for (Token lineTok : lines) {
            visitor.visit(lineTok);
            scanForBlockLines(lineTok.childs, visitor);
        }
    }

    public void scanForBlockLines(List<Token> flat, LineVisitor visitor) {
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
     * Removes "@name" / "@name(args)" lines and attaches them as Decorator objects to the next line's
     * first token. Validity per construct is the TypeChecker's job. See docs/Parser.md, "Decorator lines".
     */
    public void stripDecoratorsDeep(List<Token> lines) {
        List<Token.Decorator> pending = new ArrayList<>();
        List<Token> result = new ArrayList<>();
        for (Token lineTok : lines) {
            List<Token> tokens = lineTok.childs;
            int idx = firstNonWhitespaceIndex(tokens);
            if (idx >= 0 && tokens.get(idx).type == TokenType.OPERATOR && tokens.get(idx).text.equals("@")) {
                if (isPubBlockShape(tokens, idx)) {
                    // "@pub{...}" group: unwrap the block, mark each member isPubBlockMember
                    // (see docs/Parser.md, "Decorator lines")
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

    /** True when the line is exactly "@pub" followed by a trailing '{' (not "@pub(...)"). */
    public boolean isPubBlockShape(List<Token> tokens, int idx) {
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

    public void scanForBlockLinesDecorators(List<Token> flat) {
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
    public Token.Decorator parseDecoratorLine(List<Token> tokens, int idx, Token lineTok) {
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
     * Parses "@lock(match ...)": enum style ("match self.f : A|B") or "in" style ("match i in self.f [and ...]").
     * See docs/Parser.md, "@lock(match) qualifiers".
     */
    public Token.Decorator parseLockMatchQualifier(List<Token> t, int i, Token nameTok, Token lineTok) {
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

    public Token.Decorator parseLockEnumQualifier(List<Token> t, int i, Token lineTok) {
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

    /** "in"-style clauses: PARAM (in|within|into) self.FIELD, chained with 'and'; operators kept per clause. */
    public Token.Decorator parseLockInQualifier(List<Token> t, int i, Token lineTok) {
        List<String> params = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        List<Boolean> someWrapped = new ArrayList<>();
        while (true) {
            if (i >= t.size() || t.get(i).type != TokenType.VARREF) {
                throw new CompilerException("parse", lineTok.file, lineTok.line,
                        "'@lock(match ...)' expects a parameter name");
            }
            // "Some(i)" wraps the real parameter name
            boolean wrapped = false;
            String paramName;
            if (t.get(i).text.equals("Some")) {
                int probe = skipWs(t, i + 1);
                if (probe < t.size() && t.get(probe).type == TokenType.DELINEATOR && t.get(probe).text.equals("(")) {
                    // nest() already moved the parenthesised content into this token's childs
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

    public int skipWs(List<Token> t, int i) {
        while (i < t.size() && t.get(i).type == TokenType.WHITESPACE) {
            i++;
        }
        return i;
    }

    /** Decorator names/args are bare words; keywords are accepted too (separate namespace). */
    public boolean isDecoratorWord(Token t) {
        return t.type == TokenType.VARREF || t.type == TokenType.KEYWORD;
    }

    // ---- 3. let merge -------------------------------------------------

    /** let x = ... : "let" absorbs the variable name token into its own childs. */
    public void mergeLetInLine(Token lineTok) {
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
        // "let const" / "let static const": 'const' stays a KEYWORD (the top-level const declaration owns the
        // word); position disambiguates
        boolean isConst = false;
        if (j < tokens.size() && tokens.get(j).type == TokenType.KEYWORD && tokens.get(j).text.equals("const")) {
            isConst = true;
            j++;
            while (j < tokens.size() && tokens.get(j).type == TokenType.WHITESPACE) {
                j++;
            }
        }
        // "let:<Type> name" / "let static:<Type> name"; runs before whitespace removal
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

    public int firstNonWhitespaceIndex(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).type != TokenType.WHITESPACE) {
                return i;
            }
        }
        return -1;
    }

    // ---- 4. import collapse -------------------------------------------

    /** import "path" : whole line collapses to a single token, comments preserved. */
    public void collapseImportInLine(Token lineTok) {
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

    public void removeWhitespaceInLines(List<Token> lines) {
        for (Token lineTok : lines) {
            removeWhitespaceInFlat(lineTok.childs);
        }
    }

    public void removeWhitespaceInFlat(List<Token> flat) {
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
                // '(' and '[' bodies are one expression: leftover TERMINATORs are noise
                removeTerminatorsInFlat(t.childs);
                removeWhitespaceInFlat(t.childs);
            }
        }
    }

    public void removeTerminatorsInFlat(List<Token> flat) {
        for (int i = flat.size() - 1; i >= 0; i--) {
            if (flat.get(i).type == TokenType.TERMINATOR) {
                flat.remove(i);
            }
        }
    }

    // ---- 5.1 "then" pseudo-operator merging ("&&then"/"||then") -----------

    /**
     * Merges OPERATOR "&&"/"||" followed by VARREF "then" into one "&&then"/"||then" operator token.
     * Must run before insertCallLookupInLines ("&&then(x)" would read as a call to "then").
     * See docs/Parser.md, "&&then / ||then".
     */
    public void mergeThenOperators(List<Token> lines) {
        for (Token lineTok : lines) {
            mergeThenInFlat(lineTok.childs);
        }
    }

    public void mergeThenInFlat(List<Token> flat) {
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

    public void collapseGenericInstantiationsInLines(List<Token> lines) {
        for (Token lineTok : lines) {
            collapseGenericInstantiationsInFlat(lineTok.childs);
        }
    }

    /**
     * Collapses "VARREF ':<' ... '>'" spans into one VARREF token carrying `.genericArgs`.
     * Skips struct/enum/interface/type/extern/export/ASM lines and func/impl/library headers
     * (declared-type contexts, never expressions). See docs/Parser.md, "Generic instantiation (':<...>')".
     */
    public void collapseGenericInstantiationsInFlat(List<Token> flat) {
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

    /** flat[i] is the base VARREF, flat[i+1] the ":<" token: collapses the whole span (nested spans recursively) into one token in place. */
    public void collapseOneGenericSpan(List<Token> flat, int i) {
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

    /** Splits on top-level ',' only (tracks ':<'/'>' depth so a nested generic's commas are not separators). */
    public List<List<Token>> splitTopLevelCommaGroups(List<Token> tokens) {
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

    /**
     * Parses an optional bare "<Name[: Bound], ...>" declaration-site type-parameter list at tokens[idx]
     * (header positions only, so a bare '<' is unambiguous). See docs/Parser.md, "Type-parameter lists".
     */
    public TypeParamParseResult parseOptionalTypeParams(List<Token> tokens, int idx) {
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

    /** Parses an optional bare "<TypeExpr, ...>" type-argument usage in a header position, e.g. the "List<T>" of "impl<T> List<T>{...}". */
    public TypeArgParseResult parseOptionalTypeArgsRaw(List<Token> tokens, int idx) {
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
    public List<List<Token>> splitTopLevelCommaGroupsBare(List<Token> tokens) {
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

    public void insertCallLookupInLines(List<Token> lines) {
        for (Token lineTok : lines) {
            insertCallLookupInFlat(lineTok.childs);
        }
    }

    /**
     * Inserts CALL / LOOKUP marker operators. Never scans declared-type contexts (struct/enum/interface/
     * type/extern/export/ASM lines, func headers): "u64[50]" there is a type, not a subscript.
     * See docs/Parser.md, "CALL / LOOKUP insertion".
     */
    public void insertCallLookupInFlat(List<Token> flat) {
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
        // "static func ..." header: same never-an-expression rule as a bare func (else "origin(" gets a CALL marker)
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
            // "const NAME EXPR": NAME is never a call target
            if (i == 1 && flat.get(0).type == TokenType.KEYWORD && flat.get(0).text.equals("const")
                    && t.type == TokenType.VARREF) {
                continue;
            }
            // "sleep(...)" is a KEYWORD but still needs the CALL marker (TypeChecker.checkCall handles it)
            boolean isSleepKeyword = t.type == TokenType.KEYWORD && t.text.equals("sleep");
            if (isSleepKeyword && i + 1 < flat.size() && flat.get(i + 1).type == TokenType.DELINEATOR
                    && flat.get(i + 1).text.equals("(")) {
                flat.add(i + 1, new Token(TokenType.OPERATOR, "CALL", t.line, t.file));
                i++;
                continue;
            }
            // a '[...]' group is also a LOOKUP target (chained indexing "grid[1][1]")
            boolean tIsLookupTarget = t.type == TokenType.VARREF || t.type == TokenType.STRING
                    || (t.type == TokenType.DELINEATOR && t.text.equals("["));
            if (tIsLookupTarget && i + 1 < flat.size()) {
                Token next = flat.get(i + 1);
                if (t.type == TokenType.VARREF && next.type == TokenType.DELINEATOR && next.text.equals("(")) {
                    flat.add(i + 1, new Token(TokenType.OPERATOR, "CALL", t.line, t.file));
                    i++;
                } else if (next.type == TokenType.DELINEATOR && next.text.equals("[")) {
                    // a string literal followed by '[' is a LOOKUP target (its length is known at compile time)
                    flat.add(i + 1, new Token(TokenType.OPERATOR, "LOOKUP", t.line, t.file));
                    i++;
                }
            }
        }
        for (Token t : flat) {
            if (t.type == TokenType.TEMPLATE_STRING) {
                // recurse into "${...}" groups too, so "${f()}" gets a CALL marker and a clear constant-expression error
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
     * Collapses each "keyword [tokens] { block }" construct into one token, bottom-up.
     * Per-construct token shapes (.sub/.childs/.right): see docs/Parser.md, "Keyword-block token shapes".
     */
    /**
     * Entry point for TypeChecker: gathers a deep-cloned raw statement list (never at root), once per
     * "CLOSED:default" use site of an "impl default match @lock" template. See docs/Parser.md, "?catch and ?EXPR sugar".
     */
    public List<Token> gatherStatementLines(List<Token> lines) {
        return hub.blocks.gatherKeywordBlocks(lines, false);
    }
}

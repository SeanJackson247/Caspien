package caspien;

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
public class Parser {

    private static final String OPEN_CHARS = "([{";
    private static final String CLOSE_CHARS = ")]}";

    /** Most recent "?catch(e){..}" declaration (raw body); unscoped, reset per function in gatherFunc.
     *  See docs/Parser.md, "?catch and ?EXPR sugar". */
    private String pendingOptionalCatchParamName = null;
    private List<Token> pendingOptionalCatchRawBody = null;

    /** Returns the matching open character for a given close character, e.g. ')' -> '('. */
    private static char matchingOpen(char close) {
        return OPEN_CHARS.charAt(CLOSE_CHARS.indexOf(close));
    }

    /** Runs the whole parse pipeline; returns the file's top level as gathered LINE tokens. */
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

    /** Collapses the lexer's template-string marker sequence into one TEMPLATE_STRING token (recursive).
     *  See docs/Parser.md, "Template strings". */
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

    /** Keywords whose '{' ends the statement header; any other '{' (e.g. a struct literal) must not split the line. See docs/Parser.md, "Line splitting". */
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

    /** Groups a statement-sequence token list into LINE tokens (split on TERMINATOR and after a header-keyword '{'). */
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
     * Removes "@name" / "@name(args)" lines and attaches them as Decorator objects to the next line's
     * first token. Validity per construct is the TypeChecker's job. See docs/Parser.md, "Decorator lines".
     */
    private void stripDecoratorsDeep(List<Token> lines) {
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
     * Parses "@lock(match ...)": enum style ("match self.f : A|B") or "in" style ("match i in self.f [and ...]").
     * See docs/Parser.md, "@lock(match) qualifiers".
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

    /** "in"-style clauses: PARAM (in|within|into) self.FIELD, chained with 'and'; operators kept per clause. */
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

    private int skipWs(List<Token> t, int i) {
        while (i < t.size() && t.get(i).type == TokenType.WHITESPACE) {
            i++;
        }
        return i;
    }

    /** Decorator names/args are bare words; keywords are accepted too (separate namespace). */
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
                // '(' and '[' bodies are one expression: leftover TERMINATORs are noise
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
     * Merges OPERATOR "&&"/"||" followed by VARREF "then" into one "&&then"/"||then" operator token.
     * Must run before insertCallLookupInLines ("&&then(x)" would read as a call to "then").
     * See docs/Parser.md, "&&then / ||then".
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
     * Collapses "VARREF ':<' ... '>'" spans into one VARREF token carrying `.genericArgs`.
     * Skips struct/enum/interface/type/extern/export/ASM lines and func/impl/library headers
     * (declared-type contexts, never expressions). See docs/Parser.md, "Generic instantiation (':<...>')".
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

    /** flat[i] is the base VARREF, flat[i+1] the ":<" token: collapses the whole span (nested spans recursively) into one token in place. */
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

    /** Splits on top-level ',' only (tracks ':<'/'>' depth so a nested generic's commas are not separators). */
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
     * Parses an optional bare "<Name[: Bound], ...>" declaration-site type-parameter list at tokens[idx]
     * (header positions only, so a bare '<' is unambiguous). See docs/Parser.md, "Type-parameter lists".
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

    /** Parses an optional bare "<TypeExpr, ...>" type-argument usage in a header position, e.g. the "List<T>" of "impl<T> List<T>{...}". */
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
     * Inserts CALL / LOOKUP marker operators. Never scans declared-type contexts (struct/enum/interface/
     * type/extern/export/ASM lines, func headers): "u64[50]" there is a type, not a subscript.
     * See docs/Parser.md, "CALL / LOOKUP insertion".
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
        return gatherKeywordBlocks(lines, false);
    }

    private List<Token> gatherKeywordBlocks(List<Token> lines, boolean isRoot) {
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
                CatchClauseResult decl = gatherCatchClause(tokens, 1, "?catch(...) { ... }",
                        /* gatherBodyNow */ false);
                pendingOptionalCatchParamName = decl.paramName;
                pendingOptionalCatchRawBody = decl.body;
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
     * Gathers "try EXPR catch(e) { BLOCK }" into one synthetic "try" KEYWORD token (.sub = raw expr tokens, .childs = gathered catch body).
     * The span must run to the end of the line. See docs/Parser.md, "Try/catch span and catch clauses".
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
     * Parses one "catch(e) { ... }" clause that must end the line. With gatherBodyNow=false the body stays raw
     * (a "?catch" body is deep-cloned per use site). See docs/Parser.md, "Try/catch span and catch clauses".
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
     * Gathers an if/elseif/else chain (linked through .right); consumedOut[0] = lines consumed.
     * See docs/Parser.md, "if and match chains".
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

    /** Same as gatherIfChain for match/elsematch/else, plus the case-block form. See docs/Parser.md, "if and match chains". */
    private Token gatherMatchChain(List<Token> lines, int startIndex, int[] consumedOut) {
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

    /** True when `tokens` open a "match @lock EXPR{...}" statement; rejects the removed "lock x{...}" and "lock match" spellings. See docs/Parser.md, "match @lock". */
    private boolean isLockStatementStart(List<Token> tokens) {
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
    private Token gatherLockStatement(List<Token> lines, int startIndex, int[] consumedOut) {
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
     * Extracts one Token per case ("LABEL:{...}" or "A|B:{...}") from a case-block body, flattening the lines first.
     * See docs/Parser.md, "Case-match bodies".
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
            // Reset the pending "?catch" state here, the only reset site (gatherImpl and constructors route through gatherFunc).
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

    /** "extern name(params) ret" -- header-only form of gatherFunc; decorators (e.g. @call_convention) are validated by TypeChecker.collectExtern. See docs/Parser.md, "Declaration gatherers". */
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

    /** "export foo" -- a bare name; everything else is checked in TypeChecker.collectExport. */
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

    /** "ASM [name] { block }" or "ASM [name] \"path\"": the block arrives pre-scanned as an ASM_BLOCK token (Lexer.scanAsmBlock); the file form is validated by TypeChecker. */
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

    /** Parses an optional "implements A, B, ..." clause (a contract checked in TypeChecker). */
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

    /** Body for "struct"; a member line starting with "extends" is a parse error (inheritance was removed). */
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

    /** "type NAME <target tokens>" -- a one-liner; the raw target type tokens are read by TypeChecker. */
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
            // Like "if": .sub holds the name, .childs the raw expression tokens (processed like an if condition).
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

    /** Pre-order walk of a nested enum body: each variant gets start = running index, end = last index in its block (counter threaded by reference). */
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

    /** "interface Name{ sig; ... }" -- one "interface_method" token per signature; also "extends", "static" and default-method lines. See docs/Parser.md, "Interfaces". */
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

    /** "impl Iface for Concrete{...}" or "impl Concrete{...}"; methods go through gatherFunc directly (impl bodies are exempt from the isRoot check). .sub has 1 or 2 name tokens. */
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

    /** Overload-list key prefix for constructors in TypeChecker.functions; also an emitted symbol, so no "$" (invalid in assembly labels). Shared with TypeChecker. See docs/Parser.md, "Constructors". */
    static final String CONSTRUCTOR_NAME_PREFIX = "__ctor__";

    /** True right after "impl" for "impl [<T>] constructor for Name(...) self {...}" (an interface literally named "constructor" is the accepted collision). */
    private boolean isConstructorImplStart(List<Token> tokens) {
            // Peek only: parseOptionalTypeParams has no side effects.
        TypeParamParseResult implTypeParams = parseOptionalTypeParams(tokens, 1);
        int idx = implTypeParams.nextIndex;
        return tokens.size() > idx + 1
                && tokens.get(idx).type == TokenType.VARREF && tokens.get(idx).text.equals("constructor")
                && tokens.get(idx + 1).type == TokenType.KEYWORD && tokens.get(idx + 1).text.equals("for");
    }

    /**
     * Gathers a constructor declaration into a "func" token named CONSTRUCTOR_NAME_PREFIX + struct name (generic ones are wrapped in an "impl" token).
     * The return type must be written "self" and is replaced by the struct name. See docs/Parser.md, "Constructors".
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

        Token gathered = gatherFunc(synth, funcKw);
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
    private boolean isDefaultLockMatchImplStart(List<Token> tokens) {
        return tokens.size() > 4
                && tokens.get(1).type == TokenType.VARREF && tokens.get(1).text.equals("default")
                && tokens.get(2).type == TokenType.KEYWORD && tokens.get(2).text.equals("match")
                && tokens.get(3).type == TokenType.VARREF && tokens.get(3).text.equals("lock");
    }

    /**
     * "impl default match @lock T{ CLOSED:(params)=>{...} }" -- a policy template kept raw (TypeChecker deep-clones it per use site).
     * See docs/Parser.md, "Default lock policy".
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

    /** "library NAME<T?>{ func...; extends T; }" -- functions dispatched by library name, never through an instance. */
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

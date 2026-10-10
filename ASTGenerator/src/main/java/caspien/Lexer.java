package caspien;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 1: turns the raw source of a single file into a flat list of
 * tokens. Responsibilities owned by this stage (per project spec):
 *
 *   - context aware scan (mode: code / quoted-literal / sl comment / ml comment).
 *     ' " ` are treated identically by the scan itself -- it only knows
 *     it is "inside a quoted literal" and which character will close it.
 *   - escape processing inside quoted literals (shared by string and char)
 *   - buffering identifiers/keywords/modifiers/bools/null/integers
 *   - merging multi-character operators, float literals, and the
 *     else/if -> elseif / elif -> elseif normalization
 *   - a separate classification pass, run after the raw scan, that
 *     decides STRING vs CHAR for each quoted literal from its delimiter
 *     (' opens a char, " and ` open a string) and enforces that a char
 *     literal contains exactly one character -- this is deliberately
 *     NOT decided inline while scanning, since the raw scan only knows
 *     "same kind of literal, different closing character" until this
 *     later pass looks at the whole thing.
 *   - pinning comments to the nearest non-whitespace token
 *
 * NOT owned by this stage (left for the parser, which operates on this
 * stage's output): nesting of delineator children, splitting by line,
 * terminator removal, let-merging, import collapsing, whitespace
 * removal, CALL/LOOKUP insertion, keyword gathering, RPN conversion.
 * Whitespace tokens are therefore still present in this stage's output,
 * as is exactly one TERMINATOR token per logical line end / semicolon.
 */
public class Lexer {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "if", "else", "elseif", "elif", "loop", "struct", "enum",
            "import", "func", "let", "return", "break", "continue", "interface", "impl", "for",
            "extends", "type", "new", "match", "elsematch", "unsafe", "safe",
            "extern", "export", "ASM", "const", "par", "await", "yield", "sleep", "throw", "assume",
            "library", "atomic", "try", "catch"
    ));

    private static final Set<String> MODIFIERS = new HashSet<>(Arrays.asList(
            "mut", "imut", "owns", "ref", "raw", "auto", "static", "some"
    ));

    private static final Set<String> BOOLS = new HashSet<>(Arrays.asList("true", "false"));

    private static final Set<Character> SINGLE_CHAR_OPERATORS = new HashSet<>(Arrays.asList(
            '&', '|', '+', '-', '/', '%', '*', ',', '.', '<', '>', '!', '=', ':', '@', '?'
            // '?' -- the "?catch(e){...}"/"?EXPR" pseudo-operator (see
            // Parser.gatherKeywordBlocks). Purely a Parser-level macro:
            // both shapes are fully expanded away into ordinary
            // "try EXPR catch(e){...}" tokens before RpnConverter/
            // TreeBuilder ever run, so nothing past the Lexer/Parser
            // boundary ever needs to recognize '?' as an operator in its
            // own right -- it never reaches RPN conversion, tree
            // building, type-checking, or bytecode emission as itself.
    ));

    private static final Set<Character> DELINEATORS = new HashSet<>(Arrays.asList(
            '(', ')', '[', ']', '{', '}'
    ));

    private static final Set<String> MULTI_CHAR_OPERATORS = new HashSet<>(Arrays.asList(
            "&&", "||", "++", "--", "+=", "-=", "/=", "%=", "*=", "<=", ">=", "==", "!=", "..", ":<",
            "..." // the varargs placeholder, 'extern' declarations only -- merges in two passes
                  // through the same loop (".", "." -> ".." first, then ".." + "." -> "..."),
                  // already supported by mergeMultiCharOperators' own re-check-after-merge logic
            , "=>" // "impl default match @lock TypeName{ CLOSED: (params) => {...} }" --
                   // the default backoff/retry policy's own case-body arrow, the only
                   // place this token is ever legal.
    ));

    /**
     * Built-in numeric-limit constants -- "u64Max and u64Min, u32Max
     * and u32Min and so on... must be of their type," confirmed
     * directly, together with "for f32 these are highest and lowest
     * non-infinite and non-nan values." Reserved, globally, the exact
     * same way `__LINE`/`__FILENAME` already are: resolved the moment
     * each is lexed, into an ordinary INTEGER/FLOAT literal token that
     * every later stage already knows how to handle, rather than a new
     * kind of expression node. Unlike `__LINE`/`__FILENAME`, a bare
     * literal's own default base type/mutability
     * (`indeterminate u64`/`indeterminate f32`) isn't good enough here
     * -- each of these has to be concretely typed as its own exact
     * numeric type, and imut (never needing a `mut`/`imut` wrapper the
     * way an ordinary literal would) -- so the literal token this
     * builds also carries `forcedLiteralType` (see `Token.java`),
     * read back by `TypeChecker.resolveExprTypeInner`'s own
     * INTEGER/FLOAT cases to override the ordinary default.
     *
     * Every value here is written out as the literal's own exact,
     * final text -- including the sign, for a signed minimum -- never
     * built by negating a positive literal at lex time: the magnitude
     * of `s64Min` (9223372036854775808) itself overflows what a
     * positive `s64` literal could ever represent, so there's no
     * "positive literal, then negate" path that could produce it
     * correctly in the first place. Writing the final, already-signed
     * text directly (the same "lowering sets shape by hand" precedent
     * `evaluateConstantOperator`'s own unary-minus case already
     * established for `const` folding) sidesteps that entirely -- this
     * is plain text either way, never actually parsed back into a
     * Java numeric type anywhere in this compiler (no backend exists
     * yet to need one).
     *
     * `f32Max`/`f32Min` are IEEE-754 single-precision's own exact
     * finite bounds (`+-Float.MAX_VALUE`), written out to their full,
     * exact decimal expansion rather than scientific notation -- this
     * language's own float-literal syntax (`mergeFloats`) never
     * supports an exponent at all.
     */
    private static final Map<String, String[]> NUMERIC_LIMIT_CONSTANTS = new HashMap<>();
    static {
        // name -> { literal text, forced base type, "INTEGER"/"FLOAT" }
        NUMERIC_LIMIT_CONSTANTS.put("u8Max", new String[]{"255", "u8", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("u8Min", new String[]{"0", "u8", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("u16Max", new String[]{"65535", "u16", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("u16Min", new String[]{"0", "u16", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("u32Max", new String[]{"4294967295", "u32", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("u32Min", new String[]{"0", "u32", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("u64Max", new String[]{"18446744073709551615", "u64", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("u64Min", new String[]{"0", "u64", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("s8Max", new String[]{"127", "s8", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("s8Min", new String[]{"-128", "s8", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("s16Max", new String[]{"32767", "s16", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("s16Min", new String[]{"-32768", "s16", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("s32Max", new String[]{"2147483647", "s32", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("s32Min", new String[]{"-2147483648", "s32", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("s64Max", new String[]{"9223372036854775807", "s64", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("s64Min", new String[]{"-9223372036854775808", "s64", "INTEGER"});
        NUMERIC_LIMIT_CONSTANTS.put("f32Max",
                new String[]{"340282346638528859811704183484516925440.0", "f32", "FLOAT"});
        NUMERIC_LIMIT_CONSTANTS.put("f32Min",
                new String[]{"-340282346638528859811704183484516925440.0", "f32", "FLOAT"});
        NUMERIC_LIMIT_CONSTANTS.put("f64Max", new String[]{"1.7976931348623157e308", "f64", "FLOAT"});
        NUMERIC_LIMIT_CONSTANTS.put("f64Min", new String[]{"-1.7976931348623157e308", "f64", "FLOAT"});
    }

    private enum Mode { CODE, QUOTED_LITERAL, SL_COMMENT, ML_COMMENT, TEMPLATE_LITERAL }

    private final String src;
    private final String file;
    private int pos = 0;
    private int line = 1;
    private Mode mode = Mode.CODE;

    /** Which quote character opened the literal currently being scanned. */
    private char quoteDelimiter;

    /**
     * "let my_error_string = `Important Config file(${CONFIG_FILE})
     * not found on ${__FILENAME}:${__LINE}`," confirmed directly. Each
     * entry is the brace-nesting depth (starting at 1) of one
     * currently-open "${...}" segment -- pushed when "${" is seen
     * while in TEMPLATE_LITERAL mode (switching to CODE mode to
     * tokenize the expression normally, via the exact same scanCode
     * this file already uses for everything else -- no separate
     * expression scanner needed), incremented/decremented by scanCode
     * itself on any further '{'/'}' within that expression, and popped
     * -- switching mode back to TEMPLATE_LITERAL -- the moment it
     * returns to 0. A stack, not a single counter, so a template
     * string nested inside another template string's own "${...}"
     * (however unlikely to ever satisfy the compile-time-only
     * restriction '${...}' itself requires) doesn't corrupt the outer
     * one's own state.
     */
    private final java.util.Deque<Integer> templateExprBraceDepth = new java.util.ArrayDeque<>();

    private final List<Token> tokens = new ArrayList<>();
    private final StringBuilder buffer = new StringBuilder();
    private int bufferStartLine = 1;

    /**
     * Distinct from `file` -- the path actually used for reading/
     * resolving further imports, canonicalizing for cycle detection,
     * etc, which is a fully resolved, absolute filesystem path for any
     * imported file (`ImportResolver.resolveImport`'s own
     * `resolvedPathStr`), not a display-friendly one at all.
     * `displayFile` is what `__FILENAME` actually substitutes to --
     * "the target file should be stripped down to the base file name
     * and the other files referred to relativistically, using the same
     * style as for the import statements," confirmed directly: for the
     * top-level file being compiled, this is just its own base name
     * (no directory component, however the compiler was invoked); for
     * an imported file, this is the exact string written in the
     * "import "..."" statement that directly brought it in (never some
     * combined/nested path reflecting a deeper import chain -- each
     * import is resolved relative to its own immediate importer,
     * independently, the same way `ImportResolver.resolveImport`
     * itself already works, recursively, per import statement).
     */
    private final String displayFile;

    public Lexer(String source, String file, String displayFile) {
        this.src = source;
        this.file = file;
        this.displayFile = displayFile;
    }

    public List<Token> tokenize() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            switch (mode) {
                case CODE:
                    scanCode(c);
                    break;
                case QUOTED_LITERAL:
                    scanQuotedLiteral(c);
                    break;
                case TEMPLATE_LITERAL:
                    scanTemplateLiteral(c);
                    break;
                case SL_COMMENT:
                    scanSlComment(c);
                    break;
                case ML_COMMENT:
                    scanMlComment(c);
                    break;
            }
        }
        if (mode == Mode.QUOTED_LITERAL) {
            throw err("lex", "unterminated quoted literal");
        }
        if (mode == Mode.TEMPLATE_LITERAL || !templateExprBraceDepth.isEmpty()) {
            throw err("lex", "unterminated template literal (missing closing '`')");
        }
        if (mode == Mode.ML_COMMENT) {
            throw err("lex", "unterminated multi-line comment");
        }
        flushBuffer();

        classifyQuotedLiterals();
        mergeMultiCharOperators();
        mergeFloats();
        normalizeElseIf();
        pinComments();

        return tokens;
    }

    // ---- CODE mode -------------------------------------------------

    private void scanCode(char c) {
        if (c == '\n') {
            flushBuffer();
            emit(TokenType.TERMINATOR, "\n", line);
            line++;
            pos++;
            return;
        }
        if (c == ' ' || c == '\t' || c == '\r') {
            flushBuffer();
            int startLine = line;
            StringBuilder ws = new StringBuilder();
            while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t' || src.charAt(pos) == '\r')) {
                ws.append(src.charAt(pos));
                pos++;
            }
            emit(TokenType.WHITESPACE, ws.toString(), startLine);
            return;
        }
        if (c == ';') {
            flushBuffer();
            emit(TokenType.TERMINATOR, ";", line);
            pos++;
            return;
        }
        if (DELINEATORS.contains(c)) {
            flushBuffer();
            if (c == '{' && justSawAsmHeader()) {
                // "ASM [name] { ... }" -- the block's raw body is never
                // tokenized as ordinary Caspien code at all (real
                // assembly text routinely contains characters -- '%',
                // '$', ';', '#', etc. -- that the normal scanner can't
                // handle, or would silently misinterpret, e.g. ';' as a
                // TERMINATOR). Consumed and captured whole, right here,
                // before the ordinary scanner ever gets a chance to see
                // any of it.
                pos++; // consume the opening '{' itself
                scanAsmBlock();
                return;
            }
            if (!templateExprBraceDepth.isEmpty() && (c == '{' || c == '}')) {
                // Tracks nesting *within* a "${...}" segment's own
                // source text (e.g. a nested "{...}" the expression
                // itself contains) -- purely so this lexer knows when
                // the segment's own closing '}' has been reached, at
                // which point it switches back to TEMPLATE_LITERAL mode
                // instead of staying in CODE. Emits ')' in place of the
                // real '}' once the segment actually ends, pairing with
                // the synthesized '(' emitted for "${" above -- see that
                // comment for why '(' was chosen over '{' for the
                // token actually handed to the rest of the pipeline.
                // Any '{'/'}' at a deeper level than the segment's own
                // (a nested one the expression genuinely contains) is
                // passed through completely unchanged, real character
                // and all.
                int depth = templateExprBraceDepth.pop();
                depth += (c == '{') ? 1 : -1;
                if (depth == 0) {
                    emit(TokenType.DELINEATOR, ")", line);
                    pos++;
                    mode = Mode.TEMPLATE_LITERAL;
                    return;
                }
                templateExprBraceDepth.push(depth);
            }
            emit(TokenType.DELINEATOR, String.valueOf(c), line);
            pos++;
            return;
        }
        if (c == '"' || c == '\'') {
            flushBuffer();
            bufferStartLine = line;
            quoteDelimiter = c;
            pos++;
            mode = Mode.QUOTED_LITERAL;
            return;
        }
        if (c == '`') {
            // "let my_error_string = `Important Config file(${CONFIG_FILE})
            // not found on ${__FILENAME}:${__LINE}`," confirmed directly --
            // a backtick starts a template literal, a genuinely different
            // scanning mode from an ordinary quoted string (which never
            // interprets its own contents at all), not merely another
            // quote-delimiter choice.
            flushBuffer();
            emit(TokenType.OPERATOR, "TEMPLATE_STRING_START", line);
            bufferStartLine = line;
            pos++;
            mode = Mode.TEMPLATE_LITERAL;
            return;
        }
        if (c == '/' && peek(1) == '/') {
            flushBuffer();
            bufferStartLine = line;
            pos += 2;
            mode = Mode.SL_COMMENT;
            return;
        }
        if (c == '/' && peek(1) == '*') {
            flushBuffer();
            bufferStartLine = line;
            pos += 2;
            mode = Mode.ML_COMMENT;
            return;
        }
        if (SINGLE_CHAR_OPERATORS.contains(c)) {
            flushBuffer();
            emit(TokenType.OPERATOR, String.valueOf(c), line);
            pos++;
            return;
        }
        if (Character.isLetterOrDigit(c) || c == '_') {
            if (buffer.length() == 0) {
                bufferStartLine = line;
            }
            buffer.append(c);
            pos++;
            return;
        }
        throw err("lex", "unexpected character '" + c + "'");
    }

    // ---- ASM block raw scan --------------------------------------------

    /**
     * True if the '{' just encountered immediately follows either a
     * bare "ASM" keyword (unnamed block) or "ASM name" (named block) --
     * only whitespace tolerated in between, no comment or terminator
     * (matching this language's existing rule everywhere else that a
     * trailing '{' block must sit on the same line as its header, never
     * announced by skipping past an intervening comment/newline).
     * Looks backward over the already-emitted token stream rather than
     * needing any extra state field.
     */
    /**
     * True if the token just emitted, skipping whitespace, is a "."
     * operator -- used to contextually recognize "enum" as an accessor
     * ("X.enum") rather than the declaration keyword. Same backward-scan
     * shape as justSawAsmHeader, just against a single-token lookback.
     */
    private boolean justSawDotOperator() {
        int i = tokens.size() - 1;
        while (i >= 0 && tokens.get(i).type == TokenType.WHITESPACE) {
            i--;
        }
        return i >= 0 && tokens.get(i).type == TokenType.OPERATOR && tokens.get(i).text.equals(".");
    }

    private boolean justSawAsmHeader() {
        int i = tokens.size() - 1;
        while (i >= 0 && tokens.get(i).type == TokenType.WHITESPACE) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        Token last = tokens.get(i);
        if (last.type == TokenType.KEYWORD && last.text.equals("ASM")) {
            return true; // unnamed: "ASM {"
        }
        if (last.type == TokenType.VARREF) {
            int j = i - 1;
            while (j >= 0 && tokens.get(j).type == TokenType.WHITESPACE) {
                j--;
            }
            if (j >= 0 && tokens.get(j).type == TokenType.KEYWORD && tokens.get(j).text.equals("ASM")) {
                return true; // named: "ASM name {"
            }
        }
        return false;
    }

    /**
     * Called with `pos` already just past the block's own opening '{'
     * (already consumed by the caller, counted as depth 1 here). Copies
     * every character verbatim into the resulting ASM_BLOCK token's
     * text -- "no further parsing necessary, it's not checked for
     * correctness" -- except for exactly two things, both confirmed
     * directly: (1) nested '{'/'}' must stay balanced, tracked the
     * ordinary way except that any '{'/'}' appearing inside a quoted
     * literal or a comment doesn't count -- the same "aware of strings
     * and comments" carve-out real source scanning already needs, so
     * quoted literals and comments are each skipped over as a whole
     * unit, copied verbatim, without inspecting their contents any
     * further; (2) outside of those, a bare identifier-shaped token
     * spelling exactly "ASM_END" is illegal -- checked with a word-
     * boundary-aware scan (an identifier is exactly Character.isLetter/
     * isLetterOrDigit/'_', the same rule ordinary Caspien identifiers
     * use), so "ASM_END" only trips this when it stands alone, never as
     * a substring of some longer identifier like "MY_ASM_ENDING".
     */
    private void scanAsmBlock() {
        int startLine = line;
        int depth = 1;
        StringBuilder raw = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw err("lex", "unterminated 'ASM' block (missing closing '}')");
            }
            char c = src.charAt(pos);
            if (c == '"' || c == '`' || c == '\'') {
                copyAsmQuotedLiteralVerbatim(raw, c);
                continue;
            }
            if (c == '/' && peek(1) == '/') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    raw.append(src.charAt(pos));
                    pos++;
                }
                continue;
            }
            if (c == '/' && peek(1) == '*') {
                copyAsmBlockCommentVerbatim(raw);
                continue;
            }
            if (c == '{') {
                depth++;
                raw.append(c);
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                depth--;
                if (depth == 0) {
                    break; // this is the block's own matching close -- not appended to raw
                }
                raw.append(c);
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                StringBuilder ident = new StringBuilder();
                while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                    ident.append(src.charAt(pos));
                    pos++;
                }
                if (ident.toString().equals("ASM_END")) {
                    throw err("lex", "illegal use of the reserved identifier 'ASM_END' inside an 'ASM' block");
                }
                raw.append(ident);
                continue;
            }
            if (c == '\n') {
                line++;
            }
            raw.append(c);
            pos++;
        }
        Token t = new Token(TokenType.ASM_BLOCK, raw.toString(), startLine, file);
        t.literalValue = raw.toString();
        tokens.add(t);
    }

    /** Copies a whole ' " ` literal (opening delimiter through closing) verbatim into `raw`, including any escape sequences exactly as written -- not decoded, just passed through -- so its contents can never confuse the surrounding brace-balance/ASM_END scan. */
    private void copyAsmQuotedLiteralVerbatim(StringBuilder raw, char quote) {
        raw.append(quote);
        pos++;
        while (true) {
            if (pos >= src.length() || src.charAt(pos) == '\n') {
                throw err("lex", "unterminated quoted literal inside 'ASM' block");
            }
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < src.length()) {
                raw.append(c).append(src.charAt(pos + 1));
                pos += 2;
                continue;
            }
            raw.append(c);
            pos++;
            if (c == quote) {
                return;
            }
        }
    }

    /** Copies a whole "slash-star" block comment (already confirmed present via peek) verbatim into `raw`, through its own closing "star-slash", tracking newlines for correct error-reporting line numbers. */
    private void copyAsmBlockCommentVerbatim(StringBuilder raw) {
        raw.append('/').append('*');
        pos += 2;
        while (true) {
            if (pos >= src.length()) {
                throw err("lex", "unterminated block comment inside 'ASM' block");
            }
            char c = src.charAt(pos);
            if (c == '\n') {
                line++;
            }
            if (c == '*' && peek(1) == '/') {
                raw.append('*').append('/');
                pos += 2;
                return;
            }
            raw.append(c);
            pos++;
        }
    }

    private void flushBuffer() {
        if (buffer.length() == 0) {
            return;
        }
        String text = buffer.toString();
        buffer.setLength(0);

        String radixDecimal = text.isEmpty() || !isAsciiDigit(text.charAt(0)) ? null : normalizeIntegerLiteral(text);
        if (radixDecimal != null) {
            // 0xFF / 0XFF / 0b1010 / 1_000 / 0xFF_FF: normalised HERE to a plain decimal INTEGER token, so every later stage
            // (type checker, folder, emitter, the other modules) only ever sees ordinary decimal literal text.
            emit(TokenType.INTEGER, radixDecimal, bufferStartLine);
            if (text.length() >= 2 && text.charAt(0) == '0' && "xXbB".indexOf(text.charAt(1)) >= 0) {
                tokens.get(tokens.size() - 1).radixLiteral = true; // never merged with a following ".digits" into a float
            }
        } else if (isAllDigits(text)) {
            emit(TokenType.INTEGER, text, bufferStartLine);
        } else if (text.equals("as")) {
            // "as" is an infix cast operator (numeric widening, or an
            // alias cast) -- deliberately *not* mutability (confirmed
            // directly: "the as operator should only effect types, but
            // not mutability" -- see "mut"/"imut" below for that
            // instead). Not an identifier or a block keyword --
            // classified here rather than in KEYWORDS so it flows through
            // the same operator-precedence machinery as +, ==, etc.
            emit(TokenType.OPERATOR, text, bufferStartLine);
        } else if (text.equals("in")) {
            // "in" is an infix operator (u64, range) -> bool, same
            // reasoning as "as": classified as an operator, not a
            // keyword, so it flows through the ordinary shunting-yard
            // machinery. A for-loop's header ("for i in 0..n{") reuses
            // this exact same operator structurally -- see Parser's
            // gatherFor and TypeChecker's checkForLoop.
            emit(TokenType.OPERATOR, text, bufferStartLine);
        } else if (text.equals("within")) {
            // "within" is an infix operator (range, range)
            // -> bool, structurally identical to "in" (same "operator
            // not keyword" reasoning, same shunting-yard flow) but with
            // its own, narrower type rule (TypeChecker.checkWithin) and
            // its own runtime semantics (proper-subrange test) and
            // bytecode mnemonic (WITHIN, not IN) -- see checkWithin's
            // own doc. Unlike "in", never reused as a for-loop header.
            emit(TokenType.OPERATOR, text, bufferStartLine);
        } else if (text.equals("into")) {
            // "into" is an infix operator, structurally parallel to "in"
            // (same "operator not keyword" reasoning, same shunting-yard
            // flow, same underlying type rule -- reuses TypeChecker.checkIn
            // directly) but, unlike "in"/"within", never generally
            // available: it has no meaning as a bare expression at all
            // ("0 into 0..100" is rejected -- see resolveOperatorType's own
            // "into" case). Legal only as a bare match condition, a "for
            // match" loop header, or inside an "@lock(...)" qualifier --
            // in each of those three, it grants the same read-bounds proof
            // "in" does, plus a write-legalizing "into" proof for that
            // exact index expression, provided the target was already
            // "mut". Compiles to the identical "IN" bytecode "in" itself
            // does -- zero new mnemonics.
            emit(TokenType.OPERATOR, text, bufferStartLine);
        } else if (text.equals("fits")) {
            // "match x fits u8{...}" -- infix operator, same "operator not keyword"
            // reasoning as "is"/"in"; only meaningful as a match condition
            // (TypeChecker.checkMatchCondition), where it proves x's runtime
            // value is representable in the named integer type.
            emit(TokenType.OPERATOR, text, bufferStartLine);
        } else if (text.equals("is")) {
            // "match RANGE_PARAM is base{...}" -- an infix operator, same
            // "operator not keyword" reasoning as "in"/"as": only ever
            // meaningful inside a match condition (checked structurally
            // by TypeChecker.checkMatchCondition, same as "and" already
            // is), never a general-purpose operator elsewhere.
            emit(TokenType.OPERATOR, text, bufferStartLine);
        } else if (text.equals("instanceof") || text.equals("implements")) {
            // Runtime type-check operators (interface-typed value, StructName) ->
            // bool ("instanceof") and (struct-typed value, InterfaceName) ->
            // bool ("implements") -- same "operator not keyword" reasoning
            // as "as"/"in", so both flow through the ordinary
            // shunting-yard machinery. "implements" is *also* reused,
            // unrelated to this operator use, as a struct header
            // clause keyword ("struct X implements Y{...}") -- Parser's
            // header-scanning code matches on the token's text directly
            // there, so classifying it as OPERATOR here doesn't conflict.
            emit(TokenType.OPERATOR, text, bufferStartLine);
        } else if (text.equals("and")) {
            // Match-condition chaining only, confirmed directly: compiles
            // to the exact same bytecode as "&&", but is kept as its own
            // distinct operator (not simply rewritten to "&&" during
            // parsing) so TypeChecker.checkMatch can walk an "and"-chain
            // structurally and record each side as its own separate
            // match pattern -- "&&" itself carries no such meaning
            // outside a match condition, and is never conflated with it.
            emit(TokenType.OPERATOR, text, bufferStartLine);
        } else if (text.equals("dup")) {
            // "dup" is a compile-time-only array-literal-element
            // operator (see TypeChecker.checkArrayLiteral), same
            // "operator not keyword" reasoning as "as"/"in".
            emit(TokenType.OPERATOR, text, bufferStartLine);
        } else if (text.equals("swap")) {
            // "myAtomicChar swap '\0'" -- an infix operator, atomic
            // read-modify-write: reads the left (atomic) operand's
            // current value, atomically stores the right operand as
            // its new value, and resolves to the value that was there
            // a moment ago. Same "operator not keyword" reasoning as
            // "as"/"in" -- classified here so it flows through the
            // ordinary shunting-yard machinery rather than needing its
            // own RpnConverter promotion the way a unary prefix
            // operator ("atomic" itself, "new", "await") would.
            emit(TokenType.OPERATOR, text, bufferStartLine);
        } else if (text.equals("__LINE")) {
            // "the line number and file name of the current tokens
            // being parsed," confirmed directly -- resolved here, the
            // moment the token is lexed, using the lexer's own current
            // line, rather than deferred to any later pass at all
            // (parsing, type-checking, bytecode emission). This means
            // every later stage sees nothing but an ordinary INTEGER
            // literal -- no new keyword-dispatch logic needed anywhere
            // else in the whole compiler, and it's usable anywhere any
            // other literal already is (a const's own expression, a
            // global initializer, a struct-literal default, deep
            // inside an ordinary function body), the same way C's own
            // __LINE__/__FILE__ macros are, being resolved before any
            // real compilation begins at all.
            tokens.add(new Token(TokenType.INTEGER, Integer.toString(bufferStartLine), bufferStartLine, file));
        } else if (text.equals("__FS_ROOTS")) {
            // the run-time table of fs-roots (see FsPolicy.runtimeRootsText), an ordinary string literal
            String roots = FsPolicy.current.runtimeRootsText();
            Token rootsTok = new Token(TokenType.STRING, roots, bufferStartLine, file);
            rootsTok.literalValue = roots;
            rootsTok.quoteDelimiter = '"';
            tokens.add(rootsTok);
        } else if (text.equals("__FILENAME")) {
            // "a C style static string, encaps with "" as in
            // "main.caspien"," confirmed directly -- an ordinary STRING
            // literal token, built directly (not run back through the
            // quote-scanning path at all, since there's no real source
            // text to re-scan) with the exact same fields a real
            // quoted string literal ends up with (`literalValue`, the
            // unescaped value; `quoteDelimiter`, so downstream code
            // that inspects it sees a completely ordinary string).
            // Uses `displayFile`, never `file` -- "the target file
            // should be stripped down to the base file name and the
            // other files referred to relativistically, using the same
            // style as for the import statements," confirmed directly;
            // `file` itself is the real, fully-resolved filesystem path
            // this lexer instance was actually constructed with
            // (needed for everything else -- reading, import cycle
            // detection, error-message file reporting), which is a
            // different thing entirely for an imported file.
            Token strTok = new Token(TokenType.STRING, displayFile, bufferStartLine, file);
            strTok.literalValue = displayFile;
            strTok.quoteDelimiter = '"';
            tokens.add(strTok);
        } else if (NUMERIC_LIMIT_CONSTANTS.containsKey(text)) {
            // Built-in numeric-limit constant (u64Max, s8Min, f32Max,
            // ...) -- see NUMERIC_LIMIT_CONSTANTS's own doc comment.
            // Resolved here, unconditionally, the same "globally
            // reserved, checked before the ordinary identifier
            // dispatch" treatment __LINE/__FILENAME already get.
            String[] spec = NUMERIC_LIMIT_CONSTANTS.get(text);
            TokenType litType = spec[2].equals("FLOAT") ? TokenType.FLOAT : TokenType.INTEGER;
            Token litTok = new Token(litType, spec[0], bufferStartLine, file);
            litTok.forcedLiteralType = spec[1];
            tokens.add(litTok);
        } else if (text.equals("enum") && justSawDotOperator()) {
            // "MyInterface.enum" / "ParentClass.enum" -- the accessor for
            // a compiler-synthesized "enum for X" membership enum (see
            // TypeChecker.generateEnumForEnums), legal only inside a
            // match condition's instanceof/implements position (enforced
            // entirely in TypeChecker.checkMatchCondition/checkDot, not
            // here). "enum" is ordinarily a KEYWORD (a declaration
            // header), but immediately after a "." operator token it can
            // only ever be this accessor -- an ordinary "enum{...}"
            // declaration never follows a "." -- so it's lexed as a
            // plain VARREF here, the identical "contextual, not globally
            // reserved" treatment "default"/"finite"/"infinite"/"nan"
            // already get, letting it flow through the ordinary "."
            // dot-access RPN/tree pipeline as an unremarkable member name.
            emit(TokenType.VARREF, text, bufferStartLine);
        } else if (KEYWORDS.contains(text)) {
            emit(TokenType.KEYWORD, text, bufferStartLine);
        } else if (MODIFIERS.contains(text)) {
            emit(TokenType.MODIFIER, text, bufferStartLine);
        } else if (BOOLS.contains(text)) {
            emit(TokenType.BOOL, text, bufferStartLine);
        } else if (text.equals("null")) {
            emit(TokenType.NULL, text, bufferStartLine);
        } else {
            emit(TokenType.VARREF, text, bufferStartLine);
        }
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int digitValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return 99;
    }

    /**
     * An integer literal spelled with a radix prefix or digit separators, as a plain decimal string; null when `text` is not
     * shaped like one (it then goes through the old classification untouched: an all-digit run is an INTEGER, anything else
     * an identifier). `0x`/`0X` hexadecimal and `0b`/`0B` binary; `_` may separate digits ("1_000_000", "0xFF_FF", never
     * leading, trailing or doubled, and never directly after the prefix). A malformed one is a lex error. The value is
     * arbitrary precision here: whether it fits a type is decided later by the same checks a decimal literal gets
     * ("0xFFFFFFFFFFFFFFFF" is 18446744073709551615, a u64).
     */
    private String normalizeIntegerLiteral(String text) {
        int radix = 10;
        int start = 0;
        String kind = "decimal";
        if (text.length() >= 2 && text.charAt(0) == '0' && (text.charAt(1) == 'x' || text.charAt(1) == 'X')) {
            radix = 16;
            start = 2;
            kind = "hexadecimal";
        } else if (text.length() >= 2 && text.charAt(0) == '0' && (text.charAt(1) == 'b' || text.charAt(1) == 'B')) {
            radix = 2;
            start = 2;
            kind = "binary";
        }
        if (radix == 10) {
            // decimal: only shaped like a literal when every character is a digit or '_' (otherwise an identifier such as "3d")
            boolean sawUnderscore = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '_') {
                    sawUnderscore = true;
                } else if (!isAsciiDigit(c)) {
                    return null;
                }
            }
            if (!sawUnderscore) {
                return null;
            }
        }
        String body = text.substring(start);
        if (body.isEmpty()) {
            throw err("lex", "malformed " + kind + " literal '" + text + "': no digits after the '" + text + "' prefix");
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '_') {
                if (i == 0 || i == body.length() - 1 || body.charAt(i - 1) == '_') {
                    throw err("lex", "malformed numeric literal '" + text + "': '_' must sit between two digits");
                }
                continue;
            }
            if (digitValue(c) >= radix) {
                throw err("lex", "malformed " + kind + " literal '" + text + "': '" + c + "' is not a valid "
                        + (radix == 16 ? "hexadecimal" : radix == 2 ? "binary" : "decimal") + " digit");
            }
            digits.append(c);
        }
        return new java.math.BigInteger(digits.toString(), radix).toString();
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return s.length() > 0;
    }

    // ---- quoted literal mode (string and char both scanned identically) ----

    /**
     * Scans the body of any ' " ` literal identically -- collects raw
     * characters with escape processing, and closes on the matching
     * delimiter. Deliberately does NOT decide STRING vs CHAR here, and
     * does NOT enforce any length constraint: that's the job of
     * classifyQuotedLiterals(), which runs once the whole file has been
     * scanned.
     */
    private void scanQuotedLiteral(char c) {
        if (c == '\n') {
            throw err("lex", "unterminated quoted literal (newline before closing " + quoteDelimiter + ")");
        }
        if (c == quoteDelimiter) {
            Token t = new Token(TokenType.QUOTED_LITERAL, buffer.toString(), bufferStartLine, file);
            t.literalValue = buffer.toString();
            t.quoteDelimiter = quoteDelimiter;
            tokens.add(t);
            buffer.setLength(0);
            pos++;
            mode = Mode.CODE;
            return;
        }
        if (c == '\\') {
            buffer.append(readEscape());
            return;
        }
        buffer.append(c);
        pos++;
    }

    /**
     * Scans the literal-text portion of a backtick template string --
     * everything except a "${...}" segment (handled by switching into
     * ordinary CODE mode instead, see below) and the closing backtick
     * itself.
     */
    private void scanTemplateLiteral(char c) {
        if (c == '`') {
            flushTemplateStringPart();
            emit(TokenType.OPERATOR, "TEMPLATE_STRING_END", line);
            pos++;
            mode = Mode.CODE;
            return;
        }
        if (c == '$' && peek(1) == '{') {
            flushTemplateStringPart();
            emit(TokenType.OPERATOR, "TEMPLATE_EXPR_START", line);
            pos += 2; // consume "${"
            // Emits '(' rather than '{' -- '(' is already a fully-
            // supported expression-grouping delineator throughout the
            // rest of the pipeline (RpnConverter/TreeBuilder), while
            // '{' is not (it's used for blocks/struct literals, never
            // for "(expr)"-style grouping) -- reusing it here avoids
            // building a second, parallel expression-grouping path
            // just for this. The *source*-level '{'/'}' nesting is
            // still tracked independently, in scanCode below, purely to
            // know when this segment's own source text ends -- entirely
            // unrelated to whichever real '('/')' the expression itself
            // might separately contain.
            emit(TokenType.DELINEATOR, "(", line);
            templateExprBraceDepth.push(1);
            mode = Mode.CODE;
            return;
        }
        if (c == '\n') {
            throw err("lex", "unterminated template literal (newline before closing '`')");
        }
        if (c == '\\') {
            buffer.append(readEscape());
            return;
        }
        buffer.append(c);
        pos++;
    }

    /** Flushes whatever literal text has accumulated in `buffer` as a TEMPLATE_STRING_PART token -- called both between two "${...}" segments and right before the closing backtick, so an empty piece (two interpolations back to back, or an interpolation right at the very start/end) still produces a token, keeping every segment boundary explicit rather than needing special-casing later. */
    private void flushTemplateStringPart() {
        Token t = new Token(TokenType.OPERATOR, "TEMPLATE_STRING_PART", bufferStartLine, file);
        t.literalValue = buffer.toString();
        tokens.add(t);
        buffer.setLength(0);
        bufferStartLine = line;
    }

    /** pos is at the backslash on entry; returns the decoded character(s) and advances pos past the escape. */
    private String readEscape() {
        pos++; // consume backslash
        if (pos >= src.length()) {
            throw err("lex", "unterminated escape sequence");
        }
        char e = src.charAt(pos);
        switch (e) {
            case '\\': pos++; return "\\";
            case '\'': pos++; return "'";
            case '"':  pos++; return "\"";
            case '`':  pos++; return "`";
            case '$':  pos++; return "$";
            case 'n':  pos++; return "\n";
            case 't':  pos++; return "\t";
            case 'r':  pos++; return "\r";
            case '0':  pos++; return "\0";
            case 'b':  pos++; return "\b";
            case 'f':  pos++; return "\f";
            case 'a':  pos++; return "\u0007";
            case 'v':  pos++; return "\u000B";
            case 'x': {
                pos++;
                String hex = readHexDigits(2);
                return String.valueOf((char) Integer.parseInt(hex, 16));
            }
            case 'u': {
                pos++;
                String hex = readHexDigits(4);
                return String.valueOf((char) Integer.parseInt(hex, 16));
            }
            default:
                throw err("lex", "unknown escape sequence '\\" + e + "'");
        }
    }

    private String readHexDigits(int count) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (pos >= src.length() || Character.digit(src.charAt(pos), 16) == -1) {
                throw err("lex", "expected " + count + " hex digits in escape sequence");
            }
            hex.append(src.charAt(pos));
            pos++;
        }
        return hex.toString();
    }

    // ---- comments -----------------------------------------------------

    private void scanSlComment(char c) {
        if (c == '\n') {
            Token t = new Token(TokenType.SL_COMMENT, buffer.toString(), bufferStartLine, file);
            tokens.add(t);
            buffer.setLength(0);
            mode = Mode.CODE;
            // do not consume the newline here; let CODE mode emit its TERMINATOR
            return;
        }
        buffer.append(c);
        pos++;
        if (pos >= src.length()) {
            Token t = new Token(TokenType.SL_COMMENT, buffer.toString(), bufferStartLine, file);
            tokens.add(t);
            buffer.setLength(0);
            mode = Mode.CODE;
        }
    }

    private void scanMlComment(char c) {
        if (c == '*' && peek(1) == '/') {
            Token t = new Token(TokenType.ML_COMMENT, buffer.toString(), bufferStartLine, file);
            tokens.add(t);
            buffer.setLength(0);
            pos += 2;
            mode = Mode.CODE;
            return;
        }
        if (c == '\n') {
            line++;
        }
        buffer.append(c);
        pos++;
    }

    // ---- merge passes ---------------------------------------------------

    /**
     * Second stage of string/char handling: the raw scan treats every
     * ' " ` literal identically and only records which delimiter opened
     * it. This pass looks at that delimiter for each literal and decides
     * the real token type -- a single quote makes a CHAR (which must be
     * exactly one character), " and ` make a STRING (no length limit).
     */
    private void classifyQuotedLiterals() {
        for (Token t : tokens) {
            if (t.type != TokenType.QUOTED_LITERAL) {
                continue;
            }
            if (t.quoteDelimiter == '\'') {
                if (t.literalValue.length() != 1) {
                    throw new CompilerException("lex", t.file, t.line,
                            "char literal must contain exactly one character, got "
                                    + t.literalValue.length() + " (\"" + t.literalValue + "\")");
                }
                t.type = TokenType.CHAR;
            } else {
                t.type = TokenType.STRING;
            }
        }
    }

    private void mergeMultiCharOperators() {
        for (int i = 0; i < tokens.size() - 1; i++) {
            Token a = tokens.get(i);
            Token b = tokens.get(i + 1);
            if (a.type == TokenType.OPERATOR && b.type == TokenType.OPERATOR
                    && a.line == b.line) {
                String combo = a.text + b.text;
                if (MULTI_CHAR_OPERATORS.contains(combo)) {
                    a.text = combo;
                    tokens.remove(i + 1);
                    i--; // re-check in case of further combos (defensive; none are 3+ chars today)
                }
            }
        }
    }

    private void mergeFloats() {
        for (int i = 0; i < tokens.size() - 2; i++) {
            Token a = tokens.get(i);
            Token dot = tokens.get(i + 1);
            Token b = tokens.get(i + 2);
            if (a.type == TokenType.INTEGER && !a.radixLiteral && dot.type == TokenType.OPERATOR && dot.text.equals(".")
                    && b.type == TokenType.INTEGER && a.line == dot.line && dot.line == b.line) {
                a.type = TokenType.FLOAT;
                a.text = a.text + "." + b.text;
                tokens.remove(i + 2);
                tokens.remove(i + 1);
            }
        }
    }

    private void normalizeElseIf() {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type == TokenType.KEYWORD && t.text.equals("elif")) {
                t.text = "elseif";
                continue;
            }
            if (t.type == TokenType.KEYWORD && t.text.equals("else")) {
                int j = i + 1;
                while (j < tokens.size() && tokens.get(j).type == TokenType.WHITESPACE) {
                    j++;
                }
                if (j < tokens.size() && tokens.get(j).type == TokenType.KEYWORD
                        && tokens.get(j).text.equals("if") && tokens.get(j).line == t.line) {
                    t.text = "elseif";
                    for (int k = j; k >= i + 1; k--) {
                        tokens.remove(k);
                    }
                    continue;
                }
                // "one can use either elsematch or else match," confirmed
                // directly -- same "else" + adjacent keyword, same-line
                // collapse mechanism as "else if"/"elseif" just above,
                // mirrored exactly rather than built as a separate
                // mechanism.
                if (j < tokens.size() && tokens.get(j).type == TokenType.KEYWORD
                        && tokens.get(j).text.equals("match") && tokens.get(j).line == t.line) {
                    t.text = "elsematch";
                    for (int k = j; k >= i + 1; k--) {
                        tokens.remove(k);
                    }
                }
            }
        }
    }

    /** Validates a doc comment (`/*! key: text *&#47;` or `//! key: text`) and registers it under the keyword it labels. See {@link DocComments}. */
    private void registerDocComment(int idx, Token c) {
        int cl = c.line;
        String body = c.text.substring(1).trim().replaceAll("\\s+", " ");
        int colon = body.indexOf(':');
        if (colon <= 0) {
            throw new CompilerException("lex", file, cl, "doc comment must read '" + (c.type == TokenType.ML_COMMENT ? "/*! " : "//! ")
                    + "key: text' (keys: justify, termination)");
        }
        String key = body.substring(0, colon).trim();
        String value = body.substring(colon + 1).trim();
        if (!key.equals("justify") && !key.equals("termination")) {
            throw new CompilerException("lex", file, cl, "unknown doc comment key '" + key + "' (keys: justify, termination)");
        }
        if (value.isEmpty()) {
            throw new CompilerException("lex", file, cl, "doc comment '" + key + ":' has no text");
        }
        if (key.equals("termination") && !DocComments.CLASSES.contains(value)) {
            throw new CompilerException("lex", file, cl, "termination class '" + value + "' is not one of " + String.join(" | ", DocComments.CLASSES));
        }
        // the keyword it labels: the next real token, past any decorator lines (`@name ...` up to the end of its line)
        int j = idx + 1;
        Token target = null;
        while (j < tokens.size()) {
            Token x = tokens.get(j);
            if (isDiscardable(x)) {
                j++;
                continue;
            }
            if (x.type == TokenType.OPERATOR && x.text.equals("@")) {
                while (j < tokens.size() && tokens.get(j).type != TokenType.TERMINATOR) {
                    j++;
                }
                continue;
            }
            target = x;
            break;
        }
        String kind = target == null ? null : target.text;
        if (key.equals("termination") && "match".equals(kind)) {
            // `match @lock x{ .. }` is lowered to a spin `loop`, so it takes the label of a loop (and nothing else called `match` does)
            int k = j + 1;
            while (k < tokens.size() && isDiscardable(tokens.get(k))) {
                k++;
            }
            int k2 = k + 1;
            while (k2 < tokens.size() && isDiscardable(tokens.get(k2))) {
                k2++;
            }
            if (k2 < tokens.size() && tokens.get(k).text.equals("@") && tokens.get(k2).text.equals("lock")) {
                kind = "loop";
            }
        }
        boolean ok = key.equals("justify") ? "unsafe".equals(kind)
                : "loop".equals(kind) || "for".equals(kind) || "func".equals(kind);
        if (!ok) {
            throw new CompilerException("lex", file, cl, "'" + key + ":' doc comment is not expected here: it goes directly before "
                    + (key.equals("justify") ? "an `unsafe` block" : "a `loop`, `for`, `func` or `match @lock`")
                    + (target == null ? " (nothing follows it)" : " (found `" + kind + "` on line " + target.line + ")"));
        }
        if (!DocComments.register(new DocComments.Doc(key, value, file, kind, cl, target.line))) {
            throw new CompilerException("lex", file, cl, "'" + key + ":' appears twice before the same `" + kind + "`");
        }
    }

    private void pinComments() {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type != TokenType.SL_COMMENT && t.type != TokenType.ML_COMMENT) {
                continue;
            }
            if (t.text.startsWith("!")) {
                registerDocComment(i, t);
                tokens.remove(i);
                i--;
                continue;
            }
            Token target = findNextRealToken(i);
            if (target == null) {
                target = findPrevRealToken(i);
            }
            if (target != null) {
                target.pinnedComments.add(t);
            }
            tokens.remove(i);
            i--;
        }
    }

    /**
     * True for token kinds that don't survive into the parser's output
     * (whitespace, other comments, terminators consumed by line-splitting,
     * and closing delineators consumed by nesting) -- pinning a comment to
     * one of these would silently lose the comment once that token is
     * discarded downstream.
     */
    private static boolean isDiscardable(Token t) {
        if (t.type == TokenType.WHITESPACE || t.type == TokenType.SL_COMMENT
                || t.type == TokenType.ML_COMMENT || t.type == TokenType.TERMINATOR) {
            return true;
        }
        if (t.type == TokenType.DELINEATOR) {
            char c = t.text.charAt(0);
            return c == ')' || c == ']' || c == '}';
        }
        return false;
    }

    private Token findNextRealToken(int fromIndex) {
        for (int j = fromIndex + 1; j < tokens.size(); j++) {
            Token c = tokens.get(j);
            if (!isDiscardable(c)) {
                return c;
            }
        }
        return null;
    }

    private Token findPrevRealToken(int fromIndex) {
        for (int j = fromIndex - 1; j >= 0; j--) {
            Token c = tokens.get(j);
            if (!isDiscardable(c)) {
                return c;
            }
        }
        return null;
    }

    // ---- helpers --------------------------------------------------------

    private char peek(int ahead) {
        int p = pos + ahead;
        if (p >= src.length()) {
            return '\0';
        }
        return src.charAt(p);
    }

    private void emit(TokenType type, String text, int atLine) {
        tokens.add(new Token(type, text, atLine, file));
    }

    private CompilerException err(String stage, String message) {
        return new CompilerException(stage, file, line, message);
    }
}

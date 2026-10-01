package caspien;

/**
 * The complete set of token types produced by the lexer stage.
 * WHITESPACE is an internal/intermediate type used during lexing and
 * merging; it is stripped before the lexer hands tokens off to later
 * stages, so downstream code should never see it.
 */
public enum TokenType {
    STRING,
    CHAR,
    /** Internal only: produced by the raw scan for any of ' " ` before
     *  the classification pass decides STRING vs CHAR. Never present
     *  in the token list the lexer returns. */
    QUOTED_LITERAL,
    ML_COMMENT,
    SL_COMMENT,
    TERMINATOR,
    /** Internal only, introduced by the parser: wraps one statement's
     *  tokens (childs) after splitting a block/file by terminators. */
    LINE,
    DELINEATOR,
    OPERATOR,
    INTEGER,
    FLOAT,
    VARREF,
    KEYWORD,
    BOOL,
    NULL,
    MODIFIER,
    WHITESPACE,
    /** The raw, verbatim text of an "ASM name { ... }" block's body (never further tokenized -- see Lexer.scanAsmBlock). literalValue holds the exact source text between the outer braces, exclusive. */
    ASM_BLOCK,
    /** An unresolved backtick template literal (`` `...${expr}...` ``) -- childs alternates TEMPLATE_STRING_PART (literal-text) tokens and fully-nested expression Tokens (one per "${...}" segment), in source order. Never survives past TypeChecker.resolveTemplateString, which replaces it in place with an ordinary STRING once every segment is compile-time evaluated. See Parser.gatherTemplateStringsDeep for how this is assembled from the lexer's own flat TEMPLATE_STRING_START/TEMPLATE_STRING_PART/TEMPLATE_EXPR_START/TEMPLATE_STRING_END marker sequence. */
    TEMPLATE_STRING
}

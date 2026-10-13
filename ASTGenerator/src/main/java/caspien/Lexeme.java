package caspien;


import java.util.ArrayList;
import java.util.List;

/**
 * One lexical unit as the Lexer produces it: flat, never nested, carrying only what the scan knows. The Parser turns each Lexeme
 * into a {@link caspien.Token} (the syntax-tree node) when it nests the list.
 */
public final class Lexeme {

    public TokenType type;
    public String text;
    /** 1-indexed source line the lexeme started on. */
    public final int line;
    public final String file;

    /** For string/char literals: the escape-processed value (null otherwise). */
    public String literalValue;

    /** Which quote character opened a literal; only the classification pass reads it. */
    public char quoteDelimiter;

    /** Integer written in hex/binary/with underscores: blocks merging with a following ".digits" into a float. */
    public boolean radixLiteral;

    /** Set on the numeric-limit constants (`NUMERIC_LIMIT_CONSTANTS`): the type the literal must take. */
    public String forcedLiteralType;

    /** Comments pinned to this lexeme (nearest non-whitespace rule). */
    public List<Lexeme> pinnedComments = new ArrayList<>();

    public Lexeme(TokenType type, String text, int line, String file) {
        this.type = type;
        this.text = text;
        this.line = line;
        this.file = file;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append("(").append(text).append(")");
        sb.append("[").append(file).append(":").append(line).append("]");
        if (!pinnedComments.isEmpty()) {
            sb.append(" //").append(pinnedComments.size()).append(" comment(s)");
        }
        return sb.toString();
    }
}

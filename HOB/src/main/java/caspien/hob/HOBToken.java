package caspien.hob;

/**
 * One token of higher-order bytecode (HOB) text: its text, where it came
 * from, and whether it is a plain word or a quoted string. Immutable.
 */
public final class HOBToken {

    public enum Kind {
        /** A mnemonic or operand: "PUSH", "12", "imut_u64", a label, a char literal. */
        CODE,
        /** A quoted string literal, quotes included (a STRING line's text). */
        STRING,
        /** Reserved; HOB has no comment syntax. */
        COMMENT
    }

    public final String text;
    public final String file;
    public final int line;
    public final Kind kind;

    public HOBToken(String text, String file, int line, Kind kind) {
        this.text = text;
        this.file = file;
        this.line = line;
        this.kind = kind;
    }

    @Override
    public String toString() {
        return text;
    }
}

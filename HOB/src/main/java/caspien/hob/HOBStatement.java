package caspien.hob;

import java.util.ArrayList;
import java.util.Collection;

/**
 * One line of HOB: an opcode (or label) followed by its operands, as a list
 * of tokens. A pass that rewrites a line builds a new HOBStatement.
 */
public final class HOBStatement extends ArrayList<HOBToken> {
    private static final long serialVersionUID = 1L;


    public HOBStatement() {
    }

    public HOBStatement(int capacity) {
        super(capacity);
    }

    public HOBStatement(Collection<? extends HOBToken> tokens) {
        super(tokens);
    }

    /** First token's text (mnemonic or label), or "" for an empty statement. */
    public String opcode() {
        return isEmpty() ? "" : get(0).text;
    }

    /** Text of token i, or "" when the statement is shorter. */
    public String text(int i) {
        return i < size() ? get(i).text : "";
    }

    /** Line of the HOB text this statement was read from (0 for an empty statement). */
    public int sourceLine() {
        return isEmpty() ? 0 : get(0).line;
    }

    /**
     * An instruction a pass generates, written as text ("ALLOC 16"): split by the same scanner as program text.
     * For building new lines inside a pass, never for reading a program (that is HOBParser, once, up front).
     */
    public static HOBStatement fromText(String text, String origin) {
        return HOBParser.parseLine(text, origin, 1);
    }

    /** Several generated instructions, one statement per string. */
    public static java.util.List<HOBStatement> fromTexts(java.util.List<String> texts, String origin) {
        java.util.List<HOBStatement> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            out.add(HOBParser.parseLine(texts.get(i), origin, i + 1));
        }
        return out;
    }
}

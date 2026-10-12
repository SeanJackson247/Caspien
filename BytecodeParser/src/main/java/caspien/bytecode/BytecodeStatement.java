package caspien.bytecode;

import java.util.ArrayList;
import java.util.Collection;

/**
 * One line of HOB: an opcode (or label) followed by its operands, as a list
 * of tokens. A pass that rewrites a line builds a new BytecodeStatement.
 */
public final class BytecodeStatement extends ArrayList<BytecodeToken> {
    private static final long serialVersionUID = 1L;


    public BytecodeStatement() {
    }

    public BytecodeStatement(int capacity) {
        super(capacity);
    }

    public BytecodeStatement(Collection<? extends BytecodeToken> tokens) {
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
     * For building new lines inside a pass, never for reading a program (that is BytecodeParser, once, up front).
     */
    public static BytecodeStatement fromText(String text, String origin) {
        return BytecodeParser.parseLine(text, origin, 1);
    }

    /** Several generated instructions, one statement per string. */
    public static java.util.List<BytecodeStatement> fromTexts(java.util.List<String> texts, String origin) {
        java.util.List<BytecodeStatement> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            out.add(BytecodeParser.parseLine(texts.get(i), origin, i + 1));
        }
        return out;
    }
}

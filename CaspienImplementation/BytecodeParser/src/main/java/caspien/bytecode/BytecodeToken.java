package caspien.bytecode;

/**
 * One token of higher-order bytecode (HOB) text: its text, where it came
 * from, and whether it is a plain word or a quoted string. Immutable.
 */
public final class BytecodeToken {

    public final String text;
    public final String file;
    public final int line;
    public final BytecodeTokenKind kind;

    public BytecodeToken(String text, String file, int line, BytecodeTokenKind kind) { }

    @Override
    public String toString() { return null; }
}

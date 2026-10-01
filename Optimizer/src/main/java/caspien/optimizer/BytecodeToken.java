package caspien.optimizer;

/**
 * One token of already-emitted bytecode text, as read back in by the
 * optimizer -- deliberately a much smaller shape than the main
 * compiler's own Token class, since the optimizer never needs anything
 * beyond "what does this token say, where did it come from, and what
 * kind of thing is it." The whole program is a List<List<BytecodeToken>>
 * (BytecodeOptimizer.java), nested by line -- one inner list per line
 * of bytecode text, in original emission order.
 */
public class BytecodeToken {

    public enum Kind {
        /** An ordinary bytecode mnemonic/operand -- "PUSH", "12", "imut_u64", a label, etc. */
        CODE,
        /** A quoted string literal appearing in the bytecode (e.g. a hoisted STRING line's own text). */
        STRING,
        /** A comment -- bytecode has no comment syntax of its own today; reserved for future use. */
        COMMENT
    }

    public final String text;
    public final String file;
    public final int line;
    public final Kind kind;

    public BytecodeToken(String text, String file, int line, Kind kind) {
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

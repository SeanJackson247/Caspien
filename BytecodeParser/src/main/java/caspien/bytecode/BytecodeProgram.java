package caspien.bytecode;

import java.util.ArrayList;
import java.util.Collection;

/** A whole HOB program: its statements in order. This is the only thing the optimizer passes read and write. */
public final class BytecodeProgram extends ArrayList<BytecodeStatement> {
    private static final long serialVersionUID = 1L;


    public BytecodeProgram() {
    }

    public BytecodeProgram(int capacity) {
        super(capacity);
    }

    public BytecodeProgram(Collection<? extends BytecodeStatement> statements) {
        super(statements);
    }
}

package caspien.optimizer.hob;

import java.util.ArrayList;
import java.util.Collection;

/** A whole HOB program: its statements in order. This is the only thing the optimizer passes read and write. */
public final class HOBProgram extends ArrayList<HOBStatement> {
    private static final long serialVersionUID = 1L;


    public HOBProgram() {
    }

    public HOBProgram(int capacity) {
        super(capacity);
    }

    public HOBProgram(Collection<? extends HOBStatement> statements) {
        super(statements);
    }
}

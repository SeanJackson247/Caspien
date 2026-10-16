package caspien.lowerorder;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.bytecode.BytecodeTokenKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class RegisterFormState {
    final BytecodeProgram out = new BytecodeProgram();
    final List<RegisterFormEntry> stack = new ArrayList<>();
    final boolean[] tempUsed = new boolean[RegisterFormPass.TEMP_COUNT];
    BytecodeToken anchor; // any token, for file/line of synthesized lines
    /** region start: the input line index and output size at the last empty-model point */
    int cpIn = 0;
    int cpOut = 0;
    /** the current region uses something a plain stack flush could not reproduce faithfully */
    boolean risky = false;

    int freeTemps() { return 0; }

    int allocTemp() { return 0; }

    void freeTemp(int t) { }

    void emit(String... texts) { }

    BytecodeStatement mk(String... texts) { return null; }

    /** every remembered entry is one plain 8-byte word (a constant, a frame value, an address, a full-width temp) */
    boolean plainWords() { return false; }

    /** Discards this region's output and emits its original input lines [cpIn, upTo) instead. */
    void rollback(BytecodeProgram all, int upTo) { }

    /** Writes every remembered entry back out, bottom to top, then empties the model. */
    void flush() { }

    /**
     * @param tempLiveAbove true when a temp that is NOT on the model (e.g. a branch condition being
     *                      consumed) is still live in a register while this flush runs
     *
     * An entry's ORIGINAL line is re-emitted by the backend's ordinary PUSH/ADDR code, which uses rax/rbx
     * as scratch -- so it must not run while any temp is still live. Whenever a temp sits above the
     * entry (or is live off-model), the entry is written with a register-form push instead, which only
     * ever uses the spare scratch register. When no temp is live above, the original line is kept, so the
     * struct/array construction shapes the backend recognises reach it unchanged. (Only non-risky regions
     * are ever flushed while entries remain; see the class comment.)
     */
    void flush(boolean tempLiveAbove) { }
}

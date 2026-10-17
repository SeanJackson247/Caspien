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

    int freeTemps() {
        int n = 0;
        for (boolean u : tempUsed) {
            if (!u) n++;
        }
        return n;
    }

    int allocTemp() {
        for (int t = 0; t < RegisterFormPass.TEMP_COUNT; t++) {
            if (!tempUsed[t]) {
                tempUsed[t] = true;
                return t;
            }
        }
        return -1;
    }

    void freeTemp(int t) {
        tempUsed[t] = false;
    }

    void emit(String... texts) {
        out.add(mk(texts));
    }

    BytecodeStatement mk(String... texts) {
        BytecodeStatement l = new BytecodeStatement(texts.length);
        BytecodeToken a = anchor;
        for (String t : texts) {
            l.add(new BytecodeToken(t, a == null ? "<register-form>" : a.file, a == null ? 0 : a.line, BytecodeTokenKind.CODE));
        }
        return l;
    }

    /** every remembered entry is one plain 8-byte word (a constant, a frame value, an address, a full-width temp) */
    boolean plainWords() {
        for (RegisterFormEntry e : stack) {
            if (e.size != 8) {
                return false;
            }
        }
        return true;
    }

    /** Discards this region's output and emits its original input lines [cpIn, upTo) instead. */
    void rollback(BytecodeProgram all, int upTo) {
        out.removeRange(cpOut, out.size());
        for (int k = cpIn; k < upTo; k++) {
            out.add(all.get(k));
        }
        stack.clear();
        Arrays.fill(tempUsed, false);
    }

    /** Writes every remembered entry back out, bottom to top, then empties the model. */
    void flush() {
        flush(false);
    }

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
    void flush(boolean tempLiveAbove) {
        int lastT = -1;
        for (int i = 0; i < stack.size(); i++) {
            if (stack.get(i).kind == RegisterFormKind.T) {
                lastT = i;
            }
        }
        for (int i = 0; i < stack.size(); i++) {
            RegisterFormEntry e = stack.get(i);
            if (e.kind == RegisterFormKind.T) {
                emit("R_PUSH", "8", "%t" + e.temp);
                freeTemp(e.temp);
            } else if (tempLiveAbove || i < lastT) {
                if (e.kind == RegisterFormKind.A) {
                    emit("R_PUSHA", "8", e.text);
                } else {
                    emit("R_PUSH", "8", RegisterFormPass.operandOf(e));
                }
            } else {
                out.add(e.orig);
            }
        }
        stack.clear();
    }
}

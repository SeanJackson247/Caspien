package caspien.lowerorder;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;

/** One fusable mnemonic. Returns how many input lines it consumed, or 0 to decline (caller flushes/rolls back and passes through). A handler must not emit anything before it returns 0. */
interface RegisterFormHandler {
    int tryFuse(RegisterFormState st, BytecodeStatement line, BytecodeProgram all, int idx);
}

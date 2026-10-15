package caspien.codegen.codegenCaspien.x86backend;

import caspien.bytecode.BytecodeStatement;

/** Mnemonic groups 2 and 3 of the stack-form instruction set (one switch per group, false = not mine) (see X86Generator for how the parts fit together). */
final class X86CasesA {
    private final X86Backend hub;
    private final X86Core core;

    X86CasesA(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    boolean emitCases2(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        return false;
    }

    boolean emitCases3(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        return false;
    }

    /** Operand idx as a long when the line has it, else 0. */
    private static long optLong(BytecodeStatement line, int idx) {
        return 0;
    }

    /** Operand idx as an int size when the line has it, else dflt. */
    private static int optSize(BytecodeStatement line, int idx, int dflt) {
        return 0;
    }
}

package caspien.codegen.codegenCaspien.x86backend;

import caspien.bytecode.BytecodeStatement;

/** Mnemonic groups 4 and 5 of the stack-form instruction set (see X86Generator for how the parts fit together). */
final class X86CasesB {
    private final X86Backend hub;
    private final X86Core core;

    X86CasesB(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    boolean emitCases4(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        return false;
    }

    boolean emitCases5(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        return false;
    }
}

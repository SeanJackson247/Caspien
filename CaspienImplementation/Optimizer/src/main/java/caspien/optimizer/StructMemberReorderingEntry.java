package caspien.optimizer;

import caspien.bytecode.BytecodeStatement;

final class StructMemberReorderingEntry {
    final boolean pad;
    final long padBytes;
    final String name;
    final String type;
    final BytecodeStatement line;   // the original declaration line

    StructMemberReorderingEntry(boolean pad, long padBytes, String name, String type, BytecodeStatement line) { }
}

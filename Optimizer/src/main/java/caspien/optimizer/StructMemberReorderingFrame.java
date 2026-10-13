package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;

import java.util.List;

/** One choice point of StructMemberReorderingPass.walk: which member is being matched, where, and how far its value-unit scan has got. */
final class StructMemberReorderingFrame {
    final int j;
    final int idx;
    final int membersDone;
    final List<BytecodeProgram> acc;
    boolean entered;
    int depth;
    int p;
    BytecodeProgram unit;

    StructMemberReorderingFrame(int j, int idx, int membersDone, List<BytecodeProgram> acc) {
        this.j = j;
        this.idx = idx;
        this.membersDone = membersDone;
        this.acc = acc;
    }
}

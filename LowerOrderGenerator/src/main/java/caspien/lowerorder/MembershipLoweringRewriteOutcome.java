package caspien.lowerorder;

import caspien.bytecode.BytecodeProgram;

final class MembershipLoweringRewriteOutcome {
    final BytecodeProgram lines;
    final boolean changed;
    MembershipLoweringRewriteOutcome(BytecodeProgram lines, boolean changed) {
        this.lines = lines;
        this.changed = changed;
    }
}

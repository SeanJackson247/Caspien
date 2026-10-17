package caspien.optimizer;

import caspien.bytecode.BytecodeStatement;

/** One leading ALLOC line with its sort keys (alignment, use weight). */
final class VariableAllocationReorderingSlot {
    final int idx;
    final BytecodeStatement line;
    final long align;
    final long weight;

    VariableAllocationReorderingSlot(int idx, BytecodeStatement line, long align, long weight) {
        this.idx = idx;
        this.line = line;
        this.align = align;
        this.weight = weight;
    }
}

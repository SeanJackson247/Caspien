package caspien.optimizer;

final class LoopUnrollingLoop {
    int start;            // index of "ADDR $for_range_N T"
    int bodyStart;        // first body line
    int incStart;         // first line of the 4-line increment (= body end, exclusive)
    int jmpIdx;           // "JMP @for_A"
    int endIdx;           // "@for_end_B:"
    String rangeName, rangeType, var, varType, headLabel, endLabel;
    long lo, hi;
    int allocIdx = -1;    // "ALLOC $for_range_N T" near the top of the function
    int headIdx;          // index of "@for_A:" (after any FOR_DECORATE lines)
    boolean forced, dont; // @unroll / @dont(unroll) written on the loop
    int factor;           // @unroll(N): N, or 0 for a plain @unroll (full)
    String pos;           // "file:line" of the decorator, for messages
}

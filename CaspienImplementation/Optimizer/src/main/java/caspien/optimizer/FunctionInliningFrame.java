package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;

/** One region being expanded by FunctionInliningPass.expandRange: where it has got to, its output so far, and the call bracket it is waiting on. */
final class FunctionInliningFrame {
    int k;
    final int to;
    final boolean baseClean;
    final boolean regsLoaded;
    final BytecodeProgram out = new BytecodeProgram();
    // the call bracket whose argument region is being expanded (set while a child frame runs)
    int e;
    BytecodeStatement callLine;
    boolean isCall;
    int cut;
    int cls;
    boolean loaded;

    FunctionInliningFrame(int from, int to, boolean baseClean, boolean regsLoaded) { }
}

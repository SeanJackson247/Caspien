package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class FunctionInliningCtx {
    String caller;
    Set<String> callerNames = new HashSet<>();
    Map<String, FunctionInliningCallee> callees;
    BytecodeProgram newAllocs = new BytecodeProgram();
    long added = 0;
    boolean changed = false;
}

package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class FunctionInliningCallee {
    String name;
    List<String[]> params = new ArrayList<>();
    List<String[]> allocs = new ArrayList<>();
    BytecodeProgram code = new BytecodeProgram();
    boolean eligible;
    int scaffoldSlots;
    /** the body contains THROW / GT_UNWIND: it can leave through the caller's call-site label. */
    boolean unwinds;
    boolean usesAddr, usesMsg;
    /** the body defines a `@catch_N` label (a `?catch` / `try ... catch` of its own). */
    boolean hasCatch;
    /** every statement leaves the operand stack as it found it (no ignored call result, no unknown mnemonic): safe to run with operands of an enclosing expression beneath. */
    boolean balanced;
    boolean controlFlow;
    boolean simple;
    List<FunctionInliningRet> rets = new ArrayList<>();
    Set<String> locals = new HashSet<>();
    Set<String> globals = new HashSet<>();
    Set<String> labels = new LinkedHashSet<>();
    int lines;
    /** number of `CALL name` lines in the program at the start of this round */
    int calls;
    String why = "";
    /** @inline: inline every call site that is safe, whatever the preset's size and growth limits say. */
    boolean forced;
    /** @dont(inline): never. */
    boolean dont;
    String pos;
}

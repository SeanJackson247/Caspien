package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;

import java.util.List;

/** `hob` = the optimised program text, `program` = the same program in memory; `messages` = "[info]"/"[note]"/"[warning]" lines, in order. */
public final class OptimizerResult {
    public final String hob;
    public final BytecodeProgram program;
    public final List<String> messages;
    /** A stage that cannot finish throws; the field exists so every stage's Result can be read the same way. */
    public final boolean failed = false;

    OptimizerResult(String hob, BytecodeProgram program, List<String> messages) {
        this.hob = hob;
        this.program = program;
        this.messages = messages;
    }
}

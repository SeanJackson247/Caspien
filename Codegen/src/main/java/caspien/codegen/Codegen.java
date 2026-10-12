package caspien.codegen;

import caspien.bytecode.BytecodeParser;
import caspien.bytecode.BytecodeProgram;
import caspien.codegen.x86backend.X86Backend;

import java.util.List;

/**
 * Codegen as a library: low-order bytecode (LOB) text in, assembly text out; no files, no process state.
 * The orchestrator calls this in its own JVM; `Main` is the same thing as a stand-alone program.
 * Phases: parse the whole LOB into a BytecodeProgram, generate the assembly from it.
 */
public final class Codegen {

    private Codegen() {
    }

    /** `asm` = the assembly text (null when `failed`); `messages` = the lines a stand-alone run prints to stderr; `failed` = a fatal codegen error. */
    public static final class Result {
        public final String asm;
        public final List<String> messages;
        public final boolean failed;

        Result(String asm, List<String> messages, boolean failed) {
            this.asm = asm;
            this.messages = messages;
            this.failed = failed;
        }
    }

    /**
     * @param lobText    the LowerOrderGenerator's output
     * @param origin     name shown in diagnostics for the input (a file name)
     * @param configText the contents of `codegen.config` (required)
     */
    public static Result generate(String lobText, String origin, String configText) {
        try {
            CodegenConfig config = CodegenConfig.parse(configText, "codegen.config");
            BytecodeProgram program = new BytecodeParser().parse(lobText.lines().toList(), origin);
            X86Backend backend = new X86Backend(config.target, config.bmi2);
            backend.avx = config.avx;
            return new Result(backend.generate(program), List.of(), false);
        } catch (CodegenException e) {
            return new Result(null, List.of(e.getMessage()), true);
        }
    }
}

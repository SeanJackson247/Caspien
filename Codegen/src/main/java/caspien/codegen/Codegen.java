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

    /**
     * @param lobText    the LowerOrderGenerator's output
     * @param origin     name shown in diagnostics for the input (a file name)
     * @param configText the contents of `codegen.config` (required)
     */
    public static CodegenResult generate(String lobText, String origin, String configText) {
        try {
            CodegenConfig config = CodegenConfig.parse(configText, "codegen.config");
            BytecodeProgram program = new BytecodeParser().parse(lobText.lines().toList(), origin);
            X86Backend backend = new X86Backend(config.target, config.bmi2);
            backend.avx = config.avx;
            return new CodegenResult(backend.generate(program.asList()), List.of(), false);
        } catch (CodegenException e) {
            return new CodegenResult(null, List.of(e.getMessage()), true);
        }
    }
}

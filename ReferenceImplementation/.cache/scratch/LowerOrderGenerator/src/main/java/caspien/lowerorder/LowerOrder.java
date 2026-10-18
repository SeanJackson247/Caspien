package caspien.lowerorder;

import caspien.bytecode.BytecodeParser;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeSerializer;

import java.util.List;

/**
 * The LowerOrderGenerator as a library: a HOB program in (as text or as a BytecodeProgram), low-order bytecode (LOB) text out, no files, no process state.
 * The orchestrator calls this in its own JVM; `Main` is the same thing as a stand-alone program.
 * Phases: parse the text into a BytecodeProgram (skipped for a program), run every pass on it, serialize.
 */
public final class LowerOrder {

    private LowerOrder() {
    }

    /**
     * @param hobText    the Optimizer's output
     * @param origin     name shown in diagnostics for the input (a file name)
     * @param configText the contents of `compiler.config` (required: calling conventions and register switches)
     */
    public static LowerOrderResult generate(String hobText, String origin, String configText) {
        if (configText == null) {
            throw new IllegalStateException("required config 'compiler.config' was not supplied");
        }
        return generate(new BytecodeParser().parse(hobText.lines().toList(), origin), configText);
    }

    /** The same stage on the Optimizer's in-memory program (the orchestrator's path; the program is consumed). The LOB comes out as text and as the program (the Codegen's input). */
    public static LowerOrderResult generate(BytecodeProgram program, String configText) {
        if (configText == null) {
            throw new IllegalStateException("required config 'compiler.config' was not supplied");
        }
        CompilerConfig config = CompilerConfig.parse(configText, "compiler.config");
        BytecodeProgram lowered = new LowerOrderGenerator(config).generate(program);
        return new LowerOrderResult(new BytecodeSerializer().serialize(lowered), lowered, List.of());
    }
}

package caspien.optimizer;

import caspien.bytecode.BytecodeParser;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * The Optimizer as a library: a HOB program in (as text or as a BytecodeProgram), the optimised HOB out (text and program), no files, no
 * process state. The orchestrator calls this in its own JVM; `Main` is the same thing as a stand-alone program.
 * Phases: parse the text into a BytecodeProgram (skipped for a program), run every pass on it, serialize.
 */
public final class Optimizer {

    private Optimizer() {
    }

    /**
     * @param hobText    the front end's output
     * @param origin     name shown in diagnostics for the input (a file name)
     * @param configText the contents of `compiler.config`, or null for "every switch off"
     */
    public static OptimizerResult optimize(String hobText, String origin, String configText) {
        return optimize(new BytecodeParser().parse(hobText.lines().toList(), origin), configText);
    }

    /** The same stage on the front end's in-memory program (the orchestrator's path; the program is consumed). */
    public static OptimizerResult optimize(BytecodeProgram program, String configText) {
        List<String> cfg = configText == null ? new ArrayList<>() : configText.lines().toList();
        String cfgName = "compiler.config";
        UnrollConfig unrollConfig = UnrollConfig.parse(cfg, cfgName);
        FoldConfig foldConfig = FoldConfig.parse(cfg, cfgName);
        VariableConfig variableConfig = VariableConfig.parse(cfg, cfgName);
        InlineConfig inlineConfig = InlineConfig.parse(cfg, cfgName);

        Messages messages = new Messages();
        messages.add("[info] optimizer: " + unrollConfig + "; " + foldConfig + "; " + variableConfig + "; " + inlineConfig);
        BytecodeProgram optimized = new BytecodeOptimizer(unrollConfig, foldConfig, variableConfig, inlineConfig, messages).optimize(program);

        return new OptimizerResult(new BytecodeSerializer().serialize(optimized), optimized, messages.lines());
    }
}

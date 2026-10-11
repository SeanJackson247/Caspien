package caspien.optimizer;

import caspien.hob.HOBParser;
import caspien.hob.HOBProgram;
import caspien.hob.HOBSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * The Optimizer as a library: HOB text in, optimised HOB text out, no files, no process state. The
 * orchestrator calls this in its own JVM; `Main` is the same thing as a stand-alone program.
 * Phases: parse the whole text into a HOBProgram, run every pass on it, serialize.
 */
public final class Optimizer {

    private Optimizer() {
    }

    /** `hob` = the optimised program text; `messages` = "[info]"/"[note]"/"[warning]" lines, in order. */
    public static final class Result {
        public final String hob;
        public final List<String> messages;

        Result(String hob, List<String> messages) {
            this.hob = hob;
            this.messages = messages;
        }
    }

    /**
     * @param hobText    the front end's output
     * @param origin     name shown in diagnostics for the input (a file name)
     * @param configText the contents of `compiler.config`, or null for "every switch off"
     */
    public static Result optimize(String hobText, String origin, String configText) {
        HOBProgram program = new HOBParser().parse(hobText.lines().toList(), origin);

        List<String> cfg = configText == null ? new ArrayList<>() : configText.lines().toList();
        String cfgName = "compiler.config";
        UnrollConfig unrollConfig = UnrollConfig.parse(cfg, cfgName);
        FoldConfig foldConfig = FoldConfig.parse(cfg, cfgName);
        VariableConfig variableConfig = VariableConfig.parse(cfg, cfgName);
        InlineConfig inlineConfig = InlineConfig.parse(cfg, cfgName);

        Messages messages = new Messages();
        messages.add("[info] optimizer: " + unrollConfig + "; " + foldConfig + "; " + variableConfig + "; " + inlineConfig);
        HOBProgram optimized = new BytecodeOptimizer(unrollConfig, foldConfig, variableConfig, inlineConfig, messages).optimize(program);

        return new Result(new HOBSerializer().serialize(optimized), messages.lines());
    }
}

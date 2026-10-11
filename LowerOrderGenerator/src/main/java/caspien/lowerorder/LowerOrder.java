package caspien.lowerorder;

import caspien.hob.HOBParser;
import caspien.hob.HOBProgram;
import caspien.hob.HOBSerializer;

import java.util.List;

/**
 * The LowerOrderGenerator as a library: HOB text in, low-order bytecode (LOB) text out, no files, no process state.
 * The orchestrator calls this in its own JVM; `Main` is the same thing as a stand-alone program.
 * Phases: parse the whole text into a HOBProgram, run every pass on it, serialize.
 */
public final class LowerOrder {

    private LowerOrder() {
    }

    /** `lob` = the low-order program text; `messages` = "[info]"/"[note]"/"[warning]" lines, in order (none today). */
    public static final class Result {
        public final String lob;
        public final List<String> messages;

        Result(String lob, List<String> messages) {
            this.lob = lob;
            this.messages = messages;
        }
    }

    /**
     * @param hobText    the Optimizer's output
     * @param origin     name shown in diagnostics for the input (a file name)
     * @param configText the contents of `compiler.config` (required: calling conventions and register switches)
     */
    public static Result generate(String hobText, String origin, String configText) {
        if (configText == null) {
            throw new IllegalStateException("required config 'compiler.config' was not supplied");
        }
        HOBProgram program = new HOBParser().parse(hobText.lines().toList(), origin);
        CompilerConfig config = CompilerConfig.parse(configText, "compiler.config");
        HOBProgram lowered = new LowerOrderGenerator(config).generate(program);
        return new Result(new HOBSerializer().serialize(lowered), List.of());
    }
}

package caspien.optimizer;

import java.util.List;

/** `hob` = the optimised program text; `messages` = "[info]"/"[note]"/"[warning]" lines, in order. */
public final class OptimizerResult {
    public final String hob;
    public final List<String> messages;
    /** A stage that cannot finish throws; the field exists so every stage's Result can be read the same way. */
    public final boolean failed = false;

    OptimizerResult(String hob, List<String> messages) { }
}

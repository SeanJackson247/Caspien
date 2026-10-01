package caspien.optimizer;

import java.util.List;

/**
 * The result of running one OptimizationPass once: the (possibly
 * rewritten) program, plus whether this pass actually changed anything.
 * `changed` is what drives every fixed-point loop in BytecodeOptimizer --
 * a pass that made no changes must report false so the loop it's part of
 * can terminate.
 */
public class PassResult {

    public final List<List<BytecodeToken>> lines;
    public final boolean changed;

    public PassResult(List<List<BytecodeToken>> lines, boolean changed) {
        this.lines = lines;
        this.changed = changed;
    }
}

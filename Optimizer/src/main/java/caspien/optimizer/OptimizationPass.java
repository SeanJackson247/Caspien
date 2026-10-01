package caspien.optimizer;

import java.util.List;

/**
 * One optimization pass over the whole, already-emitted bytecode
 * program. Every pass is a pure function: given the current program, it
 * returns either the same lines back (nothing to do) or a rewritten
 * program, and says which. Passes are deliberately unaware of each
 * other and of BytecodeOptimizer's own pipeline shape -- ordering,
 * fixed-point looping, etc. all live in BytecodeOptimizer, not here.
 */
public interface OptimizationPass {

    /** A short, stable name for this pass, used only for logging/diagnostics. */
    String name();

    PassResult run(List<List<BytecodeToken>> lines);
}

package caspien.postpasses.gasreport;

import caspien.config.DocComment;
import java.util.Map;

/** Checks the termination labels on a big-stack thread; a RuntimeException is kept in `error` for the caller to rethrow. */
final class GasLabelsThread implements Runnable {
    private final GasReport g;
    private final Map<String, DocComment> funcs;
    private final Map<String, DocComment> loops;
    RuntimeException error;

    GasLabelsThread(GasReport g, Map<String, DocComment> funcs, Map<String, DocComment> loops) {
        this.g = g;
        this.funcs = funcs;
        this.loops = loops;
    }

    @Override
    public void run() {
        try {
            g.verifyLabels(funcs, loops);
        } catch (RuntimeException e) {
            error = e;
        }
    }
}

package caspien.postpasses.gasreport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One activation of GasReportCore.solve / runEvals on the explicit stack of GasReportCore.solve. */
final class GasSolveFrame {
    static final int SOLVE = 0;
    static final int RUN_EVALS = 1;

    final int kind;
    final Fn f;
    Map<String, V> args;
    int i;
    int phase;
    boolean entered;
    /** RUN_EVALS: the unbounded reasons collected from the callees. */
    Set<String>[] callUb;
    /** RUN_EVALS: the callee being specialised while a child frame runs. */
    String pendingCallee;
    Fn pendingUse;

    GasSolveFrame(int kind, Fn f) { }
}

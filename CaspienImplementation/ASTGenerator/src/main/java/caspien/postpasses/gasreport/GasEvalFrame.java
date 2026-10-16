package caspien.postpasses.gasreport;

import java.util.List;

/** One activation of GasReport's value evaluator (evalSeg / valueAt / stableValue), see GasReport.evalRun. */
final class GasEvalFrame {
    static final int EVAL_SEG = 0;
    static final int VALUE_AT = 1;
    static final int STABLE = 2;

    final int kind;
    final Fn f;
    String var;
    int from;
    int to;
    int at;
    int depth;
    int i;
    int phase;
    List<V> st;
    String field;
    String[] p;
    int write;
    /** The finished frame's answer (a V, or a List of V for EVAL_SEG), null = unknown. */
    Object result;
    /** The answer of the call this frame was waiting for. */
    Object got;

    /** Records the answer and reports "no child frame" to the driver. */
    GasEvalFrame finish(Object answer) { return null; }

    GasEvalFrame(int kind, Fn f) { }
}

package caspien.postpasses.gasreport;

import java.util.List;

/** One activation of Eval's path walk (walkMemo / block / solveLoop / padTarget / walkTarget), see GasReportCore.runWalk. */
final class GasWalkFrame {
    static final int WALK_MEMO = 0;
    static final int BLOCK = 1;
    static final int SOLVE_LOOP = 2;
    static final int PAD_TARGET = 3;
    static final int WALK_TARGET = 4;

    final int kind;
    final Eval ev;
    Loop lp;
    int i;
    int ctx;
    int mode;
    int edge;
    int target;
    int phase;
    Val[] m;
    List<Integer> run;
    boolean asm;
    Val tail;
    Val a;
    Val cont;
    Val brk;
    /** The answer of this frame (null for solveLoop). */
    Val result;
    /** The answer of the child frame this one was waiting for. */
    Val got;
    /** The child returned by the last step replaces this frame (a tail call). */
    boolean replaced;

    GasWalkFrame(int kind, Eval ev) { }

    /** Ends this frame with an answer; the driver hands it to the caller. */
    GasWalkFrame finish(Val answer) { return null; }

    /** Ends this frame with the answer of `child` (the child takes this frame's place). */
    GasWalkFrame tailCall(GasWalkFrame child) { return null; }
}

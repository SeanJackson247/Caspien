package caspien.postpasses.gasreport;

import static caspien.postpasses.gasreport.GasReportCore.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import caspien.bytecode.Text;

/** Per-function analysis state. */
public final class Eval {
    private final GasReportCore core;
    public final Fn f;
    public final int mt;                                          // GAS, HEAP, COUNT or LIVE
    public final Map<Integer, Loop> loops = new HashMap<>();      // head line -> loop
    public final Map<Long, Val[]> memo = new HashMap<>();         // (context, mode) -> per-line memo

    public final Map<Integer, Long> cred;                         // LIVE: the credits this walk uses (the function's own, or those of a pad analysed from one entry edge)

    public Eval(GasReportCore core, Fn f, int mt) { }

    public Eval(GasReportCore core, Fn f, int mt, Map<Integer, Long> cred) { }

    public void solveLoop(Loop lp) { }

    private GasWalkFrame solveFrame(Loop lp) { return null; }

    /** Costs of a whole loop statement: exitCost (normal exit or `break`; the code after the loop is added by the caller) and retCost (a `return` inside). */
    private GasWalkFrame stepSolveLoop(GasWalkFrame fr) { return null; }

    public String labelOf(Loop lp) { return null; }

    public Val headerCost(Loop lp) { return null; }

    public Val walk(int i, int ctx, int mode) { return null; }

    private GasWalkFrame walkFrame(int start, int ctx, int mode) { return null; }

    private GasWalkFrame blockFrame(Loop lp, int ctx, int mode) { return null; }

    private GasWalkFrame padFrame(int edge, int target, int ctx, int mode) { return null; }

    private GasWalkFrame targetFrame(int target, int ctx, int mode) { return null; }

    /** One step of a frame owned by this Eval: returns a child frame to run first, or null when the frame has finished (its answer is in `result`). */
    public GasWalkFrame step(GasWalkFrame fr) { return null; }

    /** A whole inner loop met while walking: its exit continues after the loop; a return inside it is a terminal path. */
    private GasWalkFrame stepBlock(GasWalkFrame fr) { return null; }

    /** The way into a catch block / landing pad from the edge at line `edge`: with the credits of that pad analysed from the state at that edge. */
    private GasWalkFrame stepPadTarget(GasWalkFrame fr) { return null; }

    private GasWalkFrame stepWalkTarget(GasWalkFrame fr) { return null; }

    /** Walks forward from fr.i collecting a run of plain lines; a branch, a label or a loop asks for child walks, then the run is costed backwards into the memo. */
    private GasWalkFrame stepWalkMemo(GasWalkFrame fr) { return null; }

    /** Terminal value of reaching line i in a loop context, or null when i is an ordinary line. */
    public Val terminal(int i, int ctx, int mode) { return null; }









    public Val lineCost(int idx) { return null; }
}

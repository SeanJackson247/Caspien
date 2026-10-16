package caspien.postpasses.gasreport;

import static caspien.postpasses.gasreport.GasReportCore.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class Fn {
    public String name;
    public int start, end;                       // line indexes: FUNC_START line .. FUNC_END line
    public Map<String, Integer> labels = new HashMap<>();
    public final BigInteger[] val = new BigInteger[NMET];                                // per metric: GAS, HEAP (bytes), COUNT, LIVE (net live bytes)
    public BigInteger livePeak = BigInteger.ZERO;                                        // LIVE: the peak of live heap bytes during a run, relative to entry
    public final Map<Integer, Long> credit = new HashMap<>();                            // line -> bytes a free at that line (GT_DESTRUCT, a resize's old block, a call's moved-in arguments) certainly releases
    public final Map<Integer, Integer> okLabelAlloc = new HashMap<>();                   // LIVE: success label (`@new_ok_N`, `@alloc_ok_N`) line -> the allocation line it confirms (a failed allocation holds no block)
    public final Map<Integer, Map<Integer, Long>> edgeCredit = new HashMap<>();          // LIVE: line of an edge into a catch block / landing pad -> the credits of that pad analysed from that edge's state
    public final Map<Integer, Integer> edgeCall = new HashMap<>();                       // LIVE: PUSH_LABEL line -> the call that may unwind through it (its moved-in arguments are freed on that path too)
    public final Map<String, Eval> padEvals = new HashMap<>();                           // credit-map signature -> Eval
    public long retLo;                                                                   // bytes of the owned block it returns (certain lower bound)
    public long retDeep;                                                                 // bytes of the blocks owned through members of the block it returns (certain lower bound)
    public boolean deepOk;                                                               // members that own memory are never moved out, replaced or shared by reference here: a destruct frees the whole tree it was built with
    public boolean[] paramFreed = new boolean[0];                                        // per ARG: every exit path destructs that owned parameter
    @SuppressWarnings("unchecked")
    final Set<String>[] ub = new Set[]{new LinkedHashSet<String>(), new LinkedHashSet<String>(), new LinkedHashSet<String>(), new LinkedHashSet<String>()};   // unbounded reasons per metric
    public int sites;                            // allocation operations written in the body
    public Set<String> notModelled = new LinkedHashSet<>();
    public Set<String> calls = new LinkedHashSet<>();
    public boolean busy;
    public boolean done;
    public boolean throwsDecorated;
    public final Set<String> deco = new HashSet<>();   // FUNC_DECORATE names (@event_loop, @with_tick, @tick, ...)
    public Map<String, V> args = new HashMap<>();                                        // parameter values this solve assumes (empty = none known): a specialisation per call context
    public final Map<Integer, Fn> calleeAt = new HashMap<>();                            // CALL line -> the (possibly specialised) callee whose figures that call uses

    /** A copy that shares the analysis results independent of argument values (credits, labels) and gets its own figures. */
    public Fn specialised() { return null; }
}

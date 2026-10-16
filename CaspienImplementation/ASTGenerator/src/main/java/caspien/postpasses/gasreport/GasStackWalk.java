package caspien.postpasses.gasreport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One function on GasReport.stackDepth's explicit stack: the calls still to visit and the best path found so far. */
final class GasStackWalk {
    final String name;
    final List<String> calls;
    final Set<String> why = new LinkedHashSet<>();
    int next;
    long best;
    String bestVia;
    /** The callee whose depth is being computed above this entry. */
    String pending;

    GasStackWalk(String name, Fn f) { }
}

package caspien.postpasses.gasreport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable bottom-up merge sort of function-name lists for the report; `mode` picks the ordering (all worst first). */
final class GasSort {
    static final int CLASS_DESC = 0;     // reason strings by their class digit
    static final int STACK_DESC = 1;     // by stack depth
    static final int HEAP_DESC = 2;      // by heap bytes
    static final int LIVE_DESC = 3;      // by peak live heap
    static final int PINNED_THEN_METRIC = 4;   // pinned roots first (given order), then by metric `mt`

    Map<String, Fn> fns;
    Map<String, Long> depth;
    Set<String> pinned;
    int mt;

    private int compare(int mode, String a, String b) { return 0; }

    /** Sorts `list` in place; equal elements keep their order. */
    void sort(List<String> list, int mode) { }
}

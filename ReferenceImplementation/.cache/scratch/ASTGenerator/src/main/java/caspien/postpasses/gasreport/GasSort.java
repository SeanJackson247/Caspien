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

    private int compare(int mode, String a, String b) {
        if (mode == CLASS_DESC) {
            return Integer.compare(b.charAt(0), a.charAt(0));
        }
        if (mode == STACK_DESC) {
            return depth.get(b).compareTo(depth.get(a));
        }
        if (mode == HEAP_DESC) {
            return fns.get(b).val[GasReportCore.HEAP].compareTo(fns.get(a).val[GasReportCore.HEAP]);
        }
        if (mode == LIVE_DESC) {
            return fns.get(b).livePeak.compareTo(fns.get(a).livePeak);
        }
        boolean pa = pinned.contains(a), pb = pinned.contains(b);
        if (pa != pb) {
            return pa ? -1 : 1;
        }
        if (pa) {
            return 0;   // pinned roots keep the order they were given in (stable sort)
        }
        return fns.get(b).val[mt].compareTo(fns.get(a).val[mt]);
    }

    /** Sorts `list` in place; equal elements keep their order. */
    void sort(List<String> list, int mode) {
        int n = list.size();
        List<String> src = new ArrayList<>(list);
        List<String> dst = new ArrayList<>(list);
        for (int width = 1; width < n; width *= 2) {
            for (int lo = 0; lo < n; lo += 2 * width) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + 2 * width, n);
                int l = lo, r = mid, k = lo;
                while (l < mid && r < hi) {
                    if (compare(mode, src.get(l), src.get(r)) <= 0) {
                        dst.set(k++, src.get(l++));
                    } else {
                        dst.set(k++, src.get(r++));
                    }
                }
                while (l < mid) {
                    dst.set(k++, src.get(l++));
                }
                while (r < hi) {
                    dst.set(k++, src.get(r++));
                }
            }
            List<String> tmp = src;
            src = dst;
            dst = tmp;
        }
        for (int i = 0; i < n; i++) {
            list.set(i, src.get(i));
        }
    }
}

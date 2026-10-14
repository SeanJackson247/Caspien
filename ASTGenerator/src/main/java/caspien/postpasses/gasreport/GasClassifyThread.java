package caspien.postpasses.gasreport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Classifies the given sites on a big-stack thread; the caller reads `out` after join(). */
final class GasClassifyThread implements Runnable {
    private final GasReport g;
    private final List<String[]> sites;
    final Map<String, String> out = new LinkedHashMap<>();

    GasClassifyThread(GasReport g, List<String[]> sites) {
        this.g = g;
        this.sites = sites;
    }

    @Override
    public void run() {
        for (Fn f : g.core.fns.values()) {
            g.core.solve(f, new ArrayList<>());
        }
        for (String[] s : sites) {
            String w = s[1] == null ? g.funcWord(s[0]) : g.loopWord(s[0], s[1]);
            if (w != null) {
                out.put(s[0] + "|" + (s[1] == null ? "" : s[1]), w);
            }
        }
    }
}

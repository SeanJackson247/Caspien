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

    GasClassifyThread(GasReport g, List<String[]> sites) { }

    @Override
    public void run() { }
}

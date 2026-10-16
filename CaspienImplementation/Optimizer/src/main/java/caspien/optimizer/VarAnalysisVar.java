package caspien.optimizer;

import java.util.ArrayList;
import java.util.List;

final class VarAnalysisVar {
    String name, type;
    int allocs, allocIdx = -1;
    boolean bad;
    final List<Integer> reads = new ArrayList<>();
    final List<Integer> assigns = new ArrayList<>();   // index of the "ADDR name T" line; the literal PUSH is at +1
}

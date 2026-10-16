package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;

final class FloatTempChain {
    int def;
    int last = -1;
    boolean ok = true;
    String temp;
    List<int[]> uses = new ArrayList<>(); // {line, operand index}
    int y = -1;
    String w = "4";
    boolean floatUse = false;
}

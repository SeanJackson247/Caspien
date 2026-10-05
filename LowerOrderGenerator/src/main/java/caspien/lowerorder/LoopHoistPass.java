package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loop-invariant base-pointer copies ("hoisting the array base") -- runs after {@link RegisterFormPass} and before
 * {@link RegVarPromotionPass}, only when compiler.config says "hoist-array-bases: on" (needs deferred-operands and
 * variables-in-registers, otherwise a copy has nowhere to live).
 *
 * <p>In safe code every access of a dynarray local reloads the pointer from its frame slot twice (once for the length check,
 * once for the element address): {@code R_MOV 8 %t0 $S ; R_LD 8 %t0 %t0 ; R_BRC ... ; R_MOV 8 %t0 $S ; R_LEA %t0 %t0 idx 1 16}.
 * Per loop, when the slot {@code $S} is never written inside the loop, this pass makes a hidden copy slot {@code $X} just before
 * the loop header ({@code R_MOV 8 $X $S}), points every read of {@code $S} inside the loop at {@code $X}, and gives {@code $X}
 * a REGHINT. {@link RegVarPromotionPass} then decides, with its usual rules (weights, liveness sharing, volatile registers
 * only across call-free ranges), whether the copy gets a register; if it gets none the copy stays in memory and the loop
 * reads {@code $X} instead of {@code $S}, which costs the same as before plus one move per loop entry.
 *
 * <p>A slot is hoisted out of a loop only when ALL of these hold:
 * <ul>
 *   <li>the loop is a natural loop with a single entry: its header label is never jumped to from outside the loop, and no
 *       label inside it is referenced from outside;</li>
 *   <li>every mention of {@code $S} inside the loop is a plain read {@code R_MOV 8 %t $S} (any write, {@code ADDR}, {@code
 *       GT_DESTRUCT}, {@code RESIZE} operand, ... inside the loop keeps the slot as it is);</li>
 *   <li>the address of {@code $S} is never exposed anywhere in the function ({@code ADDR_OF}, {@code R_ARGA}), so nothing else
 *       (a callee, a pointer) can change it while the loop runs;</li>
 *   <li>at least {@link #MIN_READS} of the reads are the start of a dynarray/pointer access ({@code R_MOV %t $S} directly
 *       followed by {@code R_LD 8 %t %t} (length) or {@code R_LEA %t %t ..} (element address)).</li>
 * </ul>
 * Loops are processed outermost first, so a slot that is invariant in an outer loop is copied once for all inner loops.
 * The copy slots are appended below the function's frame ({@code ALLOC} grows by 8 bytes per slot, rounded to 16).
 * Functions with inline assembly are left alone.
 */
public class LoopHoistPass {

    /** the fewest pointer-access starts in one loop for a copy to be worth a register */
    static final int MIN_READS = 2;

    private static final int MAX_LINES = 20000;

    private final boolean enabled;

    /** per function (by name): the frame size before the copy slots were added and the copy slot -> original slot map */
    private final Map<String, Object[]> hoisted = new HashMap<>();

    public LoopHoistPass(boolean enabled) {
        this.enabled = enabled;
    }

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return lines;
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size() + 16);
        int i = 0;
        while (i < lines.size()) {
            List<BytecodeToken> line = lines.get(i);
            if (!line.isEmpty() && line.get(0).text.equals("FUNC_START")) {
                int end = i;
                while (end < lines.size() && !(!lines.get(end).isEmpty() && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                int stop = Math.min(end + 1, lines.size());
                List<List<BytecodeToken>> fn = lines.subList(i, stop);
                List<List<BytecodeToken>> res = fn.size() <= MAX_LINES ? hoistFunction(fn) : null;
                out.addAll(res != null ? res : fn);
                i = stop;
                continue;
            }
            out.add(line);
            i++;
        }
        return out;
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }

    private static boolean isLabelDef(List<BytecodeToken> l) {
        return l.size() == 1 && t(l, 0).startsWith("@") && t(l, 0).endsWith(":");
    }

    /** a loop: header label index h, last line b that jumps back to it */
    private static final class Loop {
        int h;
        int b;
        int depth;
    }

    /** returns the new function text, or null when nothing was hoisted */
    private List<List<BytecodeToken>> hoistFunction(List<List<BytecodeToken>> fn) {
        int n = fn.size();
        int allocIdx = -1;
        long allocSize = 0;
        Map<String, Integer> labelDef = new HashMap<>();
        List<Long> exposedOffsets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.isEmpty()) {
                continue;
            }
            String m = t(l, 0);
            if (m.equals("ASM_START") || m.equals("ASM_END")) {
                return null;
            }
            if (m.equals("ALLOC") && l.size() == 2 && allocIdx < 0) {
                try {
                    allocSize = Long.parseLong(t(l, 1));
                    allocIdx = i;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (isLabelDef(l)) {
                String s = t(l, 0);
                labelDef.put(s.substring(0, s.length() - 1), i);
            }
            if (m.equals("ADDR_OF") || m.equals("R_ARGA")) {
                for (int k = 1; k < l.size(); k++) {
                    if (t(l, k).startsWith("$") && isSlot(t(l, k))) {
                        exposedOffsets.add(Long.parseLong(t(l, k).substring(1)));
                    }
                }
            }
        }
        if (allocIdx < 0 || allocSize % 8 != 0 || labelDef.isEmpty()) {
            return null;
        }
        // last reference of every label (not its definition) and the first one
        Map<String, Integer> lastRef = new HashMap<>();
        Map<String, Integer> firstRef = new HashMap<>();
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.isEmpty() || isLabelDef(l)) {
                continue;
            }
            for (int k = 1; k < l.size(); k++) {
                String s = t(l, k);
                if (s.startsWith("@") && labelDef.containsKey(s)) {
                    lastRef.put(s, i);
                    firstRef.putIfAbsent(s, i);
                }
            }
        }
        List<Loop> loops = new ArrayList<>();
        for (Map.Entry<String, Integer> e : labelDef.entrySet()) {
            int h = e.getValue();
            Integer last = lastRef.get(e.getKey());
            if (last == null || last <= h || firstRef.get(e.getKey()) < h) {
                continue; // not a back edge, or the header is also entered from above/outside
            }
            Loop lp = new Loop();
            lp.h = h;
            lp.b = last;
            if (singleEntry(fn, lp, labelDef)) {
                loops.add(lp);
            }
        }
        if (loops.isEmpty()) {
            return null;
        }
        for (Loop a : loops) {
            for (Loop b : loops) {
                if (a != b && b.h < a.h && a.b <= b.b) {
                    a.depth++;
                }
            }
        }
        // outermost (longest) first; ties broken by position for a stable result
        loops.sort((x, y) -> (y.b - y.h) != (x.b - x.h) ? Integer.compare(y.b - y.h, x.b - x.h) : Integer.compare(x.h, y.h));

        List<List<BytecodeToken>> cur = new ArrayList<>(fn);
        Map<String, String> copyOf = new HashMap<>();
        Map<Integer, List<List<BytecodeToken>>> before = new HashMap<>();
        List<List<BytecodeToken>> hints = new ArrayList<>();
        int extra = 0;
        for (Loop lp : loops) {
            Map<String, Integer> starts = new HashMap<>();
            List<long[]> writes = new ArrayList<>(); // {offset, width}: bytes the loop may store to, [offset, offset+width)
            for (int i = lp.h; i <= lp.b; i++) {
                List<BytecodeToken> l = cur.get(i);
                if (l.isEmpty()) {
                    continue;
                }
                String m = t(l, 0);
                for (int k = 1; k < l.size(); k++) {
                    String s = t(l, k);
                    if (!s.startsWith("$") || !isSlot(s)) {
                        continue;
                    }
                    long off = Long.parseLong(s.substring(1));
                    long w = writeWidth(m, l, k);
                    if (w != 0) {
                        writes.add(new long[] {off, w});
                    }
                    if (m.equals("R_MOV") && l.size() == 4 && t(l, 1).equals("8") && t(l, 2).startsWith("%t") && k == 3
                            && startsAccess(cur, i, lp.b)) {
                        starts.merge(s, 1, Integer::sum);
                    }
                }
            }
            for (Map.Entry<String, Integer> e : starts.entrySet()) {
                String s = e.getKey();
                long so = Long.parseLong(s.substring(1));
                if (so < -allocSize || e.getValue() < MIN_READS || overlaps(writes, so) || isExposed(exposedOffsets, so)) {
                    continue;
                }
                extra++;
                String x = "$" + (-(allocSize + 8L * extra));
                for (int i = lp.h; i <= lp.b; i++) {
                    List<BytecodeToken> l = cur.get(i);
                    if (l.size() == 4 && t(l, 0).equals("R_MOV") && t(l, 1).equals("8") && t(l, 2).startsWith("%t") && t(l, 3).equals(s)) {
                        cur.set(i, mk(l.get(0), "R_MOV", "8", t(l, 2), x));
                    }
                }
                copyOf.put(x, s);
                before.computeIfAbsent(lp.h, q -> new ArrayList<>()).add(mk(cur.get(lp.h).get(0), "R_MOV", "8", x, s));
                long w = 1L;
                for (int d = 0; d <= Math.min(lp.depth, 3); d++) {
                    w *= 8;
                }
                hints.add(mk(cur.get(lp.h).get(0), "REGHINT", x.substring(1), "8", String.valueOf(w * e.getValue())));
            }
        }
        if (extra == 0) {
            return null;
        }
        hoisted.put(t(fn.get(0), 1), new Object[] {allocSize, copyOf});
        long newAlloc = allocSize + 8L * extra;
        newAlloc = (newAlloc + 15) / 16 * 16;
        List<List<BytecodeToken>> res = new ArrayList<>(n + 2 * extra + 2);
        for (int i = 0; i < n; i++) {
            List<List<BytecodeToken>> ins = before.get(i);
            if (ins != null) {
                res.addAll(ins);
            }
            List<BytecodeToken> l = cur.get(i);
            if (i == allocIdx) {
                res.add(mk(l.get(0), "ALLOC", String.valueOf(newAlloc)));
                res.addAll(hints);
            } else {
                res.add(l);
            }
        }
        return res;
    }


    /**
     * How many bytes the mention of slot operand k of this line may store to (0 = the line only reads it). Known shapes give
     * their width; any other shape is unknown and counts as "everything from the slot upward" (a variable occupies the bytes
     * above its offset, so an unknown access at T can reach any slot at or above T).
     */
    private static long writeWidth(String m, List<BytecodeToken> l, int k) {
        switch (m) {
            case "R_MOV":
                return l.size() == 4 && k == 3 ? 0 : (l.size() == 4 && k == 2 ? 8 : UNKNOWN);
            case "R_LD":
                return l.size() == 4 && k == 3 ? 0 : UNKNOWN;
            case "R_ST":
                return l.size() == 4 && k == 2 ? widthOf(t(l, 1)) : (k == 3 ? 0 : UNKNOWN);
            case "R_RMW":
                return k == 3 ? 8 : (k == 4 ? 0 : UNKNOWN);
            case "R_LEA": // R_LEA %tD base idx scale [disp]: the index operand is a read, a slot base is an address
                return (l.size() == 5 || l.size() == 6) && k == 3 ? 0 : UNKNOWN;
            case "ADDR":
                return l.size() == 3 && k == 2 ? widthOf(t(l, 1)) : UNKNOWN;
            case "PUSH":
            case "R_PUSH":
            case "R_ARG":
            case "R_FARG":
            case "R_RET":
            case "R_RETF":
            case "R_UN":
            case "R_BIN":
            case "R_FBIN":
            case "R_FCMP":
            case "R_BRC":
                return 0;
            default:
                return UNKNOWN;
        }
    }

    private static final long UNKNOWN = 1L << 40;

    private static long widthOf(String s) {
        try {
            long v = Long.parseLong(s);
            return v > 0 ? v : UNKNOWN;
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    /** may any recorded store touch the 8 bytes at slot offset s? */
    private static boolean overlaps(List<long[]> writes, long s) {
        for (long[] w : writes) {
            if (w[0] < s + 8 && w[0] + w[1] > s) {
                return true;
            }
        }
        return false;
    }

    /** an ADDR_OF/R_ARGA at or below the slot (within 4 KiB) may be a pointer to an object containing it */
    private static boolean isExposed(List<Long> exposedOffsets, long s) {
        for (long t : exposedOffsets) {
            if (t <= s && s - t < 4096) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs after {@link RegVarPromotionPass}. A copy slot that got no register is undone (reads point at the original slot again, the
     * copy line goes, the frame returns to its old size): a copy that stays in memory only adds a move per loop entry and spreads
     * the frame over more cache lines. A copy that did get a register no longer needs frame space, so the frame is shrunk back in
     * every case. Then, for the copies that live in registers, a copy into a temp that the next line immediately overwrites is
     * dropped,
     * <pre>
     *   R_MOV 8 %tA %vK ; R_LD n %tA %tA              ->  R_LD n %tA %vK
     *   R_MOV 8 %tA %vK ; R_LEA %tA %tA idx sc [disp] ->  R_LEA %tA %vK idx sc [disp]     (idx is not %tA)
     * </pre>
     * (the temp is redefined by the second line, so nothing else can have read the copy).
     */
    @SuppressWarnings("unchecked")
    public List<List<BytecodeToken>> finish(List<List<BytecodeToken>> lines) {
        if (!enabled || hoisted.isEmpty()) {
            return lines;
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            List<BytecodeToken> line = lines.get(i);
            if (!line.isEmpty() && line.get(0).text.equals("FUNC_START") && line.size() >= 2 && hoisted.containsKey(t(line, 1))) {
                int end = i;
                while (end < lines.size() && !(!lines.get(end).isEmpty() && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                int stop = Math.min(end + 1, lines.size());
                Object[] info = hoisted.get(t(line, 1));
                out.addAll(finishFunction(lines.subList(i, stop), (Long) info[0], (Map<String, String>) info[1]));
                i = stop;
                continue;
            }
            out.add(line);
            i++;
        }
        return out;
    }

    private static List<List<BytecodeToken>> finishFunction(List<List<BytecodeToken>> fn, long origAlloc, Map<String, String> copyOf) {
        // which copy slots still exist as memory slots (every mention is renamed or none is)
        Set<String> alive = new HashSet<>();
        for (List<BytecodeToken> l : fn) {
            boolean isCopyLine = l.size() == 4 && t(l, 0).equals("R_MOV") && copyOf.containsKey(t(l, 2)) && t(l, 3).equals(copyOf.get(t(l, 2)));
            if (isCopyLine) {
                continue;
            }
            for (int k = 1; k < l.size(); k++) {
                if (copyOf.containsKey(t(l, k))) {
                    alive.add(t(l, k));
                }
            }
        }
        List<List<BytecodeToken>> undone = new ArrayList<>(fn.size());
        for (List<BytecodeToken> l : fn) {
            if (l.size() == 4 && t(l, 0).equals("R_MOV") && alive.contains(t(l, 2)) && t(l, 3).equals(copyOf.get(t(l, 2)))) {
                continue; // the copy of a slot that stayed in memory
            }
            if (l.size() == 2 && t(l, 0).equals("ALLOC")) {
                undone.add(mk(l.get(0), "ALLOC", String.valueOf(origAlloc)));
                continue;
            }
            boolean touch = false;
            for (int k = 1; k < l.size() && !touch; k++) {
                touch = alive.contains(t(l, k));
            }
            if (touch) {
                List<BytecodeToken> n = new ArrayList<>(l);
                for (int k = 1; k < n.size(); k++) {
                    if (alive.contains(t(l, k))) {
                        n.set(k, new BytecodeToken(copyOf.get(t(l, k)), l.get(k).file, l.get(k).line, BytecodeToken.Kind.CODE));
                    }
                }
                undone.add(n);
            } else {
                undone.add(l);
            }
        }
        List<List<BytecodeToken>> lines = undone;
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            List<BytecodeToken> a = lines.get(i);
            if (i + 1 < lines.size() && a.size() == 4 && t(a, 0).equals("R_MOV") && t(a, 1).equals("8") && t(a, 2).startsWith("%t")
                    && t(a, 3).startsWith("%v")) {
                List<BytecodeToken> b = lines.get(i + 1);
                String tA = t(a, 2);
                String vK = t(a, 3);
                if (b.size() == 4 && t(b, 0).equals("R_LD") && t(b, 2).equals(tA) && t(b, 3).equals(tA)) {
                    out.add(mk(b.get(0), "R_LD", t(b, 1), tA, vK));
                    i++;
                    continue;
                }
                if ((b.size() == 5 || b.size() == 6) && t(b, 0).equals("R_LEA") && t(b, 1).equals(tA) && t(b, 2).equals(tA)
                        && !t(b, 3).equals(tA)) {
                    List<BytecodeToken> n = new ArrayList<>(b);
                    n.set(2, new BytecodeToken(vK, b.get(2).file, b.get(2).line, BytecodeToken.Kind.CODE));
                    out.add(n);
                    i++;
                    continue;
                }
            }
            out.add(a);
        }
        return out;
    }

    private static boolean isSlot(String s) {
        try {
            Long.parseLong(s.substring(1));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** the read at i is directly followed by "R_LD 8 %t %t" (length) or "R_LEA %t %t ..." (element address) on the same temp */
    private static boolean startsAccess(List<List<BytecodeToken>> cur, int i, int bound) {
        if (i + 1 > bound) {
            return false;
        }
        List<BytecodeToken> r = cur.get(i);
        List<BytecodeToken> nx = cur.get(i + 1);
        String tmp = t(r, 2);
        if (nx.size() == 4 && t(nx, 0).equals("R_LD") && t(nx, 1).equals("8") && t(nx, 2).equals(tmp) && t(nx, 3).equals(tmp)) {
            return true;
        }
        return (nx.size() == 5 || nx.size() == 6) && t(nx, 0).equals("R_LEA") && t(nx, 2).equals(tmp);
    }

    /** true when no jump from outside [h,b] lands on a label inside it (the header itself is checked by the caller) */
    private static boolean singleEntry(List<List<BytecodeToken>> fn, Loop lp, Map<String, Integer> labelDef) {
        Set<String> inside = new HashSet<>();
        for (int i = lp.h + 1; i <= lp.b; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (isLabelDef(l)) {
                inside.add(t(l, 0).substring(0, t(l, 0).length() - 1));
            }
        }
        if (inside.isEmpty()) {
            return true;
        }
        for (int i = 0; i < fn.size(); i++) {
            if (i >= lp.h && i <= lp.b) {
                continue;
            }
            List<BytecodeToken> l = fn.get(i);
            if (l.isEmpty() || isLabelDef(l)) {
                continue;
            }
            for (int k = 1; k < l.size(); k++) {
                if (inside.contains(t(l, k))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<BytecodeToken> mk(BytecodeToken ref, String... texts) {
        List<BytecodeToken> l = new ArrayList<>(texts.length);
        for (String s : texts) {
            l.add(new BytecodeToken(s, ref.file, ref.line, BytecodeToken.Kind.CODE));
        }
        return l;
    }
}

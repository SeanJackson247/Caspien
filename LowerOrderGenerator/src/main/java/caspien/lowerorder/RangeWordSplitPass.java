package caspien.lowerorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 16-byte range values built or copied word by word instead of through the stack (runs on the STACK-form text, after
 * RangeEndHintPass and before RegisterFormPass; only when "deferred-operands" is on).
 *
 * <p>A `range` is two 8-byte words (start at the lower address). The lowering of `@recursive` functions, `for` loops and
 * ordinary range code builds and copies them with 16-byte stack operations that RegisterFormPass cannot fuse:
 *
 * <pre>
 *   ADDR 16 $X ; PUSH 8 a ; PUSH 8 b ; ASSIGN 16 16 16      (construction)
 *   ADDR 16 $X ; PUSH 16 $Y ; ASSIGN 16 16 16                (copy)
 * </pre>
 *
 * Each becomes two independent 8-byte assignments, which the register-form pass turns into plain moves:
 *
 * <pre>
 *   ADDR 8 $X ; PUSH 8 a ; ASSIGN 8 8 8 ; ADDR 8 $X+8 ; PUSH 8 b ; ASSIGN 8 8 8
 * </pre>
 *
 * Conditions (anything else is left byte for byte): `a` and `b` are `$slot`, an integer literal or `ARGn`; a slot operand
 * is read AFTER the first word of the destination was written (b) so its 8 bytes must not overlap $X..$X+16; a copy needs
 * $Y == $X (left alone, it is a no-op) or $Y..$Y+16 disjoint from $X..$X+16. The bytes written are exactly the same.
 */
public class RangeWordSplitPass {

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        int n = lines.size();
        while (i < n) {
            if (!lines.get(i).isEmpty() && lines.get(i).get(0).text.equals("FUNC_START")) {
                int end = i;
                while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                int stop = Math.min(end + 1, n);
                out.addAll(hint(split(lines.subList(i, stop), splitSlots)));
                i = stop;
            } else {
                out.add(lines.get(i));
                i++;
            }
        }
        return out;
    }

    private final Set<Long> splitSlots = new HashSet<>();

    private List<List<BytecodeToken>> split(List<List<BytecodeToken>> lines, Set<Long> slots) {
        slots.clear();
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int n = lines.size();
        int i = 0;
        while (i < n) {
            if (i + 3 < n && addr16(lines.get(i)) != null) {
                long x = addr16(lines.get(i));
                List<BytecodeToken> l1 = lines.get(i + 1);
                List<BytecodeToken> l2 = lines.get(i + 2);
                List<BytecodeToken> l3 = lines.get(i + 3);
                if (isAssign16(l2) && push16(l1) != null) {
                    long y = push16(l1);
                    if (y == x) {
                        i += 3;   // copying a range onto itself: nothing to do
                        continue;
                    }
                    if (y + 16 <= x || x + 16 <= y) {
                        BytecodeToken ref = lines.get(i).get(0);
                        slots.add(x);
                        out.add(mk(ref, "ADDR", "8", "$" + x));
                        out.add(mk(ref, "PUSH", "8", "$" + y));
                        out.add(mk(ref, "ASSIGN", "8", "8", "8"));
                        out.add(mk(ref, "ADDR", "8", "$" + (x + 8)));
                        out.add(mk(ref, "PUSH", "8", "$" + (y + 8)));
                        out.add(mk(ref, "ASSIGN", "8", "8", "8"));
                        i += 3;
                        continue;
                    }
                } else if (isAssign16(l3) && operand8(l1, x, false) && operand8(l2, x, true)) {
                    BytecodeToken ref = lines.get(i).get(0);
                    slots.add(x);
                    out.add(mk(ref, "ADDR", "8", "$" + x));
                    out.add(l1);
                    out.add(mk(ref, "ASSIGN", "8", "8", "8"));
                    out.add(mk(ref, "ADDR", "8", "$" + (x + 8)));
                    out.add(l2);
                    out.add(mk(ref, "ASSIGN", "8", "8", "8"));
                    i += 4;
                    continue;
                }
            }
            out.add(lines.get(i));
            i++;
        }
        return out;
    }

    /** REGHINT lines for the two words of every split range whose every mention is a plain 8-byte one (the promotion re-checks all of it). */
    private List<List<BytecodeToken>> hint(List<List<BytecodeToken>> fn) {
        if (splitSlots.isEmpty()) {
            return fn;
        }
        int n = fn.size();
        Map<String, Integer> labelAt = new HashMap<>();
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.size() == 1 && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":")) {
                String tx = l.get(0).text;
                labelAt.put(tx.substring(0, tx.length() - 1), i);
            }
        }
        int[] depth = new int[n + 1];
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.size() == 2 && l.get(0).text.equals("JMP")) {
                Integer a = labelAt.get(l.get(1).text);
                if (a != null && a < i) {
                    for (int k = a; k <= i; k++) {
                        depth[k]++;
                    }
                }
            }
        }
        Set<Long> hinted = new HashSet<>();
        for (List<BytecodeToken> l : fn) {
            if (l.size() == 4 && l.get(0).text.equals("REGHINT") && num(l.get(1).text) != null) {
                hinted.add(num(l.get(1).text));
            }
        }
        List<long[]> hints = new ArrayList<>();
        for (long x : splitSlots) {
            long[] w = usable(fn, x, depth);
            if (w == null) {
                continue;
            }
            if (w[0] >= 8 && !hinted.contains(x)) {
                hints.add(new long[] {x, w[0]});
            }
            if (w[1] >= 8 && !hinted.contains(x + 8)) {
                hints.add(new long[] {x + 8, w[1]});
            }
        }
        if (hints.isEmpty()) {
            return fn;
        }
        List<List<BytecodeToken>> res = new ArrayList<>(n + hints.size());
        boolean done = false;
        for (List<BytecodeToken> l : fn) {
            res.add(l);
            if (!done && !l.isEmpty() && l.get(0).text.equals("ALLOC")) {
                done = true;
                for (long[] h : hints) {
                    res.add(mk(l.get(0), "REGHINT", String.valueOf(h[0]), "8", String.valueOf(h[1])));
                }
            }
        }
        return done ? res : fn;
    }

    /** weights {start word, end word}, or null when some mention is not a plain 8-byte access or a wide access overlaps. */
    private static long[] usable(List<List<BytecodeToken>> fn, long x, int[] depth) {
        long[] w = new long[2];
        for (int i = 0; i < fn.size(); i++) {
            List<BytecodeToken> l = fn.get(i);
            boolean plain8 = l.size() >= 3 && (l.get(0).text.equals("PUSH") || l.get(0).text.equals("ADDR")) && l.get(1).text.equals("8");
            for (int k = 1; k < l.size(); k++) {
                Long o = slotOff(l.get(k).text);
                if (o == null) {
                    continue;
                }
                if (o == x || o == x + 8) {
                    if (!(plain8 && k == 2)) {
                        return null;
                    }
                    w[o == x ? 0 : 1] += wt(depth[i]);
                } else if (o > x && o < x + 16) {
                    return null;
                }
            }
            if (l.size() >= 3 && (l.get(0).text.equals("PUSH") || l.get(0).text.equals("ADDR"))) {
                Long o = slotOff(l.get(2).text);
                Long sz = num(l.get(1).text);
                if (o != null && sz != null && o < x + 16 && o + sz > x && !(o == x || o == x + 8) && !(o + sz <= x)) {
                    if (o < x || sz > 8) {
                        return null;
                    }
                }
            }
        }
        return w;
    }

    private static long wt(int depth) {
        long v = 1;
        for (int k = 0; k < Math.min(depth, 4); k++) {
            v *= 8;
        }
        return v;
    }

    private static Long addr16(List<BytecodeToken> l) {
        if (l.size() == 3 && t(l, 0).equals("ADDR") && t(l, 1).equals("16")) {
            return slotOff(t(l, 2));
        }
        return null;
    }

    private static Long push16(List<BytecodeToken> l) {
        if (l.size() == 3 && t(l, 0).equals("PUSH") && t(l, 1).equals("16")) {
            return slotOff(t(l, 2));
        }
        return null;
    }

    private static boolean isAssign16(List<BytecodeToken> l) {
        return l.size() == 4 && t(l, 0).equals("ASSIGN") && t(l, 1).equals("16") && t(l, 2).equals("16") && t(l, 3).equals("16");
    }

    /** PUSH 8 of an integer literal, ARGn, or a slot; a slot read after the destination's first word was written must not overlap it. */
    private static boolean operand8(List<BytecodeToken> l, long x, boolean afterFirstWrite) {
        if (l.size() != 3 || !t(l, 0).equals("PUSH") || !t(l, 1).equals("8")) {
            return false;
        }
        String o = t(l, 2);
        if (num(o) != null) {
            return true;
        }
        if (o.matches("ARG[0-9]+")) {
            return true;
        }
        Long s = slotOff(o);
        if (s == null) {
            return false;
        }
        return !afterFirstWrite || s + 8 <= x || x + 16 <= s;
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }

    private static Long num(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long slotOff(String s) {
        if (s.length() < 2 || s.charAt(0) != '$') {
            return null;
        }
        return num(s.substring(1));
    }

    private static List<BytecodeToken> mk(BytecodeToken ref, String... texts) {
        List<BytecodeToken> r = new ArrayList<>(texts.length);
        for (String x : texts) {
            r.add(new BytecodeToken(x, ref.file, ref.line, BytecodeToken.Kind.CODE));
        }
        return r;
    }
}

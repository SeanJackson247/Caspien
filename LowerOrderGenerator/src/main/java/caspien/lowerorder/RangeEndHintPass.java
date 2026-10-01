package caspien.lowerorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hidden range of a `for` loop, split so its END can live in a variable register (runs on the STACK-form text, after
 * RangeCheckFusionPass and before RegisterFormPass; only when both "deferred-operands" and "variables-in-registers" are on).
 *
 * <p>A `for i in a..b` loop builds its range as one 16-byte value in a hidden frame slot and tests `i < end` on every iteration:
 *
 * <pre>
 *   ADDR 16 $r ; &lt;a&gt; ; &lt;b&gt; ; ASSIGN 16 16 16          ($r = start, $r+8 = end: the first pushed word is the lower one)
 *   ...  PUSH 8 $i ; PUSH 8 $r+8 ; LT_INT 8 ; CMP ; JMP @for_end
 * </pre>
 *
 * Nothing names `$r+8` as a variable, so the register promotion never saw it and every iteration read the end from memory.
 * This pass rewrites the construction into two 8-byte stores (the memory layout is unchanged, so every other reader still
 * sees the same bytes):
 *
 * <pre>
 *   ADDR 8 $r ; &lt;a&gt; ; ASSIGN 8 8 8 ; ADDR 8 $r+8 ; &lt;b&gt; ; ASSIGN 8 8 8
 * </pre>
 *
 * and adds a "REGHINT $r+8 8 weight" line (right after the function's ALLOC, where the Optimizer's hints are), so
 * RegVarPromotionPass treats the end like any other hot scalar. The promotion's own all-or-nothing rule applies: if the
 * slot stays in memory the program is the same as before, only with two 8-byte stores instead of one 16-byte one.
 *
 * <p>Conditions (anything else is left alone): the construction is exactly ADDR 16 $r, two complete 8-byte value
 * expressions built only from `PUSH 8 x` and size-8 `ADD_INT`/`SUB_INT`/`MUL_INT`/`SHR`/`SHL`/`SAR`/`BITS_AND`/`BITS_OR`/`BITS_XOR` (so their stack effect is
 * known and neither can read the half-written range), then ASSIGN 16 16 16; every OTHER mention of `$r` in the function is
 * `PUSH 8 $r` or another construction of this same shape; every other mention of `$r+8` is `PUSH 8 $r+8`; no token names an
 * offset strictly between $r and $r+16 except those two. The weight of the hint is the sum over the mentions of `$r+8` of
 * 8^min(loopDepth, 4), a backward jump to an earlier label marking a loop (the same rule as the Optimizer's hints); a weight
 * below 8 gets no hint.
 */
public class RangeEndHintPass {

    private static final Set<String> BIN = new HashSet<>(java.util.Arrays.asList("ADD_INT", "SUB_INT", "MUL_INT", "SHR", "SHL", "SAR", "BITS_AND", "BITS_OR", "BITS_XOR"));

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        int n = lines.size();
        while (i < n) {
            if (isMn(lines.get(i), "FUNC_START")) {
                int end = i;
                while (end < n && !isMn(lines.get(end), "FUNC_END")) {
                    end++;
                }
                int stop = Math.min(end + 1, n);
                out.addAll(function(lines.subList(i, stop)));
                i = stop;
            } else {
                out.add(lines.get(i));
                i++;
            }
        }
        return out;
    }

    /** one candidate construction: the index of its ADDR line, the split point of its two expressions, the ASSIGN index */
    private static final class Site {
        int addr;
        int split;   // first line of the second expression
        int assign;
        long off;
    }

    private List<List<BytecodeToken>> function(List<List<BytecodeToken>> fn) {
        int n = fn.size();
        List<Site> sites = new ArrayList<>();
        for (int i = 0; i + 3 < n; i++) {
            Site s = match(fn, i);
            if (s != null) {
                sites.add(s);
            }
        }
        if (sites.isEmpty()) {
            return fn;
        }
        // loop depth per line: a backward jump to an earlier label defines a span
        Map<String, Integer> labelAt = new HashMap<>();
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.size() == 1 && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":")) {
                String t = l.get(0).text;
                labelAt.put(t.substring(0, t.length() - 1), i);
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
        // group the sites by slot; a slot is usable only if every mention outside its constructions is a plain 8-byte read
        Map<Long, List<Site>> bySlot = new HashMap<>();
        for (Site s : sites) {
            bySlot.computeIfAbsent(s.off, k -> new ArrayList<>()).add(s);
        }
        Set<Integer> splitAddr = new HashSet<>();
        List<long[]> hints = new ArrayList<>(); // {offset of the end slot, weight}
        List<Site> accepted = new ArrayList<>();
        for (Map.Entry<Long, List<Site>> e : bySlot.entrySet()) {
            long ro = e.getKey();
            long weight = usable(fn, ro, e.getValue(), depth);
            if (weight < 0) {
                continue;
            }
            accepted.addAll(e.getValue());
            if (weight >= 8) {
                hints.add(new long[] {ro + 8, weight});
            }
        }
        if (accepted.isEmpty()) {
            return fn;
        }
        Map<Integer, Site> byAddr = new HashMap<>();
        for (Site s : accepted) {
            byAddr.put(s.addr, s);
        }
        List<List<BytecodeToken>> res = new ArrayList<>(n + 4 + hints.size());
        boolean hinted = false;
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            Site s = byAddr.get(i);
            if (s != null) {
                // ADDR 8 $r ; <a> ; ASSIGN 8 8 8 ; ADDR 8 $r+8 ; <b> ; ASSIGN 8 8 8
                res.add(mk(l.get(0), "ADDR", "8", "$" + s.off));
                for (int k = s.addr + 1; k < s.split; k++) {
                    res.add(fn.get(k));
                }
                res.add(mk(l.get(0), "ASSIGN", "8", "8", "8"));
                res.add(mk(l.get(0), "ADDR", "8", "$" + (s.off + 8)));
                for (int k = s.split; k < s.assign; k++) {
                    res.add(fn.get(k));
                }
                res.add(mk(l.get(0), "ASSIGN", "8", "8", "8"));
                i = s.assign;
                continue;
            }
            res.add(l);
            if (!hinted && isMn(l, "ALLOC")) {
                hinted = true;
                for (long[] h : hints) {
                    res.add(mk(l.get(0), "REGHINT", String.valueOf(h[0]), "8", String.valueOf(h[1])));
                }
            }
        }
        if (!hinted && !hints.isEmpty()) {
            return fn; // no ALLOC line to hang the hint on: leave the function exactly as it was
        }
        return res;
    }

    /**
     * -1 when the slot cannot be treated this way, else the weight of its end slot. Every other mention of $ro must be PUSH 8 $ro,
     * every mention of $ro+8 PUSH 8 $ro+8 (or the construction itself), and no token may name an offset strictly inside the 16 bytes.
     */
    private static long usable(List<List<BytecodeToken>> fn, long ro, List<Site> sites, int[] depth) {
        String rs = "$" + ro;
        String es = "$" + (ro + 8);
        Set<Integer> own = new HashSet<>();
        Set<Integer> addrLines = new HashSet<>();
        for (Site s : sites) {
            addrLines.add(s.addr);
            for (int k = s.addr; k <= s.assign; k++) {
                own.add(k);
            }
        }
        long weight = 0;
        for (Site s : sites) {
            weight += w(depth[s.addr]); // the store of the end
        }
        for (int i = 0; i < fn.size(); i++) {
            List<BytecodeToken> l = fn.get(i);
            if (own.contains(i)) {
                // inside a construction: the two value expressions may not name the range at all (the ADDR line itself may)
                if (!addrLines.contains(i)) {
                    for (int k = 1; k < l.size(); k++) {
                        Long o = slotOff(l.get(k).text);
                        if (o != null && o > ro - 8 && o < ro + 16) {
                            return -1;
                        }
                    }
                }
                continue;
            }
            for (int k = 1; k < l.size(); k++) {
                String tk = l.get(k).text;
                Long o = slotOff(tk);
                if (o == null) {
                    continue;
                }
                if (o == ro) {
                    if (!(isPush8(l) && k == 2)) {
                        return -1;
                    }
                } else if (o == ro + 8) {
                    if (!(isPush8(l) && k == 2)) {
                        return -1;
                    }
                    weight += w(depth[i]);
                } else if (o > ro && o < ro + 16) {
                    return -1;
                } else if (o > ro - 16 && o < ro) {
                    // a block that starts below $r and reaches into it cannot be told from here: only an exact slot name is allowed
                    // to sit closer than 16 bytes below, and that is a different variable, so nothing to do
                    continue;
                }
            }
            // a wide access that STARTS below $r can still overlap it ("PUSH 24 $r-8"): such a line names a slot below ro with size > distance
            if (l.size() >= 3 && (isMn(l, "PUSH") || isMn(l, "ADDR"))) {
                Long o = slotOff(l.get(2).text);
                Long sz = num(l.get(1).text);
                if (o != null && sz != null && o < ro && o + sz > ro) {
                    return -1;
                }
            }
        }
        return weight;
    }

    private static long w(int depth) {
        long v = 1;
        for (int k = 0; k < Math.min(depth, 4); k++) {
            v *= 8;
        }
        return v;
    }

    /** ADDR 16 $r ; expression ; expression ; ASSIGN 16 16 16 starting at i, or null */
    private static Site match(List<List<BytecodeToken>> fn, int i) {
        List<BytecodeToken> a = fn.get(i);
        if (!(a.size() == 3 && isMn(a, "ADDR") && t(a, 1).equals("16"))) {
            return null;
        }
        Long ro = slotOff(t(a, 2));
        if (ro == null) {
            return null;
        }
        int depth = 0;
        int split = -1;
        for (int j = i + 1; j < fn.size() && j <= i + 24; j++) {
            List<BytecodeToken> l = fn.get(j);
            if (isMn(l, "ASSIGN")) {
                if (depth == 2 && split > 0 && l.size() == 4 && t(l, 1).equals("16") && t(l, 2).equals("16") && t(l, 3).equals("16")) {
                    Site s = new Site();
                    s.addr = i;
                    s.split = split;
                    s.assign = j;
                    s.off = ro;
                    return s;
                }
                return null;
            }
            if (isPush8(l)) {
                depth++;
            } else if (l.size() == 2 && BIN.contains(t(l, 0)) && t(l, 1).equals("8")) {
                depth--;
                if (depth < 1) {
                    return null;
                }
            } else {
                return null;
            }
            if (depth == 1) {
                split = j + 1; // the last point where one value is pending: the second expression starts after it
            }
            if (depth > 2 && split < 0) {
                return null;
            }
        }
        return null;
    }

    private static boolean isMn(List<BytecodeToken> l, String m) {
        return !l.isEmpty() && l.get(0).text.equals(m);
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }

    private static boolean isPush8(List<BytecodeToken> l) {
        return l.size() == 3 && t(l, 0).equals("PUSH") && t(l, 1).equals("8");
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

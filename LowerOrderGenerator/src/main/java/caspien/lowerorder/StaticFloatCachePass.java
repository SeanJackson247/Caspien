package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only float statics in spare xmm registers for the length of a call-free loop. Runs after {@link FloatIntrinsicPass}
 * (so a libm sqrt is already one instruction and no longer a call), only with "float-variables-in-registers: on".
 *
 * <p>In a loop that reads a `let static` float several times and never writes it, every read is a load
 * ({@code R_LDX n %yK &g}). This pass loads the static once just before the loop header ({@code R_LDX n %zK &g}) and turns the
 * reads inside the loop into register copies ({@code R_XMOV %yK %zK}). {@code %z0..%z3} are xmm0-3 (argument registers: free in
 * a loop without calls); {@code %x} registers above the highest one the function uses join the pool.
 *
 * <p>A loop qualifies only when ALL of these hold:
 * <ul>
 *   <li>every line in it is one of a small whitelist of register-form ops (no call, no spill/reload, no stack-form op that
 *       might use xmm0/xmm1 as its own scratch);</li>
 *   <li>the header label is jumped to only from inside the loop and no label inside is jumped to from outside;</li>
 *   <li>the static is "plain" program-wide: its address appears only as the address operand of R_LDX/R_STX/R_LD/R_ST (never
 *       as a pointer, an array base or an argument, so nothing can reach it any other way) and is not written in the loop;</li>
 *   <li>the program has no inline assembly and no thread start ({@code par_call}/{@code await_call}/{@code pthread}), so
 *       another thread cannot change the static under the loop;</li>
 *   <li>the static is read at least twice in the loop.</li>
 * </ul>
 * Loops are taken outermost first; registers an enclosing loop uses are not reused inside it.
 */
public class StaticFloatCachePass {

    private static final Set<String> OPS = new HashSet<>(Arrays.asList(
            "R_MOV", "R_LD", "R_LDI", "R_LDD", "R_ST", "R_STI", "R_STD", "R_LEA", "R_BIN", "R_BRC", "R_BRCM", "R_FBIN", "R_FBINX",
            "R_FCMP", "R_BRF", "R_LDX", "R_STX", "R_FSQRT", "R_XMOV", "R_XTOG", "R_GTOX", "R_RMW", "JMP"));
    private static final Set<String> ADDR_OPS = new HashSet<>(Arrays.asList("R_LDX", "R_STX", "R_LD", "R_ST"));
    private static final int Z_COUNT = 4;
    private static final int X_COUNT = 6;
    private static final int MAX_LINES = 20000;

    private final boolean enabled;

    public StaticFloatCachePass(boolean enabled) {
        this.enabled = enabled;
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }

    private static boolean isLabelDef(List<BytecodeToken> l) {
        return l.size() == 1 && t(l, 0).startsWith("@") && t(l, 0).endsWith(":");
    }

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return lines;
        }
        Set<String> escaped = new HashSet<>();
        for (List<BytecodeToken> l : lines) {
            if (l.isEmpty()) {
                continue;
            }
            String op = t(l, 0);
            if (op.equals("ASM_START")) {
                return lines;
            }
            for (int k = 1; k < l.size(); k++) {
                String s = t(l, k);
                if (s.startsWith("&")) {
                    if (!ADDR_OPS.contains(op)) {
                        escaped.add(s);
                    }
                } else if (s.contains("par_call") || s.contains("await_call") || s.contains("pthread")) {
                    return lines;
                }
            }
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size() + 16);
        int i = 0;
        while (i < lines.size()) {
            List<BytecodeToken> line = lines.get(i);
            if (!line.isEmpty() && t(line, 0).equals("FUNC_START")) {
                int end = i;
                while (end < lines.size() && !(!lines.get(end).isEmpty() && t(lines.get(end), 0).equals("FUNC_END"))) {
                    end++;
                }
                int stop = Math.min(end + 1, lines.size());
                List<List<BytecodeToken>> fn = lines.subList(i, stop);
                out.addAll(fn.size() <= MAX_LINES ? cacheFunction(fn, escaped) : fn);
                i = stop;
                continue;
            }
            out.add(line);
            i++;
        }
        return out;
    }

    private static final class Loop {
        int h;
        int b;
        Set<String> used = new HashSet<>();
    }

    private List<List<BytecodeToken>> cacheFunction(List<List<BytecodeToken>> fn, Set<String> escaped) {
        int n = fn.size();
        Map<String, Integer> labelDef = new HashMap<>();
        int maxX = -1;
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.isEmpty()) {
                continue;
            }
            if (isLabelDef(l)) {
                String s = t(l, 0);
                labelDef.put(s.substring(0, s.length() - 1), i);
            }
            for (int k = 1; k < l.size(); k++) {
                String s = t(l, k);
                if (s.length() >= 3 && s.startsWith("%x") && Character.isDigit(s.charAt(2))) {
                    try {
                        maxX = Math.max(maxX, Integer.parseInt(s.substring(2)));
                    } catch (NumberFormatException e) {
                        return fn;
                    }
                }
            }
        }
        // back edges: a branch to a label defined earlier
        Map<Integer, Loop> loops = new HashMap<>();
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.isEmpty() || isLabelDef(l)) {
                continue;
            }
            for (int k = 1; k < l.size(); k++) {
                String s = t(l, k);
                if (s.startsWith("@")) {
                    Integer d = labelDef.get(s);
                    if (d != null && d < i) {
                        Loop lp = loops.computeIfAbsent(d, h -> {
                            Loop x = new Loop();
                            x.h = h;
                            return x;
                        });
                        lp.b = Math.max(lp.b, i);
                    }
                }
            }
        }
        if (loops.isEmpty()) {
            return fn;
        }
        List<Loop> order = new ArrayList<>(loops.values());
        order.sort((a, b) -> a.h != b.h ? Integer.compare(a.h, b.h) : Integer.compare(b.b, a.b));
        List<String> pool = new ArrayList<>();
        for (int k = 0; k < Z_COUNT; k++) {
            pool.add("%z" + k);
        }
        for (int k = maxX + 1; k < X_COUNT; k++) {
            pool.add("%x" + k);
        }
        List<List<BytecodeToken>> cur = new ArrayList<>(fn);
        Map<Integer, List<List<BytecodeToken>>> before = new HashMap<>();
        List<Loop> done = new ArrayList<>();
        for (Loop lp : order) {
            if (!loopOk(cur, lp, labelDef, n)) {
                continue;
            }
            Map<String, Integer> reads = new HashMap<>();
            Map<String, String> width = new HashMap<>();
            Set<String> bad = new HashSet<>();
            for (int i = lp.h; i <= lp.b; i++) {
                List<BytecodeToken> l = cur.get(i);
                if (l.isEmpty() || isLabelDef(l)) {
                    continue;
                }
                String op = t(l, 0);
                for (int k = 1; k < l.size(); k++) {
                    String s = t(l, k);
                    if (!s.startsWith("&")) {
                        continue;
                    }
                    boolean plainRead = op.equals("R_LDX") && l.size() == 4 && k == 3 && (t(l, 1).equals("4") || t(l, 1).equals("8"))
                            && (t(l, 2).startsWith("%y") || t(l, 2).startsWith("%x"));
                    if (plainRead && !escaped.contains(s)) {
                        reads.merge(s, 1, Integer::sum);
                        String w = width.putIfAbsent(s, t(l, 1));
                        if (w != null && !w.equals(t(l, 1))) {
                            bad.add(s);
                        }
                    } else {
                        bad.add(s); // written, or read in another shape
                    }
                }
            }
            Set<String> taken = new HashSet<>();
            for (Loop d : done) {
                if (d.h <= lp.h && d.b >= lp.b) {
                    taken.addAll(d.used);
                }
            }
            List<String> free = new ArrayList<>();
            for (String p : pool) {
                if (!taken.contains(p)) {
                    free.add(p);
                }
            }
            List<String> cands = new ArrayList<>();
            for (Map.Entry<String, Integer> e : reads.entrySet()) {
                if (e.getValue() >= 2 && !bad.contains(e.getKey())) {
                    cands.add(e.getKey());
                }
            }
            if (cands.isEmpty() || free.isEmpty()) {
                continue;
            }
            cands.sort((a, b) -> reads.get(a).equals(reads.get(b)) ? a.compareTo(b) : Integer.compare(reads.get(b), reads.get(a)));
            Map<String, String> assigned = new HashMap<>();
            for (int k = 0; k < cands.size() && k < free.size(); k++) {
                assigned.put(cands.get(k), free.get(k));
            }
            List<List<BytecodeToken>> pre = new ArrayList<>();
            for (String g : cands) {
                String reg = assigned.get(g);
                if (reg == null) {
                    break;
                }
                BytecodeToken ref = cur.get(lp.h).get(0);
                pre.add(new ArrayList<>(Arrays.asList(new BytecodeToken("R_LDX", ref.file, ref.line, BytecodeToken.Kind.CODE),
                        new BytecodeToken(width.get(g), ref.file, ref.line, BytecodeToken.Kind.CODE),
                        new BytecodeToken(reg, ref.file, ref.line, BytecodeToken.Kind.CODE),
                        new BytecodeToken(g, ref.file, ref.line, BytecodeToken.Kind.CODE))));
                lp.used.add(reg);
            }
            for (int i = lp.h; i <= lp.b; i++) {
                List<BytecodeToken> l = cur.get(i);
                if (l.size() == 4 && t(l, 0).equals("R_LDX") && t(l, 3).startsWith("&") && assigned.containsKey(t(l, 3))) {
                    List<BytecodeToken> r = new ArrayList<>();
                    r.add(new BytecodeToken("R_XMOV", l.get(0).file, l.get(0).line, BytecodeToken.Kind.CODE));
                    r.add(l.get(2));
                    r.add(new BytecodeToken(assigned.get(t(l, 3)), l.get(0).file, l.get(0).line, BytecodeToken.Kind.CODE));
                    cur.set(i, r);
                }
            }
            before.computeIfAbsent(lp.h, k -> new ArrayList<>()).addAll(pre);
            done.add(lp);
        }
        if (done.isEmpty()) {
            return fn;
        }
        List<List<BytecodeToken>> res = new ArrayList<>(n + 16);
        for (int i = 0; i < n; i++) {
            List<List<BytecodeToken>> pre = before.get(i);
            if (pre != null) {
                res.addAll(pre);
            }
            res.add(cur.get(i));
        }
        return res;
    }

    /** whitelisted ops only, single entry: no jump to the header from outside, none to an inner label from outside */
    private boolean loopOk(List<List<BytecodeToken>> cur, Loop lp, Map<String, Integer> labelDef, int n) {
        for (int i = lp.h; i <= lp.b; i++) {
            List<BytecodeToken> l = cur.get(i);
            if (l.isEmpty()) {
                return false;
            }
            if (isLabelDef(l)) {
                continue;
            }
            if (!OPS.contains(t(l, 0))) {
                    return false;
            }
        }
        for (int i = 0; i < n; i++) {
            if (i >= lp.h && i <= lp.b) {
                continue;
            }
            List<BytecodeToken> l = cur.get(i);
            if (l.isEmpty() || isLabelDef(l)) {
                continue;
            }
            for (int k = 1; k < l.size(); k++) {
                String s = t(l, k);
                if (s.startsWith("@")) {
                    Integer d = labelDef.get(s);
                    if (d != null && d >= lp.h && d <= lp.b) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}

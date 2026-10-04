package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Marks local variables that are worth keeping in a register, as a HINT for the lower stages:
 *
 *     REGVAR name weight [f]
 *
 * (the optional trailing "f" says the variable is a float, so a lowering that has a separate float register file can use it)
 *
 * placed right after the function's leading ALLOC run. The ALLOC stays (a variable that gets no register simply stays a
 * stack slot, and a lower stage that ignores the hint produces exactly the code it did before). The hint names NO register:
 * which registers exist, which are free and how many is the lowering's business.
 *
 * A local is eligible when
 *   - it is declared by a plain "ALLOC name type" with a plain scalar type (mut/imut u8..u64, s8..s64, f32, bool, char;
 *     no storage keyword, no atomic, no struct/array/range), every declaration of the name in the function having the same
 *     type (the `i` of several `for` loops is one slot), and no '$' hidden name;
 *   - its name appears ONLY as the operand of ALLOC, PUSH and ADDR lines (any other mention, e.g. GT_DESTRUCT, disqualifies);
 *   - no PUSH/ADDR of it is directly followed by ADDR_OF (that is raw/auto/ref taking its address).
 * The weight is the number of PUSH/ADDR mentions, each counted 8^depth where depth is the number of loops (a backward JMP to an
 * already-seen label) containing it, capped at depth 4. Only variables with a weight of at least 8 (used inside a loop) are
 * hinted. A function that already carries REGVAR lines is left alone, so the pass is idempotent inside the fixed-point loop.
 * This pass proves nothing about the lowered code; the LowerOrderGenerator re-checks every mention before it promotes anything.
 */
public class RegVarHintPass implements OptimizationPass {

    private static final Pattern PLAIN_SCALAR =
            Pattern.compile("^(mut|imut)_(u8|u16|u32|u64|s8|s16|s32|s64|f32|f64|bool|char)$");
    private static final long MIN_WEIGHT = 8;

    @Override
    public String name() {
        return "regvar-hint";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>();
        boolean changed = false;
        int i = 0;
        while (i < lines.size()) {
            if (!isMnemonic(lines.get(i), "FUNC_START")) {
                out.add(lines.get(i));
                i++;
                continue;
            }
            int end = i;
            while (end < lines.size() && !isMnemonic(lines.get(end), "FUNC_END")) {
                end++;
            }
            if (end >= lines.size()) {
                end = lines.size() - 1;
            }
            List<List<BytecodeToken>> fn = lines.subList(i, end + 1);
            List<List<BytecodeToken>> hinted = hintFunction(fn);
            if (hinted != null) {
                changed = true;
                out.addAll(hinted);
            } else {
                out.addAll(fn);
            }
            i = end + 1;
        }
        return new PassResult(changed ? out : lines, changed);
    }

    private static boolean isMnemonic(List<BytecodeToken> line, String m) {
        return !line.isEmpty() && line.get(0).kind == BytecodeToken.Kind.CODE && line.get(0).text.equals(m);
    }

    private static String mnemonic(List<BytecodeToken> line) {
        return line.isEmpty() ? "" : line.get(0).text;
    }

    /** Returns the function's lines with REGVAR lines inserted, or null when nothing changes. */
    private List<List<BytecodeToken>> hintFunction(List<List<BytecodeToken>> fn) {
        Map<String, Integer> allocCount = new HashMap<>();
        Map<String, String> allocType = new HashMap<>();
        Set<String> mixedType = new HashSet<>(); // a name declared more than once with different types
        int lastLeadingAlloc = -1;
        boolean leading = true;
        for (int k = 0; k < fn.size(); k++) {
            List<BytecodeToken> l = fn.get(k);
            String m = mnemonic(l);
            if (m.equals("REGVAR")) {
                return null; // already hinted
            }
            if (m.equals("ALLOC") && l.size() == 3) {
                allocCount.merge(l.get(1).text, 1, Integer::sum);
                String prevType = allocType.put(l.get(1).text, l.get(2).text);
                if (prevType != null && !prevType.equals(l.get(2).text)) {
                    mixedType.add(l.get(1).text);
                }
                if (leading) {
                    lastLeadingAlloc = k;
                }
            } else if (!(m.equals("FUNC_START") || m.equals("FUNC_DECORATE") || m.equals("RETURNS") || m.equals("ARG") || m.equals("ALLOC_STATIC"))) {
                leading = false;
            }
        }
        if (lastLeadingAlloc < 0) {
            return null;
        }
        Set<String> candidates = new HashSet<>();
        for (Map.Entry<String, Integer> e : allocCount.entrySet()) {
            String n = e.getKey();
            // A name declared several times with ONE type (the counter `i` of several `for` loops of a function) is one slot
            // for the lower stages (they resolve a name to a single frame offset), so it is one candidate.
            if (!mixedType.contains(n) && !n.startsWith("$") && !n.equals("gt_routine_address") && !n.equals("gt_error_message")
                    && PLAIN_SCALAR.matcher(allocType.get(n)).matches()) {
                candidates.add(n);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // loop depth per line: a backward JMP to an already-seen label closes a loop over [label, jmp]
        int n = fn.size();
        int[] delta = new int[n + 1];
        Map<String, Integer> labelAt = new HashMap<>();
        for (int k = 0; k < n; k++) {
            List<BytecodeToken> l = fn.get(k);
            if (l.size() == 1 && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":")) {
                String t = l.get(0).text;
                labelAt.put(t.substring(0, t.length() - 1), k);
            } else if (isMnemonic(l, "JMP") && l.size() >= 2) {
                Integer at = labelAt.get(l.get(1).text);
                if (at != null) {
                    delta[at]++;
                    delta[k + 1]--;
                }
            }
        }
        int[] depth = new int[n];
        int run = 0;
        for (int k = 0; k < n; k++) {
            run += delta[k];
            depth[k] = run;
        }
        Map<String, Long> weight = new HashMap<>();
        for (int k = 0; k < n; k++) {
            List<BytecodeToken> l = fn.get(k);
            String m = mnemonic(l);
            for (int t = 1; t < l.size(); t++) {
                String tok = l.get(t).text;
                if (!candidates.contains(tok) || l.get(t).kind != BytecodeToken.Kind.CODE) {
                    continue;
                }
                boolean simple = (m.equals("PUSH") || m.equals("ADDR")) && t == 1;
                boolean decl = m.equals("ALLOC") && t == 1;
                if (decl) {
                    continue;
                }
                boolean addressTaken = simple && k + 1 < n && mnemonic(fn.get(k + 1)).equals("ADDR_OF");
                if (!simple || addressTaken) {
                    candidates.remove(tok);
                    weight.remove(tok);
                } else {
                    long w = 1L << (3 * Math.min(depth[k], 4));
                    weight.merge(tok, w, Long::sum);
                }
            }
        }
        List<String> chosen = new ArrayList<>();
        for (String c : candidates) {
            Long w = weight.get(c);
            if (w != null && w >= MIN_WEIGHT) {
                chosen.add(c);
            }
        }
        if (chosen.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(chosen);
        BytecodeToken ref = fn.get(lastLeadingAlloc).get(0);
        List<List<BytecodeToken>> result = new ArrayList<>(fn.subList(0, lastLeadingAlloc + 1));
        for (String c : chosen) {
            List<BytecodeToken> h = new ArrayList<>();
            h.add(new BytecodeToken("REGVAR", ref.file, ref.line, BytecodeToken.Kind.CODE));
            h.add(new BytecodeToken(c, ref.file, ref.line, BytecodeToken.Kind.CODE));
            h.add(new BytecodeToken(Long.toString(Math.min(weight.get(c), 1_000_000_000L)), ref.file, ref.line, BytecodeToken.Kind.CODE));
            if ((allocType.get(c).endsWith("_f32") || allocType.get(c).endsWith("_f64"))) {
                // a class marker only: "this one is a float". Which register file (if any) holds it is the lowering's business.
                h.add(new BytecodeToken("f", ref.file, ref.line, BytecodeToken.Kind.CODE));
            }
            result.add(h);
        }
        result.addAll(fn.subList(lastLeadingAlloc + 1, fn.size()));
        return result;
    }
}

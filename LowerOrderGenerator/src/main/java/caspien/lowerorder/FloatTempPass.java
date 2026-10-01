package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * f32 expression temporaries in xmm registers -- runs last, on the final register-form text, only when compiler.config says
 * "float-temporaries-in-registers: on" (which needs "deferred-operands: on").
 *
 * <p>The register-form pass models every temporary as a general register holding a bit pattern, so each float operation
 * copies its operands into xmm scratch registers, computes, and copies the result back (two {@code movd} per operation).
 * This pass finds a float temp that is produced by a float operation and consumed only by float operations, and keeps it in
 * an xmm register "%yK" for its whole life:
 * <pre>
 *   producers:  R_FBIN OP n %tD a b   -> R_FBINX OP n %yK a b            (n = 4 for f32, 8 for f64)
 *               R_LD n %tD addr       -> R_LDX n %yK addr
 *               R_XTOG n %tD %xJ      -> (uses read %xJ directly when it is not rewritten in between) or R_XMOV %yK %xJ
 *   consumers:  the a/b operand of R_FBIN / R_FBINX / R_FCMP (size n), R_FARG n, R_RETF        -> token %tD becomes %yK
 *               R_ST n addr %tD -> R_STX n addr %yK        R_GTOX n %xJ %tD -> R_XMOV %xJ %yK
 * All the instructions of one chain have the same width n. A chain must have at least one genuinely float consumer (a float
 * operation, a float argument or a float return), so a plain integer copy R_LD 8 / R_ST 8 is never turned into xmm moves.
 * </pre>
 * A "chain" is one definition of a temp up to its last read (the next write of the same temp ends it). A chain is rewritten only
 * if EVERY read of it is one of the consumers above (all-or-nothing, decided on the final text, nothing to roll back), the span
 * between definition and last read holds only register-form lines (no label, jump, call, stack-form instruction), at most
 * {@link #Y_COUNT} chains are live at once, and, if it lies inside a call bracket, that call passes fewer than
 * {@link #Y_COUNT} float arguments (the SysV %y registers are xmm4-7, which are argument registers). Anything else stays as it was.
 * Register-form temps are never live across labels, jumps or call brackets, so a %y register is never live across a call.
 */
public class FloatTempPass {

    /** how many xmm temporaries exist (%y0..%y3 -> SysV xmm4-7, win64 xmm12-15) */
    public static final int Y_COUNT = 4;

    private final boolean enabled;

    public FloatTempPass(boolean enabled) {
        this.enabled = enabled;
    }

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return lines;
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            List<BytecodeToken> line = lines.get(i);
            if (!line.isEmpty() && line.get(0).text.equals("FUNC_START")) {
                int end = i;
                while (end < lines.size() && !(!lines.get(end).isEmpty() && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                int stop = Math.min(end + 1, lines.size());
                processFunction(lines.subList(i, stop), out);
                i = stop;
                continue;
            }
            out.add(line);
            i++;
        }
        return out;
    }

    private static final Set<String> KNOWN_R = new HashSet<>(Arrays.asList(
            "R_MOV", "R_LD", "R_ST", "R_LEA", "R_BIN", "R_UN", "R_FBIN", "R_FCMP", "R_RMW", "R_ARG", "R_ARGA", "R_FARG",
            "R_RET", "R_RETF", "R_PUSH", "R_PUSHA", "R_SETV", "R_XTOG", "R_GTOX", "R_LDX", "R_STX", "R_FBINX", "R_XMOV"));

    /** operand index of the destination temp, or -1 */
    private static int dstIndex(String m) {
        switch (m) {
            case "R_LD": case "R_MOV": case "R_XTOG": return 2;
            case "R_LEA": return 1;
            case "R_BIN": case "R_UN": case "R_FBIN": case "R_FCMP": return 3;
            default: return -1;
        }
    }

    private static final class Chain {
        int def;
        int last = -1;
        boolean ok = true;
        String temp;
        List<int[]> uses = new ArrayList<>(); // {line, operand index}
        int y = -1;
        String w = "4";
        boolean floatUse = false;
    }

    private static String m(List<String> l) {
        return l.isEmpty() ? "" : l.get(0);
    }

    private static boolean isR(List<String> l) {
        String mm = m(l);
        return mm.startsWith("R_") && !mm.equals("R_BRF");
    }

    /** is operand k of line l (which reads a float temp) a place this pass can turn into an xmm register? */
    private static boolean consumerOk(List<String> l, int k, String w) {
        switch (m(l)) {
            case "R_FBIN": case "R_FCMP": case "R_FBINX":
                return l.size() == 6 && l.get(2).equals(w) && (k == 4 || k == 5);
            case "R_FARG":
                return l.size() == 4 && l.get(2).equals(w) && k == 3;
            case "R_RETF":
                return l.size() == 2 && k == 1;
            case "R_ST":
                return l.size() == 4 && l.get(1).equals(w) && k == 3 && !l.get(2).equals(l.get(3));
            case "R_GTOX":
                return l.size() == 4 && l.get(1).equals(w) && k == 3;
            default:
                return false;
        }
    }

    /** a consumer that is a real float operation (not a plain copy) */
    private static boolean isFloatConsumer(List<String> l) {
        switch (m(l)) {
            case "R_FBIN": case "R_FCMP": case "R_FBINX": case "R_FARG": case "R_RETF":
                return true;
            default:
                return false;
        }
    }

    private void processFunction(List<List<BytecodeToken>> fn, List<List<BytecodeToken>> out) {
        int n = fn.size();
        List<List<String>> t = new ArrayList<>(n);
        for (List<BytecodeToken> l : fn) {
            List<String> r = new ArrayList<>(l.size());
            for (BytecodeToken b : l) {
                r.add(b.text);
            }
            t.add(r);
        }
        // float-argument count of every call bracket (an %y register may not sit on an argument register that is in use)
        int[] bracketFargs = new int[n];
        int[] open = new int[n];
        int sp = 0;
        for (int i = 0; i < n; i++) {
            String mm = m(t.get(i));
            if (mm.equals("CC_START")) {
                open[sp++] = i;
            } else if (mm.equals("CC_END")) {
                if (sp > 0) {
                    sp--;
                }
            }
            if (sp > 0) {
                int f = fargIndex(t.get(i));
                if (f >= 0) {
                    for (int q = 0; q < sp; q++) {
                        bracketFargs[open[q]] = Math.max(bracketFargs[open[q]], f + 1);
                    }
                }
            }
        }
        int[] inBracketMax = new int[n];
        sp = 0;
        for (int i = 0; i < n; i++) {
            String mm = m(t.get(i));
            if (mm.equals("CC_START")) {
                open[sp++] = i;
            } else if (mm.equals("CC_END")) {
                if (sp > 0) {
                    sp--;
                }
            }
            int mx = 0;
            for (int q = 0; q < sp; q++) {
                mx = Math.max(mx, bracketFargs[open[q]]);
            }
            inBracketMax[i] = mx;
        }

        // find chains
        List<Chain> chains = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            List<String> l = t.get(d);
            String mm = m(l);
            String tmp = null;
            String w = null;
            if (mm.equals("R_FBIN") && l.size() == 6 && (l.get(2).equals("4") || l.get(2).equals("8"))) {
                tmp = l.get(3);
                w = l.get(2);
            } else if (mm.equals("R_LD") && l.size() == 4 && (l.get(1).equals("4") || l.get(1).equals("8"))) {
                tmp = l.get(2);
                w = l.get(1);
            } else if (mm.equals("R_XTOG") && l.size() == 4) {
                tmp = l.get(2);
                w = l.get(1);
            }
            if (tmp == null || !tmp.startsWith("%t")) {
                continue;
            }
            Chain c = new Chain();
            c.def = d;
            c.temp = tmp;
            c.w = w;
            if (mm.equals("R_FBIN")) {
                c.floatUse = true; // the definition is itself a float operation: a chain whose only reader is a store (`x -= ...` on an array element) is worth keeping in xmm
            }
            for (int j = d + 1; j < n; j++) {
                List<String> lj = t.get(j);
                String mj = m(lj);
                boolean mentions = false;
                for (int k = 1; k < lj.size(); k++) {
                    if (lj.get(k).equals(tmp)) {
                        mentions = true;
                    }
                }
                if (!isR(lj) || !KNOWN_R.contains(mj)) {
                    if (mentions) {
                        c.ok = false;
                    }
                    break; // end of the straight-line run: register-form temps are never live past it
                }
                int dst = dstIndex(mj);
                boolean writes = false;
                for (int k = 1; k < lj.size(); k++) {
                    if (!lj.get(k).equals(tmp)) {
                        continue;
                    }
                    if (k == dst) {
                        writes = true;
                    } else {
                        if (!consumerOk(lj, k, c.w)) {
                            c.ok = false;
                        }
                        if (isFloatConsumer(lj)) {
                            c.floatUse = true;
                        }
                        c.uses.add(new int[] {j, k});
                        c.last = j;
                    }
                }
                if (writes) {
                    break;
                }
            }
            if (c.uses.isEmpty() || !c.floatUse) {
                c.ok = false;
            }
            if (c.ok && inBracketMax[d] >= Y_COUNT) {
                c.ok = false;
            }
            if (c.ok && c.last >= 0 && inBracketMax[c.last] >= Y_COUNT) {
                c.ok = false;
            }
            chains.add(c);
        }

        // assign %y registers (a chain may take the register a chain frees on the very line it is defined)
        boolean[] freeAt = new boolean[Y_COUNT];
        Arrays.fill(freeAt, true);
        int[] busyUntil = new int[Y_COUNT];
        Arrays.fill(busyUntil, -1);
        for (Chain c : chains) {
            if (!c.ok) {
                continue;
            }
            int pick = -1;
            for (int k = 0; k < Y_COUNT; k++) {
                if (busyUntil[k] <= c.def) {
                    pick = k;
                    break;
                }
            }
            if (pick < 0) {
                c.ok = false;
                continue;
            }
            c.y = pick;
            busyUntil[pick] = c.last;
        }

        boolean changed = false;
        for (Chain c : chains) {
            if (!c.ok) {
                continue;
            }
            String y = "%y" + c.y;
            List<String> def = t.get(c.def);
            String dm = m(def);
            String repl = y;
            List<String> newDef = null;
            if (dm.equals("R_FBIN")) {
                newDef = new ArrayList<>(def);
                newDef.set(0, "R_FBINX");
                newDef.set(3, y);
            } else if (dm.equals("R_LD")) {
                newDef = new ArrayList<>(Arrays.asList("R_LDX", c.w, y, def.get(3)));
            } else { // R_XTOG n %tD %xJ
                String xj = def.get(3);
                boolean rewritten = false;
                for (int j = c.def + 1; j <= c.last && !rewritten; j++) {
                    rewritten = writesXmm(t.get(j), xj);
                }
                if (!rewritten) {
                    newDef = new ArrayList<>(Arrays.asList("R_XMOV", xj, xj)); // placeholder, dropped below
                    repl = xj;
                } else {
                    newDef = new ArrayList<>(Arrays.asList("R_XMOV", y, xj));
                }
            }
            for (int[] u : c.uses) {
                List<String> lu = t.get(u[0]);
                lu.set(u[1], repl);
                switch (m(lu)) {
                    case "R_ST":
                        lu.set(0, "R_STX");
                        break;
                    case "R_GTOX": { // R_GTOX n %xJ %y -> R_XMOV %xJ %y
                        List<String> mv = new ArrayList<>(Arrays.asList("R_XMOV", lu.get(2), lu.get(3)));
                        t.set(u[0], mv);
                        break;
                    }
                    case "R_FBIN": case "R_FCMP": case "R_FBINX": case "R_FARG": case "R_RETF":
                        break;
                    default:
                        break;
                }
            }
            t.set(c.def, newDef);
            changed = true;
        }
        if (!changed) {
            out.addAll(fn);
            return;
        }
        for (int i = 0; i < n; i++) {
            List<String> l = t.get(i);
            List<BytecodeToken> orig = fn.get(i);
            if (l.size() == 3 && l.get(0).equals("R_XMOV") && l.get(1).equals(l.get(2))) {
                continue; // an R_XTOG whose uses now read the variable directly
            }
            if (sameText(l, orig)) {
                out.add(orig);
                continue;
            }
            BytecodeToken ref = orig.isEmpty() ? null : orig.get(0);
            List<BytecodeToken> r = new ArrayList<>(l.size());
            for (String s : l) {
                r.add(new BytecodeToken(s, ref == null ? null : ref.file, ref == null ? 0 : ref.line, BytecodeToken.Kind.CODE));
            }
            out.add(r);
        }
    }

    private static boolean sameText(List<String> a, List<BytecodeToken> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i).text)) {
                return false;
            }
        }
        return true;
    }

    /** does this line write float variable register xj? */
    private static boolean writesXmm(List<String> l, String xj) {
        switch (m(l)) {
            case "R_FBINX": return l.size() > 3 && l.get(3).equals(xj);
            case "R_GTOX": case "R_LDX": case "R_POPX": return l.size() > 2 && l.get(2).equals(xj);
            case "R_XMOV": return l.size() > 1 && l.get(1).equals(xj);
            case "R_GETRETF": return l.size() > 2 && l.get(2).equals(xj);
            default: return false;
        }
    }

    /** the float argument register index a line loads, or -1 */
    private static int fargIndex(List<String> l) {
        String mm = m(l);
        try {
            if (mm.equals("R_FARG") && l.size() > 1) {
                return Integer.parseInt(l.get(1));
            }
            if (mm.equals("POP") && l.size() > 1 && l.get(1).startsWith("FARG")) {
                return Integer.parseInt(l.get(1).substring(4));
            }
        } catch (NumberFormatException e) {
            return 99;
        }
        return -1;
    }
}

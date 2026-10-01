package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Unsigned division and modulo by a constant power of two become a shift and a mask (always on; a pure code-quality
 * rewrite, it cannot change a result).
 *
 *   PUSH 8 K ; DIV_INT 8   ->   PUSH 8 n ; SHR 8        (K = 2^n, n >= 1)
 *   PUSH 8 K ; MOD_INT 8   ->   PUSH 8 K-1 ; BITS_AND 8  (K = 2^n, 2 <= K <= 2^31)
 *
 * Only the 8-byte operator on a literal divisor sitting directly before it, and only the unsigned mnemonics (the signed
 * SDIV_INT / SMOD_INT round toward zero and are left alone). A divisor of 1 and any other value are untouched, and
 * division by zero never reaches here (the language rejects a literal zero divisor).
 *
 * BITS_AND is a new stack-form mnemonic (pop two, and, push); the register-form pass turns both results into
 * R_BIN SHR / R_BIN BAND with an immediate.
 */
public class StrengthReductionPass implements OptimizationPass {

    @Override
    public String name() {
        return "strength-reduction";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            List<BytecodeToken> l = lines.get(i);
            if (i + 1 < lines.size() && isConstPush(l)) {
                List<BytecodeToken> next = lines.get(i + 1);
                if (next.size() == 2 && next.get(1).text.equals("8")) {
                    String m = next.get(0).text;
                    long k = Long.parseLong(l.get(2).text);
                    if (m.equals("DIV_INT") && k >= 2 && (k & (k - 1)) == 0) {
                        out.add(push(l, Long.numberOfTrailingZeros(k)));
                        out.add(mnem(next, "SHR"));
                        i++;
                        changed = true;
                        continue;
                    }
                    if (m.equals("MOD_INT") && k >= 2 && k <= (1L << 31) && (k & (k - 1)) == 0) {
                        out.add(push(l, k - 1));
                        out.add(mnem(next, "BITS_AND"));
                        i++;
                        changed = true;
                        continue;
                    }
                }
            }
            out.add(l);
        }
        return new PassResult(out, changed);
    }

    /** PUSH 8 <non-negative decimal literal> */
    private static boolean isConstPush(List<BytecodeToken> l) {
        if (l.size() != 3 || !l.get(0).text.equals("PUSH") || !l.get(1).text.equals("8")) {
            return false;
        }
        String v = l.get(2).text;
        if (v.isEmpty() || v.length() > 18) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) < '0' || v.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static List<BytecodeToken> push(List<BytecodeToken> old, long v) {
        List<BytecodeToken> n = new ArrayList<>(old);
        BytecodeToken t = old.get(2);
        n.set(2, new BytecodeToken(Long.toString(v), t.file, t.line, t.kind));
        return n;
    }

    private static List<BytecodeToken> mnem(List<BytecodeToken> old, String m) {
        List<BytecodeToken> n = new ArrayList<>(old);
        BytecodeToken t = old.get(0);
        n.set(0, new BytecodeToken(m, t.file, t.line, t.kind));
        return n;
    }
}

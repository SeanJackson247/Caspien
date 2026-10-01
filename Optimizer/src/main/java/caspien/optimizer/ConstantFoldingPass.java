package caspien.optimizer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Constant folding: collapses an operator whose operands are literals written in the bytecode itself into one
 * pushed literal ("PUSH 12 / PUSH 6 / ADD" becomes "PUSH 18"). Nested cases fold in one run ("PUSH 10 / PUSH 3 / SUB /
 * PUSH 2 / MUL" becomes "PUSH 14") because the result is re-examined as soon as the next operator is reached.
 *
 * What it does NOT do (other passes' jobs, kept apart on purpose): it never looks at what a variable holds
 * (no constant propagation), never removes a variable, and never looks across a label or any other line. A fold
 * happens only when the operand lines sit directly in front of the operator line.
 *
 * Off unless "constant-folding: on" is set in compiler.config (see FoldConfig).
 *
 * Folded operators (types are read from the operator line; the result is pushed with the operator's own result type):
 *   integers   ADD SUB MUL DIV MOD on u64/s64 only (a narrower stack word keeps its high bits until stored,
 *              so a folded narrow value could differ from what the backend computes); the bitwise group BITS_AND BITS_OR
 *              BITS_XOR BITS_NOT SHL SHR (SHR on a signed type is the arithmetic shift) at EVERY width u8..s64, because
 *              their runtime result is fully defined by the low `width` bits of the operands (see foldBits): a shift count
 *              is read as an unsigned number of the operand's own width and a count >= the width gives 0 (SHL, SHR) or the
 *              sign fill (SAR) -- the rule the backend implements at run time too; compares LT LT_EQ GT GT_EQ EQ NEQ on
 *              every integer width, signed or unsigned as the type says; TRUNC to an unsigned (or non-negative) narrow
 *              type; SEXT/ZEXT to a 64-bit type. Arithmetic wraps to 64 bits exactly as the hardware does.
 *   bool       AND OR NOT
 *   f32/f64    ADD SUB MUL DIV, the six compares, NEG. A result that is NaN, infinite or negative zero is NOT folded (the
 *              bytecode has no literal for it); the operator is left for the runtime.
 * Left alone: division or modulo by zero (the language already rejects a literal zero divisor), signed MIN / -1, integer NEG (the emitter already folds a negated literal), anything with a non-literal operand.
 */
public class ConstantFoldingPass implements OptimizationPass {

    private final boolean enabled;

    public ConstantFoldingPass() {
        this(false);
    }

    public ConstantFoldingPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "constant-folding";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        boolean changed = false;
        for (List<BytecodeToken> line : lines) {
            List<BytecodeToken> folded = tryFold(out, line);
            if (folded != null) {
                changed = true; // tryFold already removed the operand lines from `out`
                out.add(folded);
            } else {
                out.add(line);
            }
        }
        return changed ? new PassResult(out, true) : new PassResult(lines, false);
    }

    // ---- literal and type helpers ---------------------------------------------------------------------------------

    /** "indeterminate_u64" / "mut_u64" / "imut_f32" -> "u64"; anything else (pointers, structs, ...) -> null. */
    static String base(String type) {
        String b;
        if (type.startsWith("indeterminate_")) b = type.substring(14);
        else if (type.startsWith("imut_")) b = type.substring(5);
        else if (type.startsWith("mut_")) b = type.substring(4);
        else return null;
        switch (b) {
            case "u8": case "u16": case "u32": case "u64":
            case "s8": case "s16": case "s32": case "s64":
            case "f32": case "f64": case "bool":
                return b;
            default:
                return null;
        }
    }

    static boolean isInt(String b) {
        return b != null && (b.charAt(0) == 'u' || b.charAt(0) == 's');
    }

    private static boolean isFloat(String b) {
        return b != null && b.charAt(0) == 'f';
    }

    private static boolean signed(String b) {
        return b.charAt(0) == 's';
    }

    static int width(String b) {
        return Integer.parseInt(b.substring(1));
    }

    private static BigInteger wrap(BigInteger v, String b) {
        int w = width(b);
        BigInteger mod = BigInteger.ONE.shiftLeft(w);
        BigInteger r = v.mod(mod);
        if (signed(b) && r.testBit(w - 1)) {
            r = r.subtract(mod);
        }
        return r;
    }

    private static boolean fits(BigInteger v, String b) {
        int w = width(b);
        if (signed(b)) {
            return v.compareTo(BigInteger.ONE.shiftLeft(w - 1).negate()) >= 0 && v.compareTo(BigInteger.ONE.shiftLeft(w - 1)) < 0;
        }
        return v.signum() >= 0 && v.compareTo(BigInteger.ONE.shiftLeft(w)) < 0;
    }

    private static boolean isIntText(String s) {
        int i = s.startsWith("-") ? 1 : 0;
        if (i >= s.length()) return false;
        for (; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    /** "1.5", "-0.25": digits, one point, digits (no exponent -- anything fancier is left alone). */
    private static boolean isFloatText(String s) {
        int i = s.startsWith("-") ? 1 : 0;
        int dot = s.indexOf('.');
        if (dot <= i || dot == s.length() - 1) return false;
        for (int k = i; k < s.length(); k++) {
            if (k != dot && (s.charAt(k) < '0' || s.charAt(k) > '9')) return false;
        }
        return true;
    }

    /** A literal PUSH: "PUSH <literal> <type>" whose literal really is one of the type's kind; else null. */
    static Lit lit(List<BytecodeToken> line) {
        if (line.size() != 3 || line.get(0).kind != BytecodeToken.Kind.CODE || !line.get(0).text.equals("PUSH")) return null;
        String text = line.get(1).text, type = line.get(2).text, b = base(type);
        if (b == null) return null;
        Lit l = new Lit();
        l.type = type;
        l.base = b;
        if (isInt(b)) {
            if (!isIntText(text)) return null;
            l.i = new BigInteger(text);
            if (!fits(l.i, b)) return null;
        } else if (isFloat(b)) {
            if (!isFloatText(text)) return null;
            if (b.equals("f32")) {
                l.d = Float.parseFloat(text);
                if (Float.isInfinite((float) l.d)) return null;
            } else {
                l.d = Double.parseDouble(text);
                if (Double.isInfinite(l.d)) return null;
            }
        } else { // bool
            if (text.equals("true")) l.b = true;
            else if (text.equals("false")) l.b = false;
            else return null;
        }
        return l;
    }

    static final class Lit {
        String type, base;
        BigInteger i;
        double d;      // an f32 is held exactly as a double
        boolean b;
    }

    private static String floatText(double v, boolean single) {
        String s = single ? Float.toString((float) v) : Double.toString(v);
        String plain = new BigDecimal(s).toPlainString();
        return plain.indexOf('.') < 0 ? plain + ".0" : plain;
    }

    private static boolean badFloatResult(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) || (v == 0.0 && 1.0 / v < 0);
    }

    private static List<BytecodeToken> push(BytecodeToken at, String value, String type) {
        List<BytecodeToken> l = new ArrayList<>(3);
        l.add(new BytecodeToken("PUSH", at.file, at.line, BytecodeToken.Kind.CODE));
        l.add(new BytecodeToken(value, at.file, at.line, BytecodeToken.Kind.CODE));
        l.add(new BytecodeToken(type, at.file, at.line, BytecodeToken.Kind.CODE));
        return l;
    }

    // ---- the fold ---------------------------------------------------------------------------------------------------

    /** If `line` is an operator whose operand PUSH lines are the last ones in `out`, removes them and returns the result PUSH. */
    private static List<BytecodeToken> tryFold(List<List<BytecodeToken>> out, List<BytecodeToken> line) {
        if (line.isEmpty() || line.get(0).kind != BytecodeToken.Kind.CODE) return null;
        for (BytecodeToken t : line) {
            if (t.kind != BytecodeToken.Kind.CODE) return null;
        }
        String op = line.get(0).text;
        int n = out.size();
        switch (op) {
            case "NOT": case "NEG": case "TRUNC": case "SEXT": case "ZEXT": case "BITS_NOT": {
                if (line.size() != 3 || n < 1) return null;
                Lit a = lit(out.get(n - 1));
                if (a == null) return null;
                List<BytecodeToken> r = unary(op, a, line);
                if (r != null) out.remove(n - 1);
                return r;
            }
            case "ADD": case "SUB": case "MUL": case "DIV": case "MOD": case "SHL": case "SHR": case "BITS_OR": case "BITS_AND": case "BITS_XOR":
            case "LT": case "LT_EQ": case "GT": case "GT_EQ": case "EQ": case "NEQ": case "AND": case "OR": {
                if (line.size() != 4 || n < 2) return null;
                Lit a = lit(out.get(n - 2)), b = lit(out.get(n - 1));
                if (a == null || b == null) return null;
                List<BytecodeToken> r = binary(op, a, b, line);
                if (r != null) {
                    out.remove(n - 1);
                    out.remove(n - 2);
                }
                return r;
            }
            default:
                return null;
        }
    }

    private static List<BytecodeToken> unary(String op, Lit a, List<BytecodeToken> line) {
        BytecodeToken at = line.get(0);
        String t1 = line.get(1).text, t2 = line.get(2).text;
        switch (op) {
            case "NOT": {
                if (!"bool".equals(a.base) || !"bool".equals(base(t1)) || !"bool".equals(base(t2))) return null;
                return push(at, a.b ? "false" : "true", t2);
            }
            case "BITS_NOT": {
                String b = base(t1);
                if (!isInt(b) || !b.equals(a.base) || !b.equals(base(t2))) return null;
                BigInteger mask = BigInteger.ONE.shiftLeft(width(b)).subtract(BigInteger.ONE);
                return push(at, wrap(a.i.and(mask).xor(mask), b).toString(), t2);
            }
            case "NEG": {
                // floats only: an integer negation of a literal is already folded by the emitter
                if (!isFloat(a.base) || !a.base.equals(base(t1)) || !a.base.equals(base(t2))) return null;
                double r = -a.d;
                if (badFloatResult(r)) return null;
                return push(at, floatText(r, a.base.equals("f32")), t2);
            }
            case "TRUNC": {
                String src = base(t1), dst = base(t2);
                if (!isInt(src) || !isInt(dst) || !src.equals(a.base) || width(dst) >= width(src)) return null;
                BigInteger r = wrap(a.i, dst);
                if (signed(dst) && r.signum() < 0) return null; // a negative narrow literal push is not relied on
                return push(at, r.toString(), t2);
            }
            case "SEXT": case "ZEXT": {
                String src = base(t1), dst = base(t2);
                if (!isInt(src) || !isInt(dst) || !src.equals(a.base) || width(dst) != 64 || width(src) >= 64) return null;
                if (op.equals("SEXT") != signed(src) || signed(src) != signed(dst)) return null;
                return push(at, a.i.toString(), t2);
            }
            default:
                return null;
        }
    }

    /**
     * The bitwise group on two in-range literals of integer base type `b` (u8..s64). Every operand is first reduced to its
     * unsigned width-bit pattern (a negative signed literal becomes 2^w + v), the operation runs on those patterns, and the
     * result is converted back to the type's own value range -- exactly what the stack and register forms compute on the
     * low `w` bits. Shifts: the count is the unsigned pattern of the right operand; SHL/SHR by a count >= w give 0, SHR on a
     * signed type (the arithmetic shift) gives the sign fill (0 or -1); smaller counts shift normally (SHL discards the bits
     * pushed past the width).
     */
    static BigInteger foldBits(String op, String b, BigInteger x, BigInteger y) {
        int w = width(b);
        BigInteger mask = BigInteger.ONE.shiftLeft(w).subtract(BigInteger.ONE);
        BigInteger ux = x.and(mask), uy = y.and(mask);
        BigInteger r;
        switch (op) {
            case "BITS_AND": r = ux.and(uy); break;
            case "BITS_OR": r = ux.or(uy); break;
            case "BITS_XOR": r = ux.xor(uy); break;
            case "SHL":
                r = uy.compareTo(BigInteger.valueOf(w)) >= 0 ? BigInteger.ZERO : ux.shiftLeft(uy.intValue()).and(mask);
                break;
            default: { // SHR
                boolean wide = uy.compareTo(BigInteger.valueOf(w)) >= 0;
                if (signed(b)) {
                    r = wide ? (x.signum() < 0 ? BigInteger.ONE.negate() : BigInteger.ZERO) : x.shiftRight(uy.intValue());
                } else {
                    r = wide ? BigInteger.ZERO : ux.shiftRight(uy.intValue());
                }
                break;
            }
        }
        return wrap(r, b);
    }

    private static List<BytecodeToken> binary(String op, Lit a, Lit b, List<BytecodeToken> line) {
        BytecodeToken at = line.get(0);
        String lt = line.get(1).text, rt = line.get(2).text, res = line.get(3).text;
        String lb = base(lt), rb = base(rt), resB = base(res);
        if (lb == null || resB == null || !lb.equals(rb) || !lb.equals(a.base) || !lb.equals(b.base)) return null;

        boolean compare = op.equals("LT") || op.equals("LT_EQ") || op.equals("GT") || op.equals("GT_EQ")
                || op.equals("EQ") || op.equals("NEQ");
        if (op.equals("AND") || op.equals("OR")) {
            if (!lb.equals("bool") || !resB.equals("bool")) return null;
            return push(at, (op.equals("AND") ? a.b && b.b : a.b || b.b) ? "true" : "false", res);
        }
        if (compare) {
            if (!resB.equals("bool") || lb.equals("bool")) return null;
            int c;
            if (isInt(lb)) {
                c = a.i.compareTo(b.i);
            } else {
                c = a.d < b.d ? -1 : a.d > b.d ? 1 : 0; // literals are never NaN
            }
            boolean v;
            switch (op) {
                case "LT": v = c < 0; break;
                case "LT_EQ": v = c <= 0; break;
                case "GT": v = c > 0; break;
                case "GT_EQ": v = c >= 0; break;
                case "EQ": v = c == 0; break;
                default: v = c != 0; break;
            }
            return push(at, v ? "true" : "false", res);
        }
        if (!lb.equals(resB)) return null;
        if (isFloat(lb)) {
            boolean single = lb.equals("f32");
            double r;
            switch (op) {
                case "ADD": r = single ? (double) ((float) a.d + (float) b.d) : a.d + b.d; break;
                case "SUB": r = single ? (double) ((float) a.d - (float) b.d) : a.d - b.d; break;
                case "MUL": r = single ? (double) ((float) a.d * (float) b.d) : a.d * b.d; break;
                case "DIV": r = single ? (double) ((float) a.d / (float) b.d) : a.d / b.d; break;
                default: return null;
            }
            if (badFloatResult(r)) return null;
            return push(at, floatText(r, single), res);
        }
        if (isInt(lb) && (op.equals("BITS_AND") || op.equals("BITS_OR") || op.equals("BITS_XOR") || op.equals("SHL") || op.equals("SHR"))) {
            return push(at, foldBits(op, lb, a.i, b.i).toString(), res);
        }
        if (!isInt(lb) || width(lb) != 64) return null;
        BigInteger x = a.i, y = b.i, r;
        switch (op) {
            case "ADD": r = x.add(y); break;
            case "SUB": r = x.subtract(y); break;
            case "MUL": r = x.multiply(y); break;
            case "DIV": case "MOD": {
                if (y.signum() == 0) return null;
                if (signed(lb) && y.equals(BigInteger.ONE.negate()) && x.equals(BigInteger.ONE.shiftLeft(63).negate())) return null;
                r = op.equals("DIV") ? x.divide(y) : x.remainder(y); // truncating, like idiv/div
                break;
            }
            default: return null;
        }
        return push(at, wrap(r, lb).toString(), res);
    }
}

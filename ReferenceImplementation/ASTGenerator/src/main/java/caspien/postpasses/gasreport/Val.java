package caspien.postpasses.gasreport;

import static caspien.postpasses.gasreport.GasReportCore.*;

import java.math.BigInteger;

/**
 * A path value: `n` = net effect of a path (gas, bytes, a count; for LIVE the net change of live heap bytes), `p` = the peak of the running
 * total along the path (never below 0, the empty prefix). Plain metrics only ever read `n` (their p equals n). `neg` = "no such path".
 */
public final class Val {
    public final BigInteger n, p;
    public final boolean neg;
    public Val(BigInteger n, BigInteger p, boolean neg) {
        this.n = n;
        this.p = p;
        this.neg = neg;
    }
    public static final Val NEG = new Val(BigInteger.ZERO, BigInteger.ZERO, true);
    public static final Val ZERO = new Val(BigInteger.ZERO, BigInteger.ZERO, false);
    /** A path that ends in a throw or a process exit: its peak counts, it leaves nothing for the caller (its net is -infinity, so any returning path wins the net). */
    public static final Val NONET = new Val(BigInteger.TEN.pow(30).negate(), BigInteger.ZERO, false);
    public static Val of(BigInteger c) {
        return new Val(c, c.max(BigInteger.ZERO), false);
    }
    public static Val of(long c) {
        return of(BigInteger.valueOf(c));
    }
    /** a, then b. */
    public static Val seq(Val a, Val b) {
        if (a.neg || b.neg) {
            return NEG;
        }
        return new Val(a.n.add(b.n), a.p.max(a.n.add(b.p)), false);
    }
    /** the dearer of two alternative paths (componentwise: an upper bound for both the net and the peak). */
    public static Val alt(Val a, Val b) {
        if (a.neg) {
            return b;
        }
        if (b.neg) {
            return a;
        }
        return new Val(a.n.max(b.n), a.p.max(b.p), false);
    }
    /** c repeated k times, back to back. */
    public static Val rep(BigInteger k, Val c) {
        if (c.neg || k.signum() == 0) {
            return ZERO;
        }
        BigInteger peak = c.n.signum() > 0 ? k.subtract(BigInteger.ONE).multiply(c.n).add(c.p) : c.p;
        return new Val(k.multiply(c.n), peak, false);
    }
}

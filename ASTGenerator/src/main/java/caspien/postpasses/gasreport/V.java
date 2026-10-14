package caspien.postpasses.gasreport;

import static caspien.postpasses.gasreport.GasReportCore.*;

import java.math.BigInteger;

/** A value known at analysis time: a scalar, or the range a..b. */
public final class V {
    public final BigInteger a, b;
    public final boolean range;
    public V(BigInteger a, BigInteger b, boolean range) {
        this.a = a;
        this.b = b;
        this.range = range;
    }
    public static V sc(BigInteger x) {
        return new V(x, null, false);
    }
    @Override
    public String toString() {
        return range ? a + ".." + b : a.toString();
    }
}

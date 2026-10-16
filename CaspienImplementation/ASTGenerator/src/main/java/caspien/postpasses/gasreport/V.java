package caspien.postpasses.gasreport;

import static caspien.postpasses.gasreport.GasReportCore.*;

import java.math.BigInteger;

/** A value known at analysis time: a scalar, or the range a..b. */
public final class V {
    public final BigInteger a, b;
    public final boolean range;
    public V(BigInteger a, BigInteger b, boolean range) { }
    public static V sc(BigInteger x) { return null; }
    @Override
    public String toString() { return null; }
}

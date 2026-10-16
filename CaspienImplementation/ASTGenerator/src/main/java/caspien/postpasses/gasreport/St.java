package caspien.postpasses.gasreport;

import static caspien.postpasses.gasreport.GasReportCore.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Forward state of the credit analysis: per owned slot the bytes of the block it certainly holds (absent = unknown / null / moved), and the owned parameters certainly destructed. */
public final class St {
    public final Map<String, Long> lo = new HashMap<>();
    public final Map<String, Long> deep = new HashMap<>();      // per slot: bytes of the blocks moved into the members of what it holds (certain; counted only when the function is deepOk)
    public final Set<String> dead = new HashSet<>();
    public St copy() { return null; }
    public boolean same(St o) { return false; }
    /** Must-join: a slot keeps its bytes only when every path agrees on at least them (the minimum). */
    public static St join(St a, St b) { return null; }
}

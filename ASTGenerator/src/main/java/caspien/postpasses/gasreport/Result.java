package caspien.postpasses.gasreport;

import static caspien.postpasses.gasreport.GasReportCore.*;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Result {
    public String text;
    public final Map<String, String> gasByFunction = new LinkedHashMap<>();   // for tests: name -> "123" or ">=123"
    public final Map<String, String> stackByFunction = new LinkedHashMap<>();
    public final Map<String, String> heapByFunction = new LinkedHashMap<>();
    public final Map<String, String> countByFunction = new LinkedHashMap<>();
    public final Map<String, String> liveByFunction = new LinkedHashMap<>();    // name -> "peak/leaves" (peak prefixed ">=" when unbounded)
    public Result(String text) {
        this.text = text;
    }
}

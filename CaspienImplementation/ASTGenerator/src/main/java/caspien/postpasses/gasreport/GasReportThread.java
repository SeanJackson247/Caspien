package caspien.postpasses.gasreport;

/** Runs the report analysis on a big-stack thread; the caller reads `result` after join(). */
final class GasReportThread implements Runnable {
    private final GasReport g;
    Result result;

    GasReportThread(GasReport g) { }

    @Override
    public void run() { }
}

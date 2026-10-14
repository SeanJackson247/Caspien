package caspien.postpasses.gasreport;

/** Builds the --viz page on a big-stack thread; the caller reads `page` after join(). */
final class GasVizThread implements Runnable {
    private final GasReport g;
    private final String title;
    String page;

    GasVizThread(GasReport g, String title) {
        this.g = g;
        this.title = title;
    }

    @Override
    public void run() {
        page = g.vizPage(title);
    }
}

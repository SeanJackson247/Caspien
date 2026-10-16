final class CompilerDiagnostics {
    private final boolean suppressWarnings;
    private int warningCount = 0;
    private int failCode = 0;

    CompilerDiagnostics(boolean suppressWarnings) { }

    boolean suppressWarnings() { return false; }

    void addWarnings(int n) { }

    int warningCount() { return 0; }

    void fail(int code) { }

    boolean hasFatalError() { return false; }

    int exitCode() { return 0; }
}

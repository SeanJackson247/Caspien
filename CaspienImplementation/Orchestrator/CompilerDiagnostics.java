final class CompilerDiagnostics {
    private final boolean suppressWarnings;
    private int warningCount = 0;
    private int failCode = 0;

    CompilerDiagnostics(boolean suppressWarnings) {
        this.suppressWarnings = suppressWarnings;
    }

    boolean suppressWarnings() {
        return suppressWarnings;
    }

    void addWarnings(int n) {
        warningCount += n;
    }

    int warningCount() {
        return warningCount;
    }

    void fail(int code) {
        if (failCode == 0) {
            failCode = code;
        }
    }

    boolean hasFatalError() {
        return failCode != 0;
    }

    int exitCode() {
        return failCode;
    }
}

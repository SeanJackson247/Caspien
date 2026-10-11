// spectral-norm, single threaded. Same algorithm as spectralnorm-gcc-1 of the Computer Language Benchmarks Game.
public final class SpectralNorm {
    static double evalA(int i, int j) { return 1.0 / ((i + j) * (i + j + 1) / 2 + i + 1); }

    static void aTimesU(int n, double[] u, double[] au) {
        for (int i = 0; i < n; i++) { double s = 0; for (int j = 0; j < n; j++) s += evalA(i, j) * u[j]; au[i] = s; }
    }
    static void atTimesU(int n, double[] u, double[] au) {
        for (int i = 0; i < n; i++) { double s = 0; for (int j = 0; j < n; j++) s += evalA(j, i) * u[j]; au[i] = s; }
    }
    static void ataTimesU(int n, double[] u, double[] atau) {
        double[] v = new double[n];
        aTimesU(n, u, v);
        atTimesU(n, v, atau);
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        double[] u = new double[n], v = new double[n];
        java.util.Arrays.fill(u, 1.0);
        for (int i = 0; i < 10; i++) { ataTimesU(n, u, v); ataTimesU(n, v, u); }
        double vBv = 0, vv = 0;
        for (int i = 0; i < n; i++) { vBv += u[i] * v[i]; vv += v[i] * v[i]; }
        System.out.println(String.format("%.9f", Math.sqrt(vBv / vv)));
    }
}

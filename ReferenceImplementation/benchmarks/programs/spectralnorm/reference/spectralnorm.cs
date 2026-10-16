using System;
using System.Globalization;

// spectral-norm, single threaded. Same algorithm as spectralnorm-gcc-1 of the Computer Language Benchmarks Game.
public static class SpectralNorm
{
    static double EvalA(int i, int j) { return 1.0 / ((i + j) * (i + j + 1) / 2 + i + 1); }

    static void ATimesU(int n, double[] u, double[] au)
    {
        for (int i = 0; i < n; i++) { double s = 0; for (int j = 0; j < n; j++) s += EvalA(i, j) * u[j]; au[i] = s; }
    }
    static void AtTimesU(int n, double[] u, double[] au)
    {
        for (int i = 0; i < n; i++) { double s = 0; for (int j = 0; j < n; j++) s += EvalA(j, i) * u[j]; au[i] = s; }
    }
    static void AtaTimesU(int n, double[] u, double[] atau)
    {
        double[] v = new double[n];
        ATimesU(n, u, v);
        AtTimesU(n, v, atau);
    }

    public static void Main(string[] args)
    {
        int n = args.Length > 0 ? int.Parse(args[0]) : 100;
        double[] u = new double[n], v = new double[n];
        Array.Fill(u, 1.0);
        for (int i = 0; i < 10; i++) { AtaTimesU(n, u, v); AtaTimesU(n, v, u); }
        double vBv = 0, vv = 0;
        for (int i = 0; i < n; i++) { vBv += u[i] * v[i]; vv += v[i] * v[i]; }
        Console.Out.Write(Math.Sqrt(vBv / vv).ToString("F9", CultureInfo.InvariantCulture) + "\n");
    }
}

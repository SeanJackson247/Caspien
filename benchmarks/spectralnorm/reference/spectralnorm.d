// spectral-norm (port of spectralnorm.c), single threaded.
import core.stdc.stdio;
import core.stdc.math : sqrt;
import std.conv : to;

double evalA(int i, int j) { return 1.0 / ((i + j) * (i + j + 1) / 2 + i + 1); }

void evalAtimesU(int N, const(double)[] u, double[] Au) {
    for (int i = 0; i < N; i++) { Au[i] = 0; for (int j = 0; j < N; j++) Au[i] += evalA(i, j) * u[j]; }
}
void evalAtTimesU(int N, const(double)[] u, double[] Au) {
    for (int i = 0; i < N; i++) { Au[i] = 0; for (int j = 0; j < N; j++) Au[i] += evalA(j, i) * u[j]; }
}
void evalAtAtimesU(int N, const(double)[] u, double[] AtAu) {
    auto v = new double[N];
    evalAtimesU(N, u, v);
    evalAtTimesU(N, v, AtAu);
}

int main(string[] args) {
    int N = args.length > 1 ? args[1].to!int : 100;
    auto u = new double[N], v = new double[N];
    u[] = 1;
    for (int i = 0; i < 10; i++) { evalAtAtimesU(N, u, v); evalAtAtimesU(N, v, u); }
    double vBv = 0, vv = 0;
    for (int i = 0; i < N; i++) { vBv += u[i] * v[i]; vv += v[i] * v[i]; }
    printf("%0.9f\n", sqrt(vBv / vv));
    return 0;
}

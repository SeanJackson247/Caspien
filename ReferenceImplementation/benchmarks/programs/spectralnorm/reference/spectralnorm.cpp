/* spectral-norm, single threaded. Sebastien Loisel's spectralnorm-gcc-1 of the Computer Language Benchmarks Game. */
#include <stdio.h>
#include <stdlib.h>
#include <math.h>

static double eval_A(int i, int j) { return 1.0 / ((i + j) * (i + j + 1) / 2 + i + 1); }

static void eval_A_times_u(int N, const double u[], double Au[]) {
    for (int i = 0; i < N; i++) { Au[i] = 0; for (int j = 0; j < N; j++) Au[i] += eval_A(i, j) * u[j]; }
}
static void eval_At_times_u(int N, const double u[], double Au[]) {
    for (int i = 0; i < N; i++) { Au[i] = 0; for (int j = 0; j < N; j++) Au[i] += eval_A(j, i) * u[j]; }
}
static void eval_AtA_times_u(int N, const double u[], double AtAu[]) {
    double *v = (double*)malloc(N * sizeof(double));
    eval_A_times_u(N, u, v);
    eval_At_times_u(N, v, AtAu);
    free(v);
}

int main(int argc, char *argv[]) {
    int N = argc > 1 ? atoi(argv[1]) : 100;
    double *u = (double*)malloc(N * sizeof(double)), *v = (double*)malloc(N * sizeof(double));
    for (int i = 0; i < N; i++) u[i] = 1;
    for (int i = 0; i < 10; i++) { eval_AtA_times_u(N, u, v); eval_AtA_times_u(N, v, u); }
    double vBv = 0, vv = 0;
    for (int i = 0; i < N; i++) { vBv += u[i] * v[i]; vv += v[i] * v[i]; }
    printf("%0.9f\n", sqrt(vBv / vv));
    free(u); free(v);
    return 0;
}

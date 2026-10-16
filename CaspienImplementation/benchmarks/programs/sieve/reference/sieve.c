// Sieve of Eratosthenes: count the primes <= N (byte per number). Build with -DLEAK to skip the free().
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 100000000L;
    unsigned char *f = malloc(n + 1);
    memset(f, 1, n + 1);
    for (long i = 2; i * i <= n; i++)
        if (f[i])
            for (long j = i * i; j <= n; j += i) f[j] = 0;
    long count = 0;
    for (long i = 2; i <= n; i++) count += f[i];
    printf("%ld\n", count);
#ifndef LEAK
    free(f);
#endif
    return 0;
}

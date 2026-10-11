// Sieve of Eratosthenes (C++). -DLEAK: raw new[] never deleted; default: std::vector (freed automatically).
#include <cstdio>
#include <cstdlib>
#include <vector>
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 100000000L;
#ifdef LEAK
    unsigned char *f = new unsigned char[n + 1];
    for (long i = 0; i <= n; i++) f[i] = 1;
#else
    std::vector<unsigned char> v(n + 1, 1);
    unsigned char *f = v.data();
#endif
    for (long i = 2; i * i <= n; i++)
        if (f[i])
            for (long j = i * i; j <= n; j += i) f[j] = 0;
    long count = 0;
    for (long i = 2; i <= n; i++) count += f[i];
    printf("%ld\n", count);
    return 0;
}

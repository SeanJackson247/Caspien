// Sieve of Eratosthenes: count the primes <= N (byte per number).
import core.stdc.stdio;
import std.conv : to;

int main(string[] args) {
    long n = args.length > 1 ? args[1].to!long : 100_000_000L;
    auto f = new ubyte[n + 1];
    f[] = 1;
    for (long i = 2; i * i <= n; i++)
        if (f[i])
            for (long j = i * i; j <= n; j += i) f[j] = 0;
    long count = 0;
    for (long i = 2; i <= n; i++) count += f[i];
    printf("%ld\n", count);
    return 0;
}

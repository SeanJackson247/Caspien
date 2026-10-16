// Sieve of Eratosthenes: count the primes <= N (byte per number).
#include <objc/objc.h>
#include <objc/runtime.h>
#pragma GCC diagnostic ignored "-Wobjc-root-class"
// The benchmark body lives in a class method of a small root class (GNU libobjc ships no Foundation / NSObject here).
@interface Runner { Class isa; }
+ (int)runWithArgc:(int)argc argv:(char **)argv;
@end
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
@implementation Runner
+ (int)runWithArgc:(int)argc argv:(char **)argv {
    long i;
    long j;
    long n = argc > 1 ? atol(argv[1]) : 100000000L;
    unsigned char *f = malloc(n + 1);
    memset(f, 1, n + 1);
    for (i = 2; i * i <= n; i++)
        if (f[i])
            for (j = i * i; j <= n; j += i) f[j] = 0;
    long count = 0;
    for (i = 2; i <= n; i++) count += f[i];
    printf("%ld\n", count);
    free(f);
    return 0;
}
@end
int main(int argc, char **argv) { return [Runner runWithArgc:argc argv:argv]; }

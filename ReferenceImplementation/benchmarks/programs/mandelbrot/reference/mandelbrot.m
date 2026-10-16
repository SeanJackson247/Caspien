// Mandelbrot escape-time benchmark: an N x N grid over [-1.5,0.5] x [-1,1] (c = (2x/N - 1.5, 2y/N - 1)), at most 100 iterations of
// z = z^2 + c per point, bailing out when |z|^2 > 4. No allocation. Prints: points-that-never-escaped total-iterations.
#include <objc/objc.h>
#include <objc/runtime.h>
#pragma GCC diagnostic ignored "-Wobjc-root-class"
// The benchmark body lives in a class method of a small root class (GNU libobjc ships no Foundation / NSObject here).
@interface Runner { Class isa; }
+ (int)runWithArgc:(int)argc argv:(char **)argv;
@end
#include <stdio.h>
#include <stdlib.h>
@implementation Runner
+ (int)runWithArgc:(int)argc argv:(char **)argv {
    long y;
    long x;
    long n = argc > 1 ? atol(argv[1]) : 3000L;
    double dn = (double)n;
    long inside = 0, total = 0;
    for (y = 0; y < n; y++) {
        double ci = 2.0 * (double)y / dn - 1.0;
        for (x = 0; x < n; x++) {
            double cr = 2.0 * (double)x / dn - 1.5;
            double zr = 0.0, zi = 0.0, tr = 0.0, ti = 0.0;
            int i = 0;
            while (i < 100 && tr + ti <= 4.0) {
                zi = 2.0 * zr * zi + ci;
                zr = tr - ti + cr;
                tr = zr * zr;
                ti = zi * zi;
                i++;
            }
            total += i;
            if (i == 100) inside++;
        }
    }
    printf("%ld %ld\n", inside, total);
    return 0;
}
@end
int main(int argc, char **argv) { return [Runner runWithArgc:argc argv:argv]; }

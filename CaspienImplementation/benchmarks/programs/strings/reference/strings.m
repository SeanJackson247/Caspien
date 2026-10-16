// String manipulation benchmark. A pseudo-random text of N characters (27 symbols: a-z and space), then: word statistics, count of the
// substring "abc", reverse, replace every 'e' by "33" (the text grows), upper-case. Every result buffer is a fresh heap allocation.
// Prints: words longest abc revhash replacedlen replacedhash uphash.
#include <objc/objc.h>
#include <objc/runtime.h>
#pragma GCC diagnostic ignored "-Wobjc-root-class"
// The benchmark body lives in a class method of a small root class (GNU libobjc ships no Foundation / NSObject here).
@interface Runner { Class isa; }
+ (int)runWithArgc:(int)argc argv:(char **)argv;
@end
#include <stdio.h>
#include <stdlib.h>
static unsigned x = 12345;
static unsigned next(void) { x = x * 1664525u + 1013904223u; return x; }
static unsigned hash(const unsigned char *b, long n) {
    long i;
 unsigned h = 7; for (i = 0; i < n; i++) h = h * 31 + b[i]; return h; }
@implementation Runner
+ (int)runWithArgc:(int)argc argv:(char **)argv {
    long i;
    long n = argc > 1 ? atol(argv[1]) : 4000000L;
    unsigned char *text = malloc(n);
    for (i = 0; i < n; i++) { unsigned r = (next() >> 16) % 27; text[i] = r == 26 ? ' ' : (unsigned char)('a' + r); }
    long words = 0, longest = 0, cur = 0;
    for (i = 0; i < n; i++) {
        if (text[i] == ' ') { if (cur > 0) { words++; if (cur > longest) longest = cur; cur = 0; } } else cur++;
    }
    if (cur > 0) { words++; if (cur > longest) longest = cur; }
    long abc = 0;
    for (i = 0; i + 2 < n; i++) if (text[i] == 'a' && text[i + 1] == 'b' && text[i + 2] == 'c') abc++;
    unsigned char *rev = malloc(n);
    for (i = 0; i < n; i++) rev[i] = text[n - 1 - i];
    unsigned hrev = hash(rev, n);
    long es = 0;
    for (i = 0; i < n; i++) if (text[i] == 'e') es++;
    long m = n + es;
    unsigned char *rep = malloc(m);
    long k = 0;
    for (i = 0; i < n; i++) { if (text[i] == 'e') { rep[k++] = '3'; rep[k++] = '3'; } else rep[k++] = text[i]; }
    unsigned hrep = hash(rep, m);
    unsigned char *up = malloc(n);
    for (i = 0; i < n; i++) up[i] = text[i] == ' ' ? ' ' : text[i] - 32;
    unsigned hup = hash(up, n);
    printf("%ld %ld %ld %u %ld %u %u\n", words, longest, abc, hrev, m, hrep, hup);
    free(text); free(rev); free(rep); free(up);
    return 0;
}
@end
int main(int argc, char **argv) { return [Runner runWithArgc:argc argv:argv]; }

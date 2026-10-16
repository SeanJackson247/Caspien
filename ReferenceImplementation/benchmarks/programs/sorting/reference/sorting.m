// Sorting and searching benchmark: N pseudo-random 31-bit values; iterative quicksort (Hoare partition, middle pivot, explicit stack),
// bottom-up merge sort (temp buffer), heap sort; then N binary searches on the sorted array and 20 linear searches on the unsorted one.
// Prints: hash-of-sorted-array binary-hits linear-hits (and "BAD" if the three sorts disagree).
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
static unsigned x = 12345;
static unsigned next(void) { x = x * 1664525u + 1013904223u; return x; }

static void quicksort(int *a, long n) {
    long stack[128]; int sp = 0;
    stack[sp++] = 0; stack[sp++] = n - 1;
    while (sp > 0) {
        long hi = stack[--sp], lo = stack[--sp];
        while (lo < hi) {
            int pivot = a[(lo + hi) / 2];
            long i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) { int t = a[i]; a[i] = a[j]; a[j] = t; i++; j--; }
            }
            if (j - lo < hi - i) { stack[sp++] = i; stack[sp++] = hi; hi = j; }
            else { stack[sp++] = lo; stack[sp++] = j; lo = i; }
        }
    }
}
static void mergesort(int *a, int *tmp, long n) {
    long w;
    long lo;
    int *src = a, *dst = tmp;
    for (w = 1; w < n; w *= 2) {
        for (lo = 0; lo < n; lo += 2 * w) {
            long mid = lo + w < n ? lo + w : n, hi = lo + 2 * w < n ? lo + 2 * w : n;
            long i = lo, j = mid, k = lo;
            while (i < mid && j < hi) dst[k++] = src[i] <= src[j] ? src[i++] : src[j++];
            while (i < mid) dst[k++] = src[i++];
            while (j < hi) dst[k++] = src[j++];
        }
        int *t = src; src = dst; dst = t;
    }
    if (src != a) memcpy(a, src, n * sizeof(int));
}
static void siftdown(int *a, long root, long n) {
    for (;;) {
        long c = 2 * root + 1;
        if (c >= n) break;
        if (c + 1 < n && a[c + 1] > a[c]) c++;
        if (a[root] >= a[c]) break;
        int t = a[root]; a[root] = a[c]; a[c] = t;
        root = c;
    }
}
static void heapsort(int *a, long n) {
    long i;
    long e;
    for (i = n / 2 - 1; i >= 0; i--) siftdown(a, i, n);
    for (e = n - 1; e > 0; e--) { int t = a[0]; a[0] = a[e]; a[e] = t; siftdown(a, 0, e); }
}
static int bsearch_has(const int *a, long n, int key) {
    long lo = 0, hi = n;
    while (lo < hi) { long m = (lo + hi) / 2; if (a[m] < key) lo = m + 1; else hi = m; }
    return lo < n && a[lo] == key;
}
@implementation Runner
+ (int)runWithArgc:(int)argc argv:(char **)argv {
    long i;
    long k;
    long n = argc > 1 ? atol(argv[1]) : 2000000L;
    int *orig = malloc(n * sizeof(int)), *a = malloc(n * sizeof(int)), *b = malloc(n * sizeof(int)), *d = malloc(n * sizeof(int)), *tmp = malloc(n * sizeof(int));
    for (i = 0; i < n; i++) orig[i] = (int)(next() >> 1);
    memcpy(a, orig, n * sizeof(int)); memcpy(b, orig, n * sizeof(int)); memcpy(d, orig, n * sizeof(int));
    quicksort(a, n);
    mergesort(b, tmp, n);
    heapsort(d, n);
    unsigned h = 0; int ok = 1;
    for (i = 0; i < n; i++) { h = h * 31u + (unsigned)a[i]; if (a[i] != b[i] || a[i] != d[i]) ok = 0; if (i > 0 && a[i - 1] > a[i]) ok = 0; }
    long bs = 0;
    for (k = 0; k < n; k++) {
        int key = (int)(next() >> 1);
        if (k % 2 == 0) key = orig[(unsigned)key % n];
        bs += bsearch_has(a, n, key);
    }
    long ls = 0;
    for (k = 0; k < 20; k++) {
        int key = (int)(next() >> 1);
        if (k % 2 == 0) key = orig[(unsigned)key % n];
        for (i = 0; i < n; i++) if (orig[i] == key) { ls++; break; }
    }
    printf("%u %ld %ld %s\n", h, bs, ls, ok ? "OK" : "BAD");
    free(orig); free(a); free(b); free(d); free(tmp);
    return 0;
}
@end
int main(int argc, char **argv) { return [Runner runWithArgc:argc argv:argv]; }

// Sorting and searching benchmark (C++): same algorithms as sorting.c. Arrays are new[]/delete[]; build with -DLEAK to skip delete[].
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <utility>
static unsigned x = 12345;
static unsigned next() { x = x * 1664525u + 1013904223u; return x; }

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
                if (i <= j) { std::swap(a[i], a[j]); i++; j--; }
            }
            if (j - lo < hi - i) { stack[sp++] = i; stack[sp++] = hi; hi = j; }
            else { stack[sp++] = lo; stack[sp++] = j; lo = i; }
        }
    }
}
static void mergesort(int *a, int *tmp, long n) {
    int *src = a, *dst = tmp;
    for (long w = 1; w < n; w *= 2) {
        for (long lo = 0; lo < n; lo += 2 * w) {
            long mid = lo + w < n ? lo + w : n, hi = lo + 2 * w < n ? lo + 2 * w : n;
            long i = lo, j = mid, k = lo;
            while (i < mid && j < hi) dst[k++] = src[i] <= src[j] ? src[i++] : src[j++];
            while (i < mid) dst[k++] = src[i++];
            while (j < hi) dst[k++] = src[j++];
        }
        std::swap(src, dst);
    }
    if (src != a) std::memcpy(a, src, n * sizeof(int));
}
static void siftdown(int *a, long root, long n) {
    for (;;) {
        long c = 2 * root + 1;
        if (c >= n) break;
        if (c + 1 < n && a[c + 1] > a[c]) c++;
        if (a[root] >= a[c]) break;
        std::swap(a[root], a[c]);
        root = c;
    }
}
static void heapsort(int *a, long n) {
    for (long i = n / 2 - 1; i >= 0; i--) siftdown(a, i, n);
    for (long e = n - 1; e > 0; e--) { std::swap(a[0], a[e]); siftdown(a, 0, e); }
}
static bool bsearch_has(const int *a, long n, int key) {
    long lo = 0, hi = n;
    while (lo < hi) { long m = (lo + hi) / 2; if (a[m] < key) lo = m + 1; else hi = m; }
    return lo < n && a[lo] == key;
}
int main(int argc, char **argv) {
    long n = argc > 1 ? std::atol(argv[1]) : 2000000L;
    int *orig = new int[n], *a = new int[n], *b = new int[n], *d = new int[n], *tmp = new int[n];
    for (long i = 0; i < n; i++) orig[i] = (int)(next() >> 1);
    std::memcpy(a, orig, n * sizeof(int)); std::memcpy(b, orig, n * sizeof(int)); std::memcpy(d, orig, n * sizeof(int));
    quicksort(a, n);
    mergesort(b, tmp, n);
    heapsort(d, n);
    unsigned h = 0; bool ok = true;
    for (long i = 0; i < n; i++) { h = h * 31u + (unsigned)a[i]; if (a[i] != b[i] || a[i] != d[i]) ok = false; if (i > 0 && a[i - 1] > a[i]) ok = false; }
    long bs = 0;
    for (long k = 0; k < n; k++) {
        int key = (int)(next() >> 1);
        if (k % 2 == 0) key = orig[(unsigned)key % n];
        bs += bsearch_has(a, n, key);
    }
    long ls = 0;
    for (int k = 0; k < 20; k++) {
        int key = (int)(next() >> 1);
        if (k % 2 == 0) key = orig[(unsigned)key % n];
        for (long i = 0; i < n; i++) if (orig[i] == key) { ls++; break; }
    }
    std::printf("%u %ld %ld %s\n", h, bs, ls, ok ? "OK" : "BAD");
#ifndef LEAK
    delete[] orig; delete[] a; delete[] b; delete[] d; delete[] tmp;
#endif
    return 0;
}

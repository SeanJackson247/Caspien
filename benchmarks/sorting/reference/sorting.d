// Sorting and searching benchmark (port of sorting.c): hand-written quicksort / merge sort / heap sort on GC int arrays.
import core.stdc.stdio;
import std.conv : to;

__gshared uint x = 12345;
uint next() { x = x * 1664525u + 1013904223u; return x; }

void quicksort(int[] a, long n) {
    long[128] stack;
    int sp = 0;
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
void mergesort(int[] a, int[] tmp, long n) {
    int[] src = a, dst = tmp;
    for (long w = 1; w < n; w *= 2) {
        for (long lo = 0; lo < n; lo += 2 * w) {
            long mid = lo + w < n ? lo + w : n, hi = lo + 2 * w < n ? lo + 2 * w : n;
            long i = lo, j = mid, k = lo;
            while (i < mid && j < hi) { if (src[i] <= src[j]) dst[k++] = src[i++]; else dst[k++] = src[j++]; }
            while (i < mid) dst[k++] = src[i++];
            while (j < hi) dst[k++] = src[j++];
        }
        int[] t = src; src = dst; dst = t;
    }
    if (src.ptr != a.ptr) a[0 .. n] = src[0 .. n];
}
void siftdown(int[] a, long root, long n) {
    for (;;) {
        long c = 2 * root + 1;
        if (c >= n) break;
        if (c + 1 < n && a[c + 1] > a[c]) c++;
        if (a[root] >= a[c]) break;
        int t = a[root]; a[root] = a[c]; a[c] = t;
        root = c;
    }
}
void heapsort(int[] a, long n) {
    for (long i = n / 2 - 1; i >= 0; i--) siftdown(a, i, n);
    for (long e = n - 1; e > 0; e--) { int t = a[0]; a[0] = a[e]; a[e] = t; siftdown(a, 0, e); }
}
bool bsearchHas(const(int)[] a, long n, int key) {
    long lo = 0, hi = n;
    while (lo < hi) { long m = (lo + hi) / 2; if (a[m] < key) lo = m + 1; else hi = m; }
    return lo < n && a[lo] == key;
}

int main(string[] args) {
    long n = args.length > 1 ? args[1].to!long : 2_000_000L;
    auto orig = new int[n];
    auto tmp = new int[n];
    for (long i = 0; i < n; i++) orig[i] = cast(int)(next() >> 1);
    auto a = orig.dup, b = orig.dup, d = orig.dup;
    quicksort(a, n);
    mergesort(b, tmp, n);
    heapsort(d, n);
    uint h = 0; bool ok = true;
    for (long i = 0; i < n; i++) {
        h = h * 31u + cast(uint) a[i];
        if (a[i] != b[i] || a[i] != d[i]) ok = false;
        if (i > 0 && a[i - 1] > a[i]) ok = false;
    }
    long bs = 0;
    for (long k = 0; k < n; k++) {
        int key = cast(int)(next() >> 1);
        if (k % 2 == 0) key = orig[cast(uint) key % n];
        bs += bsearchHas(a, n, key);
    }
    long ls = 0;
    for (int k = 0; k < 20; k++) {
        int key = cast(int)(next() >> 1);
        if (k % 2 == 0) key = orig[cast(uint) key % n];
        for (long i = 0; i < n; i++) if (orig[i] == key) { ls++; break; }
    }
    printf("%u %ld %ld %s\n", h, bs, ls, ok ? "OK".ptr : "BAD".ptr);
    return 0;
}

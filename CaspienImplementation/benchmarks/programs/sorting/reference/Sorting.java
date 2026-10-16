// Sorting and searching benchmark (Java): same algorithms as sorting.c, on int[] (garbage collected).
public class Sorting {
    static int x = 12345;
    static int next() { x = x * 1664525 + 1013904223; return x; }

    static void quicksort(int[] a, int n) {
        long[] stack = new long[128]; int sp = 0;
        stack[sp++] = 0; stack[sp++] = n - 1;
        while (sp > 0) {
            long hi = stack[--sp], lo = stack[--sp];
            while (lo < hi) {
                int pivot = a[(int) ((lo + hi) / 2)];
                long i = lo, j = hi;
                while (i <= j) {
                    while (a[(int) i] < pivot) i++;
                    while (a[(int) j] > pivot) j--;
                    if (i <= j) { int t = a[(int) i]; a[(int) i] = a[(int) j]; a[(int) j] = t; i++; j--; }
                }
                if (j - lo < hi - i) { stack[sp++] = i; stack[sp++] = hi; hi = j; }
                else { stack[sp++] = lo; stack[sp++] = j; lo = i; }
            }
        }
    }
    static void mergesort(int[] a, int[] tmp, int n) {
        int[] src = a, dst = tmp;
        for (int w = 1; w < n; w *= 2) {
            for (int lo = 0; lo < n; lo += 2 * w) {
                int mid = Math.min(lo + w, n), hi = Math.min(lo + 2 * w, n);
                int i = lo, j = mid, k = lo;
                while (i < mid && j < hi) dst[k++] = src[i] <= src[j] ? src[i++] : src[j++];
                while (i < mid) dst[k++] = src[i++];
                while (j < hi) dst[k++] = src[j++];
            }
            int[] t = src; src = dst; dst = t;
        }
        if (src != a) System.arraycopy(src, 0, a, 0, n);
    }
    static void siftdown(int[] a, int root, int n) {
        for (;;) {
            int c = 2 * root + 1;
            if (c >= n) break;
            if (c + 1 < n && a[c + 1] > a[c]) c++;
            if (a[root] >= a[c]) break;
            int t = a[root]; a[root] = a[c]; a[c] = t;
            root = c;
        }
    }
    static void heapsort(int[] a, int n) {
        for (int i = n / 2 - 1; i >= 0; i--) siftdown(a, i, n);
        for (int e = n - 1; e > 0; e--) { int t = a[0]; a[0] = a[e]; a[e] = t; siftdown(a, 0, e); }
    }
    static boolean bsearchHas(int[] a, int n, int key) {
        int lo = 0, hi = n;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] < key) lo = m + 1; else hi = m; }
        return lo < n && a[lo] == key;
    }
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 2000000;
        int[] orig = new int[n], a = new int[n], b = new int[n], d = new int[n], tmp = new int[n];
        for (int i = 0; i < n; i++) orig[i] = next() >>> 1;
        System.arraycopy(orig, 0, a, 0, n); System.arraycopy(orig, 0, b, 0, n); System.arraycopy(orig, 0, d, 0, n);
        quicksort(a, n);
        mergesort(b, tmp, n);
        heapsort(d, n);
        int h = 0; boolean ok = true;
        for (int i = 0; i < n; i++) { h = h * 31 + a[i]; if (a[i] != b[i] || a[i] != d[i]) ok = false; if (i > 0 && a[i - 1] > a[i]) ok = false; }
        long bs = 0;
        for (int k = 0; k < n; k++) {
            int key = next() >>> 1;
            if (k % 2 == 0) key = orig[(int) (Integer.toUnsignedLong(key) % n)];
            if (bsearchHas(a, n, key)) bs++;
        }
        long ls = 0;
        for (int k = 0; k < 20; k++) {
            int key = next() >>> 1;
            if (k % 2 == 0) key = orig[(int) (Integer.toUnsignedLong(key) % n)];
            for (int i = 0; i < n; i++) if (orig[i] == key) { ls++; break; }
        }
        System.out.println(Integer.toUnsignedString(h) + " " + bs + " " + ls + " " + (ok ? "OK" : "BAD"));
    }
}

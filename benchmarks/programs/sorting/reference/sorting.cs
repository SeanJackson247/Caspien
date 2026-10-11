using System;

public static class Sorting
{
    static uint x = 12345;
    static uint Next() { x = x * 1664525u + 1013904223u; return x; }

    static void Quicksort(int[] a, int n)
    {
        long[] stack = new long[128]; int sp = 0;
        stack[sp++] = 0; stack[sp++] = n - 1;
        while (sp > 0)
        {
            long hi = stack[--sp], lo = stack[--sp];
            while (lo < hi)
            {
                int pivot = a[(int)((lo + hi) / 2)];
                long i = lo, j = hi;
                while (i <= j)
                {
                    while (a[(int)i] < pivot) i++;
                    while (a[(int)j] > pivot) j--;
                    if (i <= j) { int t = a[(int)i]; a[(int)i] = a[(int)j]; a[(int)j] = t; i++; j--; }
                }
                if (j - lo < hi - i) { stack[sp++] = i; stack[sp++] = hi; hi = j; }
                else { stack[sp++] = lo; stack[sp++] = j; lo = i; }
            }
        }
    }

    static void Mergesort(int[] a, int[] tmp, int n)
    {
        int[] src = a, dst = tmp;
        for (long w = 1; w < n; w *= 2)
        {
            for (long lo = 0; lo < n; lo += 2 * w)
            {
                long mid = lo + w < n ? lo + w : n, hi = lo + 2 * w < n ? lo + 2 * w : n;
                long i = lo, j = mid, k = lo;
                while (i < mid && j < hi) dst[k++] = src[i] <= src[j] ? src[i++] : src[j++];
                while (i < mid) dst[k++] = src[i++];
                while (j < hi) dst[k++] = src[j++];
            }
            int[] t = src; src = dst; dst = t;
        }
        if (src != a) Array.Copy(src, 0, a, 0, n);
    }

    static void Siftdown(int[] a, int root, int n)
    {
        for (; ; )
        {
            int c = 2 * root + 1;
            if (c >= n) break;
            if (c + 1 < n && a[c + 1] > a[c]) c++;
            if (a[root] >= a[c]) break;
            int t = a[root]; a[root] = a[c]; a[c] = t;
            root = c;
        }
    }

    static void Heapsort(int[] a, int n)
    {
        for (int i = n / 2 - 1; i >= 0; i--) Siftdown(a, i, n);
        for (int e = n - 1; e > 0; e--) { int t = a[0]; a[0] = a[e]; a[e] = t; Siftdown(a, 0, e); }
    }

    static bool BsearchHas(int[] a, int n, int key)
    {
        int lo = 0, hi = n;
        while (lo < hi) { int m = (int)(((uint)lo + (uint)hi) >> 1); if (a[m] < key) lo = m + 1; else hi = m; }
        return lo < n && a[lo] == key;
    }

    public static void Main(string[] args)
    {
        int n = args.Length > 0 ? int.Parse(args[0]) : 2000000;
        int[] orig = new int[n], a = new int[n], b = new int[n], d = new int[n], tmp = new int[n];
        for (int i = 0; i < n; i++) orig[i] = (int)(Next() >> 1);
        Array.Copy(orig, a, n); Array.Copy(orig, b, n); Array.Copy(orig, d, n);
        Quicksort(a, n);
        Mergesort(b, tmp, n);
        Heapsort(d, n);
        uint h = 0; bool ok = true;
        for (int i = 0; i < n; i++)
        {
            h = h * 31u + (uint)a[i];
            if (a[i] != b[i] || a[i] != d[i]) ok = false;
            if (i > 0 && a[i - 1] > a[i]) ok = false;
        }
        long bs = 0;
        for (int k = 0; k < n; k++)
        {
            int key = (int)(Next() >> 1);
            if (k % 2 == 0) key = orig[(int)((uint)key % (uint)n)];
            if (BsearchHas(a, n, key)) bs++;
        }
        long ls = 0;
        for (int k = 0; k < 20; k++)
        {
            int key = (int)(Next() >> 1);
            if (k % 2 == 0) key = orig[(int)((uint)key % (uint)n)];
            for (int i = 0; i < n; i++) if (orig[i] == key) { ls++; break; }
        }
        Console.Out.Write(h + " " + bs + " " + ls + " " + (ok ? "OK" : "BAD") + "\n");
    }
}

public class Chain {
    static long chain(long acc, long i, long n) { if (i >= n) return acc; return chain((acc * 31) ^ i, i + 1, n); }
    static long chainLoop(long acc, long i, long n) { for (; i < n; i++) acc = (acc * 31) ^ i; return acc; }
    public static void main(String[] a) {
        long depth = Long.parseLong(a[0]), reps = Long.parseLong(a[1]); boolean lp = a.length > 2 && a[2].equals("loop"); long total = 0;
        for (long r = 0; r < reps; r++) total ^= lp ? chainLoop(r, 0, depth) : chain(r, 0, depth);
        System.out.println(Long.toUnsignedString(total));
    }
}

// k-nucleotide benchmark: same sequence, k-mers and output as knucleotide.c (see there), but the counting uses the language's own hash map keyed by the packed 2-bit k-mer.
// Java: HashMap<Long,Integer> (boxed keys and values).
import java.util.HashMap;
public class Knucleotide {
    static final int[] KS = {1, 2, 3, 4, 6, 12};
    static final int[] QK = {1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12};
    static final long[] QV = {0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487L};
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 30000000;
        int last = 42;
        byte[] seq = new byte[n + 1];
        for (int i = 0; i < n; i++) {
            last = (last * 3877 + 29573) % 139968;
            seq[i] = (byte) (last < 42404 ? 0 : last < 70117 ? 1 : last < 97767 ? 2 : 3);
        }
        long first12 = 0;
        for (int i = 0; i < 12 && i < n; i++) first12 = (first12 << 2) | seq[i];
        long[] distinct = new long[6];
        long[] counts = new long[12];
        int nq = 0;
        for (int ki = 0; ki < 6; ki++) {
            int k = KS[ki];
            HashMap<Long, Integer> m = new HashMap<>();
            long mask = (1L << (2 * k)) - 1, key = 0;
            for (int i = 0; i < n; i++) {
                key = ((key << 2) | seq[i]) & mask;
                if (i + 1 >= k) m.merge(key, 1, Integer::sum);
            }
            distinct[ki] = m.size();
            for (int q = 0; q < 11; q++) {
                if (QK[q] != k) continue;
                Integer v = m.get(QV[q]);
                counts[nq++] = v == null ? 0 : v;
            }
            if (k == 12) {
                Integer v = m.get(first12);
                counts[nq++] = v == null ? 0 : v;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(distinct[i]).append(' ');
        for (int i = 0; i < 12; i++) { if (i > 0) sb.append(' '); sb.append(counts[i]); }
        System.out.println(sb);
    }
}

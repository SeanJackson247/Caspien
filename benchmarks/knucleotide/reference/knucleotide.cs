using System;
using System.Collections.Generic;
using System.Text;

public static class Knucleotide
{
    static readonly int[] KS = { 1, 2, 3, 4, 6, 12 };
    static readonly int[] QK = { 1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12 };
    static readonly ulong[] QV = { 0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487UL };

    public static void Main(string[] args)
    {
        int n = args.Length > 0 ? int.Parse(args[0]) : 30000000;
        int last = 42;
        byte[] seq = new byte[n + 1];
        for (int i = 0; i < n; i++)
        {
            last = (last * 3877 + 29573) % 139968;
            seq[i] = (byte)(last < 42404 ? 0 : last < 70117 ? 1 : last < 97767 ? 2 : 3);
        }
        ulong first12 = 0;
        for (int i = 0; i < 12 && i < n; i++) first12 = (first12 << 2) | seq[i];
        long[] distinct = new long[6];
        long[] counts = new long[12];
        int nq = 0;
        for (int ki = 0; ki < 6; ki++)
        {
            int k = KS[ki];
            Dictionary<ulong, int> m = new Dictionary<ulong, int>();
            ulong mask = (1UL << (2 * k)) - 1, key = 0;
            for (int i = 0; i < n; i++)
            {
                key = ((key << 2) | seq[i]) & mask;
                if (i + 1 >= k)
                {
                    m.TryGetValue(key, out int cur);
                    m[key] = cur + 1;
                }
            }
            distinct[ki] = m.Count;
            for (int q = 0; q < 11; q++)
            {
                if (QK[q] != k) continue;
                counts[nq++] = m.TryGetValue(QV[q], out int v) ? v : 0;
            }
            if (k == 12)
            {
                counts[nq++] = m.TryGetValue(first12, out int v) ? v : 0;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.Append(distinct[i]).Append(' ');
        for (int i = 0; i < 12; i++) { if (i > 0) sb.Append(' '); sb.Append(counts[i]); }
        sb.Append('\n');
        Console.Out.Write(sb.ToString());
    }
}

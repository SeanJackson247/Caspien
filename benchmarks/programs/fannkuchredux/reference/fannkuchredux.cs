using System;

// fannkuch-redux, single threaded. Same algorithm as fannkuchredux-gcc-1 of the Computer Language Benchmarks Game.
public static class FannkuchRedux
{
    static int Fannkuch(int n)
    {
        int[] perm = new int[16], perm1 = new int[16], count = new int[16];
        int maxFlips = 0, permCount = 0, checksum = 0;
        for (int i = 0; i < n; i++) perm1[i] = i;
        int r = n;
        while (true)
        {
            while (r != 1) { count[r - 1] = r; r--; }
            for (int i = 0; i < n; i++) perm[i] = perm1[i];
            int flips = 0, k;
            while ((k = perm[0]) != 0)
            {
                int k2 = (k + 1) >> 1;
                for (int i = 0; i < k2; i++) { int t = perm[i]; perm[i] = perm[k - i]; perm[k - i] = t; }
                flips++;
            }
            if (flips > maxFlips) maxFlips = flips;
            checksum += permCount % 2 == 0 ? flips : -flips;
            while (true)
            {
                if (r == n) { Console.Out.Write(checksum + "\n"); return maxFlips; }
                int perm0 = perm1[0];
                int i = 0;
                while (i < r) { int j = i + 1; perm1[i] = perm1[j]; i = j; }
                perm1[r] = perm0;
                count[r]--;
                if (count[r] > 0) break;
                r++;
            }
            permCount++;
        }
    }

    public static void Main(string[] args)
    {
        int n = args.Length > 0 ? int.Parse(args[0]) : 7;
        int f = Fannkuch(n);
        Console.Out.Write("Pfannkuchen(" + n + ") = " + f + "\n");
    }
}

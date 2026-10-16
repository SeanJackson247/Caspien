using System;
using System.Text;

public static class BinaryTrees
{
    sealed class Node { public Node l, r; }
    static Node Make(int d) { Node n = new Node(); if (d > 0) { n.l = Make(d - 1); n.r = Make(d - 1); } return n; }
    static long Check(Node n) { return n.l == null ? 1 : 1 + Check(n.l) + Check(n.r); }

    public static void Main(string[] args)
    {
        int maxd = args.Length > 0 ? int.Parse(args[0]) : 16;
        if (maxd < 6) maxd = 6;
        StringBuilder sb = new StringBuilder();
        sb.Append(Check(Make(maxd + 1)));
        Node longlived = Make(maxd);
        for (int d = 4; d <= maxd; d += 2)
        {
            long iters = 1L << (maxd - d + 4), sum = 0;
            for (long i = 0; i < iters; i++) sum += Check(Make(d));
            sb.Append(' ').Append(sum);
        }
        sb.Append(' ').Append(Check(longlived));
        sb.Append('\n');
        Console.Out.Write(sb.ToString());
    }
}

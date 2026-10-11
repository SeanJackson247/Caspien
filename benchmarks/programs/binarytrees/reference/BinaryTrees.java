// Binary trees (Java, garbage collected): ordinary heap nodes with left/right references.
public class BinaryTrees {
    static final class Node { Node l, r; }
    static Node make(int d) { Node n = new Node(); if (d > 0) { n.l = make(d - 1); n.r = make(d - 1); } return n; }
    static long check(Node n) { return n.l == null ? 1 : 1 + check(n.l) + check(n.r); }
    public static void main(String[] args) {
        int maxd = args.length > 0 ? Integer.parseInt(args[0]) : 16;
        if (maxd < 6) maxd = 6;
        StringBuilder sb = new StringBuilder();
        sb.append(check(make(maxd + 1)));
        Node longlived = make(maxd);
        for (int d = 4; d <= maxd; d += 2) {
            long iters = 1L << (maxd - d + 4), sum = 0;
            for (long i = 0; i < iters; i++) sum += check(make(d));
            sb.append(' ').append(sum);
        }
        sb.append(' ').append(check(longlived));
        System.out.println(sb);
    }
}

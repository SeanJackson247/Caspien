public class Tree {
    static long visit(long d) { long t = d + 1; if (d > 0) { t += visit(d - 1); t += visit(d - 1); } return t; }
    public static void main(String[] a) { System.out.println(visit(Long.parseLong(a[0]))); }
}

// Heap graph benchmark (Java, garbage collected).
public class Graph {
    static final class Node { int value; int dist = -1; Node e0, e1, e2, e3; }
    static int x = 12345;
    static int next() { x = x * 1664525 + 1013904223; return x; }
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 2000000;
        Node[] nodes = new Node[n];
        for (int i = 0; i < n; i++) { nodes[i] = new Node(); nodes[i].value = next() & 0xFFFFFF; }
        nodes[(int) ((long) n * 7 / 10)].value = 0xFFFFFFFF;
        for (int i = 0; i < n; i++) {
            nodes[i].e0 = nodes[(i + 1) % n];
            nodes[i].e1 = nodes[(int) (Integer.toUnsignedLong(next()) % n)];
            nodes[i].e2 = nodes[(int) (Integer.toUnsignedLong(next()) % n)];
            nodes[i].e3 = nodes[(int) (Integer.toUnsignedLong(next()) % n)];
        }
        Node[] q = new Node[n];
        int head = 0, tail = 0;
        q[tail++] = nodes[0]; nodes[0].dist = 0;
        int maxdepth = 0, needledist = -1, sum = 0;
        while (head < tail) {
            Node u = q[head++];
            sum += u.value;
            if (u.value == 0xFFFFFFFF) needledist = u.dist;
            if (u.dist > maxdepth) maxdepth = u.dist;
            Node v = u.e0; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
            v = u.e1; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
            v = u.e2; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
            v = u.e3; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
        }
        System.out.println(tail + " " + maxdepth + " " + needledist + " " + Integer.toUnsignedString(sum));
    }
}

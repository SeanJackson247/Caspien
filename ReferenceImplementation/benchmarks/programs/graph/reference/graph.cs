using System;

public static class Graph
{
    sealed class Node { public uint value; public int dist = -1; public Node e0, e1, e2, e3; }
    static uint x = 12345;
    static uint Next() { x = x * 1664525u + 1013904223u; return x; }

    public static void Main(string[] args)
    {
        int n = args.Length > 0 ? int.Parse(args[0]) : 2000000;
        Node[] nodes = new Node[n];
        for (int i = 0; i < n; i++) { nodes[i] = new Node(); nodes[i].value = Next() & 0xFFFFFFu; }
        nodes[(int)((long)n * 7 / 10)].value = 0xFFFFFFFFu;
        for (int i = 0; i < n; i++)
        {
            nodes[i].e0 = nodes[(i + 1) % n];
            nodes[i].e1 = nodes[(int)(Next() % (uint)n)];
            nodes[i].e2 = nodes[(int)(Next() % (uint)n)];
            nodes[i].e3 = nodes[(int)(Next() % (uint)n)];
        }
        Node[] q = new Node[n];
        int head = 0, tail = 0;
        q[tail++] = nodes[0]; nodes[0].dist = 0;
        int maxdepth = 0, needledist = -1;
        uint sum = 0;
        while (head < tail)
        {
            Node u = q[head++];
            sum += u.value;
            if (u.value == 0xFFFFFFFFu) needledist = u.dist;
            if (u.dist > maxdepth) maxdepth = u.dist;
            Node v = u.e0; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
            v = u.e1; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
            v = u.e2; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
            v = u.e3; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
        }
        Console.Out.Write(tail + " " + maxdepth + " " + needledist + " " + sum + "\n");
    }
}

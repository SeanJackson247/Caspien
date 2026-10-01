// Heap graph benchmark (port of graph.c): N heap-allocated nodes (final class instances) with 4 outgoing references, BFS from node 0.
import core.stdc.stdio;
import std.conv : to;

__gshared uint x = 12345;
uint next() { x = x * 1664525u + 1013904223u; return x; }

final class Node {
    uint value;
    int dist;
    Node[4] e;
}

int main(string[] args) {
    long n = args.length > 1 ? args[1].to!long : 2_000_000L;
    auto nodes = new Node[n];
    for (long i = 0; i < n; i++) { nodes[i] = new Node; nodes[i].value = next() & 0xFFFFFF; nodes[i].dist = -1; }
    nodes[n * 7 / 10].value = 0xFFFFFFFFu;
    for (long i = 0; i < n; i++) {
        nodes[i].e[0] = nodes[(i + 1) % n];
        for (int k = 1; k < 4; k++) nodes[i].e[k] = nodes[next() % n];
    }
    auto q = new Node[n];
    long head = 0, tail = 0;
    q[tail++] = nodes[0]; nodes[0].dist = 0;
    int maxdepth = 0, needledist = -1;
    uint sum = 0;
    while (head < tail) {
        Node u = q[head++];
        sum += u.value;
        if (u.value == 0xFFFFFFFFu) needledist = u.dist;
        if (u.dist > maxdepth) maxdepth = u.dist;
        for (int k = 0; k < 4; k++) { Node v = u.e[k]; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; } }
    }
    printf("%ld %d %d %u\n", tail, maxdepth, needledist, sum);
    return 0;
}

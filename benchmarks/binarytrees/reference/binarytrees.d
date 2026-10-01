// Binary trees (port of binarytrees.c): one GC-allocated class instance per node, left/right references.
import core.stdc.stdio;
import std.conv : to;

final class Node {
    Node l, r;
}

Node make(int d) {
    auto n = new Node;
    if (d > 0) { n.l = make(d - 1); n.r = make(d - 1); }
    return n;
}
long check(Node n) { return n.l !is null ? 1 + check(n.l) + check(n.r) : 1; }

int main(string[] args) {
    int maxd = args.length > 1 ? args[1].to!int : 16;
    if (maxd < 6) maxd = 6;
    Node t = make(maxd + 1);
    printf("%ld", check(t));
    t = null;
    Node longlived = make(maxd);
    for (int d = 4; d <= maxd; d += 2) {
        long iters = 1L << (maxd - d + 4), sum = 0;
        for (long i = 0; i < iters; i++) { Node a = make(d); sum += check(a); }
        printf(" %ld", sum);
    }
    printf(" %ld\n", check(longlived));
    return 0;
}

// Binary trees (C++, same as binarytrees.c): ordinary heap nodes with left/right POINTERS, one malloc per node.
// Prints one line: stretch-tree check, then for d = 4,6,..,N the summed check of 2^(N-d+4) trees of depth d, then the long-lived tree check.
// check = number of nodes reached by walking the tree. Build with -DLEAK to skip freeing the trees.
#include <cstdio>
#include <cstdlib>
struct Node { Node *l, *r; };
static Node *make(int d) {
    Node *n = (Node *)malloc(sizeof(Node));
    if (d > 0) { n->l = make(d - 1); n->r = make(d - 1); } else { n->l = n->r = nullptr; }
    return n;
}
static long check(const Node *n) { return n->l ? 1 + check(n->l) + check(n->r) : 1; }
static void release(Node *n) {
#ifndef LEAK
    if (n->l) { release(n->l); release(n->r); }
    free(n);
#else
    (void)n;
#endif
}
int main(int argc, char **argv) {
    int maxd = argc > 1 ? atoi(argv[1]) : 16;
    if (maxd < 6) maxd = 6;
    Node *t = make(maxd + 1);
    printf("%ld", check(t));
    release(t);
    Node *longlived = make(maxd);
    for (int d = 4; d <= maxd; d += 2) {
        long iters = 1L << (maxd - d + 4), sum = 0;
        for (long i = 0; i < iters; i++) { Node *a = make(d); sum += check(a); release(a); }
        printf(" %ld", sum);
    }
    printf(" %ld\n", check(longlived));
    release(longlived);
    return 0;
}

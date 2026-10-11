// Heap graph benchmark: N nodes allocated one by one on the heap, each with a random value and 4 outgoing pointers (the next node in a
// ring, so everything is reachable, plus 3 random ones). One node holds the "needle" value 0xFFFFFFFF. A breadth-first search from
// node 0 visits the whole graph; it records the depth at which the needle is found. Prints: reachable maxdepth needledist valuesum.
// Build with -DLEAK to skip freeing the nodes.
#include <stdio.h>
#include <stdlib.h>
typedef struct Node { unsigned value; int dist; struct Node *e[4]; } Node;
static unsigned x = 12345;
static unsigned next(void) { x = x * 1664525u + 1013904223u; return x; }
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 2000000L;
    Node **nodes = malloc(n * sizeof(Node *));
    for (long i = 0; i < n; i++) { nodes[i] = malloc(sizeof(Node)); nodes[i]->value = next() & 0xFFFFFF; nodes[i]->dist = -1; }
    nodes[n * 7 / 10]->value = 0xFFFFFFFFu;
    for (long i = 0; i < n; i++) {
        nodes[i]->e[0] = nodes[(i + 1) % n];
        for (int k = 1; k < 4; k++) nodes[i]->e[k] = nodes[next() % n];
    }
    Node **q = malloc(n * sizeof(Node *));
    long head = 0, tail = 0;
    q[tail++] = nodes[0]; nodes[0]->dist = 0;
    int maxdepth = 0, needledist = -1;
    unsigned sum = 0;
    while (head < tail) {
        Node *u = q[head++];
        sum += u->value;
        if (u->value == 0xFFFFFFFFu) needledist = u->dist;
        if (u->dist > maxdepth) maxdepth = u->dist;
        for (int k = 0; k < 4; k++) { Node *v = u->e[k]; if (v->dist < 0) { v->dist = u->dist + 1; q[tail++] = v; } }
    }
    printf("%ld %d %d %u\n", tail, maxdepth, needledist, sum);
#ifndef LEAK
    for (long i = 0; i < n; i++) free(nodes[i]);
    free(nodes); free(q);
#endif
    return 0;
}

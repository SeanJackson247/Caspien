// Heap graph benchmark (C++). Default: nodes owned by std::unique_ptr in a std::vector (freed automatically); -DLEAK: plain new, never deleted.
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <vector>
struct Node { unsigned value; int dist; Node *e[4]; };
static unsigned x = 12345;
static unsigned next() { x = x * 1664525u + 1013904223u; return x; }
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 2000000L;
    std::vector<Node *> nodes(n);
#ifndef LEAK
    std::vector<std::unique_ptr<Node>> owner(n);
#endif
    for (long i = 0; i < n; i++) {
#ifdef LEAK
        nodes[i] = new Node;
#else
        owner[i].reset(new Node); nodes[i] = owner[i].get();
#endif
        nodes[i]->value = next() & 0xFFFFFF; nodes[i]->dist = -1;
    }
    nodes[n * 7 / 10]->value = 0xFFFFFFFFu;
    for (long i = 0; i < n; i++) {
        nodes[i]->e[0] = nodes[(i + 1) % n];
        for (int k = 1; k < 4; k++) nodes[i]->e[k] = nodes[next() % n];
    }
    std::vector<Node *> q(n);
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
    return 0;
}

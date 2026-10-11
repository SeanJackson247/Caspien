// Heap graph benchmark: N node objects allocated one by one on the heap, each with a random value and 4 outgoing references
// (the next node in a ring plus 3 random ones), then a breadth-first search from node 0 (see graph.c).
#include <objc/objc.h>
#include <objc/runtime.h>
#pragma GCC diagnostic ignored "-Wobjc-root-class"
// GNU libobjc ships no Foundation / NSObject here, so every class derives from this tiny root class (instances are heap objects
// created with class_createInstance, i.e. one allocation per object, released with object_dispose).
@interface Base { Class isa; }
+ (id)new;
- (void)free;
@end
@implementation Base
+ (id)new { return class_createInstance(self, 0); }
- (void)free { object_dispose(self); }
@end

#include <stdio.h>
#include <stdlib.h>
static unsigned x = 12345;
static unsigned next(void) { x = x * 1664525u + 1013904223u; return x; }

@interface GNode : Base { @public unsigned value; int dist; GNode *e[4]; }
@end
@implementation GNode
@end

@interface Graph : Base { @public long n; GNode **nodes; GNode **q; long tail; int maxdepth, needledist; unsigned sum; }
- (id)initWithN:(long)count;
- (void)bfs;
- (void)destroy;
@end
@implementation Graph
- (id)initWithN:(long)count {
    long i; int k;
    n = count;
    nodes = malloc(n * sizeof(GNode *));
    for (i = 0; i < n; i++) { nodes[i] = [GNode new]; nodes[i]->value = next() & 0xFFFFFF; nodes[i]->dist = -1; }
    nodes[n * 7 / 10]->value = 0xFFFFFFFFu;
    for (i = 0; i < n; i++) {
        nodes[i]->e[0] = nodes[(i + 1) % n];
        for (k = 1; k < 4; k++) nodes[i]->e[k] = nodes[next() % n];
    }
    q = malloc(n * sizeof(GNode *));
    return self;
}
- (void)bfs {
    long head = 0; int k;
    GNode *u, *v;
    tail = 0; maxdepth = 0; needledist = -1; sum = 0;
    q[tail++] = nodes[0]; nodes[0]->dist = 0;
    while (head < tail) {
        u = q[head++];
        sum += u->value;
        if (u->value == 0xFFFFFFFFu) needledist = u->dist;
        if (u->dist > maxdepth) maxdepth = u->dist;
        for (k = 0; k < 4; k++) { v = u->e[k]; if (v->dist < 0) { v->dist = u->dist + 1; q[tail++] = v; } }
    }
}
- (void)destroy {
    long i;
    for (i = 0; i < n; i++) [nodes[i] free];
    free(nodes); free(q);
    [self free];
}
@end

int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 2000000L;
    Graph *g = [[Graph new] initWithN:n];
    [g bfs];
    printf("%ld %d %d %u\n", g->tail, g->maxdepth, g->needledist, g->sum);
    [g destroy];
    return 0;
}

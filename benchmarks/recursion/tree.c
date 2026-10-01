// Non-tail recursion: visit every node of a complete binary tree of depth D (count nodes and sum depths), recursive vs explicit stack (-DSTACK).
#include <stdio.h>
#include <stdlib.h>
typedef unsigned long u64;
#ifndef STACK
static u64 visit(u64 d) {
    u64 t = d + 1;
    if (d > 0) { t += visit(d - 1); t += visit(d - 1); }
    return t;
}
int main(int argc, char **argv) {
    u64 depth = argc > 1 ? strtoul(argv[1], 0, 10) : 25;
    printf("%lu\n", visit(depth));
    return 0;
}
#else
int main(int argc, char **argv) {
    u64 depth = argc > 1 ? strtoul(argv[1], 0, 10) : 25;
    u64 *stack = malloc((depth + 3) * sizeof(u64)), sp = 0, total = 0;
    stack[sp++] = depth;
    while (sp > 0) {
        u64 d = stack[--sp];
        total += d + 1;
        if (d > 0) { stack[sp] = d - 1; stack[sp + 1] = d - 1; sp += 2; }
    }
    printf("%lu\n", total);
    free(stack);
    return 0;
}
#endif

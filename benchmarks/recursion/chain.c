// Linear (tail) recursion, depth D, repeated R times: acc = acc*31 ^ i. Same work as the Caspien @recursive function.
// Build: -O0 (a real call per step), -O1 -fno-inline (still a real call), -O2 (gcc turns the tail call into a loop), and the hand loop (-DLOOP).
#include <stdio.h>
#include <stdlib.h>
typedef unsigned long u64;
#ifndef LOOP
static u64 chain(u64 acc, u64 i, u64 n) {
    if (i >= n) return acc;
    return chain((acc * 31) ^ i, i + 1, n);
}
#else
static u64 chain(u64 acc, u64 i, u64 n) {
    for (; i < n; i++) acc = (acc * 31) ^ i;
    return acc;
}
#endif
int main(int argc, char **argv) {
    u64 depth = argc > 1 ? strtoul(argv[1], 0, 10) : 1000, reps = argc > 2 ? strtoul(argv[2], 0, 10) : 100000, total = 0;
    for (u64 rep = 0; rep < reps; rep++) total ^= chain(rep, 0, depth);
    printf("%lu\n", total);
    return 0;
}

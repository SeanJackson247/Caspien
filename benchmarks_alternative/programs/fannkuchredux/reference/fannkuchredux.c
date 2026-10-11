/* fannkuch-redux, single threaded. Algorithm of the Computer Language Benchmarks Game program fannkuchredux-gcc-1
   (Joseph Piche, after Oleg Mazurov and Isaac Gouy's Java version). */
#include <stdio.h>
#include <stdlib.h>

static int fannkuchredux(int n) {
    int perm[16], perm1[16], count[16];
    int maxFlipsCount = 0, permCount = 0, checksum = 0;
    int i, r = n;
    for (i = 0; i < n; i++) perm1[i] = i;
    while (1) {
        while (r != 1) { count[r - 1] = r; r--; }
        for (i = 0; i < n; i++) perm[i] = perm1[i];
        int flipsCount = 0, k;
        while ((k = perm[0]) != 0) {
            int k2 = (k + 1) >> 1;
            for (i = 0; i < k2; i++) { int t = perm[i]; perm[i] = perm[k - i]; perm[k - i] = t; }
            flipsCount++;
        }
        if (flipsCount > maxFlipsCount) maxFlipsCount = flipsCount;
        checksum += permCount % 2 == 0 ? flipsCount : -flipsCount;
        while (1) {
            if (r == n) { printf("%d\n", checksum); return maxFlipsCount; }
            int perm0 = perm1[0];
            i = 0;
            while (i < r) { int j = i + 1; perm1[i] = perm1[j]; i = j; }
            perm1[r] = perm0;
            count[r]--;
            if (count[r] > 0) break;
            r++;
        }
        permCount++;
    }
}

int main(int argc, char **argv) {
    int n = argc > 1 ? atoi(argv[1]) : 7;
    printf("Pfannkuchen(%d) = %d\n", n, fannkuchredux(n));
    return 0;
}

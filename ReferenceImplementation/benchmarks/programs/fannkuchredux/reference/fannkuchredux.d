// fannkuch-redux (port of fannkuchredux.c), single threaded.
import core.stdc.stdio;
import std.conv : to;

int fannkuchredux(int n) {
    int[16] perm, perm1, count;
    int maxFlipsCount = 0, permCount = 0, checksum = 0;
    int i, r = n;
    for (i = 0; i < n; i++) perm1[i] = i;
    while (true) {
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
        while (true) {
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

int main(string[] args) {
    int n = args.length > 1 ? args[1].to!int : 7;
    printf("Pfannkuchen(%d) = %d\n", n, fannkuchredux(n));
    return 0;
}

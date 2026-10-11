// k-nucleotide benchmark (port of knucleotide.c): hand-written open-addressing table (array of structs), linear probing.
import core.stdc.stdio;
import std.conv : to;

struct Slot { ulong key; uint cnt; }

immutable int[6] KS = [1, 2, 3, 4, 6, 12];
immutable int[11] QK = [1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12];
immutable ulong[11] QV = [0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487];

ulong lookup(Slot[] tab, ulong kk, int bits, long cap) {
    ulong h = (kk * 0x9E3779B97F4A7C15UL) >> (64 - bits);
    for (;;) {
        if (tab[h].key == 0) return 0;
        if (tab[h].key == kk) return tab[h].cnt;
        h = (h + 1) & (cap - 1);
    }
}

int main(string[] args) {
    long n = args.length > 1 ? args[1].to!long : 30_000_000L;
    auto seq = new ubyte[n + 1];
    uint last = 42;
    for (long i = 0; i < n; i++) {
        last = (last * 3877u + 29573u) % 139968u;
        uint c;
        if (last < 42404) c = 0; else if (last < 70117) c = 1; else if (last < 97767) c = 2; else c = 3;
        seq[i] = cast(ubyte) c;
    }
    ulong first12 = 0;
    for (int i = 0; i < 12 && i < n; i++) first12 = (first12 << 2) | seq[i];
    ulong[6] distinct;
    ulong[12] counts;
    int nq = 0;
    for (int ki = 0; ki < 6; ki++) {
        int k = KS[ki];
        long maxd = 1L << (2 * k);
        long win = n >= k ? n - k + 1 : 0;
        if (win < maxd) maxd = win;
        if (maxd > 139968) maxd = 139968;
        int bits = 1;
        while ((1L << bits) < 2 * maxd) bits++;
        long cap = 1L << bits;
        auto tab = new Slot[cap];
        ulong mask = (1UL << (2 * k)) - 1;
        ulong key = 0, nd = 0;
        for (long i = 0; i < n; i++) {
            key = ((key << 2) | seq[i]) & mask;
            if (i + 1 >= k) {
                ulong h = ((key + 1) * 0x9E3779B97F4A7C15UL) >> (64 - bits);
                for (;;) {
                    if (tab[h].key == 0) { tab[h].key = key + 1; tab[h].cnt = 1; nd++; break; }
                    if (tab[h].key == key + 1) { tab[h].cnt++; break; }
                    h = (h + 1) & (cap - 1);
                }
            }
        }
        distinct[ki] = nd;
        for (int q = 0; q < 11; q++) {
            if (QK[q] != k) continue;
            counts[nq++] = lookup(tab, QV[q] + 1, bits, cap);
        }
        if (k == 12) counts[nq++] = lookup(tab, first12 + 1, bits, cap);
    }
    for (int i = 0; i < 6; i++) printf("%llu ", distinct[i]);
    for (int i = 0; i < 12; i++) printf(i ? " %llu".ptr : "%llu".ptr, counts[i]);
    printf("\n");
    return 0;
}

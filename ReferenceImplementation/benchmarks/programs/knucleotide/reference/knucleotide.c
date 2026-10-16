// k-nucleotide benchmark (benchmarks-game style). A DNA sequence of N bases (A,C,G,T = 0..3) is drawn from the human-frequency
// alphabet with the benchmarks-game LCG (IM=139968, IA=3877, IC=29573, seed 42, integer thresholds 42404 70117 97767), then
// for k = 1,2,3,4,6,12 every overlapping k-mer (packed 2 bits per base into a u64 key) is counted in a hash map.
// This C version uses a hand-written open-addressing table (linear probing, multiplicative hash, power-of-two capacity >= 2 x
// the largest possible number of distinct k-mers, min(4^k, N-k+1, 139968): the LCG state repeats after 139968 steps; the key is stored +1 so 0 means empty). Prints 18 numbers: the number of
// distinct k-mers for k = 1,2,3,4,6,12, then the counts of A C G T GG GT AA GGT GGTA GGTATT GGTATTTTAATT and of the first
// 12-mer of the sequence. Build with -DLEAK to skip the free() calls.
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
typedef struct { uint64_t key; uint32_t cnt; } Slot;
static unsigned last = 42;
static const int KS[6] = {1, 2, 3, 4, 6, 12};
// queries: k and packed key
static const int QK[11] = {1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12};
static const uint64_t QV[11] = {0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487};
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 30000000L;
    unsigned char *seq = malloc(n + 1);
    for (long i = 0; i < n; i++) {
        last = (last * 3877u + 29573u) % 139968u;
        unsigned c;
        if (last < 42404) c = 0; else if (last < 70117) c = 1; else if (last < 97767) c = 2; else c = 3;
        seq[i] = (unsigned char)c;
    }
    uint64_t first12 = 0;
    for (int i = 0; i < 12 && i < n; i++) first12 = (first12 << 2) | seq[i];
    uint64_t distinct[6], counts[12];
    int nq = 0;
    for (int ki = 0; ki < 6; ki++) {
        int k = KS[ki];
        long maxd = (long)1 << (2 * k);
        long win = n >= k ? n - k + 1 : 0;
        if (win < maxd) maxd = win;
        if (maxd > 139968) maxd = 139968;   // the LCG state has period IM = 139968, so no more distinct k-mers than that
        int bits = 1;
        while (((long)1 << bits) < 2 * maxd) bits++;
        long cap = (long)1 << bits;
        Slot *tab = calloc(cap, sizeof(Slot));
        uint64_t mask = (k == 32) ? ~0ULL : (((uint64_t)1 << (2 * k)) - 1);
        uint64_t key = 0, nd = 0;
        for (long i = 0; i < n; i++) {
            key = ((key << 2) | seq[i]) & mask;
            if (i + 1 >= k) {
                uint64_t h = ((key + 1) * 0x9E3779B97F4A7C15ULL) >> (64 - bits);
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
            uint64_t v = QV[q], kk = v + 1;
            uint64_t h = (kk * 0x9E3779B97F4A7C15ULL) >> (64 - bits);
            uint64_t res = 0;
            for (;;) {
                if (tab[h].key == 0) break;
                if (tab[h].key == kk) { res = tab[h].cnt; break; }
                h = (h + 1) & (cap - 1);
            }
            counts[nq++] = res;
        }
        if (k == 12) {
            uint64_t kk = first12 + 1;
            uint64_t h = (kk * 0x9E3779B97F4A7C15ULL) >> (64 - bits);
            uint64_t res = 0;
            for (;;) {
                if (tab[h].key == 0) break;
                if (tab[h].key == kk) { res = tab[h].cnt; break; }
                h = (h + 1) & (cap - 1);
            }
            counts[nq++] = res;
        }
#ifndef LEAK
        free(tab);
#endif
    }
    for (int i = 0; i < 6; i++) printf("%llu ", (unsigned long long)distinct[i]);
    for (int i = 0; i < 12; i++) printf(i ? " %llu" : "%llu", (unsigned long long)counts[i]);
    printf("\n");
#ifndef LEAK
    free(seq);
#endif
    return 0;
}

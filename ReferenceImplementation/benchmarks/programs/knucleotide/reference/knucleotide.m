// k-nucleotide (see knucleotide.c): a KmerTable object wraps the hand-written open-addressing table (linear probing,
// multiplicative hash, power-of-two capacity, key stored +1 so 0 means empty).
#include <objc/objc.h>
#include <objc/runtime.h>
#pragma GCC diagnostic ignored "-Wobjc-root-class"
// GNU libobjc ships no Foundation / NSObject here, so every class derives from this tiny root class (instances are heap objects
// created with class_createInstance, released with object_dispose).
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
#include <stdint.h>
typedef struct { uint64_t key; uint32_t cnt; } Slot;
static unsigned last = 42;
static const int KS[6] = {1, 2, 3, 4, 6, 12};
static const int QK[11] = {1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12};
static const uint64_t QV[11] = {0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487};

@interface KmerTable : Base { @public Slot *tab; int bits; long cap; uint64_t distinct; }
- (id)initForMax:(long)maxd;
- (void)add:(uint64_t)key;
- (uint64_t)count:(uint64_t)key;
- (void)destroy;
@end
@implementation KmerTable
- (id)initForMax:(long)maxd {
    bits = 1;
    while (((long)1 << bits) < 2 * maxd) bits++;
    cap = (long)1 << bits;
    tab = calloc(cap, sizeof(Slot));
    distinct = 0;
    return self;
}
- (void)add:(uint64_t)key {
    uint64_t h = ((key + 1) * 0x9E3779B97F4A7C15ULL) >> (64 - bits);
    for (;;) {
        if (tab[h].key == 0) { tab[h].key = key + 1; tab[h].cnt = 1; distinct++; break; }
        if (tab[h].key == key + 1) { tab[h].cnt++; break; }
        h = (h + 1) & (cap - 1);
    }
}
- (uint64_t)count:(uint64_t)key {
    uint64_t kk = key + 1, h = (kk * 0x9E3779B97F4A7C15ULL) >> (64 - bits);
    for (;;) {
        if (tab[h].key == 0) return 0;
        if (tab[h].key == kk) return tab[h].cnt;
        h = (h + 1) & (cap - 1);
    }
}
- (void)destroy { free(tab); [self free]; }
@end

int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 30000000L;
    unsigned char *seq = malloc(n + 1);
    long i, maxd, win;
    int ki, k, q, nq = 0;
    unsigned c;
    uint64_t first12 = 0, mask, key, distinct[6], counts[12];
    KmerTable *t;
    for (i = 0; i < n; i++) {
        last = (last * 3877u + 29573u) % 139968u;
        if (last < 42404) c = 0; else if (last < 70117) c = 1; else if (last < 97767) c = 2; else c = 3;
        seq[i] = (unsigned char)c;
    }
    for (i = 0; i < 12 && i < n; i++) first12 = (first12 << 2) | seq[i];
    for (ki = 0; ki < 6; ki++) {
        k = KS[ki];
        maxd = (long)1 << (2 * k);
        win = n >= k ? n - k + 1 : 0;
        if (win < maxd) maxd = win;
        if (maxd > 139968) maxd = 139968;
        t = [[KmerTable new] initForMax:maxd];
        mask = (((uint64_t)1 << (2 * k)) - 1);
        key = 0;
        for (i = 0; i < n; i++) {
            key = ((key << 2) | seq[i]) & mask;
            if (i + 1 >= k) [t add:key];
        }
        distinct[ki] = t->distinct;
        for (q = 0; q < 11; q++) if (QK[q] == k) counts[nq++] = [t count:QV[q]];
        if (k == 12) counts[nq++] = [t count:first12];
        [t destroy];
    }
    for (i = 0; i < 6; i++) printf("%llu ", (unsigned long long)distinct[i]);
    for (i = 0; i < 12; i++) printf(i ? " %llu" : "%llu", (unsigned long long)counts[i]);
    printf("\n");
    free(seq);
    return 0;
}

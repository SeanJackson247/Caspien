// k-nucleotide benchmark: same sequence, k-mers and output as knucleotide.c (see there), but the counting uses the language's own hash map keyed by the packed 2-bit k-mer.
// C++: std::unordered_map<uint64_t,uint32_t>. Default: maps and the sequence freed automatically; -DLEAK: heap-allocated, never deleted.
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <unordered_map>
#include <vector>
static unsigned last = 42;
static const int KS[6] = {1, 2, 3, 4, 6, 12};
static const int QK[11] = {1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12};
static const uint64_t QV[11] = {0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487};
typedef std::unordered_map<uint64_t, uint32_t> Map;
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 30000000L;
#ifdef LEAK
    unsigned char *seq = new unsigned char[n + 1];
#else
    std::vector<unsigned char> seqV(n + 1);
    unsigned char *seq = seqV.data();
#endif
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
#ifdef LEAK
        Map &m = *new Map();
#else
        Map m;
#endif
        uint64_t mask = ((uint64_t)1 << (2 * k)) - 1, key = 0;
        for (long i = 0; i < n; i++) {
            key = ((key << 2) | seq[i]) & mask;
            if (i + 1 >= k) m[key]++;
        }
        distinct[ki] = m.size();
        for (int q = 0; q < 11; q++) {
            if (QK[q] != k) continue;
            auto it = m.find(QV[q]);
            counts[nq++] = it == m.end() ? 0 : it->second;
        }
        if (k == 12) {
            auto it = m.find(first12);
            counts[nq++] = it == m.end() ? 0 : it->second;
        }
    }
    for (int i = 0; i < 6; i++) printf("%llu ", (unsigned long long)distinct[i]);
    for (int i = 0; i < 12; i++) printf(i ? " %llu" : "%llu", (unsigned long long)counts[i]);
    printf("\n");
    return 0;
}

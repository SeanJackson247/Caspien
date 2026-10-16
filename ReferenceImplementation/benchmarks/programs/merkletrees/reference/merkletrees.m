// Merkle tree benchmark (real SHA-256, FIPS 180-4, hand-written; see merkletrees.c). A Sha256 class holds the hash primitives
// as class methods and a MerkleTree object owns the leaf data and the flat array of 8-word digests.
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
#include <stdint.h>
static uint32_t x = 12345;
static uint32_t next(void) { x = x * 1664525u + 1013904223u; return x; }
static const uint32_t K[64] = {
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
static const uint32_t H0[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
#define ROR(v, n) (((v) >> (n)) | ((v) << (32 - (n))))
// one compression: st[8] updated in place with the 16-word big-endian block b
static void compress(uint32_t *st, const uint32_t *b) {
    int i;
    uint32_t w[64];
    for (i = 0; i < 16; i++) w[i] = b[i];
    for (i = 16; i < 64; i++) {
        uint32_t s0 = ROR(w[i-15], 7) ^ ROR(w[i-15], 18) ^ (w[i-15] >> 3);
        uint32_t s1 = ROR(w[i-2], 17) ^ ROR(w[i-2], 19) ^ (w[i-2] >> 10);
        w[i] = w[i-16] + s0 + w[i-7] + s1;
    }
    uint32_t a = st[0], bb = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
    for (i = 0; i < 64; i++) {
        uint32_t S1 = ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + S1 + ch + K[i] + w[i];
        uint32_t S0 = ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22);
        uint32_t mj = (a & bb) ^ (a & c) ^ (bb & c);
        uint32_t t2 = S0 + mj;
        h = g; g = f; f = e; e = d + t1; d = c; c = bb; bb = a; a = t1 + t2;
    }
    st[0] += a; st[1] += bb; st[2] += c; st[3] += d; st[4] += e; st[5] += f; st[6] += g; st[7] += h;
}
static const uint32_t PAD[16] = {0x80000000u, 0,0,0,0,0,0,0,0,0,0,0,0,0,0, 512};
static inline uint32_t bswap32(uint32_t v) { return (v >> 24) | ((v >> 8) & 0xff00u) | ((v << 8) & 0xff0000u) | (v << 24); }
// out[8] = SHA256(le64(dlo + 2^32*dhi) || le64(i))
static void leaf(uint32_t *out, uint32_t dlo, uint32_t dhi, uint64_t i) {
    int k;
    uint32_t b[16] = {bswap32(dlo), bswap32(dhi), bswap32((uint32_t)i), bswap32((uint32_t)(i >> 32)), 0x80000000u, 0,0,0,0,0,0,0,0,0,0, 128};
    for (k = 0; k < 8; k++) out[k] = H0[k];
    compress(out, b);
}
// out[8] = SHA256(l[8 words as 32 bytes] || r[8 words as 32 bytes]); out may alias l or r
static void node(uint32_t *out, const uint32_t *l, const uint32_t *r) {
    uint32_t b[16], st[8];
    int k;
    for (k = 0; k < 8; k++) { b[k] = l[k]; b[8 + k] = r[k]; st[k] = H0[k]; }
    compress(st, b);
    compress(st, PAD);
    for (k = 0; k < 8; k++) out[k] = st[k];
}

@interface Sha256 : Base
+ (void)leaf:(uint32_t *)dst lo:(uint32_t)dlo hi:(uint32_t)dhi index:(uint64_t)i;
+ (void)node:(uint32_t *)dst left:(const uint32_t *)l right:(const uint32_t *)r;
@end
@implementation Sha256
+ (void)leaf:(uint32_t *)dst lo:(uint32_t)dlo hi:(uint32_t)dhi index:(uint64_t)i { leaf(dst, dlo, dhi, i); }
+ (void)node:(uint32_t *)dst left:(const uint32_t *)l right:(const uint32_t *)r { node(dst, l, r); }
@end

@interface MerkleTree : Base { @public long n; uint32_t *data, *t; long rootOff; }
- (id)buildWithLeaves:(long)count;
- (void)verifyProofs:(uint64_t *)verified rejected:(uint64_t *)rejected;
- (void)destroy;
@end
@implementation MerkleTree
- (id)buildWithLeaves:(long)count {
    long i, off, size, noff, ns, j;
    const uint32_t *l, *r;
    n = count;
    data = malloc(n * 4);
    t = malloc((2 * n + 64) * 32);
    for (i = 0; i < n; i++) { data[i] = next(); [Sha256 leaf:t + 8 * i lo:data[i] hi:0 index:(uint64_t)i]; }
    off = 0; size = n;
    while (size > 1) {
        noff = off + size; ns = (size + 1) / 2;
        for (j = 0; j < ns; j++) {
            l = t + 8 * (off + 2 * j);
            r = (2 * j + 1 < size) ? t + 8 * (off + 2 * j + 1) : l;
            [Sha256 node:t + 8 * (noff + j) left:l right:r];
        }
        off = noff; size = ns;
    }
    rootOff = off;
    return self;
}
- (void)verifyProofs:(uint64_t *)verified rejected:(uint64_t *)rejected {
    const uint32_t *root = t + 8 * rootOff;
    uint32_t sibs[64 * 8], cur[8], dlo, dhi;
    long p, idx, o, s, pos, depth, sb, d;
    int k, eq;
    *verified = 0; *rejected = 0;
    for (p = 0; p < n / 2; p++) {
        idx = next() % n;
        o = 0; s = n; pos = idx; depth = 0;
        while (s > 1) {
            sb = (pos % 2 == 0) ? pos + 1 : pos - 1;
            if (sb >= s) sb = pos;
            for (k = 0; k < 8; k++) sibs[depth * 8 + k] = t[8 * (o + sb) + k];
            depth++;
            o += s; s = (s + 1) / 2; pos /= 2;
        }
        dlo = data[idx]; dhi = 0;
        if (p % 4 == 3) { dlo += 1; dhi = (dlo == 0); }
        [Sha256 leaf:cur lo:dlo hi:dhi index:(uint64_t)idx];
        pos = idx;
        for (d = 0; d < depth; d++) {
            if (pos % 2 == 0) [Sha256 node:cur left:cur right:sibs + 8 * d]; else [Sha256 node:cur left:sibs + 8 * d right:cur];
            pos /= 2;
        }
        eq = 1;
        for (k = 0; k < 8; k++) if (cur[k] != root[k]) eq = 0;
        if (eq) (*verified)++; else (*rejected)++;
    }
}
- (void)destroy { free(data); free(t); [self free]; }
@end

int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 140000L;
    MerkleTree *m = [[MerkleTree new] buildWithLeaves:n];
    uint64_t verified, rejected;
    int k;
    [m verifyProofs:&verified rejected:&rejected];
    for (k = 0; k < 8; k++) printf("%08x", m->t[8 * m->rootOff + k]);
    printf(" %llu %llu\n", (unsigned long long)verified, (unsigned long long)rejected);
    [m destroy];
    return 0;
}

// Merkle tree benchmark (port of merkletrees.c): hand-written SHA-256, one flat uint array of 8-word digests.
import core.stdc.stdio;
import std.conv : to;

__gshared uint x = 12345;
uint next() { x = x * 1664525u + 1013904223u; return x; }

immutable uint[64] K = [
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
immutable uint[8] H0 = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];

uint ror(uint v, uint n) { return (v >> n) | (v << (32 - n)); }

// one compression: st[0..8] updated in place with the 16-word big-endian block b
void compress(uint[] st, const(uint)[] b) {
    uint[64] w;
    for (int i = 0; i < 16; i++) w[i] = b[i];
    for (int i = 16; i < 64; i++) {
        uint s0 = ror(w[i-15], 7) ^ ror(w[i-15], 18) ^ (w[i-15] >> 3);
        uint s1 = ror(w[i-2], 17) ^ ror(w[i-2], 19) ^ (w[i-2] >> 10);
        w[i] = w[i-16] + s0 + w[i-7] + s1;
    }
    uint a = st[0], bb = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
    for (int i = 0; i < 64; i++) {
        uint S1 = ror(e, 6) ^ ror(e, 11) ^ ror(e, 25);
        uint ch = (e & f) ^ (~e & g);
        uint t1 = h + S1 + ch + K[i] + w[i];
        uint S0 = ror(a, 2) ^ ror(a, 13) ^ ror(a, 22);
        uint mj = (a & bb) ^ (a & c) ^ (bb & c);
        uint t2 = S0 + mj;
        h = g; g = f; f = e; e = d + t1; d = c; c = bb; bb = a; a = t1 + t2;
    }
    st[0] += a; st[1] += bb; st[2] += c; st[3] += d; st[4] += e; st[5] += f; st[6] += g; st[7] += h;
}
uint bswap32(uint v) { return (v >> 24) | ((v >> 8) & 0xff00u) | ((v << 8) & 0xff0000u) | (v << 24); }

// out[0..8] = SHA256(le64(dlo + 2^32*dhi) || le64(i))
void leaf(uint[] o, uint dlo, uint dhi, ulong i) {
    uint[16] b = [bswap32(dlo), bswap32(dhi), bswap32(cast(uint) i), bswap32(cast(uint)(i >> 32)), 0x80000000u, 0,0,0,0,0,0,0,0,0,0, 128];
    for (int k = 0; k < 8; k++) o[k] = H0[k];
    compress(o, b[]);
}
// out = SHA256(l || r); out may alias l or r
void node(uint[] o, const(uint)[] l, const(uint)[] r) {
    uint[16] b; uint[8] st;
    for (int k = 0; k < 8; k++) { b[k] = l[k]; b[8 + k] = r[k]; st[k] = H0[k]; }
    compress(st[], b[]);
    uint[16] p = [0x80000000u, 0,0,0,0,0,0,0,0,0,0,0,0,0,0, 512];
    compress(st[], p[]);
    for (int k = 0; k < 8; k++) o[k] = st[k];
}

int main(string[] args) {
    long n = args.length > 1 ? args[1].to!long : 140_000L;
    auto data = new uint[n];
    auto t = new uint[(2 * n + 64) * 8];
    for (long i = 0; i < n; i++) { data[i] = next(); leaf(t[8 * i .. 8 * i + 8], data[i], 0, cast(ulong) i); }
    long off = 0, size = n;
    while (size > 1) {
        long noff = off + size, ns = (size + 1) / 2;
        for (long j = 0; j < ns; j++) {
            long li = 8 * (off + 2 * j);
            long ri = (2 * j + 1 < size) ? 8 * (off + 2 * j + 1) : li;
            node(t[8 * (noff + j) .. 8 * (noff + j) + 8], t[li .. li + 8], t[ri .. ri + 8]);
        }
        off = noff; size = ns;
    }
    const(uint)[] root = t[8 * off .. 8 * off + 8];
    ulong verified = 0, rejected = 0;
    uint[64 * 8] sibs;
    uint[8] cur;
    for (long p = 0; p < n / 2; p++) {
        long idx = next() % n;
        long o = 0, s = n, pos = idx, depth = 0;
        while (s > 1) {
            long sb = (pos % 2 == 0) ? pos + 1 : pos - 1;
            if (sb >= s) sb = pos;
            for (int k = 0; k < 8; k++) sibs[depth * 8 + k] = t[8 * (o + sb) + k];
            depth++;
            o += s; s = (s + 1) / 2; pos /= 2;
        }
        uint dlo = data[idx], dhi = 0;
        if (p % 4 == 3) { dlo += 1; dhi = (dlo == 0); }
        leaf(cur[], dlo, dhi, cast(ulong) idx);
        pos = idx;
        for (long d = 0; d < depth; d++) {
            if (pos % 2 == 0) node(cur[], cur[], sibs[8 * d .. 8 * d + 8]); else node(cur[], sibs[8 * d .. 8 * d + 8], cur[]);
            pos /= 2;
        }
        bool eq = true;
        for (int k = 0; k < 8; k++) if (cur[k] != root[k]) eq = false;
        if (eq) verified++; else rejected++;
    }
    for (int k = 0; k < 8; k++) printf("%08x", root[k]);
    printf(" %llu %llu\n", verified, rejected);
    return 0;
}

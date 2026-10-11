// Merkle tree benchmark (Java, garbage collected, real SHA-256): same algorithm as merkletrees.c (int arithmetic wraps; >>> is the unsigned shift).
public class MerkleTrees {
    static int x = 12345;
    static int next() { x = x * 1664525 + 1013904223; return x; }
    static final int[] K = {
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    static final int[] H0 = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    static final int[] w = new int[64];
    static final int[] blk = new int[16];
    static final int[] st = new int[8];
    static void compress() {
        for (int i = 0; i < 16; i++) w[i] = blk[i];
        for (int i = 16; i < 64; i++) {
            int a = w[i - 15], c = w[i - 2];
            int s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
            int s1 = ((c >>> 17) | (c << 15)) ^ ((c >>> 19) | (c << 13)) ^ (c >>> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        int a = st[0], b = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
        for (int i = 0; i < 64; i++) {
            int S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
            int ch = (e & f) ^ (~e & g);
            int t1 = h + S1 + ch + K[i] + w[i];
            int S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
            int mj = (a & b) ^ (a & c) ^ (b & c);
            int t2 = S0 + mj;
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        st[0] += a; st[1] += b; st[2] += c; st[3] += d; st[4] += e; st[5] += f; st[6] += g; st[7] += h;
    }
    // out[oo..oo+7] = SHA256(le64(dlo + 2^32 dhi) || le64(i))
    static void leaf(int[] out, int oo, int dlo, int dhi, long i) {
        for (int k = 0; k < 16; k++) blk[k] = 0;
        blk[0] = Integer.reverseBytes(dlo); blk[1] = Integer.reverseBytes(dhi);
        blk[2] = Integer.reverseBytes((int) i); blk[3] = Integer.reverseBytes((int) (i >>> 32));
        blk[4] = 0x80000000; blk[15] = 128;
        for (int k = 0; k < 8; k++) st[k] = H0[k];
        compress();
        for (int k = 0; k < 8; k++) out[oo + k] = st[k];
    }
    // out[oo..] = SHA256(l[lo..lo+7] || r[ro..ro+7]); the output may alias an input (inputs are copied first)
    static void node(int[] out, int oo, int[] l, int lo, int[] r, int ro) {
        for (int k = 0; k < 8; k++) { blk[k] = l[lo + k]; blk[8 + k] = r[ro + k]; st[k] = H0[k]; }
        compress();
        for (int k = 0; k < 16; k++) blk[k] = 0;
        blk[0] = 0x80000000; blk[15] = 512;
        compress();
        for (int k = 0; k < 8; k++) out[oo + k] = st[k];
    }
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 140000;
        int[] data = new int[n];
        int[] t = new int[(2 * n + 64) * 8];
        for (int i = 0; i < n; i++) { data[i] = next(); leaf(t, 8 * i, data[i], 0, i); }
        int off = 0, size = n;
        while (size > 1) {
            int noff = off + size, ns = (size + 1) / 2;
            for (int j = 0; j < ns; j++) {
                int li = off + 2 * j, ri = (2 * j + 1 < size) ? li + 1 : li;
                node(t, 8 * (noff + j), t, 8 * li, t, 8 * ri);
            }
            off = noff; size = ns;
        }
        int[] root = new int[8];
        for (int k = 0; k < 8; k++) root[k] = t[8 * off + k];
        long verified = 0, rejected = 0;
        int[] sibs = new int[64 * 8], cur = new int[8];
        for (int p = 0; p < n / 2; p++) {
            int idx = (int) (Integer.toUnsignedLong(next()) % n);
            int o = 0, s = n, pos = idx, depth = 0;
            while (s > 1) {
                int sb = (pos % 2 == 0) ? pos + 1 : pos - 1;
                if (sb >= s) sb = pos;
                for (int k = 0; k < 8; k++) sibs[depth * 8 + k] = t[8 * (o + sb) + k];
                depth++;
                o += s; s = (s + 1) / 2; pos /= 2;
            }
            int dlo = data[idx], dhi = 0;
            if (p % 4 == 3) { dlo += 1; if (dlo == 0) dhi = 1; }
            leaf(cur, 0, dlo, dhi, idx);
            pos = idx;
            for (int d = 0; d < depth; d++) {
                if (pos % 2 == 0) node(cur, 0, cur, 0, sibs, d * 8); else node(cur, 0, sibs, d * 8, cur, 0);
                pos /= 2;
            }
            boolean eq = true;
            for (int k = 0; k < 8; k++) if (cur[k] != root[k]) eq = false;
            if (eq) verified++; else rejected++;
        }
        StringBuilder sbd = new StringBuilder();
        for (int k = 0; k < 8; k++) sbd.append(String.format("%08x", root[k]));
        System.out.println(sbd + " " + verified + " " + rejected);
    }
}

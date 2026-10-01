import sys

M = 0xFFFFFFFF
K = [
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]
H0 = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]

def ror(v: int, n: int) -> int:
    return ((v >> n) | (v << (32 - n))) & M

# one compression: st[8] updated in place with the 16-word big-endian block b; w is a 64-word scratch
def compress(st: List[int], b: List[int], w: List[int]):
    for i in range(16):
        w[i] = b[i]
    for i in range(16, 64):
        s0 = ror(w[i-15], 7) ^ ror(w[i-15], 18) ^ (w[i-15] >> 3)
        s1 = ror(w[i-2], 17) ^ ror(w[i-2], 19) ^ (w[i-2] >> 10)
        w[i] = (w[i-16] + s0 + w[i-7] + s1) & M
    a = st[0]; bb = st[1]; c = st[2]; d = st[3]; e = st[4]; f = st[5]; g = st[6]; h = st[7]
    for i in range(64):
        S1 = ror(e, 6) ^ ror(e, 11) ^ ror(e, 25)
        ch = (e & f) ^ ((~e & M) & g)
        t1 = (h + S1 + ch + K[i] + w[i]) & M
        S0 = ror(a, 2) ^ ror(a, 13) ^ ror(a, 22)
        mj = (a & bb) ^ (a & c) ^ (bb & c)
        t2 = (S0 + mj) & M
        h = g; g = f; f = e; e = (d + t1) & M; d = c; c = bb; bb = a; a = (t1 + t2) & M
    st[0] = (st[0] + a) & M; st[1] = (st[1] + bb) & M; st[2] = (st[2] + c) & M; st[3] = (st[3] + d) & M
    st[4] = (st[4] + e) & M; st[5] = (st[5] + f) & M; st[6] = (st[6] + g) & M; st[7] = (st[7] + h) & M

def bswap32(v: int) -> int:
    return ((v >> 24) | ((v >> 8) & 0xff00) | ((v << 8) & 0xff0000) | (v << 24)) & M

# out[oo..oo+8] = SHA256(le64(dlo + 2^32*dhi) || le64(i))
def leaf(out: List[int], oo: int, dlo: int, dhi: int, i: int, st: List[int], b: List[int], w: List[int]):
    for k in range(16):
        b[k] = 0
    b[0] = bswap32(dlo)
    b[1] = bswap32(dhi)
    b[2] = bswap32(i & M)
    b[3] = bswap32((i >> 32) & M)
    b[4] = 0x80000000
    b[15] = 128
    for k in range(8):
        st[k] = H0[k]
    compress(st, b, w)
    for k in range(8):
        out[oo + k] = st[k]

# out = SHA256(l[8 words as 32 bytes] || r[8 words as 32 bytes]); out may alias l or r
def node(out: List[int], oo: int, l: List[int], lo: int, r: List[int], ro: int, st: List[int], b: List[int], w: List[int]):
    for k in range(8):
        b[k] = l[lo + k]
        b[8 + k] = r[ro + k]
        st[k] = H0[k]
    compress(st, b, w)
    for k in range(16):
        b[k] = 0
    b[0] = 0x80000000
    b[15] = 512
    compress(st, b, w)
    for k in range(8):
        out[oo + k] = st[k]

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 140000
    x = 12345
    st = [0] * 8
    b = [0] * 16
    w = [0] * 64
    data = [0] * n
    t = [0] * ((2 * n + 64) * 8)
    for i in range(n):
        x = (x * 1664525 + 1013904223) & M
        data[i] = x
        leaf(t, 8 * i, data[i], 0, i, st, b, w)
    off = 0
    size = n
    while size > 1:
        noff = off + size
        ns = (size + 1) // 2
        for j in range(ns):
            lo = 8 * (off + 2 * j)
            ro = 8 * (off + 2 * j + 1) if 2 * j + 1 < size else lo
            node(t, 8 * (noff + j), t, lo, t, ro, st, b, w)
        off = noff
        size = ns
    rootoff = 8 * off
    verified = 0
    rejected = 0
    sibs = [0] * (64 * 8)
    cur = [0] * 8
    for p in range(n // 2):
        x = (x * 1664525 + 1013904223) & M
        idx = x % n
        o = 0
        s = n
        pos = idx
        depth = 0
        while s > 1:
            sb = pos + 1 if pos % 2 == 0 else pos - 1
            if sb >= s:
                sb = pos
            for k in range(8):
                sibs[depth * 8 + k] = t[8 * (o + sb) + k]
            depth += 1
            o += s
            s = (s + 1) // 2
            pos //= 2
        dlo = data[idx]
        dhi = 0
        if p % 4 == 3:
            dlo = (dlo + 1) & M
            dhi = 1 if dlo == 0 else 0
        leaf(cur, 0, dlo, dhi, idx, st, b, w)
        pos = idx
        for d in range(depth):
            if pos % 2 == 0:
                node(cur, 0, cur, 0, sibs, 8 * d, st, b, w)
            else:
                node(cur, 0, sibs, 8 * d, cur, 0, st, b, w)
            pos //= 2
        eq = True
        for k in range(8):
            if cur[k] != t[rootoff + k]:
                eq = False
        if eq:
            verified += 1
        else:
            rejected += 1
    hx = ""
    for k in range(8):
        hx += f"{t[rootoff + k]:08x}"
    print(hx, verified, rejected)

main()

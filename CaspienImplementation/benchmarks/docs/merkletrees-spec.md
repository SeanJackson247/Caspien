# Merkle tree benchmark specification (real SHA-256)

Every language does identical work: hand-written SHA-256 (FIPS 180-4), no crypto library. Reference sources are in `reference/`
(`merkletrees.{c,cpp,rs,go,js,lua}`, `MerkleTrees.java`); `reference/model.py` (hashlib) computes the expected line for any N;
`reference/sha256_selftest.c` checks the standard vectors. **Default N = 140000** (env `MERKLE_N`, `argv[1]` in the reference programs;
about 1 s for the C -O2 build). N >= 1.

## 1. Leaf data (LCG)
32-bit unsigned LCG, all arithmetic mod 2^32: `x = 12345` initially; `next(): x = x * 1664525 + 1013904223 (mod 2^32); return x`.
One single generator stream is shared by the whole program: first N calls produce the leaf data, the following N/2 calls (integer
division) choose the proof leaves.

    for i in 0..N-1: d[i] = next()          // d[i] < 2^32 (stored as u32)

## 2. Hash
SHA-256 exactly as FIPS 180-4 (constants in section 7). The digest is 8 big-endian u32 words H[0..7]; its 32 bytes are
H[0] bytes MSB first, then H[1], ... Nodes are stored as these 8 words.

* **Leaf**: `leaf(d, i) = SHA256(le64(d) || le64(i))`: a 16-byte message, `le64` = 8 bytes little-endian. As big-endian block words:
  `W0 = bswap32(d_lo)`, `W1 = bswap32(d_hi)`, `W2 = bswap32(i & 0xffffffff)`, `W3 = bswap32(i >> 32)`, `W4 = 0x80000000`,
  `W5..W14 = 0`, `W15 = 128` (bit length). One compression from H0. Here `d_lo = d mod 2^32`, `d_hi = d >> 32` (always 0 for
  untampered leaves, `bswap32` = byte-reverse of a 32-bit word).
* **Internal node**: `node(L, R) = SHA256(L || R)`, L and R the 32-byte child digests: a 64-byte message = two compressions.
  Block 1 words: `W0..W7 = L[0..7]`, `W8..W15 = R[0..7]` (the digest words as they are, no byte swap). Block 2 (padding only):
  `W0 = 0x80000000`, `W1..W14 = 0`, `W15 = 512`. The state after block 1 is the input state of block 2; the result is the
  state after block 2.

## 3. Tree (flat array, level by level)
Array `t` of node digests (8 u32 words each), capacity 2N + 64 nodes.

    t[i] = leaf(d[i], i)                     for i in 0..N-1
    off = 0; size = N
    while size > 1:
        noff = off + size; ns = (size + 1) / 2      // integer division
        for j in 0..ns-1:
            l = t[off + 2j]
            r = (2j + 1 < size) ? t[off + 2j + 1] : l   // odd last node is paired with itself
            t[noff + j] = node(l, r)
        off = noff; size = ns
    root = t[off]

For N = 1 the root is the leaf (no internal nodes) and there are 0 proofs.

## 4. Proofs
`for p in 0..N/2 - 1` (integer division):

1. `idx = next() % N` (next() as unsigned 32-bit, unsigned modulo).
2. Generate: `o = 0; s = N; pos = idx; depth = 0; while s > 1: sb = (pos even) ? pos + 1 : pos - 1; if sb >= s: sb = pos;
   sibs[depth++] = t[o + sb]; o += s; s = (s + 1) / 2; pos /= 2`  (depth is at most 64; sibs holds `depth` digests).
3. Verify: leaf data is `dd = d[idx]`, **tampered when `p % 4 == 3`: `dd = d[idx] + 1`** as a 64-bit value (so d = 0xFFFFFFFF becomes
   d_lo = 0, d_hi = 1; the index word is unchanged). `cur = leaf(dd, idx)`; `pos = idx`; for each level `k` in 0..depth-1:
   `cur = (pos even) ? node(cur, sibs[k]) : node(sibs[k], cur); pos /= 2`.
4. `cur == root` (all 8 words) counts as verified, otherwise rejected.

Untampered proofs always verify and tampered ones are always rejected, so with `m = N/2` proofs: `rejected = m / 4` (floor of
(m)/4, i.e. the number of p in 0..m-1 with p%4==3), `verified = m - rejected`.

## 5. Output
One line, newline-terminated: `<root as 64 lowercase hex chars> <verified> <rejected>`; the hex is H[0]..H[7] each as 8 hex digits
(`%08x`), words in order. Verified and rejected are decimal.

## 6. Known-answer outputs
Produced by `reference/model.py` (hashlib) and equal to all reference implementations (C, C++, Rust, Go, Node, LuaJIT, Java; free
and leak builds).

| N | expected line |
|---|---|
| 1 | `b17e634b25ddfc1b70e5bdfc87c579554345a74a9b4f9b6019de7d09e6931cff 0 0` |
| 2 | `383a7f9ad5a72f22d60865c43a84711db287f57bbbf3be9ec81caa9b77c18298 1 0` |
| 3 | `98817de929b37875de22fe82d42734bb419c4395523340c6876e73d74c20c95f 1 0` |
| 5 | `32498dd0a6ee6e4897e4326dac382ee2f5a68b64fbf044278cba323d3a3918fe 2 0` |
| 7 | `edab20bd7566bac7f603e0b7a4fd61d769be97e675d03ada96fab0bf9588a99b 3 0` |
| 100 | `b72e4400062a3aa77ee226a80c739689b2d5cdc59c767b36faa94e8fbace7044 38 12` |
| 1000 | `f3c7c520aaa0d51508a8ddbd24b644bdcfadbf6e53a430bb659b020bd38de91f 375 125` |
| 4097 | `12bfde24988c04d4f4d0ab5ef6acdf9245620989ac6951e2416ab62b0b3c0b4f 1536 512` |
| 140000 | `47269b99287448d70b425e0a1cbbccdb9b3f37cee7ec1ee4f20853c22167c744 52500 17500` |

N = 140000 is the default N. SHA-256 vectors for the primitive alone (`reference/sha256_selftest.c`): `""` ->
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`; `"abc"` ->
`ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`; the 56-byte string
`abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq` ->
`248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1`; one million `a` ->
`cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0`.

## 7. SHA-256 constants
H0 (initial state):

    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19

K[0..63] (round constants):

    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5
    0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7
    0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3
    0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2

Compression of a 16-word block `b` into state `st[8]` (all u32 arithmetic wraps mod 2^32; `ror` = rotate right):

    w[0..15] = b[0..15]
    for i in 16..63:
        s0 = ror(w[i-15],7) ^ ror(w[i-15],18) ^ (w[i-15] >> 3)
        s1 = ror(w[i-2],17) ^ ror(w[i-2],19)  ^ (w[i-2] >> 10)
        w[i] = w[i-16] + s0 + w[i-7] + s1
    (a,b,c,d,e,f,g,h) = st[0..7]
    for i in 0..63:
        S1 = ror(e,6) ^ ror(e,11) ^ ror(e,25);   ch = (e & f) ^ (~e & g)
        t1 = h + S1 + ch + K[i] + w[i]
        S0 = ror(a,2) ^ ror(a,13) ^ ror(a,22);   mj = (a & b) ^ (a & c) ^ (b & c)
        t2 = S0 + mj
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
    st[i] += (a..h)[i]   for i in 0..7

Note: `ch` can equivalently be written `(e & f) | (~e & g)` or `g ^ (e & (f ^ g))`, and `mj` as `(a & b) | (c & (a | b))`
(useful if the target language lacks some operator; `~e` is `e ^ 0xFFFFFFFF`).

## 8. Build notes
`-DLEAK` (C/C++) and `--cfg leak` (Rust) skip freeing the arrays; the default builds free. Only structural difference between C and
the others is the container (malloc / std::vector or new[] / Vec / slice / typed array / Uint32Array / ffi array).
Untested corner: the d = 0xFFFFFFFF tamper carry (d_hi = 1) never occurs for the default stream at the N values above; it is
implemented identically in every reference (and in model.py via Python's unbounded `struct.pack('<Q')`).

// Merkle tree benchmark (Node / Bun, real SHA-256): same algorithm as merkletrees.c. 32-bit words live in Uint32Arrays; every
// arithmetic result is truncated with |0 / >>>0 (additions of up to 5 words stay exact in doubles, so one truncation per sum is enough).
const n = process.argv.length > 2 ? parseInt(process.argv[2]) : 140000;
let x = 12345;
function next() { x = (Math.imul(x, 1664525) + 1013904223) >>> 0; return x; }
const K = new Uint32Array([
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]);
const H0 = new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);
const w = new Uint32Array(64);
function compress(st, b) {
  for (let i = 0; i < 16; i++) w[i] = b[i];
  for (let i = 16; i < 64; i++) {
    const a = w[i - 15], c = w[i - 2];
    const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
    const s1 = ((c >>> 17) | (c << 15)) ^ ((c >>> 19) | (c << 13)) ^ (c >>> 10);
    w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
  }
  let a = st[0], bb = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
  for (let i = 0; i < 64; i++) {
    const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
    const ch = (e & f) ^ (~e & g);
    const t1 = (h + S1 + ch + K[i] + w[i]) | 0;
    const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
    const mj = (a & bb) ^ (a & c) ^ (bb & c);
    const t2 = (S0 + mj) | 0;
    h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = bb; bb = a; a = (t1 + t2) | 0;
  }
  st[0] += a; st[1] += bb; st[2] += c; st[3] += d; st[4] += e; st[5] += f; st[6] += g; st[7] += h;
}
function bswap(v) { return (((v >>> 24) | ((v >>> 8) & 0xff00) | ((v << 8) & 0xff0000) | (v << 24)) >>> 0); }
const blk = new Uint32Array(16), st = new Uint32Array(8);
const PAD = new Uint32Array([0x80000000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 512]);
// out[oo..oo+8] = SHA256(le64(dlo + 2^32 dhi) || le64(i))
function leaf(out, oo, dlo, dhi, i) {
  blk.fill(0);
  blk[0] = bswap(dlo); blk[1] = bswap(dhi); blk[2] = bswap(i >>> 0); blk[3] = bswap(Math.floor(i / 4294967296)); blk[4] = 0x80000000; blk[15] = 128;
  for (let k = 0; k < 8; k++) st[k] = H0[k];
  compress(st, blk);
  for (let k = 0; k < 8; k++) out[oo + k] = st[k];
}
// out[oo..] = SHA256(l[lo..lo+8] || r[ro..ro+8]); the output may alias an input (inputs are copied first)
function node(out, oo, l, lo, r, ro) {
  for (let k = 0; k < 8; k++) { blk[k] = l[lo + k]; blk[8 + k] = r[ro + k]; st[k] = H0[k]; }
  compress(st, blk);
  compress(st, PAD);
  for (let k = 0; k < 8; k++) out[oo + k] = st[k];
}
const data = new Uint32Array(n);
const t = new Uint32Array((2 * n + 64) * 8);
for (let i = 0; i < n; i++) { data[i] = next(); leaf(t, 8 * i, data[i], 0, i); }
let off = 0, size = n;
while (size > 1) {
  const noff = off + size, ns = (size + 1) >> 1;
  for (let j = 0; j < ns; j++) {
    const li = off + 2 * j, ri = (2 * j + 1 < size) ? li + 1 : li;
    node(t, 8 * (noff + j), t, 8 * li, t, 8 * ri);
  }
  off = noff; size = ns;
}
const root = t.slice(8 * off, 8 * off + 8);
let verified = 0, rejected = 0;
const sibs = new Uint32Array(64 * 8), cur = new Uint32Array(8);
for (let p = 0; p < (n >> 1); p++) {
  const idx = next() % n;
  let o = 0, s = n, pos = idx, depth = 0;
  while (s > 1) {
    let sb = (pos % 2 === 0) ? pos + 1 : pos - 1;
    if (sb >= s) sb = pos;
    for (let k = 0; k < 8; k++) sibs[depth * 8 + k] = t[8 * (o + sb) + k];
    depth++;
    o += s; s = (s + 1) >> 1; pos = pos >> 1;
  }
  let dlo = data[idx], dhi = 0;
  if (p % 4 === 3) { dlo = (dlo + 1) >>> 0; if (dlo === 0) dhi = 1; }
  leaf(cur, 0, dlo, dhi, idx);
  pos = idx;
  for (let d = 0; d < depth; d++) {
    if (pos % 2 === 0) node(cur, 0, cur, 0, sibs, d * 8); else node(cur, 0, sibs, d * 8, cur, 0);
    pos = pos >> 1;
  }
  let eq = true;
  for (let k = 0; k < 8; k++) if (cur[k] !== root[k]) eq = false;
  if (eq) verified++; else rejected++;
}
let hex = "";
for (let k = 0; k < 8; k++) hex += root[k].toString(16).padStart(8, "0");
console.log(`${hex} ${verified} ${rejected}`);

// Merkle tree benchmark with a hand-written SHA-256, see merkletrees.c. Digests are 8 words in one flat array.
var x: uint(32) = 12345;
inline proc next(): uint(32) { x = x * 1664525: uint(32) + 1013904223: uint(32); return x; }
const K: [0..<64] uint(32) = [
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]: uint(32);
const H0: [0..<8] uint(32) = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]: uint(32);

inline proc ror(v: uint(32), n: int): uint(32) { return (v >> n) | (v << (32 - n)); }
inline proc bswap32(v: uint(32)): uint(32) {
  return (v >> 24) | ((v >> 8) & 0xff00: uint(32)) | ((v << 8) & 0xff0000: uint(32)) | (v << 24);
}

var w: [0..<64] uint(32);
var blk: [0..<16] uint(32);
var st: [0..<8] uint(32);

// one compression: st[0..7] updated in place with the 16-word big-endian block in blk
proc compress() {
  for i in 0..<16 do w[i] = blk[i];
  for i in 16..<64 {
    const s0 = ror(w[i-15], 7) ^ ror(w[i-15], 18) ^ (w[i-15] >> 3);
    const s1 = ror(w[i-2], 17) ^ ror(w[i-2], 19) ^ (w[i-2] >> 10);
    w[i] = w[i-16] + s0 + w[i-7] + s1;
  }
  var a = st[0], bb = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
  for i in 0..<64 {
    const S1 = ror(e, 6) ^ ror(e, 11) ^ ror(e, 25);
    const ch = (e & f) ^ (~e & g);
    const t1 = h + S1 + ch + K[i] + w[i];
    const S0 = ror(a, 2) ^ ror(a, 13) ^ ror(a, 22);
    const mj = (a & bb) ^ (a & c) ^ (bb & c);
    const t2 = S0 + mj;
    h = g; g = f; f = e; e = d + t1; d = c; c = bb; bb = a; a = t1 + t2;
  }
  st[0] += a; st[1] += bb; st[2] += c; st[3] += d; st[4] += e; st[5] += f; st[6] += g; st[7] += h;
}

// dst[oo..oo+7] = SHA256(le64(dlo + 2^32*dhi) || le64(i))
proc leaf(ref dst: [] uint(32), oo: int, dlo: uint(32), dhi: uint(32), i: uint(64)) {
  blk[0] = bswap32(dlo); blk[1] = bswap32(dhi);
  blk[2] = bswap32(i: uint(32)); blk[3] = bswap32((i >> 32): uint(32));
  blk[4] = 0x80000000: uint(32);
  for k in 5..<15 do blk[k] = 0;
  blk[15] = 128;
  for k in 0..<8 do st[k] = H0[k];
  compress();
  for k in 0..<8 do dst[oo + k] = st[k];
}
// dst[oo..] = SHA256(l || r); the inputs are copied first so dst may alias them
proc node(ref dst: [] uint(32), oo: int, const ref l: [] uint(32), lo: int, const ref r: [] uint(32), ro: int) {
  for k in 0..<8 { blk[k] = l[lo + k]; blk[8 + k] = r[ro + k]; st[k] = H0[k]; }
  compress();
  blk[0] = 0x80000000: uint(32);
  for k in 1..<15 do blk[k] = 0;
  blk[15] = 512;
  compress();
  for k in 0..<8 do dst[oo + k] = st[k];
}

proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 140000;
  var data: [0..<n] uint(32);
  var t: [0..<((2 * n + 64) * 8)] uint(32);
  for i in 0..<n { data[i] = next(); leaf(t, 8 * i, data[i], 0, i: uint(64)); }
  var off = 0, size = n;
  while size > 1 {
    const noff = off + size, ns = (size + 1) / 2;
    for j in 0..<ns {
      const lo = 8 * (off + 2 * j);
      const ro = if 2 * j + 1 < size then 8 * (off + 2 * j + 1) else lo;
      node(t, 8 * (noff + j), t, lo, t, ro);
    }
    off = noff; size = ns;
  }
  const rootOff = 8 * off;
  var verified: uint(64) = 0, rejected: uint(64) = 0;
  var sibs: [0..<(64 * 8)] uint(32);
  var cur: [0..<8] uint(32);
  for p in 0..<(n / 2) {
    const idx = (next(): int) % n;
    var o = 0, s = n, pos = idx, depth = 0;
    while s > 1 {
      var sb = if pos % 2 == 0 then pos + 1 else pos - 1;
      if sb >= s then sb = pos;
      for k in 0..<8 do sibs[depth * 8 + k] = t[8 * (o + sb) + k];
      depth += 1;
      o += s; s = (s + 1) / 2; pos /= 2;
    }
    var dlo = data[idx], dhi: uint(32) = 0;
    if p % 4 == 3 { dlo += 1; dhi = (dlo == 0): uint(32); }
    leaf(cur, 0, dlo, dhi, idx: uint(64));
    pos = idx;
    for d in 0..<depth {
      if pos % 2 == 0 then node(cur, 0, cur, 0, sibs, 8 * d);
      else node(cur, 0, sibs, 8 * d, cur, 0);
      pos /= 2;
    }
    var eq = true;
    for k in 0..<8 do if cur[k] != t[rootOff + k] then eq = false;
    if eq then verified += 1; else rejected += 1;
  }
  const hexd = "0123456789abcdef";
  var hex = "";
  for k in 0..<8 {
    const v = t[rootOff + k];
    for sh in 0..7 do hex += hexd[((v >> (28 - 4 * sh)) & 15): int];
  }
  write(hex);
  writeln(" ", verified, " ", rejected);
}

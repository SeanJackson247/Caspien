// Merkle tree benchmark with a hand-written SHA-256, see merkletrees.c. Digests are 8 words in one flat buffer.
import Glibc

var x: UInt32 = 12345
@inline(__always) func next() -> UInt32 { x = x &* 1664525 &+ 1013904223; return x }
let K: [UInt32] = [
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]
let H0: [UInt32] = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]

@inline(__always) func ror(_ v: UInt32, _ n: UInt32) -> UInt32 { return (v >> n) | (v << (32 - n)) }

typealias P = UnsafeMutablePointer<UInt32>
let w = P.allocate(capacity: 64)

// one compression: st[8] updated in place with the 16-word big-endian block b
func compress(_ st: P, _ b: P) {
    for i in 0..<16 { w[i] = b[i] }
    for i in 16..<64 {
        let s0 = ror(w[i-15], 7) ^ ror(w[i-15], 18) ^ (w[i-15] >> 3)
        let s1 = ror(w[i-2], 17) ^ ror(w[i-2], 19) ^ (w[i-2] >> 10)
        w[i] = w[i-16] &+ s0 &+ w[i-7] &+ s1
    }
    var a = st[0], bb = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7]
    for i in 0..<64 {
        let S1 = ror(e, 6) ^ ror(e, 11) ^ ror(e, 25)
        let ch = (e & f) ^ (~e & g)
        let t1 = h &+ S1 &+ ch &+ K[i] &+ w[i]
        let S0 = ror(a, 2) ^ ror(a, 13) ^ ror(a, 22)
        let mj = (a & bb) ^ (a & c) ^ (bb & c)
        let t2 = S0 &+ mj
        h = g; g = f; f = e; e = d &+ t1; d = c; c = bb; bb = a; a = t1 &+ t2
    }
    st[0] = st[0] &+ a; st[1] = st[1] &+ bb; st[2] = st[2] &+ c; st[3] = st[3] &+ d
    st[4] = st[4] &+ e; st[5] = st[5] &+ f; st[6] = st[6] &+ g; st[7] = st[7] &+ h
}
let blk = P.allocate(capacity: 16)
let st = P.allocate(capacity: 8)

// out[8] = SHA256(le64(dlo + 2^32*dhi) || le64(i))
func leaf(_ out: P, _ dlo: UInt32, _ dhi: UInt32, _ i: UInt64) {
    blk[0] = dlo.byteSwapped; blk[1] = dhi.byteSwapped
    blk[2] = UInt32(truncatingIfNeeded: i).byteSwapped; blk[3] = UInt32(truncatingIfNeeded: i >> 32).byteSwapped
    blk[4] = 0x80000000
    for k in 5..<15 { blk[k] = 0 }
    blk[15] = 128
    for k in 0..<8 { out[k] = H0[k] }
    compress(out, blk)
}
// out[8] = SHA256(l || r); out may alias l or r
func node(_ out: P, _ l: P, _ r: P) {
    for k in 0..<8 { blk[k] = l[k]; blk[8 + k] = r[k]; st[k] = H0[k] }
    compress(st, blk)
    blk[0] = 0x80000000
    for k in 1..<15 { blk[k] = 0 }
    blk[15] = 512
    compress(st, blk)
    for k in 0..<8 { out[k] = st[k] }
}

let n = CommandLine.arguments.count > 1 ? atol(CommandLine.arguments[1]) : 140000
var data = [UInt32](repeating: 0, count: n)
let t = P.allocate(capacity: (2 * n + 64) * 8)
for i in 0..<n { data[i] = next(); leaf(t + 8 * i, data[i], 0, UInt64(i)) }
var off = 0, size = n
while size > 1 {
    let noff = off + size, ns = (size + 1) / 2
    for j in 0..<ns {
        let l = t + 8 * (off + 2 * j)
        let r = (2 * j + 1 < size) ? t + 8 * (off + 2 * j + 1) : l
        node(t + 8 * (noff + j), l, r)
    }
    off = noff; size = ns
}
let root = t + 8 * off
var verified: UInt64 = 0, rejected: UInt64 = 0
let sibs = P.allocate(capacity: 64 * 8)
let cur = P.allocate(capacity: 8)
for p in 0..<(n / 2) {
    let idx = Int(next()) % n
    var o = 0, s = n, pos = idx, depth = 0
    while s > 1 {
        var sb = (pos % 2 == 0) ? pos + 1 : pos - 1
        if sb >= s { sb = pos }
        for k in 0..<8 { sibs[depth * 8 + k] = t[8 * (o + sb) + k] }
        depth += 1
        o += s; s = (s + 1) / 2; pos /= 2
    }
    var dlo = data[idx], dhi: UInt32 = 0
    if p % 4 == 3 { dlo = dlo &+ 1; dhi = dlo == 0 ? 1 : 0 }
    leaf(cur, dlo, dhi, UInt64(idx))
    pos = idx
    for d in 0..<depth {
        if pos % 2 == 0 { node(cur, cur, sibs + 8 * d) } else { node(cur, sibs + 8 * d, cur) }
        pos /= 2
    }
    var eq = true
    for k in 0..<8 where cur[k] != root[k] { eq = false }
    if eq { verified += 1 } else { rejected += 1 }
}
var hex = ""
for k in 0..<8 {
    let s = String(root[k], radix: 16)
    hex += String(repeating: "0", count: 8 - s.count) + s
}
print("\(hex) \(verified) \(rejected)")

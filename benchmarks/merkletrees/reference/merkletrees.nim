# Merkle tree benchmark (port of merkletrees.c): hand-written SHA-256, one flat seq of 8-word digests (digests are copied in/out as value arrays).
import std/[os, strutils]

var x: uint32 = 12345
proc next(): uint32 =
  x = x * 1664525'u32 + 1013904223'u32
  x

const K: array[64, uint32] = [
  0x428a2f98'u32,0x71374491'u32,0xb5c0fbcf'u32,0xe9b5dba5'u32,0x3956c25b'u32,0x59f111f1'u32,0x923f82a4'u32,0xab1c5ed5'u32,
  0xd807aa98'u32,0x12835b01'u32,0x243185be'u32,0x550c7dc3'u32,0x72be5d74'u32,0x80deb1fe'u32,0x9bdc06a7'u32,0xc19bf174'u32,
  0xe49b69c1'u32,0xefbe4786'u32,0x0fc19dc6'u32,0x240ca1cc'u32,0x2de92c6f'u32,0x4a7484aa'u32,0x5cb0a9dc'u32,0x76f988da'u32,
  0x983e5152'u32,0xa831c66d'u32,0xb00327c8'u32,0xbf597fc7'u32,0xc6e00bf3'u32,0xd5a79147'u32,0x06ca6351'u32,0x14292967'u32,
  0x27b70a85'u32,0x2e1b2138'u32,0x4d2c6dfc'u32,0x53380d13'u32,0x650a7354'u32,0x766a0abb'u32,0x81c2c92e'u32,0x92722c85'u32,
  0xa2bfe8a1'u32,0xa81a664b'u32,0xc24b8b70'u32,0xc76c51a3'u32,0xd192e819'u32,0xd6990624'u32,0xf40e3585'u32,0x106aa070'u32,
  0x19a4c116'u32,0x1e376c08'u32,0x2748774c'u32,0x34b0bcb5'u32,0x391c0cb3'u32,0x4ed8aa4a'u32,0x5b9cca4f'u32,0x682e6ff3'u32,
  0x748f82ee'u32,0x78a5636f'u32,0x84c87814'u32,0x8cc70208'u32,0x90befffa'u32,0xa4506ceb'u32,0xbef9a3f7'u32,0xc67178f2'u32]
const H0: array[8, uint32] = [0x6a09e667'u32,0xbb67ae85'u32,0x3c6ef372'u32,0xa54ff53a'u32,0x510e527f'u32,0x9b05688c'u32,0x1f83d9ab'u32,0x5be0cd19'u32]

type
  Digest = array[8, uint32]
  Block = array[16, uint32]

template ror(v: uint32, n: int): uint32 = (v shr n) or (v shl (32 - n))

# one compression: st updated in place with the 16-word big-endian block b
proc compress(st: var Digest, b: Block) =
  var w: array[64, uint32]
  for i in 0 ..< 16: w[i] = b[i]
  for i in 16 ..< 64:
    let s0 = ror(w[i-15], 7) xor ror(w[i-15], 18) xor (w[i-15] shr 3)
    let s1 = ror(w[i-2], 17) xor ror(w[i-2], 19) xor (w[i-2] shr 10)
    w[i] = w[i-16] + s0 + w[i-7] + s1
  var a = st[0]
  var bb = st[1]
  var c = st[2]
  var d = st[3]
  var e = st[4]
  var f = st[5]
  var g = st[6]
  var h = st[7]
  for i in 0 ..< 64:
    let S1 = ror(e, 6) xor ror(e, 11) xor ror(e, 25)
    let ch = (e and f) xor ((not e) and g)
    let t1 = h + S1 + ch + K[i] + w[i]
    let S0 = ror(a, 2) xor ror(a, 13) xor ror(a, 22)
    let mj = (a and bb) xor (a and c) xor (bb and c)
    let t2 = S0 + mj
    h = g; g = f; f = e; e = d + t1; d = c; c = bb; bb = a; a = t1 + t2
  st[0] += a; st[1] += bb; st[2] += c; st[3] += d; st[4] += e; st[5] += f; st[6] += g; st[7] += h

proc bswap32(v: uint32): uint32 =
  (v shr 24) or ((v shr 8) and 0xff00'u32) or ((v shl 8) and 0xff0000'u32) or (v shl 24)

# SHA256(le64(dlo + 2^32*dhi) || le64(i))
proc leaf(dlo, dhi: uint32, i: uint64): Digest =
  var b: Block = [bswap32(dlo), bswap32(dhi), bswap32(uint32(i and 0xFFFFFFFF'u64)), bswap32(uint32(i shr 32)), 0x80000000'u32, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 128]
  result = H0
  compress(result, b)

# SHA256(l || r)
proc node(l, r: Digest): Digest =
  var b: Block
  for k in 0 ..< 8:
    b[k] = l[k]
    b[8 + k] = r[k]
  result = H0
  compress(result, b)
  var p: Block = [0x80000000'u32, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 512]
  compress(result, p)

proc getD(t: seq[uint32], i: int): Digest =
  for k in 0 ..< 8: result[k] = t[8 * i + k]

proc putD(t: var seq[uint32], i: int, d: Digest) =
  for k in 0 ..< 8: t[8 * i + k] = d[k]

let n = if paramCount() > 0: parseInt(paramStr(1)) else: 140_000
var data = newSeq[uint32](n)
var t = newSeq[uint32]((2 * n + 64) * 8)
for i in 0 ..< n:
  data[i] = next()
  t.putD(i, leaf(data[i], 0, uint64(i)))
var off = 0
var size = n
while size > 1:
  let noff = off + size
  let ns = (size + 1) div 2
  for j in 0 ..< ns:
    let l = t.getD(off + 2 * j)
    let r = if 2 * j + 1 < size: t.getD(off + 2 * j + 1) else: l
    t.putD(noff + j, node(l, r))
  off = noff
  size = ns
let root = t.getD(off)
var verified, rejected = 0'u64
var sibs: array[64, Digest]
for p in 0 ..< n div 2:
  let idx = int(next() mod uint32(n))
  var o = 0
  var s = n
  var pos = idx
  var depth = 0
  while s > 1:
    var sb = if pos mod 2 == 0: pos + 1 else: pos - 1
    if sb >= s: sb = pos
    sibs[depth] = t.getD(o + sb)
    inc depth
    o += s
    s = (s + 1) div 2
    pos = pos div 2
  var dlo = data[idx]
  var dhi = 0'u32
  if p mod 4 == 3:
    dlo += 1
    dhi = if dlo == 0: 1'u32 else: 0'u32
  var cur = leaf(dlo, dhi, uint64(idx))
  pos = idx
  for d in 0 ..< depth:
    if pos mod 2 == 0: cur = node(cur, sibs[d]) else: cur = node(sibs[d], cur)
    pos = pos div 2
  if cur == root: inc verified else: inc rejected
for k in 0 ..< 8: stdout.write toHex(BiggestInt(root[k]), 8).toLowerAscii
echo " ", verified, " ", rejected

# Merkle tree with real SHA-256 (FIPS 180-4); see merkletrees.c.
K = StaticArray[
  0x428a2f98_u32, 0x71374491_u32, 0xb5c0fbcf_u32, 0xe9b5dba5_u32, 0x3956c25b_u32, 0x59f111f1_u32, 0x923f82a4_u32, 0xab1c5ed5_u32,
  0xd807aa98_u32, 0x12835b01_u32, 0x243185be_u32, 0x550c7dc3_u32, 0x72be5d74_u32, 0x80deb1fe_u32, 0x9bdc06a7_u32, 0xc19bf174_u32,
  0xe49b69c1_u32, 0xefbe4786_u32, 0x0fc19dc6_u32, 0x240ca1cc_u32, 0x2de92c6f_u32, 0x4a7484aa_u32, 0x5cb0a9dc_u32, 0x76f988da_u32,
  0x983e5152_u32, 0xa831c66d_u32, 0xb00327c8_u32, 0xbf597fc7_u32, 0xc6e00bf3_u32, 0xd5a79147_u32, 0x06ca6351_u32, 0x14292967_u32,
  0x27b70a85_u32, 0x2e1b2138_u32, 0x4d2c6dfc_u32, 0x53380d13_u32, 0x650a7354_u32, 0x766a0abb_u32, 0x81c2c92e_u32, 0x92722c85_u32,
  0xa2bfe8a1_u32, 0xa81a664b_u32, 0xc24b8b70_u32, 0xc76c51a3_u32, 0xd192e819_u32, 0xd6990624_u32, 0xf40e3585_u32, 0x106aa070_u32,
  0x19a4c116_u32, 0x1e376c08_u32, 0x2748774c_u32, 0x34b0bcb5_u32, 0x391c0cb3_u32, 0x4ed8aa4a_u32, 0x5b9cca4f_u32, 0x682e6ff3_u32,
  0x748f82ee_u32, 0x78a5636f_u32, 0x84c87814_u32, 0x8cc70208_u32, 0x90befffa_u32, 0xa4506ceb_u32, 0xbef9a3f7_u32, 0xc67178f2_u32,
]
H0 = StaticArray[0x6a09e667_u32, 0xbb67ae85_u32, 0x3c6ef372_u32, 0xa54ff53a_u32, 0x510e527f_u32, 0x9b05688c_u32, 0x1f83d9ab_u32, 0x5be0cd19_u32]

@[AlwaysInline]
def ror(v : UInt32, n : Int32) : UInt32
  (v >> n) | (v << (32 - n))
end

@[AlwaysInline]
def bswap32(v : UInt32) : UInt32
  (v >> 24) | ((v >> 8) & 0xff00_u32) | ((v << 8) & 0xff0000_u32) | (v << 24)
end

class Sha
  @st = StaticArray(UInt32, 8).new(0_u32)
  @b = StaticArray(UInt32, 16).new(0_u32)

  # one compression: @st updated in place with the 16-word block @b
  def compress
    w = uninitialized StaticArray(UInt32, 64)
    16.times { |i| w[i] = @b[i] }
    16.upto(63) do |i|
      s0 = ror(w[i - 15], 7) ^ ror(w[i - 15], 18) ^ (w[i - 15] >> 3)
      s1 = ror(w[i - 2], 17) ^ ror(w[i - 2], 19) ^ (w[i - 2] >> 10)
      w[i] = w[i - 16] &+ s0 &+ w[i - 7] &+ s1
    end
    a = @st[0]; bb = @st[1]; c = @st[2]; d = @st[3]; e = @st[4]; f = @st[5]; g = @st[6]; h = @st[7]
    64.times do |i|
      s1 = ror(e, 6) ^ ror(e, 11) ^ ror(e, 25)
      ch = (e & f) ^ (~e & g)
      t1 = h &+ s1 &+ ch &+ K[i] &+ w[i]
      s0 = ror(a, 2) ^ ror(a, 13) ^ ror(a, 22)
      mj = (a & bb) ^ (a & c) ^ (bb & c)
      t2 = s0 &+ mj
      h = g; g = f; f = e; e = d &+ t1; d = c; c = bb; bb = a; a = t1 &+ t2
    end
    @st[0] &+= a; @st[1] &+= bb; @st[2] &+= c; @st[3] &+= d
    @st[4] &+= e; @st[5] &+= f; @st[6] &+= g; @st[7] &+= h
  end

  # dst[oo, 8] = SHA256(le64(dlo + 2^32*dhi) || le64(i))
  def leaf(dst : Array(UInt32), oo : Int64, dlo : UInt32, dhi : UInt32, i : UInt64)
    @b[0] = bswap32(dlo); @b[1] = bswap32(dhi); @b[2] = bswap32(i.to_u32!); @b[3] = bswap32((i >> 32).to_u32!)
    @b[4] = 0x80000000_u32
    5.upto(14) { |j| @b[j] = 0_u32 }
    @b[15] = 128_u32
    8.times { |k| @st[k] = H0[k] }
    compress
    8.times { |k| dst[oo + k] = @st[k] }
  end

  # dst = SHA256(l || r); dst may alias l or r
  def node(dst : Array(UInt32), oo : Int64, l : Array(UInt32), lo : Int64, r : Array(UInt32), ro : Int64)
    8.times do |k|
      @b[k] = l[lo + k]
      @b[8 + k] = r[ro + k]
      @st[k] = H0[k]
    end
    compress
    @b[0] = 0x80000000_u32
    1.upto(14) { |j| @b[j] = 0_u32 }
    @b[15] = 512_u32
    compress
    8.times { |k| dst[oo + k] = @st[k] }
  end
end

module Rng
  @@x = 12345_u32

  def self.next : UInt32
    @@x = @@x &* 1664525_u32 &+ 1013904223_u32
    @@x
  end
end

n = (ARGV[0]? || "140000").to_i64
sha = Sha.new
data = Array(UInt32).new(n.to_i32, 0_u32)
t = Array(UInt32).new(((2 * n + 64) * 8).to_i32, 0_u32)
n.times do |i|
  data[i] = Rng.next
  sha.leaf(t, 8 * i, data[i], 0_u32, i.to_u64)
end
off = 0_i64
size = n
while size > 1
  noff = off + size
  ns = (size + 1) // 2
  ns.times do |j|
    lo = 8 * (off + 2 * j)
    ro = (2 * j + 1 < size) ? 8 * (off + 2 * j + 1) : lo
    sha.node(t, 8 * (noff + j), t, lo, t, ro)
  end
  off = noff
  size = ns
end
root = 8 * off
verified = 0_u64
rejected = 0_u64
sibs = Array(UInt32).new(64 * 8, 0_u32)
cur = Array(UInt32).new(8, 0_u32)
(n // 2).times do |p|
  idx = (Rng.next % n)
  o = 0_i64
  s = n
  pos = idx
  depth = 0_i64
  while s > 1
    sb = pos % 2 == 0 ? pos + 1 : pos - 1
    sb = pos if sb >= s
    8.times { |k| sibs[depth * 8 + k] = t[8 * (o + sb) + k] }
    depth += 1
    o += s
    s = (s + 1) // 2
    pos //= 2
  end
  dlo = data[idx]
  dhi = 0_u32
  if p % 4 == 3
    dlo &+= 1_u32
    dhi = dlo == 0 ? 1_u32 : 0_u32
  end
  sha.leaf(cur, 0_i64, dlo, dhi, idx.to_u64)
  pos = idx
  depth.times do |d|
    if pos % 2 == 0
      sha.node(cur, 0_i64, cur, 0_i64, sibs, 8 * d)
    else
      sha.node(cur, 0_i64, sibs, 8 * d, cur, 0_i64)
    end
    pos //= 2
  end
  eq = true
  8.times { |k| eq = false if cur[k] != t[root + k] }
  if eq
    verified += 1
  else
    rejected += 1
  end
end
8.times { |k| print t[root + k].to_s(16).rjust(8, '0') }
puts " #{verified} #{rejected}"

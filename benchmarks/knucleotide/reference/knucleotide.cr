# k-nucleotide: hand-written open-addressing table (linear probing, multiplicative hash); see knucleotide.c.
# The table is two parallel arrays (keys, counts) instead of an array of structs.
KS = [1, 2, 3, 4, 6, 12]
QK = [1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12]
QV = [0_u64, 1_u64, 2_u64, 3_u64, 10_u64, 11_u64, 0_u64, 43_u64, 172_u64, 2767_u64, 11337487_u64]
GOLDEN = 0x9E3779B97F4A7C15_u64

def lookup(keys : Array(UInt64), cnts : Array(UInt32), bits : Int32, cap : Int64, kk : UInt64) : UInt64
  h = ((kk &* GOLDEN) >> (64 - bits)).to_i64
  loop do
    k = keys[h]
    return 0_u64 if k == 0
    return cnts[h].to_u64 if k == kk
    h = (h + 1) & (cap - 1)
  end
end

n = (ARGV[0]? || "30000000").to_i64
seq = Bytes.new(n + 1)
last = 42_u32
n.times do |i|
  last = (last &* 3877_u32 &+ 29573_u32) % 139968_u32
  seq[i] = if last < 42404
             0_u8
           elsif last < 70117
             1_u8
           elsif last < 97767
             2_u8
           else
             3_u8
           end
end
first12 = 0_u64
{12_i64, n}.min.times { |i| first12 = (first12 << 2) | seq[i] }
distinct = Array(UInt64).new(6, 0_u64)
counts = Array(UInt64).new(12, 0_u64)
nq = 0
6.times do |ki|
  k = KS[ki]
  maxd = 1_i64 << (2 * k)
  win = n >= k ? n - k + 1 : 0_i64
  maxd = win if win < maxd
  maxd = 139968_i64 if maxd > 139968
  bits = 1
  while (1_i64 << bits) < 2 * maxd
    bits += 1
  end
  cap = 1_i64 << bits
  keys = Array(UInt64).new(cap.to_i32, 0_u64)
  cnts = Array(UInt32).new(cap.to_i32, 0_u32)
  mask = (1_u64 << (2 * k)) - 1
  key = 0_u64
  nd = 0_u64
  n.times do |i|
    key = ((key << 2) | seq[i]) & mask
    if i + 1 >= k
      kk = key + 1
      h = ((kk &* GOLDEN) >> (64 - bits)).to_i64
      loop do
        s = keys[h]
        if s == 0
          keys[h] = kk
          cnts[h] = 1_u32
          nd += 1
          break
        end
        if s == kk
          cnts[h] &+= 1_u32
          break
        end
        h = (h + 1) & (cap - 1)
      end
    end
  end
  distinct[ki] = nd
  11.times do |q|
    next if QK[q] != k
    counts[nq] = lookup(keys, cnts, bits, cap, QV[q] + 1)
    nq += 1
  end
  if k == 12
    counts[nq] = lookup(keys, cnts, bits, cap, first12 + 1)
    nq += 1
  end
end
6.times { |i| print distinct[i], " " }
12.times do |i|
  print " " if i > 0
  print counts[i]
end
puts

# String manipulation benchmark: see strings.c.
module Rng
  @@x = 12345_u32

  def self.next : UInt32
    @@x = @@x &* 1664525_u32 &+ 1013904223_u32
    @@x
  end
end

def hash(b : Bytes, n : Int64) : UInt32
  h = 7_u32
  i = 0_i64
  while i < n
    h = h &* 31_u32 &+ b[i]
    i += 1
  end
  h
end

n = (ARGV[0]? || "4000000").to_i64
text = Bytes.new(n)
n.times do |i|
  r = (Rng.next >> 16) % 27
  text[i] = r == 26 ? 32_u8 : (97_u8 + r.to_u8)
end
words = 0_i64
longest = 0_i64
cur = 0_i64
n.times do |i|
  if text[i] == 32_u8
    if cur > 0
      words += 1
      longest = cur if cur > longest
      cur = 0_i64
    end
  else
    cur += 1
  end
end
if cur > 0
  words += 1
  longest = cur if cur > longest
end
abc = 0_i64
i = 0_i64
while i + 2 < n
  abc += 1 if text[i] == 97_u8 && text[i + 1] == 98_u8 && text[i + 2] == 99_u8
  i += 1
end
rev = Bytes.new(n)
n.times { |k| rev[k] = text[n - 1 - k] }
hrev = hash(rev, n)
es = 0_i64
n.times { |k| es += 1 if text[k] == 101_u8 }
m = n + es
rep = Bytes.new(m)
k = 0_i64
n.times do |j|
  if text[j] == 101_u8
    rep[k] = 51_u8
    rep[k + 1] = 51_u8
    k += 2
  else
    rep[k] = text[j]
    k += 1
  end
end
hrep = hash(rep, m)
up = Bytes.new(n)
n.times { |j| up[j] = text[j] == 32_u8 ? 32_u8 : text[j] - 32_u8 }
hup = hash(up, n)
puts "#{words} #{longest} #{abc} #{hrev} #{m} #{hrep} #{hup}"

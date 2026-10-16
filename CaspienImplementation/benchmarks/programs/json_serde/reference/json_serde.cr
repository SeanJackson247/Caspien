# JSON serialise + parse benchmark: see json_serde.c.
module Rng
  @@x = 12345_u32

  def self.next : UInt32
    @@x = @@x &* 1664525_u32 &+ 1013904223_u32
    @@x
  end
end

struct Rec
  getter id : UInt64, name_off : Int64, name_len : Int64, score : UInt64, tag_off : Int64, tag_cnt : Int64

  def initialize(@id, @name_off, @name_len, @score, @tag_off, @tag_cnt)
  end
end

def wnum(t : Bytes, p : Int64, v : UInt64) : Int64
  d = 1_i64
  u = v
  while u >= 10
    u //= 10
    d += 1
  end
  (d - 1).downto(0) do |k|
    t[p + k] = (48_u8 + (v % 10).to_u8)
    v //= 10
  end
  p + d
end

def wlit(t : Bytes, p : Int64, s : String) : Int64
  s.to_slice.copy_to(t + p)
  p + s.bytesize
end

n = (ARGV[0]? || "4000000").to_i64
text = Bytes.new(n * 80 + 16)
p = 0_i64
text[p] = '['.ord.to_u8
p += 1
n.times do |i|
  if i > 0
    text[p] = ','.ord.to_u8
    p += 1
  end
  p = wlit(text, p, "{\"id\":")
  p = wnum(text, p, i.to_u64)
  p = wlit(text, p, ",\"name\":\"")
  nl = 3 + (Rng.next >> 16) % 8
  nl.times do
    text[p] = 97_u8 + ((Rng.next >> 16) % 26).to_u8
    p += 1
  end
  p = wlit(text, p, "\",\"score\":")
  cents = ((Rng.next >> 8) % 1000000).to_u64
  p = wnum(text, p, cents // 100)
  text[p] = '.'.ord.to_u8
  p += 1
  text[p] = 48_u8 + (cents % 100 // 10).to_u8
  p += 1
  text[p] = 48_u8 + (cents % 10).to_u8
  p += 1
  p = wlit(text, p, ",\"tags\":[")
  tc = (Rng.next >> 16) % 5
  tc.times do |k|
    if k > 0
      text[p] = ','.ord.to_u8
      p += 1
    end
    p = wnum(text, p, ((Rng.next >> 16) % 100).to_u64)
  end
  p = wlit(text, p, "]}")
end
text[p] = ']'.ord.to_u8
p += 1
tlen = p
# ---- parse ----
recs = Array(Rec).new(n.to_i32)
names = Bytes.new(n * 10 + 8)
tags = Bytes.new(n * 4 + 8)
noff = 0_i64
toff = 0_i64
q = 1_i64
while true
  c = text[q]
  break if c == ']'.ord
  if c == ','.ord
    q += 1
    next
  end
  q += 1 # '{'
  id = 0_u64
  name_off = 0_i64
  name_len = 0_i64
  score = 0_u64
  tag_off = 0_i64
  tag_cnt = 0_i64
  while true
    q += 1 # opening quote of the key
    k0 = text[q]
    while text[q] != '"'.ord
      q += 1
    end
    q += 2 # closing quote, ':'
    if k0 == 'i'.ord
      v = 0_u64
      while text[q] >= 48 && text[q] <= 57
        v = v * 10 + (text[q] - 48)
        q += 1
      end
      id = v
    elsif k0 == 'n'.ord
      q += 1
      name_off = noff
      while text[q] != '"'.ord
        names[noff] = text[q]
        noff += 1
        q += 1
      end
      name_len = noff - name_off
      q += 1
    elsif k0 == 's'.ord
      v = 0_u64
      while text[q] >= 48 && text[q] <= 57
        v = v * 10 + (text[q] - 48)
        q += 1
      end
      q += 1 # '.'
      v = v * 100 + (text[q] - 48) * 10 + (text[q + 1] - 48)
      q += 2
      score = v
    else
      q += 1 # '['
      tag_off = toff
      while text[q] != ']'.ord
        q += 1 if text[q] == ','.ord
        v = 0_u64
        while text[q] >= 48 && text[q] <= 57
          v = v * 10 + (text[q] - 48)
          q += 1
        end
        tags[toff] = v.to_u8!
        toff += 1
      end
      tag_cnt = toff - tag_off
      q += 1
    end
    if text[q] == ','.ord
      q += 1
    else
      q += 1
      break
    end
  end
  recs << Rec.new(id, name_off, name_len, score, tag_off, tag_cnt)
end
# ---- checksum over the parsed records ----
h = 7_u32
recs.each do |r|
  h = h &* 31_u32 &+ r.id.to_u32!
  h = h &* 31_u32 &+ r.score.to_u32!
  h = h &* 31_u32 &+ r.name_len.to_u32!
  r.name_len.times { |k| h = h &* 31_u32 &+ names[r.name_off + k] }
  h = h &* 31_u32 &+ r.tag_cnt.to_u32!
  r.tag_cnt.times { |k| h = h &* 31_u32 &+ tags[r.tag_off + k] }
end
puts "#{recs.size} #{tlen} #{h}"

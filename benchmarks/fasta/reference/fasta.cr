# FASTA benchmark into one heap buffer: see fasta.c.
ALU   = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA".to_slice
CHARS = "ACGTBDHKMNRSVWYACGT".to_slice
THR   = StaticArray[37792_u32, 54588_u32, 71384_u32, 109176_u32, 111975_u32, 114774_u32, 117574_u32, 120373_u32, 123172_u32, 125972_u32, 128771_u32, 131570_u32, 134370_u32, 137169_u32, 139968_u32, 42404_u32, 70117_u32, 97767_u32, 139968_u32]
HDR   = [">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n"]

n = (ARGV[0]? || "40000000").to_i64
cnt = StaticArray[n * 2 // 10, n * 3 // 10, 0_i64]
cnt[2] = n - cnt[0] - cnt[1]
off = StaticArray[0, 0, 15]
buf = Bytes.new(n + n // 60 + 1024)
last = 42_u32
pos = 0_i64
a = 0_i64; c = 0_i64; g = 0_i64; t = 0_i64; other = 0_i64
ai = 0
3.times do |s|
  hs = HDR[s].to_slice
  hs.copy_to(buf + pos)
  pos += hs.size
  col = 0
  cnt[s].times do
    ch = 0_u8
    if s == 0
      ch = ALU[ai]
      ai += 1
      ai = 0 if ai == 287
    else
      last = (last &* 3877_u32 &+ 29573_u32) % 139968_u32
      j = off[s]
      while last >= THR[j]
        j += 1
      end
      ch = CHARS[j]
    end
    buf[pos] = ch
    pos += 1
    case ch
    when 'A'.ord then a += 1
    when 'C'.ord then c += 1
    when 'G'.ord then g += 1
    when 'T'.ord then t += 1
    else                other += 1
    end
    col += 1
    if col == 60
      buf[pos] = 10_u8
      pos += 1
      col = 0
    end
  end
  if col > 0
    buf[pos] = 10_u8
    pos += 1
  end
end
h = 7_u32
pos.times { |i| h = h &* 31_u32 &+ buf[i] }
puts "#{pos} #{h} #{a} #{c} #{g} #{t} #{other}"

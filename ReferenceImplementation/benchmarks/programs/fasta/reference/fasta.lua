-- FASTA benchmark (LuaJIT, ffi byte buffer). Same algorithm and output as fasta.c.
local ffi = require("ffi")
local ALU = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA"
local CHARS = "ACGTBDHKMNRSVWYACGT"
local THR = {[0]=37792,54588,71384,109176,111975,114774,117574,120373,123172,125972,128771,131570,134370,137169,139968,42404,70117,97767,139968}
local HDR = {">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n"}
local n = tonumber(arg[1]) or 40000000
local cnt = {math.floor(n * 2 / 10), math.floor(n * 3 / 10), 0}
cnt[3] = n - cnt[1] - cnt[2]
local off = {0, 0, 15}
local last = 42
local buf = ffi.new("uint8_t[?]", n + math.floor(n / 60) + 1024)
local pos, ai, a, c, g, t, other = 0, 0, 0, 0, 0, 0, 0
for s = 1, 3 do
  local hd = HDR[s]
  for k = 1, #hd do buf[pos] = hd:byte(k); pos = pos + 1 end
  local col = 0
  local o = off[s]
  for i = 1, cnt[s] do
    local ch
    if s == 1 then
      ch = ALU:byte(ai + 1)
      ai = ai + 1
      if ai == 287 then ai = 0 end
    else
      last = (last * 3877 + 29573) % 139968
      local j = o
      while last >= THR[j] do j = j + 1 end
      ch = CHARS:byte(j + 1)
    end
    buf[pos] = ch; pos = pos + 1
    if ch == 65 then a = a + 1 elseif ch == 67 then c = c + 1 elseif ch == 71 then g = g + 1 elseif ch == 84 then t = t + 1 else other = other + 1 end
    col = col + 1
    if col == 60 then buf[pos] = 10; pos = pos + 1; col = 0 end
  end
  if col > 0 then buf[pos] = 10; pos = pos + 1 end
end
local h = 7
for i = 0, pos - 1 do h = (h * 31 + buf[i]) % 4294967296 end
print(string.format("%d %d %d %d %d %d %d", pos, h, a, c, g, t, other))

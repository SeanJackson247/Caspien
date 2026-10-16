-- k-nucleotide benchmark: same sequence, k-mers and output as knucleotide.c (see there), but the counting uses the language's own
-- hash map keyed by the packed 2-bit k-mer.
-- LuaJIT: a plain table with numeric keys (hash part), ffi byte buffer for the sequence.
local ffi = require("ffi")
local KS = {1, 2, 3, 4, 6, 12}
local QK = {1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12}
local QV = {0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487}
local n = tonumber(arg[1]) or 30000000
local last = 42
local seq = ffi.new("uint8_t[?]", n + 1)
for i = 0, n - 1 do
  last = (last * 3877 + 29573) % 139968
  if last < 42404 then seq[i] = 0 elseif last < 70117 then seq[i] = 1 elseif last < 97767 then seq[i] = 2 else seq[i] = 3 end
end
local first12 = 0
for i = 0, math.min(12, n) - 1 do first12 = first12 * 4 + seq[i] end
local distinct, counts = {}, {}
for ki = 1, 6 do
  local k = KS[ki]
  local m = {}
  local nd = 0
  local mod = 4 ^ k
  local key = 0
  for i = 0, n - 1 do
    key = (key * 4 + seq[i]) % mod
    if i + 1 >= k then
      local v = m[key]
      if v == nil then m[key] = 1; nd = nd + 1 else m[key] = v + 1 end
    end
  end
  distinct[ki] = nd
  for q = 1, 11 do
    if QK[q] == k then counts[#counts + 1] = m[QV[q]] or 0 end
  end
  if k == 12 then counts[#counts + 1] = m[first12] or 0 end
end
local parts = {}
for i = 1, 6 do parts[#parts + 1] = string.format("%d", distinct[i]) end
for i = 1, 12 do parts[#parts + 1] = string.format("%d", counts[i]) end
print(table.concat(parts, " "))

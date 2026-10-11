-- Merkle tree benchmark (LuaJIT, real SHA-256): same algorithm as merkletrees.c. 32-bit words are held in FFI int32_t arrays and
-- manipulated with the bit library (band/bxor/bnot/ror/rshift/tobit); values are the two's-complement view of the uint32 words.
local ffi = require("ffi")
local bit = require("bit")
local band, bxor, bnot, ror, rshift, tobit, bswap = bit.band, bit.bxor, bit.bnot, bit.ror, bit.rshift, bit.tobit, bit.bswap
local n = tonumber(arg[1]) or 140000
local x = 12345
local function nxtr() x = (x * 1664525 + 1013904223) % 4294967296; return x end
local Kt = {
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2}
local K = ffi.new("int32_t[64]")
for i = 0, 63 do K[i] = tobit(Kt[i + 1]) end
local Ht = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19}
local H0 = ffi.new("int32_t[8]")
for i = 0, 7 do H0[i] = tobit(Ht[i + 1]) end
local w = ffi.new("int32_t[64]")
local st = ffi.new("int32_t[8]")
local blk = ffi.new("int32_t[16]")
local function compress()
  for i = 0, 15 do w[i] = blk[i] end
  for i = 16, 63 do
    local a, c = w[i - 15], w[i - 2]
    local s0 = bxor(ror(a, 7), ror(a, 18), rshift(a, 3))
    local s1 = bxor(ror(c, 17), ror(c, 19), rshift(c, 10))
    w[i] = tobit(w[i - 16] + s0 + w[i - 7] + s1)
  end
  local a, b, c, d, e, f, g, h = st[0], st[1], st[2], st[3], st[4], st[5], st[6], st[7]
  for i = 0, 63 do
    local S1 = bxor(ror(e, 6), ror(e, 11), ror(e, 25))
    local ch = bxor(band(e, f), band(bnot(e), g))
    local t1 = tobit(h + S1 + ch + K[i] + w[i])
    local S0 = bxor(ror(a, 2), ror(a, 13), ror(a, 22))
    local mj = bxor(band(a, b), band(a, c), band(b, c))
    local t2 = tobit(S0 + mj)
    h = g; g = f; f = e; e = tobit(d + t1); d = c; c = b; b = a; a = tobit(t1 + t2)
  end
  st[0] = tobit(st[0] + a); st[1] = tobit(st[1] + b); st[2] = tobit(st[2] + c); st[3] = tobit(st[3] + d)
  st[4] = tobit(st[4] + e); st[5] = tobit(st[5] + f); st[6] = tobit(st[6] + g); st[7] = tobit(st[7] + h)
end
local PAD = ffi.new("int32_t[16]")
PAD[0] = tobit(0x80000000); PAD[15] = 512
-- out[oo..oo+7] = SHA256(le64(dlo + 2^32 dhi) || le64(i))
local function leaf(out, oo, dlo, dhi, i)
  for k = 0, 15 do blk[k] = 0 end
  blk[0] = bswap(dlo); blk[1] = bswap(dhi); blk[2] = bswap(i % 4294967296); blk[3] = bswap(math.floor(i / 4294967296))
  blk[4] = tobit(0x80000000); blk[15] = 128
  for k = 0, 7 do st[k] = H0[k] end
  compress()
  for k = 0, 7 do out[oo + k] = st[k] end
end
-- out[oo..] = SHA256(l[lo..lo+7] || r[ro..ro+7]); the output may alias an input (inputs are copied first)
local function node(out, oo, l, lo, r, ro)
  for k = 0, 7 do blk[k] = l[lo + k]; blk[8 + k] = r[ro + k]; st[k] = H0[k] end
  compress()
  for k = 0, 15 do blk[k] = PAD[k] end
  compress()
  for k = 0, 7 do out[oo + k] = st[k] end
end
local data = ffi.new("int32_t[?]", n)
local t = ffi.new("int32_t[?]", (2 * n + 64) * 8)
for i = 0, n - 1 do
  local d = nxtr()
  data[i] = tobit(d)
  leaf(t, 8 * i, data[i], 0, i)
end
local off, size = 0, n
while size > 1 do
  local noff, ns = off + size, math.floor((size + 1) / 2)
  for j = 0, ns - 1 do
    local li = off + 2 * j
    local ri = li
    if 2 * j + 1 < size then ri = li + 1 end
    node(t, 8 * (noff + j), t, 8 * li, t, 8 * ri)
  end
  off, size = noff, ns
end
local root = ffi.new("int32_t[8]")
for k = 0, 7 do root[k] = t[8 * off + k] end
local verified, rejected = 0, 0
local sibs = ffi.new("int32_t[?]", 64 * 8)
local cur = ffi.new("int32_t[8]")
for p = 0, math.floor(n / 2) - 1 do
  local idx = nxtr() % n
  local o, s, pos, depth = 0, n, idx, 0
  while s > 1 do
    local sb
    if pos % 2 == 0 then sb = pos + 1 else sb = pos - 1 end
    if sb >= s then sb = pos end
    for k = 0, 7 do sibs[depth * 8 + k] = t[8 * (o + sb) + k] end
    depth = depth + 1
    o = o + s; s = math.floor((s + 1) / 2); pos = math.floor(pos / 2)
  end
  local dlo, dhi = data[idx], 0
  if p % 4 == 3 then
    dlo = tobit(dlo + 1)
    if dlo == 0 then dhi = 1 end
  end
  leaf(cur, 0, dlo, dhi, idx)
  pos = idx
  for d = 0, depth - 1 do
    if pos % 2 == 0 then node(cur, 0, cur, 0, sibs, d * 8) else node(cur, 0, sibs, d * 8, cur, 0) end
    pos = math.floor(pos / 2)
  end
  local eq = true
  for k = 0, 7 do if cur[k] ~= root[k] then eq = false end end
  if eq then verified = verified + 1 else rejected = rejected + 1 end
end
local hex = {}
for k = 0, 7 do hex[#hex + 1] = bit.tohex(root[k]) end
print(table.concat(hex) .. " " .. verified .. " " .. rejected)

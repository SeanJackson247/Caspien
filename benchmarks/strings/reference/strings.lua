-- String manipulation benchmark (LuaJIT, ffi byte buffers)
local ffi = require("ffi")
local n = tonumber(arg[1]) or 4000000
local x = 12345
local function nxt() x = (x * 1664525 + 1013904223) % 4294967296; return x end
local function hash(b, len) local h = 7; for i = 0, len - 1 do h = (h * 31 + b[i]) % 4294967296 end; return h end
local text = ffi.new("uint8_t[?]", n)
for i = 0, n - 1 do local r = math.floor(nxt() / 65536) % 27; text[i] = (r == 26) and 32 or (97 + r) end
local words, longest, cur = 0, 0, 0
for i = 0, n - 1 do
  if text[i] == 32 then
    if cur > 0 then words = words + 1; if cur > longest then longest = cur end; cur = 0 end
  else cur = cur + 1 end
end
if cur > 0 then words = words + 1; if cur > longest then longest = cur end end
local abc = 0
for i = 0, n - 3 do if text[i] == 97 and text[i + 1] == 98 and text[i + 2] == 99 then abc = abc + 1 end end
local rev = ffi.new("uint8_t[?]", n)
for i = 0, n - 1 do rev[i] = text[n - 1 - i] end
local hrev = hash(rev, n)
local es = 0
for i = 0, n - 1 do if text[i] == 101 then es = es + 1 end end
local m = n + es
local rep = ffi.new("uint8_t[?]", m)
local k = 0
for i = 0, n - 1 do
  if text[i] == 101 then rep[k] = 51; rep[k + 1] = 51; k = k + 2 else rep[k] = text[i]; k = k + 1 end
end
local hrep = hash(rep, m)
local up = ffi.new("uint8_t[?]", n)
for i = 0, n - 1 do if text[i] == 32 then up[i] = 32 else up[i] = text[i] - 32 end end
local hup = hash(up, n)
print(string.format("%d %d %d %d %d %d %d", words, longest, abc, hrev, m, hrep, hup))

-- JSON serialise + parse benchmark (LuaJIT, ffi buffers). Same hand-written serialiser / iterative parser / checksum as json_serde.c.
-- Records are parallel ffi arrays (a struct array is also possible; parallel arrays keep every field a plain double).
local ffi = require("ffi")
local n = tonumber(arg[1]) or 4000000
local x = 12345
local function nxt() x = (x * 1664525 + 1013904223) % 4294967296; return x end
local text = ffi.new("uint8_t[?]", n * 80 + 16)
local floor = math.floor
local function wnum(p, v)
  local d, u = 1, v
  while u >= 10 do u = floor(u / 10); d = d + 1 end
  for k = d - 1, 0, -1 do text[p + k] = 48 + v % 10; v = floor(v / 10) end
  return p + d
end
local function wlit(p, s) for i = 1, #s do text[p] = s:byte(i); p = p + 1 end return p end
local p = 0
text[p] = 91; p = p + 1
for i = 0, n - 1 do
  if i > 0 then text[p] = 44; p = p + 1 end
  p = wlit(p, '{"id":'); p = wnum(p, i)
  p = wlit(p, ',"name":"')
  local nl = 3 + floor(nxt() / 65536) % 8
  for k = 1, nl do text[p] = 97 + floor(nxt() / 65536) % 26; p = p + 1 end
  p = wlit(p, '","score":')
  local cents = floor(nxt() / 256) % 1000000
  p = wnum(p, floor(cents / 100)); text[p] = 46; p = p + 1
  text[p] = 48 + floor((cents % 100) / 10); p = p + 1; text[p] = 48 + cents % 10; p = p + 1
  p = wlit(p, ',"tags":[')
  local tc = floor(nxt() / 65536) % 5
  for k = 0, tc - 1 do
    if k > 0 then text[p] = 44; p = p + 1 end
    p = wnum(p, floor(nxt() / 65536) % 100)
  end
  p = wlit(p, "]}")
end
text[p] = 93; p = p + 1
local tlen = p
local rid, rnoff, rnlen = ffi.new("double[?]", n + 1), ffi.new("double[?]", n + 1), ffi.new("double[?]", n + 1)
local rscore, rtoff, rtcnt = ffi.new("double[?]", n + 1), ffi.new("double[?]", n + 1), ffi.new("double[?]", n + 1)
local names, tags = ffi.new("uint8_t[?]", n * 10 + 8), ffi.new("uint8_t[?]", n * 4 + 8)
local count, noff, toff, q = 0, 0, 0, 1
while true do
  local c = text[q]
  if c == 93 then break end
  if c == 44 then q = q + 1
  else
    q = q + 1
    local id, nameOff, nameLen, score, tagOff, tagCnt = 0, 0, 0, 0, 0, 0
    while true do
      q = q + 1
      local k0 = text[q]
      while text[q] ~= 34 do q = q + 1 end
      q = q + 2
      if k0 == 105 then
        local v = 0
        while text[q] >= 48 and text[q] <= 57 do v = v * 10 + (text[q] - 48); q = q + 1 end
        id = v
      elseif k0 == 110 then
        q = q + 1
        nameOff = noff
        while text[q] ~= 34 do names[noff] = text[q]; noff = noff + 1; q = q + 1 end
        nameLen = noff - nameOff
        q = q + 1
      elseif k0 == 115 then
        local v = 0
        while text[q] >= 48 and text[q] <= 57 do v = v * 10 + (text[q] - 48); q = q + 1 end
        q = q + 1
        v = v * 100 + (text[q] - 48) * 10 + (text[q + 1] - 48); q = q + 2
        score = v
      else
        q = q + 1
        tagOff = toff
        while text[q] ~= 93 do
          if text[q] == 44 then q = q + 1 end
          local v = 0
          while text[q] >= 48 and text[q] <= 57 do v = v * 10 + (text[q] - 48); q = q + 1 end
          tags[toff] = v; toff = toff + 1
        end
        tagCnt = toff - tagOff
        q = q + 1
      end
      if text[q] == 44 then q = q + 1 else q = q + 1; break end
    end
    rid[count] = id; rnoff[count] = nameOff; rnlen[count] = nameLen; rscore[count] = score; rtoff[count] = tagOff; rtcnt[count] = tagCnt
    count = count + 1
  end
end
local h = 7
for i = 0, count - 1 do
  h = (h * 31 + rid[i] % 4294967296) % 4294967296
  h = (h * 31 + rscore[i] % 4294967296) % 4294967296
  h = (h * 31 + rnlen[i]) % 4294967296
  for k = 0, rnlen[i] - 1 do h = (h * 31 + names[rnoff[i] + k]) % 4294967296 end
  h = (h * 31 + rtcnt[i]) % 4294967296
  for k = 0, rtcnt[i] - 1 do h = (h * 31 + tags[rtoff[i] + k]) % 4294967296 end
end
print(string.format("%d %d %d", count, tlen, h))

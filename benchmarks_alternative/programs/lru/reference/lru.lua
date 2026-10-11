-- LRU cache benchmark (LuaJIT): a table for the hash map (key -> node index) plus an index-based doubly linked recency list in arrays.
local n = tonumber(arg[1]) or 20000000
local CAP, KEYS, HOT = 262144, 1048576, 131072
local x = 12345
local function nxtr() x = (x * 1664525 + 1013904223) % 4294967296; return x end
local map = {}
local key, val, prv, nxt = {}, {}, {}, {}
local head, tail, size = 0, 0, 0   -- node indices are 1-based, 0 = none
local hits, misses, sum = 0, 0, 0
local floor = math.floor
for op = 1, n do
  local a = nxtr(); local y = nxtr()
  local range = (floor(y / 1048576) % 4 == 0) and KEYS or HOT
  local k = floor(a / 256) % range
  local i = map[k]
  if floor(y / 16777216) % 4 ~= 0 then
    if i then
      hits = hits + 1; sum = (sum + val[i] + k) % 4294967296
      if head ~= i then   -- move to front
        local p, q = prv[i], nxt[i]
        nxt[p] = q
        if q ~= 0 then prv[q] = p else tail = p end
        prv[i] = 0; nxt[i] = head; prv[head] = i; head = i
      end
    else misses = misses + 1 end
  elseif i then
    val[i] = y
    if head ~= i then
      local p, q = prv[i], nxt[i]
      nxt[p] = q
      if q ~= 0 then prv[q] = p else tail = p end
      prv[i] = 0; nxt[i] = head; prv[head] = i; head = i
    end
  else
    if size == CAP then
      i = tail; sum = (sum + key[i]) % 4294967296
      map[key[i]] = nil
      local p = prv[i]
      if p ~= 0 then nxt[p] = 0; tail = p else head = 0; tail = 0 end
    else size = size + 1; i = size end
    key[i] = k; val[i] = y; map[k] = i
    prv[i] = 0; nxt[i] = head
    if head ~= 0 then prv[head] = i else tail = i end
    head = i
  end
end
local fin = 0
local i = head
while i ~= 0 do fin = (fin * 31 + key[i]) % 4294967296; i = nxt[i] end
print(string.format("%d %d %d", hits, misses, (sum + fin) % 4294967296))

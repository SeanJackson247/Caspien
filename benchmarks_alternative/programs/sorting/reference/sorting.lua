-- Sorting and searching benchmark (LuaJIT): same algorithms as sorting.c, on plain tables (1-based internally).
local x = 12345
local function next() x = (x * 1664525 + 1013904223) % 4294967296; return x end

local function quicksort(a, n)
  local stack, sp = {}, 0
  stack[sp + 1] = 1; stack[sp + 2] = n; sp = sp + 2
  while sp > 0 do
    local hi = stack[sp]; local lo = stack[sp - 1]; sp = sp - 2
    while lo < hi do
      local pivot = a[math.floor((lo + hi) / 2)]
      local i, j = lo, hi
      while i <= j do
        while a[i] < pivot do i = i + 1 end
        while a[j] > pivot do j = j - 1 end
        if i <= j then a[i], a[j] = a[j], a[i]; i = i + 1; j = j - 1 end
      end
      if j - lo < hi - i then stack[sp + 1] = i; stack[sp + 2] = hi; sp = sp + 2; hi = j
      else stack[sp + 1] = lo; stack[sp + 2] = j; sp = sp + 2; lo = i end
    end
  end
end
local function mergesort(a, tmp, n)
  local src, dst = a, tmp
  local w = 1
  while w < n do
    local lo = 1
    while lo <= n do
      local mid = math.min(lo + w, n + 1)
      local hi = math.min(lo + 2 * w, n + 1)
      local i, j, k = lo, mid, lo
      while i < mid and j < hi do
        if src[i] <= src[j] then dst[k] = src[i]; i = i + 1 else dst[k] = src[j]; j = j + 1 end
        k = k + 1
      end
      while i < mid do dst[k] = src[i]; i = i + 1; k = k + 1 end
      while j < hi do dst[k] = src[j]; j = j + 1; k = k + 1 end
      lo = lo + 2 * w
    end
    src, dst = dst, src
    w = w * 2
  end
  if src ~= a then for i = 1, n do a[i] = src[i] end end
end
local function siftdown(a, root, n)
  while true do
    local c = 2 * root
    if c > n then break end
    if c + 1 <= n and a[c + 1] > a[c] then c = c + 1 end
    if a[root] >= a[c] then break end
    a[root], a[c] = a[c], a[root]
    root = c
  end
end
local function heapsort(a, n)
  for i = math.floor(n / 2), 1, -1 do siftdown(a, i, n) end
  for e = n, 2, -1 do a[1], a[e] = a[e], a[1]; siftdown(a, 1, e - 1) end
end
local function bsearch_has(a, n, key)
  local lo, hi = 1, n + 1
  while lo < hi do
    local m = math.floor((lo + hi) / 2)
    if a[m] < key then lo = m + 1 else hi = m end
  end
  return lo <= n and a[lo] == key
end
local n = tonumber(arg[1]) or 2000000
local orig, a, b, d, tmp = {}, {}, {}, {}, {}
for i = 1, n do orig[i] = math.floor(next() / 2) end
for i = 1, n do a[i] = orig[i]; b[i] = orig[i]; d[i] = orig[i]; tmp[i] = 0 end
quicksort(a, n)
mergesort(b, tmp, n)
heapsort(d, n)
local h, ok = 0, true
for i = 1, n do
  h = (h * 31 + a[i]) % 4294967296
  if a[i] ~= b[i] or a[i] ~= d[i] then ok = false end
  if i > 1 and a[i - 1] > a[i] then ok = false end
end
local bs = 0
for k = 0, n - 1 do
  local key = math.floor(next() / 2)
  if k % 2 == 0 then key = orig[key % n + 1] end
  if bsearch_has(a, n, key) then bs = bs + 1 end
end
local ls = 0
for k = 0, 19 do
  local key = math.floor(next() / 2)
  if k % 2 == 0 then key = orig[key % n + 1] end
  for i = 1, n do if orig[i] == key then ls = ls + 1; break end end
end
print(string.format("%d %d %d %s", h, bs, ls, ok and "OK" or "BAD"))

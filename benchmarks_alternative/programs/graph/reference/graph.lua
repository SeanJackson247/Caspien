-- Heap graph benchmark (LuaJIT): one table per node, garbage collected.
local n = tonumber(arg[1]) or 2000000
local x = 12345
local function nxt() x = (x * 1664525 + 1013904223) % 4294967296; return x end
local nodes = {}
for i = 1, n do nodes[i] = { value = nxt() % 16777216, dist = -1 } end
nodes[math.floor(n * 7 / 10) + 1].value = 4294967295
for i = 1, n do
  local u = nodes[i]
  u.e0 = nodes[i % n + 1]
  u.e1 = nodes[nxt() % n + 1]
  u.e2 = nodes[nxt() % n + 1]
  u.e3 = nodes[nxt() % n + 1]
end
local q = {}
local head, tail = 1, 1
q[1] = nodes[1]; nodes[1].dist = 0; tail = 2
local maxdepth, needledist, sum = 0, -1, 0
while head < tail do
  local u = q[head]; head = head + 1
  sum = (sum + u.value) % 4294967296
  if u.value == 4294967295 then needledist = u.dist end
  if u.dist > maxdepth then maxdepth = u.dist end
  local v = u.e0; if v.dist < 0 then v.dist = u.dist + 1; q[tail] = v; tail = tail + 1 end
  v = u.e1; if v.dist < 0 then v.dist = u.dist + 1; q[tail] = v; tail = tail + 1 end
  v = u.e2; if v.dist < 0 then v.dist = u.dist + 1; q[tail] = v; tail = tail + 1 end
  v = u.e3; if v.dist < 0 then v.dist = u.dist + 1; q[tail] = v; tail = tail + 1 end
end
print(string.format("%d %d %d %d", tail - 1, maxdepth, needledist, sum))

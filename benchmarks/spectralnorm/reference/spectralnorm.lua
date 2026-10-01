-- spectral-norm, single threaded; same algorithm as spectralnorm.c (arrays are 1-based here). Runs unchanged on Lua 5.x and LuaJIT.
local sqrt = math.sqrt
local function evalA(i, j)
  local ij = i + j
  return 1.0 / (ij * (ij + 1) / 2 + i + 1)       -- ij*(ij+1) is always even, so the division is exact
end
local function aTimesU(n, u, au)
  for i = 0, n - 1 do
    local s = 0.0
    for j = 0, n - 1 do s = s + evalA(i, j) * u[j + 1] end
    au[i + 1] = s
  end
end
local function atTimesU(n, u, au)
  for i = 0, n - 1 do
    local s = 0.0
    for j = 0, n - 1 do s = s + evalA(j, i) * u[j + 1] end
    au[i + 1] = s
  end
end
local function ataTimesU(n, u, atau, v)
  aTimesU(n, u, v)
  atTimesU(n, v, atau)
end

local n = tonumber(arg and arg[1]) or 100
local u, v, w = {}, {}, {}
for i = 1, n do u[i] = 1.0; v[i] = 0.0; w[i] = 0.0 end
for _ = 1, 10 do ataTimesU(n, u, v, w); ataTimesU(n, v, u, w) end
local vBv, vv = 0.0, 0.0
for i = 1, n do vBv = vBv + u[i] * v[i]; vv = vv + v[i] * v[i] end
io.write(string.format("%0.9f", sqrt(vBv / vv)), "\n")

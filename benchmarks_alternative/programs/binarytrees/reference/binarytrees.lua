-- Binary trees (LuaJIT): ordinary tables with l / r fields (garbage collected).
local maxd = tonumber(arg[1]) or 16
if maxd < 6 then maxd = 6 end
local function make(d)
  if d > 0 then return { l = make(d - 1), r = make(d - 1) } end
  return { l = false, r = false }
end
local function check(n)
  if not n.l then return 1 end
  return 1 + check(n.l) + check(n.r)
end
local out = { string.format("%d", check(make(maxd + 1))) }
local longlived = make(maxd)
for d = 4, maxd, 2 do
  local iters = 2 ^ (maxd - d + 4)
  local sum = 0
  for i = 1, iters do sum = sum + check(make(d)) end
  out[#out + 1] = string.format("%d", sum)
end
out[#out + 1] = string.format("%d", check(longlived))
print(table.concat(out, " "))

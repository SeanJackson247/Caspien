local depth = tonumber(arg[1])
local function visit(d)
  local t = d + 1
  if d > 0 then t = t + visit(d - 1); t = t + visit(d - 1) end
  return t
end
print(string.format("%d", visit(depth)))

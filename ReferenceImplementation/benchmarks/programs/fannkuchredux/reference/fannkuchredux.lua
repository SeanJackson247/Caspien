-- fannkuch-redux, single threaded; same algorithm as fannkuchredux.c (arrays are 1-based here: element i of the C program is [i+1]).
-- Runs unchanged on Lua 5.x and LuaJIT.
local floor = math.floor
local function fannkuchredux(n)
  local perm, perm1, count = {}, {}, {}
  local maxFlips, permCount, checksum = 0, 0, 0
  for i = 0, n - 1 do perm1[i + 1] = i; perm[i + 1] = 0; count[i + 1] = 0 end
  local r = n
  while true do
    while r ~= 1 do count[r] = r; r = r - 1 end
    for i = 1, n do perm[i] = perm1[i] end
    local flips = 0
    local k = perm[1]
    while k ~= 0 do
      local k2 = floor((k + 1) / 2)
      for i = 0, k2 - 1 do
        local t = perm[i + 1]
        perm[i + 1] = perm[k - i + 1]
        perm[k - i + 1] = t
      end
      flips = flips + 1
      k = perm[1]
    end
    if flips > maxFlips then maxFlips = flips end
    if permCount % 2 == 0 then checksum = checksum + flips else checksum = checksum - flips end
    while true do
      if r == n then
        io.write(string.format("%d", checksum), "\n")
        return maxFlips
      end
      local perm0 = perm1[1]
      local i = 0
      while i < r do
        local j = i + 1
        perm1[i + 1] = perm1[j + 1]
        i = j
      end
      perm1[r + 1] = perm0
      count[r + 1] = count[r + 1] - 1
      if count[r + 1] > 0 then break end
      r = r + 1
    end
    permCount = permCount + 1
  end
end

local n = tonumber(arg and arg[1]) or 7
local flips = fannkuchredux(n)
io.write(string.format("Pfannkuchen(%d) = %d", n, flips), "\n")

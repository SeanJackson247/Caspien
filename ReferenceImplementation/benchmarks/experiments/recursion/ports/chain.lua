local ffi = require("ffi")
local bit = require("bit")
local bxor = bit.bxor
local depth, reps, lp = tonumber(arg[1]), tonumber(arg[2]), arg[3] == "loop"
local U = ffi.typeof("uint64_t")
local function chain(acc, i, n)
  if i >= n then return acc end
  return chain(bxor(acc * 31, i), i + 1, n)
end
local function chainLoop(acc, i, n)
  while i < n do acc = bxor(acc * 31, i); i = i + 1 end
  return acc
end
local f = lp and chainLoop or chain
local total = U(0)
for r = 0, reps - 1 do total = bxor(total, f(U(r), 0, depth)) end
print((tostring(total):gsub("ULL$", "")))

-- Sieve of Eratosthenes (LuaJIT; a table of numbers, 1 per entry)
local n = tonumber(arg[1]) or 100000000
local ffi = require("ffi")
local f = ffi.new("uint8_t[?]", n + 1)
ffi.fill(f, n + 1, 1)
local i = 2
while i * i <= n do
  if f[i] ~= 0 then
    for j = i * i, n, i do f[j] = 0 end
  end
  i = i + 1
end
local count = 0
for k = 2, n do count = count + f[k] end
print(string.format("%d", count))

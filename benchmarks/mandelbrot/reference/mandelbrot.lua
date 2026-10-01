-- Mandelbrot escape-time benchmark (LuaJIT): same grid, maths and output as mandelbrot.c.
local n = tonumber(arg[1]) or 3000
local dn = n
local inside, total = 0, 0
for y = 0, n - 1 do
  local ci = 2.0 * y / dn - 1.0
  for x = 0, n - 1 do
    local cr = 2.0 * x / dn - 1.5
    local zr, zi, tr, ti, i = 0.0, 0.0, 0.0, 0.0, 0
    while i < 100 and tr + ti <= 4.0 do
      zi = 2.0 * zr * zi + ci
      zr = tr - ti + cr
      tr = zr * zr
      ti = zi * zi
      i = i + 1
    end
    total = total + i
    if i == 100 then inside = inside + 1 end
  end
end
print(string.format("%d %d", inside, total))

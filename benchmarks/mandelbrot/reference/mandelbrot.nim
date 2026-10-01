# Mandelbrot escape-time benchmark (port of mandelbrot.c).
import std/[os, strutils]

let n = if paramCount() > 0: parseInt(paramStr(1)) else: 3000
let dn = float(n)
var inside, total = 0
for y in 0 ..< n:
  let ci = 2.0 * float(y) / dn - 1.0
  for x in 0 ..< n:
    let cr = 2.0 * float(x) / dn - 1.5
    var zr, zi, tr, ti = 0.0
    var i = 0
    while i < 100 and tr + ti <= 4.0:
      zi = 2.0 * zr * zi + ci
      zr = tr - ti + cr
      tr = zr * zr
      ti = zi * zi
      inc i
    total += i
    if i == 100: inc inside
echo inside, " ", total

# spectral-norm (port of spectralnorm.c), single threaded.
import std/[os, strutils, math]

proc evalA(i, j: int): float64 = 1.0 / float64((i + j) * (i + j + 1) div 2 + i + 1)

proc evalAtimesU(N: int, u: seq[float64], Au: var seq[float64]) =
  for i in 0 ..< N:
    Au[i] = 0
    for j in 0 ..< N: Au[i] += evalA(i, j) * u[j]

proc evalAtTimesU(N: int, u: seq[float64], Au: var seq[float64]) =
  for i in 0 ..< N:
    Au[i] = 0
    for j in 0 ..< N: Au[i] += evalA(j, i) * u[j]

proc evalAtAtimesU(N: int, u: seq[float64], AtAu: var seq[float64]) =
  var v = newSeq[float64](N)
  evalAtimesU(N, u, v)
  evalAtTimesU(N, v, AtAu)

let N = if paramCount() > 0: parseInt(paramStr(1)) else: 100
var u = newSeq[float64](N)
var v = newSeq[float64](N)
for i in 0 ..< N: u[i] = 1
for i in 0 ..< 10:
  evalAtAtimesU(N, u, v)
  evalAtAtimesU(N, v, u)
var vBv, vv = 0.0
for i in 0 ..< N:
  vBv += u[i] * v[i]
  vv += v[i] * v[i]
echo formatFloat(sqrt(vBv / vv), ffDecimal, 9)

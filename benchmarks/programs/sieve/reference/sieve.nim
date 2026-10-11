# Sieve of Eratosthenes: count the primes <= N (byte per number).
import std/[os, strutils]

let n = if paramCount() > 0: parseInt(paramStr(1)) else: 100_000_000
var f = newSeq[byte](n + 1)
for i in 0 .. n: f[i] = 1
var i = 2
while i * i <= n:
  if f[i] != 0:
    var j = i * i
    while j <= n:
      f[j] = 0
      j += i
  inc i
var count = 0
for i in 2 .. n: count += int(f[i])
echo count

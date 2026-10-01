# fannkuch-redux (port of fannkuchredux.c), single threaded.
import std/[os, strutils]

proc fannkuchredux(n: int): int =
  var perm, perm1, count: array[16, int]
  var maxFlipsCount = 0
  var permCount = 0
  var checksum = 0
  var r = n
  for i in 0 ..< n: perm1[i] = i
  while true:
    while r != 1:
      count[r - 1] = r
      dec r
    for i in 0 ..< n: perm[i] = perm1[i]
    var flipsCount = 0
    var k = perm[0]
    while k != 0:
      let k2 = (k + 1) shr 1
      for i in 0 ..< k2:
        let t = perm[i]
        perm[i] = perm[k - i]
        perm[k - i] = t
      inc flipsCount
      k = perm[0]
    if flipsCount > maxFlipsCount: maxFlipsCount = flipsCount
    checksum += (if permCount mod 2 == 0: flipsCount else: -flipsCount)
    while true:
      if r == n:
        echo checksum
        return maxFlipsCount
      let perm0 = perm1[0]
      var i = 0
      while i < r:
        let j = i + 1
        perm1[i] = perm1[j]
        i = j
      perm1[r] = perm0
      dec count[r]
      if count[r] > 0: break
      inc r
    inc permCount

let n = if paramCount() > 0: parseInt(paramStr(1)) else: 7
let res = fannkuchredux(n)
echo "Pfannkuchen(", n, ") = ", res

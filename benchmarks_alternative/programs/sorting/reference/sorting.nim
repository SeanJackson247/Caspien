# Sorting and searching benchmark (port of sorting.c): hand-written quicksort / merge sort / heap sort on int32 seqs.
import std/[os, strutils]

var x: uint32 = 12345
proc next(): uint32 =
  x = x * 1664525'u32 + 1013904223'u32
  x

proc quicksort(a: var seq[int32], n: int) =
  var stack: array[128, int]
  var sp = 0
  stack[sp] = 0; inc sp
  stack[sp] = n - 1; inc sp
  while sp > 0:
    dec sp; var hi = stack[sp]
    dec sp; var lo = stack[sp]
    while lo < hi:
      let pivot = a[(lo + hi) div 2]
      var i = lo
      var j = hi
      while i <= j:
        while a[i] < pivot: inc i
        while a[j] > pivot: dec j
        if i <= j:
          let t = a[i]; a[i] = a[j]; a[j] = t
          inc i; dec j
      if j - lo < hi - i:
        stack[sp] = i; inc sp
        stack[sp] = hi; inc sp
        hi = j
      else:
        stack[sp] = lo; inc sp
        stack[sp] = j; inc sp
        lo = i

proc mergePass(src: seq[int32], dst: var seq[int32], n, w: int) =
  var lo = 0
  while lo < n:
    let mid = if lo + w < n: lo + w else: n
    let hi = if lo + 2 * w < n: lo + 2 * w else: n
    var i = lo
    var j = mid
    var k = lo
    while i < mid and j < hi:
      if src[i] <= src[j]:
        dst[k] = src[i]; inc i
      else:
        dst[k] = src[j]; inc j
      inc k
    while i < mid:
      dst[k] = src[i]; inc i; inc k
    while j < hi:
      dst[k] = src[j]; inc j; inc k
    lo += 2 * w

proc mergesort(a: var seq[int32], tmp: var seq[int32], n: int) =
  var srcIsA = true
  var w = 1
  while w < n:
    if srcIsA: mergePass(a, tmp, n, w) else: mergePass(tmp, a, n, w)
    srcIsA = not srcIsA
    w *= 2
  if not srcIsA:
    for i in 0 ..< n: a[i] = tmp[i]

proc siftdown(a: var seq[int32], root0, n: int) =
  var root = root0
  while true:
    var c = 2 * root + 1
    if c >= n: break
    if c + 1 < n and a[c + 1] > a[c]: inc c
    if a[root] >= a[c]: break
    let t = a[root]; a[root] = a[c]; a[c] = t
    root = c

proc heapsort(a: var seq[int32], n: int) =
  var i = n div 2 - 1
  while i >= 0:
    siftdown(a, i, n)
    dec i
  var e = n - 1
  while e > 0:
    let t = a[0]; a[0] = a[e]; a[e] = t
    siftdown(a, 0, e)
    dec e

proc bsearchHas(a: seq[int32], n: int, key: int32): bool =
  var lo = 0
  var hi = n
  while lo < hi:
    let m = (lo + hi) div 2
    if a[m] < key: lo = m + 1 else: hi = m
  lo < n and a[lo] == key

let n = if paramCount() > 0: parseInt(paramStr(1)) else: 2_000_000
var orig = newSeq[int32](n)
var tmp = newSeq[int32](n)
for i in 0 ..< n: orig[i] = int32(next() shr 1)
var a = orig
var b = orig
var d = orig
quicksort(a, n)
mergesort(b, tmp, n)
heapsort(d, n)
var h: uint32 = 0
var ok = true
for i in 0 ..< n:
  h = h * 31'u32 + uint32(a[i])
  if a[i] != b[i] or a[i] != d[i]: ok = false
  if i > 0 and a[i - 1] > a[i]: ok = false
var bs = 0
for k in 0 ..< n:
  var key = int32(next() shr 1)
  if k mod 2 == 0: key = orig[int(uint32(key) mod uint32(n))]
  if bsearchHas(a, n, key): inc bs
var ls = 0
for k in 0 ..< 20:
  var key = int32(next() shr 1)
  if k mod 2 == 0: key = orig[int(uint32(key) mod uint32(n))]
  for i in 0 ..< n:
    if orig[i] == key:
      inc ls
      break
echo h, " ", bs, " ", ls, " ", (if ok: "OK" else: "BAD")

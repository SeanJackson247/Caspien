# LRU cache benchmark (port of lru.c): chained hash buckets through the node pool, index-based doubly linked recency list (parallel uint32 seqs).
import std/[os, strutils]

const
  CAP = 262144'u32
  KEYS = 1048576'u32
  HOT = 131072'u32
  NB = 524288
  NONE = 0xFFFFFFFF'u32

var x: uint32 = 12345
proc next(): uint32 =
  x = x * 1664525'u32 + 1013904223'u32
  x

var
  key = newSeq[uint32](int CAP)
  val = newSeq[uint32](int CAP)
  prv = newSeq[uint32](int CAP)
  nxt = newSeq[uint32](int CAP)
  hn = newSeq[uint32](int CAP)
  bucket = newSeq[uint32](NB)
  head = NONE
  tail = NONE

proc hsh(k: uint32): uint32 = uint32((uint64(k) * 2654435761'u64) and 0xFFFFFFFF'u64) shr 13

proc unlinkNode(i: uint32) =
  if prv[i] != NONE: nxt[prv[i]] = nxt[i] else: head = nxt[i]
  if nxt[i] != NONE: prv[nxt[i]] = prv[i] else: tail = prv[i]

proc pushFront(i: uint32) =
  prv[i] = NONE
  nxt[i] = head
  if head != NONE: prv[head] = i else: tail = i
  head = i

proc find(k: uint32): uint32 =
  var i = bucket[hsh(k)]
  while i != NONE and key[i] != k: i = hn[i]
  i

proc hashRemove(i: uint32) =
  let b = hsh(key[i])
  if bucket[b] == i:
    bucket[b] = hn[i]
    return
  var j = bucket[b]
  while hn[j] != i: j = hn[j]
  hn[j] = hn[i]

let n = if paramCount() > 0: parseInt(paramStr(1)) else: 20_000_000
for i in 0 ..< NB: bucket[i] = NONE
var size = 0'u32
var hits, misses, sum = 0'u64
for op in 0 ..< n:
  let a = next()
  let y = next()
  let range = if (y shr 20) mod 4 == 0: KEYS else: HOT
  let k = (a shr 8) mod range
  var i = find(k)
  if (y shr 24) mod 4 != 0:
    if i != NONE:
      inc hits
      sum += uint64(val[i]) + uint64(k)
      unlinkNode(i)
      pushFront(i)
    else: inc misses
  elif i != NONE:
    val[i] = y
    unlinkNode(i)
    pushFront(i)
  else:
    if size == CAP:
      i = tail
      sum += uint64(key[i])
      unlinkNode(i)
      hashRemove(i)
    else:
      i = size
      inc size
    key[i] = k
    val[i] = y
    let b = hsh(k)
    hn[i] = bucket[b]
    bucket[b] = i
    pushFront(i)
var fin = 0'u64
var i = head
while i != NONE:
  fin = (fin * 31 + uint64(key[i])) mod 4294967296'u64
  i = nxt[i]
echo hits, " ", misses, " ", (sum + fin) mod 4294967296'u64

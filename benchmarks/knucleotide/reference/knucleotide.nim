# k-nucleotide benchmark (port of knucleotide.c): hand-written open-addressing table (seq of objects), linear probing.
import std/[os, strutils]

type Slot = object
  key: uint64
  cnt: uint32

const KS = [1, 2, 3, 4, 6, 12]
const QK = [1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12]
const QV = [0'u64, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487]
const MUL = 0x9E3779B97F4A7C15'u64

proc lookup(tab: seq[Slot], kk: uint64, bits: int, cap: int): uint64 =
  var h = int((kk * MUL) shr uint64(64 - bits))
  while true:
    if tab[h].key == 0: return 0
    if tab[h].key == kk: return uint64(tab[h].cnt)
    h = (h + 1) and (cap - 1)

let n = if paramCount() > 0: parseInt(paramStr(1)) else: 30_000_000
var seqn = newSeq[byte](n + 1)
var last: uint32 = 42
for i in 0 ..< n:
  last = (last * 3877'u32 + 29573'u32) mod 139968'u32
  let c = if last < 42404'u32: 0'u8 elif last < 70117'u32: 1'u8 elif last < 97767'u32: 2'u8 else: 3'u8
  seqn[i] = c
var first12 = 0'u64
for i in 0 ..< min(12, n): first12 = (first12 shl 2) or uint64(seqn[i])
var distinctK: array[6, uint64]
var counts: array[12, uint64]
var nq = 0
for ki in 0 ..< 6:
  let k = KS[ki]
  var maxd = 1 shl (2 * k)
  let win = if n >= k: n - k + 1 else: 0
  if win < maxd: maxd = win
  if maxd > 139968: maxd = 139968
  var bits = 1
  while (1 shl bits) < 2 * maxd: inc bits
  let cap = 1 shl bits
  var tab = newSeq[Slot](cap)
  let mask = (1'u64 shl uint64(2 * k)) - 1
  var key, nd = 0'u64
  for i in 0 ..< n:
    key = ((key shl 2) or uint64(seqn[i])) and mask
    if i + 1 >= k:
      var h = int(((key + 1) * MUL) shr uint64(64 - bits))
      while true:
        if tab[h].key == 0:
          tab[h].key = key + 1
          tab[h].cnt = 1
          inc nd
          break
        if tab[h].key == key + 1:
          inc tab[h].cnt
          break
        h = (h + 1) and (cap - 1)
  distinctK[ki] = nd
  for q in 0 ..< 11:
    if QK[q] != k: continue
    counts[nq] = lookup(tab, QV[q] + 1, bits, cap)
    inc nq
  if k == 12:
    counts[nq] = lookup(tab, first12 + 1, bits, cap)
    inc nq
for i in 0 ..< 6: stdout.write distinctK[i], " "
for i in 0 ..< 12:
  if i > 0: stdout.write " "
  stdout.write counts[i]
stdout.write "\n"

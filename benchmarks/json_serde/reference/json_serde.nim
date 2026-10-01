# JSON serialise + parse benchmark (port of json_serde.c): hand-written serialiser and index-based parser over one text buffer, seq of Rec objects.
import std/[os, strutils]

type Rec = object
  id, nameOff, nameLen, score, tagOff, tagCnt: uint64

var x: uint32 = 12345
proc next(): uint32 =
  x = x * 1664525'u32 + 1013904223'u32
  x

proc wnum(t: var string, p: int, v0: uint64): int =
  var v = v0
  var d = 1
  var u = v
  while u >= 10:
    u = u div 10
    inc d
  for k in countdown(d - 1, 0):
    t[p + k] = char(ord('0') + int(v mod 10))
    v = v div 10
  p + d

proc wlit(t: var string, p0: int, s: string): int =
  var p = p0
  for ch in s:
    t[p] = ch
    inc p
  p

let n = if paramCount() > 0: parseInt(paramStr(1)) else: 4_000_000
var text = newString(n * 80 + 16)
var p = 0
text[p] = '['; inc p
for i in 0 ..< n:
  if i > 0:
    text[p] = ','; inc p
  p = wlit(text, p, "{\"id\":")
  p = wnum(text, p, uint64(i))
  p = wlit(text, p, ",\"name\":\"")
  let nl = 3 + int((next() shr 16) mod 8)
  for k in 0 ..< nl:
    text[p] = char(ord('a') + int((next() shr 16) mod 26)); inc p
  p = wlit(text, p, "\",\"score\":")
  let cents = uint64((next() shr 8) mod 1000000'u32)
  p = wnum(text, p, cents div 100)
  text[p] = '.'; inc p
  text[p] = char(ord('0') + int(cents mod 100 div 10)); inc p
  text[p] = char(ord('0') + int(cents mod 10)); inc p
  p = wlit(text, p, ",\"tags\":[")
  let tc = int((next() shr 16) mod 5)
  for k in 0 ..< tc:
    if k > 0:
      text[p] = ','; inc p
    p = wnum(text, p, uint64((next() shr 16) mod 100))
  p = wlit(text, p, "]}")
text[p] = ']'; inc p
let tlen = p
# ---- parse ----
var recs = newSeq[Rec](n + 1)
var names = newString(n * 10 + 8)
var tags = newSeq[byte](n * 4 + 8)
var count, noff, toff = 0
var q = 1
while true:
  let c = text[q]
  if c == ']': break
  if c == ',':
    inc q
    continue
  inc q
  var r: Rec
  while true:
    inc q
    let k0 = text[q]
    while text[q] != '"': inc q
    q += 2
    if k0 == 'i':
      var v = 0'u64
      while text[q] >= '0' and text[q] <= '9':
        v = v * 10 + uint64(ord(text[q]) - ord('0'))
        inc q
      r.id = v
    elif k0 == 'n':
      inc q
      r.nameOff = uint64(noff)
      while text[q] != '"':
        names[noff] = text[q]; inc noff
        inc q
      r.nameLen = uint64(noff) - r.nameOff
      inc q
    elif k0 == 's':
      var v = 0'u64
      while text[q] >= '0' and text[q] <= '9':
        v = v * 10 + uint64(ord(text[q]) - ord('0'))
        inc q
      inc q
      v = v * 100 + uint64((ord(text[q]) - ord('0')) * 10 + (ord(text[q + 1]) - ord('0')))
      q += 2
      r.score = v
    else:
      inc q
      r.tagOff = uint64(toff)
      while text[q] != ']':
        if text[q] == ',': inc q
        var v = 0'u64
        while text[q] >= '0' and text[q] <= '9':
          v = v * 10 + uint64(ord(text[q]) - ord('0'))
          inc q
        tags[toff] = byte(v); inc toff
      r.tagCnt = uint64(toff) - r.tagOff
      inc q
    if text[q] == ',': inc q
    else:
      inc q
      break
  recs[count] = r
  inc count
# ---- checksum over the parsed records ----
template lo32(v: uint64): uint32 = uint32(v and 0xFFFFFFFF'u64)
var h: uint32 = 7
for i in 0 ..< count:
  let r = recs[i]
  h = h * 31'u32 + lo32(r.id)
  h = h * 31'u32 + lo32(r.score)
  h = h * 31'u32 + lo32(r.nameLen)
  for k in 0 ..< int(r.nameLen): h = h * 31'u32 + uint32(ord(names[int(r.nameOff) + k]))
  h = h * 31'u32 + lo32(r.tagCnt)
  for k in 0 ..< int(r.tagCnt): h = h * 31'u32 + uint32(tags[int(r.tagOff) + k])
echo count, " ", tlen, " ", h

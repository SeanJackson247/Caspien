# String manipulation benchmark (port of strings.c): byte sequences, every result a fresh seq.
import std/[os, strutils]

var x: uint32 = 12345
proc next(): uint32 =
  x = x * 1664525'u32 + 1013904223'u32
  x

proc hash(b: seq[byte]): uint32 =
  var h: uint32 = 7
  for c in b: h = h * 31'u32 + uint32(c)
  h

let n = if paramCount() > 0: parseInt(paramStr(1)) else: 4_000_000
var text = newSeq[byte](n)
for i in 0 ..< n:
  let r = (next() shr 16) mod 27'u32
  text[i] = if r == 26: byte(' ') else: byte(int(r) + ord('a'))
var words, longest, cur = 0
for i in 0 ..< n:
  if text[i] == byte(' '):
    if cur > 0:
      inc words
      if cur > longest: longest = cur
      cur = 0
  else: inc cur
if cur > 0:
  inc words
  if cur > longest: longest = cur
var abc = 0
for i in 0 ..< n - 2:
  if text[i] == byte('a') and text[i + 1] == byte('b') and text[i + 2] == byte('c'): inc abc
var rev = newSeq[byte](n)
for i in 0 ..< n: rev[i] = text[n - 1 - i]
let hrev = hash(rev)
var es = 0
for i in 0 ..< n:
  if text[i] == byte('e'): inc es
let m = n + es
var rep = newSeq[byte](m)
var k = 0
for i in 0 ..< n:
  if text[i] == byte('e'):
    rep[k] = byte('3'); inc k
    rep[k] = byte('3'); inc k
  else:
    rep[k] = text[i]; inc k
let hrep = hash(rep)
var up = newSeq[byte](n)
for i in 0 ..< n: up[i] = if text[i] == byte(' '): byte(' ') else: text[i] - 32'u8
let hup = hash(up)
echo words, " ", longest, " ", abc, " ", hrev, " ", m, " ", hrep, " ", hup

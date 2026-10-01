# FASTA benchmark (port of fasta.c): everything is written into one byte buffer, only the summary line is printed.
import std/[os, strutils]

const ALU = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA"
const CHARS = "ACGTBDHKMNRSVWYACGT"
const THR: array[19, uint32] = [37792'u32, 54588, 71384, 109176, 111975, 114774, 117574, 120373, 123172, 125972, 128771, 131570, 134370, 137169, 139968, 42404, 70117, 97767, 139968]
const HDR: array[3, string] = [">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n"]

let n = if paramCount() > 0: parseInt(paramStr(1)) else: 40_000_000
var cnt: array[3, int]
cnt[0] = n * 2 div 10
cnt[1] = n * 3 div 10
cnt[2] = n - cnt[0] - cnt[1]
let off = [0, 0, 15]
var buf = newSeq[byte](n + n div 60 + 1024)
var last: uint32 = 42
var pos, a, c, g, t, other, ai = 0
for s in 0 .. 2:
  for hc in HDR[s]:
    buf[pos] = byte(hc); inc pos
  var col = 0
  for i in 0 ..< cnt[s]:
    var ch: char
    if s == 0:
      ch = ALU[ai]
      inc ai
      if ai == 287: ai = 0
    else:
      last = (last * 3877'u32 + 29573'u32) mod 139968'u32
      var j = off[s]
      while last >= THR[j]: inc j
      ch = CHARS[j]
    buf[pos] = byte(ch); inc pos
    if ch == 'A': inc a
    elif ch == 'C': inc c
    elif ch == 'G': inc g
    elif ch == 'T': inc t
    else: inc other
    inc col
    if col == 60:
      buf[pos] = byte('\n'); inc pos
      col = 0
  if col > 0:
    buf[pos] = byte('\n'); inc pos
var h: uint32 = 7
for i in 0 ..< pos: h = h * 31'u32 + uint32(buf[i])
echo pos, " ", h, " ", a, " ", c, " ", g, " ", t, " ", other

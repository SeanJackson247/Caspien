// FASTA benchmark, see fasta.c: all output goes into one byte buffer, then a checksum and letter counts are printed.
import Glibc

let ALU = Array("GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA".utf8)
let CHARS = Array("ACGTBDHKMNRSVWYACGT".utf8)
let THR: [UInt32] = [37792,54588,71384,109176,111975,114774,117574,120373,123172,125972,128771,131570,134370,137169,139968,42404,70117,97767,139968]
let HDR = [">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n"]

let n = CommandLine.arguments.count > 1 ? atol(CommandLine.arguments[1]) : 40000000
let cnt0 = n * 2 / 10, cnt1 = n * 3 / 10
let cnt = [cnt0, cnt1, n - cnt0 - cnt1]
let off = [0, 0, 15]
var buf = [UInt8](repeating: 0, count: n + n / 60 + 1024)
var last: UInt32 = 42
var pos = 0, a = 0, c = 0, g = 0, t = 0, other = 0, ai = 0
for s in 0..<3 {
    for ch in HDR[s].utf8 { buf[pos] = ch; pos += 1 }
    var col = 0
    for _ in 0..<cnt[s] {
        let ch: UInt8
        if s == 0 {
            ch = ALU[ai]
            ai += 1
            if ai == 287 { ai = 0 }
        } else {
            last = (last &* 3877 &+ 29573) % 139968
            var j = off[s]
            while last >= THR[j] { j += 1 }
            ch = CHARS[j]
        }
        buf[pos] = ch; pos += 1
        if ch == 65 { a += 1 } else if ch == 67 { c += 1 } else if ch == 71 { g += 1 } else if ch == 84 { t += 1 } else { other += 1 }
        col += 1
        if col == 60 { buf[pos] = 10; pos += 1; col = 0 }
    }
    if col > 0 { buf[pos] = 10; pos += 1 }
}
var h: UInt32 = 7
for i in 0..<pos { h = h &* 31 &+ UInt32(buf[i]) }
print("\(pos) \(h) \(a) \(c) \(g) \(t) \(other)")

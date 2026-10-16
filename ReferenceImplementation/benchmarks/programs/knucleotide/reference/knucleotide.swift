// k-nucleotide benchmark, see knucleotide.c: hand-written open-addressing table (linear probing, multiplicative hash).
import Glibc

struct Slot { var key: UInt64 = 0; var cnt: UInt32 = 0 }
let KS = [1, 2, 3, 4, 6, 12]
let QK = [1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12]
let QV: [UInt64] = [0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487]
let GOLD: UInt64 = 0x9E3779B97F4A7C15

let n = CommandLine.arguments.count > 1 ? atol(CommandLine.arguments[1]) : 30000000
var last: UInt32 = 42
var seq = [UInt8](repeating: 0, count: n + 1)
for i in 0..<n {
    last = (last &* 3877 &+ 29573) % 139968
    let c: UInt8
    if last < 42404 { c = 0 } else if last < 70117 { c = 1 } else if last < 97767 { c = 2 } else { c = 3 }
    seq[i] = c
}
var first12: UInt64 = 0
for i in 0..<min(12, n) { first12 = (first12 << 2) | UInt64(seq[i]) }

func lookup(_ tab: [Slot], _ kk: UInt64, _ bits: Int, _ cap: Int) -> UInt64 {
    var h = Int((kk &* GOLD) >> UInt64(64 - bits))
    while true {
        if tab[h].key == 0 { return 0 }
        if tab[h].key == kk { return UInt64(tab[h].cnt) }
        h = (h + 1) & (cap - 1)
    }
}

var distinct = [UInt64](repeating: 0, count: 6)
var counts = [UInt64]()
for ki in 0..<6 {
    let k = KS[ki]
    var maxd = 1 << (2 * k)
    let win = n >= k ? n - k + 1 : 0
    if win < maxd { maxd = win }
    if maxd > 139968 { maxd = 139968 }
    var bits = 1
    while (1 << bits) < 2 * maxd { bits += 1 }
    let cap = 1 << bits
    var tab = [Slot](repeating: Slot(), count: cap)
    let mask: UInt64 = (UInt64(1) << UInt64(2 * k)) - 1
    var key: UInt64 = 0, nd: UInt64 = 0
    let shift = UInt64(64 - bits)
    for i in 0..<n {
        key = ((key << 2) | UInt64(seq[i])) & mask
        if i + 1 >= k {
            var h = Int(((key &+ 1) &* GOLD) >> shift)
            while true {
                if tab[h].key == 0 { tab[h].key = key &+ 1; tab[h].cnt = 1; nd += 1; break }
                if tab[h].key == key &+ 1 { tab[h].cnt += 1; break }
                h = (h + 1) & (cap - 1)
            }
        }
    }
    distinct[ki] = nd
    for q in 0..<11 where QK[q] == k { counts.append(lookup(tab, QV[q] + 1, bits, cap)) }
    if k == 12 { counts.append(lookup(tab, first12 + 1, bits, cap)) }
}
var out = ""
for i in 0..<6 { out += "\(distinct[i]) " }
for i in 0..<12 { out += (i > 0 ? " " : "") + "\(counts[i])" }
print(out)

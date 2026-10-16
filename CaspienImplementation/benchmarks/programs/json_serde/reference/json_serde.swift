// JSON serialise + parse benchmark, see json_serde.c: hand-written serialiser and index-based parser over a byte buffer.
import Glibc

struct Rec { var id: UInt64 = 0, nameOff = 0, nameLen = 0, score: UInt64 = 0, tagOff = 0, tagCnt = 0 }
var x: UInt32 = 12345
@inline(__always) func next() -> UInt32 { x = x &* 1664525 &+ 1013904223; return x }

func wnum(_ t: inout [UInt8], _ p: Int, _ v0: UInt64) -> Int {
    var d = 1, u = v0
    while u >= 10 { u /= 10; d += 1 }
    var v = v0
    var k = d - 1
    while k >= 0 { t[p + k] = UInt8(48 + v % 10); v /= 10; k -= 1 }
    return p + d
}
func wlit(_ t: inout [UInt8], _ p0: Int, _ s: StaticString) -> Int {
    var p = p0
    s.withUTF8Buffer { for c in $0 { t[p] = c; p += 1 } }
    return p
}

let n = CommandLine.arguments.count > 1 ? atol(CommandLine.arguments[1]) : 4000000
var text = [UInt8](repeating: 0, count: n * 80 + 16)
var p = 0
text[p] = 91; p += 1
for i in 0..<n {
    if i > 0 { text[p] = 44; p += 1 }
    p = wlit(&text, p, "{\"id\":"); p = wnum(&text, p, UInt64(i))
    p = wlit(&text, p, ",\"name\":\"")
    let nl = 3 + Int((next() >> 16) % 8)
    for _ in 0..<nl { text[p] = UInt8(97 + (next() >> 16) % 26); p += 1 }
    p = wlit(&text, p, "\",\"score\":")
    let cents = UInt64((next() >> 8) % 1000000)
    p = wnum(&text, p, cents / 100); text[p] = 46; p += 1
    text[p] = UInt8(48 + cents % 100 / 10); p += 1
    text[p] = UInt8(48 + cents % 10); p += 1
    p = wlit(&text, p, ",\"tags\":[")
    let tc = Int((next() >> 16) % 5)
    for k in 0..<tc {
        if k > 0 { text[p] = 44; p += 1 }
        p = wnum(&text, p, UInt64((next() >> 16) % 100))
    }
    p = wlit(&text, p, "]}")
}
text[p] = 93; p += 1
let tlen = p
// ---- parse ----
var recs = [Rec]()
recs.reserveCapacity(n)
var names = [UInt8](repeating: 0, count: n * 10 + 8)
var tags = [UInt8](repeating: 0, count: n * 4 + 8)
var noff = 0, toff = 0, q = 1
while true {
    let c = text[q]
    if c == 93 { break }
    if c == 44 { q += 1; continue }
    q += 1                                  // '{'
    var r = Rec()
    while true {
        q += 1                              // opening quote of the key
        let k0 = text[q]
        while text[q] != 34 { q += 1 }
        q += 2                              // closing quote, ':'
        if k0 == 105 {                      // 'i'
            var v: UInt64 = 0
            while text[q] >= 48 && text[q] <= 57 { v = v &* 10 &+ UInt64(text[q] - 48); q += 1 }
            r.id = v
        } else if k0 == 110 {               // 'n'
            q += 1
            r.nameOff = noff
            while text[q] != 34 { names[noff] = text[q]; noff += 1; q += 1 }
            r.nameLen = noff - r.nameOff
            q += 1
        } else if k0 == 115 {               // 's'
            var v: UInt64 = 0
            while text[q] >= 48 && text[q] <= 57 { v = v &* 10 &+ UInt64(text[q] - 48); q += 1 }
            q += 1
            v = v &* 100 &+ UInt64(text[q] - 48) &* 10 &+ UInt64(text[q + 1] - 48); q += 2
            r.score = v
        } else {
            q += 1
            r.tagOff = toff
            while text[q] != 93 {
                if text[q] == 44 { q += 1 }
                var v: UInt64 = 0
                while text[q] >= 48 && text[q] <= 57 { v = v &* 10 &+ UInt64(text[q] - 48); q += 1 }
                tags[toff] = UInt8(truncatingIfNeeded: v); toff += 1
            }
            r.tagCnt = toff - r.tagOff
            q += 1
        }
        if text[q] == 44 { q += 1 } else { q += 1; break }
    }
    recs.append(r)
}
var h: UInt32 = 7
for r in recs {
    h = h &* 31 &+ UInt32(truncatingIfNeeded: r.id)
    h = h &* 31 &+ UInt32(truncatingIfNeeded: r.score)
    h = h &* 31 &+ UInt32(truncatingIfNeeded: r.nameLen)
    for k in 0..<r.nameLen { h = h &* 31 &+ UInt32(names[r.nameOff + k]) }
    h = h &* 31 &+ UInt32(truncatingIfNeeded: r.tagCnt)
    for k in 0..<r.tagCnt { h = h &* 31 &+ UInt32(tags[r.tagOff + k]) }
}
print("\(recs.count) \(tlen) \(h)")

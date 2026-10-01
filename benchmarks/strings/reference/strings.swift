// String manipulation benchmark (byte buffers), see strings.c.
import Glibc

let n = CommandLine.arguments.count > 1 ? atol(CommandLine.arguments[1]) : 4000000
var x: UInt32 = 12345
@inline(__always) func next() -> UInt32 { x = x &* 1664525 &+ 1013904223; return x }
func hash(_ b: [UInt8]) -> UInt32 {
    var h: UInt32 = 7
    for c in b { h = h &* 31 &+ UInt32(c) }
    return h
}
var text = [UInt8](repeating: 0, count: n)
for i in 0..<n {
    let r = (next() >> 16) % 27
    text[i] = r == 26 ? 32 : UInt8(97 + r)
}
var words = 0, longest = 0, cur = 0
for i in 0..<n {
    if text[i] == 32 {
        if cur > 0 { words += 1; if cur > longest { longest = cur }; cur = 0 }
    } else { cur += 1 }
}
if cur > 0 { words += 1; if cur > longest { longest = cur } }
var abc = 0
if n >= 3 {
    for i in 0..<(n - 2) where text[i] == 97 && text[i + 1] == 98 && text[i + 2] == 99 { abc += 1 }
}
var rev = [UInt8](repeating: 0, count: n)
for i in 0..<n { rev[i] = text[n - 1 - i] }
let hrev = hash(rev)
var es = 0
for i in 0..<n where text[i] == 101 { es += 1 }
let m = n + es
var rep = [UInt8](repeating: 0, count: m)
var k = 0
for i in 0..<n {
    if text[i] == 101 { rep[k] = 51; rep[k + 1] = 51; k += 2 } else { rep[k] = text[i]; k += 1 }
}
let hrep = hash(rep)
var up = [UInt8](repeating: 0, count: n)
for i in 0..<n { up[i] = text[i] == 32 ? 32 : text[i] &- 32 }
let hup = hash(up)
print("\(words) \(longest) \(abc) \(hrev) \(m) \(hrep) \(hup)")

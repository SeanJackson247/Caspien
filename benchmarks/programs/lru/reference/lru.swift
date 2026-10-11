// LRU cache benchmark, see lru.c: chained hash buckets threaded through the node pool + index-based doubly linked recency list.
import Glibc

let CAP = 262144, KEYS: UInt32 = 1048576, HOT: UInt32 = 131072, NB = 524288
let NONE: UInt32 = 0xFFFFFFFF
var x: UInt32 = 12345
@inline(__always) func next() -> UInt32 { x = x &* 1664525 &+ 1013904223; return x }

var key = [UInt32](repeating: 0, count: CAP)
var val = [UInt32](repeating: 0, count: CAP)
var prv = [UInt32](repeating: 0, count: CAP)
var nxt = [UInt32](repeating: 0, count: CAP)
var hn = [UInt32](repeating: 0, count: CAP)
var bucket = [UInt32](repeating: NONE, count: NB)
var head = NONE, tail = NONE

@inline(__always) func hsh(_ k: UInt32) -> Int { return Int((k &* 2654435761) >> 13) }
func unlinkNode(_ i: Int) {
    if prv[i] != NONE { nxt[Int(prv[i])] = nxt[i] } else { head = nxt[i] }
    if nxt[i] != NONE { prv[Int(nxt[i])] = prv[i] } else { tail = prv[i] }
}
func pushFront(_ i: Int) {
    prv[i] = NONE; nxt[i] = head
    if head != NONE { prv[Int(head)] = UInt32(i) } else { tail = UInt32(i) }
    head = UInt32(i)
}
func find(_ k: UInt32) -> UInt32 {
    var i = bucket[hsh(k)]
    while i != NONE && key[Int(i)] != k { i = hn[Int(i)] }
    return i
}
func hashRemove(_ i: Int) {
    let b = hsh(key[i])
    if bucket[b] == UInt32(i) { bucket[b] = hn[i]; return }
    var j = Int(bucket[b])
    while hn[j] != UInt32(i) { j = Int(hn[j]) }
    hn[j] = hn[i]
}

let n = CommandLine.arguments.count > 1 ? atol(CommandLine.arguments[1]) : 20000000
var size = 0
var hits: UInt64 = 0, misses: UInt64 = 0, sum: UInt64 = 0
for _ in 0..<n {
    let a = next(), y = next()
    let range = ((y >> 20) % 4 == 0) ? KEYS : HOT
    let k = (a >> 8) % range
    var i = find(k)
    if (y >> 24) % 4 != 0 {
        if i != NONE {
            hits += 1; sum &+= UInt64(val[Int(i)]) &+ UInt64(k); unlinkNode(Int(i)); pushFront(Int(i))
        } else { misses += 1 }
    } else if i != NONE {
        val[Int(i)] = y; unlinkNode(Int(i)); pushFront(Int(i))
    } else {
        var idx: Int
        if size == CAP {
            idx = Int(tail); sum &+= UInt64(key[idx]); unlinkNode(idx); hashRemove(idx)
        } else { idx = size; size += 1 }
        key[idx] = k; val[idx] = y
        let b = hsh(k); hn[idx] = bucket[b]; bucket[b] = UInt32(idx)
        pushFront(idx)
        i = UInt32(idx)
    }
}
var fin: UInt64 = 0
var i = head
while i != NONE { fin = (fin &* 31 &+ UInt64(key[Int(i)])) % 4294967296; i = nxt[Int(i)] }
print("\(hits) \(misses) \((sum &+ fin) % 4294967296)")

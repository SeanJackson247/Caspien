// Sorting and searching benchmark, see sorting.c: quicksort (explicit stack), bottom-up merge sort, heap sort, binary + linear search.
import Glibc

var x: UInt32 = 12345
@inline(__always) func next() -> UInt32 { x = x &* 1664525 &+ 1013904223; return x }

func quicksort(_ a: inout [Int32], _ n: Int) {
    var stack = [Int](repeating: 0, count: 128)
    var sp = 0
    stack[sp] = 0; sp += 1
    stack[sp] = n - 1; sp += 1
    while sp > 0 {
        sp -= 1; var hi = stack[sp]
        sp -= 1; var lo = stack[sp]
        while lo < hi {
            let pivot = a[(lo + hi) / 2]
            var i = lo, j = hi
            while i <= j {
                while a[i] < pivot { i += 1 }
                while a[j] > pivot { j -= 1 }
                if i <= j { let t = a[i]; a[i] = a[j]; a[j] = t; i += 1; j -= 1 }
            }
            if j - lo < hi - i {
                stack[sp] = i; sp += 1; stack[sp] = hi; sp += 1; hi = j
            } else {
                stack[sp] = lo; sp += 1; stack[sp] = j; sp += 1; lo = i
            }
        }
    }
}
func mergesort(_ a: inout [Int32], _ tmp: inout [Int32], _ n: Int) {
    var srcIsA = true
    var w = 1
    while w < n {
        var lo = 0
        while lo < n {
            let mid = lo + w < n ? lo + w : n
            let hi = lo + 2 * w < n ? lo + 2 * w : n
            var i = lo, j = mid, k = lo
            if srcIsA {
                while i < mid && j < hi {
                    if a[i] <= a[j] { tmp[k] = a[i]; i += 1 } else { tmp[k] = a[j]; j += 1 }
                    k += 1
                }
                while i < mid { tmp[k] = a[i]; k += 1; i += 1 }
                while j < hi { tmp[k] = a[j]; k += 1; j += 1 }
            } else {
                while i < mid && j < hi {
                    if tmp[i] <= tmp[j] { a[k] = tmp[i]; i += 1 } else { a[k] = tmp[j]; j += 1 }
                    k += 1
                }
                while i < mid { a[k] = tmp[i]; k += 1; i += 1 }
                while j < hi { a[k] = tmp[j]; k += 1; j += 1 }
            }
            lo += 2 * w
        }
        srcIsA.toggle()
        w *= 2
    }
    if !srcIsA { for i in 0..<n { a[i] = tmp[i] } }
}
func siftdown(_ a: inout [Int32], _ r: Int, _ n: Int) {
    var root = r
    while true {
        var c = 2 * root + 1
        if c >= n { break }
        if c + 1 < n && a[c + 1] > a[c] { c += 1 }
        if a[root] >= a[c] { break }
        let t = a[root]; a[root] = a[c]; a[c] = t
        root = c
    }
}
func heapsort(_ a: inout [Int32], _ n: Int) {
    var i = n / 2 - 1
    while i >= 0 { siftdown(&a, i, n); i -= 1 }
    var e = n - 1
    while e > 0 {
        let t = a[0]; a[0] = a[e]; a[e] = t
        siftdown(&a, 0, e)
        e -= 1
    }
}
func bsearchHas(_ a: [Int32], _ n: Int, _ key: Int32) -> Bool {
    var lo = 0, hi = n
    while lo < hi {
        let m = (lo + hi) / 2
        if a[m] < key { lo = m + 1 } else { hi = m }
    }
    return lo < n && a[lo] == key
}

let n = CommandLine.arguments.count > 1 ? atol(CommandLine.arguments[1]) : 2000000
var orig = [Int32](repeating: 0, count: n)
for i in 0..<n { orig[i] = Int32(truncatingIfNeeded: next() >> 1) }
var a = orig, b = orig, d = orig
var tmp = [Int32](repeating: 0, count: n)
quicksort(&a, n)
mergesort(&b, &tmp, n)
heapsort(&d, n)
var h: UInt32 = 0
var ok = true
for i in 0..<n {
    h = h &* 31 &+ UInt32(bitPattern: a[i])
    if a[i] != b[i] || a[i] != d[i] { ok = false }
    if i > 0 && a[i - 1] > a[i] { ok = false }
}
var bs = 0
for k in 0..<n {
    var key = Int32(truncatingIfNeeded: next() >> 1)
    if k % 2 == 0 { key = orig[Int(UInt32(bitPattern: key)) % n] }
    if bsearchHas(a, n, key) { bs += 1 }
}
var ls = 0
for k in 0..<20 {
    var key = Int32(truncatingIfNeeded: next() >> 1)
    if k % 2 == 0 { key = orig[Int(UInt32(bitPattern: key)) % n] }
    for i in 0..<n where orig[i] == key { ls += 1; break }
}
print("\(h) \(bs) \(ls) \(ok ? "OK" : "BAD")")

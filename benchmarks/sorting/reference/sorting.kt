var x: Int = 12345
fun next(): Int { x = x * 1664525 + 1013904223; return x }

fun quicksort(a: IntArray, n: Int) {
    val stack = LongArray(128); var sp = 0
    stack[sp++] = 0; stack[sp++] = (n - 1).toLong()
    while (sp > 0) {
        var hi = stack[--sp]; var lo = stack[--sp]
        while (lo < hi) {
            val pivot = a[((lo + hi) / 2).toInt()]
            var i = lo; var j = hi
            while (i <= j) {
                while (a[i.toInt()] < pivot) i++
                while (a[j.toInt()] > pivot) j--
                if (i <= j) { val t = a[i.toInt()]; a[i.toInt()] = a[j.toInt()]; a[j.toInt()] = t; i++; j-- }
            }
            if (j - lo < hi - i) { stack[sp++] = i; stack[sp++] = hi; hi = j }
            else { stack[sp++] = lo; stack[sp++] = j; lo = i }
        }
    }
}

fun mergesort(a: IntArray, tmp: IntArray, n: Int) {
    var src = a; var dst = tmp
    var w = 1
    while (w < n) {
        var lo = 0
        while (lo < n) {
            val mid = minOf(lo + w, n); val hi = minOf(lo + 2 * w, n)
            var i = lo; var j = mid; var k = lo
            while (i < mid && j < hi) { if (src[i] <= src[j]) dst[k++] = src[i++] else dst[k++] = src[j++] }
            while (i < mid) dst[k++] = src[i++]
            while (j < hi) dst[k++] = src[j++]
            lo += 2 * w
        }
        val t = src; src = dst; dst = t
        w *= 2
    }
    if (src !== a) System.arraycopy(src, 0, a, 0, n)
}

fun siftdown(a: IntArray, r: Int, n: Int) {
    var root = r
    while (true) {
        var c = 2 * root + 1
        if (c >= n) break
        if (c + 1 < n && a[c + 1] > a[c]) c++
        if (a[root] >= a[c]) break
        val t = a[root]; a[root] = a[c]; a[c] = t
        root = c
    }
}

fun heapsort(a: IntArray, n: Int) {
    var i = n / 2 - 1
    while (i >= 0) { siftdown(a, i, n); i-- }
    var e = n - 1
    while (e > 0) { val t = a[0]; a[0] = a[e]; a[e] = t; siftdown(a, 0, e); e-- }
}

fun bsearchHas(a: IntArray, n: Int, key: Int): Boolean {
    var lo = 0; var hi = n
    while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < key) lo = m + 1 else hi = m }
    return lo < n && a[lo] == key
}

fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toInt() else 2000000
    val orig = IntArray(n); val a = IntArray(n); val b = IntArray(n); val d = IntArray(n); val tmp = IntArray(n)
    for (i in 0 until n) orig[i] = next() ushr 1
    System.arraycopy(orig, 0, a, 0, n); System.arraycopy(orig, 0, b, 0, n); System.arraycopy(orig, 0, d, 0, n)
    quicksort(a, n)
    mergesort(b, tmp, n)
    heapsort(d, n)
    var h = 0; var ok = true
    for (i in 0 until n) {
        h = h * 31 + a[i]
        if (a[i] != b[i] || a[i] != d[i]) ok = false
        if (i > 0 && a[i - 1] > a[i]) ok = false
    }
    var bs = 0L
    for (k in 0 until n) {
        var key = next() ushr 1
        if (k % 2 == 0) key = orig[(key.toLong() % n).toInt()]
        if (bsearchHas(a, n, key)) bs++
    }
    var ls = 0L
    for (k in 0 until 20) {
        var key = next() ushr 1
        if (k % 2 == 0) key = orig[(key.toLong() % n).toInt()]
        for (i in 0 until n) if (orig[i] == key) { ls++; break }
    }
    print("${h.toUInt()} $bs $ls ${if (ok) "OK" else "BAD"}\n")
}

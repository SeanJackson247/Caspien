// fannkuch-redux, single threaded. Same algorithm as fannkuchredux-gcc-1 of the Computer Language Benchmarks Game.
fun fannkuchredux(n: Int): Int {
    val perm = IntArray(16); val perm1 = IntArray(16); val count = IntArray(16)
    var maxFlips = 0; var permCount = 0; var checksum = 0
    for (i in 0 until n) perm1[i] = i
    var r = n
    while (true) {
        while (r != 1) { count[r - 1] = r; r-- }
        for (i in 0 until n) perm[i] = perm1[i]
        var flips = 0
        var k = perm[0]
        while (k != 0) {
            val k2 = (k + 1) shr 1
            for (i in 0 until k2) { val t = perm[i]; perm[i] = perm[k - i]; perm[k - i] = t }
            flips++
            k = perm[0]
        }
        if (flips > maxFlips) maxFlips = flips
        checksum += if (permCount % 2 == 0) flips else -flips
        while (true) {
            if (r == n) { print("$checksum\n"); return maxFlips }
            val perm0 = perm1[0]
            var i = 0
            while (i < r) { val j = i + 1; perm1[i] = perm1[j]; i = j }
            perm1[r] = perm0
            count[r]--
            if (count[r] > 0) break
            r++
        }
        permCount++
    }
}

fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toInt() else 7
    val f = fannkuchredux(n)
    print("Pfannkuchen($n) = $f\n")
}

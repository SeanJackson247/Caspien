val KS = intArrayOf(1, 2, 3, 4, 6, 12)
val QK = intArrayOf(1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12)
val QV = longArrayOf(0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487L)

fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toInt() else 30000000
    var last = 42
    val seq = ByteArray(n + 1)
    for (i in 0 until n) {
        last = (last * 3877 + 29573) % 139968
        seq[i] = (if (last < 42404) 0 else if (last < 70117) 1 else if (last < 97767) 2 else 3).toByte()
    }
    var first12 = 0L
    var z = 0
    while (z < 12 && z < n) { first12 = (first12 shl 2) or seq[z].toLong(); z++ }
    val distinct = LongArray(6)
    val counts = LongArray(12)
    var nq = 0
    for (ki in 0 until 6) {
        val k = KS[ki]
        val m = HashMap<Long, Int>()
        val mask = (1L shl (2 * k)) - 1
        var key = 0L
        for (i in 0 until n) {
            key = ((key shl 2) or seq[i].toLong()) and mask
            if (i + 1 >= k) m.merge(key, 1, Int::plus)
        }
        distinct[ki] = m.size.toLong()
        for (q in 0 until 11) {
            if (QK[q] != k) continue
            counts[nq++] = (m[QV[q]] ?: 0).toLong()
        }
        if (k == 12) counts[nq++] = (m[first12] ?: 0).toLong()
    }
    val sb = StringBuilder()
    for (i in 0 until 6) sb.append(distinct[i]).append(' ')
    for (i in 0 until 12) { if (i > 0) sb.append(' '); sb.append(counts[i]) }
    sb.append('\n')
    print(sb)
}

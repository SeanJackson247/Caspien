fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toLong() else 100000000L
    val f = ByteArray((n + 1).toInt()) { 1 }
    var i = 2L
    while (i * i <= n) { if (f[i.toInt()].toInt() != 0) { var j = i * i; while (j <= n) { f[j.toInt()] = 0; j += i } }; i++ }
    var count = 0L
    for (k in 2..n.toInt()) count += f[k]
    println(count)
}

class Node { @JvmField var l: Node? = null; @JvmField var r: Node? = null }
fun make(d: Int): Node { val n = Node(); if (d > 0) { n.l = make(d - 1); n.r = make(d - 1) }; return n }
fun check(n: Node): Long { val l = n.l; return if (l == null) 1L else 1 + check(l) + check(n.r!!) }

fun main(args: Array<String>) {
    var maxd = if (args.isNotEmpty()) args[0].toInt() else 16
    if (maxd < 6) maxd = 6
    val sb = StringBuilder()
    sb.append(check(make(maxd + 1)))
    val longlived = make(maxd)
    var d = 4
    while (d <= maxd) {
        val iters = 1L shl (maxd - d + 4)
        var sum = 0L
        var i = 0L
        while (i < iters) { sum += check(make(d)); i++ }
        sb.append(' ').append(sum)
        d += 2
    }
    sb.append(' ').append(check(longlived))
    sb.append('\n')
    print(sb)
}

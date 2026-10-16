class Node { @JvmField var value: Int = 0; @JvmField var dist: Int = -1; @JvmField var e0: Node? = null; @JvmField var e1: Node? = null; @JvmField var e2: Node? = null; @JvmField var e3: Node? = null }
var x: Int = 12345
fun next(): Int { x = x * 1664525 + 1013904223; return x }

fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toInt() else 2000000
    val nodes = arrayOfNulls<Node>(n)
    for (i in 0 until n) { val nd = Node(); nd.value = next() and 0xFFFFFF; nodes[i] = nd }
    nodes[(n.toLong() * 7 / 10).toInt()]!!.value = -1
    for (i in 0 until n) {
        val nd = nodes[i]!!
        nd.e0 = nodes[(i + 1) % n]
        nd.e1 = nodes[(next().toUInt().toLong() % n).toInt()]
        nd.e2 = nodes[(next().toUInt().toLong() % n).toInt()]
        nd.e3 = nodes[(next().toUInt().toLong() % n).toInt()]
    }
    val q = arrayOfNulls<Node>(n)
    var head = 0; var tail = 0
    q[tail++] = nodes[0]; nodes[0]!!.dist = 0
    var maxdepth = 0; var needledist = -1; var sum = 0
    while (head < tail) {
        val u = q[head++]!!
        sum += u.value
        if (u.value == -1) needledist = u.dist
        if (u.dist > maxdepth) maxdepth = u.dist
        var v = u.e0!!; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v }
        v = u.e1!!; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v }
        v = u.e2!!; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v }
        v = u.e3!!; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v }
    }
    print("$tail $maxdepth $needledist ${sum.toUInt()}\n")
}

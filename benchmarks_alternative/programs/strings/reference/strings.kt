var x: Int = 12345
fun next(): Int { x = x * 1664525 + 1013904223; return x }
fun hash(b: ByteArray, n: Int): Int { var h = 7; for (i in 0 until n) h = h * 31 + (b[i].toInt() and 0xff); return h }

fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toInt() else 4000000
    val text = ByteArray(n)
    for (i in 0 until n) { val r = (next() ushr 16) % 27; text[i] = (if (r == 26) ' '.code else 'a'.code + r).toByte() }
    var words = 0L; var longest = 0L; var cur = 0L
    for (i in 0 until n) {
        if (text[i] == ' '.code.toByte()) { if (cur > 0) { words++; if (cur > longest) longest = cur; cur = 0 } } else cur++
    }
    if (cur > 0) { words++; if (cur > longest) longest = cur }
    var abc = 0L
    for (i in 0 until n - 2) if (text[i] == 'a'.code.toByte() && text[i + 1] == 'b'.code.toByte() && text[i + 2] == 'c'.code.toByte()) abc++
    val rev = ByteArray(n)
    for (i in 0 until n) rev[i] = text[n - 1 - i]
    val hrev = hash(rev, n)
    var es = 0
    for (i in 0 until n) if (text[i] == 'e'.code.toByte()) es++
    val m = n + es
    val rep = ByteArray(m)
    var k = 0
    for (i in 0 until n) { if (text[i] == 'e'.code.toByte()) { rep[k++] = '3'.code.toByte(); rep[k++] = '3'.code.toByte() } else rep[k++] = text[i] }
    val hrep = hash(rep, m)
    val up = ByteArray(n)
    for (i in 0 until n) up[i] = if (text[i] == ' '.code.toByte()) ' '.code.toByte() else (text[i] - 32).toByte()
    val hup = hash(up, n)
    print("$words $longest $abc ${hrev.toUInt()} $m ${hrep.toUInt()} ${hup.toUInt()}\n")
}

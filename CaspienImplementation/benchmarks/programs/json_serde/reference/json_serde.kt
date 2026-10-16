// JSON serialise + parse benchmark (Kotlin/JVM). Same hand-written serialiser / iterative parser / checksum as json_serde.c.
// Records are parallel LongArrays (like the Java port); names and tags go into ByteArray pools.
var x: Int = 12345
fun next(): Int { x = x * 1664525 + 1013904223; return x }

fun wnum(t: ByteArray, p: Int, v0: Long): Int {
    var v = v0
    var d = 1; var u = v; while (u >= 10) { u /= 10; d++ }
    for (k in d - 1 downTo 0) { t[p + k] = ('0'.code + (v % 10).toInt()).toByte(); v /= 10 }
    return p + d
}
fun wlit(t: ByteArray, p0: Int, s: String): Int { var p = p0; for (i in 0 until s.length) t[p++] = s[i].code.toByte(); return p }

fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toInt() else 4000000
    val text = ByteArray(n * 80 + 16)
    var p = 0
    text[p++] = '['.code.toByte()
    for (i in 0 until n) {
        if (i > 0) text[p++] = ','.code.toByte()
        p = wlit(text, p, "{\"id\":"); p = wnum(text, p, i.toLong())
        p = wlit(text, p, ",\"name\":\"")
        val nl = 3 + (next() ushr 16) % 8
        for (k in 0 until nl) text[p++] = ('a'.code + (next() ushr 16) % 26).toByte()
        p = wlit(text, p, "\",\"score\":")
        val cents = ((next() ushr 8) % 1000000).toLong()
        p = wnum(text, p, cents / 100); text[p++] = '.'.code.toByte(); text[p++] = ('0'.code + (cents % 100 / 10).toInt()).toByte(); text[p++] = ('0'.code + (cents % 10).toInt()).toByte()
        p = wlit(text, p, ",\"tags\":[")
        val tc = (next() ushr 16) % 5
        for (k in 0 until tc) { if (k > 0) text[p++] = ','.code.toByte(); p = wnum(text, p, ((next() ushr 16) % 100).toLong()) }
        p = wlit(text, p, "]}")
    }
    text[p++] = ']'.code.toByte()
    val tlen = p
    val rid = LongArray(n + 1); val rnoff = LongArray(n + 1); val rnlen = LongArray(n + 1); val rscore = LongArray(n + 1); val rtoff = LongArray(n + 1); val rtcnt = LongArray(n + 1)
    val names = ByteArray(n * 10 + 8)
    val tags = ByteArray(n * 4 + 8)
    var count = 0; var noff = 0; var toff = 0; var q = 1
    val DQ = '"'.code.toByte(); val D0 = '0'.code.toByte(); val D9 = '9'.code.toByte(); val COMMA = ','.code.toByte(); val RB = ']'.code.toByte()
    while (true) {
        val c = text[q]
        if (c == RB) break
        if (c == COMMA) { q++; continue }
        q++
        var id = 0L; var nameOff = 0L; var nameLen = 0L; var score = 0L; var tagOff = 0L; var tagCnt = 0L
        while (true) {
            q++
            val k0 = text[q].toInt().toChar()
            while (text[q] != DQ) q++
            q += 2
            if (k0 == 'i') {
                var v = 0L; while (text[q] >= D0 && text[q] <= D9) { v = v * 10 + (text[q] - D0); q++ }
                id = v
            } else if (k0 == 'n') {
                q++
                nameOff = noff.toLong()
                while (text[q] != DQ) { names[noff++] = text[q]; q++ }
                nameLen = noff - nameOff
                q++
            } else if (k0 == 's') {
                var v = 0L; while (text[q] >= D0 && text[q] <= D9) { v = v * 10 + (text[q] - D0); q++ }
                q++
                v = v * 100 + (text[q] - D0) * 10 + (text[q + 1] - D0); q += 2
                score = v
            } else {
                q++
                tagOff = toff.toLong()
                while (text[q] != RB) {
                    if (text[q] == COMMA) q++
                    var v = 0L; while (text[q] >= D0 && text[q] <= D9) { v = v * 10 + (text[q] - D0); q++ }
                    tags[toff++] = v.toByte()
                }
                tagCnt = toff - tagOff
                q++
            }
            if (text[q] == COMMA) q++ else { q++; break }
        }
        rid[count] = id; rnoff[count] = nameOff; rnlen[count] = nameLen; rscore[count] = score; rtoff[count] = tagOff; rtcnt[count] = tagCnt
        count++
    }
    var h = 7
    for (i in 0 until count) {
        h = h * 31 + rid[i].toInt()
        h = h * 31 + rscore[i].toInt()
        h = h * 31 + rnlen[i].toInt()
        for (k in 0 until rnlen[i]) h = h * 31 + (names[(rnoff[i] + k).toInt()].toInt() and 0xff)
        h = h * 31 + rtcnt[i].toInt()
        for (k in 0 until rtcnt[i]) h = h * 31 + (tags[(rtoff[i] + k).toInt()].toInt() and 0xff)
    }
    print("$count $tlen ${h.toUInt()}\n")
}

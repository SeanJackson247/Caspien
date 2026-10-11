const val ALU = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA"
const val CHARS = "ACGTBDHKMNRSVWYACGT"
val THR = intArrayOf(37792, 54588, 71384, 109176, 111975, 114774, 117574, 120373, 123172, 125972, 128771, 131570, 134370, 137169, 139968, 42404, 70117, 97767, 139968)
val HDR = arrayOf(">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n")

fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toInt() else 40000000
    val cnt = intArrayOf(0, 0, 0)
    cnt[0] = (n.toLong() * 2 / 10).toInt()
    cnt[1] = (n.toLong() * 3 / 10).toInt()
    cnt[2] = n - cnt[0] - cnt[1]
    val off = intArrayOf(0, 0, 15)
    var last = 42
    val buf = ByteArray(n + n / 60 + 1024)
    val alu = ALU.toByteArray(Charsets.US_ASCII)
    val chars = CHARS.toByteArray(Charsets.US_ASCII)
    var pos = 0; var ai = 0
    var a = 0L; var c = 0L; var g = 0L; var t = 0L; var other = 0L
    for (s in 0 until 3) {
        val hd = HDR[s].toByteArray(Charsets.US_ASCII)
        System.arraycopy(hd, 0, buf, pos, hd.size)
        pos += hd.size
        var col = 0
        for (i in 0 until cnt[s]) {
            val ch: Byte
            if (s == 0) {
                ch = alu[ai]
                if (++ai == 287) ai = 0
            } else {
                last = (last * 3877 + 29573) % 139968
                var j = off[s]
                while (last >= THR[j]) j++
                ch = chars[j]
            }
            buf[pos++] = ch
            when (ch.toInt().toChar()) { 'A' -> a++; 'C' -> c++; 'G' -> g++; 'T' -> t++; else -> other++ }
            if (++col == 60) { buf[pos++] = '\n'.code.toByte(); col = 0 }
        }
        if (col > 0) buf[pos++] = '\n'.code.toByte()
    }
    var h = 7
    for (i in 0 until pos) h = h * 31 + (buf[i].toInt() and 0xff)
    print("$pos ${h.toUInt()} $a $c $g $t $other\n")
}

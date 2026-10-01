// LRU cache benchmark (Kotlin/JVM): an access-ordered LinkedHashMap (eldest = least recently used), like the Java port.
const val CAP = 262144
const val KEYS = 1048576
const val HOT = 131072
var x: Int = 12345
fun next(): Int { x = x * 1664525 + 1013904223; return x }

fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toInt() else 20000000
    val m = LinkedHashMap<Int, Int>(CAP * 2, 0.75f, true)
    var hits = 0L; var misses = 0L
    var sum = 0
    for (op in 0 until n) {
        val a = next(); val y = next()
        val range = if ((y ushr 20) % 4 == 0) KEYS else HOT
        val k = (a ushr 8) % range
        if ((y ushr 24) % 4 != 0) {
            val v = m[k]   // access order: get moves the entry to the most-recent end
            if (v != null) { hits++; sum += v + k } else misses++
        } else if (m.containsKey(k)) {
            m[k] = y       // put of an existing key also counts as an access
        } else {
            if (m.size == CAP) {
                val eldest = m.entries.iterator().next()
                val ek = eldest.key
                sum += ek
                m.remove(ek)
            }
            m[k] = y
        }
    }
    var fin = 0L
    // iteration order is least -> most recent; the checksum walks most -> least recent
    val sz = m.size
    val keys = IntArray(sz)
    var j = sz
    for (kk in m.keys) keys[--j] = kk
    for (i in 0 until sz) fin = (fin * 31 + keys[i]) % 4294967296L
    print("$hits $misses ${((sum.toLong() and 0xFFFFFFFFL) + fin) % 4294967296L}\n")
}

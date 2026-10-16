import java.util.Locale

// spectral-norm, single threaded. Same algorithm as spectralnorm-gcc-1 of the Computer Language Benchmarks Game.
fun evalA(i: Int, j: Int): Double = 1.0 / ((i + j) * (i + j + 1) / 2 + i + 1)

fun aTimesU(n: Int, u: DoubleArray, au: DoubleArray) {
    for (i in 0 until n) { var s = 0.0; for (j in 0 until n) s += evalA(i, j) * u[j]; au[i] = s }
}
fun atTimesU(n: Int, u: DoubleArray, au: DoubleArray) {
    for (i in 0 until n) { var s = 0.0; for (j in 0 until n) s += evalA(j, i) * u[j]; au[i] = s }
}
fun ataTimesU(n: Int, u: DoubleArray, atau: DoubleArray) {
    val v = DoubleArray(n)
    aTimesU(n, u, v)
    atTimesU(n, v, atau)
}

fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toInt() else 100
    val u = DoubleArray(n); val v = DoubleArray(n)
    u.fill(1.0)
    for (i in 0 until 10) { ataTimesU(n, u, v); ataTimesU(n, v, u) }
    var vBv = 0.0; var vv = 0.0
    for (i in 0 until n) { vBv += u[i] * v[i]; vv += v[i] * v[i] }
    print(String.format(Locale.ROOT, "%.9f", Math.sqrt(vBv / vv)) + "\n")
}

fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toLong() else 3000L
    val dn = n.toDouble()
    var inside = 0L; var total = 0L
    var y = 0L
    while (y < n) {
        val ci = 2.0 * y.toDouble() / dn - 1.0
        var x = 0L
        while (x < n) {
            val cr = 2.0 * x.toDouble() / dn - 1.5
            var zr = 0.0; var zi = 0.0; var tr = 0.0; var ti = 0.0
            var i = 0
            while (i < 100 && tr + ti <= 4.0) {
                zi = 2.0 * zr * zi + ci
                zr = tr - ti + cr
                tr = zr * zr
                ti = zi * zi
                i++
            }
            total += i
            if (i == 100) inside++
            x++
        }
        y++
    }
    print("$inside $total\n")
}

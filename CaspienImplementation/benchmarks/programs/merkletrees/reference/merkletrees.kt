// Merkle tree benchmark (Kotlin/JVM, real SHA-256): same algorithm as merkletrees.c (Int arithmetic wraps; ushr is the unsigned shift).
var x: Int = 12345
fun next(): Int { x = x * 1664525 + 1013904223; return x }
val K = intArrayOf(0x428a2f98.toInt(),0x71374491.toInt(),0xb5c0fbcf.toInt(),0xe9b5dba5.toInt(),0x3956c25b.toInt(),0x59f111f1.toInt(),0x923f82a4.toInt(),0xab1c5ed5.toInt(),0xd807aa98.toInt(),0x12835b01.toInt(),0x243185be.toInt(),0x550c7dc3.toInt(),0x72be5d74.toInt(),0x80deb1fe.toInt(),0x9bdc06a7.toInt(),0xc19bf174.toInt(),0xe49b69c1.toInt(),0xefbe4786.toInt(),0x0fc19dc6.toInt(),0x240ca1cc.toInt(),0x2de92c6f.toInt(),0x4a7484aa.toInt(),0x5cb0a9dc.toInt(),0x76f988da.toInt(),0x983e5152.toInt(),0xa831c66d.toInt(),0xb00327c8.toInt(),0xbf597fc7.toInt(),0xc6e00bf3.toInt(),0xd5a79147.toInt(),0x06ca6351.toInt(),0x14292967.toInt(),0x27b70a85.toInt(),0x2e1b2138.toInt(),0x4d2c6dfc.toInt(),0x53380d13.toInt(),0x650a7354.toInt(),0x766a0abb.toInt(),0x81c2c92e.toInt(),0x92722c85.toInt(),0xa2bfe8a1.toInt(),0xa81a664b.toInt(),0xc24b8b70.toInt(),0xc76c51a3.toInt(),0xd192e819.toInt(),0xd6990624.toInt(),0xf40e3585.toInt(),0x106aa070.toInt(),0x19a4c116.toInt(),0x1e376c08.toInt(),0x2748774c.toInt(),0x34b0bcb5.toInt(),0x391c0cb3.toInt(),0x4ed8aa4a.toInt(),0x5b9cca4f.toInt(),0x682e6ff3.toInt(),0x748f82ee.toInt(),0x78a5636f.toInt(),0x84c87814.toInt(),0x8cc70208.toInt(),0x90befffa.toInt(),0xa4506ceb.toInt(),0xbef9a3f7.toInt(),0xc67178f2.toInt())
val H0 = intArrayOf(0x6a09e667.toInt(),0xbb67ae85.toInt(),0x3c6ef372.toInt(),0xa54ff53a.toInt(),0x510e527f.toInt(),0x9b05688c.toInt(),0x1f83d9ab.toInt(),0x5be0cd19.toInt())
val w = IntArray(64)
val blk = IntArray(16)
val st = IntArray(8)

fun compress() {
    for (i in 0 until 16) w[i] = blk[i]
    for (i in 16 until 64) {
        val a = w[i - 15]; val c = w[i - 2]
        val s0 = (a.rotateRight(7)) xor (a.rotateRight(18)) xor (a ushr 3)
        val s1 = (c.rotateRight(17)) xor (c.rotateRight(19)) xor (c ushr 10)
        w[i] = w[i - 16] + s0 + w[i - 7] + s1
    }
    var a = st[0]; var b = st[1]; var c = st[2]; var d = st[3]; var e = st[4]; var f = st[5]; var g = st[6]; var h = st[7]
    for (i in 0 until 64) {
        val S1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
        val ch = (e and f) xor (e.inv() and g)
        val t1 = h + S1 + ch + K[i] + w[i]
        val S0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
        val mj = (a and b) xor (a and c) xor (b and c)
        val t2 = S0 + mj
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
    }
    st[0] += a; st[1] += b; st[2] += c; st[3] += d; st[4] += e; st[5] += f; st[6] += g; st[7] += h
}

// out[oo..oo+7] = SHA256(le64(dlo + 2^32 dhi) || le64(i))
fun leaf(out: IntArray, oo: Int, dlo: Int, dhi: Int, i: Long) {
    for (k in 0 until 16) blk[k] = 0
    blk[0] = Integer.reverseBytes(dlo); blk[1] = Integer.reverseBytes(dhi)
    blk[2] = Integer.reverseBytes(i.toInt()); blk[3] = Integer.reverseBytes((i ushr 32).toInt())
    blk[4] = 0x80000000.toInt(); blk[15] = 128
    for (k in 0 until 8) st[k] = H0[k]
    compress()
    for (k in 0 until 8) out[oo + k] = st[k]
}

// out[oo..] = SHA256(l[lo..lo+7] || r[ro..ro+7]); the output may alias an input (inputs are copied first)
fun node(out: IntArray, oo: Int, l: IntArray, lo: Int, r: IntArray, ro: Int) {
    for (k in 0 until 8) { blk[k] = l[lo + k]; blk[8 + k] = r[ro + k]; st[k] = H0[k] }
    compress()
    for (k in 0 until 16) blk[k] = 0
    blk[0] = 0x80000000.toInt(); blk[15] = 512
    compress()
    for (k in 0 until 8) out[oo + k] = st[k]
}

fun main(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toInt() else 140000
    val data = IntArray(n)
    val t = IntArray((2 * n + 64) * 8)
    for (i in 0 until n) { data[i] = next(); leaf(t, 8 * i, data[i], 0, i.toLong()) }
    var off = 0; var size = n
    while (size > 1) {
        val noff = off + size; val ns = (size + 1) / 2
        for (j in 0 until ns) {
            val li = off + 2 * j; val ri = if (2 * j + 1 < size) li + 1 else li
            node(t, 8 * (noff + j), t, 8 * li, t, 8 * ri)
        }
        off = noff; size = ns
    }
    val root = IntArray(8)
    for (k in 0 until 8) root[k] = t[8 * off + k]
    var verified = 0L; var rejected = 0L
    val sibs = IntArray(64 * 8); val cur = IntArray(8)
    for (p in 0 until n / 2) {
        val idx = (next().toUInt().toLong() % n).toInt()
        var o = 0; var s = n; var pos = idx; var depth = 0
        while (s > 1) {
            var sb = if (pos % 2 == 0) pos + 1 else pos - 1
            if (sb >= s) sb = pos
            for (k in 0 until 8) sibs[depth * 8 + k] = t[8 * (o + sb) + k]
            depth++
            o += s; s = (s + 1) / 2; pos /= 2
        }
        var dlo = data[idx]; var dhi = 0
        if (p % 4 == 3) { dlo += 1; if (dlo == 0) dhi = 1 }
        leaf(cur, 0, dlo, dhi, idx.toLong())
        pos = idx
        for (d in 0 until depth) {
            if (pos % 2 == 0) node(cur, 0, cur, 0, sibs, d * 8) else node(cur, 0, sibs, d * 8, cur, 0)
            pos /= 2
        }
        var eq = true
        for (k in 0 until 8) if (cur[k] != root[k]) eq = false
        if (eq) verified++ else rejected++
    }
    val sbd = StringBuilder()
    for (k in 0 until 8) sbd.append(String.format(java.util.Locale.ROOT, "%08x", root[k]))
    print("$sbd $verified $rejected\n")
}

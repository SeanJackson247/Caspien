import java.util.Locale

const val PI = 3.141592653589793
const val SOLAR = 4 * PI * PI
const val DPY = 365.24

class Body(@JvmField var x: Double, @JvmField var y: Double, @JvmField var z: Double,
           @JvmField var vx: Double, @JvmField var vy: Double, @JvmField var vz: Double, @JvmField var mass: Double)

val b = arrayOf(
    Body(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, SOLAR),
    Body(4.84143144246472090e+00, -1.16032004402742839e+00, -1.03622044471123109e-01, 1.66007664274403694e-03 * DPY, 7.69901118419740425e-03 * DPY, -6.90460016972063023e-05 * DPY, 9.54791938424326609e-04 * SOLAR),
    Body(8.34336671824457987e+00, 4.12479856412430479e+00, -4.03523417114321381e-01, -2.76742510726862411e-03 * DPY, 4.99852801234917238e-03 * DPY, 2.30417297573763929e-05 * DPY, 2.85885980666130812e-04 * SOLAR),
    Body(1.28943695621391310e+01, -1.51111514016986312e+01, -2.23307578892655734e-01, 2.96460137564761618e-03 * DPY, 2.37847173959480950e-03 * DPY, -2.96589568540237556e-05 * DPY, 4.36624404335156298e-05 * SOLAR),
    Body(1.53796971148509165e+01, -2.59193146099879641e+01, 1.79258772950371181e-01, 2.68067772490389322e-03 * DPY, 1.62824170038242295e-03 * DPY, -9.51592254519715870e-05 * DPY, 5.15138902046611451e-05 * SOLAR))

fun advance(dt: Double) {
    for (i in 0 until 5) for (j in i + 1 until 5) {
        val p = b[i]; val q = b[j]
        val dx = p.x - q.x; val dy = p.y - q.y; val dz = p.z - q.z
        val d2 = dx * dx + dy * dy + dz * dz; val mag = dt / (d2 * Math.sqrt(d2))
        p.vx -= dx * q.mass * mag; p.vy -= dy * q.mass * mag; p.vz -= dz * q.mass * mag
        q.vx += dx * p.mass * mag; q.vy += dy * p.mass * mag; q.vz += dz * p.mass * mag
    }
    for (p in b) { p.x += dt * p.vx; p.y += dt * p.vy; p.z += dt * p.vz }
}

fun energy(): Double {
    var e = 0.0
    for (i in 0 until 5) {
        val p = b[i]
        e += 0.5 * p.mass * (p.vx * p.vx + p.vy * p.vy + p.vz * p.vz)
        for (j in i + 1 until 5) {
            val q = b[j]
            val dx = p.x - q.x; val dy = p.y - q.y; val dz = p.z - q.z
            e -= p.mass * q.mass / Math.sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
    return e
}

fun main(args: Array<String>) {
    val n = args[0].toLong()
    var px = 0.0; var py = 0.0; var pz = 0.0
    for (p in b) { px += p.vx * p.mass; py += p.vy * p.mass; pz += p.vz * p.mass }
    b[0].vx = -px / SOLAR; b[0].vy = -py / SOLAR; b[0].vz = -pz / SOLAR
    print(String.format(Locale.ROOT, "%.9f", energy()) + "\n")
    var i = 0L
    while (i < n) { advance(0.01); i++ }
    print(String.format(Locale.ROOT, "%.9f", energy()) + "\n")
}

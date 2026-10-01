# n-body: see nbody.c.
PI         = 3.141592653589793
SOLAR_MASS = 4.0 * PI * PI
DPY        = 365.24

class Planet
  property x : Float64, y : Float64, z : Float64
  property vx : Float64, vy : Float64, vz : Float64
  getter mass : Float64

  def initialize(@x, @y, @z, @vx, @vy, @vz, @mass)
  end
end

BODIES = [
  Planet.new(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, SOLAR_MASS),
  Planet.new(4.84143144246472090e+00, -1.16032004402742839e+00, -1.03622044471123109e-01,
    1.66007664274403694e-03 * DPY, 7.69901118419740425e-03 * DPY, -6.90460016972063023e-05 * DPY,
    9.54791938424326609e-04 * SOLAR_MASS),
  Planet.new(8.34336671824457987e+00, 4.12479856412430479e+00, -4.03523417114321381e-01,
    -2.76742510726862411e-03 * DPY, 4.99852801234917238e-03 * DPY, 2.30417297573763929e-05 * DPY,
    2.85885980666130812e-04 * SOLAR_MASS),
  Planet.new(1.28943695621391310e+01, -1.51111514016986312e+01, -2.23307578892655734e-01,
    2.96460137564761618e-03 * DPY, 2.37847173959480950e-03 * DPY, -2.96589568540237556e-05 * DPY,
    4.36624404335156298e-05 * SOLAR_MASS),
  Planet.new(1.53796971148509165e+01, -2.59193146099879641e+01, 1.79258772950371181e-01,
    2.68067772490389322e-03 * DPY, 1.62824170038242295e-03 * DPY, -9.51592254519715870e-05 * DPY,
    5.15138902046611451e-05 * SOLAR_MASS),
]

def advance(dt : Float64)
  5.times do |i|
    bi = BODIES[i]
    (i + 1).upto(4) do |j|
      bj = BODIES[j]
      dx = bi.x - bj.x
      dy = bi.y - bj.y
      dz = bi.z - bj.z
      d2 = dx * dx + dy * dy + dz * dz
      mag = dt / (d2 * Math.sqrt(d2))
      bi.vx -= dx * bj.mass * mag
      bi.vy -= dy * bj.mass * mag
      bi.vz -= dz * bj.mass * mag
      bj.vx += dx * bi.mass * mag
      bj.vy += dy * bi.mass * mag
      bj.vz += dz * bi.mass * mag
    end
  end
  5.times do |i|
    b = BODIES[i]
    b.x += dt * b.vx
    b.y += dt * b.vy
    b.z += dt * b.vz
  end
end

def energy : Float64
  e = 0.0
  5.times do |i|
    bi = BODIES[i]
    e += 0.5 * bi.mass * (bi.vx * bi.vx + bi.vy * bi.vy + bi.vz * bi.vz)
    (i + 1).upto(4) do |j|
      bj = BODIES[j]
      dx = bi.x - bj.x
      dy = bi.y - bj.y
      dz = bi.z - bj.z
      e -= bi.mass * bj.mass / Math.sqrt(dx * dx + dy * dy + dz * dz)
    end
  end
  e
end

n = ARGV[0].to_i64
px = 0.0
py = 0.0
pz = 0.0
BODIES.each do |b|
  px += b.vx * b.mass
  py += b.vy * b.mass
  pz += b.vz * b.mass
end
BODIES[0].vx = -px / SOLAR_MASS
BODIES[0].vy = -py / SOLAR_MASS
BODIES[0].vz = -pz / SOLAR_MASS
printf "%.9f\n", energy
n.times { advance(0.01) }
printf "%.9f\n", energy

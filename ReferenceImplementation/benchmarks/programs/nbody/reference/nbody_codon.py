import sys
from math import sqrt

PI = 3.141592653589793
SOLAR = 4 * PI * PI
DPY = 365.24

class Planet:
    x: float
    y: float
    z: float
    vx: float
    vy: float
    vz: float
    mass: float

    def __init__(self, x: float, y: float, z: float, vx: float, vy: float, vz: float, mass: float):
        self.x = x
        self.y = y
        self.z = z
        self.vx = vx
        self.vy = vy
        self.vz = vz
        self.mass = mass

def make_bodies():
    return [
        Planet(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, SOLAR),
        Planet(4.84143144246472090e+00, -1.16032004402742839e+00, -1.03622044471123109e-01, 1.66007664274403694e-03 * DPY, 7.69901118419740425e-03 * DPY, -6.90460016972063023e-05 * DPY, 9.54791938424326609e-04 * SOLAR),
        Planet(8.34336671824457987e+00, 4.12479856412430479e+00, -4.03523417114321381e-01, -2.76742510726862411e-03 * DPY, 4.99852801234917238e-03 * DPY, 2.30417297573763929e-05 * DPY, 2.85885980666130812e-04 * SOLAR),
        Planet(1.28943695621391310e+01, -1.51111514016986312e+01, -2.23307578892655734e-01, 2.96460137564761618e-03 * DPY, 2.37847173959480950e-03 * DPY, -2.96589568540237556e-05 * DPY, 4.36624404335156298e-05 * SOLAR),
        Planet(1.53796971148509165e+01, -2.59193146099879641e+01, 1.79258772950371181e-01, 2.68067772490389322e-03 * DPY, 1.62824170038242295e-03 * DPY, -9.51592254519715870e-05 * DPY, 5.15138902046611451e-05 * SOLAR),
    ]

def advance(bodies: List[Planet], dt: float):
    for i in range(5):
        bi = bodies[i]
        for j in range(i + 1, 5):
            bj = bodies[j]
            dx = bi.x - bj.x
            dy = bi.y - bj.y
            dz = bi.z - bj.z
            d2 = dx * dx + dy * dy + dz * dz
            mag = dt / (d2 * sqrt(d2))
            bi.vx -= dx * bj.mass * mag
            bi.vy -= dy * bj.mass * mag
            bi.vz -= dz * bj.mass * mag
            bj.vx += dx * bi.mass * mag
            bj.vy += dy * bi.mass * mag
            bj.vz += dz * bi.mass * mag
    for i in range(5):
        b = bodies[i]
        b.x += dt * b.vx
        b.y += dt * b.vy
        b.z += dt * b.vz

def energy(bodies: List[Planet]) -> float:
    e = 0.0
    for i in range(5):
        bi = bodies[i]
        e += 0.5 * bi.mass * (bi.vx * bi.vx + bi.vy * bi.vy + bi.vz * bi.vz)
        for j in range(i + 1, 5):
            bj = bodies[j]
            dx = bi.x - bj.x
            dy = bi.y - bj.y
            dz = bi.z - bj.z
            e -= bi.mass * bj.mass / sqrt(dx * dx + dy * dy + dz * dz)
    return e

def main():
    n = int(sys.argv[1])
    bodies = make_bodies()
    px = 0.0
    py = 0.0
    pz = 0.0
    for b in bodies:
        px += b.vx * b.mass
        py += b.vy * b.mass
        pz += b.vz * b.mass
    bodies[0].vx = -px / SOLAR
    bodies[0].vy = -py / SOLAR
    bodies[0].vz = -pz / SOLAR
    print(f"{energy(bodies):.9f}")
    for _ in range(n):
        advance(bodies, 0.01)
    print(f"{energy(bodies):.9f}")

main()

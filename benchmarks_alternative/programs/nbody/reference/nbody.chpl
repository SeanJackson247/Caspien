// N-body simulation, see nbody.c.
use IO;
param PI = 3.141592653589793;
param SOLAR_MASS = 4 * PI * PI;
param DPY = 365.24;
record Planet { var x, y, z, vx, vy, vz, mass: real; }

proc advance(ref b: [] Planet, dt: real) {
  for i in 0..<5 {
    for j in (i + 1)..<5 {
      const dx = b[i].x - b[j].x, dy = b[i].y - b[j].y, dz = b[i].z - b[j].z;
      const d2 = dx * dx + dy * dy + dz * dz;
      const mag = dt / (d2 * sqrt(d2));
      const mi = b[i].mass, mj = b[j].mass;
      b[i].vx -= dx * mj * mag; b[i].vy -= dy * mj * mag; b[i].vz -= dz * mj * mag;
      b[j].vx += dx * mi * mag; b[j].vy += dy * mi * mag; b[j].vz += dz * mi * mag;
    }
  }
  for i in 0..<5 {
    b[i].x += dt * b[i].vx; b[i].y += dt * b[i].vy; b[i].z += dt * b[i].vz;
  }
}
proc energy(const ref b: [] Planet): real {
  var e = 0.0;
  for i in 0..<5 {
    e += 0.5 * b[i].mass * (b[i].vx * b[i].vx + b[i].vy * b[i].vy + b[i].vz * b[i].vz);
    for j in (i + 1)..<5 {
      const dx = b[i].x - b[j].x, dy = b[i].y - b[j].y, dz = b[i].z - b[j].z;
      e -= b[i].mass * b[j].mass / sqrt(dx * dx + dy * dy + dz * dz);
    }
  }
  return e;
}

proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 1000;
  var bodies: [0..<5] Planet = [
    new Planet(0, 0, 0, 0, 0, 0, SOLAR_MASS),
    new Planet(4.84143144246472090e+00, -1.16032004402742839e+00, -1.03622044471123109e-01, 1.66007664274403694e-03 * DPY, 7.69901118419740425e-03 * DPY, -6.90460016972063023e-05 * DPY, 9.54791938424326609e-04 * SOLAR_MASS),
    new Planet(8.34336671824457987e+00, 4.12479856412430479e+00, -4.03523417114321381e-01, -2.76742510726862411e-03 * DPY, 4.99852801234917238e-03 * DPY, 2.30417297573763929e-05 * DPY, 2.85885980666130812e-04 * SOLAR_MASS),
    new Planet(1.28943695621391310e+01, -1.51111514016986312e+01, -2.23307578892655734e-01, 2.96460137564761618e-03 * DPY, 2.37847173959480950e-03 * DPY, -2.96589568540237556e-05 * DPY, 4.36624404335156298e-05 * SOLAR_MASS),
    new Planet(1.53796971148509165e+01, -2.59193146099879641e+01, 1.79258772950371181e-01, 2.68067772490389322e-03 * DPY, 1.62824170038242295e-03 * DPY, -9.51592254519715870e-05 * DPY, 5.15138902046611451e-05 * SOLAR_MASS)];
  var px = 0.0, py = 0.0, pz = 0.0;
  for i in 0..<5 {
    px += bodies[i].vx * bodies[i].mass; py += bodies[i].vy * bodies[i].mass; pz += bodies[i].vz * bodies[i].mass;
  }
  bodies[0].vx = -px / SOLAR_MASS; bodies[0].vy = -py / SOLAR_MASS; bodies[0].vz = -pz / SOLAR_MASS;
  writef("%.9dr\n", energy(bodies));
  for 1..n do advance(bodies, 0.01);
  writef("%.9dr\n", energy(bodies));
}

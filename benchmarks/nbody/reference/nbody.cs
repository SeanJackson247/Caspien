using System;
using System.Globalization;

public static class NBody
{
    const double PI = 3.141592653589793, SOLAR = 4 * PI * PI, DPY = 365.24;
    sealed class Body
    {
        public double x, y, z, vx, vy, vz, mass;
        public Body(double x, double y, double z, double vx, double vy, double vz, double mass)
        { this.x = x; this.y = y; this.z = z; this.vx = vx; this.vy = vy; this.vz = vz; this.mass = mass; }
    }
    static Body[] b = {
        new Body(0,0,0,0,0,0,SOLAR),
        new Body(4.84143144246472090e+00,-1.16032004402742839e+00,-1.03622044471123109e-01,1.66007664274403694e-03*DPY,7.69901118419740425e-03*DPY,-6.90460016972063023e-05*DPY,9.54791938424326609e-04*SOLAR),
        new Body(8.34336671824457987e+00,4.12479856412430479e+00,-4.03523417114321381e-01,-2.76742510726862411e-03*DPY,4.99852801234917238e-03*DPY,2.30417297573763929e-05*DPY,2.85885980666130812e-04*SOLAR),
        new Body(1.28943695621391310e+01,-1.51111514016986312e+01,-2.23307578892655734e-01,2.96460137564761618e-03*DPY,2.37847173959480950e-03*DPY,-2.96589568540237556e-05*DPY,4.36624404335156298e-05*SOLAR),
        new Body(1.53796971148509165e+01,-2.59193146099879641e+01,1.79258772950371181e-01,2.68067772490389322e-03*DPY,1.62824170038242295e-03*DPY,-9.51592254519715870e-05*DPY,5.15138902046611451e-05*SOLAR)};

    static void Advance(double dt)
    {
        for (int i = 0; i < 5; i++)
            for (int j = i + 1; j < 5; j++)
            {
                Body p = b[i], q = b[j];
                double dx = p.x - q.x, dy = p.y - q.y, dz = p.z - q.z;
                double d2 = dx * dx + dy * dy + dz * dz; double mag = dt / (d2 * Math.Sqrt(d2));
                p.vx -= dx * q.mass * mag; p.vy -= dy * q.mass * mag; p.vz -= dz * q.mass * mag;
                q.vx += dx * p.mass * mag; q.vy += dy * p.mass * mag; q.vz += dz * p.mass * mag;
            }
        foreach (Body p in b) { p.x += dt * p.vx; p.y += dt * p.vy; p.z += dt * p.vz; }
    }

    static double Energy()
    {
        double e = 0;
        for (int i = 0; i < 5; i++)
        {
            Body p = b[i];
            e += 0.5 * p.mass * (p.vx * p.vx + p.vy * p.vy + p.vz * p.vz);
            for (int j = i + 1; j < 5; j++)
            {
                Body q = b[j];
                double dx = p.x - q.x, dy = p.y - q.y, dz = p.z - q.z;
                e -= p.mass * q.mass / Math.Sqrt(dx * dx + dy * dy + dz * dz);
            }
        }
        return e;
    }

    public static void Main(string[] a)
    {
        long n = long.Parse(a[0], CultureInfo.InvariantCulture);
        double px = 0, py = 0, pz = 0;
        foreach (Body p in b) { px += p.vx * p.mass; py += p.vy * p.mass; pz += p.vz * p.mass; }
        b[0].vx = -px / SOLAR; b[0].vy = -py / SOLAR; b[0].vz = -pz / SOLAR;
        Console.Out.Write(Energy().ToString("F9", CultureInfo.InvariantCulture) + "\n");
        for (long i = 0; i < n; i++) Advance(0.01);
        Console.Out.Write(Energy().ToString("F9", CultureInfo.InvariantCulture) + "\n");
    }
}

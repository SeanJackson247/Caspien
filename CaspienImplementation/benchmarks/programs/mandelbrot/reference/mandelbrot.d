// Mandelbrot escape-time benchmark (port of mandelbrot.c).
import core.stdc.stdio;
import std.conv : to;

int main(string[] args) {
    long n = args.length > 1 ? args[1].to!long : 3000L;
    double dn = cast(double) n;
    long inside = 0, total = 0;
    for (long y = 0; y < n; y++) {
        double ci = 2.0 * cast(double) y / dn - 1.0;
        for (long x = 0; x < n; x++) {
            double cr = 2.0 * cast(double) x / dn - 1.5;
            double zr = 0.0, zi = 0.0, tr = 0.0, ti = 0.0;
            int i = 0;
            while (i < 100 && tr + ti <= 4.0) {
                zi = 2.0 * zr * zi + ci;
                zr = tr - ti + cr;
                tr = zr * zr;
                ti = zi * zi;
                i++;
            }
            total += i;
            if (i == 100) inside++;
        }
    }
    printf("%ld %ld\n", inside, total);
    return 0;
}

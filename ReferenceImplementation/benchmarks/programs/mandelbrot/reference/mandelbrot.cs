using System;
using System.Globalization;

public static class Mandelbrot
{
    public static void Main(string[] args)
    {
        long n = args.Length > 0 ? long.Parse(args[0], CultureInfo.InvariantCulture) : 3000L;
        double dn = (double)n;
        long inside = 0, total = 0;
        for (long y = 0; y < n; y++)
        {
            double ci = 2.0 * (double)y / dn - 1.0;
            for (long x = 0; x < n; x++)
            {
                double cr = 2.0 * (double)x / dn - 1.5;
                double zr = 0.0, zi = 0.0, tr = 0.0, ti = 0.0;
                int i = 0;
                while (i < 100 && tr + ti <= 4.0)
                {
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
        Console.Out.Write(inside + " " + total + "\n");
    }
}

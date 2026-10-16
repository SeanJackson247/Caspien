// Mandelbrot escape-time benchmark, see mandelbrot.c.
proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 3000;
  const dn = n: real;
  var inside = 0, total = 0;
  for y in 0..<n {
    const ci = 2.0 * (y: real) / dn - 1.0;
    for x in 0..<n {
      const cr = 2.0 * (x: real) / dn - 1.5;
      var zr = 0.0, zi = 0.0, tr = 0.0, ti = 0.0;
      var i = 0;
      while i < 100 && tr + ti <= 4.0 {
        zi = 2.0 * zr * zi + ci;
        zr = tr - ti + cr;
        tr = zr * zr;
        ti = zi * zi;
        i += 1;
      }
      total += i;
      if i == 100 then inside += 1;
    }
  }
  writeln(inside, " ", total);
}

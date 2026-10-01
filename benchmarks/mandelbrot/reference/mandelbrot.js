// Mandelbrot escape-time benchmark (Node / Bun): same grid, maths and output as mandelbrot.c.
const n = process.argv.length > 2 ? parseInt(process.argv[2]) : 3000;
const dn = n;
let inside = 0, total = 0;
for (let y = 0; y < n; y++) {
  const ci = 2.0 * y / dn - 1.0;
  for (let x = 0; x < n; x++) {
    const cr = 2.0 * x / dn - 1.5;
    let zr = 0.0, zi = 0.0, tr = 0.0, ti = 0.0, i = 0;
    while (i < 100 && tr + ti <= 4.0) {
      zi = 2.0 * zr * zi + ci;
      zr = tr - ti + cr;
      tr = zr * zr;
      ti = zi * zi;
      i++;
    }
    total += i;
    if (i === 100) inside++;
  }
}
console.log(inside + " " + total);

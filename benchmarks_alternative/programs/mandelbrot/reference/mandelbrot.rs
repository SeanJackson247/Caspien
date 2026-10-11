// Mandelbrot escape-time benchmark (Rust): same grid, maths and output as mandelbrot.c. No allocation.
use std::env;
fn main() {
    let n: i64 = env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(3000);
    let dn = n as f64;
    let (mut inside, mut total) = (0i64, 0i64);
    for y in 0..n {
        let ci = 2.0 * (y as f64) / dn - 1.0;
        for x in 0..n {
            let cr = 2.0 * (x as f64) / dn - 1.5;
            let (mut zr, mut zi, mut tr, mut ti) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
            let mut i = 0;
            while i < 100 && tr + ti <= 4.0 {
                zi = 2.0 * zr * zi + ci;
                zr = tr - ti + cr;
                tr = zr * zr;
                ti = zi * zi;
                i += 1;
            }
            total += i;
            if i == 100 { inside += 1; }
        }
    }
    println!("{} {}", inside, total);
}

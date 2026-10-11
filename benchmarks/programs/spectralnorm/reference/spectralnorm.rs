// spectral-norm, single threaded. Same algorithm as spectralnorm-gcc-1 of the Computer Language Benchmarks Game.
fn eval_a(i: usize, j: usize) -> f64 { 1.0 / (((i + j) * (i + j + 1) / 2 + i + 1) as f64) }

fn a_times_u(u: &[f64], au: &mut [f64]) {
    for i in 0..u.len() { let mut s = 0.0; for j in 0..u.len() { s += eval_a(i, j) * u[j]; } au[i] = s; }
}
fn at_times_u(u: &[f64], au: &mut [f64]) {
    for i in 0..u.len() { let mut s = 0.0; for j in 0..u.len() { s += eval_a(j, i) * u[j]; } au[i] = s; }
}
fn ata_times_u(u: &[f64], atau: &mut [f64]) {
    let mut v = vec![0.0; u.len()];
    a_times_u(u, &mut v);
    at_times_u(&v, atau);
}

fn main() {
    let n: usize = std::env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(100);
    let mut u = vec![1.0f64; n];
    let mut v = vec![0.0f64; n];
    for _ in 0..10 { ata_times_u(&u, &mut v); ata_times_u(&v, &mut u); }
    let (mut vbv, mut vv) = (0.0, 0.0);
    for i in 0..n { vbv += u[i] * v[i]; vv += v[i] * v[i]; }
    println!("{:.9}", (vbv / vv).sqrt());
}

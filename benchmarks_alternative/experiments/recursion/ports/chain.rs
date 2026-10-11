use std::env;
#[inline(never)]
fn chain(acc: u64, i: u64, n: u64) -> u64 { if i >= n { return acc; } chain((acc.wrapping_mul(31)) ^ i, i + 1, n) }
fn chain_loop(mut acc: u64, mut i: u64, n: u64) -> u64 { while i < n { acc = acc.wrapping_mul(31) ^ i; i += 1; } acc }
fn main() {
    let a: Vec<String> = env::args().collect();
    let depth: u64 = a[1].parse().unwrap(); let reps: u64 = a[2].parse().unwrap();
    let lp = a.len() > 3 && a[3] == "loop"; let mut total = 0u64;
    for rep in 0..reps { total ^= if lp { chain_loop(rep, 0, depth) } else { chain(rep, 0, depth) }; }
    println!("{}", total);
}

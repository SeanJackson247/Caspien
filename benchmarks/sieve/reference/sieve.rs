// Sieve of Eratosthenes (Rust). Build with --cfg leak to leak the buffer instead of dropping it.
use std::env;
fn main() {
    let n: usize = env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(100_000_000);
    let mut f = vec![1u8; n + 1];
    let mut i = 2usize;
    while i * i <= n {
        if f[i] != 0 {
            let mut j = i * i;
            while j <= n { f[j] = 0; j += i; }
        }
        i += 1;
    }
    let mut count: u64 = 0;
    for i in 2..=n { count += f[i] as u64; }
    println!("{}", count);
    #[cfg(leak)]
    std::mem::forget(f);
}

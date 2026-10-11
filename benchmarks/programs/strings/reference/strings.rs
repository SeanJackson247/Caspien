// String manipulation benchmark (Rust, Vec<u8>). --cfg leak forgets every buffer instead of dropping it.
use std::env;
struct Lcg(u32);
impl Lcg { fn next(&mut self) -> u32 { self.0 = self.0.wrapping_mul(1664525).wrapping_add(1013904223); self.0 } }
fn hash(b: &[u8]) -> u32 { let mut h: u32 = 7; for &c in b { h = h.wrapping_mul(31).wrapping_add(c as u32); } h }
fn main() {
    let n: usize = env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(4_000_000);
    let mut g = Lcg(12345);
    let mut text = vec![0u8; n];
    for i in 0..n { let r = (g.next() >> 16) % 27; text[i] = if r == 26 { b' ' } else { b'a' + r as u8 }; }
    let (mut words, mut longest, mut cur) = (0u64, 0u64, 0u64);
    for i in 0..n {
        if text[i] == b' ' { if cur > 0 { words += 1; if cur > longest { longest = cur; } cur = 0; } } else { cur += 1; }
    }
    if cur > 0 { words += 1; if cur > longest { longest = cur; } }
    let mut abc = 0u64;
    for i in 0..n.saturating_sub(2) { if text[i] == b'a' && text[i + 1] == b'b' && text[i + 2] == b'c' { abc += 1; } }
    let mut rev = vec![0u8; n];
    for i in 0..n { rev[i] = text[n - 1 - i]; }
    let hrev = hash(&rev);
    let es = text.iter().filter(|&&c| c == b'e').count();
    let m = n + es;
    let mut rep = vec![0u8; m];
    let mut k = 0;
    for i in 0..n { if text[i] == b'e' { rep[k] = b'3'; rep[k + 1] = b'3'; k += 2; } else { rep[k] = text[i]; k += 1; } }
    let hrep = hash(&rep);
    let mut up = vec![0u8; n];
    for i in 0..n { up[i] = if text[i] == b' ' { b' ' } else { text[i] - 32 }; }
    let hup = hash(&up);
    println!("{} {} {} {} {} {} {}", words, longest, abc, hrev, m, hrep, hup);
    #[cfg(leak)]
    { std::mem::forget(text); std::mem::forget(rev); std::mem::forget(rep); std::mem::forget(up); }
}

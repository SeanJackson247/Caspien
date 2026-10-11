// k-nucleotide benchmark: same sequence, k-mers and output as knucleotide.c (see there), but the counting uses the language's own hash map keyed by the packed 2-bit k-mer.
// Rust: std::collections::HashMap<u64,u32> (default SipHash). --cfg leak forgets the maps and the sequence instead of dropping them.
use std::collections::HashMap;
use std::env;
const KS: [usize; 6] = [1, 2, 3, 4, 6, 12];
const QK: [usize; 11] = [1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12];
const QV: [u64; 11] = [0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487];
fn main() {
    let n: usize = env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(30_000_000);
    let mut last: u32 = 42;
    let mut seq = vec![0u8; n + 1];
    for i in 0..n {
        last = (last * 3877 + 29573) % 139968;
        seq[i] = if last < 42404 { 0 } else if last < 70117 { 1 } else if last < 97767 { 2 } else { 3 };
    }
    let mut first12: u64 = 0;
    for i in 0..12.min(n) { first12 = (first12 << 2) | seq[i] as u64; }
    let mut distinct = [0u64; 6];
    let mut counts: Vec<u64> = Vec::new();
    for ki in 0..6 {
        let k = KS[ki];
        let mut m: HashMap<u64, u32> = HashMap::new();
        let mask: u64 = (1u64 << (2 * k)) - 1;
        let mut key: u64 = 0;
        for i in 0..n {
            key = ((key << 2) | seq[i] as u64) & mask;
            if i + 1 >= k { *m.entry(key).or_insert(0) += 1; }
        }
        distinct[ki] = m.len() as u64;
        for q in 0..11 {
            if QK[q] != k { continue; }
            counts.push(*m.get(&QV[q]).unwrap_or(&0) as u64);
        }
        if k == 12 { counts.push(*m.get(&first12).unwrap_or(&0) as u64); }
        #[cfg(leak)]
        std::mem::forget(m);
    }
    let mut out = String::new();
    for d in distinct.iter() { out.push_str(&format!("{} ", d)); }
    let c: Vec<String> = counts.iter().map(|x| x.to_string()).collect();
    out.push_str(&c.join(" "));
    println!("{}", out);
    #[cfg(leak)]
    std::mem::forget(seq);
}

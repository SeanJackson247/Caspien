// FASTA benchmark (Rust, Vec<u8> buffer). --cfg leak forgets the buffer instead of dropping it. Same algorithm and output as fasta.c.
use std::env;
const ALU: &[u8] = b"GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA";
const CHARS: &[u8] = b"ACGTBDHKMNRSVWYACGT";
const THR: [u32; 19] = [37792,54588,71384,109176,111975,114774,117574,120373,123172,125972,128771,131570,134370,137169,139968,42404,70117,97767,139968];
const HDR: [&[u8]; 3] = [b">ONE Homo sapiens alu\n", b">TWO IUB ambiguity codes\n", b">THREE Homo sapiens frequency\n"];
fn main() {
    let n: usize = env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(40_000_000);
    let cnt = [n * 2 / 10, n * 3 / 10, n - n * 2 / 10 - n * 3 / 10];
    let off = [0usize, 0, 15];
    let mut last: u32 = 42;
    let mut buf: Vec<u8> = vec![0u8; n + n / 60 + 1024];
    let (mut pos, mut a, mut c, mut g, mut t, mut other, mut ai) = (0usize, 0u64, 0u64, 0u64, 0u64, 0u64, 0usize);
    for s in 0..3 {
        buf[pos..pos + HDR[s].len()].copy_from_slice(HDR[s]);
        pos += HDR[s].len();
        let mut col = 0;
        for _ in 0..cnt[s] {
            let ch: u8;
            if s == 0 {
                ch = ALU[ai];
                ai += 1;
                if ai == 287 { ai = 0; }
            } else {
                last = (last * 3877 + 29573) % 139968;
                let mut j = off[s];
                while last >= THR[j] { j += 1; }
                ch = CHARS[j];
            }
            buf[pos] = ch;
            pos += 1;
            match ch { b'A' => a += 1, b'C' => c += 1, b'G' => g += 1, b'T' => t += 1, _ => other += 1 }
            col += 1;
            if col == 60 { buf[pos] = b'\n'; pos += 1; col = 0; }
        }
        if col > 0 { buf[pos] = b'\n'; pos += 1; }
    }
    let mut h: u32 = 7;
    for i in 0..pos { h = h.wrapping_mul(31).wrapping_add(buf[i] as u32); }
    println!("{} {} {} {} {} {} {}", pos, h, a, c, g, t, other);
    #[cfg(leak)]
    std::mem::forget(buf);
}

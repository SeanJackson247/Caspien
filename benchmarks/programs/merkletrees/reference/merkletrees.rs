// Merkle tree benchmark (Rust, real SHA-256): same algorithm as merkletrees.c. Default: Vecs dropped; --cfg leak: forgotten.
use std::env;
const K: [u32; 64] = [
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
const H0: [u32; 8] = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
fn compress(st: &mut [u32; 8], b: &[u32; 16]) {
    let mut w = [0u32; 64];
    w[..16].copy_from_slice(b);
    for i in 16..64 {
        let s0 = w[i-15].rotate_right(7) ^ w[i-15].rotate_right(18) ^ (w[i-15] >> 3);
        let s1 = w[i-2].rotate_right(17) ^ w[i-2].rotate_right(19) ^ (w[i-2] >> 10);
        w[i] = w[i-16].wrapping_add(s0).wrapping_add(w[i-7]).wrapping_add(s1);
    }
    let [mut a, mut bb, mut c, mut d, mut e, mut f, mut g, mut h] = *st;
    for i in 0..64 {
        let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
        let ch = (e & f) ^ (!e & g);
        let t1 = h.wrapping_add(s1).wrapping_add(ch).wrapping_add(K[i]).wrapping_add(w[i]);
        let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
        let mj = (a & bb) ^ (a & c) ^ (bb & c);
        let t2 = s0.wrapping_add(mj);
        h = g; g = f; f = e; e = d.wrapping_add(t1); d = c; c = bb; bb = a; a = t1.wrapping_add(t2);
    }
    for (s, v) in st.iter_mut().zip([a, bb, c, d, e, f, g, h]) { *s = s.wrapping_add(v); }
}
fn leaf(out: &mut [u32], dlo: u32, dhi: u32, i: u64) {
    let b = [dlo.swap_bytes(), dhi.swap_bytes(), (i as u32).swap_bytes(), ((i >> 32) as u32).swap_bytes(), 0x80000000, 0,0,0,0,0,0,0,0,0,0, 128];
    let mut st = H0;
    compress(&mut st, &b);
    out[..8].copy_from_slice(&st);
}
fn node(out: &mut [u32], l: &[u32], r: &[u32]) {
    let mut b = [0u32; 16];
    b[..8].copy_from_slice(&l[..8]);
    b[8..].copy_from_slice(&r[..8]);
    let mut st = H0;
    compress(&mut st, &b);
    let p = [0x80000000, 0,0,0,0,0,0,0,0,0,0,0,0,0,0, 512];
    compress(&mut st, &p);
    out[..8].copy_from_slice(&st);
}
fn main() {
    let n: usize = env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(140000);
    let mut x: u32 = 12345;
    let mut next = || { x = x.wrapping_mul(1664525).wrapping_add(1013904223); x };
    let mut data = vec![0u32; n];
    let mut t = vec![0u32; (2 * n + 64) * 8];
    for i in 0..n { data[i] = next(); leaf(&mut t[8 * i..8 * i + 8], data[i], 0, i as u64); }
    let (mut off, mut size) = (0usize, n);
    let mut tmp = [0u32; 8];
    while size > 1 {
        let noff = off + size;
        let ns = (size + 1) / 2;
        for j in 0..ns {
            let li = off + 2 * j;
            let ri = if 2 * j + 1 < size { li + 1 } else { li };
            node(&mut tmp, &t[8 * li..8 * li + 8], &t[8 * ri..8 * ri + 8]);
            t[8 * (noff + j)..8 * (noff + j) + 8].copy_from_slice(&tmp);
        }
        off = noff; size = ns;
    }
    let mut root = [0u32; 8];
    root.copy_from_slice(&t[8 * off..8 * off + 8]);
    let (mut verified, mut rejected) = (0u64, 0u64);
    let mut sibs = [0u32; 64 * 8];
    let mut cur = [0u32; 8];
    for p in 0..n / 2 {
        let idx = (next() as usize) % n;
        let (mut o, mut s, mut pos, mut depth) = (0usize, n, idx, 0usize);
        while s > 1 {
            let mut sb = if pos % 2 == 0 { pos + 1 } else { pos - 1 };
            if sb >= s { sb = pos; }
            sibs[depth * 8..depth * 8 + 8].copy_from_slice(&t[8 * (o + sb)..8 * (o + sb) + 8]);
            depth += 1;
            o += s; s = (s + 1) / 2; pos /= 2;
        }
        let mut dlo = data[idx];
        let mut dhi = 0u32;
        if p % 4 == 3 { dlo = dlo.wrapping_add(1); dhi = (dlo == 0) as u32; }
        leaf(&mut cur, dlo, dhi, idx as u64);
        pos = idx;
        for d in 0..depth {
            let c = cur;
            if pos % 2 == 0 { node(&mut cur, &c, &sibs[d * 8..d * 8 + 8]); } else { node(&mut cur, &sibs[d * 8..d * 8 + 8], &c); }
            pos /= 2;
        }
        if cur == root { verified += 1; } else { rejected += 1; }
    }
    for k in 0..8 { print!("{:08x}", root[k]); }
    println!(" {} {}", verified, rejected);
    #[cfg(leak)]
    { std::mem::forget(data); std::mem::forget(t); }
}

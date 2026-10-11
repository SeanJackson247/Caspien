// JSON serialise + parse benchmark (Rust). Same hand-written serialiser / iterative parser / checksum as json_serde.c (see there).
// Vec<u8> / Vec<Rec> buffers; --cfg leak forgets every buffer instead of dropping it.
use std::env;
#[derive(Clone, Copy, Default)]
struct Rec { id: u64, name_off: u64, name_len: u64, score: u64, tag_off: u64, tag_cnt: u64 }
struct Lcg(u32);
impl Lcg { fn next(&mut self) -> u32 { self.0 = self.0.wrapping_mul(1664525).wrapping_add(1013904223); self.0 } }
fn wnum(t: &mut [u8], mut p: usize, mut v: u64) -> usize {
    let mut d = 1usize; let mut u = v; while u >= 10 { u /= 10; d += 1; }
    for k in (0..d).rev() { t[p + k] = b'0' + (v % 10) as u8; v /= 10; }
    p += d; p
}
fn wlit(t: &mut [u8], mut p: usize, s: &[u8]) -> usize { for &c in s { t[p] = c; p += 1; } p }
fn main() {
    let n: usize = env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(4_000_000);
    let mut g = Lcg(12345);
    let mut text = vec![0u8; n * 80 + 16];
    let mut p = 0usize;
    text[p] = b'['; p += 1;
    for i in 0..n {
        if i > 0 { text[p] = b','; p += 1; }
        p = wlit(&mut text, p, b"{\"id\":"); p = wnum(&mut text, p, i as u64);
        p = wlit(&mut text, p, b",\"name\":\"");
        let nl = 3 + (g.next() >> 16) % 8;
        for _ in 0..nl { text[p] = b'a' + ((g.next() >> 16) % 26) as u8; p += 1; }
        p = wlit(&mut text, p, b"\",\"score\":");
        let cents = ((g.next() >> 8) % 1000000) as u64;
        p = wnum(&mut text, p, cents / 100); text[p] = b'.'; p += 1;
        text[p] = b'0' + (cents % 100 / 10) as u8; p += 1; text[p] = b'0' + (cents % 10) as u8; p += 1;
        p = wlit(&mut text, p, b",\"tags\":[");
        let tc = (g.next() >> 16) % 5;
        for k in 0..tc { if k > 0 { text[p] = b','; p += 1; } p = wnum(&mut text, p, ((g.next() >> 16) % 100) as u64); }
        p = wlit(&mut text, p, b"]}");
    }
    text[p] = b']'; p += 1;
    let tlen = p;
    let mut recs = vec![Rec::default(); n + 1];
    let mut names = vec![0u8; n * 10 + 8];
    let mut tags = vec![0u8; n * 4 + 8];
    let (mut count, mut noff, mut toff, mut q) = (0usize, 0usize, 0usize, 1usize);
    loop {
        let c = text[q];
        if c == b']' { break; }
        if c == b',' { q += 1; continue; }
        q += 1;
        let mut r = Rec::default();
        loop {
            q += 1;
            let k0 = text[q];
            while text[q] != b'"' { q += 1; }
            q += 2;
            if k0 == b'i' {
                let mut v = 0u64; while text[q] >= b'0' && text[q] <= b'9' { v = v * 10 + (text[q] - b'0') as u64; q += 1; }
                r.id = v;
            } else if k0 == b'n' {
                q += 1;
                r.name_off = noff as u64;
                while text[q] != b'"' { names[noff] = text[q]; noff += 1; q += 1; }
                r.name_len = noff as u64 - r.name_off;
                q += 1;
            } else if k0 == b's' {
                let mut v = 0u64; while text[q] >= b'0' && text[q] <= b'9' { v = v * 10 + (text[q] - b'0') as u64; q += 1; }
                q += 1;
                v = v * 100 + (text[q] - b'0') as u64 * 10 + (text[q + 1] - b'0') as u64; q += 2;
                r.score = v;
            } else {
                q += 1;
                r.tag_off = toff as u64;
                while text[q] != b']' {
                    if text[q] == b',' { q += 1; }
                    let mut v = 0u64; while text[q] >= b'0' && text[q] <= b'9' { v = v * 10 + (text[q] - b'0') as u64; q += 1; }
                    tags[toff] = v as u8; toff += 1;
                }
                r.tag_cnt = toff as u64 - r.tag_off;
                q += 1;
            }
            if text[q] == b',' { q += 1; } else { q += 1; break; }
        }
        recs[count] = r; count += 1;
    }
    let mut h: u32 = 7;
    for i in 0..count {
        let r = recs[i];
        h = h.wrapping_mul(31).wrapping_add(r.id as u32);
        h = h.wrapping_mul(31).wrapping_add(r.score as u32);
        h = h.wrapping_mul(31).wrapping_add(r.name_len as u32);
        for k in 0..r.name_len { h = h.wrapping_mul(31).wrapping_add(names[(r.name_off + k) as usize] as u32); }
        h = h.wrapping_mul(31).wrapping_add(r.tag_cnt as u32);
        for k in 0..r.tag_cnt { h = h.wrapping_mul(31).wrapping_add(tags[(r.tag_off + k) as usize] as u32); }
    }
    println!("{} {} {}", count, tlen, h);
    #[cfg(leak)]
    { std::mem::forget(text); std::mem::forget(recs); std::mem::forget(names); std::mem::forget(tags); }
}

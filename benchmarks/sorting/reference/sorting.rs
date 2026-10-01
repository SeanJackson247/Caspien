// Sorting and searching benchmark (Rust): same algorithms as sorting.c, on Vec<i32>. Vecs drop at the end; --cfg leak forgets them.
use std::env;
struct Rng(u32);
impl Rng { fn next(&mut self) -> u32 { self.0 = self.0.wrapping_mul(1664525).wrapping_add(1013904223); self.0 } }

fn quicksort(a: &mut [i32]) {
    let n = a.len() as i64;
    let mut stack = [0i64; 128]; let mut sp = 0;
    stack[sp] = 0; sp += 1; stack[sp] = n - 1; sp += 1;
    while sp > 0 {
        sp -= 1; let mut hi = stack[sp]; sp -= 1; let mut lo = stack[sp];
        while lo < hi {
            let pivot = a[((lo + hi) / 2) as usize];
            let (mut i, mut j) = (lo, hi);
            while i <= j {
                while a[i as usize] < pivot { i += 1; }
                while a[j as usize] > pivot { j -= 1; }
                if i <= j { a.swap(i as usize, j as usize); i += 1; j -= 1; }
            }
            if j - lo < hi - i { stack[sp] = i; sp += 1; stack[sp] = hi; sp += 1; hi = j; }
            else { stack[sp] = lo; sp += 1; stack[sp] = j; sp += 1; lo = i; }
        }
    }
}
fn mergesort(a: &mut Vec<i32>, tmp: &mut Vec<i32>) {
    let n = a.len();
    let mut w = 1;
    let mut in_a = true;
    while w < n {
        {
            let (src, dst): (&Vec<i32>, &mut Vec<i32>) = if in_a { (&*a, &mut *tmp) } else { (&*tmp, &mut *a) };
            let mut lo = 0;
            while lo < n {
                let mid = if lo + w < n { lo + w } else { n };
                let hi = if lo + 2 * w < n { lo + 2 * w } else { n };
                let (mut i, mut j, mut k) = (lo, mid, lo);
                while i < mid && j < hi { if src[i] <= src[j] { dst[k] = src[i]; i += 1; } else { dst[k] = src[j]; j += 1; } k += 1; }
                while i < mid { dst[k] = src[i]; i += 1; k += 1; }
                while j < hi { dst[k] = src[j]; j += 1; k += 1; }
                lo += 2 * w;
            }
        }
        in_a = !in_a;
        w *= 2;
    }
    if !in_a { a.copy_from_slice(tmp); }
}
fn siftdown(a: &mut [i32], mut root: usize, n: usize) {
    loop {
        let mut c = 2 * root + 1;
        if c >= n { break; }
        if c + 1 < n && a[c + 1] > a[c] { c += 1; }
        if a[root] >= a[c] { break; }
        a.swap(root, c);
        root = c;
    }
}
fn heapsort(a: &mut [i32]) {
    let n = a.len();
    let mut i = n / 2;
    while i > 0 { i -= 1; siftdown(a, i, n); }
    let mut e = n - 1;
    while e > 0 { a.swap(0, e); siftdown(a, 0, e); e -= 1; }
}
fn bsearch_has(a: &[i32], key: i32) -> bool {
    let (mut lo, mut hi) = (0usize, a.len());
    while lo < hi { let m = (lo + hi) / 2; if a[m] < key { lo = m + 1; } else { hi = m; } }
    lo < a.len() && a[lo] == key
}
fn main() {
    let n: usize = env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(2_000_000);
    let mut r = Rng(12345);
    let orig: Vec<i32> = (0..n).map(|_| (r.next() >> 1) as i32).collect();
    let mut a = orig.clone(); let mut b = orig.clone(); let mut d = orig.clone();
    let mut tmp = vec![0i32; n];
    quicksort(&mut a);
    mergesort(&mut b, &mut tmp);
    heapsort(&mut d);
    let mut h: u32 = 0; let mut ok = true;
    for i in 0..n {
        h = h.wrapping_mul(31).wrapping_add(a[i] as u32);
        if a[i] != b[i] || a[i] != d[i] { ok = false; }
        if i > 0 && a[i - 1] > a[i] { ok = false; }
    }
    let mut bs = 0u64;
    for k in 0..n {
        let mut key = (r.next() >> 1) as i32;
        if k % 2 == 0 { key = orig[(key as u32 as usize) % n]; }
        if bsearch_has(&a, key) { bs += 1; }
    }
    let mut ls = 0u64;
    for k in 0..20 {
        let mut key = (r.next() >> 1) as i32;
        if k % 2 == 0 { key = orig[(key as u32 as usize) % n]; }
        if orig.iter().any(|&v| v == key) { ls += 1; }
    }
    println!("{} {} {} {}", h, bs, ls, if ok { "OK" } else { "BAD" });
    #[cfg(leak)]
    { std::mem::forget(orig); std::mem::forget(a); std::mem::forget(b); std::mem::forget(d); std::mem::forget(tmp); }
}

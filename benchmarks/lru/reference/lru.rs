// LRU cache benchmark (Rust): same algorithm as lru.c (capacity 262144, 1048576 keys, chained hash threaded through a node pool, index-based
// doubly linked recency list in Vecs). Default: the Vecs are dropped at the end; --cfg leak: they are forgotten. Prints: hits misses checksum.
use std::env;
const CAP: u32 = 262144;
const KEYS: u32 = 1048576;
const HOT: u32 = 131072;
const NB: usize = 524288;
const NONE: u32 = 0xFFFF_FFFF;
fn hsh(k: u32) -> usize { ((((k as u64) * 2654435761u64) & 0xFFFF_FFFF) >> 13) as usize }
struct Lru { key: Vec<u32>, val: Vec<u32>, prv: Vec<u32>, nxt: Vec<u32>, hn: Vec<u32>, bucket: Vec<u32>, head: u32, tail: u32 }
impl Lru {
    fn unlink(&mut self, i: u32) {
        let (p, n) = (self.prv[i as usize], self.nxt[i as usize]);
        if p != NONE { self.nxt[p as usize] = n; } else { self.head = n; }
        if n != NONE { self.prv[n as usize] = p; } else { self.tail = p; }
    }
    fn push_front(&mut self, i: u32) {
        self.prv[i as usize] = NONE; self.nxt[i as usize] = self.head;
        if self.head != NONE { let h = self.head as usize; self.prv[h] = i; } else { self.tail = i; }
        self.head = i;
    }
    fn find(&self, k: u32) -> u32 {
        let mut i = self.bucket[hsh(k)];
        while i != NONE && self.key[i as usize] != k { i = self.hn[i as usize]; }
        i
    }
    fn hash_remove(&mut self, i: u32) {
        let b = hsh(self.key[i as usize]);
        if self.bucket[b] == i { self.bucket[b] = self.hn[i as usize]; return; }
        let mut j = self.bucket[b];
        while self.hn[j as usize] != i { j = self.hn[j as usize]; }
        self.hn[j as usize] = self.hn[i as usize];
    }
}
fn main() {
    let n: usize = env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(20_000_000);
    let c = CAP as usize;
    let mut l = Lru { key: vec![0; c], val: vec![0; c], prv: vec![0; c], nxt: vec![0; c], hn: vec![0; c], bucket: vec![NONE; NB], head: NONE, tail: NONE };
    let mut x: u32 = 12345;
    let mut next = || { x = x.wrapping_mul(1664525).wrapping_add(1013904223); x };
    let mut size: u32 = 0;
    let (mut hits, mut misses) = (0u64, 0u64);
    let mut sum: u32 = 0;
    for _ in 0..n {
        let a = next(); let y = next();
        let range = if (y >> 20) % 4 == 0 { KEYS } else { HOT };
        let k = (a >> 8) % range;
        let mut i = l.find(k);
        if (y >> 24) % 4 != 0 {
            if i != NONE { hits += 1; sum = sum.wrapping_add(l.val[i as usize]).wrapping_add(k); l.unlink(i); l.push_front(i); }
            else { misses += 1; }
        } else if i != NONE {
            l.val[i as usize] = y; l.unlink(i); l.push_front(i);
        } else {
            if size == CAP {
                i = l.tail; sum = sum.wrapping_add(l.key[i as usize]); l.unlink(i); l.hash_remove(i);
            } else { i = size; size += 1; }
            l.key[i as usize] = k; l.val[i as usize] = y;
            let b = hsh(k); l.hn[i as usize] = l.bucket[b]; l.bucket[b] = i;
            l.push_front(i);
        }
    }
    let mut fin: u64 = 0;
    let mut i = l.head;
    while i != NONE { fin = (fin * 31 + l.key[i as usize] as u64) % 4294967296; i = l.nxt[i as usize]; }
    println!("{} {} {}", hits, misses, (sum as u64 + fin) % 4294967296);
    #[cfg(leak)]
    std::mem::forget(l);
}

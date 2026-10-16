// Heap graph benchmark (Rust): every node is its own Box, linked by raw pointers (a cyclic graph cannot be expressed with plain
// references). Default: the boxes are dropped at the end; --cfg leak: they are leaked.
use std::env;
struct Node { value: u32, dist: i32, e: [*mut Node; 4] }
fn main() {
    let n: usize = env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(2_000_000);
    let mut x: u32 = 12345;
    let mut next = || { x = x.wrapping_mul(1664525).wrapping_add(1013904223); x };
    let mut nodes: Vec<*mut Node> = Vec::with_capacity(n);
    for _ in 0..n {
        let v = next() & 0xFFFFFF;
        nodes.push(Box::into_raw(Box::new(Node { value: v, dist: -1, e: [std::ptr::null_mut(); 4] })));
    }
    unsafe {
        (*nodes[n * 7 / 10]).value = 0xFFFF_FFFF;
        for i in 0..n {
            (*nodes[i]).e[0] = nodes[(i + 1) % n];
            for k in 1..4 { let t = (next() as usize) % n; (*nodes[i]).e[k] = nodes[t]; }
        }
        let mut q: Vec<*mut Node> = vec![std::ptr::null_mut(); n];
        let (mut head, mut tail) = (0usize, 0usize);
        q[tail] = nodes[0]; tail += 1; (*nodes[0]).dist = 0;
        let (mut maxdepth, mut needledist) = (0i32, -1i32);
        let mut sum: u32 = 0;
        while head < tail {
            let u = q[head]; head += 1;
            sum = sum.wrapping_add((*u).value);
            if (*u).value == 0xFFFF_FFFF { needledist = (*u).dist; }
            if (*u).dist > maxdepth { maxdepth = (*u).dist; }
            for k in 0..4 { let v = (*u).e[k]; if (*v).dist < 0 { (*v).dist = (*u).dist + 1; q[tail] = v; tail += 1; } }
        }
        println!("{} {} {} {}", tail, maxdepth, needledist, sum);
        #[cfg(not(leak))]
        for &p in &nodes { drop(Box::from_raw(p)); }
    }
}

// Binary trees (Rust): ordinary heap nodes, Option<Box<Node>> children (pointer nodes, one allocation per node).
// Default: trees are dropped (freed); --cfg leak: they are mem::forget-ed, never freed.
use std::env;
struct Node { l: Option<Box<Node>>, r: Option<Box<Node>> }
fn make(d: i32) -> Box<Node> {
    if d > 0 { Box::new(Node { l: Some(make(d - 1)), r: Some(make(d - 1)) }) } else { Box::new(Node { l: None, r: None }) }
}
fn check(n: &Node) -> i64 { match (&n.l, &n.r) { (Some(l), Some(r)) => 1 + check(l) + check(r), _ => 1 } }
fn release(t: Box<Node>) {
    #[cfg(leak)]
    std::mem::forget(t);
    #[cfg(not(leak))]
    drop(t);
}
fn main() {
    let mut maxd: i32 = env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(16);
    if maxd < 6 { maxd = 6; }
    let t = make(maxd + 1);
    print!("{}", check(&t));
    release(t);
    let longlived = make(maxd);
    let mut d = 4;
    while d <= maxd {
        let iters: i64 = 1i64 << (maxd - d + 4);
        let mut sum: i64 = 0;
        for _ in 0..iters { let a = make(d); sum += check(&a); release(a); }
        print!(" {}", sum);
        d += 2;
    }
    println!(" {}", check(&longlived));
    release(longlived);
}

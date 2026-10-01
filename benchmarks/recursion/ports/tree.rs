use std::env;
fn visit(d: u64) -> u64 { let mut t = d + 1; if d > 0 { t += visit(d - 1); t += visit(d - 1); } t }
fn main() { let d: u64 = env::args().nth(1).unwrap().parse().unwrap(); println!("{}", visit(d)); }

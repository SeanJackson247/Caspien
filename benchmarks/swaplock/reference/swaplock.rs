// swaplock: see swaplock.c. Lock: std::sync::Mutex around the (count, sum) pair, shared through an Arc.
use std::sync::{Arc, Mutex};
use std::thread;
const THREADS: u64 = 32;

fn main() {
	let k: u64 = std::env::args().nth(1).map(|a| a.parse().unwrap()).unwrap_or(500000);
	let shared = Arc::new(Mutex::new((0u64, 0u64)));
	let hs: Vec<_> = (0..THREADS).map(|id| {
		let s = Arc::clone(&shared);
		thread::spawn(move || {
			for i in 0..k {
				let mut g = s.lock().unwrap();
				g.0 += 1;
				g.1 += id + (i % 7);
			}
		})
	}).collect();
	for h in hs { h.join().unwrap(); }
	let g = shared.lock().unwrap();
	println!("count={} sum={}", g.0, g.1);
}

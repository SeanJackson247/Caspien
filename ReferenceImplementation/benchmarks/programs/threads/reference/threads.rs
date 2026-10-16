// threads: see threads.c. thread::spawn returns the task's result through its JoinHandle.
use std::thread;
const WORK: u64 = 1000000;
const WAVE: u64 = 64;

fn task(id: u64) -> u64 {
	let mut s: u64 = (id + 1).wrapping_mul(2654435761);
	let mut acc: u64 = 0;
	for _ in 0..WORK {
		s ^= s << 13; s ^= s >> 7; s ^= s << 17;
		acc += s & 255;
	}
	acc
}

fn main() {
	let n: u64 = std::env::args().nth(1).map(|a| a.parse().unwrap()).unwrap_or(1024);
	let mut total: u64 = 0;
	let mut base = 0;
	while base < n {
		let hs: Vec<_> = (0..WAVE).map(|j| { let id = base + j; thread::spawn(move || task(id)) }).collect();
		for h in hs { total += h.join().unwrap(); }
		base += WAVE;
	}
	println!("tasks={} checksum={}", n, total);
}

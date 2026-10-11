// fannkuch-redux, single threaded. Same algorithm as fannkuchredux-gcc-1 of the Computer Language Benchmarks Game.
fn fannkuchredux(n: usize) -> i32 {
    let mut perm = [0usize; 16];
    let mut perm1 = [0usize; 16];
    let mut count = [0usize; 16];
    let mut max_flips: i32 = 0;
    let mut perm_count: i32 = 0;
    let mut checksum: i32 = 0;
    for i in 0..n { perm1[i] = i; }
    let mut r = n;
    loop {
        while r != 1 { count[r - 1] = r; r -= 1; }
        for i in 0..n { perm[i] = perm1[i]; }
        let mut flips: i32 = 0;
        loop {
            let k = perm[0];
            if k == 0 { break; }
            let k2 = (k + 1) >> 1;
            for i in 0..k2 { perm.swap(i, k - i); }
            flips += 1;
        }
        if flips > max_flips { max_flips = flips; }
        checksum += if perm_count % 2 == 0 { flips } else { -flips };
        loop {
            if r == n { println!("{}", checksum); return max_flips; }
            let perm0 = perm1[0];
            let mut i = 0;
            while i < r { let j = i + 1; perm1[i] = perm1[j]; i = j; }
            perm1[r] = perm0;
            count[r] -= 1;
            if count[r] > 0 { break; }
            r += 1;
        }
        perm_count += 1;
    }
}

fn main() {
    let n: usize = std::env::args().nth(1).map(|s| s.parse().unwrap()).unwrap_or(7);
    println!("Pfannkuchen({}) = {}", n, fannkuchredux(n));
}

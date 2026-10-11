package main

import "core:fmt"
import "core:os"
import "core:strconv"

arg_n :: proc(def: int) -> int {
	if len(os.args) > 1 {
		if v, ok := strconv.parse_int(os.args[1]); ok {
			return v
		}
	}
	return def
}

rng: u32 = 12345

next :: proc() -> u32 {
	rng = rng * 1664525 + 1013904223
	return rng
}

K := [64]u32 {
	0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
	0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
	0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
	0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
	0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
	0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
	0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
	0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
}
H0 := [8]u32{0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19}

ror :: #force_inline proc(v: u32, n: u32) -> u32 {
	return (v >> n) | (v << (32 - n))
}

compress :: proc(st: ^[8]u32, b: ^[16]u32) {
	w: [64]u32
	for i in 0 ..< 16 {w[i] = b[i]}
	for i in 16 ..< 64 {
		s0 := ror(w[i - 15], 7) ~ ror(w[i - 15], 18) ~ (w[i - 15] >> 3)
		s1 := ror(w[i - 2], 17) ~ ror(w[i - 2], 19) ~ (w[i - 2] >> 10)
		w[i] = w[i - 16] + s0 + w[i - 7] + s1
	}
	a, bb, c, d, e, f, g, h := st[0], st[1], st[2], st[3], st[4], st[5], st[6], st[7]
	for i in 0 ..< 64 {
		S1 := ror(e, 6) ~ ror(e, 11) ~ ror(e, 25)
		ch := (e & f) ~ (~e & g)
		t1 := h + S1 + ch + K[i] + w[i]
		S0 := ror(a, 2) ~ ror(a, 13) ~ ror(a, 22)
		mj := (a & bb) ~ (a & c) ~ (bb & c)
		t2 := S0 + mj
		h = g;g = f;f = e;e = d + t1;d = c;c = bb;bb = a;a = t1 + t2
	}
	st[0] += a;st[1] += bb;st[2] += c;st[3] += d
	st[4] += e;st[5] += f;st[6] += g;st[7] += h
}

bswap32 :: #force_inline proc(v: u32) -> u32 {
	return (v >> 24) | ((v >> 8) & 0xff00) | ((v << 8) & 0xff0000) | (v << 24)
}

leaf :: proc(out: ^[8]u32, dlo, dhi: u32, i: u64) {
	b := [16]u32{bswap32(dlo), bswap32(dhi), bswap32(u32(i)), bswap32(u32(i >> 32)), 0x80000000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 128}
	out^ = H0
	compress(out, &b)
}

node :: proc(out: ^[8]u32, l, r: ^[8]u32) {
	b: [16]u32
	st: [8]u32
	for k in 0 ..< 8 {
		b[k] = l[k]
		b[8 + k] = r[k]
		st[k] = H0[k]
	}
	compress(&st, &b)
	p := [16]u32{0x80000000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 512}
	compress(&st, &p)
	out^ = st
}

main :: proc() {
	n := arg_n(140000)
	data := make([]u32, n)
	t := make([][8]u32, 2 * n + 64)
	defer {delete(data);delete(t)}
	for i in 0 ..< n {
		data[i] = next()
		leaf(&t[i], data[i], 0, u64(i))
	}
	off, size := 0, n
	for size > 1 {
		noff := off + size
		ns := (size + 1) / 2
		for j in 0 ..< ns {
			l := &t[off + 2 * j]
			r := &t[off + 2 * j + 1] if 2 * j + 1 < size else l
			node(&t[noff + j], l, r)
		}
		off = noff
		size = ns
	}
	root := t[off]
	verified, rejected: u64
	sibs: [64][8]u32
	cur: [8]u32
	for p in 0 ..< n / 2 {
		idx := int(next()) % n
		o, s, pos, depth := 0, n, idx, 0
		for s > 1 {
			sb := pos + 1 if pos % 2 == 0 else pos - 1
			if sb >= s {sb = pos}
			sibs[depth] = t[o + sb]
			depth += 1
			o += s
			s = (s + 1) / 2
			pos /= 2
		}
		dlo := data[idx]
		dhi: u32 = 0
		if p % 4 == 3 {
			dlo += 1
			dhi = 1 if dlo == 0 else 0
		}
		leaf(&cur, dlo, dhi, u64(idx))
		pos = idx
		for d in 0 ..< depth {
			if pos % 2 == 0 {
				node(&cur, &cur, &sibs[d])
			} else {
				node(&cur, &sibs[d], &cur)
			}
			pos /= 2
		}
		if cur == root {verified += 1} else {rejected += 1}
	}
	for k in 0 ..< 8 {
		fmt.printf("%08x", root[k])
	}
	fmt.printf(" %d %d\n", verified, rejected)
}

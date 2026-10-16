// Merkle tree benchmark (Go, garbage collected, real SHA-256): same algorithm as merkletrees.c.
package main

import (
	"fmt"
	"math/bits"
	"os"
	"strconv"
)

var x uint32 = 12345

func next() uint32 { x = x*1664525 + 1013904223; return x }

var K = [64]uint32{
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2}

var H0 = [8]uint32{0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19}

func compress(st *[8]uint32, b *[16]uint32) {
	var w [64]uint32
	copy(w[:16], b[:])
	for i := 16; i < 64; i++ {
		s0 := bits.RotateLeft32(w[i-15], -7) ^ bits.RotateLeft32(w[i-15], -18) ^ (w[i-15] >> 3)
		s1 := bits.RotateLeft32(w[i-2], -17) ^ bits.RotateLeft32(w[i-2], -19) ^ (w[i-2] >> 10)
		w[i] = w[i-16] + s0 + w[i-7] + s1
	}
	a, bb, c, d, e, f, g, h := st[0], st[1], st[2], st[3], st[4], st[5], st[6], st[7]
	for i := 0; i < 64; i++ {
		S1 := bits.RotateLeft32(e, -6) ^ bits.RotateLeft32(e, -11) ^ bits.RotateLeft32(e, -25)
		ch := (e & f) ^ (^e & g)
		t1 := h + S1 + ch + K[i] + w[i]
		S0 := bits.RotateLeft32(a, -2) ^ bits.RotateLeft32(a, -13) ^ bits.RotateLeft32(a, -22)
		mj := (a & bb) ^ (a & c) ^ (bb & c)
		t2 := S0 + mj
		h = g
		g = f
		f = e
		e = d + t1
		d = c
		c = bb
		bb = a
		a = t1 + t2
	}
	st[0] += a
	st[1] += bb
	st[2] += c
	st[3] += d
	st[4] += e
	st[5] += f
	st[6] += g
	st[7] += h
}

func leaf(out []uint32, dlo, dhi uint32, i uint64) {
	b := [16]uint32{bits.ReverseBytes32(dlo), bits.ReverseBytes32(dhi), bits.ReverseBytes32(uint32(i)), bits.ReverseBytes32(uint32(i >> 32)), 0x80000000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 128}
	st := H0
	compress(&st, &b)
	copy(out[:8], st[:])
}

func node(out, l, r []uint32) {
	var b [16]uint32
	copy(b[:8], l[:8])
	copy(b[8:], r[:8])
	st := H0
	compress(&st, &b)
	p := [16]uint32{0x80000000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 512}
	compress(&st, &p)
	copy(out[:8], st[:])
}

func main() {
	n := 140000
	if len(os.Args) > 1 {
		n, _ = strconv.Atoi(os.Args[1])
	}
	data := make([]uint32, n)
	t := make([]uint32, (2*n+64)*8)
	for i := 0; i < n; i++ {
		data[i] = next()
		leaf(t[8*i:], data[i], 0, uint64(i))
	}
	off, size := 0, n
	var tmp [8]uint32
	for size > 1 {
		noff, ns := off+size, (size+1)/2
		for j := 0; j < ns; j++ {
			li := off + 2*j
			ri := li
			if 2*j+1 < size {
				ri = li + 1
			}
			node(tmp[:], t[8*li:], t[8*ri:])
			copy(t[8*(noff+j):], tmp[:])
		}
		off, size = noff, ns
	}
	var root [8]uint32
	copy(root[:], t[8*off:8*off+8])
	var verified, rejected uint64
	var sibs [64 * 8]uint32
	var cur, c [8]uint32
	for p := 0; p < n/2; p++ {
		idx := int(next()) % n
		o, s, pos, depth := 0, n, idx, 0
		for s > 1 {
			sb := pos - 1
			if pos%2 == 0 {
				sb = pos + 1
			}
			if sb >= s {
				sb = pos
			}
			copy(sibs[depth*8:depth*8+8], t[8*(o+sb):])
			depth++
			o += s
			s = (s + 1) / 2
			pos /= 2
		}
		dlo, dhi := data[idx], uint32(0)
		if p%4 == 3 {
			dlo++
			if dlo == 0 {
				dhi = 1
			}
		}
		leaf(cur[:], dlo, dhi, uint64(idx))
		pos = idx
		for d := 0; d < depth; d++ {
			c = cur
			if pos%2 == 0 {
				node(cur[:], c[:], sibs[d*8:])
			} else {
				node(cur[:], sibs[d*8:], c[:])
			}
			pos /= 2
		}
		if cur == root {
			verified++
		} else {
			rejected++
		}
	}
	for k := 0; k < 8; k++ {
		fmt.Printf("%08x", root[k])
	}
	fmt.Println("", verified, rejected)
}

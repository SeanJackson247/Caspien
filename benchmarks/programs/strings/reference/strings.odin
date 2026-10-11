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

x: u32 = 12345

next :: proc() -> u32 {
	x = x * 1664525 + 1013904223
	return x
}

hash :: proc(b: []u8) -> u32 {
	h: u32 = 7
	for c in b {
		h = h * 31 + u32(c)
	}
	return h
}

main :: proc() {
	n := arg_n(4000000)
	text := make([]u8, n)
	defer delete(text)
	for i in 0 ..< n {
		r := (next() >> 16) % 27
		text[i] = ' ' if r == 26 else u8('a') + u8(r)
	}
	words, longest, cur := 0, 0, 0
	for i in 0 ..< n {
		if text[i] == ' ' {
			if cur > 0 {
				words += 1
				if cur > longest {longest = cur}
				cur = 0
			}
		} else {
			cur += 1
		}
	}
	if cur > 0 {
		words += 1
		if cur > longest {longest = cur}
	}
	abc := 0
	for i := 0; i + 2 < n; i += 1 {
		if text[i] == 'a' && text[i + 1] == 'b' && text[i + 2] == 'c' {
			abc += 1
		}
	}
	rev := make([]u8, n)
	defer delete(rev)
	for i in 0 ..< n {
		rev[i] = text[n - 1 - i]
	}
	hrev := hash(rev)
	es := 0
	for i in 0 ..< n {
		if text[i] == 'e' {es += 1}
	}
	m := n + es
	rep := make([]u8, m)
	defer delete(rep)
	k := 0
	for i in 0 ..< n {
		if text[i] == 'e' {
			rep[k] = '3'
			rep[k + 1] = '3'
			k += 2
		} else {
			rep[k] = text[i]
			k += 1
		}
	}
	hrep := hash(rep)
	up := make([]u8, n)
	defer delete(up)
	for i in 0 ..< n {
		up[i] = ' ' if text[i] == ' ' else text[i] - 32
	}
	hup := hash(up)
	fmt.printf("%d %d %d %d %d %d %d\n", words, longest, abc, hrev, m, hrep, hup)
}

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

quicksort :: proc(a: []i32) {
	n := len(a)
	stack: [128]int
	sp := 0
	stack[sp] = 0;sp += 1
	stack[sp] = n - 1;sp += 1
	for sp > 0 {
		sp -= 1;hi := stack[sp]
		sp -= 1;lo := stack[sp]
		for lo < hi {
			pivot := a[(lo + hi) / 2]
			i, j := lo, hi
			for i <= j {
				for a[i] < pivot {i += 1}
				for a[j] > pivot {j -= 1}
				if i <= j {
					a[i], a[j] = a[j], a[i]
					i += 1
					j -= 1
				}
			}
			if j - lo < hi - i {
				stack[sp] = i;sp += 1
				stack[sp] = hi;sp += 1
				hi = j
			} else {
				stack[sp] = lo;sp += 1
				stack[sp] = j;sp += 1
				lo = i
			}
		}
	}
}

mergesort :: proc(a: []i32, tmp: []i32) {
	n := len(a)
	src, dst := a, tmp
	for w := 1; w < n; w *= 2 {
		for lo := 0; lo < n; lo += 2 * w {
			mid := min(lo + w, n)
			hi := min(lo + 2 * w, n)
			i, j, k := lo, mid, lo
			for i < mid && j < hi {
				if src[i] <= src[j] {
					dst[k] = src[i];i += 1
				} else {
					dst[k] = src[j];j += 1
				}
				k += 1
			}
			for i < mid {dst[k] = src[i];i += 1;k += 1}
			for j < hi {dst[k] = src[j];j += 1;k += 1}
		}
		src, dst = dst, src
	}
	if raw_data(src) != raw_data(a) {
		copy(a, src)
	}
}

siftdown :: proc(a: []i32, root_in: int, n: int) {
	root := root_in
	for {
		c := 2 * root + 1
		if c >= n {break}
		if c + 1 < n && a[c + 1] > a[c] {c += 1}
		if a[root] >= a[c] {break}
		a[root], a[c] = a[c], a[root]
		root = c
	}
}

heapsort :: proc(a: []i32) {
	n := len(a)
	for i := n / 2 - 1; i >= 0; i -= 1 {
		siftdown(a, i, n)
	}
	for e := n - 1; e > 0; e -= 1 {
		a[0], a[e] = a[e], a[0]
		siftdown(a, 0, e)
	}
}

bsearch_has :: proc(a: []i32, key: i32) -> bool {
	lo, hi := 0, len(a)
	for lo < hi {
		m := (lo + hi) / 2
		if a[m] < key {lo = m + 1} else {hi = m}
	}
	return lo < len(a) && a[lo] == key
}

main :: proc() {
	n := arg_n(2000000)
	orig := make([]i32, n)
	a := make([]i32, n)
	b := make([]i32, n)
	d := make([]i32, n)
	tmp := make([]i32, n)
	defer {delete(orig);delete(a);delete(b);delete(d);delete(tmp)}
	for i in 0 ..< n {
		orig[i] = i32(next() >> 1)
	}
	copy(a, orig);copy(b, orig);copy(d, orig)
	quicksort(a)
	mergesort(b, tmp)
	heapsort(d)
	h: u32 = 0
	ok := true
	for i in 0 ..< n {
		h = h * 31 + u32(a[i])
		if a[i] != b[i] || a[i] != d[i] {ok = false}
		if i > 0 && a[i - 1] > a[i] {ok = false}
	}
	bs := 0
	for k in 0 ..< n {
		key := i32(next() >> 1)
		if k % 2 == 0 {key = orig[int(u32(key)) % n]}
		if bsearch_has(a, key) {bs += 1}
	}
	ls := 0
	for k in 0 ..< 20 {
		key := i32(next() >> 1)
		if k % 2 == 0 {key = orig[int(u32(key)) % n]}
		for i in 0 ..< n {
			if orig[i] == key {
				ls += 1
				break
			}
		}
	}
	fmt.printf("%d %d %d %s\n", h, bs, ls, "OK" if ok else "BAD")
}

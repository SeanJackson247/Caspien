// Sorting and searching benchmark (Go): same algorithms as sorting.c, on []int32 (garbage collected).
package main

import (
	"fmt"
	"os"
	"strconv"
)

var x uint32 = 12345

func next() uint32 { x = x*1664525 + 1013904223; return x }

func quicksort(a []int32) {
	n := int64(len(a))
	var stack [128]int64
	sp := 0
	stack[sp] = 0; sp++
	stack[sp] = n - 1; sp++
	for sp > 0 {
		sp--; hi := stack[sp]
		sp--; lo := stack[sp]
		for lo < hi {
			pivot := a[(lo+hi)/2]
			i, j := lo, hi
			for i <= j {
				for a[i] < pivot { i++ }
				for a[j] > pivot { j-- }
				if i <= j { a[i], a[j] = a[j], a[i]; i++; j-- }
			}
			if j-lo < hi-i { stack[sp] = i; sp++; stack[sp] = hi; sp++; hi = j } else { stack[sp] = lo; sp++; stack[sp] = j; sp++; lo = i }
		}
	}
}

func mergesort(a, tmp []int32) {
	n := len(a)
	src, dst := a, tmp
	for w := 1; w < n; w *= 2 {
		for lo := 0; lo < n; lo += 2 * w {
			mid := lo + w
			if mid > n { mid = n }
			hi := lo + 2*w
			if hi > n { hi = n }
			i, j, k := lo, mid, lo
			for i < mid && j < hi {
				if src[i] <= src[j] { dst[k] = src[i]; i++ } else { dst[k] = src[j]; j++ }
				k++
			}
			for i < mid { dst[k] = src[i]; i++; k++ }
			for j < hi { dst[k] = src[j]; j++; k++ }
		}
		src, dst = dst, src
	}
	if &src[0] != &a[0] { copy(a, src) }
}

func siftdown(a []int32, root, n int) {
	for {
		c := 2*root + 1
		if c >= n { break }
		if c+1 < n && a[c+1] > a[c] { c++ }
		if a[root] >= a[c] { break }
		a[root], a[c] = a[c], a[root]
		root = c
	}
}

func heapsort(a []int32) {
	n := len(a)
	for i := n/2 - 1; i >= 0; i-- { siftdown(a, i, n) }
	for e := n - 1; e > 0; e-- { a[0], a[e] = a[e], a[0]; siftdown(a, 0, e) }
}

func bsearchHas(a []int32, key int32) bool {
	lo, hi := 0, len(a)
	for lo < hi {
		m := (lo + hi) / 2
		if a[m] < key { lo = m + 1 } else { hi = m }
	}
	return lo < len(a) && a[lo] == key
}

func main() {
	n := 2000000
	if len(os.Args) > 1 { n, _ = strconv.Atoi(os.Args[1]) }
	orig := make([]int32, n)
	for i := range orig { orig[i] = int32(next() >> 1) }
	a := make([]int32, n); b := make([]int32, n); d := make([]int32, n); tmp := make([]int32, n)
	copy(a, orig); copy(b, orig); copy(d, orig)
	quicksort(a)
	mergesort(b, tmp)
	heapsort(d)
	var h uint32
	ok := true
	for i := 0; i < n; i++ {
		h = h*31 + uint32(a[i])
		if a[i] != b[i] || a[i] != d[i] { ok = false }
		if i > 0 && a[i-1] > a[i] { ok = false }
	}
	bs := 0
	for k := 0; k < n; k++ {
		key := int32(next() >> 1)
		if k%2 == 0 { key = orig[uint32(key)%uint32(n)] }
		if bsearchHas(a, key) { bs++ }
	}
	ls := 0
	for k := 0; k < 20; k++ {
		key := int32(next() >> 1)
		if k%2 == 0 { key = orig[uint32(key)%uint32(n)] }
		for i := 0; i < n; i++ { if orig[i] == key { ls++; break } }
	}
	res := "OK"
	if !ok { res = "BAD" }
	fmt.Printf("%d %d %d %s\n", h, bs, ls, res)
}

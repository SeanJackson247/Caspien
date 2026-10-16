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

Slot :: struct {
	key: u64,
	cnt: u32,
}

KS := [6]int{1, 2, 3, 4, 6, 12}
QK := [11]int{1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12}
QV := [11]u64{0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487}

lookup :: proc(tab: []Slot, bits: uint, v: u64) -> u64 {
	kk := v + 1
	h := int((kk * 0x9E3779B97F4A7C15) >> (64 - bits))
	for {
		if tab[h].key == 0 {return 0}
		if tab[h].key == kk {return u64(tab[h].cnt)}
		h = (h + 1) & (len(tab) - 1)
	}
}

main :: proc() {
	n := arg_n(30000000)
	seq := make([]u8, n + 1)
	defer delete(seq)
	last: u32 = 42
	for i in 0 ..< n {
		last = (last * 3877 + 29573) % 139968
		c: u8
		if last < 42404 {c = 0} else if last < 70117 {c = 1} else if last < 97767 {c = 2} else {c = 3}
		seq[i] = c
	}
	first12: u64 = 0
	for i := 0; i < 12 && i < n; i += 1 {
		first12 = (first12 << 2) | u64(seq[i])
	}
	ndist: [6]u64
	counts: [12]u64
	nq := 0
	for k, ki in KS {
		maxd := 1 << uint(2 * k)
		win := n - k + 1 if n >= k else 0
		if win < maxd {maxd = win}
		if maxd > 139968 {maxd = 139968}
		bits: uint = 1
		for (1 << bits) < 2 * maxd {bits += 1}
		cap := 1 << bits
		tab := make([]Slot, cap)
		mask: u64 = (u64(1) << uint(2 * k)) - 1
		key: u64 = 0
		nd: u64 = 0
		for i in 0 ..< n {
			key = ((key << 2) | u64(seq[i])) & mask
			if i + 1 >= k {
				h := int(((key + 1) * 0x9E3779B97F4A7C15) >> (64 - bits))
				for {
					if tab[h].key == 0 {
						tab[h].key = key + 1
						tab[h].cnt = 1
						nd += 1
						break
					}
					if tab[h].key == key + 1 {
						tab[h].cnt += 1
						break
					}
					h = (h + 1) & (cap - 1)
				}
			}
		}
		ndist[ki] = nd
		for q in 0 ..< 11 {
			if QK[q] != k {continue}
			counts[nq] = lookup(tab, bits, QV[q])
			nq += 1
		}
		if k == 12 {
			counts[nq] = lookup(tab, bits, first12)
			nq += 1
		}
		delete(tab)
	}
	for i in 0 ..< 6 {
		fmt.printf("%d ", ndist[i])
	}
	for i in 0 ..< 12 {
		if i > 0 {fmt.printf(" ")}
		fmt.printf("%d", counts[i])
	}
	fmt.printf("\n")
}

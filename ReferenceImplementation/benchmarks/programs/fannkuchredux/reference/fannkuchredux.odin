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

fannkuchredux :: proc(n: int) -> int {
	perm, perm1, count: [16]int
	max_flips, perm_count, checksum := 0, 0, 0
	r := n
	for i in 0 ..< n {perm1[i] = i}
	for {
		for r != 1 {
			count[r - 1] = r
			r -= 1
		}
		for i in 0 ..< n {perm[i] = perm1[i]}
		flips := 0
		for perm[0] != 0 {
			k := perm[0]
			k2 := (k + 1) >> 1
			for i in 0 ..< k2 {
				perm[i], perm[k - i] = perm[k - i], perm[i]
			}
			flips += 1
		}
		if flips > max_flips {max_flips = flips}
		checksum += flips if perm_count % 2 == 0 else -flips
		for {
			if r == n {
				fmt.printf("%d\n", checksum)
				return max_flips
			}
			perm0 := perm1[0]
			i := 0
			for i < r {
				j := i + 1
				perm1[i] = perm1[j]
				i = j
			}
			perm1[r] = perm0
			count[r] -= 1
			if count[r] > 0 {break}
			r += 1
		}
		perm_count += 1
	}
}

main :: proc() {
	n := arg_n(7)
	m := fannkuchredux(n)
	fmt.printf("Pfannkuchen(%d) = %d\n", n, m)
}

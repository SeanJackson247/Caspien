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

main :: proc() {
	n := arg_n(3000)
	dn := f64(n)
	inside, total := 0, 0
	for y in 0 ..< n {
		ci := 2.0 * f64(y) / dn - 1.0
		for x in 0 ..< n {
			cr := 2.0 * f64(x) / dn - 1.5
			zr, zi, tr, ti: f64
			i := 0
			for i < 100 && tr + ti <= 4.0 {
				zi = 2.0 * zr * zi + ci
				zr = tr - ti + cr
				tr = zr * zr
				ti = zi * zi
				i += 1
			}
			total += i
			if i == 100 {inside += 1}
		}
	}
	fmt.printf("%d %d\n", inside, total)
}

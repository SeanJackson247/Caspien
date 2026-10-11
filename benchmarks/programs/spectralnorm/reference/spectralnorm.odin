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

import "core:math"

eval_a :: #force_inline proc(i, j: int) -> f64 {
	return 1.0 / f64((i + j) * (i + j + 1) / 2 + i + 1)
}

a_times_u :: proc(n: int, u, au: []f64) {
	for i in 0 ..< n {
		au[i] = 0
		for j in 0 ..< n {au[i] += eval_a(i, j) * u[j]}
	}
}

at_times_u :: proc(n: int, u, au: []f64) {
	for i in 0 ..< n {
		au[i] = 0
		for j in 0 ..< n {au[i] += eval_a(j, i) * u[j]}
	}
}

ata_times_u :: proc(n: int, u, ata_u: []f64) {
	v := make([]f64, n)
	defer delete(v)
	a_times_u(n, u, v)
	at_times_u(n, v, ata_u)
}

main :: proc() {
	n := arg_n(100)
	u := make([]f64, n)
	v := make([]f64, n)
	defer {delete(u);delete(v)}
	for i in 0 ..< n {u[i] = 1}
	for _ in 0 ..< 10 {
		ata_times_u(n, u, v)
		ata_times_u(n, v, u)
	}
	vbv, vv: f64
	for i in 0 ..< n {
		vbv += u[i] * v[i]
		vv += v[i] * v[i]
	}
	fmt.printf("%.9f\n", math.sqrt(vbv / vv))
}

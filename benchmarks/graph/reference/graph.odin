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

Node :: struct {
	value: u32,
	dist:  i32,
	e:     [4]^Node,
}

x: u32 = 12345

next :: proc() -> u32 {
	x = x * 1664525 + 1013904223
	return x
}

main :: proc() {
	n := arg_n(2000000)
	nodes := make([]^Node, n)
	defer delete(nodes)
	for i in 0 ..< n {
		nodes[i] = new(Node)
		nodes[i].value = next() & 0xFFFFFF
		nodes[i].dist = -1
	}
	defer for p in nodes {free(p)}
	nodes[n * 7 / 10].value = 0xFFFFFFFF
	for i in 0 ..< n {
		nodes[i].e[0] = nodes[(i + 1) % n]
		for k in 1 ..< 4 {
			nodes[i].e[k] = nodes[int(next()) % n]
		}
	}
	q := make([]^Node, n)
	defer delete(q)
	head, tail := 0, 0
	q[tail] = nodes[0]
	tail += 1
	nodes[0].dist = 0
	maxdepth: i32 = 0
	needledist: i32 = -1
	sum: u32 = 0
	for head < tail {
		u := q[head]
		head += 1
		sum += u.value
		if u.value == 0xFFFFFFFF {needledist = u.dist}
		if u.dist > maxdepth {maxdepth = u.dist}
		for k in 0 ..< 4 {
			v := u.e[k]
			if v.dist < 0 {
				v.dist = u.dist + 1
				q[tail] = v
				tail += 1
			}
		}
	}
	fmt.printf("%d %d %d %d\n", tail, maxdepth, needledist, sum)
}

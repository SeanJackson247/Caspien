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
	l, r: ^Node,
}

make_tree :: proc(d: int) -> ^Node {
	n := new(Node)
	if d > 0 {
		n.l = make_tree(d - 1)
		n.r = make_tree(d - 1)
	}
	return n
}

check :: proc(n: ^Node) -> int {
	if n.l != nil {
		return 1 + check(n.l) + check(n.r)
	}
	return 1
}

release :: proc(n: ^Node) {
	if n.l != nil {
		release(n.l)
		release(n.r)
	}
	free(n)
}

main :: proc() {
	maxd := arg_n(16)
	if maxd < 6 {maxd = 6}
	t := make_tree(maxd + 1)
	fmt.printf("%d", check(t))
	release(t)
	longlived := make_tree(maxd)
	for d := 4; d <= maxd; d += 2 {
		iters := 1 << uint(maxd - d + 4)
		sum := 0
		for _ in 0 ..< iters {
			a := make_tree(d)
			sum += check(a)
			release(a)
		}
		fmt.printf(" %d", sum)
	}
	fmt.printf(" %d\n", check(longlived))
	release(longlived)
}

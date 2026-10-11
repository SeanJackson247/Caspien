// Heap graph benchmark (Go, garbage collected).
package main

import (
	"fmt"
	"os"
	"strconv"
)

type Node struct {
	value uint32
	dist  int32
	e     [4]*Node
}

var x uint32 = 12345

func next() uint32 { x = x*1664525 + 1013904223; return x }

func main() {
	n := 2000000
	if len(os.Args) > 1 {
		n, _ = strconv.Atoi(os.Args[1])
	}
	nodes := make([]*Node, n)
	for i := 0; i < n; i++ {
		nodes[i] = &Node{value: next() & 0xFFFFFF, dist: -1}
	}
	nodes[n*7/10].value = 0xFFFFFFFF
	for i := 0; i < n; i++ {
		nodes[i].e[0] = nodes[(i+1)%n]
		for k := 1; k < 4; k++ {
			nodes[i].e[k] = nodes[int(next())%n]
		}
	}
	q := make([]*Node, n)
	head, tail := 0, 0
	q[tail] = nodes[0]
	tail++
	nodes[0].dist = 0
	maxdepth, needledist := int32(0), int32(-1)
	var sum uint32
	for head < tail {
		u := q[head]
		head++
		sum += u.value
		if u.value == 0xFFFFFFFF {
			needledist = u.dist
		}
		if u.dist > maxdepth {
			maxdepth = u.dist
		}
		for k := 0; k < 4; k++ {
			v := u.e[k]
			if v.dist < 0 {
				v.dist = u.dist + 1
				q[tail] = v
				tail++
			}
		}
	}
	fmt.Println(tail, maxdepth, needledist, sum)
}

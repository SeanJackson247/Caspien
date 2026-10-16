// Binary trees (Go, garbage collected): ordinary heap nodes with left/right pointers.
package main

import (
	"fmt"
	"os"
	"strconv"
)

type Node struct{ l, r *Node }

func make_(d int) *Node {
	n := &Node{}
	if d > 0 {
		n.l = make_(d - 1)
		n.r = make_(d - 1)
	}
	return n
}

func check(n *Node) int64 {
	if n.l == nil {
		return 1
	}
	return 1 + check(n.l) + check(n.r)
}

func main() {
	maxd := 16
	if len(os.Args) > 1 {
		maxd, _ = strconv.Atoi(os.Args[1])
	}
	if maxd < 6 {
		maxd = 6
	}
	fmt.Print(check(make_(maxd + 1)))
	longlived := make_(maxd)
	for d := 4; d <= maxd; d += 2 {
		iters := 1 << uint(maxd-d+4)
		var sum int64
		for i := 0; i < iters; i++ {
			sum += check(make_(d))
		}
		fmt.Print(" ", sum)
	}
	fmt.Println("", check(longlived))
}

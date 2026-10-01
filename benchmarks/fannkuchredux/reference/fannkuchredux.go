// fannkuch-redux, single threaded. Same algorithm as fannkuchredux-gcc-1 of the Computer Language Benchmarks Game.
package main

import (
	"fmt"
	"os"
	"strconv"
)

func fannkuchredux(n int) int {
	var perm, perm1, count [16]int
	maxFlips, permCount, checksum := 0, 0, 0
	for i := 0; i < n; i++ {
		perm1[i] = i
	}
	r := n
	for {
		for r != 1 {
			count[r-1] = r
			r--
		}
		for i := 0; i < n; i++ {
			perm[i] = perm1[i]
		}
		flips := 0
		for {
			k := perm[0]
			if k == 0 {
				break
			}
			k2 := (k + 1) >> 1
			for i := 0; i < k2; i++ {
				perm[i], perm[k-i] = perm[k-i], perm[i]
			}
			flips++
		}
		if flips > maxFlips {
			maxFlips = flips
		}
		if permCount%2 == 0 {
			checksum += flips
		} else {
			checksum -= flips
		}
		for {
			if r == n {
				fmt.Println(checksum)
				return maxFlips
			}
			perm0 := perm1[0]
			i := 0
			for i < r {
				j := i + 1
				perm1[i] = perm1[j]
				i = j
			}
			perm1[r] = perm0
			count[r]--
			if count[r] > 0 {
				break
			}
			r++
		}
		permCount++
	}
}

func main() {
	n := 7
	if len(os.Args) > 1 {
		n, _ = strconv.Atoi(os.Args[1])
	}
	fmt.Printf("Pfannkuchen(%d) = %d\n", n, fannkuchredux(n))
}

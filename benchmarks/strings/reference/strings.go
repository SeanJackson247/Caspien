// String manipulation benchmark (Go, garbage collected).
package main

import (
	"fmt"
	"os"
	"strconv"
)

var x uint32 = 12345

func next() uint32 { x = x*1664525 + 1013904223; return x }
func hash(b []byte) uint32 {
	var h uint32 = 7
	for _, c := range b {
		h = h*31 + uint32(c)
	}
	return h
}

func main() {
	n := 4000000
	if len(os.Args) > 1 {
		n, _ = strconv.Atoi(os.Args[1])
	}
	text := make([]byte, n)
	for i := 0; i < n; i++ {
		r := (next() >> 16) % 27
		if r == 26 {
			text[i] = ' '
		} else {
			text[i] = byte('a' + r)
		}
	}
	words, longest, cur := 0, 0, 0
	for i := 0; i < n; i++ {
		if text[i] == ' ' {
			if cur > 0 {
				words++
				if cur > longest {
					longest = cur
				}
				cur = 0
			}
		} else {
			cur++
		}
	}
	if cur > 0 {
		words++
		if cur > longest {
			longest = cur
		}
	}
	abc := 0
	for i := 0; i+2 < n; i++ {
		if text[i] == 'a' && text[i+1] == 'b' && text[i+2] == 'c' {
			abc++
		}
	}
	rev := make([]byte, n)
	for i := 0; i < n; i++ {
		rev[i] = text[n-1-i]
	}
	hrev := hash(rev)
	es := 0
	for i := 0; i < n; i++ {
		if text[i] == 'e' {
			es++
		}
	}
	m := n + es
	rep := make([]byte, m)
	k := 0
	for i := 0; i < n; i++ {
		if text[i] == 'e' {
			rep[k] = '3'
			rep[k+1] = '3'
			k += 2
		} else {
			rep[k] = text[i]
			k++
		}
	}
	hrep := hash(rep)
	up := make([]byte, n)
	for i := 0; i < n; i++ {
		if text[i] == ' ' {
			up[i] = ' '
		} else {
			up[i] = text[i] - 32
		}
	}
	hup := hash(up)
	fmt.Println(words, longest, abc, hrev, m, hrep, hup)
}

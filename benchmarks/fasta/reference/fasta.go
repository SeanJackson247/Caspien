// FASTA benchmark (Go, garbage collected). Same algorithm and output as fasta.c.
package main

import (
	"fmt"
	"os"
	"strconv"
)

const ALU = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA"
const CHARS = "ACGTBDHKMNRSVWYACGT"

var THR = [19]uint32{37792,54588,71384,109176,111975,114774,117574,120373,123172,125972,128771,131570,134370,137169,139968,42404,70117,97767,139968}
var HDR = [3]string{">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n"}

func main() {
	n := 40000000
	if len(os.Args) > 1 {
		n, _ = strconv.Atoi(os.Args[1])
	}
	cnt := [3]int{n * 2 / 10, n * 3 / 10, 0}
	cnt[2] = n - cnt[0] - cnt[1]
	off := [3]int{0, 0, 15}
	var last uint32 = 42
	buf := make([]byte, n+n/60+1024)
	pos, a, c, g, t, other, ai := 0, 0, 0, 0, 0, 0, 0
	for s := 0; s < 3; s++ {
		pos += copy(buf[pos:], HDR[s])
		col := 0
		for i := 0; i < cnt[s]; i++ {
			var ch byte
			if s == 0 {
				ch = ALU[ai]
				ai++
				if ai == 287 {
					ai = 0
				}
			} else {
				last = (last*3877 + 29573) % 139968
				j := off[s]
				for last >= THR[j] {
					j++
				}
				ch = CHARS[j]
			}
			buf[pos] = ch
			pos++
			switch ch {
			case 'A':
				a++
			case 'C':
				c++
			case 'G':
				g++
			case 'T':
				t++
			default:
				other++
			}
			col++
			if col == 60 {
				buf[pos] = '\n'
				pos++
				col = 0
			}
		}
		if col > 0 {
			buf[pos] = '\n'
			pos++
		}
	}
	var h uint32 = 7
	for i := 0; i < pos; i++ {
		h = h*31 + uint32(buf[i])
	}
	fmt.Println(pos, h, a, c, g, t, other)
}

// k-nucleotide benchmark: same sequence, k-mers and output as knucleotide.c (see there), but the counting uses the language's own hash map keyed by the packed 2-bit k-mer.
// Go: built-in map[uint64]uint32, garbage collected.
package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

var KS = [6]int{1, 2, 3, 4, 6, 12}
var QK = [11]int{1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12}
var QV = [11]uint64{0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487}

func main() {
	n := 30000000
	if len(os.Args) > 1 {
		n, _ = strconv.Atoi(os.Args[1])
	}
	var last uint32 = 42
	seq := make([]byte, n+1)
	for i := 0; i < n; i++ {
		last = (last*3877 + 29573) % 139968
		var c byte
		if last < 42404 {
			c = 0
		} else if last < 70117 {
			c = 1
		} else if last < 97767 {
			c = 2
		} else {
			c = 3
		}
		seq[i] = c
	}
	var first12 uint64
	for i := 0; i < 12 && i < n; i++ {
		first12 = (first12 << 2) | uint64(seq[i])
	}
	var distinct [6]uint64
	counts := []uint64{}
	for ki := 0; ki < 6; ki++ {
		k := KS[ki]
		m := make(map[uint64]uint32)
		mask := (uint64(1) << (2 * uint(k))) - 1
		var key uint64
		for i := 0; i < n; i++ {
			key = ((key << 2) | uint64(seq[i])) & mask
			if i+1 >= k {
				m[key]++
			}
		}
		distinct[ki] = uint64(len(m))
		for q := 0; q < 11; q++ {
			if QK[q] != k {
				continue
			}
			counts = append(counts, uint64(m[QV[q]]))
		}
		if k == 12 {
			counts = append(counts, uint64(m[first12]))
		}
	}
	var sb strings.Builder
	for _, d := range distinct {
		sb.WriteString(strconv.FormatUint(d, 10) + " ")
	}
	parts := make([]string, len(counts))
	for i, c := range counts {
		parts[i] = strconv.FormatUint(c, 10)
	}
	sb.WriteString(strings.Join(parts, " "))
	fmt.Println(sb.String())
}

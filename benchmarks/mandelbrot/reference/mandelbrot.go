// Mandelbrot escape-time benchmark (Go): same grid, maths and output as mandelbrot.c.
package main

import (
	"fmt"
	"os"
	"strconv"
)

func main() {
	n := 3000
	if len(os.Args) > 1 {
		n, _ = strconv.Atoi(os.Args[1])
	}
	dn := float64(n)
	var inside, total int64
	for y := 0; y < n; y++ {
		ci := 2.0*float64(y)/dn - 1.0
		for x := 0; x < n; x++ {
			cr := 2.0*float64(x)/dn - 1.5
			zr, zi, tr, ti := 0.0, 0.0, 0.0, 0.0
			i := 0
			for i < 100 && tr+ti <= 4.0 {
				zi = 2.0*zr*zi + ci
				zr = tr - ti + cr
				tr = zr * zr
				ti = zi * zi
				i++
			}
			total += int64(i)
			if i == 100 {
				inside++
			}
		}
	}
	fmt.Println(inside, total)
}

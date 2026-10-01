// spectral-norm, single threaded. Same algorithm as spectralnorm-gcc-1 of the Computer Language Benchmarks Game.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
)

func evalA(i, j int) float64 { return 1.0 / float64((i+j)*(i+j+1)/2+i+1) }

func aTimesU(u, au []float64) {
	for i := range u {
		s := 0.0
		for j := range u {
			s += evalA(i, j) * u[j]
		}
		au[i] = s
	}
}
func atTimesU(u, au []float64) {
	for i := range u {
		s := 0.0
		for j := range u {
			s += evalA(j, i) * u[j]
		}
		au[i] = s
	}
}
func ataTimesU(u, atau []float64) {
	v := make([]float64, len(u))
	aTimesU(u, v)
	atTimesU(v, atau)
}

func main() {
	n := 100
	if len(os.Args) > 1 {
		n, _ = strconv.Atoi(os.Args[1])
	}
	u := make([]float64, n)
	v := make([]float64, n)
	for i := range u {
		u[i] = 1
	}
	for i := 0; i < 10; i++ {
		ataTimesU(u, v)
		ataTimesU(v, u)
	}
	vBv, vv := 0.0, 0.0
	for i := 0; i < n; i++ {
		vBv += u[i] * v[i]
		vv += v[i] * v[i]
	}
	fmt.Printf("%0.9f\n", math.Sqrt(vBv/vv))
}

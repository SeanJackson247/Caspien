// Sieve of Eratosthenes (Go, garbage collected).
package main

import (
	"fmt"
	"os"
	"strconv"
)

func main() {
	n := 100000000
	if len(os.Args) > 1 {
		n, _ = strconv.Atoi(os.Args[1])
	}
	f := make([]byte, n+1)
	for i := range f {
		f[i] = 1
	}
	for i := 2; i*i <= n; i++ {
		if f[i] != 0 {
			for j := i * i; j <= n; j += i {
				f[j] = 0
			}
		}
	}
	count := 0
	for i := 2; i <= n; i++ {
		count += int(f[i])
	}
	fmt.Println(count)
}

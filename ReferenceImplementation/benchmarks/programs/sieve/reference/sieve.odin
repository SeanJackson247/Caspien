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

main :: proc() {
	n := arg_n(100000000)
	f := make([]u8, n + 1)
	defer delete(f)
	for i in 0 ..= n {
		f[i] = 1
	}
	for i := 2; i * i <= n; i += 1 {
		if f[i] != 0 {
			for j := i * i; j <= n; j += i {
				f[j] = 0
			}
		}
	}
	count := 0
	for i := 2; i <= n; i += 1 {
		count += int(f[i])
	}
	fmt.printf("%d\n", count)
}

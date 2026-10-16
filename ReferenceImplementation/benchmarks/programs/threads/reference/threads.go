// threads: see threads.c. Go schedules goroutines onto OS threads itself; runtime.LockOSThread() pins each task to its own
// OS thread for the length of the task, which is the closest Go gets to "one OS thread per task".
package main

import (
	"fmt"
	"os"
	"runtime"
	"strconv"
	"sync"
)

const WORK = 1000000
const WAVE = 64

func task(id uint64, out *uint64, wg *sync.WaitGroup) {
	runtime.LockOSThread()
	defer wg.Done()
	s := (id + 1) * 2654435761
	var acc uint64
	for i := 0; i < WORK; i++ {
		s ^= s << 13
		s ^= s >> 7
		s ^= s << 17
		acc += s & 255
	}
	*out = acc
}

func main() {
	n := uint64(1024)
	if len(os.Args) > 1 {
		n, _ = strconv.ParseUint(os.Args[1], 10, 64)
	}
	var total uint64
	for base := uint64(0); base < n; base += WAVE {
		var res [WAVE]uint64
		var wg sync.WaitGroup
		wg.Add(WAVE)
		for j := uint64(0); j < WAVE; j++ {
			go task(base+j, &res[j], &wg)
		}
		wg.Wait()
		for j := 0; j < WAVE; j++ {
			total += res[j]
		}
	}
	fmt.Printf("tasks=%d checksum=%d\n", n, total)
}

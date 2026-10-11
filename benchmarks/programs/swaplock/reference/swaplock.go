// swaplock: see swaplock.c. Lock: sync.Mutex. Each goroutine is pinned to its own OS thread with runtime.LockOSThread().
package main

import (
	"fmt"
	"os"
	"runtime"
	"strconv"
	"sync"
)

const THREADS = 32

var mu sync.Mutex
var count, sum uint64

func worker(id uint64, k uint64, wg *sync.WaitGroup) {
	runtime.LockOSThread()
	defer wg.Done()
	for i := uint64(0); i < k; i++ {
		mu.Lock()
		count += 1
		sum += id + (i % 7)
		mu.Unlock()
	}
}

func main() {
	k := uint64(500000)
	if len(os.Args) > 1 {
		k, _ = strconv.ParseUint(os.Args[1], 10, 64)
	}
	var wg sync.WaitGroup
	wg.Add(THREADS)
	for j := uint64(0); j < THREADS; j++ {
		go worker(j, k, &wg)
	}
	wg.Wait()
	fmt.Printf("count=%d sum=%d\n", count, sum)
}

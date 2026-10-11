// LRU cache benchmark (Go, garbage collected): map[uint32]*list.Element + container/list (front = most recently used).
package main

import (
	"container/list"
	"fmt"
	"os"
	"strconv"
)

const (
	CAP  = 262144
	KEYS = 1048576
	HOT  = 131072
)

type entry struct{ key, val uint32 }

var x uint32 = 12345

func next() uint32 { x = x*1664525 + 1013904223; return x }

func main() {
	n := 20000000
	if len(os.Args) > 1 {
		n, _ = strconv.Atoi(os.Args[1])
	}
	order := list.New()
	m := make(map[uint32]*list.Element, CAP*2)
	var hits, misses uint64
	var sum uint32
	for op := 0; op < n; op++ {
		a := next()
		y := next()
		rng := uint32(HOT)
		if (y>>20)%4 == 0 {
			rng = KEYS
		}
		k := (a >> 8) % rng
		el, ok := m[k]
		if (y>>24)%4 != 0 {
			if ok {
				hits++
				sum += el.Value.(*entry).val + k
				order.MoveToFront(el)
			} else {
				misses++
			}
		} else if ok {
			el.Value.(*entry).val = y
			order.MoveToFront(el)
		} else {
			if len(m) == CAP {
				b := order.Back()
				e := b.Value.(*entry)
				sum += e.key
				delete(m, e.key)
				order.Remove(b)
			}
			m[k] = order.PushFront(&entry{k, y})
		}
	}
	var fin uint64
	for el := order.Front(); el != nil; el = el.Next() {
		fin = (fin*31 + uint64(el.Value.(*entry).key)) % 4294967296
	}
	fmt.Println(hits, misses, (uint64(sum)+fin)%4294967296)
}

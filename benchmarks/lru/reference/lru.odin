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

CAP :: u32(262144)
KEYS :: u32(1048576)
HOT :: u32(131072)
NB :: u32(524288)
NONE :: u32(0xFFFFFFFF)

rng: u32 = 12345

next :: proc() -> u32 {
	rng = rng * 1664525 + 1013904223
	return rng
}

key, val, prv, nxt, hn, bucket: []u32
head: u32 = NONE
tail: u32 = NONE

hsh :: #force_inline proc(k: u32) -> u32 {
	return u32(u64(k) * 2654435761) >> 13
}

unlink_node :: proc(i: u32) {
	if prv[i] != NONE {nxt[prv[i]] = nxt[i]} else {head = nxt[i]}
	if nxt[i] != NONE {prv[nxt[i]] = prv[i]} else {tail = prv[i]}
}

push_front :: proc(i: u32) {
	prv[i] = NONE
	nxt[i] = head
	if head != NONE {prv[head] = i} else {tail = i}
	head = i
}

find :: proc(k: u32) -> u32 {
	i := bucket[hsh(k)]
	for i != NONE && key[i] != k {
		i = hn[i]
	}
	return i
}

hash_remove :: proc(i: u32) {
	b := hsh(key[i])
	if bucket[b] == i {
		bucket[b] = hn[i]
		return
	}
	j := bucket[b]
	for hn[j] != i {
		j = hn[j]
	}
	hn[j] = hn[i]
}

main :: proc() {
	n := arg_n(20000000)
	key = make([]u32, CAP)
	val = make([]u32, CAP)
	prv = make([]u32, CAP)
	nxt = make([]u32, CAP)
	hn = make([]u32, CAP)
	bucket = make([]u32, NB)
	defer {delete(key);delete(val);delete(prv);delete(nxt);delete(hn);delete(bucket)}
	for i in 0 ..< int(NB) {
		bucket[i] = NONE
	}
	size: u32 = 0
	hits, misses, sum: u64
	for _ in 0 ..< n {
		a := next()
		y := next()
		range_ := KEYS if (y >> 20) % 4 == 0 else HOT
		k := (a >> 8) % range_
		i := find(k)
		if (y >> 24) % 4 != 0 {
			if i != NONE {
				hits += 1
				sum += u64(val[i]) + u64(k)
				unlink_node(i)
				push_front(i)
			} else {
				misses += 1
			}
		} else if i != NONE {
			val[i] = y
			unlink_node(i)
			push_front(i)
		} else {
			if size == CAP {
				i = tail
				sum += u64(key[i])
				unlink_node(i)
				hash_remove(i)
			} else {
				i = size
				size += 1
			}
			key[i] = k
			val[i] = y
			b := hsh(k)
			hn[i] = bucket[b]
			bucket[b] = i
			push_front(i)
		}
	}
	fin: u64 = 0
	for i := head; i != NONE; i = nxt[i] {
		fin = (fin * 31 + u64(key[i])) % 4294967296
	}
	fmt.printf("%d %d %d\n", hits, misses, (sum + fin) % 4294967296)
}

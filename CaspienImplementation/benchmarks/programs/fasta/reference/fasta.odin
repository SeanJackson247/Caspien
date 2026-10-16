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

ALU := "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA"
CHARS := "ACGTBDHKMNRSVWYACGT"
THR := [19]u32{37792, 54588, 71384, 109176, 111975, 114774, 117574, 120373, 123172, 125972, 128771, 131570, 134370, 137169, 139968, 42404, 70117, 97767, 139968}
HDR := [3]string{">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n"}

main :: proc() {
	n := arg_n(40000000)
	cnt := [3]int{n * 2 / 10, n * 3 / 10, 0}
	cnt[2] = n - cnt[0] - cnt[1]
	off := [3]int{0, 0, 15}
	buf := make([]u8, n + n / 60 + 1024)
	defer delete(buf)
	last: u32 = 42
	pos, a, c, g, t, other, ai := 0, 0, 0, 0, 0, 0, 0
	for s in 0 ..< 3 {
		hl := len(HDR[s])
		copy(buf[pos:pos + hl], HDR[s])
		pos += hl
		col := 0
		for _ in 0 ..< cnt[s] {
			ch: u8
			if s == 0 {
				ch = ALU[ai]
				ai += 1
				if ai == 287 {ai = 0}
			} else {
				last = (last * 3877 + 29573) % 139968
				j := off[s]
				for last >= THR[j] {j += 1}
				ch = CHARS[j]
			}
			buf[pos] = ch
			pos += 1
			switch ch {
			case 'A': a += 1
			case 'C': c += 1
			case 'G': g += 1
			case 'T': t += 1
			case: other += 1
			}
			col += 1
			if col == 60 {
				buf[pos] = '\n'
				pos += 1
				col = 0
			}
		}
		if col > 0 {
			buf[pos] = '\n'
			pos += 1
		}
	}
	h: u32 = 7
	for i in 0 ..< pos {
		h = h * 31 + u32(buf[i])
	}
	fmt.printf("%d %d %d %d %d %d %d\n", pos, h, a, c, g, t, other)
}

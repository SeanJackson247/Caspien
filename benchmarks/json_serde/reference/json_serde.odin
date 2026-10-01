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

Rec :: struct {
	id, name_off, name_len, score, tag_off, tag_cnt: u64,
}

rng: u32 = 12345

next :: proc() -> u32 {
	rng = rng * 1664525 + 1013904223
	return rng
}

wnum :: proc(t: []u8, p: int, v_in: u64) -> int {
	d := 1
	u := v_in
	for u >= 10 {
		u /= 10
		d += 1
	}
	v := v_in
	for k := d - 1; k >= 0; k -= 1 {
		t[p + k] = u8('0') + u8(v % 10)
		v /= 10
	}
	return p + d
}

wlit :: proc(t: []u8, p_in: int, s: string) -> int {
	p := p_in
	for i in 0 ..< len(s) {
		t[p] = s[i]
		p += 1
	}
	return p
}

main :: proc() {
	n := arg_n(4000000)
	text := make([]u8, n * 80 + 16)
	defer delete(text)
	p := 0
	text[p] = '[';p += 1
	for i in 0 ..< n {
		if i > 0 {text[p] = ',';p += 1}
		p = wlit(text, p, "{\"id\":")
		p = wnum(text, p, u64(i))
		p = wlit(text, p, ",\"name\":\"")
		nl := 3 + int((next() >> 16) % 8)
		for _ in 0 ..< nl {
			text[p] = u8('a') + u8((next() >> 16) % 26)
			p += 1
		}
		p = wlit(text, p, "\",\"score\":")
		cents := u64((next() >> 8) % 1000000)
		p = wnum(text, p, cents / 100)
		text[p] = '.'
		text[p + 1] = u8('0') + u8(cents % 100 / 10)
		text[p + 2] = u8('0') + u8(cents % 10)
		p += 3
		p = wlit(text, p, ",\"tags\":[")
		tc := int((next() >> 16) % 5)
		for k in 0 ..< tc {
			if k > 0 {text[p] = ',';p += 1}
			p = wnum(text, p, u64((next() >> 16) % 100))
		}
		p = wlit(text, p, "]}")
	}
	text[p] = ']';p += 1
	tlen := p
	// ---- parse ----
	recs := make([]Rec, n + 1)
	names := make([]u8, n * 10 + 8)
	tags := make([]u8, n * 4 + 8)
	defer {delete(recs);delete(names);delete(tags)}
	count, noff, toff, q := 0, 0, 0, 1
	for {
		c := text[q]
		if c == ']' {break}
		if c == ',' {q += 1;continue}
		q += 1
		r: Rec
		for {
			q += 1
			k0 := text[q]
			for text[q] != '"' {q += 1}
			q += 2
			if k0 == 'i' {
				v: u64
				for text[q] >= '0' && text[q] <= '9' {v = v * 10 + u64(text[q] - '0');q += 1}
				r.id = v
			} else if k0 == 'n' {
				q += 1
				r.name_off = u64(noff)
				for text[q] != '"' {names[noff] = text[q];noff += 1;q += 1}
				r.name_len = u64(noff) - r.name_off
				q += 1
			} else if k0 == 's' {
				v: u64
				for text[q] >= '0' && text[q] <= '9' {v = v * 10 + u64(text[q] - '0');q += 1}
				q += 1
				v = v * 100 + u64(text[q] - '0') * 10 + u64(text[q + 1] - '0')
				q += 2
				r.score = v
			} else {
				q += 1
				r.tag_off = u64(toff)
				for text[q] != ']' {
					if text[q] == ',' {q += 1}
					v: u64
					for text[q] >= '0' && text[q] <= '9' {v = v * 10 + u64(text[q] - '0');q += 1}
					tags[toff] = u8(v)
					toff += 1
				}
				r.tag_cnt = u64(toff) - r.tag_off
				q += 1
			}
			if text[q] == ',' {q += 1} else {q += 1;break}
		}
		recs[count] = r
		count += 1
	}
	h: u32 = 7
	for i in 0 ..< count {
		r := recs[i]
		h = h * 31 + u32(r.id)
		h = h * 31 + u32(r.score)
		h = h * 31 + u32(r.name_len)
		for k in 0 ..< r.name_len {h = h * 31 + u32(names[r.name_off + k])}
		h = h * 31 + u32(r.tag_cnt)
		for k in 0 ..< r.tag_cnt {h = h * 31 + u32(tags[r.tag_off + k])}
	}
	fmt.printf("%d %d %d\n", count, tlen, h)
}

// JSON serialise + parse benchmark (Go, garbage collected). Same hand-written serialiser / iterative parser / checksum as json_serde.c.
package main

import (
	"fmt"
	"os"
	"strconv"
)

type Rec struct{ id, nameOff, nameLen, score, tagOff, tagCnt uint64 }

var x uint32 = 12345

func next() uint32 { x = x*1664525 + 1013904223; return x }
func wnum(t []byte, p int, v uint64) int {
	d := 1
	for u := v; u >= 10; u /= 10 {
		d++
	}
	for k := d - 1; k >= 0; k-- {
		t[p+k] = byte('0' + v%10)
		v /= 10
	}
	return p + d
}
func wlit(t []byte, p int, s string) int {
	for i := 0; i < len(s); i++ {
		t[p] = s[i]
		p++
	}
	return p
}

func main() {
	n := 4000000
	if len(os.Args) > 1 {
		n, _ = strconv.Atoi(os.Args[1])
	}
	text := make([]byte, n*80+16)
	p := 0
	text[p] = '['
	p++
	for i := 0; i < n; i++ {
		if i > 0 {
			text[p] = ','
			p++
		}
		p = wlit(text, p, "{\"id\":")
		p = wnum(text, p, uint64(i))
		p = wlit(text, p, ",\"name\":\"")
		nl := 3 + int((next()>>16)%8)
		for k := 0; k < nl; k++ {
			text[p] = byte('a' + (next()>>16)%26)
			p++
		}
		p = wlit(text, p, "\",\"score\":")
		cents := uint64((next() >> 8) % 1000000)
		p = wnum(text, p, cents/100)
		text[p] = '.'
		p++
		text[p] = byte('0' + cents%100/10)
		p++
		text[p] = byte('0' + cents%10)
		p++
		p = wlit(text, p, ",\"tags\":[")
		tc := int((next() >> 16) % 5)
		for k := 0; k < tc; k++ {
			if k > 0 {
				text[p] = ','
				p++
			}
			p = wnum(text, p, uint64((next()>>16)%100))
		}
		p = wlit(text, p, "]}")
	}
	text[p] = ']'
	p++
	tlen := p
	recs := make([]Rec, n+1)
	names := make([]byte, n*10+8)
	tags := make([]byte, n*4+8)
	count, noff, toff, q := 0, 0, 0, 1
	for {
		c := text[q]
		if c == ']' {
			break
		}
		if c == ',' {
			q++
			continue
		}
		q++
		var r Rec
		for {
			q++
			k0 := text[q]
			for text[q] != '"' {
				q++
			}
			q += 2
			if k0 == 'i' {
				var v uint64
				for text[q] >= '0' && text[q] <= '9' {
					v = v*10 + uint64(text[q]-'0')
					q++
				}
				r.id = v
			} else if k0 == 'n' {
				q++
				r.nameOff = uint64(noff)
				for text[q] != '"' {
					names[noff] = text[q]
					noff++
					q++
				}
				r.nameLen = uint64(noff) - r.nameOff
				q++
			} else if k0 == 's' {
				var v uint64
				for text[q] >= '0' && text[q] <= '9' {
					v = v*10 + uint64(text[q]-'0')
					q++
				}
				q++
				v = v*100 + uint64(text[q]-'0')*10 + uint64(text[q+1]-'0')
				q += 2
				r.score = v
			} else {
				q++
				r.tagOff = uint64(toff)
				for text[q] != ']' {
					if text[q] == ',' {
						q++
					}
					var v uint64
					for text[q] >= '0' && text[q] <= '9' {
						v = v*10 + uint64(text[q]-'0')
						q++
					}
					tags[toff] = byte(v)
					toff++
				}
				r.tagCnt = uint64(toff) - r.tagOff
				q++
			}
			if text[q] == ',' {
				q++
			} else {
				q++
				break
			}
		}
		recs[count] = r
		count++
	}
	var h uint32 = 7
	for i := 0; i < count; i++ {
		r := recs[i]
		h = h*31 + uint32(r.id)
		h = h*31 + uint32(r.score)
		h = h*31 + uint32(r.nameLen)
		for k := uint64(0); k < r.nameLen; k++ {
			h = h*31 + uint32(names[r.nameOff+k])
		}
		h = h*31 + uint32(r.tagCnt)
		for k := uint64(0); k < r.tagCnt; k++ {
			h = h*31 + uint32(tags[r.tagOff+k])
		}
	}
	fmt.Println(count, tlen, h)
}

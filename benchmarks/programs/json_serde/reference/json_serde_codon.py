import sys

M = 0xFFFFFFFF

class Rec:
    id: int
    name_off: int
    name_len: int
    score: int
    tag_off: int
    tag_cnt: int

    def __init__(self):
        self.id = 0
        self.name_off = 0
        self.name_len = 0
        self.score = 0
        self.tag_off = 0
        self.tag_cnt = 0

def wnum(t: List[u8], p: int, v: int) -> int:
    d = 1
    u = v
    while u >= 10:
        u //= 10
        d += 1
    k = d - 1
    while k >= 0:
        t[p + k] = u8(48 + v % 10)
        v //= 10
        k -= 1
    return p + d

def wlit(t: List[u8], p: int, s: str) -> int:
    for ch in s:
        t[p] = u8(ord(ch))
        p += 1
    return p

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 4000000
    x = 12345
    text = [u8(0)] * (n * 80 + 16)
    p = 0
    text[p] = u8(91)
    p += 1
    for i in range(n):
        if i > 0:
            text[p] = u8(44)
            p += 1
        p = wlit(text, p, '{"id":')
        p = wnum(text, p, i)
        p = wlit(text, p, ',"name":"')
        x = (x * 1664525 + 1013904223) & M
        nl = 3 + (x >> 16) % 8
        for k in range(nl):
            x = (x * 1664525 + 1013904223) & M
            text[p] = u8(97 + (x >> 16) % 26)
            p += 1
        p = wlit(text, p, '","score":')
        x = (x * 1664525 + 1013904223) & M
        cents = (x >> 8) % 1000000
        p = wnum(text, p, cents // 100)
        text[p] = u8(46)
        p += 1
        text[p] = u8(48 + cents % 100 // 10)
        p += 1
        text[p] = u8(48 + cents % 10)
        p += 1
        p = wlit(text, p, ',"tags":[')
        x = (x * 1664525 + 1013904223) & M
        tc = (x >> 16) % 5
        for k in range(tc):
            if k > 0:
                text[p] = u8(44)
                p += 1
            x = (x * 1664525 + 1013904223) & M
            p = wnum(text, p, (x >> 16) % 100)
        p = wlit(text, p, "]}")
    text[p] = u8(93)
    p += 1
    tlen = p
    # ---- parse ----
    recs = List[Rec](n + 1)
    names = [u8(0)] * (n * 10 + 8)
    tags = [u8(0)] * (n * 4 + 8)
    count = 0
    noff = 0
    toff = 0
    q = 1
    while True:
        c = text[q]
        if c == u8(93):
            break
        if c == u8(44):
            q += 1
            continue
        q += 1  # '{'
        r = Rec()
        while True:
            q += 1  # opening quote of the key
            k0 = text[q]
            while text[q] != u8(34):
                q += 1
            q += 2  # closing quote, ':'
            if k0 == u8(105):
                v = 0
                while text[q] >= u8(48) and text[q] <= u8(57):
                    v = v * 10 + int(text[q]) - 48
                    q += 1
                r.id = v
            elif k0 == u8(110):
                q += 1
                r.name_off = noff
                while text[q] != u8(34):
                    names[noff] = text[q]
                    noff += 1
                    q += 1
                r.name_len = noff - r.name_off
                q += 1
            elif k0 == u8(115):
                v = 0
                while text[q] >= u8(48) and text[q] <= u8(57):
                    v = v * 10 + int(text[q]) - 48
                    q += 1
                q += 1  # '.'
                v = v * 100 + (int(text[q]) - 48) * 10 + (int(text[q + 1]) - 48)
                q += 2
                r.score = v
            else:
                q += 1  # '['
                r.tag_off = toff
                while text[q] != u8(93):
                    if text[q] == u8(44):
                        q += 1
                    v = 0
                    while text[q] >= u8(48) and text[q] <= u8(57):
                        v = v * 10 + int(text[q]) - 48
                        q += 1
                    tags[toff] = u8(v)
                    toff += 1
                r.tag_cnt = toff - r.tag_off
                q += 1  # ']'
            if text[q] == u8(44):
                q += 1
            else:
                q += 1
                break
        recs.append(r)
        count += 1
    # ---- checksum over the parsed records ----
    h = 7
    for i in range(count):
        r = recs[i]
        h = (h * 31 + (r.id & M)) & M
        h = (h * 31 + (r.score & M)) & M
        h = (h * 31 + (r.name_len & M)) & M
        for k in range(r.name_len):
            h = (h * 31 + int(names[r.name_off + k])) & M
        h = (h * 31 + (r.tag_cnt & M)) & M
        for k in range(r.tag_cnt):
            h = (h * 31 + int(tags[r.tag_off + k])) & M
    print(count, tlen, h)

main()

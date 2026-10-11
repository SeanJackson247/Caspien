import sys

def hash_bytes(b: List[u8]):
    h = u32(7)
    for c in b:
        h = h * u32(31) + u32(int(c))
    return h

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 4000000
    x = u32(12345)
    text = List[u8](n)
    for i in range(n):
        x = x * u32(1664525) + u32(1013904223)
        r = int((x >> u32(16)) % u32(27))
        text.append(u8(32) if r == 26 else u8(97 + r))

    words = 0
    longest = 0
    cur = 0
    for i in range(n):
        if text[i] == u8(32):
            if cur > 0:
                words += 1
                if cur > longest:
                    longest = cur
                cur = 0
        else:
            cur += 1
    if cur > 0:
        words += 1
        if cur > longest:
            longest = cur

    abc = 0
    for i in range(n - 2):
        if text[i] == u8(97) and text[i + 1] == u8(98) and text[i + 2] == u8(99):
            abc += 1

    rev = List[u8](n)
    for i in range(n):
        rev.append(text[n - 1 - i])
    hrev = hash_bytes(rev)

    es = 0
    for i in range(n):
        if text[i] == u8(101):
            es += 1
    m = n + es
    rep = List[u8](m)
    for i in range(n):
        if text[i] == u8(101):
            rep.append(u8(51))
            rep.append(u8(51))
        else:
            rep.append(text[i])
    hrep = hash_bytes(rep)

    up = List[u8](n)
    for i in range(n):
        c = text[i]
        up.append(c if c == u8(32) else c - u8(32))
    hup = hash_bytes(up)
    print(words, longest, abc, int(hrev), m, int(hrep), int(hup))

main()

import sys

KS = [1, 2, 3, 4, 6, 12]
QK = [1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12]
QV = [0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487]
GOLD = UInt[64](0x9E3779B97F4A7C15)

def slot(kk: int, shift: int) -> int:
    return int((UInt[64](kk) * GOLD) >> UInt[64](shift))

def lookup(tkey: List[int], tcnt: List[int], kk: int, bits: int, cap: int) -> int:
    h = slot(kk, 64 - bits)
    while True:
        if tkey[h] == 0:
            return 0
        if tkey[h] == kk:
            return tcnt[h]
        h = (h + 1) & (cap - 1)

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 30000000
    last = 42
    seq = List[u8](n + 1)
    for i in range(n):
        last = (last * 3877 + 29573) % 139968
        c = 0
        if last < 42404:
            c = 0
        elif last < 70117:
            c = 1
        elif last < 97767:
            c = 2
        else:
            c = 3
        seq.append(u8(c))
    first12 = 0
    i = 0
    while i < 12 and i < n:
        first12 = (first12 << 2) | int(seq[i])
        i += 1
    distinct = [0] * 6
    counts = [0] * 12
    nq = 0
    for ki in range(6):
        k = KS[ki]
        maxd = 1 << (2 * k)
        win = n - k + 1 if n >= k else 0
        if win < maxd:
            maxd = win
        if maxd > 139968:
            maxd = 139968
        bits = 1
        while (1 << bits) < 2 * maxd:
            bits += 1
        cap = 1 << bits
        tkey = [0] * cap
        tcnt = [0] * cap
        mask = (1 << (2 * k)) - 1
        key = 0
        nd = 0
        shift = 64 - bits
        for i in range(n):
            key = ((key << 2) | int(seq[i])) & mask
            if i + 1 >= k:
                kk = key + 1
                h = slot(kk, shift)
                while True:
                    if tkey[h] == 0:
                        tkey[h] = kk
                        tcnt[h] = 1
                        nd += 1
                        break
                    if tkey[h] == kk:
                        tcnt[h] += 1
                        break
                    h = (h + 1) & (cap - 1)
        distinct[ki] = nd
        for q in range(11):
            if QK[q] != k:
                continue
            counts[nq] = lookup(tkey, tcnt, QV[q] + 1, bits, cap)
            nq += 1
        if k == 12:
            counts[nq] = lookup(tkey, tcnt, first12 + 1, bits, cap)
            nq += 1
    out = ""
    for i in range(6):
        out += str(distinct[i]) + " "
    for i in range(12):
        out += (" " if i else "") + str(counts[i])
    print(out)

main()

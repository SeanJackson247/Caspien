import sys

ALU = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA"
CHARS = "ACGTBDHKMNRSVWYACGT"
THR = [37792, 54588, 71384, 109176, 111975, 114774, 117574, 120373, 123172, 125972, 128771, 131570, 134370, 137169, 139968, 42404, 70117, 97767, 139968]
HDR = [">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n"]

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 40000000
    cnt = [n * 2 // 10, n * 3 // 10, 0]
    cnt[2] = n - cnt[0] - cnt[1]
    off = [0, 0, 15]
    buf = List[u8](n + n // 60 + 1024)
    last = 42
    a = 0
    c = 0
    g = 0
    t = 0
    other = 0
    ai = 0
    alen = len(ALU)
    for s in range(3):
        for ch in HDR[s]:
            buf.append(u8(ord(ch)))
        col = 0
        for _ in range(cnt[s]):
            ch = 0
            if s == 0:
                ch = ord(ALU[ai])
                ai += 1
                if ai == alen:
                    ai = 0
            else:
                last = (last * 3877 + 29573) % 139968
                j = off[s]
                while last >= THR[j]:
                    j += 1
                ch = ord(CHARS[j])
            buf.append(u8(ch))
            if ch == 65:
                a += 1
            elif ch == 67:
                c += 1
            elif ch == 71:
                g += 1
            elif ch == 84:
                t += 1
            else:
                other += 1
            col += 1
            if col == 60:
                buf.append(u8(10))
                col = 0
        if col > 0:
            buf.append(u8(10))
    h = u32(7)
    for b in buf:
        h = h * u32(31) + u32(int(b))
    print(len(buf), int(h), a, c, g, t, other)

main()

import sys

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 100000000
    f = List[u8](n + 1)
    for _ in range(n + 1):
        f.append(u8(1))
    i = 2
    while i * i <= n:
        if f[i] != u8(0):
            j = i * i
            while j <= n:
                f[j] = u8(0)
                j += i
        i += 1
    count = 0
    for i in range(2, n + 1):
        count += int(f[i])
    print(count)

main()

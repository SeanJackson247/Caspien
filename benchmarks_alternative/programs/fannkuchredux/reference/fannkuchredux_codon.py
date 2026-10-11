import sys

def fannkuchredux(n: int):
    perm = [0] * 16
    perm1 = [0] * 16
    count = [0] * 16
    maxFlipsCount = 0
    permCount = 0
    checksum = 0
    r = n
    for i in range(n):
        perm1[i] = i
    while True:
        while r != 1:
            count[r - 1] = r
            r -= 1
        for i in range(n):
            perm[i] = perm1[i]
        flipsCount = 0
        k = perm[0]
        while k != 0:
            k2 = (k + 1) >> 1
            for i in range(k2):
                t = perm[i]
                perm[i] = perm[k - i]
                perm[k - i] = t
            flipsCount += 1
            k = perm[0]
        if flipsCount > maxFlipsCount:
            maxFlipsCount = flipsCount
        checksum += flipsCount if permCount % 2 == 0 else -flipsCount
        while True:
            if r == n:
                print(checksum)
                return maxFlipsCount
            perm0 = perm1[0]
            i = 0
            while i < r:
                j = i + 1
                perm1[i] = perm1[j]
                i = j
            perm1[r] = perm0
            count[r] -= 1
            if count[r] > 0:
                break
            r += 1
        permCount += 1

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 7
    m = fannkuchredux(n)
    print("Pfannkuchen(" + str(n) + ") = " + str(m))

main()

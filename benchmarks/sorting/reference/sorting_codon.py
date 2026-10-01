import sys

M32 = 0xFFFFFFFF

def quicksort(a: List[int], n: int):
    stack = [0] * 128
    sp = 0
    stack[sp] = 0
    sp += 1
    stack[sp] = n - 1
    sp += 1
    while sp > 0:
        sp -= 1
        hi = stack[sp]
        sp -= 1
        lo = stack[sp]
        while lo < hi:
            pivot = a[(lo + hi) // 2]
            i = lo
            j = hi
            while i <= j:
                while a[i] < pivot:
                    i += 1
                while a[j] > pivot:
                    j -= 1
                if i <= j:
                    t = a[i]
                    a[i] = a[j]
                    a[j] = t
                    i += 1
                    j -= 1
            if j - lo < hi - i:
                stack[sp] = i
                sp += 1
                stack[sp] = hi
                sp += 1
                hi = j
            else:
                stack[sp] = lo
                sp += 1
                stack[sp] = j
                sp += 1
                lo = i

def mergesort(a: List[int], tmp: List[int], n: int):
    src = a
    dst = tmp
    w = 1
    while w < n:
        lo = 0
        while lo < n:
            mid = lo + w if lo + w < n else n
            hi = lo + 2 * w if lo + 2 * w < n else n
            i = lo
            j = mid
            k = lo
            while i < mid and j < hi:
                if src[i] <= src[j]:
                    dst[k] = src[i]
                    i += 1
                else:
                    dst[k] = src[j]
                    j += 1
                k += 1
            while i < mid:
                dst[k] = src[i]
                i += 1
                k += 1
            while j < hi:
                dst[k] = src[j]
                j += 1
                k += 1
            lo += 2 * w
        t = src
        src = dst
        dst = t
        w *= 2
    if src is not a:
        for i in range(n):
            a[i] = src[i]

def siftdown(a: List[int], root: int, n: int):
    while True:
        c = 2 * root + 1
        if c >= n:
            break
        if c + 1 < n and a[c + 1] > a[c]:
            c += 1
        if a[root] >= a[c]:
            break
        t = a[root]
        a[root] = a[c]
        a[c] = t
        root = c

def heapsort(a: List[int], n: int):
    i = n // 2 - 1
    while i >= 0:
        siftdown(a, i, n)
        i -= 1
    e = n - 1
    while e > 0:
        t = a[0]
        a[0] = a[e]
        a[e] = t
        siftdown(a, 0, e)
        e -= 1

def bsearch_has(a: List[int], n: int, key: int) -> int:
    lo = 0
    hi = n
    while lo < hi:
        m = (lo + hi) // 2
        if a[m] < key:
            lo = m + 1
        else:
            hi = m
    return 1 if (lo < n and a[lo] == key) else 0

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 2000000
    x = 12345
    orig = [0] * n
    for i in range(n):
        x = (x * 1664525 + 1013904223) & M32
        orig[i] = x >> 1
    a = orig.copy()
    b = orig.copy()
    d = orig.copy()
    tmp = [0] * n
    quicksort(a, n)
    mergesort(b, tmp, n)
    heapsort(d, n)
    h = 0
    ok = True
    for i in range(n):
        h = (h * 31 + a[i]) & M32
        if a[i] != b[i] or a[i] != d[i]:
            ok = False
        if i > 0 and a[i - 1] > a[i]:
            ok = False
    bs = 0
    for k in range(n):
        x = (x * 1664525 + 1013904223) & M32
        key = x >> 1
        if k % 2 == 0:
            key = orig[key % n]
        bs += bsearch_has(a, n, key)
    ls = 0
    for k in range(20):
        x = (x * 1664525 + 1013904223) & M32
        key = x >> 1
        if k % 2 == 0:
            key = orig[key % n]
        for i in range(n):
            if orig[i] == key:
                ls += 1
                break
    print(h, bs, ls, "OK" if ok else "BAD")

main()

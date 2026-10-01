import sys

CAP = 262144
KEYS = 1048576
HOT = 131072
NB = 524288
NONE = 0xFFFFFFFF
M32 = 0xFFFFFFFF

def hsh(k: int) -> int:
    return ((k * 2654435761) & M32) >> 13

class Lru:
    key: List[int]
    val: List[int]
    prv: List[int]
    nxt: List[int]
    hn: List[int]
    bucket: List[int]
    head: int
    tail: int

    def __init__(self):
        self.key = [0] * CAP
        self.val = [0] * CAP
        self.prv = [0] * CAP
        self.nxt = [0] * CAP
        self.hn = [0] * CAP
        self.bucket = [NONE] * NB
        self.head = NONE
        self.tail = NONE

    def unlink_node(self, i: int):
        p = self.prv[i]
        q = self.nxt[i]
        if p != NONE:
            self.nxt[p] = q
        else:
            self.head = q
        if q != NONE:
            self.prv[q] = p
        else:
            self.tail = p

    def push_front(self, i: int):
        self.prv[i] = NONE
        self.nxt[i] = self.head
        if self.head != NONE:
            self.prv[self.head] = i
        else:
            self.tail = i
        self.head = i

    def find(self, k: int) -> int:
        i = self.bucket[hsh(k)]
        while i != NONE and self.key[i] != k:
            i = self.hn[i]
        return i

    def hash_remove(self, i: int):
        b = hsh(self.key[i])
        if self.bucket[b] == i:
            self.bucket[b] = self.hn[i]
            return
        j = self.bucket[b]
        while self.hn[j] != i:
            j = self.hn[j]
        self.hn[j] = self.hn[i]

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 20000000
    c = Lru()
    x = 12345
    size = 0
    hits = 0
    misses = 0
    s = 0
    for op in range(n):
        x = (x * 1664525 + 1013904223) & M32
        a = x
        x = (x * 1664525 + 1013904223) & M32
        y = x
        rng = KEYS if ((y >> 20) % 4 == 0) else HOT
        k = (a >> 8) % rng
        i = c.find(k)
        if (y >> 24) % 4 != 0:
            if i != NONE:
                hits += 1
                s += c.val[i] + k
                c.unlink_node(i)
                c.push_front(i)
            else:
                misses += 1
        elif i != NONE:
            c.val[i] = y
            c.unlink_node(i)
            c.push_front(i)
        else:
            if size == CAP:
                i = c.tail
                s += c.key[i]
                c.unlink_node(i)
                c.hash_remove(i)
            else:
                i = size
                size += 1
            c.key[i] = k
            c.val[i] = y
            b = hsh(k)
            c.hn[i] = c.bucket[b]
            c.bucket[b] = i
            c.push_front(i)
    fin = 0
    i = c.head
    while i != NONE:
        fin = (fin * 31 + c.key[i]) % 4294967296
        i = c.nxt[i]
    print(hits, misses, (s + fin) % 4294967296)

main()

import sys

class Node:
    value: u32
    dist: int
    e0: int
    e1: int
    e2: int
    e3: int

    def __init__(self, value: u32):
        self.value = value
        self.dist = -1
        self.e0 = 0
        self.e1 = 0
        self.e2 = 0
        self.e3 = 0

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 2000000
    x = u32(12345)
    nodes = List[Node](n)
    for i in range(n):
        x = x * u32(1664525) + u32(1013904223)
        nodes.append(Node(x & u32(0xFFFFFF)))
    nodes[n * 7 // 10].value = u32(0xFFFFFFFF)
    un = u32(n)
    for i in range(n):
        nd = nodes[i]
        nd.e0 = (i + 1) % n
        x = x * u32(1664525) + u32(1013904223)
        nd.e1 = int(x % un)
        x = x * u32(1664525) + u32(1013904223)
        nd.e2 = int(x % un)
        x = x * u32(1664525) + u32(1013904223)
        nd.e3 = int(x % un)
    q = List[int](n)
    for i in range(n):
        q.append(0)
    head = 0
    tail = 0
    q[tail] = 0
    tail += 1
    nodes[0].dist = 0
    maxdepth = 0
    needledist = -1
    s = u32(0)
    while head < tail:
        u = nodes[q[head]]
        head += 1
        s += u.value
        if u.value == u32(0xFFFFFFFF):
            needledist = u.dist
        if u.dist > maxdepth:
            maxdepth = u.dist
        d = u.dist + 1
        v = nodes[u.e0]
        if v.dist < 0:
            v.dist = d
            q[tail] = u.e0
            tail += 1
        v = nodes[u.e1]
        if v.dist < 0:
            v.dist = d
            q[tail] = u.e1
            tail += 1
        v = nodes[u.e2]
        if v.dist < 0:
            v.dist = d
            q[tail] = u.e2
            tail += 1
        v = nodes[u.e3]
        if v.dist < 0:
            v.dist = d
            q[tail] = u.e3
            tail += 1
    print(tail, maxdepth, needledist, int(s))

main()

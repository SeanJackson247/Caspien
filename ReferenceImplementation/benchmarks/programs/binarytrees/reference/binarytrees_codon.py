import sys

class Node:
    l: Optional[Node]
    r: Optional[Node]

    def __init__(self, l: Optional[Node], r: Optional[Node]):
        self.l = l
        self.r = r

def make(d: int) -> Node:
    if d > 0:
        return Node(make(d - 1), make(d - 1))
    return Node(None, None)

def check(n: Node) -> int:
    l = n.l
    if l is not None:
        return 1 + check(l) + check(n.r.__val__())
    return 1

def main():
    maxd = int(sys.argv[1]) if len(sys.argv) > 1 else 16
    if maxd < 6:
        maxd = 6
    t = make(maxd + 1)
    out = str(check(t))
    longlived = make(maxd)
    d = 4
    while d <= maxd:
        iters = 1 << (maxd - d + 4)
        s = 0
        for _ in range(iters):
            a = make(d)
            s += check(a)
        out += " " + str(s)
        d += 2
    out += " " + str(check(longlived))
    print(out)

main()

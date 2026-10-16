"""Independent model for ref_recursive_test.caspien."""
# list: 100 nodes, value i*3+1, linked in order; sumList, countList, and the ghost-table entries (100 nodes + the array block)
lst = sum(i * 3 + 1 for i in range(100))
print("list %d %d %d" % (lst, 100, 101))
print("clone %d" % 202)  # the clone adds another 100 nodes + its own array block
# tree: heap order, node i has children 2i+1, 2i+2; walk every 5-bit code (bit d picks left/right at depth d), sum the visited values
val = lambda i: i * 7 + 2
total = 0
for code in range(32):
    i = 0
    s = 0
    for d in range(5):
        s += val(i)
        i = 2 * i + 1 + ((code >> d) & 1)
    s += val(i)
    total += s
print("tree %d" % total)
# graph: 50 nodes, 3 LCG edges each, walk 400 steps taking edge (step % 3)
x = 12345
edges = []
for i in range(50):
    e = []
    for _ in range(3):
        x = (x * 1664525 + 1013904223) & 0xFFFFFFFF
        e.append(x % 50)
    edges.append(e)
cur, s = 0, 0
for step in range(400):
    s += cur * 11 + 5
    cur = edges[cur][step % 3]
print("graph %d" % s)
print("ping %d" % (5 + 3))
print("link %d" % (40 + 2))
print("dangle %d" % 100)   # seen once while the target lives; skipped once it is freed
print("leak %d" % 0)

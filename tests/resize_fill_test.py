"""Independent model for resize_fill_test.caspien."""
def grow(a, n, fill):
    return a[:n] + [fill] * (n - len(a)) if n > len(a) else a[:n]
def cs(a): return sum((i + 1) * v for i, v in enumerate(a))
a8 = [7, 7, 7]
for n, f in ((200, 5), (20, 1), (150, 0), (160, 3)): a8 = grow(a8, n, f)
a16 = [7, 7, 7]
for n, f in ((100, 0), (10, 4), (90, 0), (100, 777)): a16 = grow(a16, n, f)
a32 = [7, 7, 7]
for n, f in ((50, 0), (5, 2), (70, 0), (75, 100000)): a32 = grow(a32, n, f)
a64 = [7, 7, 7]
for n, f in ((40, 0), (4, 6), (60, 0), (70, 4294967296)): a64 = grow(a64, n, f)
print(len(a8), cs(a8), len(a16), cs(a16), len(a32), cs(a32), len(a64), cs(a64))

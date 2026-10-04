#!/usr/bin/env python3
"""Independent model of tests/dyn_growth_test.caspien: prints the expected values the test hard-codes (and asserts them)."""
N = 100000
a = [i * 3 + 1 for i in range(N)]
assert sum(a) == 14999950000
a = a[:10]; assert sum(a) == 145
a += [7] * 1000; assert (len(a), sum(a)) == (1010, 7145)
b = [1, 2, 3, 4, 5, 6, 7, 8] + [9] * 56          # resize to 64, fill 9
b = b[:40]; b += [4] * 10                          # shrink in place, grow with fill 4
assert (len(b), sum(b)) == (50, 364)
c = [1, 2, 3, 5]; c2 = c + [8] * 100               # resize(c, 4, 5), then the clone grown to 104 with fill 8
assert (sum(c), len(c2), sum(c2)) == (11, 104, 811)
g = list(range(1, 201))[:3] + [2, 2]               # 200 -> 3 (reallocs down) -> 5 with fill 2
assert (len(g), sum(g)) == (5, 10)
print("dyn_growth model ok")

#!/usr/bin/env python3
"""Independent model of tests/hashmap_growth_test.caspien."""
def pow2(n):
    c = 1
    while c < n: c *= 2
    return c
assert (pow2(100), pow2(8), pow2(1000)) == (128, 8, 1024)
keys = [(i * 7919 + 13) % 1000003 for i in range(50000)]
m = {k: i * 2 + 1 for i, k in enumerate(keys)}
assert len(m) == 50000
cap, seen = 2, set()
for k in keys:                                      # grow (double) before inserting when (count+1)*10 > cap*7
    if (len(seen) + 1) * 10 > cap * 7: cap *= 2
    seen.add(k)
assert cap == 131072
for i in range(5000): m[keys[i]] = 7
assert sum(m[k] for k in keys) == 2475035000
assert not any(k in m for k in range(2000000, 2000100))
assert sum(k + 1 for k in range(200)) == 20100
print("hashmap_growth model ok")

#!/usr/bin/env python3
"""Independent model of tests/hashmap_kv_test.caspien (and the ref twin): maps whose key and value types differ."""
a = {i: (i * 1000003 + 17) for i in range(200)}                       # u8 key -> u64 value
assert len(a) == 200
sum_a = sum(a[k] for k in range(200))
b = {i * 7919 + 13: (i * 3) & 0xFF for i in range(5000)}              # u64 key -> u8 value
assert len(b) == 5000
sum_b = sum(b[i * 7919 + 13] for i in range(5000))
c = {(i * 37) & 0xFFFF: (i * 2654435761) & 0xFFFFFFFF for i in range(5000)}   # u16 key -> u32 value
assert len(c) == 5000
sum_c = sum(c[(i * 37) & 0xFFFF] for i in range(5000))
absent = sum(1 for k in range(200, 256) if k in a)
print("sum_a", sum_a, "sum_b", sum_b, "sum_c", sum_c, "absent", absent)

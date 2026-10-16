#!/usr/bin/env python3
"""Independent model of tests/hashmap_remove_test.caspien (and the ref twin): a dict driven by the same LCG."""
x = 12345
d = {}
removed = found = 0
for i in range(20000):
    x = (x * 1103515245 + 12345) % 2147483648
    op = (x >> 16) % 4
    key = (x >> 8) % 5000
    if op < 2:
        d[key] = i
    elif op == 2:
        if key in d:
            del d[key]
            removed += 1
    else:
        if key in d:
            found += 1
total = sum(d.get(k, 0) for k in range(5000))
present = sum(1 for k in range(5000) if k in d)
print("count", len(d), "removed", removed, "found", found, "total", total, "present", present)

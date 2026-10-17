#!/usr/bin/env python3
"""Independent model of tests/same_name_locals_test.caspien."""
assert sum((i * 7919 + 13) % 1000003 + 5 for i in range(50000)) == 24996304419
assert sum(i + i + 1 for i in range(10)) == 100
assert sum(j + 4000000000 for j in range(3)) == 12000000003
print("same_name_locals model ok")

#!/usr/bin/env python3
"""Independent model of tests/string_bulk_test.caspien (character codes: a=97, b=98, x=120)."""
s = "ab" * 1000 + "x" * 500
assert len(s) == 2500 and sum(ord(c) * (i % 7 + 1) for i, c in enumerate(s)) == 1019752
t = "hello"
assert t[3:99] == "lo" and t[4:2] == "" and t[5:9] == "" and len(t[0:5]) == 5   # sub clamps its end; start >= end is empty
u = s[1990:2300]; assert len(u) == 310 and sum(ord(c) for c in u) == 36975
d = s + s; assert len(d) == 5000 and sum(ord(c) * (i % 5 + 1) for i, c in enumerate(d)) == 1530000
print("string_bulk model ok")

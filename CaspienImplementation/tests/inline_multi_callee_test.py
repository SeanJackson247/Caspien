#!/usr/bin/env python3
# Independent model for inline_multi_callee_test.caspien
def f(x, n):
    v = x
    for k in range(1, n + 1):
        v = (v * 3 + k) & 16777215
    return v
a = f(7, 60)
b = f(a + 1, 60)
print("big", a + b)
print("once", f(11, 60))
d = (3 * 5 + 1) & 16777215
e = (d * 5 + 1) & 16777215
print("small", d + e)

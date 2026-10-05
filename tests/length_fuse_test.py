#!/usr/bin/env python3
# Independent model for length_fuse_test.caspien
M64 = 1 << 64
a = [i * 3 + 1 for i in range(100)]
s1 = sum(a[i] for i in range(150) if i < 100)
m1 = sum(1 for i in range(150) if i >= 100)
print("T1 sum", s1)
print("T1 miss", m1)
b = [0] * 10
s2 = 0
m2 = 0
for r in range(20):
    fv = r + 1
    sz = 10
    if r & 3 == 1: sz = 120
    if r & 3 == 2: sz = 35
    if r & 3 == 3: sz = 149
    if sz > len(b): b = b + [fv] * (sz - len(b))
    else: b = b[:sz]
    for i in range(150):
        if i < len(b): s2 += b[i] + i
        else: m2 += 1
print("T2 sum", s2)
print("T2 miss", m2)
c = [0] * 64
d = [0] * 300
s3 = 0
m3 = 0
for i in range(300):
    if i < 64: c[i] = i * 7 + 2
    else: m3 += 1
    if i < 300: d[i] = i + 5
    else: m3 += 1000
    if i < 64: s3 += c[i]
    if i < 300: s3 += d[i] * 2
print("T3 sum", s3)
print("T3 miss", m3)
h = [0] * 64
w = [0] * 20
s4 = 0
m4 = 0
for i in range(100):
    if i < 64: h[i] = (i * 5) & 255
    else: m4 += 1
    if i < 20: w[i] = (i * 100003) & 0xFFFFFFFF
    else: m4 += 1000
for i in range(100):
    if i < 64: s4 += h[i]
    if i < 20: s4 += w[i]
print("T4 sum", s4)
print("T4 miss", m4)
s5 = 0
m5 = 0
for r in range(20):
    for i in range(64):
        j = r * 8 + i
        if j < 100: s5 += a[j]
        else: m5 += 1
print("T5 sum", s5)
print("T5 miss", m5)

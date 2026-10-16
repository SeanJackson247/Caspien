"""Independent model for loop_hoist_test.caspien: prints the expected output lines."""
a = [(i * 7 + 3) & 4095 for i in range(1024)]
b = [a[(i * 31) & 1023] + i for i in range(1024)]
print("S1", sum(b))

c = [0]
s2 = 0
for k in range(40):
    sz = k + 2
    c = c[:sz] + [k + 1] * max(0, sz - len(c))
    s2 += sum(c[j] * (j + 1) for j in range(sz))
print("S2", s2)

d = [0, 0]
s3 = 0
for k in range(30):
    sz = 400 if (k & 1) == 0 else 3
    d = d[:sz] + [k + 5] * max(0, sz - len(d))
    d = [d[j] + j for j in range(sz)]
    s3 += sum(d)
print("S3", s3)

p = [(i * 13 + 1) & 255 for i in range(1024)]
q = [0] * 1024
for r in range(3):
    for i in range(64):
        for j in range(16):
            t = i * 16 + j
            u = p[t]
            v = p[(t * 5) & 1023]
            u = u * 3 + v + q[t]
            q[t] = u
print("S4", sum(q))

e = [(i * 5 + 1) & 1023 for i in range(512)]
s5 = 0
for i in range(512):
    y = ((e[i] + i) * 2654435761 + 12345) & 16777215
    e[i] = y
    s5 += y
print("S5", s5)

s6 = 0
for i in range(1024):
    if (i & 3) == 0:
        continue
    if i > 900:
        break
    s6 += a[i] + b[i]
print("S6", s6)

s7 = 0
for k in range(8):
    sz = 16 + k
    t = [k] * sz
    t = [j * k + 1 for j in range(sz)]
    s7 += sum(t)
print("S7", s7)

a1 = [i + 1 for i in range(256)]
a2 = [i * 2 for i in range(256)]
a3 = [i * 3 + 7 for i in range(256)]
a4 = [(i * 11) & 255 for i in range(256)]
a5 = [255 - i for i in range(256)]
s8 = 0
for r in range(4):
    for i in range(256):
        s8 += a1[i] + a2[i] * 2 + a3[i] * 3 + a4[i] * 4 + a5[i] * 5 + r
print("S8", s8)

h = [(i * 7) & 255 for i in range(300)]
w = [(i * 100003) & 0xFFFFFFFF for i in range(300)]
s9 = 0
for r in range(5):
    for i in range(300):
        s9 += h[i] + w[i]
        h[i] = (i + r) & 255
print("S9", s9)

cl = [x + 1 for x in a]
print("S10", sum(a) + sum(x * 2 for x in cl))

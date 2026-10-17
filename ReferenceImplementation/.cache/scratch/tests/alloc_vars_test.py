"""Independent model for alloc_vars_test.caspien."""
n, m = 200, 7
s1, s2, s3 = 0, 1, 2
for i in range(n):
    a, b, c = i, i * 2, i * 3
    s1 += a + b * 2 + c * 3
    s2 = (s2 * 31 + c) & 1048575
    b = s3 + i
    s3 = (s3 + b) & 65535
print("A", s1 + s2 * 7 + s3 * 13)

acc, mix, cnt = 0, 5, 0
d = [0, 0]
for i in range(n):
    sz = 2 + ((i * 5) & 15)
    d = d[:sz] + [i + 1] * max(0, sz - len(d))
    cl = list(d)
    k = sum(cl[j] * (j + 1) for j in range(sz))
    d[0] = k
    acc += k
    mix = (mix * 33 + k) & 4194303
    cnt += sz
print("B", acc + mix * 3 + cnt * 11 + m)

fa = [3, 5, 7, 9, 11, 13, 15, 17]
s, x = 0, 1
for i in range(n):
    q = (i * 3) & 7
    s += fa[q]
    s += i + 1
    x = (x * 17 + s) & 1048575
print("C", s + x * 5)

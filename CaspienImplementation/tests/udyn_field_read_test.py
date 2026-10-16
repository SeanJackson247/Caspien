# Model for udyn_field_read_test.caspien
M = (1 << 64) - 1
n = 40
a = [(i * 7 + 3) & M for i in range(n)]
b = [(i * i + 11) & M for i in range(n)]
p = [(i * 13 + 5) & 255 for i in range(n)]
q = [(i * 100003 + 77) & 0xffffffff for i in range(n)]
r = [(i * 1000000007 + 123456789) & M for i in range(n)]
s = [(i * 977 + 9) & 0xffff for i in range(n)]
t = [(255 - i) & 255 for i in range(n)]
print("S1 %d %d %d %d %d %d %d" % (sum(a) & M, sum(x * 3 for x in b) & M, sum(p), sum(q), sum(r) & M, sum(x * 5 for x in s), sum(t)))
print("S2 %d %d" % ((a[0] + b[1] + a[39]) & M, (p[3] + q[5] + r[7] + s[11] + t[13]) & M))
chain = 0
cur = 1
for i in range(n):
    nxt = a[cur] % 40
    b[cur] = (b[cur] + 1) & M
    if b[nxt] % 2 == 0:
        chain = (chain + b[nxt]) & M
    else:
        chain = (chain + a[cur]) & M
    cur = nxt
print("S3 %d %d" % (chain, cur))
mx = 0
for i in range(n):
    if t[i] > mx:
        mx = t[i] + p[i]
print("S4 %d" % mx)

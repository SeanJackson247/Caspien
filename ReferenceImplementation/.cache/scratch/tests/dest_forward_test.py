"""Independent model for dest_forward_test.caspien (all values stay below 2**64; unsigned subtraction never underflows in the test data)."""
M = (1 << 64) - 1
n = 200
a8 = [(i * 37 + 11) & 0xFF for i in range(n)]
a16 = [(i * 4099 + 7) & 0xFFFF for i in range(n)]
a32 = [(i * 1000003 + 5) & 0xFFFFFFFF for i in range(n)]
a64 = [(i * 7919 + 13) % 100003 for i in range(n)]
s1 = s2 = s3 = s4 = 0
for i in range(n):
    s1 += a8[i] + a16[i]
    s2 += a32[i] % 1000003 + a64[i]
for i in range(1, n):
    lo, hi = a64[i - 1], a64[i]
    d = hi
    e = lo
    d = (e - d) & M
    if hi > lo:
        d = hi - lo
    s3 += d % 1000
    m = (lo + hi) // 2
    m //= 2
    s4 += m
x, y, z, acc = 5, 11, 3, 0
for i in range(n):
    x = a64[i]
    y = (x - y) & M
    if y > 100000:
        y -= 100000
    z = (x + y) >> 1
    z -= 1
    x = z
    z = x * 3
    w = x
    acc += w + z + (y % 7)
    acc ^= z
    acc &= 4294967295
t, u = 0, 9
for i in range(n):
    t = a64[i]
    u += t
    t = a32[i]
    u ^= t
    if u > 1000000000:
        u -= 999999999
print("%d %d %d %d" % (s1, s2, s3, s4))
print("%d %d %d %d %d" % (x, y, z, acc, u))

"""Independent model for static_cache_test.caspien (f32 results rounded after every operation)."""
import math, struct
r = lambda v: struct.unpack('f', struct.pack('f', v))[0]
ka, kb, kc = 1.5, 0.25, -0.75
fa, fb = 1.5, 0.25
m = [0.5, 0.25, 0.125, 0.75, 0.375, 0.625, 0.875, 0.0625]
s = 0.0
for _ in range(40):
    s = s * 0.5 + ka * kb + ka * kc
print("A=%.17e" % s)
t = 0.0
for _ in range(40):
    t = r(r(r(r(t * 0.5) + r(fa * fb)) + r(fa * fa)))
print("B=%.9e" % t)
u = 0.0
for _ in range(30):
    u = u * 0.5 + (m[0] * m[1] + m[2] * m[3])
    u = u + (m[4] * m[5] + m[6] * m[7])
    u = u + (m[0] * m[7] + m[1] * m[6])
    u = u + (m[2] * m[5] + m[3] * m[4])
print("C=%.17e" % u)
v = 0.0
wr = 0.0
for _ in range(25):
    wr = wr + ka
    v = (v + wr * ka) + wr * kb
print("D=%.17e %.17e" % (v, wr))
w = 0.0
for _ in range(6):
    ka = ka + 1.0
    w = (w + ka * kb) + ka * kc
print("E=%.17e %.17e" % (w, ka))
x = 0.0
for _ in range(5):
    for _ in range(7):
        x = (x + kb * kc) + kb * kb
    x = x * 0.5 + kc * kc
print("F=%.17e" % x)
y = 0.0
for _ in range(20):
    y = (y + math.sqrt(kb * kb + kc * kc)) + kb
print("G=%.17e" % y)
p = 0.0
for _ in range(10):
    p = (p + kb) + kc
for _ in range(10):
    p = (p * m[0] + m[1]) + m[1] * m[1]
print("H=%.17e" % p)
def early(n):
    s = 0.0
    for i in range(n):
        s = s + kb * kc
        if i == 3:
            return s * kb
    return s
print("I=%.17e %.17e" % (early(10), early(2)))

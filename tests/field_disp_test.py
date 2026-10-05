#!/usr/bin/env python3
# Independent model for field_disp_test.caspien
import struct
def f32(x): return struct.unpack('<f', struct.pack('<f', x))[0]
a, b, c, d, e, f, g = 7, 250, 65000, 123456789, 2.0, 0.5, 11
def show(tag): print("%s %d %d %d %d %.3f %.3f %d" % (tag, a, b, c, d, e, f, g))
show("start")
for i in range(4):
    k = i + 1
    a = (a * 3 + k) % (1 << 64)
    b = (b + k) & 0xFF
    c = (c * 5 + k) & 0xFFFF
    d = (d + 4000000000) & 0xFFFFFFFF
    e = e * 1.5 + 0.25
    f = f32(f + 2.5)
    g = (g + a) % (1 << 64)
    show("upd")
b, c, d, g = 200, 60000, 4294967295, 77
show("consts")
print("sum %d" % ((a + b + c + d + g) % (1 << 64)))

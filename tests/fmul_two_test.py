"""Independent model for fmul_two_test.caspien (f32 results rounded after every operation)."""
import struct
r = lambda v: struct.unpack('f', struct.pack('f', v))[0]
a, b, c, acc, gd = 1.25, -3.5, 0.0, 0.0, 0.3
x, y, z, accf, gf = r(1.25), r(-3.5), 0.0, 0.0, r(0.3)
for _ in range(100):
    c = 2.0 * a
    a = b * 2.0
    b = c * 2.0 * 0.375 - 1.0
    acc = gd * 2.0 + acc
    gd = b * 0.5 + 0.1
    z = r(2.0 * x)
    x = r(y * 2.0)
    y = r(r(r(z * 2.0) * 0.375) - 1.0)
    accf = r(r(gf * 2.0) + accf)
    gf = r(r(y * 0.5) + 0.1)
print("F64=%.17e %.17e %.17e %.17e %.17e" % (a, b, c, acc, gd))
print("F32=%.9e %.9e %.9e %.9e %.9e" % (x, y, z, accf, gf))

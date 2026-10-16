"""Independent model for float_direct_test.caspien."""
import struct
r = lambda x: struct.unpack('f', struct.pack('f', x))[0]
p, q, rr, s = 1.0, 2.0, 3.0, 4.0
a, b, c, d = 1.0, 2.0, 3.0, 4.0
for _ in range(200):
    p = q - rr + 0.5; q = p * 0.5 + s; rr = rr * 0.75 + q; s = (p + q) * 0.25 - rr
    a = r(r(b - c) + 0.5); b = r(r(a * 0.5) + d); c = r(r(c * 0.75) + b); d = r(r(r(a + b) * 0.25) - c)
    p = q * (rr * 0.5 + s) * 0.5 + 1.0; a = r(r(r(b * r(r(c * 0.5) + d)) * 0.5) + 1.0)
    s = 2.0 * q * s * 0.01 + 0.25; d = r(r(r(r(2.0 * b) * d) * 0.01) + 0.25)
print("F64=%.17e %.17e %.17e %.17e" % (p, q, rr, s))
print("F32=%.9e %.9e %.9e %.9e" % (a, b, c, d))

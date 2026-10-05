"""Independent model for global_const_addr_test.caspien (f32 results rounded after every operation)."""
import struct
r32 = lambda v: struct.unpack('f', struct.pack('f', v))[0]
a8 = [3, 250, 17, 99, 128, 1, 0, 77]
a16 = [65000, 2, 300, 4000, 5, 60000, 7, 8]
a32 = [4000000000, 5, 3000000000, 1, 99, 123456789, 42, 7]
a64 = [10000000000, 2, 30000000000, 4, 5, 6, 7, 800000000000]
f64s = [1.5, 2.25, -3.0, 0.125, 8.0, -0.5]
f32s = [r32(v) for v in f64s]
rounds = 25
s8 = s16 = s32 = s64 = 0
for r in range(rounds):
    s8 += a8[0] + a8[1] * 2 + a8[7]
    s16 += a16[0] + a16[5] + a16[7]
    s32 += a32[0] + a32[2] + a32[7]
    s64 += a64[0] + a64[7] + a64[3]
    a8[2] = (r * 3 + 1) & 0xFF
    a8[3] = a8[2]
    a16[1] = (r * 700 + 5) & 0xFFFF
    a16[2] = a16[1]
    a32[1] = (r * 100000007 + 9) & 0xFFFFFFFF
    a32[3] = a32[1]
    a64[1] = r * 1000003 + 11
    a64[4] = a64[1]
    a8[4] = 200
    a16[4] = 40000
    a32[4] = 3999999999
    a64[5] = 123456789012
    s8 += a8[2] + a8[3] + a8[4]
    s16 += a16[1] + a16[2] + a16[4]
    s32 += a32[1] + a32[3] + a32[4]
    s64 += a64[1] + a64[4] + a64[5]
k = 3
sv = 0
for i in range(rounds):
    sv += a8[k] + a8[6] + a64[k] + a64[2]
    a64[k] += 1
    a8[6] += 1
fa = 0.0
fb = 0.0
for r in range(rounds):
    fa = fa + f64s[0] * f64s[3]
    f64s[1] = fa * 0.001
    f64s[2] = f64s[1]
    fa = fa + f64s[2] * f64s[4]
    fb = r32(fb + r32(f32s[0] * f32s[3]))
    f32s[1] = r32(fb * r32(0.001))
    f32s[2] = f32s[1]
    fb = r32(fb + r32(f32s[2] * f32s[4]))
print("K %.17e %.17e %.17e %.9e %.9e %.9e" % (fa, f64s[1], f64s[2], fb, f32s[1], f32s[2]))
print("I %d %d %d %d %d" % (s8, s16, s32, s64, sv))
print("J %d %d %d %d %d %d" % (a8[2], a16[2], a32[3], a8[6], a64[3], a64[4]))

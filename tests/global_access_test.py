"""Independent model for global_access_test.caspien (integer PASS values and the two float finals)."""
import struct
r32 = lambda x: struct.unpack('f', struct.pack('f', x))[0]
g8, g16, g32, g64 = 200, 60000, 4000000000, 1099511627776
for i in range(1000):
    g8 = (g8 + 7) & 0xFF; g16 = (g16 * 3 + 1) & 0xFFFF
    g32 = (g32 * 5 + 3) & 0xFFFFFFFF; g64 = (g64 * 7 + i) & (2**64 - 1)
print("u8", g8, "u16", g16, "u32", g32, "u64", g64)
f32, f64 = 1.5, 2.25
for _ in range(100):
    f32 = r32(r32(f32 * 1.5) + 0.25); f64 = f64 * 1.25 + 0.5
print("F32=%.9e" % f32, "F64=%.17e" % f64)
arr = [1, 2, 3]; sc = 0
for i in range(300):
    arr[i % 3] += i; sc += arr[(i + 1) % 3]
print("arr0", arr[0], "sum", sc)

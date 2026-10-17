# independent model of tests/pure_rt_test.caspien (wrapping u64)
M = (1 << 64) - 1
def poly(x):
    s = 7
    for i in range(8):
        s = (s * 31 + x + i) & M
    return s
def collatzish(x):
    v = x
    for _ in range(40):
        v = (v * 3 + 1) & M if v & 1 else v >> 1
    return v
def rotl(x, n):
    n %= 64
    return ((x << n) | (x >> (64 - n))) & M if n else x
def mix(a, b):
    return poly(a) ^ rotl(collatzish(b), 13)
print(mix(3, 27), collatzish(97), poly(12345), 1, 42, 5)

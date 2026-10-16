"""Independent model for const32_test.caspien: prints the expected A/B/C/D/E/LO lines."""
M = 2**64 - 1
x = 88172645463325252; a = b = c = d = e = lo = 0
for _ in range(1000):
    x = (x * 6364136223846793005 + 1442695040888963407) & M
    a += x & 0xFFFFFFFF; b += x & 0x80000000; c += x & 0xFFFFFFF0
    d ^= (x + 3000000000) & M
    lo += (x & 0xFFFFFFFF) > 2147483647
    e |= (x >> 7) & 0xFFFFFFFF
for k, v in zip("A B C D E LO".split(), (a, b, c, d, e, lo)):
    print(f"{k}={v}")

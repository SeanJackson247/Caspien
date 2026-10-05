"""Independent model for mul_big_const_test.caspien (u64 wrapping products)."""
M = (1 << 64) - 1
consts = [4294967297, 4294967295, 1099511627777, 1099511627775, 4611686018427387905, 9223372036854775807, 2147483649]
acc = [0] * 7
for i in range(300):
    x = (i * 1099511628211 + 14695981039346656037) & M
    y = (i * 6364136223846793005 + 1442695040888963407) & M
    for j, k in enumerate(consts):
        acc[j] = (acc[j] + x * k) & M
    acc[0] = (acc[0] + y * 4294967297) & M
    acc[1] = (acc[1] + 4294967295 * y) & M
    acc[2] = (acc[2] + 3 * x + 2 * y) & M
    acc[3] = (acc[3] + 4294967297 * x) & M
print(" ".join(str(v) for v in acc))

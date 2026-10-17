"""Independent model for udyn_read_test.caspien: prints the expected output line."""
M = 2**64 - 1
n = 100
a8 = [(i * 37 + 11) & 0xFF for i in range(n)]
a16 = [(i * 4099 + 7) & 0xFFFF for i in range(n)]
a32 = [(i * 1000003 + 5) & 0xFFFFFFFF for i in range(n)]
a64 = [(i * 6364136223846793005 + 1442695040888963407) & M for i in range(n)]
idx = [(i * 7 + 3) % 100 for i in range(n)]
s8 = s16 = s32 = s64 = sn = cnt = 0
k = 5
for i in range(n):
    s8 += a8[i]; s16 += a16[i]; s32 += a32[i]; s64 ^= a64[i]
    sn += a8[idx[i]] + a16[idx[idx[i]]]
    if a32[i] > 2000000000: cnt += 1
    if a8[k] == a8[i]: cnt += 100
s8 += a8[99] + a8[0] + a8[k]
print(s8, s16, s32, s64, sn, cnt)

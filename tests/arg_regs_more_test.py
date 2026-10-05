# Model for arg_regs_more_test.caspien (wrapping u64 arithmetic).
M = (1 << 64) - 1

def init():
    return [14, 933, 852, 771, 690, 609, 528, 447, 366, 285, 204, 123]

MODS = [10, 7, 1000, 6, 13, 24, 10, 7, 1000, 6, 13, 24]
MULS = [3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14]
DIVS = [3, 10, 12, 100, 6, 20, 3, 10, 12, 100, 6, 20]

def first_block(a):
    # a_i = a_i + a_{i+1}*mul + (a_{src} % mod), src per source lines
    srcs = [5, 6, 7, 8, 9, 10, 11, 0, 1, 2, 3, 4]
    for i in range(12):
        a[i] = (a[i] + a[(i + 1) % 12] * MULS[i] + a[srcs[i]] % MODS[i]) & M

def second_block(a, count):
    for i in range(count):
        x = a[(i + 3) % 12]
        c1 = a[(i + 2) % 12]
        a[i] = a[i] ^ ((x << ((c1 % 7) + 1)) & M)
        y = a[(i + 3) % 12]
        c2 = a[(i + 1) % 12]
        z = a[(i + 4) % 12]
        a[i] = ((a[i] & 4294967295) + (y >> ((c2 % 5) + 1)) + z // DIVS[i]) & M

def pressure(rounds):
    a = init()
    for _ in range(rounds):
        first_block(a)
        second_block(a, 12)
    s = 0
    for v in a:
        s = (s + v) & M
    return s

def across(rounds):
    a = init()
    for _ in range(rounds):
        first_block(a)
        second_block(a, 3)
    s = 0
    for v in a:
        s ^= v
    return s

print(pressure(40), across(25))

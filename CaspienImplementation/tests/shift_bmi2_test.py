"""Independent model for shift_bmi2_test.caspien."""
M64 = 2**64 - 1
cs = [0, 1, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33, 40, 63, 64, 65, 100, 127, 128, 200, 255]
vals = [1, 255, 43690, 3735928559, 12297829382473034410, 18446744073709551615]
for w in (8, 16, 32, 64):
    mask = (1 << w) - 1
    hl = hr = 0
    for c in [c for c in cs if c < (1 << w)]:
        for v in vals:
            v &= mask
            l = (v << c) & mask if c < w else 0
            r = v >> c if c < w else 0
            hl = (hl * 31 + l + 1) & M64
            hr = (hr * 31 + r + 1) & M64
    print("u%d %d %d" % (w, hl, hr))

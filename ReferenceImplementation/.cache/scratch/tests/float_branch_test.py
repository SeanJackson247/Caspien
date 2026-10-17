"""Independent model for float_branch_test.caspien (all values are exact binary fractions, so f32 == f64 here)."""
import operator as o
ops = [o.gt, o.lt, o.ge, o.le, o.eq, o.ne]
cnt = w = 0
res = []
for _ in range(2):
    x = y = 0.0
    for i in range(40):
        for k, op in enumerate(ops):
            if op(x, y): cnt += 1 << k
        w += 3 if x > 2.5 else 1
        x += 0.25
        y = x if i % 3 == 0 else y + 0.125
    k = 0
    while x > 0.0:
        x -= 0.5; k += 1
    res.append(k)
print(cnt, w, res[0], res[1])

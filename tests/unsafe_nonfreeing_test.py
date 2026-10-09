# model for unsafe_nonfreeing_test: bits() is the identity on a u64
v = 1000
print("PASS param after memcopy", v + v - v)
w = 999
print("PASS two helpers", w + (w + 1) + w)
k = 42
print("PASS match after memcopy", k + k - 42)

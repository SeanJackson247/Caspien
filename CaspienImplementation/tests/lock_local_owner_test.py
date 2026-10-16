# model for lock_local_owner_test: five accumulations of k*k (k = 1..5) under a lock, then reads
s = sum(k * k for k in range(1, 6))
print("PASS sum of squares", s)
print("PASS count", 5)
print("PASS inner owns local", 21)
print("PASS handle usable after the lock", s)

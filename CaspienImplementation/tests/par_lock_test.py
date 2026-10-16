# model for par_lock_test: ten polled results x*11 for x = 1..10, and eight dropped handles whose threads all finished before the scope ended
print("PASS results", sum(x * 11 for x in range(1, 11)))
print("PASS dropped handles waited", 8)

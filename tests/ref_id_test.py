# Model for ref_id_test.caspien. Each line is derived from the program's loop structure, not copied from a run.
N = 2000
old_alive = 0                      # a ref taken to the freed object never sees a later object, whatever its address
new_alive = N                      # the ref taken to the current object is alive every round
value_sum = sum(i + 1 for i in range(N))
chain = sum(i + 1 for i in range(100))
print("PASS aba old refs alive", old_alive)
print("PASS aba new refs alive", new_alive)
print("PASS aba value sum", value_sum)
print("PASS stale before reuse", 0)     # probe 1.1: dead before the address is reused
print("PASS stale after reuse", 0)      # and still dead after (the old design printed 223 here)
print("PASS equal same object", 1)
print("PASS equal other object", 0)
print("PASS equal null", 1)
print("PASS equal some vs ref", 1)
print("PASS equal after free", 1)      # both ids name the same (dead) object: still equal
print("PASS chain sum", chain)
print("PASS chain via functions", chain)
print("PASS alive before shrink", 50)
print("PASS dead after shrink", 0)
print("PASS raw conversion", 1)
print("PASS leak", 0)

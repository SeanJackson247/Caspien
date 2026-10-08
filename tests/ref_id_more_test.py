# Model for ref_id_more_test.caspien: values written in the program's constructors, summed and counted here.
vals = [10, 20, 30]
print("PASS assume read", 11)
print("PASS dyn refs alive", len(vals))
print("PASS dyn refs sum", sum(vals))
print("PASS dyn refs dead", 0)          # all three owners are consumed (freed) before the second pass
print("PASS clone keeps ref", 22)
print("PASS generic link", 33)
print("PASS leak", 0)

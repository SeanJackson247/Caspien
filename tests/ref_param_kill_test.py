# Independent model of ref_param_kill_test: plain arithmetic mirrored on the program's operations.
node_a = 10
print("PASS read before unsafe", node_a)
print("PASS rematch after unsafe", node_a + 1)
print("PASS unrelated type freed", 5)
counter = 0
for _ in range(3):
    counter += 1
print("PASS self rematch", counter)
k = 0
k += 4
print("PASS local resize", k)

# Model: the first body reads 111 before the free; h.p is re-pointed at a node holding 5; after that node is freed the alive check fails and the body is skipped.
print("PASS read then free", 111)
print("PASS member re-pointed", 5)
print("PASS dead member skipped", 0)

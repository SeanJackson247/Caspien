# model for lock_owns_member_test: the counter holds 7 when read back through the slot (a clone of c), then a fresh counter holds 9
print("PASS moved in and out", 7)
print("PASS slot empty again", 0)
print("PASS fresh new inside", 9)

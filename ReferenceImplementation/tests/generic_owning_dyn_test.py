"""Independent model for generic_owning_dyn_test.caspien. Live ghost-table entries: one per box plus one per dynarray block."""
sq = [i * i for i in range(10)]
print("filled %d %d" % (sum(sq), 10 + 1))            # 10 boxes + the array block
print("grown %d %d" % (sum(sq), 10 + 1))             # resize moves the block (re-registered): still 10 boxes + 1 block
print("cloned %d %d" % (sum(sq), 2 * (10 + 1)))      # the clone owns copies of the 10 boxes and its own block
print("shrunk %d %d" % (sum(sq[:5]), 5 + 1 + 10 + 1))  # 5 boxes dropped from the cut-off tail
print("leak 0")

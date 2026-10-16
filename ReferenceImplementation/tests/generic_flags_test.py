# Model of generic_flags_test: an `assume match` block is NOT conditional at run time (the condition is only assumed), so the block always runs:
# q = n + 100, then +1 when n fits u8.
def pick(n):
    q = n + 100
    if n < 256:
        q += 1
    return q
print("PASS u8 instance", pick(5))
print("PASS u64 instance", pick(300))
print("PASS assumed, not tested", pick(0))

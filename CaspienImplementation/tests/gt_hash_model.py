#!/usr/bin/env python3
"""Replays the operation sequence of tests/gt_hash_test.caspien and prints the expected hits / live counts."""
seed, hits, s = 12345, 0, set()
for _ in range(40000):
    seed = (seed * 1103515245 + 12345) & 2147483647
    k, op = (seed >> 8) % 3000, seed % 3
    if op == 0: s.add(k)
    elif op == 1: s.discard(k)
    elif k in s: hits += 1
print("hits=%d live=%d" % (hits, len(s)))

#!/usr/bin/env python3
"""Replays the operation sequence of tests/gt_id_test.caspien with plain sets and dicts and prints the expected counts."""
seed = 12345
reg = set()          # registered keys
ids = {}             # key -> id given in the current registration
last = {}            # key -> last id returned for this key (any registration)
nxt = 0
hits = refs = idsum = resolved = dead = 0
for _ in range(60000):
    seed = (seed * 1103515245 + 12345) & 2147483647
    k, op = (seed >> 8) % 3000, seed % 5
    if op == 0:
        reg.add(k)
    elif op == 1:
        reg.discard(k)
        ids.pop(k, None)
    elif op == 2:
        if k in reg:
            hits += 1
    elif op == 3:
        if k in reg:
            if k not in ids:
                nxt += 1
                ids[k] = nxt
            refs += 1
            idsum += ids[k]
            last[k] = ids[k]
    else:
        lid = last.get(k, 0)
        if lid != 0 and k in reg and ids.get(k) == lid:
            resolved += 1
        else:
            dead += 1
print("hits=%d refs=%d idsum=%d live=%d issued=%d resolved=%d dead=%d" % (hits, refs, idsum, len(reg), nxt, resolved, dead))

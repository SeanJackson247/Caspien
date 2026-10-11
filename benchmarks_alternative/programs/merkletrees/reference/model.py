#!/usr/bin/env python3
"""Reference model for the Merkle tree benchmark (hashlib). Usage: model.py [N]  -> prints '<root hex> <verified> <rejected>'. See ../SPEC.md."""
import hashlib, struct, sys
DEFAULT_N = 140000


def run(n):
    x = 12345
    def nxt():
        nonlocal x
        x = (x * 1664525 + 1013904223) & 0xFFFFFFFF
        return x
    sha = hashlib.sha256
    data = []
    level = []
    for i in range(n):
        d = nxt()
        data.append(d)
        level.append(sha(struct.pack("<QQ", d, i)).digest())
    levels = [level]
    while len(level) > 1:
        nxt_level = []
        for j in range((len(level) + 1) // 2):
            l = level[2 * j]
            r = level[2 * j + 1] if 2 * j + 1 < len(level) else l
            nxt_level.append(sha(l + r).digest())
        level = nxt_level
        levels.append(level)
    root = level[0]
    verified = rejected = 0
    for p in range(n // 2):
        idx = nxt() % n
        sibs = []
        pos = idx
        for lv in levels[:-1]:
            sb = pos + 1 if pos % 2 == 0 else pos - 1
            if sb >= len(lv):
                sb = pos
            sibs.append(lv[sb])
            pos //= 2
        d = data[idx] + (1 if p % 4 == 3 else 0)
        cur = sha(struct.pack("<QQ", d, idx)).digest()
        pos = idx
        for sb in sibs:
            cur = sha(cur + sb).digest() if pos % 2 == 0 else sha(sb + cur).digest()
            pos //= 2
        if cur == root:
            verified += 1
        else:
            rejected += 1
    return "%s %d %d" % (root.hex(), verified, rejected)


if __name__ == "__main__":
    print(run(int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_N))

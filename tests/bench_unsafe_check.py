#!/usr/bin/env python3
"""Guards the benchmark sources: an `*unsafe*` Caspien benchmark variant must not contain runtime bounds proofs (`match i in a{`, `match i into a{`) or
alive proofs on array elements (`match Some(a[i].n){`): they can never fail there and cost a branch (or a ghost-table probe) each; `assume match` is the
unsafe form. `match Some(x){..} else{..}` on a plain local is a real null test and stays."""
import glob, os, re, sys
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
bad = []
n = 0
for f in sorted(glob.glob(os.path.join(ROOT, "benchmarks", "*", "caspien", "*unsafe*.caspien"))):
    n += 1
    for k, line in enumerate(open(f).read().split("\n"), 1):
        s = line.strip()
        if re.match(r"^match [A-Za-z_0-9 +*()-]+ (in|into) [A-Za-z_.]+\{$", s) or re.match(r"^match Some\([A-Za-z_0-9]+\[[^\]]*\][^)]*\)\{$", s):
            bad.append("%s:%d: %s" % (os.path.relpath(f, ROOT), k, s))
for b in bad:
    print("FAIL", b)
print("%s bench_unsafe_check: %d unsafe benchmark sources, %d runtime proofs that belong to `assume match`" % ("PASS" if not bad else "FAIL", n, len(bad)))
sys.exit(1 if bad else 0)

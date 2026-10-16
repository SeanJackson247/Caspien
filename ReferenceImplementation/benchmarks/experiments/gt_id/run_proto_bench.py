#!/usr/bin/env python3
"""Compiles gt_proto_a / _z / _e with the benchmarks' FULL and OFF optimiser configurations (harness scratch tree) and prints the best of REPS runs per
operation.   python3 experiments/gt_id/run_proto_bench.py [N=100000] [REPS=3]   (run from benchmarks/; needs the repository's stdlib)"""
import os, re, shutil, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from harness import caspien, core
n = sys.argv[1] if len(sys.argv) > 1 else "100000"
reps = int(sys.argv[2]) if len(sys.argv) > 2 else 3
subprocess.run([sys.executable, os.path.join(HERE, "make_proto_bench.py")], check=True)
tree = caspien.Tree()
try:
    for cfg, kv in (("full", caspien.FULL), ("off", caspien.OFF)):
        best = {}
        for v in ("a", "z", "e"):
            rel = tree.prepare(os.path.join(HERE, "gt_proto_%s.caspien" % v), kv)
            # the prototypes import "../../stdlib/..." (two levels below the tree root): put them there
            two = os.path.join(tree.dir, "x", "y"); os.makedirs(two, exist_ok=True)
            shutil.copy(os.path.join(HERE, "gt_proto_%s.caspien" % v), os.path.join(two, "_gp.caspien"))
            exe = os.path.join(tree.dir, "gtp_%s_%s" % (v, cfg))
            c = tree.compile_command(os.path.join("x", "y", "_gp.caspien"), exe)
            r = subprocess.run(c["cmd"], cwd=c["cwd"], env=c["env"], capture_output=True, text=True)
            if not os.path.exists(exe):
                print(cfg, v, "COMPILE FAILED", r.stdout[-300:], r.stderr[-300:]); continue
            for _ in range(reps):
                out = subprocess.run([exe], capture_output=True, text=True, env=dict(os.environ, GT_N=n)).stdout
                for m in re.finditer(r"^(.{28}) +(\d+) ns/op", out, flags=re.M):
                    key = (v, m.group(1).strip()); best[key] = min(best.get(key, 1 << 60), int(m.group(2)))
        print("\n== %s, N=%s (best of %d, ns/op, CPU time)" % (cfg, n, reps))
        print("%-28s %8s %8s %8s" % ("", "A", "E noid", "E ids"))
        for o in ["register", "alive_check (hit)", "ref_id (first, creates id)", "ref_id (again)", "ref_resolve (live id)", "moved (unregister)"]:
            print("%-28s %8s %8s %8s" % (o, best.get(("a", o), "-"), best.get(("z", o), "-"), best.get(("e", o), "-")))
finally:
    tree.close()

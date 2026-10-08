#!/usr/bin/env python3
"""Compiles gt_proto_a / gt_proto_e under the shipped, allon and aggressive optimizer presets (Linux scratch tree of tests/run_tests.py) and prints
the best of REPS runs per operation. Usage: run_proto_bench.py [N=100000] [REPS=3]"""
import os, re, subprocess, sys
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tests")); sys.path.insert(0, os.path.join(ROOT, "benchmarks"))
import run_tests as R
n = sys.argv[1] if len(sys.argv) > 1 else "100000"
reps = int(sys.argv[2]) if len(sys.argv) > 2 else 3
R.sync()
subprocess.run(["python3", os.path.join(ROOT, "benchmarks/gt_id/make_proto_bench.py")], check=True)
os.makedirs(os.path.join(R.SCRATCH, "benchmarks/gt_id"), exist_ok=True)
for f in ("gt_proto_a.caspien", "gt_proto_e.caspien", "gt_proto_z.caspien"):
    subprocess.run(["cp", os.path.join(ROOT, "benchmarks/gt_id", f), os.path.join(R.SCRATCH, "benchmarks/gt_id", f)], check=True)
pre = R.presets()
for cfg in ("shipped", "allon", "aggressive"):
    open(os.path.join(R.SCRATCH, "toolchain.config"), "w").write(pre[cfg])
    best = {}
    for v in ("a", "z", "e"):
        exe = "/tmp/claude-0/gtp_%s_%s" % (v, cfg)
        r = subprocess.run(["java", "-cp", ".", "Compiler", "--no-cache", "-i", "benchmarks/gt_id/gt_proto_%s.caspien" % v, exe], cwd=R.SCRATCH,
                           capture_output=True, text=True, env={k: x for k, x in os.environ.items() if k != "JAVA_TOOL_OPTIONS"})
        if not os.path.exists(exe):
            print(cfg, v, "COMPILE FAILED", r.stdout[-300:], r.stderr[-300:]); continue
        for _ in range(reps):
            out = subprocess.run([exe], capture_output=True, text=True, env=dict(os.environ, GT_N=n)).stdout
            for m in re.finditer(r"^(.{28}) +(\d+) ns/op", out, flags=re.M):
                key = (v, m.group(1).strip()); best[key] = min(best.get(key, 1 << 60), int(m.group(2)))
    print("\n== %s, N=%s (best of %d, ns/op, CPU time)" % (cfg, n, reps))
    ops = ["register", "alive_check (hit)", "ref_id (first, creates id)", "ref_id (again)", "ref_resolve (live id)", "moved (unregister)"]
    print("%-28s %8s %8s %8s" % ("", "A", "E noid", "E ids"))
    for o in ops:
        print("%-28s %8s %8s %8s" % (o, best.get(("a", o), "-"), best.get(("z", o), "-"), best.get(("e", o), "-")))

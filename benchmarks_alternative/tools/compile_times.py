#!/usr/bin/env python3
"""Compile-time benchmark (nothing is run): every Caspien variant of every program, everything-on config, --no-cache, one build at a time, median of
--builds builds. Writes site/data/compile_times.json; the site shows it beside the other languages' compile times from the results files.
    python3 tools/compile_times.py [--builds 3] [--only sieve lru]"""
import argparse, os, statistics, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from harness import caspien, manifest, paths, results

ap = argparse.ArgumentParser()
ap.add_argument("--builds", type=int, default=3)
ap.add_argument("--only", nargs="*", default=[])
a = ap.parse_args()
tree, rows = caspien.Tree(), []
try:
    for P in manifest.load():
        if a.only and P["id"] not in a.only:
            continue
        for src in sorted({v["src"] for v in P["caspien"]}):
            rel = tree.prepare(os.path.join(paths.PROGRAMS, P["id"], "caspien", src + ".caspien"), caspien.FULL)
            cmd = tree.compile_command(rel, os.path.join(tree.dir, "cas_out"))
            ts = []
            for _ in range(a.builds):
                t0 = time.time()
                r = subprocess.run(cmd["cmd"], cwd=cmd["cwd"], env=cmd["env"], capture_output=True, text=True)
                if r.returncode:
                    print("FAIL %s/%s: %s" % (P["id"], src, (r.stdout + r.stderr)[-300:]))
                    ts = []
                    break
                ts.append(time.time() - t0)
            if ts:
                rows.append({"program": P["id"], "variant": src, "compile_s": statistics.median(ts), "runs": ts})
                print("%-14s %-28s %6.2fs" % (P["id"], src, rows[-1]["compile_s"]), flush=True)
finally:
    tree.close()
results.write_json(os.path.join(paths.DATA, "compile_times.json"), {"builds": a.builds, "caspien_full": rows})

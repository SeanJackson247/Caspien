#!/usr/bin/env python3
"""Compile-time and executable-size benchmark (nothing is run): every Caspien program (the manifest's variants and the experiments' sources),
everything-on config, --no-cache, one build at a time, median of --builds builds. Writes site/data/compile_times.json; the site shows it beside the
other languages' figures from the results files. With --only the measured programs replace their rows in the existing file.
    python3 tools/compile_times.py [--builds 3] [--only sieve lru recursion]"""
import argparse, glob, os, statistics, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from harness import caspien, manifest, paths, results

OUT = os.path.join(paths.DATA, "compile_times.json")


def sources():
    """[(program, variant, path)]: manifest programs by their listed variants, then experiments/<name>/caspien/*.caspien."""
    out = [(P["id"], src, os.path.join(paths.PROGRAMS, P["id"], "caspien", src + ".caspien")) for P in manifest.load() for src in sorted({v["src"] for v in P["caspien"]})]
    for f in sorted(glob.glob(os.path.join(paths.BENCH, "experiments", "*", "caspien", "*.caspien"))):
        out.append((f.split(os.sep)[-3], os.path.basename(f)[:-8], f))
    return out


ap = argparse.ArgumentParser()
ap.add_argument("--builds", type=int, default=3)
ap.add_argument("--only", nargs="*", default=[])
a = ap.parse_args()
tree, rows = caspien.Tree(), []
exe = os.path.join(tree.dir, "cas_out")
try:
    for prog, src, path in sources():
        if a.only and prog not in a.only:
            continue
        cmd = tree.compile_command(tree.prepare(path, caspien.FULL), exe)
        ts, size = [], 0
        for _ in range(a.builds):
            t0 = time.time()
            r = subprocess.run(cmd["cmd"], cwd=cmd["cwd"], env=cmd["env"], capture_output=True, text=True)
            if r.returncode:
                print("FAIL %s/%s: %s" % (prog, src, (r.stdout + r.stderr)[-300:]))
                ts = []
                break
            ts.append(time.time() - t0)
            size = os.path.getsize(exe)
        if ts:
            rows.append({"program": prog, "variant": src, "compile_s": statistics.median(ts), "runs": ts, "size_bytes": size})
            print("%-14s %-28s %6.2fs %9d B" % (prog, src, rows[-1]["compile_s"], size), flush=True)
finally:
    tree.close()
if a.only and os.path.exists(OUT):
    old = results.read(OUT)["caspien_full"]
    done = {r["program"] for r in rows}
    rows = [r for r in old if r["program"] not in done] + rows
results.write_json(OUT, {"builds": a.builds, "caspien_full": rows})

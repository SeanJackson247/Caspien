#!/usr/bin/env python3
"""Per-program tuning of the "specific" build: from the FULL config flip one switch at a time, keep a flip only when the program's Caspien variants get
clearly faster (geometric mean of the paired specific/full ratios below --keep). Each trial is one `harness run --caspien-only` with the candidate in
BENCH_SPECIFIC_JSON, so both builds are measured in the same run. Result: harness/specific.json. One at a time (shared scratch files).
    python3 tools/tune_specific.py [--only sieve lru] [--runs 3] [--keep 0.97]"""
import argparse, json, math, os, subprocess, sys
BENCH = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BENCH)
from harness import paths, results

PROGS = ["nbody", "fannkuchredux", "spectralnorm", "sieve", "strings", "graph", "sorting", "binarytrees", "mandelbrot", "fasta", "knucleotide", "lru", "merkletrees", "json_serde"]
FLIPS = [("jcc-padding", "off"), ("bmi2", "off"), ("hoist-array-bases", "off"), ("variables-in-alloc-functions", "off"),
         ("variables-in-arg-registers", "off"), ("loop-unrolling", "balanced"), ("function-inlining", "balanced")]


def trial(prog, overrides, runs):
    env = dict(os.environ, BENCH_SPECIFIC_JSON=json.dumps(overrides), JAVA_TOOL_OPTIONS="")
    subprocess.run([sys.executable, "-m", "harness", "run", prog, "--caspien-only", "--runs", str(runs), "--no-leak-check"], cwd=BENCH, env=env,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    rows = results.read(results.result_path(prog, quick=True))["results"]
    tag = lambda s: {r["label"].rsplit(" · ", 1)[0]: r for r in rows if r["label"].endswith(" · " + s)}
    full, spec = tag("full"), tag("specific")
    rs = [spec[k]["time_s"] / full[k]["time_s"] for k in spec if k in full and spec[k].get("ok", True) and full[k].get("ok", True)]
    return (math.exp(sum(map(math.log, rs)) / len(rs)) if rs else None), rs


ap = argparse.ArgumentParser()
ap.add_argument("--only", nargs="*", default=[])
ap.add_argument("--runs", type=int, default=5)
ap.add_argument("--keep", type=float, default=0.97)
a = ap.parse_args()
result = json.load(open(paths.SPECIFIC)) if os.path.exists(paths.SPECIFIC) else {}
for prog in PROGS:
    if a.only and prog not in a.only:
        continue
    best, best_ratio = {}, 1.0
    for sw, val in FLIPS:
        cand = dict(best, **{sw: val})
        g, rs = trial(prog, cand, a.runs)
        print("%-14s %-30s %-9s geomean %s  %s" % (prog, sw, val, "%.3f" % g if g else "-", " ".join("%.2f" % x for x in rs)), flush=True)
        if g is not None and g < best_ratio * a.keep:
            best, best_ratio = cand, g
    result.pop(prog, None)
    if best:
        result[prog] = best
    print("=> %s: %s (ratio %.3f)" % (prog, best or "no flip clearly helps", best_ratio), flush=True)
    json.dump(result, open(paths.SPECIFIC, "w"), indent=1, sort_keys=True)

#!/usr/bin/env python3
"""
Per-program tuning of the "specific" benchmark build: starting from the FULL config, flip one switch at a time and keep the flip only when the
program's Caspien variants get clearly faster (geometric mean of the paired specific/full time ratios below KEEP). Every trial is one
`run_all.py --caspien-only --only <prog>` run with the candidate overrides in BENCH_SPECIFIC_JSON, so full and specific are built and measured
in the same run (paired). The result is written to benchmarks/specific.json ({program: {switch: value}}; programs with no keeper are absent
and get no "specific" row). Run one at a time: it uses the shared scratch tree.

    python3 benchmarks/tune_specific.py [--only sieve lru] [--runs 3] [--keep 0.97]
"""
import argparse, json, math, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "nbody"))
import bench as B  # noqa: E402

PROGS = ["nbody", "fannkuchredux", "spectralnorm", "sieve", "strings", "graph", "sorting", "binarytrees", "mandelbrot", "fasta", "knucleotide", "lru", "merkletrees", "json_serde"]
FLIPS = [("jcc-padding", "off"), ("bmi2", "off"), ("hoist-array-bases", "off"), ("variables-in-alloc-functions", "off"),
         ("variables-in-arg-registers", "off"), ("loop-unrolling", "balanced"), ("function-inlining", "balanced")]


def trial(prog, ov, runs):
    env = dict(os.environ, BENCH_SPECIFIC_JSON=json.dumps(ov), JAVA_TOOL_OPTIONS="")
    subprocess.run([sys.executable, os.path.join(HERE, "run_all.py"), "--caspien-only", "--only", prog, "--runs", str(runs)], env=env,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    path = os.path.join(HERE, prog, "results.quick.json")
    rows = json.load(open(path))["results"]
    full = {r["label"].rsplit(" · ", 1)[0]: r for r in rows if r["label"].endswith("· full")}
    spec = {r["label"].rsplit(" · ", 1)[0]: r for r in rows if r["label"].endswith("· specific")}
    rs = [spec[k]["time_s"] / full[k]["time_s"] for k in spec if k in full and spec[k].get("ok", True) and full[k].get("ok", True)]
    return math.exp(sum(math.log(x) for x in rs) / len(rs)) if rs else None, rs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", default=[])
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--keep", type=float, default=0.97)
    a = ap.parse_args()
    path = os.path.join(HERE, "specific.json")
    result = json.load(open(path)) if os.path.exists(path) else {}
    for prog in PROGS:
        if a.only and prog not in a.only:
            continue
        best, best_ratio = {}, 1.0
        for sw, val in FLIPS:
            cand = dict(best, **{sw: val})
            # a trial with the current best set is the reference: ratio of cand vs full, compared with best's own ratio
            g, rs = trial(prog, cand, a.runs)
            print("%-14s %-30s %-6s geomean %s  %s" % (prog, sw, val, "%.3f" % g if g else "-", " ".join("%.2f" % x for x in rs)), flush=True)
            if g is not None and g < best_ratio * a.keep:
                best, best_ratio = cand, g
        if best:
            result[prog] = best
        else:
            result.pop(prog, None)
        print("=> %s: %s (ratio %.3f)" % (prog, best or "no flip clearly helps", best_ratio), flush=True)
        json.dump(result, open(path, "w"), indent=1, sort_keys=True)


if __name__ == "__main__":
    main()
